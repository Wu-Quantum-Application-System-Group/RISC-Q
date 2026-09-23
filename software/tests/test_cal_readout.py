"""The readout-completeness pieces of spec 14 F2: the confusion correction (`rcorr`, qcal's
`rcorr_cmat`), the 3-level `ReadoutFidelity`'s guards, and — in co-sim — that the three real preps
actually reach levels 0/1/2.

`rcorr` is the one piece of readout analysis with an exact answer — push a known population vector
through a known confusion matrix and the correction has to return it bit-for-bit (up to float error),
so these are equalities, not tolerances. That is the whole 3×3 confusion ARITHMETIC, host-pure, and
it is why the co-sim half no longer measures a matrix (specs/software-test-refactor/02 §3.6): the
old gate spent `relax = 8000` × 32 shots × 3 preps — the second-worst test in the suite — to
rediscover, through shot noise, that a diagonal-dominant matrix inverts.

What is left for the simulator is the one claim the host cannot make: that the three PRODUCTION
prep sequences — idle, the GE π (`ReadoutCalibration`'s `Cond(prep, X90·X90)`), and
`ReadoutFidelity._run_3level`'s GE π + EF π — really drive the qutrit to levels 0, 1 and 2. That is
an **L2 state probe** (01 §4): |2⟩ is invisible to the hardware `res` bit (one threshold, two
outcomes), so it is read off `drv.sim.model_state()["populations"]`, one noiseless shot per prep
against the analytic target.

universal-cal V6: the classifier layer moved to `riscq.cal.analysis.classifier` and the readout
classes onto the universal base, so the imports are repointed and the two L2 probes now run the
classes' own sequence through `riscq.cal.experiment.Experiment` (`_rawiq_prog` / `_ef_prep_prog`
are gone with the kernel-per-class code). Nothing was retired from this module: the classes'
public behaviour is covered by tests/test_cals_readout.py, the timing rules by
tests/test_sequence.py, and the old-vs-new signal parity is recorded at git commit 347f749.
"""

import math

import numpy as np
import pytest

from riscq.cal import Classifier, Config, ReadoutCalibration, ReadoutFidelity
from riscq.cal.analysis.classifier import (ClassifierN, _gmm_fit, _gmm_predict, _snr, rcorr,
                                           res_fidelity)
from riscq.cal.base import GATE_ENV, gate_sigma
from riscq.cal.cals.readout import PREP
from riscq.cal.experiment import Experiment
from riscq.cal.measure import Measure
from riscq.cal.sequence import Gate
from riscq.pulses import Pulse, units

# The planted qutrit's two carriers, an anharmonicity of exactly 4096 codes (100 MHz on this build)
# apart. `ThreeLevelModel` picks which transition a batch drives by demodulating the gate DAC against
# BOTH carriers over that batch's 16 samples and taking the larger — and 16 samples is a very short
# window: at the old 50/40 MHz pair (Δ = 410 codes) the two demods came back within 1.6 % of each
# other and the winner flipped batch to batch, so the "EF π" also drove GE and the |2> prep topped
# out at 0.49. A separation of 4096 codes puts exactly one full turn of the difference frequency in
# a batch, so each carrier's demod is EXACTLY zero against the other transition and both preps are
# the textbook gates their planted rates say they are.
F_GE, F_EF = 150e6, 50e6
GE_AMP, EF_AMP = 0.5, 0.5


def _clusters(s0: complex, s1: complex, n: int = 400, sigma: float = 0.05, seed: int = 3):
    """Two IQ clouds around planted complex responses — the shape a readout actually produces."""
    rng = np.random.default_rng(seed)
    return [np.column_stack([s.real + rng.normal(0, sigma, n), s.imag + rng.normal(0, sigma, n)])
            for s in (s0, s1)]


def _proposed_phase(iq0, iq1) -> float:
    """ReadoutCalibration's rule: rotate the |0>->|1> cluster axis onto +real."""
    return -math.atan2(*(iq0.mean(0) - iq1.mean(0))[::-1])


def test_res_fidelity_is_perfect_when_the_two_responses_are_antipodal():
    """A flat readout tone puts |1> exactly pi out of phase from |0>, so the rotated midpoint lands
    on the imaginary axis and the hardware's hard-zero threshold sits between the clusters."""
    iq0, iq1 = _clusters(0.6 + 0.8j, -0.6 - 0.8j)
    assert res_fidelity(iq0, iq1, _proposed_phase(iq0, iq1)) > 0.99


