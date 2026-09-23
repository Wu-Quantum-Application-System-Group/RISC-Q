"""Two-qubit CZ calibration — host-pure tests (specs/two-qubit/01, /04): the config-frequency seed,
the `two_qubit` schema round-trip, the (i, j) key convention, the coupler-core role lookup, the
joint-readout shot-index zip, the layout-aware pulse-list resolvers (`riscq.cal.cz`) and the
Ramsey-peak / signed-fringe analysis the CZ classes fit with. The cross-core alignment on the real
3-core co-sim is test_twoqubit_cosim.

Retired at universal-cal V6 (the one batched kernel replaced the per-class kernels, so the tests of
those kernels' internals went with them):

- `ef_table` / `cz_table` / `cz_drive_table` slot roles, `_cz_cond_progs`' lock-step compile, the
  `k_cz_pop` / `k_cz_local` / `k_ef_*` compile gates and `_sandwich_binds` — there is no per-class
  kernel any more. The timing rules those tables encoded (the mid-shot retune's LEAD gap, the
  train pacing, the end-anchor) are tests/test_sequence.py; the pair sequences that replace them
  are tests/test_cals_twoqubit.py (incl. the EF-sandwich shelf and the coupler/drive forms); the
  bit-exact signal parity of every pair class against the old kernels — every core's DAC windows —
  is recorded at git commit 347f749 in `tests/test_parity_2q.py` of that commit.
- the `RelativePhase` peak, the `SpectatorPhase` fringe and the `CZAmpFreqSweep` argmax on the
  responder: the same claims, on the new program shape, are in tests/test_cals_twoqubit.py.
- the EF virtual-Z bracket asserted on generated C (`ef_vz` words in the source): the bracket is
  now the compiler's one frame rule per (channel, carrier), gated in tests/test_sequence.py.
"""

import copy
import math
import re
from pathlib import Path

import numpy as np
import pytest

from riscq.cal import (JAZZ, ClassifierN, Config, CZAmpFreqSweep, CZAmplitude, CZFrequency, CZSweep,
                       EFAmplitude, EFPhase, LocalPhases, SpectatorPhase, calc_cz_frequency,
                       coupler_core, cz_coupler_form, cz_sandwich, joint_populations, pair_key)
from riscq.cal.base import _levels_pop, gate_ch, gate_sigma
from riscq.cal.cals.twoqubit import _phi_axis
from riscq.cal.cz import (_branch_correction, _cz_amp, _cz_drive_indices, _cz_entry, _cz_local_set,
                          _cz_pulse, _cz_pulse_set, _cz_rel_phase_set, _cz_spectator_set,
                          _cz_vz_entry, _fit_complex_freq, _fringe_peak, _local_phase_code,
                          _mean_offset, _signed_fft_freq)
from riscq.cal.gates import drive_sigma, resolve
from riscq.map import SocMap, SocParams, pack16
from riscq.pulses import envelopes, units
from tests.responder import q16_axis
from tests.test_sequence import _phase_axis

_SIM2Q = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"
_SIM2Q1C = Path(__file__).resolve().parents[1] / "configs" / "sim-2q1c.json"
_X6Y3 = Path(__file__).resolve().parents[2] / "examples" / "cal-config-x6y3.yaml"


def test_pair_key_matches_qcal_tuple_repr():
    assert pair_key((0, 1)) == "(0, 1)"
    assert pair_key((3, 12)) == "(3, 12)"
    assert pair_key((0, 1)) == str((0, 1))          # interchangeable with qcal's str(qp) key


