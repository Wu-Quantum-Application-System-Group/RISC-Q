"""Calibration convergence, host-pure (specs/software-test-refactor/01 §2.2 — the L0 tier).

Each test runs a REAL calibration class — real `compile_kernel`, real grid arithmetic, real fits,
real `Config` write-back — against an analytic population model supplied through the shared
`responder` fixture. Only the RISC-V execution is replaced, so what is under test here is the
half of a calibration loop that co-simulating cannot make cheaper: does the analysis recover the
planted truth, and does the proposal move the Config the right way.

The physics that produces those populations is the kernels' job and is gated separately:
the emitted signal at L1 (DAC windows, model off), its effect on the qubit at L2 (state probes),
and the whole loop together in the `--slow` anchors.

The rule (01 §2.3): every `@r.answer` here computes its populations from FIRST PRINCIPLES — the
textbook response of the sequence the class is running. None of them import from `riscq.cal` to
decide what to return, and none were tuned until a test passed.
"""

import math
from pathlib import Path

import numpy as np
import pytest
from scipy.special import erfc

from riscq.cal import (Amplitude, Classifier, Config, Fidelity, Frequency, Phase, Punchout,
                       ReadoutCalibration, ReadoutFidelity, Resonator, Separation, T1, T2, Window)
from riscq.cal.base import GATE_ENV, gate_sigma
from riscq.cal.readout import _rawiq_prog, _ro_amp_prog
from riscq.pulses import Pulse, units
from tests.responder import counts, counts_heralded, int_axis, iq_sum, q16_axis, raw_iq

CONFIG = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"
F_GE = 50e6                      # planted qubit frequency (freq code 2048)
Y180_X90 = 0                     # k_phase's compile-time `seq` fold: qcal's first Phase sequence
RAW = 1                          # the kernels' `mode` fold: IQ shots out, not a classified count


def _s(n_batches, m):
    """batches → seconds: the co-sim's own short times, in the Config's physical units
    (spec 13 §2)."""
    return units.ns(n_batches, m.params) * 1e-9


def _cfg(m, qfreq=F_GE, x90_amp=0.5, dur=40, drive=None, relax=1600):
    """The same physical-units Config shape the co-sim tests use (spec 13 §3). `dur` is the demod
    WINDOW and `drive` the readout-drive length, both in batches (seconds in the tree)."""
    s = lambda n: _s(n, m)                            # noqa: E731  batches → seconds
    c = Config()
    c["qubit/0/freq"] = float(qfreq)
    c["qubit/0/x90/amp"] = float(x90_amp)
    c["qubit/0/T1"] = s(120)
    c["readout/0/freq"] = float(units.demod_code_to_freq(2048, m.params))
    c["readout/0/amp"] = 0.5
    c["readout/0/dur"] = s(dur + 16 if drive is None else drive)
    c["readout/0/demod/dur"] = s(dur)
    c["reset/relax"] = s(relax)
    return c


def _cfg2(m, freqs=(F_GE, F_GE), x90_amp=0.5, relax=1600):
    """`_cfg`'s two-core twin (spec 13 §8): the same tree with a block for core 0 AND core 1, each on
    its own carrier — what a simultaneous multi-qubit cal reads."""
    c = _cfg(m, qfreq=freqs[0], x90_amp=x90_amp, relax=relax)
    other = _cfg(m, qfreq=freqs[1], x90_amp=x90_amp, relax=relax)
    c["qubit/1"] = other["qubit/0"]
    c["readout/1"] = other["readout/0"]
    return c


def _sigma_per_code(m, carrier=F_GE):
    """The drive integral per amplitude code — linear, so one evaluation fixes the whole axis.
    This is the model's own Σ amp_est, i.e. what `rabi_rad_per_amp` multiplies."""
    hi = units.AMP_SCALE - 600
    return gate_sigma(m, Pulse(GATE_ENV, freq_hz=carrier, amp=0.5), carrier, hi) / hi


# ── the analytic physics the answers are built on (01 §2.3: first principles, never riscq.cal) ──

RO_A_OVER_SIGMA = 2.2    # cluster half-separation in units of the per-quadrature readout noise:
#                          means 4.4σ apart = qcal SNR 1.1, well over ReadoutCalibration's 0.5 floor
CHAIN_PHASE = 0.8        # the readout chain's absolute IQ angle in the ZERO demod frame (rad)
IQ_SCALE = 1e5           # decoder counts per unit of readout response (the integrator's own scale;
#                          the `out` words are integers, so a response of O(1) has to be seated)


def _misassign(snr):
    """The Gaussian assignment error of a discriminator `snr` σ away from each cluster: the noise
    tail Φ(−snr) that crosses the threshold."""
    return 0.5 * erfc(np.asarray(snr, float) / math.sqrt(2))


def _out(p1, prog):
    """Encode a |1> population as the `out` array this program writes: a heralded kernel interleaves
    (count, kept) pairs, a plain one writes one count per point (spec 13 §8)."""
    shots = int(prog.bindings["shots"])
    return counts_heralded(p1, shots) if prog.bindings.get("herald") else counts(p1, shots)


def _unseat(word) -> int:
    """A seated pack16 register word (spec 12) back to its plain signed 16-bit code."""
    c = (int(word) >> 16) & 0xFFFF
    return c - (1 << 16) if c >= (1 << 15) else c


def _vz_phase(prog, params) -> np.ndarray:
    """The virtual-Z phase (rad) the kernel applies at each point. The (p0, dp) pair is a plain
    phase-code accumulator, and one phase code is π/2^15 rad — so this is the swept frame angle the
    Ramsey / Phase circuits actually see, read off the run instead of re-derived."""
    p0, dp = _unseat(params["p0"]), _unseat(params["dp"])
    n = int(prog.bindings["npts"])
    return (p0 + np.arange(n) * dp) * math.pi / (1 << 15)


def _demod_phase(prog) -> float:
    """The demod-carrier phase this program was COMPILED at (rad) — `readout_tables` bakes it into
    the demod slot, and the measured phasor rotates with it."""
    return prog.tables["demod"][0][0] * math.pi / (1 << 15)


def _antipodal_iq(prep, shots, theta, seed=0, a_over_sigma=RO_A_OVER_SIGMA):
    """The textbook dispersion-free readout: |0> and |1> emit the same tone π out of phase, so the
    two clusters sit at ±A along the readout chain's own axis (`CHAIN_PHASE`) with isotropic
    Gaussian noise σ. A demod carrier compiled at `theta` rotates the measured phasor by e^{+iθ} —
    the convention under which ReadoutCalibration's `−atan2(m0 − m1)` proposal lands |0> on +real,
    which is what the co-sim `demod_phase` fixture demonstrates on the real datapath.

    The draws depend only on (prep, shots), so two runs of the same prep see the SAME shots: the
    comparisons below then isolate the DECISION RULE from the sampling."""
    rng = np.random.default_rng(seed + prep)
    mean = (1 - 2 * prep) * a_over_sigma * np.exp(1j * CHAIN_PHASE)
    noise = rng.normal(0, 1, shots) + 1j * rng.normal(0, 1, shots)
    return IQ_SCALE * (mean + noise) * np.exp(1j * theta)


def _res_count(z) -> int:
    """The on-chip discriminator: sign(sumR) against a ZERO threshold — |0> on +real reads res=0,
    |1> on −real reads res=1 (base.res_sign's +1 convention)."""
    return int(np.count_nonzero(np.asarray(z).real < 0))