def test_res_fidelity_is_perfect_for_a_dispersive_readout_probed_at_f_r():
    """At f_r the two Lorentzian responses are complex CONJUGATES, so their difference is purely
    imaginary and their sum purely real — rotating the axis onto +real again centres the midpoint."""
    s0 = 1.0 / (1.0 + 0.6j)
    iq0, iq1 = _clusters(s0, s0.conjugate(), sigma=0.02)
    assert res_fidelity(iq0, iq1, _proposed_phase(iq0, iq1)) > 0.99


def test_res_fidelity_collapses_off_resonance_even_though_the_clusters_are_well_separated():
    """spec 15 §9.7's finding: probed OFF f_r the responses are neither antipodal nor conjugate, so
    a ROTATION cannot move the midpoint off the real axis. Both clusters end up on the +real side of
    the hardware's hard-zero threshold and `res` discriminates at chance — while the host
    classifier, which puts its boundary where the data is, is untouched. That is exactly why
    ReadoutCalibration gates on this and not on `separation` alone."""
    chi, kappa, det = 1.2e6, 4.0e6, 1.5e6
    s0 = 1.0 / (1.0 + 2j * (det - chi) / kappa)
    s1 = 1.0 / (1.0 + 2j * (det + chi) / kappa)
    iq0, iq1 = _clusters(s0, s1, sigma=0.02)
    clf = Classifier(iq0, iq1)
    assert clf.separation > 2.0, "the clusters are well separated to a host classifier"
    assert res_fidelity(iq0, iq1, _proposed_phase(iq0, iq1)) < 0.6, \
        "the hard-zero res bit should be at chance here"


# ── the unsupervised GMM classifier (spec 21) ──
#
# The prep label is not the true state at measurement: preparation errs and the qubit decays
# across the prep→readout gap (14.8 % of |1> at the old SEP = 96, spec 15 §9.3), so shots that
# actually read out as |0> carry a |1> label. qcal therefore fits its classifier UNSUPERVISED on
# the pooled shots (labels only seed means_init and score); these tests plant exactly that error
# and check the fit shrugs it off where the labelled statistics measurably do not.

M0, M1 = np.array([1.0, 0.0]), np.array([-1.0, 0.5])     # true cluster centroids, ‖Δ‖ ≈ 2.06


def _decayed_clusters(eps: float, n: int = 400, sigma: float = 0.15, seed: int = 7):
    """The prep-error scenario the GMM cut-over exists for: a fraction `eps` of the |1>-prep shots
    decayed before the window and actually read out as |0> — they sit in the |0> cloud with a |1>
    label. Returns (iq0, iq1) with the decayed shots at the TAIL of iq1."""
    rng = np.random.default_rng(seed)
    k = int(round(eps * n))
    iq0 = rng.normal(M0, sigma, (n, 2))
    iq1 = np.vstack([rng.normal(M1, sigma, (n - k, 2)), rng.normal(M0, sigma, (k, 2))])
    return iq0, iq1


def test_gmm_recovers_the_true_means_under_prep_decay():
    """8 % decay drags the LABELLED |1> mean toward the |0> cloud by ε·dist ≈ 0.16; the fitted
    component mean stays within shot noise (σ/√n ≈ 0.008) of the true centroid."""
    iq0, iq1 = _decayed_clusters(0.08)
    clf = Classifier(iq0, iq1)
    assert np.hypot(*(clf.m0 - M0)) < 0.05
    assert np.hypot(*(clf.m1 - M1)) < 0.05
    assert np.hypot(*(iq1.mean(0) - M1)) > 0.1     # the labelled mean the old classifier trained on


def test_gmm_assigns_decayed_shots_to_the_cluster_they_landed_in():
    """The 32 decayed shots ARE |0> at measurement; the confusion's row-1 off-diagonal becomes the
    honest prep error instead of being absorbed into a shifted threshold."""
    iq0, iq1 = _decayed_clusters(0.08)
    clf = Classifier(iq0, iq1)
    assert np.all(clf.classify(iq1[-32:]) == 0)
    conf = clf.confusion()
    assert abs(conf[1, 0] - 0.08) < 0.02
    assert conf[0, 0] > 0.99


def test_fitted_separation_is_immune_to_the_decay_inflated_variance():
    """The labelled |1> cloud is BIMODAL under decay — its variance carries an ε(1−ε)·dist² term
    that depresses the labelled-cloud `_snr` by ~2× here, exactly the statistic Separation used to
    argmax. The fitted σ is the within-cluster width, so the fitted SNR stays at its clean value."""
    iq0, iq1 = _decayed_clusters(0.08)
    clean = Classifier(*_decayed_clusters(0.0)).separation
    clf = Classifier(iq0, iq1)
    assert abs(clf.separation - clean) / clean < 0.05
    assert clf.separation > 1.5 * _snr(iq0, iq1)