def test_calc_cz_frequency_02_and_20():
    """qcal calculate_parametric_cz_frequency (cz.py:37-77): CZ/freq = |f_state − f_11|, with
    f_11 = f_GE(i)+f_GE(j), f_02 = f_GE(j)+f_EF(j), f_20 = f_GE(i)+f_EF(i)."""
    cfg = Config()
    cfg["qubit/0/freq"] = 5.0e9
    cfg["qubit/1/freq"] = 5.2e9
    cfg["qubit/0/EF/freq"] = 4.7e9      # f_GE + anharmonicity (−300 MHz)
    cfg["qubit/1/EF/freq"] = 4.9e9

    calc_cz_frequency(cfg, [(0, 1)], state="02")
    # f_11 = 10.2e9; f_02 = 5.2e9 + 4.9e9 = 10.1e9; |10.1 − 10.2| = 100 MHz
    assert cfg["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(100e6)

    calc_cz_frequency(cfg, [(0, 1)], state="20")
    # f_20 = 5.0e9 + 4.7e9 = 9.7e9; |9.7 − 10.2| = 500 MHz
    assert cfg["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(500e6)


def test_calc_cz_frequency_multiple_pairs_and_guard():
    cfg = Config()
    for q, f in ((0, 5.0e9), (1, 5.2e9), (2, 5.4e9)):
        cfg[f"qubit/{q}/freq"] = f
        cfg[f"qubit/{q}/EF/freq"] = f - 0.3e9
    calc_cz_frequency(cfg, [(0, 1), (1, 2)], state="02")
    assert cfg["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(abs((5.2e9 + 4.9e9) - (5.0e9 + 5.2e9)))
    assert cfg["two_qubit/(1, 2)/CZ/freq"] == pytest.approx(abs((5.4e9 + 5.1e9) - (5.2e9 + 5.4e9)))
    with pytest.raises(AssertionError):
        calc_cz_frequency(cfg, [(0, 1)], state="11")


def test_two_qubit_schema_round_trips_through_yaml(tmp_path):
    """The coupler-drive CZ layout (spec 01 §2): the (i, j) key, the pulse list, the coupler core, and
    the CZ freq survive a YAML save/load — a plain slash-path tree, no special loader."""
    cfg = Config()
    cfg["two_qubit/(0, 1)/core"] = 2
    cfg["two_qubit/(0, 1)/CZ/freq"] = 213.0e6
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "C0_1", "time": 200.0e-9, "kwargs": {"amp": 0.35, "phase": 0.0},
         "env": {"env_func": "square", "ramp_fraction": 0.1}},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.0}},
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": 0.0}},
    ]
    cfg["two_qubit/(0, 1)/ZZ11"] = 0.0

    path = tmp_path / "twoq.yaml"
    cfg.save(path)
    back = Config.load(path)
    assert back["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(213.0e6)
    assert coupler_core(back, (0, 1)) == 2
    assert back["two_qubit/(0, 1)/CZ/pulse"][0]["channel"] == "C0_1"
    assert back["two_qubit/(0, 1)/CZ/pulse"][1]["env"] == "virtualz"
    assert back["two_qubit/(0, 1)/ZZ11"] == 0.0
    assert back.to_dict() == cfg.to_dict()


def test_joint_populations_zips_by_shot_index():
    """The two-qubit readout zip (spec 01 §5): shot k on control and shot k on target are the same
    repetition. A deterministic per-shot pattern must land in the right joint bin."""
    control = np.array([0, 0, 1, 1, 1, 0])
    target = np.array([0, 1, 0, 1, 1, 0])
    p = joint_populations({0: control, 1: target}, order=(0, 1))
    # bins: 00 (shots 0,5)=2, 01 (shot 1)=1, 10 (shot 2)=1, 11 (shots 3,4)=2  →  /6
    assert np.allclose(p, np.array([2, 1, 1, 2]) / 6)
    assert p.sum() == pytest.approx(1.0)
    # order matters: swapping control/target swaps the 01 and 10 bins
    p_swapped = joint_populations({0: control, 1: target}, order=(1, 0))
    assert np.allclose(p_swapped, np.array([2, 1, 1, 2]) / 6)   # symmetric counts here, but...
    ctrl2, tgt2 = np.array([1, 0]), np.array([0, 0])
    # control=[1,0], target=[0,0]: shot0 → 10, shot1 → 00  ⇒  [P00, P01, P10, P11] = [.5, 0, .5, 0]
    assert np.allclose(joint_populations({0: ctrl2, 1: tgt2}, (0, 1)), [0.5, 0, 0.5, 0])
    # order (1, 0): control=[0,0], target=[1,0]: shot0 → 01, shot1 → 00  ⇒  [.5, .5, 0, 0]
    assert np.allclose(joint_populations({0: ctrl2, 1: tgt2}, (1, 0)), [0.5, 0.5, 0, 0])


def test_joint_populations_length_mismatch_is_loud():
    """A desynced pair of shot streams is an alignment failure, not a silent truncation."""
    with pytest.raises(ValueError, match="desynced"):
        joint_populations({0: np.zeros(10), 1: np.zeros(9)}, order=(0, 1))


# ── EF subspace (spec two-qubit/01 §4.1): host-pure ──

def test_levels_pop_reads_the_target_population():
    """_levels_pop classifies RAW IQ into levels with a 3-level ClassifierN and counts the target level
    (spec 01 §4.1): a point whose shots sit on the |2> centroid reads P(2)=1, on |1> reads P(2)=0 — the
    {|1>, |2>} discrimination the hardware res bit cannot do."""
    means = np.array([[10.0, 0.0], [-5.0, 8.66], [-5.0, -8.66]])   # 3 clusters ~120° apart
    rng = np.random.default_rng(0)
    clf = ClassifierN([means[k] + 0.2 * rng.standard_normal((40, 2)) for k in range(3)])
    npts, shots = 2, 16
    pt0 = means[1] + 0.2 * rng.standard_normal((shots, 2))         # all near |1>
    pt1 = means[2] + 0.2 * rng.standard_normal((shots, 2))         # all near |2>
    out = np.concatenate([pt0, pt1]).reshape(-1)                   # point-major, flat (2·npts·shots)
    assert np.array_equal(_levels_pop(out, npts, shots, clf, 2), [0.0, 1.0])
    assert np.array_equal(_levels_pop(out, npts, shots, clf, 1), [1.0, 0.0])


def test_ef_amplitude_guards_and_classifier_arg():
    """The EF X90 repetition guard (4·EF-X90 = 2π) and the classifier argument: the EF classes are
    `Amplitude`/`Phase` with `readout='classifier'`, so the classifier reaches the decode through
    the Measure — a bare `ClassifierN` fans out to every qubit, a dict names them (the old
    one-classifier-one-qubit ValueError is gone; a dict is still honoured verbatim)."""
    clf = ClassifierN([np.zeros((4, 2)), np.ones((4, 2)), 2 * np.ones((4, 2))])
    cfg = Config()
    with pytest.raises(AssertionError, match="multiple of 4"):
        EFAmplitude(cfg, 0, clf, n_gates=2)
    assert EFAmplitude(cfg, 0, clf, n_gates=4).measure().classifiers == {0: clf}
    assert EFAmplitude(cfg, [0, 1], clf).measure().classifiers == {0: clf, 1: clf}
    assert EFAmplitude(cfg, [0, 1], {0: clf, 1: clf}).measure().classifiers == {0: clf, 1: clf}
    assert EFAmplitude(cfg, 0, clf).measure().level == 2           # P(|2>), not the res bit


# ── JAZZ fit (spec two-qubit/01 §4.3): host-pure ──

def test_signed_fft_freq_resolves_the_sign():
    """The complex quadrature FFT (qcal est_freq_fft) returns a SIGNED frequency: I − jQ of a fringe
    running the other way lands on a negative bin, which a real cosine fit could never tell apart."""
    t = np.linspace(0, 4e-6, 40)
    zp = np.exp(1j * 2 * np.pi * 2e6 * t)
    zn = np.exp(-1j * 2 * np.pi * 2e6 * t)
    assert _signed_fft_freq(t, zp) == pytest.approx(2e6, rel=0.05)
    assert _signed_fft_freq(t, zn) == pytest.approx(-2e6, rel=0.05)


def test_jazz_recovers_zz_from_synthetic_fringes():
    """JAZZ's fit: ZZ11 = f(control=1) − f(control=0), each control state's fringe frequency measured
    from the complex quadrature I − jQ (`_fit_complex_freq`, exactly what `JAZZ.analyze` builds from
    its two closes). Two synthetic fringes at +1.0 MHz and +2.0 MHz → ZZ = 1.0 MHz; a control state
    running the other way is signed negative."""
    t = np.linspace(0, 4e-6, 40)
    rng = np.random.default_rng(0)

    def fringe(f):                                  # I = cos, Q = −sin ⇒ I − jQ = e^{+j2πft} (recovers +f)
        env = 0.45 * np.exp(-t / 3e-6)              # a decaying, slightly noisy fringe (as in co-sim)
        n = lambda: 0.01 * rng.standard_normal(len(t))
        return (0.5 + env * np.cos(2 * np.pi * f * t) + n(),
                0.5 - env * np.sin(2 * np.pi * f * t) + n())

    def signed(f):                                  # JAZZ.analyze's own quadrature combination
        I, Q = fringe(f)
        z = (np.asarray(I) - np.mean(I)) - 1j * (np.asarray(Q) - np.mean(Q))
        return _fit_complex_freq(t, z)

    f0, ok0 = signed(1.0e6)
    f1, ok1 = signed(2.0e6)
    assert ok0 and ok1
    assert f0 == pytest.approx(1.0e6, abs=5e4) and f1 == pytest.approx(2.0e6, abs=5e4)
    assert (f1 - f0) == pytest.approx(1.0e6, abs=1e5)         # the ZZ
    fneg, _ = signed(-1.2e6)                                  # a fringe running the other way
    assert fneg == pytest.approx(-1.2e6, abs=5e4)


# ── CZ resonance & conditionality (spec two-qubit/01 §4.4-4.5): host-pure ──

def _cz_config():
    """A minimal two-qubit Config with a coupler-drive CZ entry (spec 01 §2)."""
    cfg = Config()
    for q in (0, 1):
        cfg[f"qubit/{q}/freq"] = 50e6
        cfg[f"qubit/{q}/x90/amp"] = 0.5
    cfg["two_qubit/(0, 1)/core"] = 2
    cfg["two_qubit/(0, 1)/CZ/freq"] = 25e6
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "C0_1", "time": 200e-9, "kwargs": {"amp": 0.35, "phase": 0.0},
         "env": {"env_func": "cosine_square", "ramp_fraction": 0.1}},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.3}},   # a ZI correction
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": -0.2}},  # an IZ correction
    ]
    return cfg