def _lorentzian(f_hz, f_r, kappa, chi, state):
    """A driven resonator's response, pulled to f_r + χ by |0> (state=+1) and f_r − χ by |1>
    (state=−1): S(f) = 1 / (1 + 2i(f − f_r ∓ χ)/κ). Textbook, and the reason a dispersive sweep has
    two different answers — argmax |S(|0>)| sits at f_r + χ, argmax |S(|0>) − S(|1>)| at f_r."""
    return 1.0 / (1.0 + 2j * (np.asarray(f_hz, float) - f_r - state * chi) / kappa)


def _vna_freqs(prog, params, m) -> np.ndarray:
    """The physical frequencies of a k_vna sweep's realized codes (its (c0q, dcq) descriptor)."""
    return units.code_to_freq(q16_axis(prog, params, x0="c0q", dx="dcq"), m.params)


def test_amplitude_recovers_the_planted_rabi_rate(responder, socmap):
    """`Amplitude(n_gates=1)` fits P = (1 − cos(rabi·σ))/2 against the drive integral σ and must
    return the planted rate, plus the amplitude that makes rabi·σ = π/2.

    The co-sim twin of this (`test_cal.py::test_amplitude_recovers_rabi`, a `--slow` anchor) costs
    746 s: 21 points × 160 shots × ~1800 batches of grid, ~90 % of it idle relax head. The analysis
    it exercises is identical; only the source of the counts differs.
    """
    m = socmap
    r = responder(CONFIG)
    g = _sigma_per_code(m)
    rabi = float(4 * math.pi / (g * units.AMP_SCALE))     # ~2 Rabi periods across the sweep

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            xs = q16_axis(prog, params.get(q))            # the codes the kernel realizes
            p1 = (1 - np.cos(rabi * g * xs)) / 2          # textbook Rabi, from first principles
            out[q] = {"out": counts(p1, prog.bindings["shots"])}
        return out

    res = Amplitude(_cfg(m), 0, n_gates=1).run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/rabi"] == pytest.approx(rabi, rel=0.01)
    # ...and the proposed X90 amplitude is the one that rotates by exactly π/2
    a_star = res.proposal["qubit/0/x90/amp"] * units.AMP_SCALE
    assert rabi * g * a_star == pytest.approx(math.pi / 2, rel=0.01)


def test_amplitude_rejects_a_fit_whose_pi_over_2_lies_outside_the_sweep(responder, socmap):
    """qcal's `in_range` guard (single_qubit.py:273-279), through the real class: a clean cosine
    over a narrow high-amplitude span implies a π/2 amplitude far BELOW the swept codes. That is an
    extrapolation, not a measurement, and must come back `ok=False` even though the fit succeeded."""
    m = socmap
    r = responder(CONFIG)
    g = _sigma_per_code(m)
    rabi = float(4 * math.pi / (g * units.AMP_SCALE))

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            xs = q16_axis(prog, params.get(q))
            out[q] = {"out": counts((1 - np.cos(rabi * g * xs)) / 2, prog.bindings["shots"])}
        return out

    wide = Amplitude(_cfg(m), 0, n_gates=1).run(r.drv)
    narrow = Amplitude(_cfg(m), 0, n_gates=1, amp_span=(0.85, 0.97)).run(r.drv)
    assert wide.ok, "the full-span sweep contains its own π/2 amplitude"
    assert not narrow.ok, "a π/2 amplitude outside the swept codes must be rejected"


def test_amplitude_proposal_is_not_applied_until_apply(responder, socmap):
    """`Result.apply()` is the only thing that writes the Config — a cal that mutated it during
    `run` would make every downstream step depend on step order."""
    m = socmap
    r = responder(CONFIG)
    g = _sigma_per_code(m)
    rabi = float(4 * math.pi / (g * units.AMP_SCALE))
    cfg = _cfg(m, x90_amp=0.5)

    @r.answer
    def _(progs, params):
        return {q: {"out": counts((1 - np.cos(rabi * g * q16_axis(prog, params.get(q)))) / 2,
                                  prog.bindings["shots"])} for q, prog in progs.items()}

    res = Amplitude(cfg, 0, n_gates=1).run(r.drv)
    assert cfg["qubit/0/x90/amp"] == 0.5, "run() must not touch the Config"
    res.apply()
    assert cfg["qubit/0/x90/amp"] == res.proposal["qubit/0/x90/amp"]


def test_amplitude_fine_pass_refines_the_coarse(responder, socmap):
    """spec 13 Q4 — the notebook's two-step amplitude cal on qcal's knobs: a coarse n_gates=1 cosine
    sweep, then a FINE pass that repeats the gate 4× (qcal's multiple-of-4 guard: 4·X90 = 2π,
    back to |0>) over `relative_amp` 0.7–1.3× whatever the coarse step just wrote.

    What is deterministic here — and so what this pins — is the coarse→fine ARITHMETIC over the
    populations: the fine sweep's realized codes really are 0.7–1.3× the amplitude the coarse pass
    applied (qcal's `amplitudes = config[param] * amplitudes`), the amplified population
    MINIMISES at the tuned amp (an upward parabola, where qcal fitting P(|0>) demands a downward
    one), and the vertex lands on the true π/2 amplitude.

    NOT moved: the co-sim twin's `err_fine < err_coarse` comparison. Four gates buy a 4× finer
    amplitude resolution for the same population error — a VARIANCE claim, and against a noiseless
    responder the coarse fit is already exact to 0.02 %, so there is no error left to improve on.
    That half stays with the `--slow` anchor, which has the shot noise that makes it mean something.
    """
    m = socmap
    r = responder(CONFIG)
    g = _sigma_per_code(m)
    rabi = float(4 * math.pi / (g * units.AMP_SCALE))       # ~2 Rabi periods across the sweep

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            xs = q16_axis(prog, params.get(q))
            n = int(prog.bindings["ngates"])                # n gates ⇒ n× the drive integral
            out[q] = {"out": _out((1 - np.cos(n * rabi * g * xs)) / 2, prog)}
        return out

    true_amp = (math.pi / 2) / (rabi * g) / units.AMP_SCALE
    cfg = _cfg(m, x90_amp=0.5)
    coarse = Amplitude(cfg, 0, n_gates=1, points=13, shots=64).run(r.drv)
    assert coarse.ok
    coarse.apply()                                          # the fine pass sweeps RELATIVE to this
    applied = cfg["qubit/0/x90/amp"]

    fine = Amplitude(cfg, 0, n_gates=4, amp_span=(0.7, 1.3), relative_amp=True, points=9,
                     shots=64).run(r.drv)
    assert fine.ok
    assert fine.fit[0].params["a"] > 0, "the 4-gate |1> population must MINIMISE at the tuned amp"
    xs = fine.data[0]["x"]                                  # the realized codes, host-mirrored
    assert xs[0] == pytest.approx(0.7 * applied * units.AMP_SCALE, abs=1.0)
    assert xs[-1] == pytest.approx(1.3 * applied * units.AMP_SCALE, abs=1.0)
    fine.apply()
    assert cfg["qubit/0/x90/amp"] == pytest.approx(true_amp, rel=0.005)


def test_t1_recovers_the_planted_decay(responder, socmap):
    """T1 preps |1>, sweeps the idle Δt before the readout and exp-fits P = A·exp(−Δt/T1) + C. The
    answer is the textbook relaxation P(|1>) = exp(−Δt/T1) on the delay grid the kernel realizes.

    The co-sim twin costs 295 s; the analysis is identical. Tolerance tightened from the twin's
    ±20 % (which paid for 120-shot binomial noise) to ±5 %, the residual now being only the `counts`
    encoder's ±½-count quantization of the population."""
    m = socmap
    r = responder(CONFIG)
    t1 = 120.0                                              # batches (the model's own unit)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            delays = int_axis(prog, params.get(q), x0="d0", dx="dd")
            out[q] = {"out": _out(np.exp(-delays / t1), prog)}
        return out

    res = T1(_cfg(m, x90_amp=0.495), 0, points=9).run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/T1"] == pytest.approx(_s(t1, m), rel=0.05)


