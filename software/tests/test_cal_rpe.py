"""spec 14 F5 — the host half of the RPE gate: pyRPE analysis recovers planted angles.

Every test here synthesizes noiseless (or shot-noisy) counts from a *planted* angle using the
model RPE assumes — P_cos = (1 + cos(d·phi))/2, P_sin = (1 + sin(d·phi))/2 — pushes them through
the real `riscq.cal.analysis.rpe` estimators, and asserts the planted angle comes back. That covers
the branch selection, the consistency check, and each estimator's inversion algebra. The last test
runs `LocalPhases` itself on one plant and pins the property `CZRPE` reads back: its write is
ZZ-free, so a converged tree lands both local phases on +π/2 whatever the conditional phase is.

The physics that produces those counts is the sequence compiler's job and is gated separately.

universal-cal V6 retired, from this module: the `_analytic_cz_driver` twin of
`CZRPE`-recovers-planted-generator-errors (the same plant and the same assertions are
tests/test_cals_twoqubit.py::test_cz_rpe_recovers_planted_generator_errors, on the responder), and
the four co-sim rung-geometry tests that read the gate DAC through the deleted kernel internals
`RPEFrequency._programs` / `_x90_train` / `RPEPhase._echo` / `twoqubit._cz_cond_progs` — the idle,
train, echo and retune-gap geometry they measured is now the sequence compiler's R1–R10, gated by
tests/test_sequence.py, the classes by tests/test_cals_twoqubit.py, and the bit-exact old-vs-new
signal parity is recorded at git commit 347f749.
"""

import math
from pathlib import Path

import numpy as np
import pytest

from riscq.cal import Config, LocalPhases
from riscq.cal.analysis.rpe import (CZ_STATE_PAIRS, CZ_TARGETS, X90_TARGET, Angles, RPEBranchError,
                                    cz_angles, damped_update, freq_error_hz, idle_angles,
                                    vz_correction, wrap, x90_angles)
from riscq.cal.batched import COUNTS
from riscq.cal.cals.twoqubit import QUAD
from tests.test_sequence import _phase_axis

DEPTHS = (1, 2, 4, 8, 16, 32, 64)


def _counts(phi, depths=DEPTHS, shots=2048, rng=None):
    """Synthesize the (cos, sin) count pairs an ideal RPE experiment would return for `phi`."""
    cos, sin = {}, {}
    for d in depths:
        p_cos = (1.0 + np.cos(d * phi)) / 2.0
        p_sin = (1.0 + np.sin(d * phi)) / 2.0
        if rng is None:
            n_cos, n_sin = round(p_cos * shots), round(p_sin * shots)
        else:
            n_cos, n_sin = rng.binomial(shots, p_cos), rng.binomial(shots, p_sin)
        cos[d] = (n_cos, shots - n_cos)
        sin[d] = (n_sin, shots - n_sin)
    return cos, sin


# ── the idle / frequency estimator (qcal's gate='I') ─────────────────────────────────────────

@pytest.mark.parametrize("planted", [0.0, 0.013, -0.021, 0.4, -0.4])
def test_idle_recovers_the_planted_phase_per_step(planted):
    """The 'Z' angle is the phase accumulated per idle step, and is its own error (target 0)."""
    cos, sin = _counts(planted)
    got = idle_angles(cos, sin, shots := 2048, DEPTHS)
    assert got.trusted["Z"] == pytest.approx(planted, abs=1e-4)
    assert got.trusted_error["Z"] == got.trusted["Z"]
    assert got.n_shots == shots
    # the deepest generation is trusted on noiseless counts, and its uncertainty is the tightest
    assert got.last_good == len(DEPTHS) - 1
    assert got.last_good_depth == DEPTHS[-1]
    assert got.uncertainty == pytest.approx(np.pi / (2 * DEPTHS[-1] * np.sqrt(shots)))


def test_idle_beats_the_single_depth_resolution():
    """The point of the ladder: precision is set by the deepest rung, not by the shot count.

    At depth 1 a 3e-4 phase moves P by 1.5e-4 — far under 2048-shot noise. Amplified 64x it is
    resolvable, and the estimate lands within shot noise of the planted value.
    """
    planted = 3e-4
    cos, sin = _counts(planted, rng=np.random.default_rng(7))
    got = idle_angles(cos, sin, 2048, DEPTHS)
    depth_1_resolution = np.pi / (2 * DEPTHS[0] * np.sqrt(2048))
    assert got.uncertainty == pytest.approx(depth_1_resolution / DEPTHS[-1])
    assert abs(got.trusted["Z"] - planted) < 3 * got.uncertainty