def test_gmm_fit_is_deterministic():
    """No RNG anywhere in the fit (unlike sklearn's, which k-means-seeds weights/variances even
    when means_init is given): two fits of the same input are bit-identical."""
    iq0, iq1 = _decayed_clusters(0.08)
    for a, b in zip(_gmm_fit([iq0, iq1]), _gmm_fit([iq0, iq1])):
        assert np.array_equal(a, b)


def test_gmm_matches_sklearn():
    """Dev-env-only parity with the reference implementation qcal actually calls (NOT a package
    dependency): same pooled data, same init → same converged mixture and the same assignments."""
    mixture = pytest.importorskip("sklearn.mixture")
    iq0, iq1 = _decayed_clusters(0.08)
    means, sigmas, weights = _gmm_fit([iq0, iq1])
    x = np.vstack([iq0, iq1])
    g = mixture.GaussianMixture(2, covariance_type="spherical", random_state=0,
                                means_init=np.vstack([iq0.mean(0), iq1.mean(0)]))
    g.fit(x)
    ours, theirs = np.argsort(means[:, 0]), np.argsort(g.means_[:, 0])
    assert np.allclose(means[ours], g.means_[theirs], atol=5e-3)
    assert np.allclose(sigmas[ours], np.sqrt(g.covariances_)[theirs], atol=5e-3)
    agree = float(np.mean(_gmm_predict(x, means, sigmas, weights) == g.predict(x)))
    assert max(agree, 1.0 - agree) > 0.995         # order-insensitive: identical boundaries


def test_classifier_anchor_invariant():
    """The anchor guard's invariant: whatever EM converges to, the component holding the majority
    of the |0>-prep shots is named 0 — including under heavy overlap, where the component order is
    at EM's mercy and qcal's init-anchoring-only scheme could silently flip labels."""
    for s0, s1, sigma in ((0.6 + 0.8j, -0.6 - 0.8j, 0.05),
                          (1.0 + 0.0j, 1.02 + 0.02j, 0.4),      # nearly coincident
                          (0.0 + 0.0j, 0.1 + 0.1j, 0.02)):
        iq0, iq1 = _clusters(s0, s1, sigma=sigma, seed=11)
        clf = Classifier(iq0, iq1)
        assert float(np.mean(clf.classify(iq0) == 0)) >= 0.5


def test_gmm_survives_coincident_clusters():
    """The reg_covar floor: identical (zero-variance) clouds neither NaN nor divide by zero;
    separation honestly reads 0."""
    pts = np.zeros((64, 2))
    clf = Classifier(pts, pts.copy())
    assert np.all(np.isfinite(clf.means)) and np.all(np.isfinite(clf.sigmas))
    assert np.isfinite(clf.separation) and clf.separation < 0.5


def test_fitted_snr_argmax_matches_the_labelled_argmax_on_clean_sweeps():
    """Separation's per-point statistic moved from the labelled-cloud `_snr` to the fitted GMM SNR
    (spec 21 §2.5): on clean data the two numbers track closely and the DECISION — the argmax over
    the sweep — must agree exactly."""
    dists = [0.6, 1.2, 2.0, 1.4, 0.8]
    fitted, labelled = [], []
    for i, d in enumerate(dists):
        iq0, iq1 = _clusters(0j, complex(d), n=200, sigma=0.15, seed=20 + i)
        fitted.append(Classifier(iq0, iq1).separation)
        labelled.append(_snr(iq0, iq1))
    assert int(np.argmax(fitted)) == int(np.argmax(labelled)) == 2


def test_plot_draws_the_qcal_figure():
    """The qcal readout figure (spec 21 §2.4), smoke-tested on the Agg backend: one panel per
    qubit carrying the shot density (hexbin), the decision regions (contourf) and the 0/1 legend;
    `raw=True` swaps the hexbin for the prep-labelled scatter."""
    mpl = pytest.importorskip("matplotlib")
    mpl.use("Agg")
    from matplotlib.collections import PathCollection

    rc = ReadoutCalibration(None, [0, 1], shots=8)
    for q in rc.qubits:
        rc.classifier[q] = Classifier(*_decayed_clusters(0.05, n=64, seed=5 + q))
    fig = rc.plot()
    assert len(fig.axes) == 2                    # one panel per qubit
    for ax in fig.axes:
        assert len(ax.collections) >= 2          # hexbin + decision regions
        assert ax.get_legend() is not None
    raw = rc.plot(raw=True)
    assert any(isinstance(c, PathCollection) for c in raw.axes[0].collections)