def test_t2_recovers_the_planted_decay(responder, socmap):
    """T2* is a Ramsey at a small applied detuning: the two X90s bracket a swept wait over which the
    kernel ramps a virtual-Z, so P(|1>) = ½ + ½·exp(−Δt/T2)·cos(φ) with φ the ramp the kernel itself
    applies (read off the run's seated (p0, dp) pair, not re-derived). The damped-cosine fit's τ is
    T2*.

    The co-sim twin costs 453 s. Tolerance tightened from ±20/25 % to ±5 % — noiseless, so what is
    left is the `counts` quantization, not shot noise."""
    m = socmap
    r = responder(CONFIG)
    t2 = 200.0

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            waits = int_axis(prog, params.get(q), x0="w0", dx="dw")
            p1 = 0.5 + 0.5 * np.exp(-waits / t2) * np.cos(_vz_phase(prog, params[q]))
            out[q] = {"out": _out(p1, prog)}
        return out

    res = T2(_cfg(m), 0, detune=units.code_to_freq(70, m.params), points=15,
             t0=_s(8, m), dt=_s(16, m)).run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/T2"] == pytest.approx(_s(t2, m), rel=0.05)


@pytest.mark.parametrize("d0_code", [60, -60])
def test_frequency_proposal_moves_the_carrier_toward_f_ge(responder, socmap, d0_code):
    """spec 13 Q4 — the update sign, PINNED. The fringe frequency is |δ + applied|, so its magnitude
    alone cannot tell a carrier that is too high from one that is too low: only the position of the
    V's vertex can (b = −δ), and only if the whole chain — the on-core virtual-Z ramp's sign, the
    axis it ramps, and the proposal's arithmetic — agrees. Get any of them backwards and the
    "correction" DOUBLES the error for one sign while looking perfect for the other, so the detuning
    is planted BOTH ways.

    The answer is the textbook Ramsey fringe of a carrier detuned by δ from the qubit: the phase
    accrued over a wait is the free precession 2π·δ·t PLUS the virtual-Z the kernel ramps, so the
    fringe runs at |δ + applied| with no extra assumption. The co-sim twin costs 2 × 599 s; the
    other half of its claim — that a detuned carrier really does ramp the model's drive axis — is
    RTL physics and stays there for T4b to make an L2 probe.

    Tolerances tightened from the twin's ±25 codes / 0.3× residual (shot noise on 12×96 counts) to
    ±1 code and 0.02×: an exact V has an exact vertex."""
    m = socmap
    r = responder(CONFIG)
    drive = units.code_to_freq(units._freq_code(F_GE, m.params) + d0_code, m.params)
    delta_hz = units.code_to_freq(d0_code, m.params)        # the carrier's error, in Hz
    t2 = 3000.0

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            waits = int_axis(prog, params.get(q), x0="w0", dx="dw")
            t_s = units.ns(waits, m.params) * 1e-9
            phi = _vz_phase(prog, params[q]) + 2 * math.pi * delta_hz * t_s
            p1 = 0.5 + 0.5 * np.exp(-waits / t2) * np.cos(phi)
            out[q] = {"out": _out(p1, prog)}
        return out

    cfg = _cfg(m, qfreq=drive, relax=800)
    cal = Frequency(cfg, 0, detune=units.code_to_freq(200, m.params), n_detune=4,
                    t0=_s(8, m), dt=_s(4, m), points=12, shots=96)
    res = cal.run(r.drv)
    assert res.ok
    assert res.fit[0].params["a"] > 0, "the V must open upward (qcal's negative-curvature guard)"
    assert cal.recovered_detuning_code[0] == pytest.approx(d0_code, abs=1.0)
    err_before = abs(drive - F_GE)
    res.apply()
    assert abs(cfg["qubit/0/freq"] - F_GE) < 0.02 * err_before, \
        "the config frequency did not move toward f_ge"


# ── §8's per-qubit verdict: a cal that runs the whole chip refuses PER QUBIT (spec 20 U0) ──

def test_frequency_verdict_is_per_qubit(responder, socmap):
    """`Frequency` computes `oks = {q: bool}` and used to drop it at the `Result`. With an empty
    `oks`, `Result.apply` is all-or-nothing and raises the moment ANY qubit fails — so one drifted
    qubit vetoed the update of all the others, exactly what the per-qubit apply of spec 13 §8
    exists to prevent (and what the notebook's per-qubit refusal branch reads).

    Both runs answer the textbook Ramsey fringe of a carrier detuned by δ, one δ per core (the
    single-core test's physics, twice). In the second, core 1's T2 is far shorter than the first
    wait, so its fringe is already dead when the sweep starts and every damped-cosine fit refuses:
    core 1 gets no V and no proposal, while core 0's is untouched. `apply()` then has to write core
    0 rather than refuse the pair."""
    m = socmap
    r = responder(CONFIG)
    d_code = {0: 60, 1: -60}                                # each core its own planted detuning
    drive = {q: units.code_to_freq(units._freq_code(F_GE, m.params) + d, m.params)
             for q, d in d_code.items()}

    def run(t2):
        @r.answer
        def _(progs, params):
            out = {}
            for q, prog in progs.items():
                waits = int_axis(prog, params.get(q), x0="w0", dx="dw")
                t_s = units.ns(waits, m.params) * 1e-9
                delta_hz = units.code_to_freq(d_code[q], m.params)
                phi = _vz_phase(prog, params[q]) + 2 * math.pi * delta_hz * t_s
                out[q] = {"out": _out(0.5 + 0.5 * np.exp(-waits / t2[q]) * np.cos(phi), prog)}
            return out

        cfg = _cfg2(m, freqs=(drive[0], drive[1]), relax=800)
        cal = Frequency(cfg, [0, 1], detune=units.code_to_freq(200, m.params), n_detune=4,
                        t0=_s(8, m), dt=_s(4, m), points=12, shots=96)
        return cfg, cal.run(r.drv)

    _, both = run({0: 3000.0, 1: 3000.0})
    assert both.ok and both.oks == {0: True, 1: True}

    cfg, one = run({0: 3000.0, 1: 2.0})                     # core 1 dephases before the first wait
    assert not one.ok and one.oks == {0: True, 1: False}
    before = cfg["qubit/1/freq"]
    one.apply()                                             # must NOT raise: core 0 passed
    assert cfg["qubit/0/freq"] == one.proposal["qubit/0/freq"], "core 0's fit was thrown away"
    assert cfg["qubit/1/freq"] == before, "core 1 failed — its carrier must stay put"