def test_idle_angle_converts_to_a_detuning_in_hz():
    """A qubit detuned by df accumulates 2*pi*df*t over an idle of t, so df = theta/(2*pi*t)."""
    t_idle, detuning = 100e-9, 1.5e5
    planted = 2 * np.pi * detuning * t_idle
    cos, sin = _counts(planted)
    got = idle_angles(cos, sin, 2048, DEPTHS)
    assert freq_error_hz(got.trusted["Z"], t_idle) == pytest.approx(detuning, rel=1e-3)


def test_idle_unwraps_past_the_depth_1_window():
    """Branch selection is the whole trick: each generation resolves the 2*pi/d ambiguity of the
    next using the previous estimate, so a phase that wraps many times at depth 64 still lands."""
    planted = 1.1  # 64 * 1.1 = 70.4 rad — more than 11 full turns at the deepest rung
    cos, sin = _counts(planted)
    assert idle_angles(cos, sin, 2048, DEPTHS).trusted["Z"] == pytest.approx(planted, abs=1e-4)


def test_flat_signal_is_an_error_not_a_converged_zero():
    """A dead qubit must raise rather than read out as a perfectly calibrated one.

    P = 1/2 everywhere makes arctan2(0, 0) return 0 at every depth, which the consistency check
    happily accepts as a converged angle of exactly zero. Only the contrast floor catches it.
    """
    dead = {d: (1024, 1024) for d in DEPTHS}
    with pytest.raises(RPEBranchError, match="contrast"):
        idle_angles(dead, dead, 2048, DEPTHS)


def test_decohered_deep_rungs_are_not_trusted():
    """When the signal dies partway up the ladder the trusted generation must stop there.

    This is the case the consistency check does *not* cover: dead rungs return an arbitrary angle
    with a very narrow consistency window, and successive dead rungs readily agree with each
    other — so the ladder converges confidently on a wrong answer. The contrast floor is what
    bounds the ladder at the coherence time.
    """
    cos, sin = _counts(0.05)
    for d in (16, 32, 64):  # decohered: both quadratures sit at P = 1/2
        cos[d] = sin[d] = (1030, 2048 - 1030)
    got = idle_angles(cos, sin, 2048, DEPTHS)
    assert got.last_good_depth == 8
    assert got.trusted["Z"] == pytest.approx(0.05, abs=1e-3)
    assert got.contrast[-1] < 0.05 < got.contrast[0]


def test_mismatched_depth_ladders_are_rejected():
    cos, sin = _counts(0.1)
    with pytest.raises(ValueError, match="same depths"):
        idle_angles(cos, {d: v for d, v in sin.items() if d != 64}, 2048)


# ── the X90 estimator: amplitude (X angle) + drive phase (Z angle) ────────────────────────────

def _x90_counts(x_angle, z_angle, depths=DEPTHS, shots=2048):
    """Invert the linearized estimator to get the direct/interleaved angles that produce (X, Z).

    The direct experiment sees the rotation *magnitude*; the interleaved echo sees the axis tilt.
    """
    magnitude = np.hypot(x_angle, z_angle)
    tilt = np.arctan2(z_angle, x_angle)
    epsilon = magnitude / X90_TARGET - 1.0
    interleaved = 2.0 * np.arcsin(2.0 * tilt * np.cos(np.pi * epsilon / 2.0))
    return _counts(magnitude, depths, shots), _counts(interleaved, depths, shots)


@pytest.mark.parametrize("x_err,z_err", [(0.0, 0.0), (0.02, 0.0), (0.0, 0.03), (-0.05, 0.04)])
def test_x90_recovers_the_planted_rotation_and_axis(x_err, z_err):
    """X is the rotation angle (amplitude error), Z the axis tilt out of x-hat (phase error)."""
    x_angle, z_angle = X90_TARGET + x_err, z_err
    (dcos, dsin), (icos, isin) = _x90_counts(x_angle, z_angle)
    got = x90_angles(dcos, dsin, icos, isin, 2048, DEPTHS)
    assert got.trusted["X"] == pytest.approx(x_angle, abs=1e-3)
    assert got.trusted["Z"] == pytest.approx(z_angle, abs=1e-3)
    assert got.trusted_error["X"] == pytest.approx(x_err, abs=1e-3)
    assert got.trusted_error["Z"] == pytest.approx(z_err, abs=1e-3)


def test_x90_truncates_to_the_shallower_ladder():
    """The interleaved block spends four X90s per repetition, so its ladder is the shorter one;
    the recombination has to run on the common prefix rather than off the end of an array."""
    shallow = DEPTHS[:4]
    (dcos, dsin), _ = _x90_counts(X90_TARGET + 0.02, 0.01)
    _, (icos, isin) = _x90_counts(X90_TARGET + 0.02, 0.01, depths=shallow)
    got = x90_angles(dcos, dsin, icos, isin, 2048, DEPTHS)
    assert got.depths == shallow
    assert len(got.estimates["X"]) == len(shallow)
    assert got.trusted["X"] == pytest.approx(X90_TARGET + 0.02, abs=1e-3)