def test_rcorr_inverts_a_planted_confusion():
    """The defining property: p_meas = p_true @ cmat (row = PREPARED, col = MEASURED), so correcting
    p_meas returns p_true exactly. A 3-level matrix with realistic leakage between every pair."""
    cmat = np.array([[0.97, 0.02, 0.01],       # |0> read as 0/1/2
                     [0.04, 0.93, 0.03],
                     [0.02, 0.08, 0.90]])
    assert np.allclose(cmat.sum(1), 1.0)
    p_true = np.array([0.20, 0.50, 0.30])
    p_meas = p_true @ cmat
    assert not np.allclose(p_meas, p_true)      # the confusion really moved the populations
    assert np.allclose(rcorr(p_meas, cmat), p_true)


def test_rcorr_is_a_no_op_on_a_perfect_readout():
    p = np.array([0.1, 0.6, 0.3])
    assert np.allclose(rcorr(p, np.eye(3)), p)


def test_rcorr_corrects_a_stack_of_populations():
    """A whole sweep at once: one row per point (a Leakage scan's P(|0>,|1>,|2>) per phase)."""
    cmat = np.array([[0.95, 0.05], [0.10, 0.90]])
    p_true = np.array([[1.0, 0.0], [0.5, 0.5], [0.0, 1.0], [0.25, 0.75]])
    got = rcorr(p_true @ cmat, cmat)
    assert got.shape == p_true.shape and np.allclose(got, p_true)


def test_rcorr_does_not_clip_an_unphysical_correction():
    """A population the confusion cannot explain corrects OUT of [0, 1] — reported, not squashed, so a
    miscalibrated matrix is visible rather than silently plausible."""
    cmat = np.array([[0.9, 0.1], [0.1, 0.9]])
    out = rcorr(np.array([1.0, 0.0]), cmat)     # cleaner than the matrix allows
    assert out[0] > 1.0 and out[1] < 0.0


def test_rcorr_rejects_a_mismatched_matrix():
    with pytest.raises(AssertionError, match="does not match"):
        rcorr(np.array([0.5, 0.5]), np.eye(3))


def test_three_level_fidelity_requires_a_pretrained_classifier():
    """Confusing the very shots a classifier was fitted to measures the fit, not the readout — so the
    3-level mode takes the classifier from outside (the EFAmplitude/EFPhase convention) and refuses to
    run without one."""
    cfg = Config()
    with pytest.raises(AssertionError, match="pre-trained"):
        ReadoutFidelity(cfg, 0, n_levels=3)
    with pytest.raises(AssertionError, match="n_levels"):
        ReadoutFidelity(cfg, 0, n_levels=4)
    clf = ClassifierN([np.zeros((4, 2)), np.ones((4, 2)), 2 * np.ones((4, 2))])
    assert ReadoutFidelity(cfg, 0, n_levels=3, classifier=clf).classifier is clf
    assert ReadoutFidelity(cfg, 0).classifier is None         # the 2-level path needs none


# ── L2: the three REAL preps reach levels 0 / 1 / 2 (spec 14 F2) ──

def _s(batches, m):
    return float(batches) / m.params.dsp_freq_hz


def _cfg3(m, q=0):
    """A qutrit Config: GE + EF gates and a 3-level readout tone. The EF X is a real π in {|1>, |2>},
    which is what the |2> prep plays after the GE π.

    `reset/relax` is at its floor: an L2 probe re-issues `set_model`, which rebuilds the qutrit in
    |0> in ZERO simulated cycles, so the 8000-batch idle head the old shot-statistics version needed
    to reset between shots buys nothing here (01 §4.2)."""
    c = Config()
    c[f"qubit/{q}/freq"] = F_GE
    c[f"qubit/{q}/x90/amp"] = GE_AMP
    c[f"qubit/{q}/T1"] = _s(120, m)
    c[f"qubit/{q}/EF/freq"] = F_EF
    c[f"qubit/{q}/EF/x90/amp"] = EF_AMP
    c[f"qubit/{q}/EF/x/amp"] = EF_AMP
    c[f"readout/{q}/freq"] = float(units.demod_code_to_freq(2048, m.params))
    c[f"readout/{q}/amp"] = 0.5
    c[f"readout/{q}/dur"] = _s(56, m)
    c[f"readout/{q}/demod/dur"] = _s(40, m)
    c["reset/relax"] = _s(8, m)
    return c