def test_cz_pulse_and_config_accessors():
    """`_cz_pulse` builds the coupler-drive tone BASEBAND (freq_hz None — the sequence retunes the
    line to CZ/freq) at the config amp/env; the local-phase accessors read each QUBIT's virtual-Z
    entry (channel-matched — the control's is the ZI, the target's the IZ); `_cz_pulse_set` updates
    the physical drive in a fresh list (the proposal payload, since it lives in a list leaf). The
    coupler form: one drive, `core` present."""
    m = SocMap(SocParams.load(_SIM2Q1C))
    cfg = _cz_config()
    assert cz_coupler_form(cfg, (0, 1))                          # spec 04 §4.1: `core` ⇒ coupler form
    assert _cz_drive_indices(cfg["two_qubit/(0, 1)/CZ/pulse"]) == [0]
    tone = _cz_pulse(cfg, (0, 1), m, 20)
    assert tone.freq_hz is None and tone.amp == 0.35 and tone.phase == 0.0
    assert _cz_amp(cfg, (0, 1)) == 0.35
    assert _local_phase_code(cfg, (0, 1), 0) == pack16(units._phase_code(0.3))    # ZI (qubit 0, 'Q0')
    assert _local_phase_code(cfg, (0, 1), 1) == pack16(units._phase_code(-0.2))   # IZ (qubit 1, 'Q1')
    assert _local_phase_code(cfg, (0, 1), 5) == 0                                 # absent → 0
    pulses = _cz_pulse_set(cfg, (0, 1), "amp", 0.42)
    assert pulses[0]["kwargs"]["amp"] == 0.42
    assert cfg["two_qubit/(0, 1)/CZ/pulse"][0]["kwargs"]["amp"] == 0.35           # original untouched
    assert _cz_pulse_set(cfg, (0, 1), "time", 1.5e-7)[0]["time"] == 1.5e-7


def test_cz_resonance_dip_fit_recovers_fcz():
    """CZSweep('freq')'s analysis (spec 01 §4.4): the CONTROL P(1) dip = 1 − the off-resonant Rabi
    transfer Ω²/(Ω²+Δ²)·sin²(√(Ω²+Δ²)·N/2) (the closed form test_models pins on the model), fit by a
    parabola, locates f_CZ — replacing qcal's argmax-mean with a proper fit. The vertex lands within a
    sweep step of the planted resonance."""
    from riscq.cal import fits
    m = SocMap(SocParams.load(_SIM2Q1C))
    f_cz_code, N, A = 2048, 40, 10000.0
    Om = (math.pi / (N * A)) * A                                  # resonant rate rad/batch
    codes = np.arange(f_cz_code - 40, f_cz_code + 41, 8)
    freqs = np.array([units.code_to_freq(int(c), m.params) for c in codes])

    def p11(code):                                               # control P(1) = 1 − transfer to |02>
        dlt = (code - f_cz_code) * 16 * math.pi / (1 << 15)      # demod axis-ramp per batch (the detuning)
        g = math.hypot(Om, dlt)
        return 1.0 - (Om ** 2 / g ** 2) * math.sin(g * N / 2) ** 2

    P1 = np.array([p11(int(c)) for c in codes])
    fit = fits.fit_parabola(freqs, P1)
    assert fit.ok and fit.params["a"] > 0, "a P(1) dip is an UPWARD parabola (min at resonance)"
    assert abs(fit.value - units.code_to_freq(f_cz_code, m.params)) < abs(units.code_to_freq(8, m.params))


