"""The calibration suite's RTL half: what the SoC EMITS and what that does to the qubit.

Every calibration batches its whole sweep into ONE run (spec 08): the host preloads the swept knobs
as point columns, the kernel walks them on a fixed grid whose idle head is the T1 relax reset, and
the host fits the self-normalised counts population against the model. Only two of the steps in
that loop need a simulator, and after the T4 migration
(specs/software-test-refactor/02 §3.2) those two are all that is left here:

- **L1** (01 §3) — the emitted stimulus, model OFF: the readout timing knobs and the heralded
  two-window grid, asserted on the converters, plus the batched cal's O(1) seam-op cost.
- **L2** (01 §4) — what that stimulus does to the qubit, read off `drv.sim.model_state()` through
  `tests.probe.Probe`: one shot per point, an ANALYTIC target, no fit and no shot statistics.

Everything else — the fits, the proposals, the (count, kept) decode, the readout cluster/confusion
/window cals, the coarse→fine amplitude arithmetic, the demod-frame fixed point — is host-pure in
`tests/test_cal_host.py` against the analytic responder. The two `--slow` anchors
(`test_amplitude_recovers_rabi`, `test_x6y3_improves_detuned_config`) put the whole loop back
together with real shots and real noise, and are what notice if an L0 responder or an L2 analytic
target ever drifts from the hardware.

Retired with the per-class kernels at universal-cal V6: the frame-bracket word tests (`base.x90_vz`
/ `ef_vz` — the bracket is the sequence compiler's now, pinned in tests/test_sequence.py) and the
three per-kernel herald-geometry tests, which become the ONE `test_herald_grid_geometry` below
because there is now one kernel. The bit-exact equality of every emitted window against those
kernels is recorded at commit 347f749 (tests/test_parity.py, tests/test_parity_2q.py of that tree).
"""

import math
from pathlib import Path

import numpy as np
import pytest

from riscq import run as rq
from riscq.cal import Amplitude, Classifier, Config, Frequency, ReadoutCalibration, calibration_x6y3
from riscq.cal import fits
from riscq.cal.analysis.estimators import rabi_amplitude
from riscq.cal.base import (GATE_CH, GATE_ENV, SEP, batch_timeout, batches, demod_table, gate_pulse,
                            gate_sigma, herald_offset, readout_tables, socmap)
from riscq.cal.gates import resolve
from riscq.lang import Array, ParamTable, compile_kernel, kernel
from riscq.map import LEAD, READOUT_LEAD, SocMap, SocParams
from riscq.pulses import Pulse, envelopes, units
from tests.probe import Probe, rabi_for

F_GE = 50e6                              # planted qubit frequency (freq_code 2048)
RELAX = 1600                             # co-sim relax head (batches) — the Config carries it in SECONDS
QCAL_COSIM_YAML = Path(__file__).with_name("qcal_cosim.yaml")   # the co-sim-scaled qcal tree (spec 13 Q6)
RO_RELAX, RO_T1 = 3200, 600              # the readout cals' budget: relax ≫ T1 ≫ SEP (= LEAD = 96),
#                                          so the |1> prep both SURVIVES to the window and RESETS after it


# ── host unit test: the readout classifier ──

def test_classifier_separates_and_confuses():
    rng = np.random.default_rng(0)
    iq0 = rng.normal([1000, 0], 120, (40, 2))     # |0> cluster on +real
    iq1 = rng.normal([-1000, 0], 120, (40, 2))    # |1> cluster on −real (π flip)
    clf = Classifier(iq0, iq1)
    # qcal's SNR (spec 13 §2): ‖Δmeans‖ / (2σ₀ + 2σ₁) = 2000 / (4 · 120) ≈ 4.2 — a quarter of the
    # plain distance/σ number this used to report (same clusters, qcal's denominator).
    assert clf.separation == pytest.approx(2000 / (4 * 120), rel=0.1)
    conf = clf.confusion()
    assert conf[0, 0] > 0.95 and conf[1, 1] > 0.95   # near-perfect assignment
    assert np.allclose(conf.sum(1), 1.0)


# ── host unit tests: qcal's Amplitude guard + the X90 frame bracket (spec 13 Q4) ──

def test_amplitude_enforces_qcals_n_gates_guard():
    """qcal single_qubit.py:154-158: an amplified sweep has to land back on |0>, so a repeated-X90
    train is a multiple of 4 (each X90 is a quarter period) and a repeated-X train a multiple of 2.
    n_gates=1 is unconstrained."""
    cfg = Config()
    with pytest.raises(AssertionError):
        Amplitude(cfg, 0, n_gates=2)                   # X90: 2 · π/2 = π, does NOT return to |0>
    with pytest.raises(AssertionError):
        Amplitude(cfg, 0, gate="X", n_gates=3)         # X: 3 · π = π
    with pytest.raises(AssertionError):
        Amplitude(cfg, 0, gate="Y90")                  # qcal has exactly two native gates here
    Amplitude(cfg, 0, n_gates=4)                       # both fine
    Amplitude(cfg, 0, gate="X", n_gates=2)


def test_amplitude_n1_rejects_a_fit_outside_the_swept_span():
    """qcal's in_range guard for the n_gates=1 amplitude fit (single_qubit.py:273-279): the fitted
    π/2 amp code must lie INSIDE the swept codes, exactly as the n_gates>1 vertex already must. The
    old `0 < a_star < AMP_SCALE` accepted any on-scale extrapolation: a clean cosine over a narrow
    high-amp span implies a π/2 amp far BELOW the sweep — a good fit, but not a measurement."""
    rng = np.random.default_rng(2)
    sigma_per_code = 3.7e-4                             # gate_sigma is linear in the amp code
    a_true = 2000.0                                     # the implied π/2 amp code
    rabi = (math.pi / 2) / (a_true * sigma_per_code)

    def sweep(x0, x1):
        xs = np.linspace(x0, x1, 21)
        sig = sigma_per_code * xs
        return xs, sig, (1 - np.cos(rabi * sig)) / 2 + rng.normal(0, 0.01, xs.size)

    def estimate(x0, x1):
        xs, sig, P = sweep(x0, x1)
        return rabi_amplitude(xs, sig, P, 1, math.pi / 2, "qubit/0/x90/amp")

    est = estimate(600, 19000)                          # a_true inside the sweep
    assert est.ok and abs(est.proposal["qubit/0/x90/amp"] * units.AMP_SCALE - a_true) < 100
    est = estimate(8000, 19000)                         # a_true OUTSIDE the sweep
    assert est.fit.ok and not est.ok, "a fit whose π/2 amp lies outside the sweep must be rejected"
    assert est.proposal == {}, "a rejected fit writes nothing"