def _rot(x, z):
    """exp(-i(x·X + z·Z)/2) as a 2x2 — the gate RPE's (X, Z) angles describe."""
    omega = np.hypot(x, z)
    axis = np.array([[z, x], [x, -z]], dtype=complex) / (omega if omega else 1.0)
    return np.cos(omega / 2) * np.eye(2) - 1j * np.sin(omega / 2) * axis


@pytest.mark.parametrize("tilt", [0.0, 0.05, -0.05, 0.3])
def test_vz_correction_straightens_the_rotation_axis(tilt):
    """Adding the correction to BOTH virtual-Z slots must leave a pure x-rotation.

    The frame convention is that a virtual-Z of `a` inserts Rz(-a), so the corrected gate is
    Rz(-beta)·G·Rz(-beta). Note beta is NOT tilt/2: a z-error accrued during a quarter turn
    splits as (2/pi)·tilt per side.
    """
    beta = vz_correction(X90_TARGET, tilt)
    corrected = _rot(0.0, -beta) @ _rot(X90_TARGET, tilt) @ _rot(0.0, -beta)
    assert corrected[0, 0].imag == pytest.approx(0.0, abs=1e-12)   # no Z left in the generator
    assert corrected[0, 1].real == pytest.approx(0.0, abs=1e-12)   # and none in Y either
    assert beta == pytest.approx((2 / np.pi) * tilt, rel=0.05)


# ── the CZ estimator: ZZ / IZ / ZI from the three state pairs ─────────────────────────────────

def _cz_counts(zz, iz, zi, depths=DEPTHS, shots=2048):
    """Per-CZ accumulated angle for each state pair, in the CZ = expm(-i/2(...)) convention."""
    per_pair = {(0, 1): iz + zz, (2, 3): iz - zz, (3, 1): zi - zz}
    return {pair: _counts(per_pair[pair], depths, shots) for pair in CZ_STATE_PAIRS}


def test_cz_recovers_the_ideal_targets():
    """Sanity anchor: an ideal CZ = diag(1,1,1,-1) accumulates 0, pi, pi on the three state pairs
    and must invert back to exactly the (-pi/2, pi/2, pi/2) targets, i.e. zero error."""
    ideal = _cz_counts(zz=CZ_TARGETS["ZZ"], iz=CZ_TARGETS["IZ"], zi=CZ_TARGETS["ZI"])
    got = cz_angles(ideal, 2048, DEPTHS)
    for name, target in CZ_TARGETS.items():
        assert got.trusted[name] == pytest.approx(target, abs=1e-3)
        assert got.trusted_error[name] == pytest.approx(0.0, abs=1e-3)


@pytest.mark.parametrize("dzz,diz,dzi", [(0.05, 0.0, 0.0), (0.0, 0.03, 0.0), (0.0, 0.0, -0.04),
                                         (-0.06, 0.02, 0.05)])
def test_cz_recovers_planted_generator_errors(dzz, diz, dzi):
    """Each of ZZ / IZ / ZI must move independently — the inversion mixes all three state pairs,
    so a sign or factor slip shows up as crosstalk between the recovered errors."""
    planted = {"zz": CZ_TARGETS["ZZ"] + dzz, "iz": CZ_TARGETS["IZ"] + diz,
               "zi": CZ_TARGETS["ZI"] + dzi}
    got = cz_angles(_cz_counts(**planted), 2048, DEPTHS)
    assert got.trusted_error["ZZ"] == pytest.approx(dzz, abs=2e-3)
    assert got.trusted_error["IZ"] == pytest.approx(diz, abs=2e-3)
    assert got.trusted_error["ZI"] == pytest.approx(dzi, abs=2e-3)


def test_cz_missing_state_pair_is_rejected():
    counts = _cz_counts(zz=-np.pi / 2, iz=np.pi / 2, zi=np.pi / 2)
    del counts[(3, 1)]
    with pytest.raises(ValueError, match=r"state pair\(s\) \[\(3, 1\)\]"):
        cz_angles(counts, 2048, DEPTHS)


# ── the feedback rule (spec 14 §4: damped clip updates, no optimizer stack) ───────────────────

def test_damped_update_is_damped_and_clipped():
    assert damped_update(5.0, -1.0, gain=0.5) == pytest.approx(4.5)
    assert damped_update(5.0, -10.0, gain=1.0, max_step=0.5) == pytest.approx(4.5)
    assert damped_update(5.0, +10.0, gain=1.0, max_step=0.5) == pytest.approx(5.5)