def test_cz_conditionality_argmax_and_amplitude_vertex():
    """The conditionality analyses (spec 01 §4.5): CZFrequency writes the argmax of R over the freq
    sweep (parabola-refined at the peak), CZAmplitude the parabola VERTEX of R over the amp window. Both
    exercised on synthetic R curves peaked at a known value (the model's R physics is
    test_models.test_twoqubit_cz_conditionality_R_peaks_at_the_cz)."""
    from riscq.cal import fits
    x = np.linspace(-1.0, 1.0, 21)
    R = 1.0 - 0.6 * (x - 0.2) ** 2                                # a peak at x = 0.2
    assert x[int(np.argmax(R))] == pytest.approx(0.2, abs=0.06)   # CZFrequency's argmax
    v = fits.fit_parabola(x, R)                                   # CZAmplitude's vertex (downward, a<0)
    assert v.ok and v.params["a"] < 0 and v.value == pytest.approx(0.2, abs=1e-6)


def test_cz_classes_construct_and_carry_defaults():
    """The CZ cal classes take (cfg, pair) with qcal-faithful defaults (the coupler-drive layout is the
    default, spec 01 §2 — no per-call params= boilerplate) and the (i, j) tuple key."""
    cfg = _cz_config()
    assert CZSweep(cfg, (0, 1), knob="dur").pair == (0, 1)
    assert CZFrequency(cfg, (0, 1)).ngates == 1
    assert CZAmplitude(cfg, (0, 1)).n_gates == (1, 3, 5, 7, 9)
    assert LocalPhases(cfg, (0, 1)).pair == (0, 1)
    with pytest.raises(AssertionError, match="freq/dur/amp"):
        CZSweep(cfg, (0, 1), knob="phase")


# ── Local phases (spec two-qubit/01 §4.6): host-pure ──

def test_local_phases_fringe_and_mean():
    """LocalPhases' analysis (spec 01 §4.6; branch combination per spec 04 §3/X1, qcal
    cz.py:2013-2051 parity): the ACTIVE Ramsey fringe peak is the first-harmonic phase of P vs the
    full-turn φ sweep (robust to the cosine sign); the correction removes the conditional π from the
    spectator-|1> branch BEFORE the shorter-arc midpoint. On ideal-CZ branches (|1> peak = |0> peak
    + π + δ for a small residual conditionality error δ) it recovers the |0>-branch local phase
    + δ/2 — NO π/2 term — stable for δ of EITHER sign and under noise (the raw midpoint sat at
    local ± π/2 with a noise-unstable sign: _mean_offset's wrap-boundary degeneracy)."""
    phi = np.linspace(-math.pi, math.pi, 24, endpoint=False)
    rng = np.random.default_rng(7)

    def fringe(peak):                                            # a noisy Ramsey P peaking at `peak`
        return 0.5 + 0.4 * np.cos(phi - peak) + 0.01 * rng.standard_normal(phi.size)

    def wrap(x):
        return (x + math.pi) % (2 * math.pi) - math.pi

    a, ca = _fringe_peak(phi, fringe(0.3))
    assert a == pytest.approx(0.3, abs=0.05) and ca > 0.15      # the contrast the run gates on
    assert _mean_offset(0.3, 0.42) == pytest.approx(0.36)       # the plain shorter-arc midpoint

    for peak0 in (0.3, -2.9):               # -2.9: the corrected pair straddles the ±π wrap
        for delta in (0.12, -0.12):         # the residual conditional-phase error, EITHER sign
            o0, _ = _fringe_peak(phi, fringe(peak0))
            o1, _ = _fringe_peak(phi, fringe(peak0 + math.pi + delta))
            corr = _branch_correction(o0, o1)
            assert wrap(corr - (peak0 + delta / 2)) == pytest.approx(0.0, abs=0.05)

    assert math.isnan(_branch_correction(0.3, math.nan))        # a failed branch stays a nan


def test_layout_accessors_on_the_two_qubit_drive_form():
    """(X0 gate) the layout-aware walk on the real X6Y3 config (spec 04 §4.2): nothing is found by
    index — drives by the find_pulse_index walk (string references shift every position), virtual-Z
    entries by their `channel` key — on a plain pair, a spectator-carrying pair, and the EF-X
    sandwich pair."""
    cfg = Config.from_qcal(_X6Y3)

    # (0, 1) — the plain two-qubit-drive layout: drives at 0/1, vz Q0/Q1/Q2 behind them.
    pl = cfg["two_qubit/(0, 1)/CZ/pulse"]
    assert not cz_coupler_form(cfg, (0, 1))                      # no `core` ⇒ two-qubit-drive form
    assert _cz_drive_indices(pl) == [0, 1]
    assert _cz_entry(cfg, (0, 1))["channel"] == "Q0.qdrv"        # control drive, phase 0
    tgt = _cz_entry(cfg, (0, 1), drive=1)
    assert tgt["channel"] == "Q1.qdrv" and tgt["kwargs"]["phase"] != 0.0   # the RELATIVE phase
    assert _cz_vz_entry(pl, 0) is pl[2] and _cz_vz_entry(pl, 1) is pl[3]   # ZI / IZ by channel
    assert _cz_vz_entry(pl, 2) is pl[4]                                     # the spectator (Q2)
    assert _local_phase_code(cfg, (0, 1), 0) == pack16(units._phase_code(pl[2]["kwargs"]["phase"]))
    assert _local_phase_code(cfg, (0, 1), 1) == pack16(units._phase_code(pl[3]["kwargs"]["phase"]))

    # (5, 6) — the EF-X shelving sandwich: string references at 0/3 shift drives to 1/2, vz to 4/5.
    pl = cfg["two_qubit/(5, 6)/CZ/pulse"]
    assert isinstance(pl[0], str) and isinstance(pl[3], str)
    assert _cz_drive_indices(pl) == [1, 2]
    assert _cz_entry(cfg, (5, 6))["channel"] == "Q5.qdrv"
    assert _cz_entry(cfg, (5, 6), drive=1)["channel"] == "Q6.qdrv"
    assert _cz_vz_entry(pl, 5) is pl[4] and _cz_vz_entry(pl, 6) is pl[5]
    assert _cz_vz_entry(pl, 7) is None and _local_phase_code(cfg, (5, 6), 7) == 0

    # updates land on BOTH drive lines (qcal calibrates them jointly) and never on strings or vz
    pulses = _cz_pulse_set(cfg, (5, 6), "amp", 0.42)
    assert [pulses[i]["kwargs"]["amp"] for i in (1, 2)] == [0.42, 0.42]
    assert pulses[0] == pl[0] and pulses[3] == pl[3]             # the sandwich survives verbatim
    local = _cz_local_set(cfg, (5, 6), 0.11, -0.22)
    assert local[4]["kwargs"]["phase"] == 0.11 and local[5]["kwargs"]["phase"] == -0.22
    assert local[0] == pl[0] and local[1] == pl[1]               # drives and strings untouched