def _qutrit(m):
    """The planted qutrit: a GE Rabi rate making the two-X90 prep an exact π and an EF rate making the
    EF X an exact π in {|1>, |2>} — so each prep is a textbook gate and the expected populations are
    the ideal ones. 01 §4.6: no decay, no noise, no collapse (the probe reads the state, not shots)."""
    ge = gate_sigma(m, Pulse(GATE_ENV, freq_hz=F_GE, amp=GE_AMP), F_GE, units._amp_code(GE_AMP))
    ef = gate_sigma(m, Pulse(GATE_ENV, amp=EF_AMP), F_EF, units._amp_code(EF_AMP))
    return {"kind": "threelevel", "core": 0, "f_ge": F_GE, "f_ef": F_EF,
            "rabi_ge_rad_per_amp": math.pi / (2 * ge), "rabi_ef_rad_per_amp": math.pi / ef,
            "readout_code": 2048, "readout_amp": 18000.0, "init_level": 0}


# One test per IMAGE, because each experiment is its own image and an image load is ~7 k simulated
# batches — the two together overrun the 20 k per-test cap (specs/software-test-refactor/02 §1).
#
# Both replace the halves of a 3 × 32-shot, `relax = 8000` confusion measurement whose |2> row only
# ever reached 0.5. The preps are now pinned to 0.02 instead of to "dominates its row", and the 3×3
# matrix ARITHMETIC — the row-stochastic shape and the `rcorr` inverse — is the host-pure half above.

@pytest.mark.cosim
def test_ge_preps_reach_levels_0_and_1(cosim):
    """L2 (spec 14 F2) — the |0> and |1> rows of the 3-level confusion come from ONE image whose
    `prep` runtime param picks idle or the GE π (`ReadoutCalibration`'s own `Cond(prep, X90·X90)`
    sequence, read RAW). Both must land on the level they name, and the |1> prep must not leak
    into |2>.

    The target is analytic and exact: the GE rate is planted so the two-X90 prep is a π in
    {|0>, |1>}, so the prep is a textbook gate and the populations are 1 at the intended level and 0
    elsewhere. Nothing is fitted and nothing is sampled — |2> is invisible to the `res` bit, so the
    populations come off `model_state()`, one noiseless shot per prep (the Experiment's
    `before`/`after` hooks re-plant the model per rerun, so each point starts from |0> in zero
    simulated cycles)."""
    drv, m = cosim
    q = 0
    cal = ReadoutCalibration(_cfg3(m, q), q, shots=1)
    spec, pops = _qutrit(m), {}
    Experiment(cal.cfg, [q], {q: cal.sequence(q, ())}, {q: ()}, (PREP,), cal.measure(), cal.shots,
               label="ReadoutCalibration").run(
        drv, before=lambda vals: drv.sim.set_model(spec),
        after=lambda vals: pops.__setitem__(int(vals[0]), drv.sim.model_state()["populations"]))
    for prep, want in ((0, [1.0, 0.0, 0.0]), (1, [0.0, 1.0, 0.0])):
        print(f"\n[prep {prep}] populations={np.round(pops[prep], 4).tolist()} want={want}")
        assert pops[prep] == pytest.approx(want, abs=0.02), \
            f"the prep={prep} sequence left the qutrit at {np.round(pops[prep], 4).tolist()}, not {want}"


@pytest.mark.cosim
def test_ef_prep_reaches_level_2(cosim):
    """L2 (spec 14 F2) — the |2> row's prep: a GE π followed by an EF π at the config's EF X
    amplitude, on its own image with the carrier retuned mid-shot. It is the sequence
    `ReadoutFidelity._run_3level` runs for its third cloud, unchanged.

    Both rates are planted exact (GE: the two-X90 prep is a π in {|0>, |1>}; EF: the EF X is a π in
    {|1>, |2>}), so the analytic target is a clean |2> — which also makes it the sharpest available
    statement about the mid-shot GE→EF retune: any slip in WHEN the new carrier takes effect leaves
    population behind in |1>."""
    drv, m = cosim
    q = 0
    ef = [Gate("x90"), Gate("x90"), Gate("EF/x")]        # ReadoutFidelity._run_3level's |2> prep
    drv.sim.set_model(_qutrit(m))
    Experiment(_cfg3(m, q), [q], {q: ef}, {q: ()}, (), Measure.raw(phase=0.0), 1,
               label="ReadoutFidelity3_ef").run(drv)
    pops = drv.sim.model_state()["populations"]
    print(f"\n[prep |2>] populations={np.round(pops, 4).tolist()} want=[0.0, 0.0, 1.0]")
    assert pops == pytest.approx([0.0, 0.0, 1.0], abs=0.02), \
        f"the GE π + EF π prep left the qutrit at {np.round(pops, 4).tolist()}, not |2>"