def test_phase_verdict_is_per_qubit(responder, socmap):
    """The same claim for `Phase`, through a different guard: qcal fails a line crossing that falls
    OUTSIDE the swept span (`_line_crossing`'s in_range), so a qubit whose Stark shift is larger
    than the sweep half-width refuses while its neighbour, centred in the span, still writes.

    The answer is `test_heralded_phase_matches_unheralded`'s pair of three-X90 composites, each
    core's projections ½(1 ∓ sin(φ − φ*)) around its OWN planted frame φ*."""
    m = socmap
    r = responder(CONFIG)
    span = 0.3
    stark = {0: 0.1, 1: 0.6}                     # core 1's frame is beyond ±span: no crossing swept

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            d = np.sin(_vz_phase(prog, params[q]) - stark[q])
            p1 = 0.5 * (1 - d) if prog.bindings["seq"] == Y180_X90 else 0.5 * (1 + d)
            out[q] = {"out": _out(p1, prog)}
        return out

    cfg = _cfg2(m, relax=1000)
    res = Phase(cfg, [0, 1], points=11, span=span, shots=64).run(r.drv)
    assert not res.ok and res.oks == {0: True, 1: False}
    assert res.proposal["qubit/0/x90/vz"][0] == pytest.approx(stark[0], abs=0.02)
    assert "qubit/1/x90/vz" not in res.proposal
    res.apply()                                             # must NOT raise: core 0 passed
    assert cfg["qubit/0/x90/vz"] == res.proposal["qubit/0/x90/vz"]
    assert "qubit/1/x90/vz" not in cfg, "core 1 failed — no frame may be written for it"


# ── the readout cals (spec 13 §5): clusters, the discrimination frame, the confusion diagonal ──

def _antipodal_answer(a_over_sigma=RO_A_OVER_SIGMA):
    """One readout, two ways out: a RAW program writes its IQ shots, a COUNTS one writes how many of
    them the on-chip discriminator called |1>. Both come from the SAME draws, so the two decode
    paths can be compared against each other rather than against two noise realisations."""
    def answer(progs, params):
        out = {}
        for q, prog in progs.items():
            z = _antipodal_iq(int(params[q]["prep"]), int(prog.bindings["shots"]),
                              _demod_phase(prog), a_over_sigma=a_over_sigma)
            out[q] = {"out": raw_iq(z) if prog.bindings.get("mode") == RAW
                      else _out([_res_count(z) / len(z)], prog)}
        return out
    return answer


def test_readout_calibration_captures_in_the_zero_demod_frame(socmap):
    """spec 13 §5 — the property that MAKES the demod-phase proposal a fixed point, asserted
    where it lives: on the compiled program. The proposal is ABSOLUTE (rotate the |0>→|1> cluster
    axis onto +real), so the RAW capture must run in the ZERO demod frame — baking the config's
    CURRENT phase into the capture carrier would rotate the measured axis by exactly that stale
    value and turn the 'absolute' proposal relative. Invisible on a co-sim config (stored phase 0),
    wrong on X6Y3 (−109.9°…+39.0°).

    Sharper than the co-sim round trip it replaces: `_rawiq_prog`'s demod slot carries phase code 0
    whatever `readout/0/demod/phase` holds — while a COUNTS program, which must discriminate in the
    calibrated frame, bakes that same phase in. Both halves, one compile each."""
    m = socmap
    cfg = _cfg(m, x90_amp=0.495)
    for stale in (0.0, 1.0, -2.5):
        cfg["readout/0/demod/phase"] = stale
        prog, _ = _rawiq_prog(m, cfg, 0, "X90", 8)
        assert prog.tables["demod"][0][0] == 0, \
            f"the RAW capture baked the stored demod phase {stale} into its carrier"
    cfg["readout/0/demod/phase"] = 1.0
    prog, _ = _ro_amp_prog(m, cfg, 0, "X90", 8, 1, 0, 0)
    assert prog.tables["demod"][0][0] == units._phase_code(1.0), \
        "a COUNTS readout must discriminate in the CALIBRATED frame"


def test_readout_calibration_phase_proposal_is_a_fixed_point(responder, socmap):
    """The other half: the proposal arithmetic. The clusters are the textbook π-out-of-phase readout
    sitting at an arbitrary chain angle; the answer rotates them by whatever demod phase the program
    was compiled at, so a capture that baked a stale phase WOULD drift. Plant a stale nonzero phase,
    calibrate, apply, re-calibrate: the second proposal must equal the first exactly, and it must be
    the phase that lands |0> on +real (i.e. cancels the chain angle).

    Tightened from the co-sim twin's 0.2 rad (which paid for 24-shot cluster noise) to exact: the
    same shots are drawn both times, so a fixed point is a fixed point to the last bit."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_antipodal_answer())
    cfg = _cfg(m, x90_amp=0.495)
    cfg["readout/0/demod/phase"] = 1.0                   # any stale nonzero phase (rad)

    r1 = ReadoutCalibration(cfg, 0, shots=64).run(r.drv)
    assert r1.ok
    r1.apply()
    r2 = ReadoutCalibration(cfg, 0, shots=64).run(r.drv)
    assert r2.ok
    p1, p2 = (x.proposal["readout/0/demod/phase"] for x in (r1, r2))
    assert math.remainder(p2 - p1, 2 * math.pi) == pytest.approx(0.0, abs=1e-12)
    assert math.remainder(p1 + CHAIN_PHASE, 2 * math.pi) == pytest.approx(0.0, abs=0.05), \
        "the proposal must cancel the chain angle, landing |0> on +real"


def test_readout_calibration_returns_per_prep_shots_and_a_classifier(responder, socmap):
    """`acquire_shots` chunking (spec 09/13 §8): ReadoutCalibration issues one RAW rerun per prep
    state over ONE resident image, so each prep comes back as its own `(shots, 2)` block, and the
    trained Classifier rides on the Result for the later steps to reuse instead of retraining.

    The co-sim twin's third claim — that the two clusters really separate (qcal SNR > 1) — is
    `TwoLevelModel` statistics and moved to `test_models.py`."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_antipodal_answer())
    res = ReadoutCalibration(_cfg(m, x90_amp=0.495), 0, shots=16).run(r.drv)
    assert res.data[0]["iq0"].shape == (16, 2) and res.data[0]["iq1"].shape == (16, 2)
    assert isinstance(res.fit[0], Classifier)
    assert len(r.setups) == 1 and len(r.reruns) == 2, "two preps, two reruns of ONE resident image"
    assert set(res.proposal) == {"readout/0/demod/phase", "readout/0/res_sign"}