def test_frequency_qcal_signature_maps_to_the_shorthand():
    """spec 13 §3's promised signature: `detunings` (signed Hz, each a host rerun) overrides the
    ±k·detune ladder, `t_max` (seconds) spreads `points` waits over [0, t_max]. The Hz values are
    code-exact multiples (code_to_freq of an int) so the ladder (±k·code(d)) and the explicit list
    (code(±k·d)) land on identical codes — at arbitrary Hz the two round independently by ±1 LSB."""
    m = SocMap(SocParams.load(Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"))
    cfg = Config()
    d = units.code_to_freq(200, m.params)
    ladder = Frequency(cfg, 0, detune=d, n_detune=4)
    explicit = Frequency(cfg, 0, detunings=(-2 * d, -d, d, 2 * d))
    assert sorted(explicit._d_codes(m)) == sorted(ladder._d_codes(m))
    t0, dt = Frequency(cfg, 0, t_max=1e-6, points=30)._wait_grid(m)
    assert t0 == 0 and dt == round(1e-6 / 29 * m.params.dsp_freq_hz) > 0
    assert Frequency(cfg, 0, t0=80e-9, dt=40e-9)._wait_grid(m) == \
        (batches(80e-9, m), batches(40e-9, m))          # the shorthand stays the shorthand


def test_socmap_reads_the_board_driver():
    """The cal suite must run on hardware: RemoteDriver has no `.sim` (spec 10 §6) — its SocParams
    JSON is on `.board`. socmap takes either seam."""
    text = (Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json").read_text()

    class _Src:
        def __init__(self, t): self._t = t
        def get_params(self): return self._t

    class _Board:
        def __init__(self, t): self.board = _Src(t)

    class _Sim:
        def __init__(self, t): self.sim = _Src(t)

    assert socmap(_Board(text)).params.name == "sim-2q"
    assert socmap(_Sim(text)).params.qubit_num == 2


# ── cosim helpers ──

def _readout_freq(m):
    """The physical readout frequency whose demod code = the model tone code 2048."""
    return units.demod_code_to_freq(2048, m.params)


def _s(n_batches, m):
    """batches → seconds: the co-sim's own short times, expressed in the Config's physical units
    (spec 13 §2). The kernels see exactly the batch counts the pre-units tests used."""
    return units.ns(n_batches, m.params) * 1e-9


def _cfg(m, qfreq, x90_amp=0.5, dur=40, drive=None, relax=RELAX):
    """The co-sim Config, in PHYSICAL units (spec 13 §3). `dur` is the demod WINDOW (batches here,
    seconds in the tree) and `drive` the readout-drive length — the projective model only emits its
    tone while the drive is on, so the drive must cover the window (default: window + 16)."""
    c = Config()
    c["qubit/0/freq"] = float(qfreq)
    c["qubit/0/x90/amp"] = float(x90_amp)
    c["qubit/0/T1"] = _s(120, m)
    c["readout/0/freq"] = float(_readout_freq(m))
    c["readout/0/amp"] = 0.5
    c["readout/0/dur"] = _s(dur + 16 if drive is None else drive, m)
    c["readout/0/demod/dur"] = _s(dur, m)
    c["reset/relax"] = _s(relax, m)
    return c


def _sig_max(m, carrier):
    x90 = Pulse(GATE_ENV, freq_hz=carrier, amp=0.5)
    return gate_sigma(m, x90, carrier, units.AMP_SCALE - 600)


def _true_x90_amp(m, rabi):
    """The X90 amplitude (float) that rotates by π/2 for the planted rabi: θ = rabi·G·amp_code."""
    g = _sig_max(m, F_GE) / (units.AMP_SCALE - 600)     # sig per amp code (linear)
    return (math.pi / 2) / (rabi * g) / units.AMP_SCALE


def _model(rabi, f_ge=F_GE, t1=300, t2=450, noise=0.0, seed=0, collapse=False, **kw):
    """The planted TwoLevelModel. `kw` carries the optional dispersive readout (f_r/kappa/chi, spec
    13 Q2 — off by default, so every non-dispersive test sees exactly the old flat tone) and any
    readout_amp override."""
    return dict(kind="twolevel", core=0, rabi_rad_per_amp=float(rabi), readout_code=2048,
                readout_phase=0.0, f_ge=float(f_ge),
                t1=int(t1), t2=int(t2), noise_scale=float(noise), noise_seed=int(seed),
                collapse=bool(collapse), **{"readout_amp": 20000.0, **kw})


def _rabi_pi(m):
    """The Rabi rate that makes a π rotation out of the default |1> prep — X90·X90 at x90_amp=0.495
    (spec 13 §4), i.e. the same drive integral as one GATE_ENV pulse at amp 0.99."""
    return float(math.pi / gate_sigma(m, Pulse(GATE_ENV, freq_hz=F_GE, amp=0.99), F_GE,
                                      units._amp_code(0.99)))


@pytest.fixture(scope="session")
def demod_phase(cosim):
    """The real demod discrimination phase, measured ONCE by ReadoutCalibration on the projective
    model (spec 08 §2.1): it captures |0>/|1> clusters and proposes the demod-carrier phase that
    lands |0> on +real (sign(sumR) then discriminates). All counts-mode tests bake it into their
    readout tables (cfg["readout/0/demod/phase"]) — the real compile-time write_slot("phase") path.
    The pipeline angle is model-rabi/T-independent (readout_phase pinned 0), so one measurement
    serves every counts cal."""
    drv, m = cosim
    # The readout budget the qcal cluster SNR needs (its 2σ₀ + 2σ₁ denominator charges for every
    # un-prepped shot in the |1> cluster, where the old distance/σ number barely noticed): the relax head
    # must RESET the qubit (3200 ≈ 5·T1) and T1 must let |1> SURVIVE the pulse-end → readout separation
    # (600 ≫ SEP = LEAD = 96). t1 = 2000 with a 1600 relax reset only half the shots; t1 = 300 with the
    # same relax let a third of |1> decay before the window.
    drv.sim.set_model(_model(_rabi_pi(m), t1=RO_T1, t2=3000, noise=300.0, seed=7, collapse=True))
    cfg = _cfg(m, F_GE, relax=RO_RELAX)
    r = ReadoutCalibration(cfg, 0, shots=24).run(drv)
    drv.sim.set_model({"kind": "zero"})
    assert r.ok, f"ReadoutCalibration could not separate clusters (sep={r.data[0]['separation']:.2f})"
    return float(r.proposal["readout/0/demod/phase"])


@pytest.fixture(autouse=True)
def _zero_after(request):
    yield
    if request.config.getoption("--cosim"):
        request.getfixturevalue("cosim")[0].sim.set_model({"kind": "zero"})


# ── Amplitude: recover the planted Rabi rate within 1% ──

@pytest.mark.cosim
@pytest.mark.slow
def test_amplitude_recovers_rabi(cosim, demod_phase):
    drv, m = cosim
    rabi = float(4 * math.pi / _sig_max(m, F_GE))          # ~2 Rabi periods (counts sub-1% budget)
    drv.sim.set_model(_model(rabi, t1=200, t2=2000, noise=300.0, seed=1, collapse=True))
    cfg = _cfg(m, F_GE)
    cfg["readout/0/demod/phase"] = demod_phase
    r = Amplitude(cfg, 0, n_gates=1).run(drv)
    recovered = r.proposal["qubit/0/rabi"]
    # spec 13 Q4 — THE cross-check, on the real sweep: we take the amplitude through a physical Rabi
    # RATE (fit P against the drive integral σ), qcal reads it off the period of the same cosine in
    # the amplitude axis (amp = period_frac / f_fit; 0.25 for an X90). σ is linear in the amp code, so
    # the two routes must land on the same amplitude — ours additionally recovers the rate.
    fq = fits.fit_cosine(r.data[0]["x"], r.data[0]["y"])   # the cosine in the AMPLITUDE-CODE axis
    a_qcal = (0.25 / fq.value) / units.AMP_SCALE
    ours = r.proposal["qubit/0/x90/amp"]
    print(f"\n[amplitude] recovered={recovered:.6e} planted={rabi:.6e} ratio={recovered/rabi:.4f}\n"
          f"  x90 amp: rate route={ours:.5f}  qcal 0.25/f_fit={a_qcal:.5f}  "
          f"ratio={ours/a_qcal:.5f}")
    assert r.ok
    assert abs(recovered / rabi - 1) < 0.01, f"recovered {recovered} vs planted {rabi}"
    assert abs(ours / a_qcal - 1) < 0.01, f"our {ours} vs qcal's period arithmetic {a_qcal}"


@pytest.mark.cosim
@pytest.mark.slow
def test_amplitude_fine_pass_improves_the_coarse(cosim, demod_phase):
    """spec 13 Q4 — the notebook's two-step amplitude cal, on qcal's knobs: a coarse n_gates=1 cosine
    sweep, then a FINE pass that repeats the gate 4× (qcal's multiple-of-4 guard: 4 · X90 = 2π, back
    to |0>) over `relative_amp` 0.7–1.3× whatever the coarse step just wrote. Four gates amplify an
    amplitude error 4×, so the parabola's minimum pins it far more sharply than the cosine did — the
    fine pass must beat the coarse one against the model's true π/2 amplitude.

    An ANCHOR (specs/software-test-refactor/02 §4.1), and the clearest case for why the tier exists:
    `err_fine < err_coarse` is a VARIANCE claim — four gates buy a 4× finer amplitude resolution
    *for the same population error*, which means nothing without a population error to reduce. T4a
    measured the two fits against the noiseless L0 responder at these knobs and got 0.018 % and
    0.017 %: float residue, not a property. Its deterministic half did move to L0
    (test_cal_host::test_amplitude_fine_pass_refines_the_coarse — the relative_amp span, the upward
    parabola, the vertex on the true π/2 amp); the amplification itself has no owner in the fast
    tiers, so it comes back here with the real shot noise that makes it a statement. Original knobs
    and tolerances, deliberately: an anchor that has been made cheap is no longer an anchor."""
    drv, m = cosim
    rabi = float(4 * math.pi / _sig_max(m, F_GE))
    drv.sim.set_model(_model(rabi, t1=200, t2=2000, noise=300.0, seed=17, collapse=True))
    cfg = _cfg(m, F_GE)
    cfg["readout/0/demod/phase"] = demod_phase
    true_amp = _true_x90_amp(m, rabi)

    coarse = Amplitude(cfg, 0, n_gates=1, points=13, shots=64).run(drv)
    assert coarse.ok
    coarse.apply()                                          # the fine pass sweeps RELATIVE to this
    err_coarse = abs(cfg["qubit/0/x90/amp"] - true_amp)
    fine = Amplitude(cfg, 0, n_gates=4, amp_span=(0.7, 1.3), relative_amp=True, points=9,
                     shots=64).run(drv)
    assert fine.ok
    fine.apply()
    err_fine = abs(cfg["qubit/0/x90/amp"] - true_amp)
    print(f"\n[amp-fine] true={true_amp:.5f}  coarse={coarse.proposal['qubit/0/x90/amp']:.5f} "
          f"(err {err_coarse:.5f})  fine={fine.proposal['qubit/0/x90/amp']:.5f} (err {err_fine:.5f})\n"
          f"  fine P={np.round(fine.data[0]['y'], 3).tolist()}")
    assert fine.fit[0].params["a"] > 0, "the 4-gate |1> population must MINIMISE at the tuned amp"
    assert err_fine < err_coarse, f"the fine pass did not improve: {err_coarse:.5f} → {err_fine:.5f}"


# ── L2 state probes: what the emitted circuit does to the QUBIT (01 §4) ──
#
# The RTL plays the real circuit; the answer is then READ off the model (`Probe.state()` →
# `drv.sim.model_state()`) instead of being re-measured with shot statistics. The rate that makes a
# pulse an exact rotation is PLANTED with `probe.rabi_for`, so every gate below is correct by
# construction and its expected state is the textbook one — never another run of the same model
# (01 §4.4). One shot per point, one `rq.setup` per test, and `set_model` re-prepares |0> for free.
#
# None of these kernels carries a readout: the state comes off the model, so all a kernel has to
# guarantee is that its pulses have LEFT THE DAC before it reports done.

# 01 §4.6: no noise, no collapse, no t1/t2 — decay is not the subject of any probe here. The
# readout knobs are left at their defaults: nothing below fires the demod.
L2_MODEL = dict(kind="twolevel", core=0, f_ge=F_GE, noise_scale=0.0, collapse=False)


X90, X = 0, 1                            # the probe kernel's own prep fold (was base's)


def _gate_table(cfg, q, m, names=("x90", "x")) -> ParamTable:
    """The Config's own gate pulses as one ParamTable on the qubit's gate channel — what the old
    `base.prep` handed a kernel; the calibrations get theirs from the sequence compiler."""
    gs = {n: resolve(cfg, q, n, m) for n in names}
    first = gs[names[0]]
    return ParamTable(first.line, first.carrier_hz, {n: g.pulse for n, g in gs.items()})


@kernel
def k_probe_x90(gate: ParamTable, out: Array):
    """One X90 from |0>."""
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    play(gate, gate["x90"], t)  # noqa: F821
    wait_until(t + gate["x90"].dur + SEP)  # noqa: F821   the pulse has left the DAC
    out[0] = 0


@kernel
def k_probe_x90_x_x90(gate: ParamTable, out: Array):
    """qcal's Phase(gate='X') circuit — X90 · X · X90, contiguous (k_phase's X90_X_X90 branch,
    without the swept frame: the axes here are the pulses' own, from the Config)."""
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    d = gate["x90"].dur  # noqa: F821
    xd = gate["x"].dur  # noqa: F821
    t = now() + LEAD  # noqa: F821
    play(gate, gate["x90"], t)  # noqa: F821
    play(gate, gate["x"], t + d)  # noqa: F821
    play(gate, gate["x90"], t + d + xd)  # noqa: F821
    wait_until(t + 2 * d + xd + SEP)  # noqa: F821
    out[0] = 0


@kernel
def k_probe_prep(gate: ParamTable, out: Array, pg: int):
    """qcal's two |1> preps (base.prep): X90 · X90 — one play + one bare fire, B0's startTime
    auto-advance making the train contiguous — or the Config's OWN X pulse.

    `pg` is a RUNTIME scalar here so one resident image serves both preps; the production kernels
    fold it at compile time, which is a compiler property and is gated host-pure."""
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    if pg == X90:
        play(gate, gate["x90"], t)  # noqa: F821
        fire(gate, gate["x90"])  # noqa: F821
    else:
        play(gate, gate["x"], t)  # noqa: F821
    wait_until(t + 2 * gate["x90"].dur + gate["x"].dur + SEP)  # noqa: F821  covers either branch
    out[0] = 0


@kernel
def k_probe_ramsey(gate: ParamTable, out: Array, wait: int, maxw: int):
    """Two X90s `wait` batches apart, start to start — the Ramsey pair, no readout."""
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    play(gate, gate["x90"], t)  # noqa: F821
    play(gate, gate["x90"], t + wait)  # noqa: F821
    wait_until(t + maxw + gate["x90"].dur + SEP)  # noqa: F821
    out[0] = 0


@pytest.mark.cosim
def test_phase_recovers_the_stark_shift(cosim):
    """L2 — spec 13 Q3's physics, asserted where it actually lives: on the BLOCH PHASE.

    Plant an ac-Stark drive phase (`stark_rad_per_sigma`: every driven batch also rotates the qubit
    about +z, so an X90 of drive integral σ leaves ε = stark·σ of Z behind) and read what ONE X90
    does. What it leaves is a phase in the equatorial plane — invisible to a z-basis readout, which
    is why the counts version this replaces had to infer it from where two 7×96-shot lines cross.

    The analytic target is the one the old test's `_stark_vz` named. The pulse is
    exp(−i[θ(n̂·σ) + εZ]/2) with θ = π/2 (drive and Stark accrue TOGETHER, batch by batch), i.e. a
    rotation by Ω = √(θ² + ε²) about n = (θ, 0, ε)/Ω. Rodrigues on |0> gives, exactly,

        bx = (θε/Ω²)(1 − cos Ω)     by = −(θ/Ω)·sin Ω     bz = cos Ω + (ε/Ω)²(1 − cos Ω)

    and since bx / (−by) = (ε/Ω)·(1 − cos Ω)/sin Ω = (ε/Ω)·tan(Ω/2), the equatorial azimuth is
    exactly −π/2 + β with tan β = (ε/Ω)·tan(Ω/2). β is the per-side virtual-Z the calibration
    exists to write, so this pins the same number the old proposal did — from one shot instead of
    1344, and to 0.02 rad instead of 0.10.

    The gate is 32 batches long (not GATE_ENV's 4) because the model interleaves the xy and z
    rotations batch by batch: the closed form above is their n → ∞ limit and the first-order Trotter
    error is θε/(2n) — 0.06 rad at n = 4, 0.007 at n = 32. A real gate is a continuous drive; the
    per-batch step is the model's discretization, so the continuum answer is the honest target."""
    _, m = cosim
    x90 = Pulse(envelopes.square(128), freq_hz=F_GE, amp=0.5)     # 32 batches — see the docstring
    sigma = gate_sigma(m, x90, F_GE, x90.amp_code())
    gate = ParamTable(GATE_CH, F_GE, {"x90": x90})
    prog = compile_kernel(k_probe_x90, m, tables=dict(gate=gate), out=Array(1))
    spec = {**L2_MODEL, "rabi_rad_per_amp": rabi_for(m, x90, F_GE, math.pi / 2)}   # an EXACT X90
    p = Probe(cosim, {0: prog})

    for eps in (0.0, 0.3):                        # 0.0 is the control: no Stark ⇒ no residual phase
        b = p.state({**spec, "stark_rad_per_sigma": eps / sigma})["bloch"]
        omega = math.hypot(math.pi / 2, eps)
        s, c = (math.pi / 2) / omega, eps / omega
        want = [s * c * (1 - math.cos(omega)), -s * math.sin(omega),
                math.cos(omega) + c * c * (1 - math.cos(omega))]
        beta = math.atan2(eps * math.tan(omega / 2), omega)
        print(f"\n[stark eps={eps:.2f}] bloch={np.round(b, 4).tolist()} want={np.round(want, 4).tolist()}"
              f"  azimuth={math.atan2(b[1], b[0]):+.4f} want={-math.pi / 2 + beta:+.4f} (β={beta:+.4f})")
        assert b == pytest.approx(want, abs=0.02), \
            f"eps={eps}: the X90 landed at {b}, the Stark-tilted rotation wants {want}"
        assert math.atan2(b[1], b[0]) == pytest.approx(-math.pi / 2 + beta, abs=0.02), \
            "the residual Bloch phase is not the per-side virtual-Z the calibration writes"


@pytest.mark.cosim
@pytest.mark.parametrize("planted", [0.0, 0.5])
def test_phase_x_gate_recovers_the_planted_axis(cosim, planted):
    """L2 — spec 14 F1's circuit, on the state. X90 · X · X90 is a 2π rotation that returns to |0>
    only when the X sits on the X90s' AXIS. Plant that axis on the X90s (`qubit/0/x90/phase`) and
    leave the X at 0 — exactly the config the counts version used — so the mismatch is
    δ = φ_X − φ_X90 = −planted.

    Analytic, and exact here: the drive is resonant so each pulse has ONE fixed xy-plane axis, and
    the X is the X6Y3 shape (double LENGTH, same amplitude) so its drive integral is exactly 2σ,
    i.e. exactly π. Rodrigues through the three rotations from |0> gives

        b = (−sin 2δ · cos φ_X90,  −sin 2δ · sin φ_X90,  cos 2δ)

    so ⟨σz⟩ = cos 2δ — the period-π fringe the old test could only locate to ±0.25 rad through a
    13 × 96-shot cosine, now pinned to 0.02 in one shot. (`planted = 0` is the aligned case: a clean
    2π back to |0>.)"""
    _, m = cosim
    cfg = _cfg(m, F_GE, x90_amp=0.5)
    cfg["qubit/0/x90/phase"] = planted            # the reference axis both X90s sit on
    cfg["qubit/0/x/env"] = "square"               # the X: double LENGTH, same amp → π (spec 13 §4)
    cfg["qubit/0/x/dur"] = _s(8, m)
    cfg["qubit/0/x/amp"] = 0.5
    cfg["qubit/0/x/phase"] = 0.0                  # deliberately off the X90s when planted != 0
    x90 = gate_pulse(cfg, 0, m)
    gate = ParamTable(GATE_CH, F_GE, {"x90": x90, "x": gate_pulse(cfg, 0, m, "x")})
    prog = compile_kernel(k_probe_x90_x_x90, m, tables=dict(gate=gate), out=Array(1))
    p = Probe(cosim, {0: prog})
    b = p.state({**L2_MODEL, "rabi_rad_per_amp": rabi_for(m, x90, F_GE, math.pi / 2)})["bloch"]

    d = 0.0 - planted                             # the X's axis minus the X90s'
    want = [-math.sin(2 * d) * math.cos(planted), -math.sin(2 * d) * math.sin(planted),
            math.cos(2 * d)]
    print(f"\n[phase-X planted={planted:+.2f}] bloch={np.round(b, 4).tolist()} "
          f"want={np.round(want, 4).tolist()}")
    assert b == pytest.approx(want, abs=0.02), \
        f"X90·X·X90 with the X {d:+.3f} rad off the X90s landed at {b}, not {want}"


@pytest.mark.cosim
def test_prep_gate_x90_and_x_agree(cosim):
    """L2 — spec 13 Q1: qcal's two |1> preps must BOTH reach |1>.

    `gate='X90'` plays the Config's X90 twice (one play + one bare fire, contiguous through B0's
    startTime auto-advance); `gate='X'` plays the Config's OWN X pulse once — here, as on X6Y3, a
    double-LENGTH same-amplitude pulse, NOT the deleted "π = 2× the X90 amp" synthesis. Both
    integrate to the same drive, so with the X90 planted as an exact π/2 both must land ⟨σz⟩ = −1.

    The counts version compared the two preps' clusters under one fixed classifier and could only
    say they agreed to 0.15 with each above 0.75; this says each is |1> to 0.02, off one shot."""
    _, m = cosim
    cfg = _cfg(m, F_GE, x90_amp=0.495)
    cfg["qubit/0/x/env"] = "square"               # the X: double LENGTH, same amp → the same π
    cfg["qubit/0/x/dur"] = _s(8, m)
    cfg["qubit/0/x/amp"] = 0.495
    table = _gate_table(cfg, 0, m)                # the Config's own x90 + x, on the gate channel
    pg90, pgx = X90, X
    prog = compile_kernel(k_probe_prep, m, tables=dict(gate=table), out=Array(1))
    spec = {**L2_MODEL, "rabi_rad_per_amp": rabi_for(m, table.pulses["x90"], F_GE, math.pi / 2)}
    p = Probe(cosim, {0: prog})

    for name, pg in (("X90·X90", pg90), ("X", pgx)):
        b = p.state(spec, {0: {"pg": pg}})["bloch"]
        print(f"\n[prep-gate {name}] bloch={np.round(b, 4).tolist()}")
        assert b == pytest.approx([0.0, 0.0, -1.0], abs=0.02), f"the {name} prep landed at {b}, not |1>"


@pytest.mark.cosim
def test_multiqubit_both_cores_recover(cosim):
    """L2 — spec 13 Q5's load-bearing deliverable: ONE run drives TWO cores and each core's own
    qubit responds to its own gate DAC.

    One compiled image, loaded on both cores, plays the same X90-shaped pulse on each. The two
    models are planted with DIFFERENT rates (`rabi_for` π/2 on core 0, π on core 1) so the same
    stimulus must produce two DIFFERENT textbook states in the same run: core 0 on the equator at
    −y, core 1 at |1>. A crossed gate DAC, a core that never released, or a shared-time slip all
    break that; recovering a fit is not needed to see it."""
    _, m = cosim
    x90 = Pulse(GATE_ENV, freq_hz=F_GE, amp=0.5)
    gate = ParamTable(GATE_CH, F_GE, {"x90": x90})
    prog = compile_kernel(k_probe_x90, m, tables=dict(gate=gate), out=Array(1))
    turn = {0: math.pi / 2, 1: math.pi}                  # core 0 an X90, core 1 a π
    sub = [{**L2_MODEL, "core": q, "rabi_rad_per_amp": rabi_for(m, x90, F_GE, turn[q])}
           for q in (0, 1)]

    p = Probe(cosim, {0: prog, 1: prog})
    models = p.state({"kind": "multi", "models": sub})["models"]
    b0, b1 = (mo["bloch"] for mo in models)
    print(f"\n[multi-q] core0={np.round(b0, 4).tolist()} core1={np.round(b1, 4).tolist()}")
    assert b0 == pytest.approx([0.0, -1.0, 0.0], abs=0.02), f"core 0's X90 landed at {b0}, not −y"
    assert b1 == pytest.approx([0.0, 0.0, -1.0], abs=0.02), f"core 1's π landed at {b1}, not |1>"


@pytest.mark.cosim
@pytest.mark.parametrize("d0_code", [60, -60])
def test_frequency_recovers_detuning(cosim, d0_code):
    """L2 — the RTL half of spec 13 Q4: a carrier detuned by δ really does RAMP THE DRIVE AXIS, so
    a Ramsey pair separated by a wait accumulates exactly the phase δ says it should.

    The model takes the drive axis by demodulating the gate DAC against `f_ge`, so a carrier δ codes
    away ramps that axis by 2π·δ/2^12 rad per batch (16 DAC samples of δ/2^16 turns each). Two X90s
    whose axes differ by φ therefore leave, exactly (Rodrigues, both axes in the xy-plane, in the
    frame of the FIRST pulse's axis),

        b = (−sin φ · cos φ,  −sin² φ,  −cos φ)      ⇒   ⟨σz⟩ = −cos φ,  |b_xy| = |sin φ|

    with φ = 2π·δ·Δt/2^12 and Δt the pulses' start-to-start separation. Both of those are invariant
    under z-rotation, so they are what is asserted: the first pulse's ABSOLUTE axis is
    2π·δ·t₁/2^12, and t₁ is wherever in free-running batch time the rerun happened to land, so the
    lab-frame azimuth carries an arbitrary offset and nothing else does. Three waits pin the RATE —
    no fit, no fringe envelope, no 12×96×4 sweep (the counts version cost 599 s per sign).

    The X90 is ONE batch long on purpose. The model applies one rotation per batch about that
    batch's axis, so a single-batch pulse is exactly a fixed-axis rotation and the two-axis formula
    above is exact; a 4-batch GATE_ENV would ramp its own axis by 0.37 rad mid-pulse (the detuned
    Rabi tilt), which no fixed-axis target can describe.

    What ⟨σz⟩ CANNOT see is the ramp's sign: reflecting through the xz-plane maps φ → −φ and leaves
    every z-basis observable of a sequence starting at the pole invariant. So ±60 pins the rate for
    a carrier above AND below f_ge, but the sign lock-step (V-vertex → proposal) is arithmetic and
    lives host-pure in test_cal_host::test_frequency_proposal_moves_the_carrier_toward_f_ge.

    Tolerance 0.05: `_drive_axis` demodulates 16 samples per batch, so the counter-rotating term at
    f_drive + f_ge leaves ~0.015 rad of axis residual per pulse (~0.03 rad differential), which is
    deterministic, not statistical."""
    _, m = cosim
    drive = units.code_to_freq(units._freq_code(F_GE, m.params) + d0_code, m.params)   # f_ge + δ
    x90 = Pulse(envelopes.square(4), freq_hz=drive, amp=0.5)      # ONE batch — see the docstring
    gate = ParamTable(GATE_CH, drive, {"x90": x90})
    waits = (8, 20, 32)                                           # start-to-start, batches
    prog = compile_kernel(k_probe_ramsey, m, tables=dict(gate=gate), out=Array(1),
                          maxw=max(waits))
    spec = {**L2_MODEL, "rabi_rad_per_amp": rabi_for(m, x90, drive, math.pi / 2)}
    p = Probe(cosim, {0: prog})

    for w in waits:
        b = p.state(spec, {0: {"wait": w}})["bloch"]
        phi = 2 * math.pi * d0_code * w / (1 << 12)
        print(f"\n[frequency δ={d0_code:+d} Δt={w}] φ={phi:+.4f} bloch={np.round(b, 4).tolist()}"
              f"  ⟨σz⟩={b[2]:+.4f} want={-math.cos(phi):+.4f}  |b_xy|={math.hypot(b[0], b[1]):.4f} "
              f"want={abs(math.sin(phi)):.4f}")
        assert b[2] == pytest.approx(-math.cos(phi), abs=0.05), \
            f"δ={d0_code:+d}, Δt={w}: ⟨σz⟩ = {b[2]:+.4f}, a δ-ramped axis wants {-math.cos(phi):+.4f}"
        assert math.hypot(b[0], b[1]) == pytest.approx(abs(math.sin(phi)), abs=0.05), \
            "the pair did not leave the equatorial radius |sin φ| the same φ implies"


# ── L1: the readout TIMING knobs and the heralded grid, on the converters (01 §3) ──

@kernel
def k_ro_delay(ro: ParamTable, demod: ParamTable, out: Array, code: int, pre: int, ddly: int):
    """One readout drive, started `pre` batches early, and one demod window opening `ddly` after the
    reference time — the `demod/delay` knob's own arithmetic. Every counts kernel does exactly this
    (`play(demod, ..., t_ro + ddly)`); the delay is not a table field, which is why sweeping it needs
    a per-run param instead of a write_slot."""
    init_pulse_params(demod.pulses)  # noqa: F821
    set_freq(demod, code)  # noqa: F821
    init_pulse_params(ro.pulses)  # noqa: F821
    set_freq(ro, ro.freq)  # noqa: F821
    t = now() + LEAD + pre  # noqa: F821
    play(ro, ro["meas"], t - pre)  # noqa: F821
    play(demod, demod["sq"], t + ddly)  # noqa: F821
    wait_until(t + ddly + READOUT_LEAD)  # noqa: F821
    read_res()  # noqa: F821                                 (HALTS until the window has settled)
    out[0] = read_real()  # noqa: F821
    out[1] = read_imag()  # noqa: F821


@pytest.mark.cosim
def test_readout_timing_knob_moves_the_readout(cosim):
    """L1 (spec 14 F2) — `demod/delay` MOVES THE INTEGRATION WINDOW IN TIME, measured on the readout
    datapath to the batch.

    The demod carrier is not a converter channel (it feeds the decoder), so the window's position is
    not a DAC capture — but it is just as observable. Loop the readout DRIVE back into the ADC
    (`LoopbackModel`: deterministic, no quantum state, permitted at L1 by 01 §3.3) and the decoder
    integrates the echo only where the window and the drive OVERLAP. Park the window deep inside the
    drive and it collects the whole 64 batches; slide it off the trailing edge and it loses exactly
    one batch of signal per batch of delay:

        |z|(ddly) = A·(drive_end − ddly),   A = |z|(deep inside) / window

    so the 16-batch step between the two ramp points must remove exactly a QUARTER of the plateau.
    Stating it as a slope is what makes it independent of the converter round trip and the decoder
    pipeline — a fixed offset on `drive_end` that a single absolute edge could not separate.

    The model this replaces was the projective TwoLevelModel scored through `Window`'s confusion
    diagonal at 48 shots/point, where the only assertable effect was the total collapse of the
    starved point; the decode half of that claim is host-pure in test_cal_host."""
    drv, m = cosim
    q, pre, drive, win = 0, 64, 192, 64            # batches: drive [t−64, t+128), window 64 long
    # demod code 8192 = one quarter turn per ADC sample, so the demod product's 2ω term closes
    # exactly every batch and each overlapping batch contributes the SAME amount. At the model's
    # usual code 2048 it does not, and the leftover ripple is ±3 % of a 20-batch integral — which
    # would put a systematic error in the slope this test is about.
    ro_freq = units.demod_code_to_freq(8192, m.params)
    ro = ParamTable(1, ro_freq, {"meas": Pulse(envelopes.square(drive), freq_hz=ro_freq, amp=0.5)})
    prog = compile_kernel(k_ro_delay, m, tables=dict(ro=ro, demod=demod_table(win, m)), out=Array(2),
                          code=units.demod_freq_to_code(ro_freq, m.params), pre=pre)
    drv.sim.set_model({"kind": "loopback", "src": m.ro_dac(q), "dst": m.adc_of(q), "gain": 1.0})
    rq.setup(drv, m, {q: prog})

    def mag(ddly):
        out = rq.rerun(drv, m, {q: prog}, params={q: {"ddly": ddly}}, results=["out"],
                       timeout=batch_timeout(4 * (pre + drive)))[q]["out"]
        return math.hypot(float(out[0]), float(out[1]))

    flat, hi, lo = (mag(d) for d in (0, 96, 112))   # 0 = deep inside; 96/112 straddle the tail
    per_batch = flat / win                          # A: the integral one overlapping batch is worth
    edge = 96 + hi / per_batch                      # the drive's trailing edge, in window time
    print(f"\n[demod/delay] |z| plateau={flat:.0f} ddly=96 -> {hi:.0f} ddly=112 -> {lo:.0f}  "
          f"step={hi - lo:.0f} want={16 * per_batch:.0f}  overlap={hi / per_batch:.2f}/"
          f"{lo / per_batch:.2f} batches, edge={edge:.2f} (the drive ends at {drive - pre})")
    assert flat > 0 and hi < flat, "the window never left the middle of the drive"
    assert hi - lo == pytest.approx(16 * per_batch, rel=0.01), \
        f"16 batches of demod/delay removed {(hi - lo) / per_batch:.2f} batches of the integral, not 16"
    assert 0 <= edge - (drive - pre) <= 16, \
        f"the window sits {edge - (drive - pre):.2f} batches off the drive's end — not a round trip"


def _dac_windows(t0, cap):
    """The (start, end) absolute batch stamps of each contiguous run of nonzero DAC output."""
    on = np.flatnonzero(cap.any(axis=1))
    if not on.size:
        return []
    cuts = np.flatnonzero(np.diff(on) > 1)
    return [(int(t0 + r[0]), int(t0 + r[-1] + 1)) for r in np.split(on, cuts + 1)]


# ── §8 heralding: the two-window grid geometry, on the converters ──
#
# A `readout/herald` run inserts a readout BEFORE the sequence and post-selects on it finding the
# qubit in |0>. The read HALTS the core, so the drive scheduled right after it needs the same
# scheduling lead a normal shot has, or it posts too late and DROPS — the qubit never rotates and
# every point reads |0>. `herald_offset` is derived to give exactly that:
#
#     hoff = seq + delay + READOUT_LEAD + 2·SEP
#         ⇒ (t_ro − SEP − seq) − (t_h + delay + READOUT_LEAD) == SEP
#
# which is a statement about WHEN pulses leave the converters, so that is where it is asserted.
# The other half of the old three tests — the (count, kept) decode and its denominator — is
# host-pure in test_cal_host, one test per kernel.
#
# ONE test PER KERNEL. k_rabi, k_ro_amp and k_phase each carry their own copy of the same four-line
# herald block, differing only in the sequence they bracket, and three copies of four lines is
# exactly how a copy-paste divergence survives — which is the failure this geometry catches.

# The capture is armed before the reset release, so it pays for the core's boot as well as the
# shot. Measured: ~2 000 batches of boot + preamble before k_rabi reads `now()`, then one heralded
# grid period (<= 350) and the 56-batch measurement drive. At 2 000 the capture ended between the
# two windows, which reads as a dropped drive — a confusing way to fail, so leave the margin.
HERALD_NCAP = 3200


def _herald_cfg(m):
    """The heralded L1 config: model off, relax head at its floor (it is not the subject here)."""
    cfg = _cfg(m, F_GE, x90_amp=0.495, relax=8)
    cfg["readout/herald"] = True
    return cfg


# ── §8 heralding: the two-window grid geometry, on the converters ──
#
# A `readout/herald` run inserts a readout BEFORE the sequence and post-selects on it finding the
# qubit in |0>. The read HALTS the core, so the drive scheduled right after it needs the same
# scheduling lead a normal shot has, or it posts too late and DROPS — the qubit never rotates and
# every point reads |0> (and a fire that is merely LATE plays the PREVIOUS pulse's parameters,
# SOC_TIPS §5). `herald_offset` is derived to give exactly that:
#
#     hoff = seq + delay + READOUT_LEAD + 2·SEP
#         ⇒ (t_ro − SEP − seq) − (t_h + delay + READOUT_LEAD) == SEP
#
# which is a statement about WHEN pulses leave the converters, so that is where it is asserted.
# The (count, kept) decode and its denominator are host-pure in test_cal_host. ONE test: there is
# one kernel now (`k_batched`), so the three per-kernel copies this replaces cannot diverge.

HERALD_NCAP = 16000      # the image load (~11.5 k batches) + boot + the one heralded shot


@pytest.mark.cosim
def test_herald_grid_geometry(cosim):
    """L1 — one heralded Amplitude point through the production class, model off: two readout
    windows `herald_offset` apart, the swept gate between them, a full LEAD of posting lead after
    the herald window closes, and a clean SEP before the measurement opens."""
    drv, m = cosim
    q = 0
    cfg = _cfg(m, F_GE, x90_amp=0.495, relax=8)
    cfg["readout/herald"] = True
    drv.sim.set_model({"kind": "zero"})           # the DACs carry only this core's own drive
    caps = {d: drv.sim.dac_capture_arm(d, HERALD_NCAP) for d in (m.gate_dac(q), m.ro_dac(q))}
    Amplitude(cfg, q, n_gates=1, points=1, shots=1).run(drv)
    ro_win = _dac_windows(*drv.sim.dac_capture_get(caps[m.ro_dac(q)]))
    gate_win = _dac_windows(*drv.sim.dac_capture_get(caps[m.gate_dac(q)]))
    _, _, _, _, ddly = readout_tables(cfg, q, m)
    drive = batches(cfg[f"readout/{q}/dur"], m)   # the measurement tone's LENGTH (not the window)
    d = resolve(cfg, q, "x90", m).dur
    print(f"\n[herald] seq={d} drive={drive} ddly={ddly} ro={ro_win} gate={gate_win}")

    assert len(ro_win) == 2, f"a heralded shot plays TWO readout windows, the DAC shows {ro_win}"
    (h_start, h_end), (m_start, _) = ro_win
    assert h_end - h_start == drive, f"the herald read is not one full drive: {ro_win}"
    assert m_start - h_start == herald_offset(d, ddly), \
        f"the two windows are not `herald_offset` apart: {ro_win}"
    assert len(gate_win) == 1 and gate_win[0][1] - gate_win[0][0] == d, \
        f"the swept gate did not reach the gate DAC as one {d}-batch window: {gate_win}"
    g_start, g_end = gate_win[0]
    assert g_start - (h_start + ddly + READOUT_LEAD) == LEAD, \
        "the drive gets no full posting lead after the herald read (the drive-drop trap)"
    assert m_start - g_end == SEP, "the sequence does not end a clean SEP before the measurement"


# ── §7 cost accounting: a batched cal is O(1) client seam ops in one run ──

@pytest.mark.cosim
def test_cost_accounting_amplitude(cosim):
    """L1 (spec 08 §7) — a batched Amplitude cal costs O(1) client seam ops in ONE run (a few block
    writes in, one poll, one block read out), not the ~50k of the old one-run-per-point host loop.
    The op count is independent of npts×shots (block writes/reads move O(n) bytes in O(1) ops), so
    this tiny 5×4 sweep counts the same as a full 21×160 one. CountingDriver wraps the 4 seam ops
    (spec 08 B3).

    Model OFF and the grid sized for COST, not physics: an op count does not care what the counts
    are, so the 1600-batch relax head and the 40-batch window the physics tests need are pure
    overhead here. `shots` is 4 rather than the original 8 because the floor is not the sweep — the
    k_rabi image load alone is ~11.5 k simulated batches and the rerun another ~2.7 k, so 40 shots
    on the shortest legal grid (period 168) cannot fit under the 20 k cap
    (specs/software-test-refactor/02 §1). Nothing in the claim depends on the count: 20 shots is
    still a batched sweep, and the size-INDEPENDENCE itself is owned by
    test_rerun.py::test_rerun_op_budget_size_independent, which compares two sizes on purpose."""
    from test_rerun import CountingDriver
    drv, m = cosim
    drv.sim.set_model({"kind": "zero"})
    cfg = _cfg(m, F_GE, dur=8, drive=24, relax=8)
    cfg["readout/0/demod/phase"] = math.pi / 2          # any phase; the op count is physics-independent
    cd = CountingDriver(drv)
    Amplitude(cfg, 0, n_gates=1, points=5, shots=4).run(cd)
    print(f"\n[cost] one batched Amplitude cal = {cd.ops} client seam ops in 1 run "
          f"(setup+rerun; the rerun batch alone is 10 seam ops — test_rerun.py, B3)")
    assert cd.ops < 60, f"batched Amplitude cost {cd.ops} ops (expected O(1), a few dozen), not ~50k"


# ── the Calibration_X6Y3 sequence improves a deliberately-detuned Config ──

@pytest.mark.cosim
@pytest.mark.slow
def test_x6y3_improves_detuned_config(cosim):
    """spec 13 Q6 — the payoff: calibration_x6y3 IS the notebook, driven through the qcal adapter. The
    config is loaded from a co-sim-scaled qcal tree by Config.from_qcal (the parity path — not a
    hand-built _cfg), a copy is deliberately detuned, and the whole 8-step chain must move it toward
    the model's ground truth on EVERY corrected step, not just freq + amp."""
    drv, m = cosim
    rabi = float(3 * math.pi / _sig_max(m, F_GE))
    # projective model (spec 08 §2.4): the whole X6Y3 chain — readout-cluster cals AND the counts-mode
    # qubit cals — runs on ONE model, so that model must clear the READOUT budget (the qcal SNR formula,
    # spec 13 §2/Q2): relax=RO_RELAX ≫ T1=RO_T1 ≫ SEP (=LEAD=96) resets to |0> in the idle head yet
    # retains |1> across the SEP. (The pre-Q2 t1=300/relax=1600 gave sep≈0.91 < 1.0 — starved for the
    # ~4×-tighter qcal SNR, cascading ReadoutCalibration ok=False → an un-applied demod phase → Frequency
    # and Phase failing.) High-SNR clusters (noise=300 vs readout_amp=20000).
    drv.sim.set_model(_model(rabi, t1=RO_T1, t2=3000, noise=300.0, seed=11, collapse=True))

    # THE headline: the chain is driven from a Config.from_qcal(...) tree (the qcal adapter path), whose
    # co-sim-scaled numbers reproduce _cfg's world exactly (readout on the model's demod code 2048, the
    # square GATE_ENV gate, the RO_RELAX relax head) through the real qcal layout.
    ro_freq = _readout_freq(m)
    cfg = Config.from_qcal(QCAL_COSIM_YAML)
    assert cfg["qubit/0/freq"] == F_GE and cfg["readout/0/freq"] == ro_freq
    assert cfg["reset/relax"] == pytest.approx(_s(RO_RELAX, m))
    assert cfg["qubit/0/x90/vz"] == [0.0, 0.0]                # a co-sim config: the frame bracket is a no-op

    # deliberately-detune a copy: qubit freq off by d0, X90 amp wrong (0.5 vs the true π/2 amp)
    d0_code = 60
    drive = units.code_to_freq(units._freq_code(F_GE, m.params) + d0_code, m.params)
    cfg["qubit/0/freq"] = float(drive)
    cfg["qubit/0/x90/amp"] = 0.5
    true_x90_amp = _true_x90_amp(m, rabi)

    freq_before = abs(cfg["qubit/0/freq"] - F_GE)
    amp_before = abs(cfg["qubit/0/x90/amp"] - true_x90_amp)
    results = calibration_x6y3(cfg, 0, drv, verbose=True)
    assert len(results) == 8 and all(r.label for r in results)
    rc, sep, fid, rof, freq, amp_c, amp_f, phase = results

    # spec 13 Q6 — every CORRECTED step actually worked end-to-end, not just freq + amp:
    assert rc.ok, f"ReadoutCalibration could not separate clusters (sep={rc.data[0]['separation']:.2f})"
    assert sep.ok, "Separation did not return a readout frequency"
    assert abs(sep.proposal["readout/0/freq"] - ro_freq) <= 2.5e6, \
        f"Separation returned an out-of-band readout freq {sep.proposal['readout/0/freq']:.5g} (want ≈{ro_freq:.5g})"
    assert fid.ok, "Fidelity did not return a readout amplitude"
    assert 0.4 < fid.proposal["readout/0/amp"] < 0.6, \
        f"Fidelity picked a wild readout amp {fid.proposal['readout/0/amp']}"
    assert rof.ok and 0.6 < rof.data[0]["fidelity"] <= 1.0, \
        f"ReadoutFidelity reported an implausible fidelity {rof.data[0]['fidelity']:.3f}"
    assert freq.ok, "Frequency fit failed"
    assert amp_c.ok, "coarse Amplitude fit failed"
    assert amp_f.ok, "fine Amplitude fit failed"
    assert phase.ok and "qubit/0/x90/vz" in phase.proposal, "Phase did not recover a virtual-Z"

    freq_after = abs(cfg["qubit/0/freq"] - F_GE)
    amp_after = abs(cfg["qubit/0/x90/amp"] - true_x90_amp)
    print(f"\n[x6y3] freq err {freq_before:.3g}→{freq_after:.3g}  "
          f"x90amp {cfg['qubit/0/x90/amp']:.4f} (true≈{true_x90_amp:.4f}) err {amp_before:.4f}→{amp_after:.4f}\n"
          f"  readout freq={sep.proposal['readout/0/freq']:.5g} amp={fid.proposal['readout/0/amp']:.4f} "
          f"fidelity={rof.data[0]['fidelity']:.3f}  vz={phase.proposal['qubit/0/x90/vz'][0]:+.4f}")
    assert freq_after < 0.4 * freq_before, "X6Y3 did not improve the qubit frequency"
    assert amp_after < 0.5 * amp_before, "X6Y3 did not improve the X90 amplitude"