def test_cz_pulse_envelope_kwargs_reach_the_build():
    """(X0 gate) the CZ tone's envelope kwargs = the entry's `kwargs` minus amp/phase (spec 04 §3):
    X6Y3 carries `ramp_fraction` beside amp/phase, and the old drop was invisible only because
    cosine_square's default equals the config value — plant a NON-default one and check it lands.
    The legacy `{env_func, ...}` dict form still supplies (and merges under) shape kwargs."""
    m = SocMap(SocParams.load(_SIM2Q1C))
    ch = gate_ch(m)
    n = 20 * ch.samples_per_line
    rate = ch.samples_per_line * m.params.dsp_freq_hz

    cfg = Config.from_qcal(_X6Y3)
    pl = copy.deepcopy(cfg["two_qubit/(0, 1)/CZ/pulse"])
    pl[0]["kwargs"]["ramp_fraction"] = 0.5
    cfg["two_qubit/(0, 1)/CZ/pulse"] = pl
    tone = _cz_pulse(cfg, (0, 1), m, 20)
    assert np.array_equal(tone.env, envelopes.build("cosine_square", n, rate, ramp_fraction=0.5))
    assert not np.array_equal(tone.env, envelopes.build("cosine_square", n, rate,
                                                        ramp_fraction=0.25))
    assert tone.amp == pl[0]["kwargs"]["amp"]                   # amp/phase stay slot params
    assert tone.phase == 0.0

    tone2 = _cz_pulse(_cz_config(), (0, 1), m, 20)              # dict-env form: ramp_fraction 0.1
    assert np.array_equal(tone2.env, envelopes.build("cosine_square", n, rate, ramp_fraction=0.1))


def test_cz_local_set_writes_both_frames():
    """LocalPhases writes the control ZI (pulse/1) and target IZ (pulse/2) virtual-Z phases into a fresh
    pulse list (the proposal payload) without touching the original config."""
    cfg = _cz_config()
    pulses = _cz_local_set(cfg, (0, 1), 0.11, -0.22)
    assert pulses[1]["kwargs"]["phase"] == 0.11 and pulses[2]["kwargs"]["phase"] == -0.22
    assert cfg["two_qubit/(0, 1)/CZ/pulse"][1]["kwargs"]["phase"] == 0.3   # original untouched


# ── Two-qubit-drive form (spec 04 §4.1-4.4 / X2): host-pure ──

def _drive_cfg():
    """A minimal two-qubit-drive Config (spec 04 §1): NO coupler core — both CZ lines on the pair's
    own gate channels at the shared in-band `CZ/freq`, the TARGET line carrying the relative phase.
    Distinct GE carriers so the f_GE → f_CZ retune is visible per core."""
    cfg = Config()
    for q, f in ((0, 50e6), (1, 75e6)):
        cfg[f"qubit/{q}/freq"] = f
        cfg[f"qubit/{q}/x90/amp"] = 0.5
        cfg[f"readout/{q}/freq"] = 10e6
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = 56e-8
        cfg[f"readout/{q}/demod/dur"] = 40e-8
    cfg["reset/relax"] = 8e-6
    cfg["two_qubit/(0, 1)/CZ/freq"] = 25e6
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "Q0", "time": 30e-8, "kwargs": {"amp": 0.35, "phase": 0.0}, "env": "square"},
        {"channel": "Q1", "time": 30e-8, "kwargs": {"amp": 0.35, "phase": 0.267}, "env": "square"},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.0}},
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": 0.0}},
    ]
    return cfg