def test_readout_fidelity_matches_the_host_classifier(responder, socmap):
    """spec 13 §5 — ReadoutFidelity's confusion comes from the `res` bit under the FIXED hardware
    discriminator (two COUNTS reruns; no raw IQ, no retraining). It must agree with the confusion of
    a host Classifier trained on the same readout's clusters — i.e. the on-chip discriminator really
    is the classifier we think it is. The old version could not show this: it retrained on the very
    points it then confused.

    Host-pure the two see the SAME shots, so the comparison isolates the decision rule: the hardware
    thresholds `Re(z)` at a hard ZERO in the calibrated frame, the host at the empirical midpoint of
    the two cluster projections. Those differ by ~σ/√N, so a handful of boundary shots may fall the
    other way — hence atol=0.02 rather than the twin's 0.15 (which was 2σ of two 48-shot estimates),
    a 7× tightening."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_antipodal_answer())
    cfg = _cfg(m, x90_amp=0.495)
    rc = ReadoutCalibration(cfg, 0, shots=400).run(r.drv)
    assert rc.ok
    rc.apply()                                          # fix the discrimination frame + res-sign
    host = rc.fit[0]

    res = ReadoutFidelity(cfg, 0, shots=400).run(r.drv)
    conf, hconf = res.data[0]["confusion"], host.confusion()
    assert 0.7 < res.data[0]["fidelity"] < 1.0, "a saturated confusion would test nothing"
    assert np.allclose(np.diag(conf), np.diag(hconf), atol=0.02)


def test_fidelity_picks_readout_amp(responder, socmap):
    """spec 13 §5 — Fidelity sweeps qcal's knob (the readout DRIVE amplitude, on-core via k_ro_amp)
    and scores the confusion diagonal ½[P(0|0) + P(1|1)] under the FIXED hardware discriminator,
    never retrained per point.

    The answer is the linear resonator: its response is proportional to the drive and the receiver
    noise is not, so the discriminator sits `snr ∝ amp` σ from each cluster and the diagonal is
    1 − Φ(−snr). Modelled MARGINAL at the config amplitude (snr = 1), which is the situation a
    Fidelity sweep exists for. That makes the diagonal strictly monotone in the drive — sharper than
    the twin's 'the argmax lands somewhere in the sweep', since there is no interior optimum for a
    linear cavity."""
    m = socmap
    r = responder(CONFIG)
    cfg = _cfg(m, x90_amp=0.495)
    a_cfg = float(cfg["readout/0/amp"])

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            amps = q16_axis(prog, params.get(q)) / units.AMP_SCALE
            eps = _misassign(amps / a_cfg)               # snr = 1 at the config amplitude
            prep = int(params[q]["prep"])
            out[q] = {"out": _out(eps if prep == 0 else 1 - eps, prog)}
        return out

    res = Fidelity(cfg, 0, amp_span=0.45, points=5, shots=24).run(r.drv)
    amps, fid = res.data[0]["x"], res.data[0]["y"]
    assert res.ok
    assert np.all(np.diff(fid) > 0), f"the diagonal is not monotone in the drive amplitude: {fid}"
    assert fid[-1] - fid[0] > 0.1, "the diagonal does not respond to the readout amplitude"
    assert res.proposal["readout/0/amp"] == pytest.approx(amps[-1])


def test_fidelity_sweeps_the_full_span_at_tiny_amp(responder, socmap):
    """qcal's Fidelity sweeps exactly ±amp_span around the config amp; the old AMP_MIN = 0.01 floor
    silently truncated the lower half-span for X6Y3-class readout amps (q5: 0.0115 lost 54 % of it).
    At amp 0.012, span 0.005, the first swept point must be 0.007 — not the clamped 0.01. Only the
    realized sweep axis is the claim, so the populations are left flat.

    Tightened from the twin's abs=1e-4 to the exact code the sweep realizes: `_amp_code(0.007)`."""
    m = socmap
    r = responder(CONFIG)
    r.answer(lambda progs, params: {q: {"out": _out(np.zeros(int(p.bindings["npts"])), p)}
                                    for q, p in progs.items()})
    cfg = _cfg(m, x90_amp=0.495)
    cfg["readout/0/amp"] = 0.012
    res = Fidelity(cfg, 0, amp_span=0.005, points=3, shots=16).run(r.drv)
    xs = res.data[0]["x"]
    assert xs[0] == units._amp_code(0.007) / units.AMP_SCALE, "the lower half-span was clamped away"
    assert xs[-1] == units._amp_code(0.017) / units.AMP_SCALE


def test_window_picks_the_longer_integration(responder, socmap):
    """The demod-window sweep — OURS, not qcal's (spec 13 §5) — retunes the window via
    write_slot + rerun (no recompile, spec 08 §4) and is scored exactly like Fidelity: the confusion
    diagonal under the fixed discriminator, not a classifier retrained per window.

    The answer is coherent integration against white noise: a window of w batches collects SNR ∝ √w,
    so the longer one must win. The window is read off the slot the class actually wrote — a retune
    that never reached the slot would leave both points identical and the test would fail."""
    m = socmap
    r = responder(CONFIG)
    durs = (16, 64)                                     # candidate windows (batches)
    snr_per_sqrt_batch = 0.25                           # ⇒ snr 1.0 at 16 batches, 2.0 at 64

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            win = r.slot(q, "demod", 0, "dur")
            assert win is not None, "Window did not retune the demod slot before rerunning"
            eps = _misassign(snr_per_sqrt_batch * math.sqrt(win))
            prep = int(params[q]["prep"])
            out[q] = {"out": _out([eps if prep == 0 else 1 - eps], prog)}
        return out

    cfg = _cfg(m, x90_amp=0.495, dur=64, drive=80)      # the drive covers the longest window
    res = Window(cfg, 0, durs=[_s(d, m) for d in durs], shots=24).run(r.drv)
    fid = res.data[0]["y"]
    assert res.ok
    assert fid[1] > fid[0] + 0.05, "the longer integration window did not read out better"
    assert res.proposal["readout/0/demod/dur"] == pytest.approx(_s(durs[1], m))