def test_damped_update_multiplicative_for_amplitude():
    """Amplitude is linear in rotation angle, so its correction is a fractional one."""
    assert damped_update(0.1, -0.04, gain=1.0, multiplicative=True) == pytest.approx(0.096)


def test_wrap_is_the_references_rectify_angle():
    assert wrap(0.0) == pytest.approx(0.0)
    assert wrap(3 * np.pi) == pytest.approx(-np.pi)
    assert wrap(np.array([2 * np.pi + 0.1, -0.1])) == pytest.approx([0.1, -0.1])


def test_angles_dataclass_reports_the_trusted_generation():
    a = Angles(depths=(1, 2, 4), estimates={"Z": np.array([0.5, 0.4, 0.41])},
               errors={"Z": np.array([0.5, 0.4, 0.41])}, last_good=1, n_shots=100)
    assert a.trusted == {"Z": pytest.approx(0.4)}
    assert a.last_good_depth == 2
    assert a.uncertainty == pytest.approx(np.pi / (2 * 2 * 10.0))




# ── LocalPhases → CZRPE, one plant, host-pure on the responder ────────────────────────────────

_SIM2Q = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"

#: QUAD's codes 0..3 as the close phase they seat (+Y90, +X90, −Y90, −X90).
PHI_CLOSE = {0: math.pi / 2, 1: 0.0, 2: -math.pi / 2, 3: math.pi}


def _drive_cfg():
    """A minimal two-qubit-drive Config (spec 04 §1), copied from tests/test_twoqubit.py: NO
    coupler core — both CZ lines on the pair's own gate channels at the shared in-band `CZ/freq`,
    the TARGET line carrying the relative phase, and both local virtual-Z entries at zero."""
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


def _quad(word) -> int:
    return list(QUAD.values).index(int(word))


def _reader(progs):
    """The cores that read out (COUNTS mode) and a zero `out` for every core."""
    reads = [c for c, p in progs.items() if p.bindings.get("mode") == COUNTS]
    return reads, {c: {"out": np.zeros(int(p.bindings["npts"]), int)} for c, p in progs.items()}


@pytest.mark.parametrize("theta_zz", [-np.pi / 2, -1.2])
@pytest.mark.parametrize("raw", [(0.4, -0.9), (-2.8, 2.6)])
def test_local_phases_write_is_zz_free(responder, theta_zz, raw):
    """spec 14 §3 finding 9, first half — `LocalPhases`' write is ZZ-FREE.

    The active qubit accrues ψ_s = θ_raw ± θ_ZZ over the CZ (the spectator's Z flips the ZZ term);
    `_branch_correction` writes ψ₀ + δ/2 = θ_raw − π/2 (its δ = −2·θ_ZZ − π), i.e. the effective
    local phase lands on exactly +π/2 for both qubits however badly the conditional phase is
    calibrated. θ_ZZ is parameterized both at the ideal −π/2 and off it; the raw phases once well
    inside (−π, π] and once straddling its wrap. That is what makes the write a defect detector
    rather than a quality metric — and it is the property `CZRPE` then reads back as IZ = ZI = π/2.

    The composition leg (running `CZRPE` on the tree this write lands and checking it has nothing
    left to correct) is covered by its own planted test,
    tests/test_cals_twoqubit.py::test_cz_rpe_recovers_planted_generator_errors, which plants the
    generator errors directly; finding 9 is that the two agree only as the Ramsey qubit's residual
    detuning → 0, so composing them is not an identity to assert on a co-sim twin.
    """
    theta_zi_raw, theta_iz_raw = raw
    shots = 4096
    cfg = _drive_cfg()
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        reads = [c for c, p in progs.items() if p.bindings.get("mode") == COUNTS]
        out = {c: {"out": np.zeros(int(p.bindings["npts"]), int)} for c, p in progs.items()}
        (active,) = reads                           # only the ACTIVE qubit reads (one role per pass)
        sp = params[active]["r0"]                   # the spectator's prep
        phi = _phase_axis(progs[active], params[active], (("x0", "dx0"),))
        peak = {0: theta_zi_raw, 1: theta_iz_raw}[active] + (theta_zz if sp == 0 else -theta_zz)
        out[active] = {"out": np.rint((0.5 + 0.4 * np.cos(phi - peak)) * shots)}
        return out

    res = LocalPhases(cfg, (0, 1), points=24, shots=shots).run(r.drv)
    assert res.ok and len(r.setups) == 2                         # one experiment per role
    pl = res.proposal["two_qubit/(0, 1)/CZ/pulse"]
    for q, i, theta_raw in ((0, 2, theta_zi_raw), (1, 3, theta_iz_raw)):
        assert wrap(pl[i]["kwargs"]["phase"] - (theta_raw - np.pi / 2)) == pytest.approx(0.0, abs=2e-3), \
            "the branch combination is not ZZ-free"