def test_calc_cz_frequency_drive_form():
    """The drive-form seed (spec 04 §4.4): CZ/freq = (f_11 + f_state)/4 — half the two-photon
    midpoint — with the '20' mirror; the parametric arithmetic is untouched; a bad form is loud."""
    cfg = Config()
    cfg["qubit/0/freq"] = 5.0e9
    cfg["qubit/1/freq"] = 5.2e9
    cfg["qubit/0/EF/freq"] = 4.7e9
    cfg["qubit/1/EF/freq"] = 4.9e9

    calc_cz_frequency(cfg, [(0, 1)], state="02", form="drive")
    assert cfg["two_qubit/(0, 1)/CZ/freq"] == pytest.approx((10.2e9 + 10.1e9) / 4)   # 5.075 GHz
    calc_cz_frequency(cfg, [(0, 1)], state="20", form="drive")
    assert cfg["two_qubit/(0, 1)/CZ/freq"] == pytest.approx((10.2e9 + 9.7e9) / 4)    # 4.975 GHz
    calc_cz_frequency(cfg, [(0, 1)], state="02")                                     # parametric default
    assert cfg["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(100e6)
    with pytest.raises(AssertionError, match="parametric"):
        calc_cz_frequency(cfg, [(0, 1)], form="coupler")


def test_drive_seed_lands_near_x6y3_calibrated():
    """(X2 gate) the drive-form seed reproduces the six PLAIN X6Y3 pairs' calibrated `CZ/freq` to
    within ±100 MHz (spec 04 §1: −92…+61 MHz on state='02'); the EF-sandwich pairs (5,6)/(6,7) sit
    in the shelved manifold and are excluded (X4)."""
    cfg = Config.from_qcal(_X6Y3)
    plain = [(0, 1), (1, 2), (2, 3), (3, 4), (4, 5), (7, 0)]
    cal = {p: float(cfg[f"two_qubit/{pair_key(p)}/CZ/freq"]) for p in plain}
    calc_cz_frequency(cfg, plain, state="02", form="drive")
    for p in plain:
        seed = float(cfg[f"two_qubit/{pair_key(p)}/CZ/freq"])
        assert abs(seed - cal[p]) < 100e6, \
            f"pair {p}: drive seed {seed / 1e9:.4f} GHz vs calibrated {cal[p] / 1e9:.4f} GHz"


def _spect_cfg():
    """`_drive_cfg()` + a third qubit (the ring spectator) and its channel-matched vz entry in the
    pair's CZ pulse list (the X6Y3 layout: every pair carries 1-2 spectator corrections)."""
    cfg = _drive_cfg()
    cfg["qubit/2/freq"] = 60e6
    cfg["qubit/2/x90/amp"] = 0.5
    cfg["readout/2/freq"] = 12e6
    cfg["readout/2/amp"] = 0.5
    cfg["readout/2/dur"] = 56e-8
    cfg["readout/2/demod/dur"] = 40e-8
    pl = copy.deepcopy(cfg["two_qubit/(0, 1)/CZ/pulse"])
    pl.append({"channel": "Q2", "env": "virtualz", "kwargs": {"phase": 0.0}})
    cfg["two_qubit/(0, 1)/CZ/pulse"] = pl
    return cfg


def test_spectator_phase_guards():
    """SpectatorPhase's loud edges: a spectator inside the pair, a conditional outside it, a
    coupler-form pair (unsupported — spec 04 §4.5 scopes the drive form), and a write-back for a
    qubit with no vz entry."""
    cfg = _spect_cfg()
    with pytest.raises(AssertionError, match="LocalPhases"):
        SpectatorPhase(cfg, (0, 1), spectator=1)                 # spectator is in the pair
    with pytest.raises(AssertionError, match="conditional"):
        SpectatorPhase(cfg, (0, 1), spectator=2, conditional=2)  # conditional is not in the pair
    with pytest.raises(AssertionError, match="two-qubit-drive"):
        SpectatorPhase(_cz_config(), (0, 1), spectator=3)        # coupler form: not wired
    with pytest.raises(ValueError, match="virtualz"):
        _cz_spectator_set(cfg, (0, 1), 5, 0.1)                   # qubit 5 has no entry
    assert SpectatorPhase(cfg, (0, 1), spectator=2).conditional == 0   # defaults to the control


def test_phi_axis_is_a_real_full_turn():
    """(X3 fix) the class-level φ axis must actually SWEEP: ±π wrap to the SAME phase code, so an
    inclusive −π→+π span collapses to a zero step (a flat axis — LocalPhases/SpectatorPhase could
    never see a fringe). `_phi_axis` is endpoint-exclusive: a nonzero uniform step covering one
    full turn without the duplicate endpoint."""
    assert units._phase_code(math.pi) == units._phase_code(-math.pi)   # the wrap that bit
    for points in (15, 24):
        ax = _phi_axis(points)
        dc = int(np.diff(ax.codes)[0])
        assert dc > 0 and len(ax.values) == points
        assert ax.values[0] == pytest.approx(-math.pi, abs=1e-4)
        assert points * dc == pytest.approx(1 << 16, abs=points / 2)   # one full turn, exclusive
        assert ax.values[-1] < math.pi - 1e-3                          # no duplicate ±π sample
    assert _phi_axis(1).dx == 0


def test_cz_rel_phase_set_targets_the_second_drive():
    """`_cz_rel_phase_set` writes ONLY the target drive line's `kwargs/phase` (qcal's pulse/{idx+1}
    param) into a fresh list; a single-drive (coupler) list is a loud error."""
    cfg = _drive_cfg()
    pulses = _cz_rel_phase_set(cfg, (0, 1), 0.5)
    assert pulses[1]["kwargs"]["phase"] == 0.5
    assert pulses[0]["kwargs"]["phase"] == 0.0                   # control line untouched
    assert cfg["two_qubit/(0, 1)/CZ/pulse"][1]["kwargs"]["phase"] == 0.267   # original untouched
    with pytest.raises(ValueError, match="coupler form"):
        _cz_rel_phase_set(_cz_config(), (0, 1), 0.5)


# ── CZ 2D amp x freq seed landscape (spec 14 F4): the planted physics the responder tests use ──

_AF_N = 60                    # CZ tone length in batches (the planted pseudo-qubit product's grid)
_AF_AMP = 0.35                # the planted 2pi-round-trip amp — _drive_cfg()'s own CZ amp
_AF_DELTA_PER_HZ = 6.0 / 1.5e6   # planted detuning ramp: 6 rad of axis walk at 1.5 MHz off


def _cz_uv(amp, freq, f_star):
    """The planted (|11>, |02>) amplitudes after one CZ tone: `_AF_N` batches, each rotating the
    {|11>, |02>} pseudo-qubit by theta/N about an axis advancing by delta/N per batch — exactly
    `TwoQubitModel`'s drive-form activation (demod-then-rotate per batch, the axis ramping with the
    carrier detuning), reproduced to 1e-3 across amp AND detuning. theta = 2*pi at `_AF_AMP` on
    resonance, where the round trip closes and stamps the conditional pi."""
    theta = 2 * math.pi * amp / _AF_AMP
    delta = _AF_DELTA_PER_HZ * (freq - f_star)
    a, b = 1 + 0j, 0j
    c, s = math.cos(theta / (2 * _AF_N)), math.sin(theta / (2 * _AF_N))
    for t in range(_AF_N):
        e = np.exp(1j * delta * t / _AF_N)
        a, b = c * a - 1j * s * b / e, -1j * s * e * a + c * b
    return a, b


def _cz_branch_p0(u, v, quad):
    """The tomography branch's target P(read 0) for a CZ that left |11> with amplitude u and |02>
    with v. The control-|1> row holds (|0> + u|1>)/sqrt(2) after the Y90 prep + the CZ, and the
    close is Y90 (quad 0) or X90 (quad 1) — |1 - u|^2/4 and |1 - i*u|^2/4 — while the |02> leg
    parks |v|^2/2 of the target in |2>, which our ONE-BIT discriminator reads as a coin flip
    (+|v|^2/4; the |2> ambiguity of spec 01 §4.5, which qcal's 3-level classifier resolves and we
    do not). The control-|0> branch is the same with u = 1, v = 0 (the CZ cannot touch |01>)."""
    return abs(1 - (1j * u if quad else u)) ** 2 / 4 + abs(v) ** 2 / 4


# ── EF-sandwich CZ playback (spec 04 §1 / X4): host-pure ──

def _sandwich_cfg():
    """`_drive_cfg()` rebuilt as an EF-sandwich pair (the X6Y3 (5,6)/(6,7) layout on the 2-core
    sim-2q labels): qubit 1's EF keys + the SAME drive/vz list bracketed by the two identical
    `single_qubit/1/EF/X/pulse` string references."""
    cfg = _drive_cfg()
    cfg["qubit/1/EF/freq"] = 125e6
    cfg["qubit/1/EF/x/amp"] = 0.6
    pl = cfg["two_qubit/(0, 1)/CZ/pulse"]
    cfg["two_qubit/(0, 1)/CZ/pulse"] = (["single_qubit/1/EF/X/pulse"] + pl[:2]
                                        + ["single_qubit/1/EF/X/pulse"] + pl[2:])
    return cfg


def test_cz_sandwich_resolves_the_x6y3_pair():
    """(X4 gate) the sandwich layout on the REAL X6Y3 pairs: the two string references resolve to
    the SHELF qubit (6 for both (5, 6) and (6, 7)) whose own EF X the pair plays around its tones,
    and a plain pair resolves to None. What the shelf gate then compiles to — `Gate('qubit/6/EF/x')`
    before and after the tones, on both cores' padded tables — is
    tests/test_cals_twoqubit.py::test_cz_sandwich_plays_the_shelf_ef_x_on_both_sides."""
    cfg = Config.from_qcal(_X6Y3)
    assert cz_sandwich(cfg, (0, 1)) is None                      # plain pair: no references
    assert cz_sandwich(cfg, (5, 6)) == 6 and cz_sandwich(cfg, (6, 7)) == 6
    # the shelf's own EF calibration is what `cz_sandwich` validated the reference against
    assert f"qubit/6/EF/freq" in cfg and f"qubit/6/EF/x/amp" in cfg
    assert cz_sandwich(_sandwich_cfg(), (0, 1)) == 1             # the synthetic mirror


def test_cz_sandwich_rejects_unsupported_layouts():
    """`cz_sandwich` supports exactly the X6Y3 sandwich; every other string-reference layout is a
    loud error, never a silent mis-play (X4 scope): a lone reference, a non-EF-X path, a non-member
    qubit, references that do not bracket the drives, and missing EF calibration keys."""
    base = _sandwich_cfg()["two_qubit/(0, 1)/CZ/pulse"]

    def with_pulses(pl):
        c = _sandwich_cfg()
        c["two_qubit/(0, 1)/CZ/pulse"] = pl
        return c

    with pytest.raises(ValueError, match="2 identical"):
        cz_sandwich(with_pulses(base[:3] + base[4:]), (0, 1))    # the post reference dropped
    pl = copy.deepcopy(base)
    pl[0] = pl[3] = "single_qubit/1/GE/X/pulse"
    with pytest.raises(ValueError, match="not an EF X"):
        cz_sandwich(with_pulses(pl), (0, 1))
    pl = copy.deepcopy(base)
    pl[0] = pl[3] = "single_qubit/5/EF/X/pulse"
    with pytest.raises(ValueError, match="not a member"):
        cz_sandwich(with_pulses(pl), (0, 1))
    pl = [base[1], base[0], base[3], base[2]] + base[4:]         # refs BETWEEN the drives
    with pytest.raises(ValueError, match="bracket"):
        cz_sandwich(with_pulses(pl), (0, 1))
    c = _drive_cfg()                                             # refs but no EF calibration
    c["two_qubit/(0, 1)/CZ/pulse"] = copy.deepcopy(base)
    with pytest.raises(ValueError, match="missing qubit/1/EF"):
        cz_sandwich(c, (0, 1))


# ── EFPhase / EF-X amplitude (spec 04 §2 / X4): host-pure on the responder ──

_EF_MEANS = np.array([[10.0, 0.0], [-5.0, 8.66], [-5.0, -8.66]])   # |0>/|1>/|2> IQ centroids


def _ef_clf(seed=3):
    """A 3-level ClassifierN on well-separated synthetic clusters (the test_levels_pop geometry)."""
    rng = np.random.default_rng(seed)
    return ClassifierN([_EF_MEANS[k] + 0.1 * rng.standard_normal((30, 2)) for k in range(3)])


def _levels_iq(P, shots):
    """A RAW `out` array whose per-point P(|2>) is exactly `P`: round(p·shots) shots on the |2>
    centroid, the rest on |1> (the kernel's point-major 2·npts·shots cursor layout)."""
    iq = np.zeros((len(P), shots, 2))
    for i, p in enumerate(np.clip(P, 0.0, 1.0)):
        n2 = int(round(float(p) * shots))
        iq[i, :n2] = _EF_MEANS[2]
        iq[i, n2:] = _EF_MEANS[1]
    return iq.reshape(-1)


def _ef_cfg():
    """`_drive_cfg` plus qubit 0's EF calibration (what an EF class compiles against)."""
    cfg = _drive_cfg()
    cfg["qubit/0/EF/freq"] = 40e6
    cfg["qubit/0/EF/x90/amp"] = 0.4
    cfg["qubit/0/EF/x/amp"] = 0.5
    return cfg


def _circuit(prog) -> str:
    """Which of `Phase`'s circuits a program is, off the include the Experiment's label names."""
    return re.search(r"seq_Phase_(\w+?)_core", prog.c_source).group(1)


def test_ef_phase_recovers_planted_vz(responder):
    """(X4 gate) `EFPhase` end-to-end host-pure on planted lines (the GE Phase golden probe, on the
    3-level decode): each of the two crossing circuits' P(|2>) is linear in the swept phi with
    opposite slopes crossing at a planted phi* — the class's REAL two-circuit compile (one
    Experiment per circuit), the ClassifierN decode, the `_line_crossing` analysis and the
    `qubit/{q}/EF/x90/vz` = [phi*, phi*] write-back all run for real. The relative_phase pass
    re-centres the sweep on the STORED vz[0] (qcal's `phases + config[param]`)."""
    cfg = _ef_cfg()
    points, shots, span = 15, 100, 0.25    # RAW out = 2·npts·shots words: sized for the 16KB core RAM
    star = {"phi": 0.1}
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        prog = progs[0]
        phi = _phase_axis(prog, params.get(0, {}), (("x0", "dx0"),))
        slope = 1.0 if _circuit(prog) == "Y180_X90" else -1.0
        return {0: {"out": _levels_iq(0.5 + slope * (phi - star["phi"]), shots)}}

    cal = EFPhase(cfg, 0, _ef_clf(), points=points, span=span, shots=shots)
    res = cal.run(r.drv)
    assert res.ok and not cal.fallback[0]
    assert len(r.setups) == 2                                    # one compile per crossing circuit
    assert cal.recovered_vz[0] == pytest.approx(0.1, abs=0.01)
    assert res.proposal["qubit/0/EF/x90/vz"] == pytest.approx([0.1, 0.1], abs=0.01)

    cfg["qubit/0/EF/x90/vz"] = [0.3, 0.25]                       # stored pair (X6Y3: asymmetric)
    star["phi"] = 0.38                                           # inside 0.3 ± span
    res2 = EFPhase(cfg, 0, _ef_clf(), points=points, span=span, shots=shots,
                   relative_phase=True).run(r.drv)
    assert res2.ok
    assert res2.proposal["qubit/0/EF/x90/vz"] == pytest.approx([0.38, 0.38], abs=0.01)


def test_ef_amplitude_gate_x_knob(responder):
    """(X4 gate) EFAmplitude's `gate` knob — qcal `Amplitude(subspace='EF', gate='X')` (spec 04
    §2): the repetition guard flips to qcal's multiple-of-2 (pairs of EF π's return to |1>), the
    write path moves to `qubit/{q}/EF/x/amp`, and the n_gates=1 cosine fit recovers a planted π
    amplitude — P(|2>) generated from a planted EF Rabi rate over the ACTUAL swept codes, maximal
    at the π amp."""
    clf = _ef_clf(5)
    cfg = _ef_cfg()
    with pytest.raises(AssertionError, match="multiple of 2"):
        EFAmplitude(cfg, 0, clf, gate="X", n_gates=3)
    with pytest.raises(AssertionError, match="multiple of 4"):
        EFAmplitude(cfg, 0, clf, gate="X90", n_gates=2)
    with pytest.raises(AssertionError, match="gate must be"):
        EFAmplitude(cfg, 0, clf, gate="EFX")
    assert EFAmplitude(cfg, 0, clf, gate="X", n_gates=2).target_angle == pytest.approx(math.pi)

    m = SocMap(SocParams.load(_SIM2Q))
    efx = resolve(cfg, 0, "EF/x", m)
    a_star = 0.5
    rabi = math.pi / drive_sigma(m, efx, units._amp_code(a_star))   # π EXACTLY at a* = 0.5
    points, shots = 15, 100
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        codes = q16_axis(progs[0], params.get(0, {}), "x0", "dx0")    # the codes the kernel realizes
        P = [(1 - math.cos(rabi * drive_sigma(m, efx, int(c)))) / 2 for c in codes]
        return {0: {"out": _levels_iq(np.array(P), shots)}}

    cal = EFAmplitude(cfg, 0, clf, gate="X", n_gates=1, amp_span=(0.05, 0.95), points=points,
                      shots=shots)
    res = cal.run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/EF/x/amp"] == pytest.approx(a_star, abs=0.02)
    assert res.proposal["qubit/0/EF/rabi"] == pytest.approx(rabi, rel=0.05)
    assert "qubit/0/EF/x90/amp" not in res.proposal              # the X90 path is untouched


def test_ef_cals_capture_in_the_classifiers_zero_demod_frame(responder, monkeypatch):
    """(spec 14 finding 7) `ClassifierN`'s training captures are deliberately zero-frame, so every
    consumer that classifies host-side must capture in that same frame or its IQ clouds arrive
    rotated by the stored demod phase relative to the classifier's means — 0 on the co-sim configs,
    −109.9°…+39.0° on X6Y3. `Measure.levels` owns that invariant now (it pins phase = 0.0), and the
    res-bit cals are the deliberate opposite: there the stored phase IS the hardware discrimination
    knob, so they keep passing the config frame (`phase=None`)."""
    from riscq.cal import Amplitude, EFFrequency, Phase
    from riscq.cal import base as cal_base
    cfg = _ef_cfg()
    cfg["readout/0/demod/phase"] = -1.918                       # the config frame the res bit uses
    points, shots = 7, 16
    seen = []
    real = cal_base.readout_tables

    def recorder(cfg_, q, m_, phase=None, win=None):
        seen.append(phase)
        return real(cfg_, q, m_, phase=phase, win=win)

    monkeypatch.setattr(cal_base, "readout_tables", recorder)
    r = responder(_SIM2Q)

    fringe = 0.5 + 0.4 * np.cos(np.arange(points) * 0.7)
    r.answer(lambda progs, params: {q: {"out": _levels_iq(fringe, shots)} for q in progs})
    for cal in (EFAmplitude(cfg, 0, _ef_clf(), points=points, shots=shots),
                EFFrequency(cfg, 0, _ef_clf(), points=points, shots=shots),
                EFPhase(cfg, 0, _ef_clf(), points=points, shots=shots),
                EFPhase(cfg, 0, _ef_clf(), gate="X", points=points, shots=shots)):
        seen.clear()
        cal.run(r.drv)
        assert seen and set(seen) == {0.0}, f"{cal.label()} captured at {set(seen)}"

    # the res-bit consumers must NOT move
    from tests.responder import counts
    r.answer(lambda progs, params: {q: {"out": counts(fringe, shots)} for q in progs})
    for cal in (Amplitude(cfg, 0, points=points, shots=shots),
                Phase(cfg, 0, points=points, shots=shots)):
        seen.clear()
        cal.run(r.drv)
        assert seen and set(seen) == {None}, f"{cal.label()} captured at {set(seen)}"