def test_window_sweeps_each_qubit_around_its_own_timing(responder, socmap):
    """spec 20 U3 — `durs` as a `{q: [seconds]}` dict. The reference centres each window sweep on
    THAT qubit's current timing (`± span + cfg[readout/{q}/…]`), which one shared list cannot do
    once two qubits sit at different timings; the cores still step through their lists TOGETHER, one
    rerun pair per index, so the readout stays simultaneous (spec 13 §8).

    The answer is the matched filter, from first principles: a readout tone lasting T batches puts
    signal ∝ min(w, T) into a window of w while the noise grows as √w, so the SNR peaks exactly at
    w = T. The two cores are planted with different T and each given the three candidates centred on
    its own; each argmax has to land on its own optimum. One shared list — or a retune that reached
    only one core — cannot satisfy both."""
    m = socmap
    r = responder(CONFIG)
    tone = {0: 32, 1: 96}                               # each core's readout tone length (batches)
    durs = {q: [_s(t // 2, m), _s(t, m), _s(3 * t // 2, m)] for q, t in tone.items()}

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            w = r.slot(q, "demod", 0, "dur")
            assert w is not None, f"Window did not retune core {q}'s demod slot before rerunning"
            eps = _misassign(0.2 * min(w, tone[q]) / math.sqrt(w))
            out[q] = {"out": _out([eps if int(params[q]["prep"]) == 0 else 1 - eps], prog)}
        return out

    cfg = _cfg2(m, x90_amp=0.495)
    for q in tone:                                      # the config sits at the longest candidate
        cfg[f"readout/{q}/demod/dur"] = _s(3 * max(tone.values()) // 2, m)
        cfg[f"readout/{q}/dur"] = _s(2 * max(tone.values()), m)
    res = Window(cfg, [0, 1], durs=durs, shots=200).run(r.drv)

    assert res.ok
    for q, t in tone.items():
        assert int(np.argmax(res.data[q]["y"])) == 1, \
            f"core {q}'s best window is not the one matched to its own tone: {res.data[q]['y']}"
        assert res.proposal[f"readout/{q}/demod/dur"] == pytest.approx(_s(t, m))


def test_window_delay_is_swept_as_a_per_core_param(responder, socmap):
    """The same dict sweep on `demod/delay`, which is NOT a table field — the kernel adds it to the
    demod's play time — so it rides a per-run param instead of a `write_slot`, and that param has to
    be written per CORE (spec 20 U3).

    The answer is the echo's arrival: core q's readout comes back after its own round trip τ_q, so a
    window as long as the tone collects max(0, T − |d − τ_q|) of it and the diagonal peaks exactly at
    d = τ_q. One `ddly` shared by both cores would move them together and one of them would miss."""
    m = socmap
    r = responder(CONFIG)
    tone = 32                                           # the echo's length (batches), both cores
    tau = {0: 16, 1: 48}                                # ... but each core's round trip differs
    durs = {q: [_s(t - 16, m), _s(t, m), _s(t + 16, m)] for q, t in tau.items()}

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            overlap = max(0.0, tone - abs(int(params[q]["ddly"]) - tau[q]))
            eps = _misassign(0.06 * overlap)
            out[q] = {"out": _out([eps if int(params[q]["prep"]) == 0 else 1 - eps], prog)}
        return out

    cfg = _cfg2(m, x90_amp=0.495)
    longest = max(tau.values()) + 16
    for q in tau:                                       # the config sits at the longest candidate
        cfg[f"readout/{q}/demod/dur"] = _s(tone, m)
        cfg[f"readout/{q}/demod/delay"] = _s(longest, m)
        cfg[f"readout/{q}/dur"] = _s(2 * (longest + tone), m)
    res = Window(cfg, [0, 1], durs=durs, shots=200, knob="demod/delay").run(r.drv)

    assert res.ok
    for q, t in tau.items():
        assert int(np.argmax(res.data[q]["y"])) == 1, \
            f"core {q} did not peak on its own round trip: {res.data[q]['y']}"
        assert res.proposal[f"readout/{q}/demod/delay"] == pytest.approx(_s(t, m))


# ── Separation / Punchout: the dispersive resonator sweeps ──

CHI_CODE, KAPPA_CODE = 60, 170     # 2χ/κ ≈ 0.7: |0> peaks at f_r + χ, separation at f_r
VNA_NOISE = 0.1                    # receiver noise, as a fraction of the on-resonance response


def _vna_answer(r, m, noise=VNA_NOISE, amp_slot=False):
    """The dispersive readout's answer for a k_vna sweep: at each swept drive frequency the
    resonator responds with the Lorentzian its state pulls, and the receiver adds Gaussian noise.
    `amp_slot` scales the response by the drive amplitude the class wrote into the `ro` slot
    (Punchout's outer loop) — the resonator answers ITS DRIVE."""
    f_r = units.demod_code_to_freq(2048, m.params)
    chi, kappa = (units.code_to_freq(c, m.params) for c in (CHI_CODE, KAPPA_CODE))

    def answer(progs, params):
        out = {}
        for q, prog in progs.items():
            prep = int(params[q]["prep"])
            npts, shots = (int(prog.bindings[k]) for k in ("npts", "shots"))
            s = _lorentzian(_vna_freqs(prog, params.get(q), m), f_r, kappa, chi, 1 - 2 * prep)
            gain = (r.slot(q, "ro", 0, "amp") / units.AMP_SCALE) if amp_slot else 1.0
            rng = np.random.default_rng(prep)
            z = np.repeat(gain * s, shots)
            z = z + noise * (rng.normal(0, 1, z.size) + 1j * rng.normal(0, 1, z.size))
            out[q] = {"out": raw_iq(IQ_SCALE * z)}
        return out
    return answer


def _iqsum_answer(m, noise=0.0):
    """The same cavity for a k_vna IQSUM sweep: |0> only (spectroscopy never preps), the shots
    summed on-core the way the kernel does."""
    f_r = units.demod_code_to_freq(2048, m.params)
    chi, kappa = (units.code_to_freq(c, m.params) for c in (CHI_CODE, KAPPA_CODE))

    def answer(progs, params):
        out = {}
        for q, prog in progs.items():
            shots, sh = (int(prog.bindings[k]) for k in ("shots", "sh"))
            s = _lorentzian(_vna_freqs(prog, params.get(q), m), f_r, kappa, chi, +1)
            if noise:
                rng = np.random.default_rng(q)
                s = s + noise * (rng.normal(0, 1, s.size) + 1j * rng.normal(0, 1, s.size))
            out[q] = {"out": iq_sum(IQ_SCALE * s, shots, sh)}
        return out
    return answer


def test_separation_picks_max_separation_not_the_magnitude_peak(responder, socmap):
    """spec 13 §5 — THE regression that catches the old Separation. On a dispersive readout the |0>
    response peaks at f_r + χ while the two-state separation peaks at f_r, so the |0>-magnitude
    argmax (what the old |0>-only VNA took) and the cluster-SNR argmax (qcal's statistic, what we
    take now) are DIFFERENT grid points. Separation runs the matched-pair sweep at both prep states
    (k_vna RAW, two reruns of one resident program) and must pick the latter.

    The answer is the resonator from first principles: S(f) = 1/(1 + 2i(f − f_r ∓ χ)/κ) at
    2χ/κ ≈ 0.7, with the five swept codes landing on f_r + {−2χ, −χ, 0, +χ, +2χ}."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_vna_answer(r, m))
    cfg = _cfg(m, x90_amp=0.495)
    f_r = float(cfg["readout/0/freq"])
    chi = units.code_to_freq(CHI_CODE, m.params)

    res = Separation(cfg, 0, span=2 * chi, points=5, shots=64).run(r.drv)
    xs, sep, mag0 = (res.data[0][k] for k in ("x", "y", "mag0"))
    best, peak = int(np.argmax(sep)), int(np.argmax(mag0))
    assert res.ok
    assert xs[peak] == pytest.approx(f_r + chi), \
        "the |0> magnitude does not peak at the |0> dressed resonance"
    assert best != peak, "max separation and the |0>-magnitude peak coincide — the gate is vacuous"
    assert xs[best] == pytest.approx(f_r), "Separation did not pick the max-separation frequency"
    assert res.proposal["readout/0/freq"] == pytest.approx(f_r)


def test_separation_proposes_physical_hz_not_the_alias(responder, socmap):
    """The 16-bit sweep codes alias (Nyquist fold): on X6Y3 the 6.55 GHz readout, synthesized in the
    DAC's 2nd Nyquist zone, folds to a code whose code_to_freq is ≈ −1.44 GHz — which is what
    the old proposal wrote back into the tree of record. The proposal must be DELTA-based physical
    Hz (f0 + code_to_freq(best − c0)): store the readout freq as the out-of-band alias f − fs — the
    IDENTICAL hardware code bit-for-bit, so the run itself is unchanged — and the proposal must
    come back in that same band, within the swept span of the stored value."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_vna_answer(r, m))
    cfg = _cfg(m, x90_amp=0.495)
    f_alias = float(cfg["readout/0/freq"]) - units.sample_rate(m.params)
    assert units._freq_code(f_alias, m.params) == units._freq_code(cfg["readout/0/freq"], m.params)
    cfg["readout/0/freq"] = f_alias
    span = 2 * units.code_to_freq(CHI_CODE, m.params)

    res = Separation(cfg, 0, span=span, points=5, shots=64).run(r.drv)
    assert res.ok
    assert abs(res.proposal["readout/0/freq"] - f_alias) <= span, \
        "the proposal must stay delta-based in the stored band, not jump to the baseband alias"
    assert np.all(np.abs(res.data[0]["x"] - f_alias) <= span)   # the x-axis is in-band Hz too


def test_punchout_maps_frequency_against_drive_power(responder, socmap):
    """The punchout map — walkthrough stage 1.2 (spec 14 F2). ONE k_vna program per qubit, then a
    `write_slot("ro", 0, "amp")` + a |0> rerun per amplitude, so the map is (amps × points) of |S21|
    at the |0> resonator.

    On a LINEAR resonator the dressed peak does NOT walk with power (real punchout needs a nonlinear
    cavity), so what is gated is what this physics can show, and it is exactly what a mis-wired amp
    loop would break: every row peaks at the same dressed frequency (f_r + χ) and the rows scale
    with the written drive amplitude. Noiseless, so the linearity is exact to the amp CODE's
    rounding — rel=0.01 instead of the twin's 0.25."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_vna_answer(r, m, noise=0.0, amp_slot=True))
    cfg = _cfg(m, x90_amp=0.495)
    f_r = float(cfg["readout/0/freq"])
    chi = units.code_to_freq(CHI_CODE, m.params)
    amps = [0.1, 0.3, 0.6]

    res = Punchout(cfg, 0, amps=amps, span=2.5e6, points=21, shots=16).run(r.drv)
    mag, freqs = res.data[0]["mag"], res.data[0]["x"]
    assert mag.shape == (len(amps), 21)
    peaks = {int(np.argmax(row)) for row in mag}
    assert len(peaks) == 1, f"the dressed peak moved with power on a LINEAR resonator: {peaks}"
    step = float(freqs[1] - freqs[0])
    assert abs(freqs[peaks.pop()] - (f_r + chi)) <= step, "the rows miss the |0> resonance"
    rowmax = np.array([row.max() for row in mag])
    assert np.all(np.diff(rowmax) > 0), f"the response did not grow with drive amplitude: {rowmax}"
    assert rowmax[2] / rowmax[0] == pytest.approx(
        units._amp_code(amps[2]) / units._amp_code(amps[0]), rel=0.01)


def test_resonator_scans_the_cavity_coherently(responder, socmap):
    """Resonator spectroscopy (qcal's `Resonator`, spec 20 §8) — the reference session's first cell:
    the |0> response over an arbitrary frequency list, `shots` integrals summed ON-CORE (k_vna
    IQSUM) so the scan is ONE run of two words per point.

    Same planted cavity as `Separation`'s, so the answers are known from first principles: the |0>
    magnitude peaks at the dressed resonance f_r + χ, and the reported x-axis is the caller's own
    frequencies. Two things this sweep must not lose:

      * the SCALE — the mean IQ has to come back through `>> sh` and `/ shots`, so it is checked
        against the Lorentzian itself, not just its argmax;
      * the PHASE — the sum is coherent (qcal's `iq.mean(axis=1)`, the reason its figure has an
        unwrapped-phase panel), so the response swings through ~π across the resonance. A power
        sum (`re² + im²`, what `vna.ipynb`'s wideband kernel accumulates) would still peak in the
        right place and would still pass every magnitude assert here."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_iqsum_answer(m))
    cfg = _cfg(m, x90_amp=0.495)
    f_r = float(cfg["readout/0/freq"])
    chi, kappa = (units.code_to_freq(c, m.params) for c in (CHI_CODE, KAPPA_CODE))
    freqs = f_r + np.linspace(-2 * chi, 4 * chi, 13)          # the |0> peak lands on index 6

    res = Resonator(cfg, 0, freqs={0: freqs}, shots=64).run(r.drv)
    x, iq, mag = (res.data[0][k] for k in ("x", "iq", "mag"))
    step = float(x[1] - x[0])
    assert np.allclose(x, freqs, atol=step)          # the caller's band, not the folded code's alias
    assert int(np.argmax(mag)) == 6, f"|S| did not peak at the |0> dressed resonance: {mag}"
    assert mag == pytest.approx(IQ_SCALE * np.abs(_lorentzian(x, f_r, kappa, chi, +1)), rel=0.01), \
        "the on-core sum did not come back as the mean IQ (>> sh / shots)"
    phase = np.unwrap(np.angle(iq))
    assert np.all(np.diff(phase) < 0), f"the coherent phase is not monotone across the cavity: {phase}"
    assert phase == pytest.approx(np.angle(_lorentzian(x, f_r, kappa, chi, +1)), abs=0.01), \
        "the phase is not the cavity's — the sum was not coherent"
    assert phase[0] - phase[-1] > 2.0, \
        "the response barely turned across ±3χ (arctan bound: 2.26 rad here, π asymptotically)"


def test_resonator_scan_folds_past_the_half_rate(responder, socmap):
    """A bring-up scan that crosses the DAC's half rate — start below it, end above, which is where
    X6Y3's readout band lives. The code ramp runs past 2^15, and that is not an error: the kernel's
    accumulator is a plain int32, so its wrap IS the fold the phase accumulator does in hardware and
    the register keeps the code mod 2^16 — the same aliasing `units._freq_code` applies to a single
    tone. So the host keeps the ramp UNFOLDED (it is the frequency axis the caller asked for, and it
    must not jump a whole fs at the crossing), and what the register sees at each point must be the
    code that frequency would have been programmed with directly."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_iqsum_answer(m))
    fs = units.sample_rate(m.params)
    freqs = np.linspace(0.4 * fs, 0.6 * fs, 21)          # straight through the half rate

    res = Resonator(_cfg(m), 0, freqs={0: freqs}, shots=16).run(r.drv)
    x = res.data[0]["x"]
    assert np.all(np.diff(x) > 0), f"the reported axis folded with the code: {x}"
    code = fs / (1 << 16)
    assert x == pytest.approx(freqs, abs=1.5 * code)    # the ramp is integer codes: the `>> 16`
    #                        floor costs up to a code, the rounded Q16 step up to half of one more

    codes = q16_axis(r.setups[-1][0], x0="c0q", dx="dcq")      # the host's unfolded ramp
    assert codes.max() >= (1 << 15), "the scan never crossed the fold — the gate is vacuous"
    seen = ((codes + (1 << 15)) % (1 << 16)) - (1 << 15)       # what the int32 wrap leaves behind
    want = [units._freq_code(float(f), m.params) for f in x]
    assert np.all(np.abs(seen - want) <= 1), \
        f"the folded codes are not the tones' own: {seen[:3]}... vs {want[:3]}..."


def _hanger_answer(m, dips, depth=0.6):
    """A HANGER-coupled cavity for a k_vna IQSUM sweep: S = 1 − depth·Σ L(f), which DIPS at each
    planted resonance instead of peaking — the notch geometry qcal's rule reads. (`_iqsum_answer`'s
    cavity is a TRANSMISSION Lorentzian, a peak, and has no dip at all.) The scans below put ~10
    points across κ: qcal smooths with σ = 3 SAMPLES before it gates, so a notch narrower than that
    is washed out below its own half-depth threshold and reads as no dip at all."""
    kappa = units.code_to_freq(KAPPA_CODE, m.params)

    def answer(progs, params):
        out = {}
        for q, prog in progs.items():
            shots, sh = (int(prog.bindings[k]) for k in ("shots", "sh"))
            f = _vna_freqs(prog, params.get(q), m)
            s = 1.0 + 0j - depth * sum(_lorentzian(f, d, kappa, 0.0, +1) for d in dips)
            out[q] = {"out": iq_sum(IQ_SCALE * s, shots, sh)}
        return out
    return answer


def test_resonator_proposes_the_notch_it_found(responder, socmap):
    """qcal's dip rule (`Resonator.analyze`, resonator.py:527-556) and the write-back its
    `Characterize.final` does: smooth the dB trace, keep the local minima at least halfway down from
    the mean to the deepest point, and — when exactly ONE qualifies — propose it as
    `readout/{q}/freq`. The planted notch sits 3κ off the tree's stored probe, so recovering it is a
    real measurement and `apply()` has somewhere to move the Config to."""
    m = socmap
    r = responder(CONFIG)
    cfg = _cfg(m)
    f_r = float(cfg["readout/0/freq"])
    kappa = units.code_to_freq(KAPPA_CODE, m.params)
    dip = f_r + 3 * kappa
    r.answer(_hanger_answer(m, [dip]))
    freqs = f_r + np.linspace(-10 * kappa, 10 * kappa, 201)

    res = Resonator(cfg, 0, freqs={0: freqs}, shots=64).run(r.drv)
    d = res.data[0]
    step = float(d["x"][1] - d["x"][0])
    assert not d["fallback"], "a planted notch did not clear qcal's half-depth gate"
    assert len(d["peaks"]) == 1, f"one resonator in band, {len(d['peaks'])} dips: {d['peaks']}"
    assert d["notch"] == pytest.approx(dip, abs=2 * step), \
        f"the fitted notch missed the planted resonance by {(d['notch'] - dip) / kappa:.2f} kappa"
    assert res.ok and res.oks[0]
    assert res.proposal == {"readout/0/freq": d["notch"]}
    res.apply()
    assert cfg["readout/0/freq"] == pytest.approx(dip, abs=2 * step)


def test_resonator_refuses_a_band_holding_two_resonators(responder, socmap):
    """Two dips in one trace is qcal's "Too many peaks" — it characterizes nothing there, and the
    reference session's wideband 6.53 → 6.85 GHz scan (eight resonators) is exactly this case. The
    frequency is not proposed, `apply()` refuses outright, and every qualifying dip comes back in
    `data[q]["peaks"]` for the caller to read."""
    m = socmap
    r = responder(CONFIG)
    cfg = _cfg(m)
    f_r = float(cfg["readout/0/freq"])
    kappa = units.code_to_freq(KAPPA_CODE, m.params)
    dips = [f_r - 8 * kappa, f_r + 8 * kappa]
    r.answer(_hanger_answer(m, dips))
    freqs = f_r + np.linspace(-16 * kappa, 16 * kappa, 321)

    res = Resonator(cfg, 0, freqs={0: freqs}, shots=64).run(r.drv)
    d = res.data[0]
    step = float(d["x"][1] - d["x"][0])
    assert d["peaks"] == pytest.approx(dips, abs=2 * step), \
        f"the two dips were not both found: {d['peaks']}"
    assert math.isnan(d["notch"]) and not res.ok and not res.oks[0]
    assert res.proposal == {}
    with pytest.raises(RuntimeError):
        res.apply()
    assert cfg["readout/0/freq"] == f_r          # nothing moved


def test_resonator_does_not_propose_a_transmission_peak(responder, socmap):
    """The dip rule assumes a hanger notch. Our planted cavity — and `TwoLevelModel`'s, spec 17 §3
    E6 — is a TRANSMISSION Lorentzian whose |0> response PEAKS, so nothing clears the gate: qcal
    falls back to the raw magnitude's argmin (here a band edge) and writes it, we report the same
    number flagged `fallback` and propose nothing (README principle 6)."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_iqsum_answer(m))
    cfg = _cfg(m)
    f_r = float(cfg["readout/0/freq"])
    chi = units.code_to_freq(CHI_CODE, m.params)
    freqs = f_r + np.linspace(-6 * chi, 6 * chi, 61)

    res = Resonator(cfg, 0, freqs={0: freqs}, shots=64).run(r.drv)
    d = res.data[0]
    assert d["fallback"] and len(d["peaks"]) == 0
    assert d["notch"] == pytest.approx(float(d["x"][int(np.argmin(d["mag"]))]))
    assert not res.ok and not res.oks[0] and res.proposal == {}


# ── §8 heralding: the (count, kept) decode and its denominator ──

def test_heralding_matches_unheralded_on_clean_qubit(responder, socmap):
    """spec 13 §8 — a `readout/herald` counts run inserts a readout BEFORE the sequence and counts
    only the shots that found the qubit in |0> (`P = count/kept`). On a clean qubit the herald
    passes every shot, so `count/kept` must equal `count/shots`: heralded and unheralded Rabi are
    the SAME curve, and the recovered rate the same rate.

    What is host-pure here is exactly that decode — the kernel writes an interleaved (count, kept)
    pair per point instead of one count, and `population_heralded` has to divide by `kept`, not by
    `shots`. A wrong denominator scales P away and the fit follows. The twin compared at atol=0.2
    (3σ of 64-shot noise); against the same answered populations the curves must agree EXACTLY.

    NOT moved: the two-window grid geometry that makes the herald read land a full scheduling lead
    before the drive. That is timing, and it stays in test_cal.py for T4b."""
    m = socmap
    r = responder(CONFIG)
    g = _sigma_per_code(m)
    rabi = float(3 * math.pi / (g * units.AMP_SCALE))

    @r.answer
    def _(progs, params):
        return {q: {"out": _out((1 - np.cos(rabi * g * q16_axis(p, params.get(q)))) / 2, p)}
                for q, p in progs.items()}

    cfg = _cfg(m)
    cfg["readout/herald"] = False
    off = Amplitude(cfg, 0, n_gates=1, points=9, shots=64).run(r.drv)
    cfg["readout/herald"] = True
    on = Amplitude(cfg, 0, n_gates=1, points=9, shots=64).run(r.drv)

    assert off.ok and on.ok
    assert r.setups[-1][0].arrays["out"] == 2 * 9, "a heralded kernel writes (count, kept) pairs"
    assert np.array_equal(off.data[0]["y"], on.data[0]["y"]), \
        "heralded and unheralded populations must agree (the herald passes every clean-|0> shot)"
    assert on.proposal["qubit/0/rabi"] == off.proposal["qubit/0/rabi"]


def test_heralded_readout_fidelity_matches_unheralded(responder, socmap):
    """The same herald fold in k_ro_amp (spec 13 §8): qcal's transpiler post-selects EVERY circuit,
    the confusion circuits included. On a clean |0> qubit the heralded confusion diagonal must match
    the unheralded one — the (count, kept) decode again, this time through `_diagonal`'s two reruns.
    Exact, where the co-sim twin allowed atol=0.15 for 32-shot noise."""
    m = socmap
    r = responder(CONFIG)
    r.answer(_antipodal_answer())
    cfg = _cfg(m, x90_amp=0.495)
    cfg["readout/herald"] = False
    off = ReadoutFidelity(cfg, 0, shots=64).run(r.drv)
    cfg["readout/herald"] = True
    on = ReadoutFidelity(cfg, 0, shots=64).run(r.drv)
    assert r.setups[-1][0].arrays["out"] == 2, "a heralded single-point run writes (count, kept)"
    assert 0.7 < off.data[0]["fidelity"] < 1.0, "a saturated confusion would test nothing"
    assert np.array_equal(on.data[0]["confusion"], off.data[0]["confusion"])


def test_heralded_phase_matches_unheralded(responder, socmap):
    """The same herald fold in k_phase (spec 13 §8): qcal post-selects both Phase sequences, so the
    line crossing has to come back identical on a clean |0> qubit.

    The answer is the pair of sequences from first principles: both are three-X90 composites that
    land on the equator when the frame is right, so their |1> populations are ½ at the calibrated
    phase and depart with OPPOSITE sign — the projections are ½(1 ∓ sin(φ − φ*)) in the swept
    virtual-Z, which is the pair of near-lines qcal crosses. Nothing is planted, so φ* = 0.
    Exact, where the twin allowed 0.3 rad for 32-shot noise on the crossing."""
    m = socmap
    r = responder(CONFIG)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            d = np.sin(_vz_phase(prog, params[q]))          # φ* = 0: no Stark planted
            p1 = 0.5 * (1 - d) if prog.bindings["seq"] == Y180_X90 else 0.5 * (1 + d)
            out[q] = {"out": _out(p1, prog)}
        return out

    cfg = _cfg(m, relax=1000)
    cfg["readout/herald"] = False
    off = Phase(cfg, 0, points=7, span=0.3, shots=32).run(r.drv)
    cfg["readout/herald"] = True
    on = Phase(cfg, 0, points=7, span=0.3, shots=32).run(r.drv)
    assert off.ok and on.ok
    assert r.setups[-1][0].arrays["out"] == 2 * 7, "a heralded kernel writes (count, kept) pairs"
    v_off, v_on = (x.proposal["qubit/0/x90/vz"][0] for x in (off, on))
    assert v_on == v_off
    assert v_off == pytest.approx(0.0, abs=1e-9), "an unbiased pair of lines crosses at 0"
