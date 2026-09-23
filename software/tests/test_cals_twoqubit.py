"""L0 gates of specs/universal-cal V5: the two-qubit and RPE classes on the universal base, host-pure
on the responder against planted physics (the answer functions model the intended physics from
first principles — tests/responder.py's rule), plus old-vs-new twins for the single-qubit RPE
ladders (the same counts → the same proposal). The signal parity is tests/test_parity_2q.py."""

import math
from pathlib import Path

import numpy as np
import pytest

from riscq.cal.batched import COUNTS
from riscq.cal.cals import (JAZZ, CZRPE, CZAmpFreqSweep, CZAmplitude, CZFrequency, CZSweep,
                            LocalPhases, RelativePhase, RPEAmplitude, RPEFrequency, RPEPhase,
                            SpectatorPhase)
from riscq.cal.cals.twoqubit import QUAD
from riscq.cal.analysis.rpe import vz_correction, wrap
from riscq.cal.cz import _cz_vz_entry
from riscq.map import SocMap, SocParams
from riscq.pulses import units
from tests.responder import counts, q16_axis
from tests.test_sequence import _axis, _phase_axis, _unseat
from tests.cal_fixtures import (F_GE, _AF_AMP, _cfg, _cz_branch_p0, _cz_config, _cz_uv, _drive_cfg,
                                _sandwich_cfg, _spect_cfg)

_SIM2Q = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"
_SIM2Q1C = Path(__file__).resolve().parents[1] / "configs" / "sim-2q1c.json"

PHI_CLOSE = {0: math.pi / 2, 1: 0.0, 2: -math.pi / 2, 3: math.pi}   # QUAD's codes 0..3


def _rad(word) -> float:
    return _unseat(word) * math.pi / (1 << 15)


def _quad(word) -> int:
    return list(QUAD.values).index(int(word))


def _reader(progs):
    """The cores that read out (COUNTS mode) and a zero `out` for every core."""
    reads = [c for c, p in progs.items() if p.bindings.get("mode") == COUNTS]
    return reads, {c: {"out": np.zeros(int(p.bindings["npts"]), int)} for c, p in progs.items()}


# ── RelativePhase / SpectatorPhase / LocalPhases: the Ramsey-peak family ──

def test_relative_phase_recovers_the_peak(responder):
    """The target line's phase is a runtime Param on the tone; the four tomography branches per
    phase come from a conditional phase θ(φ) peaking (θ = π) at a planted φ* (R = |sin(θ/2)|)."""
    cfg = _drive_cfg()
    phi_star, shots = 0.7, 400
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        p = params[1]
        phi, prep, quad = _rad(p["r0"]), p["r1"], _quad(p["r2"])
        theta = math.pi * math.cos((phi - phi_star) / 2) if prep else 0.0
        p0 = (1 + math.cos(theta - PHI_CLOSE[quad] + math.pi / 2)) / 2      # target P(0)
        _, out = _reader(progs)
        out[1] = {"out": counts([1 - p0], shots)}
        return out

    cal = RelativePhase(cfg, (0, 1), points=21, shots=shots)
    res = cal.run(r.drv)
    R = res.data[(0, 1)]["R"]
    assert len(r.setups) == 1                                       # one image, 21×2×4 reruns
    assert res.ok and R.max() > 0.9
    written = res.proposal["two_qubit/(0, 1)/CZ/pulse"][1]["kwargs"]["phase"]
    assert written == pytest.approx(phi_star, abs=0.2)
    assert cfg["two_qubit/(0, 1)/CZ/pulse"][1]["kwargs"]["phase"] == 0.267   # original untouched
    with pytest.raises(AssertionError, match="two-qubit-drive"):
        RelativePhase(_cz_config(), (0, 1))


def test_spectator_phase_recovers_planted_phase(responder):
    """The spectator's Ramsey fringe P(1) = (1 + cos(ψ_c + φ))/2 per conditional branch (a small
    spectator conditionality δ, NO conditional π); the wrap-aware branch mean lands in the
    SPECTATOR's entry of the pair's list. A real 3-core compile: only the spectator reads."""
    cfg = _spect_cfg()
    psi, delta, points, shots = 0.9, 0.1, 24, 200
    r = responder(_SIM2Q1C)

    @r.answer
    def _(progs, params):
        reads, out = _reader(progs)
        assert reads == [2], "only the spectator reads out"
        sp = params[0]["r0"]
        phi = _phase_axis(progs[2], params[2], (("x0", "dx0"),))
        out[2] = {"out": np.rint((1 + np.cos(psi + sp * delta + phi)) / 2 * shots)}
        return out

    cal = SpectatorPhase(cfg, (0, 1), spectator=2, points=points, shots=shots)
    res = cal.run(r.drv)
    assert res.ok
    assert sorted(r.setups[0]) == [0, 1, 2]
    pulses = res.proposal["two_qubit/(0, 1)/CZ/pulse"]
    assert _cz_vz_entry(pulses, 2)["kwargs"]["phase"] == pytest.approx(-(psi + delta / 2), abs=0.03)
    assert _cz_vz_entry(pulses, 0)["kwargs"]["phase"] == 0.0
    assert _cz_vz_entry(cfg["two_qubit/(0, 1)/CZ/pulse"], 2)["kwargs"]["phase"] == 0.0


@pytest.mark.parametrize("theta_zz", [-math.pi / 2, -1.2])
def test_local_phases_write_is_zz_free(responder, theta_zz):
    """The active qubit accrues θ_raw ± θ_ZZ over the CZ (the spectator's Z flips the ZZ term);
    the written correction is θ_raw − π/2 whatever θ_ZZ is (the X1 branch combination), for the
    control (→ ZI, spectator = target) and the target (→ IZ). The active role reads alone."""
    cfg = _drive_cfg()
    raw = {0: 0.4, 1: -0.9}
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        reads, out = _reader(progs)
        (active,) = reads
        sp = params[active]["r0"]
        phi = _phase_axis(progs[active], params[active], (("x0", "dx0"),))
        peak = raw[active] + (theta_zz if sp == 0 else -theta_zz)
        out[active] = {"out": np.rint((0.5 + 0.4 * np.cos(phi - peak)) * 200)}
        return out

    res = LocalPhases(cfg, (0, 1), points=24, shots=200).run(r.drv)
    assert res.ok and len(r.setups) == 2                            # one experiment per role
    pl = res.proposal["two_qubit/(0, 1)/CZ/pulse"]
    for q, i in ((0, 2), (1, 3)):
        assert wrap(pl[i]["kwargs"]["phase"] - (raw[q] - math.pi / 2)) == pytest.approx(0.0, abs=0.05)


# ── the conditionality-R family ──

def test_cz_amp_freq_sweep_seeds_the_argmax(responder):
    """The on-core freq sweep × the host `amp` Param on BOTH lines (lock-step): the four
    tomography branches per cell from the {|11>, |02>} pseudo-qubit (tests/test_twoqubit's
    `_cz_uv`), the 2D argmax → CZ/freq + the drive amp; a dead landscape writes nothing."""
    cfg = _drive_cfg()
    points, shots, span = 9, 400, 3e6
    r = responder(_SIM2Q)
    m = SocMap(SocParams.load(_SIM2Q))
    f_star = {}

    @r.answer
    def _(progs, params):
        p = params[1]
        amp, prep, quad = (int(p["r0"]) >> 16) / units.AMP_SCALE, p["r1"], _quad(p["r2"])
        assert params[0]["r0"] == p["r0"], "both lines carry the same amp word"
        fax = np.array([units.code_to_freq(int(c), m.params)
                        for c in q16_axis(progs[1], params[1], "x0", "dx0")])
        f_star.setdefault("f", float(fax[points // 2]))            # plant on a grid point
        p0 = np.array([_cz_branch_p0(*(_cz_uv(amp, f, f_star["f"]) if prep else (1.0, 0.0)), quad)
                       for f in fax])
        _, out = _reader(progs)
        out[1] = {"out": np.rint((1 - p0) * shots).astype(int)}
        return out

    cal = CZAmpFreqSweep(cfg, (0, 1), span=span, points=points, shots=shots)
    res = cal.run(r.drv)
    d = res.data[(0, 1)]
    assert len(r.setups) == 1 and d["R"].shape == (7, points)
    ka, kf = np.unravel_index(int(np.argmax(d["R"])), d["R"].shape)
    assert (ka, kf) == (3, points // 2) and d["R"][ka, kf] > 0.95 and res.ok
    assert res.proposal["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(f_star["f"])
    written = res.proposal["two_qubit/(0, 1)/CZ/pulse"]
    assert [p["kwargs"]["amp"] for p in written[:2]] == pytest.approx([_AF_AMP, _AF_AMP])
    assert written[1]["kwargs"]["phase"] == 0.267
    dead = CZAmpFreqSweep(cfg, (0, 1), amps=[0.02, 0.03], span=span, points=points,
                          shots=shots).run(r.drv)
    assert not dead.ok and dead.proposal == {}


def test_cz_frequency_and_amplitude_ladder(responder):
    """CZFrequency's R over the carrier peaks at the planted resonance; CZAmplitude's odd-n ladder
    (n·θ = 2π·n at the planted amp) converges its parabola vertex onto it and writes both lines."""
    cfg = _drive_cfg()
    amp_star, shots, m = 0.38, 400, SocMap(SocParams.load(_SIM2Q))
    f_cz = cfg["two_qubit/(0, 1)/CZ/freq"]
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        p = params[1]
        prep, quad = p["r0"], _quad(p["r1"])
        codes = q16_axis(progs[1], params[1], "x0", "dx0")
        n = state["n"]
        if state["knob"] == "freq":
            xs = [(cfg["two_qubit/(0, 1)/CZ/pulse"][0]["kwargs"]["amp"],
                   units.code_to_freq(int(c), m.params)) for c in codes]
        else:
            xs = [(int(c) / units.AMP_SCALE, f_cz) for c in codes]
        p0 = []
        for amp, f in xs:
            theta = 2 * math.pi * amp / amp_star * n            # n resonant tones compose
            u = complex(math.cos(theta / 2), 0) if f == f_cz else _cz_uv(amp, f, f_cz)[0]
            v = -1j * math.sin(theta / 2) if f == f_cz else _cz_uv(amp, f, f_cz)[1]
            p0.append(_cz_branch_p0(u, v, quad) if prep else _cz_branch_p0(1.0, 0.0, quad))
        _, out = _reader(progs)
        out[1] = {"out": np.rint((1 - np.array(p0)) * shots).astype(int)}
        return out

    state = {"knob": "amp", "n": 1}
    cfg["two_qubit/(0, 1)/CZ/pulse"][0]["kwargs"]["amp"] = 0.34     # start off the plant
    cfg["two_qubit/(0, 1)/CZ/pulse"][1]["kwargs"]["amp"] = 0.34
    cal = CZAmplitude(cfg, (0, 1), n_gates=(1, 3, 5), points=11, shots=shots)
    orig_run = cal._series

    def series(drv, **kw):
        state["n"] = cal._n
        return orig_run(drv, **kw)

    cal._series = series
    res = cal.run(r.drv)
    assert res.ok and len(r.setups) == 3
    written = res.proposal["two_qubit/(0, 1)/CZ/pulse"]
    assert [p["kwargs"]["amp"] for p in written[:2]] == pytest.approx([amp_star] * 2, abs=0.01)

    state.update(knob="freq", n=1)
    cfg["two_qubit/(0, 1)/CZ/pulse"][0]["kwargs"]["amp"] = amp_star
    cfg["two_qubit/(0, 1)/CZ/pulse"][1]["kwargs"]["amp"] = amp_star
    res = CZFrequency(cfg, (0, 1), span=2e6, points=15, shots=shots).run(r.drv)
    assert res.ok
    assert res.proposal["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(f_cz, abs=2e6 / 14)
    assert res.data[(0, 1)]["R"].max() > 0.95


def test_cz_sandwich_plays_the_shelf_ef_x_on_both_sides(responder):
    """An EF-sandwich pair (X4): the shelf core's program retunes to its EF carrier and plays its
    EF X before and after the tones; the partner idles through both (equal-length shots)."""
    cfg = _sandwich_cfg()
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        _, out = _reader(progs)
        return out

    from riscq.cal.sequence import compile_sequence, emit_header
    cal = CZFrequency(cfg, (0, 1), points=3, shots=4)
    cal.run(r.drv)
    src = {c: p.c_source for c, p in r.setups[0].items()}
    # the partner's gate table is padded to the shelf's three slots (x90, ef, cz)
    assert all("volatile struct rq_slot tbl_gate[3]" in src[c] for c in (0, 1))
    m = SocMap(SocParams.load(_SIM2Q))
    ax = cal.axes((0, 1), m)
    comp = compile_sequence(cfg, 0, cal._full((0, 1), ax), m, ax, cal.params((0, 1), m))
    ef = f"{units.freq_to_code(125e6, m.params):#X}".replace("0X", "0x")
    assert emit_header(comp, 1, None).count(f"set_freq(RF_CH0, {ef});") == 2
    assert ef not in emit_header(comp, 0, None)
    keys = [(r_.core, r_.key) for r_ in comp.rows]
    assert keys[3] == (1, "qubit/1/EF/x") and keys[-2] == (1, "qubit/1/EF/x")


# ── CZSweep (coupler form) and JAZZ ──

def test_cz_sweep_dip_and_return(responder):
    """Coupler form on the 3-core map: the freq knob's control-P(1) dip locates f_CZ (the coupler
    carries the swept carrier, the qubits read); the dur / amp knobs' return cosine writes the
    pulse `time` / `amp` on the coupler entry."""
    cfg = _cz_config()
    for q in (0, 1):
        cfg[f"readout/{q}/freq"], cfg[f"readout/{q}/amp"] = 10e6, 0.5
        cfg[f"readout/{q}/dur"], cfg[f"readout/{q}/demod/dur"] = 56e-8, 40e-8
    cfg["reset/relax"] = 8e-6
    m = SocMap(SocParams.load(_SIM2Q1C))
    f_cz, N, A = 25e6, 40, 0.35
    czd = round(200e-9 * m.params.dsp_freq_hz)                      # the config tone, batches
    code_star = units._freq_code(f_cz, m.params)
    Om = math.pi / N
    r = responder(_SIM2Q1C)

    @r.answer
    def _(progs, params):
        reads, out = _reader(progs)
        assert sorted(reads) == [0, 1] and progs[2].bindings["mode"] != COUNTS
        codes = q16_axis(progs[2], params[2], "x0", "dx0")
        if state["knob"] == "freq":
            dlt = (codes - code_star) * 16 * math.pi / (1 << 15)
            g = np.hypot(Om, dlt)
            P1 = 1 - (Om ** 2 / g ** 2) * np.sin(g * N / 2) ** 2
        elif state["knob"] == "amp":
            P1 = np.cos(math.pi * (codes / units.AMP_SCALE) / A) ** 2      # 2π return at A
        else:
            dur = _axis(progs[2], params[2], (("x0", "dx0"),))
            P1 = np.cos(math.pi * dur / czd) ** 2                        # a round trip per czd
        out[0] = {"out": counts(P1, 200)}
        return out

    state = {"knob": "freq"}
    res = CZSweep(cfg, (0, 1), "freq", span=3e6, points=21, shots=200).run(r.drv)
    assert res.ok and res.proposal["two_qubit/(0, 1)/CZ/freq"] == pytest.approx(f_cz, abs=1.5e5)
    state["knob"] = "amp"
    res = CZSweep(cfg, (0, 1), "amp", points=21, shots=200).run(r.drv)
    assert res.ok and res.proposal["two_qubit/(0, 1)/CZ/pulse"][0]["kwargs"]["amp"] == \
        pytest.approx(A, abs=0.02)
    state["knob"] = "dur"
    res = CZSweep(cfg, (0, 1), "dur", points=21, shots=200).run(r.drv)
    assert res.ok and res.proposal["two_qubit/(0, 1)/CZ/pulse"][0]["time"] == pytest.approx(200e-9, rel=0.05)


def test_jazz_recovers_zz(responder):
    """Both cores read out; per control state the target's I (X90 close) / Q (Y90 close) fringes
    at f0 / f0 + ZZ over the full delay 2w; ZZ11 = f(1) − f(0)."""
    cfg = _drive_cfg()
    zz, f0, m = 0.9e6, 1.5e6, SocMap(SocParams.load(_SIM2Q))
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        reads, out = _reader(progs)
        assert sorted(reads) == [0, 1]
        prep, quad = params[0]["r0"], _rad(params[1]["r1"])
        w = _axis(progs[1], params[1], (("x0", "dx0"),))
        t = 2 * w / m.params.dsp_freq_hz
        f = f0 + prep * zz
        env = 0.45 * np.exp(-t / 3e-6)
        P = 0.5 + env * (np.cos(2 * math.pi * f * t) if quad == 0 else -np.sin(2 * math.pi * f * t))
        out[1] = {"out": counts(P, 400)}
        return out

    res = JAZZ(cfg, (0, 1), detune=0.0, points=30, t0=40e-9, dt=40e-9, shots=400).run(r.drv)
    assert res.ok and len(r.setups) == 1 and len(r.reruns) == 4
    assert res.proposal["two_qubit/(0, 1)/ZZ11"] == pytest.approx(zz, abs=1e5)


# ── CZRPE ──

@pytest.mark.parametrize("sign", [1, -1])
def test_cz_rpe_recovers_planted_generator_errors(responder, sign):
    """The three ladders through the base: (0, 1)/(2, 3) Ramsey the target, (3, 1) the control
    (a second experiment), each rung `depth` CZs read at the balanced closes; the Ramsey core's
    P(|1>) = (1 − sin(Θ − φ_close))/2 at Θ = d·(A_phys − its own config vz)."""
    dzz, diz, dzi = 0.06 * sign, 0.12 * sign, -0.10 * sign
    zi_c, iz_c = -0.15 * sign, 0.20 * sign
    thzz, thiz, thzi = -np.pi / 2 + dzz, np.pi / 2 + diz, np.pi / 2 + dzi
    a_phys = {(0, 1): thiz + thzz, (2, 3): thiz - thzz, (3, 1): thzi - thzz}
    shots = 4096
    cfg = _drive_cfg()
    cfg["two_qubit/(0, 1)/CZ/pulse"][2]["kwargs"]["phase"] = zi_c
    cfg["two_qubit/(0, 1)/CZ/pulse"][3]["kwargs"]["phase"] = iz_c
    vz_of = {0: zi_c, 1: iz_c}
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        ramsey = 1 if len(r.setups) == 1 else 0                  # the target's ladders run first
        other = 1 - ramsey
        p = params[ramsey]
        depth, prep, quad = p["r0"], p["r1"], _quad(p["r2"])
        sp = (3, 1) if ramsey == 0 else ((2, 3) if prep else (0, 1))
        theta = depth * (a_phys[sp] - vz_of[ramsey])
        p1 = (1.0 - np.sin(theta - PHI_CLOSE[quad])) / 2.0
        _, out = _reader(progs)
        out[ramsey] = {"out": np.array([round(p1 * shots)])}
        out[other] = {"out": np.array([0])}
        return out

    cal = CZRPE(cfg, (0, 1), depths=(1, 2, 4, 8), shots=shots, gain=1.0, max_step=1.0)
    res = cal.run(r.drv)
    assert res.ok and len(r.setups) == 2
    a = cal.angles[(0, 1)]
    assert a.trusted["ZZ"] == pytest.approx(thzz, abs=5e-3)
    assert a.trusted["IZ"] == pytest.approx(thiz - iz_c, abs=5e-3)
    assert a.trusted["ZI"] == pytest.approx(thzi - zi_c, abs=5e-3)
    assert res.data[(0, 1)]["zz_error"] == pytest.approx(dzz, abs=5e-3)
    pl = res.proposal["two_qubit/(0, 1)/CZ/pulse"]
    assert pl[2]["kwargs"]["phase"] == pytest.approx(dzi, abs=5e-3)
    assert pl[3]["kwargs"]["phase"] == pytest.approx(diz, abs=5e-3)
    assert cfg["two_qubit/(0, 1)/CZ/pulse"][2]["kwargs"]["phase"] == zi_c


# ── the single-qubit RPE ladders: the plant through the new Param images ──

def _sq_cfg():
    m = SocMap(SocParams.load(_SIM2Q))
    return _cfg(m, F_GE), m


def _ramsey_p1(wait_batches, close_rad, delta_hz, m):
    """The rung's fringe in the class's own convention (its QUADRATURES docstring): pyRPE reads
    (C+ − C−)/(C+ + C−) as cos / sin of the accrued angle, so P = (1 + cos(θ + close))/2 makes the
    cos pair (closes 0, π) read cos θ and the sin pair (−π/2, +π/2) read sin θ."""
    theta = 2 * math.pi * delta_hz * wait_batches / m.params.dsp_freq_hz
    return (1 + math.cos(theta + close_rad)) / 2


def test_rpe_frequency_recovers_the_planted_detuning(responder):
    """The depth and the closing quadrature are Params of ONE image: the answer reads the rung's
    idle (`r0` × t_idle) and close phase (`r1`) off the rerun and returns the Ramsey fringe of a
    planted carrier error; the ladder recovers it and SUBTRACTS it from the carrier."""
    cfg, m = _sq_cfg()
    delta = 60e3
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            b, p = prog.bindings, params.get(q, {})
            wait, close = int(p["r0"]) * 10, _rad(p["r1"])        # depth × 10 batches
            out[q] = {"out": counts([_ramsey_p1(wait, close, delta, m)], b["shots"])}
        return out

    kw = dict(t_idle=10 / m.params.dsp_freq_hz, depths=(1, 2, 4, 8), shots=512)
    cal = RPEFrequency(cfg, 0, **kw)
    res = cal.run(r.drv)
    assert res.ok and len(r.setups) == 1                           # one image for the whole ladder
    assert cal.recovered_detuning[0] == pytest.approx(delta, rel=0.02)
    assert res.proposal["qubit/0/freq"] == pytest.approx(F_GE - delta, abs=2e3)


def test_rpe_amplitude_recovers_the_planted_over_rotation(responder):
    """The train length is the Param: a planted per-gate angle θ comes back as the multiplicative
    amplitude correction (π/2)/θ."""
    cfg, m = _sq_cfg()
    theta = math.pi / 2 * 1.04                                     # a 4 % over-rotation
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            b, p = prog.bindings, params.get(q, {})
            out[q] = {"out": counts([(1 - math.cos(int(p["r0"]) * theta)) / 2], b["shots"])}
        return out

    cal = RPEAmplitude(cfg, 0, depths=(1, 2, 4), shots=512)
    res = cal.run(r.drv)
    assert res.ok and len(r.setups) == 1
    # the balanced d+2 / d pair reads the over-rotation back with the estimator's own few-%
    # systematic (its closes are a π apart only at an exact π/2 gate), so the claim is the
    # direction and size of the correction, not a 4th digit
    amp = float(cfg["qubit/0/x90/amp"])
    assert theta < cal.recovered_angle[0] < theta * 1.05
    assert res.proposal["qubit/0/x90/amp"] == pytest.approx(amp / 1.04, rel=0.03)
    assert res.proposal["qubit/0/x90/amp"] < amp                   # an over-rotation turns it down


def test_rpe_phase_recovers_the_planted_axis_tilt(responder):
    """Two Param experiments — the direct trains and the interleaved echo — of ONE planted X90
    whose axis is tilted out of the drive plane by `tilt`; the recombination recovers the tilt and
    writes the virtual-Z shift that straightens it.

    Both ladders are planted in the estimator's own convention (`x90_angles`): the direct train of
    n gates leaves P = (1 − cos(n·Ω))/2, whose d+2 / d and d+1 / d+3 balanced pairs read
    cos / sin of d·Ω; the echo's `tail` closing X90s are a quarter turn apart each, so
    P = (1 − cos(d·θ_int − tail·π/2))/2 reads cos / sin of the accrued d·θ_int. `θ_int` is the
    per-block angle that `x90_angles`' linearization maps back to the planted tilt."""
    cfg, m = _sq_cfg()
    tilt, over = 0.05, 1.02
    omega = math.pi / 2 * over                                      # the planted rotation angle
    eps = over - 1.0
    theta_int = 2 * math.asin(2 * tilt * math.cos(math.pi * eps / 2))
    r = responder(_SIM2Q)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            b, p = prog.bindings, params.get(q, {})
            if "r1" in p:                                           # the echo: (depth, tail)
                p1 = (1 - math.cos(int(p["r0"]) * theta_int - int(p["r1"]) * math.pi / 2)) / 2
            else:                                                   # the direct train: n gates
                p1 = (1 - math.cos(int(p["r0"]) * omega)) / 2
            out[q] = {"out": counts([p1], b["shots"])}
        return out

    cal = RPEPhase(cfg, 0, depths=(1, 2, 4), shots=4096)
    res = cal.run(r.drv)
    assert res.ok and len(r.setups) == 2                            # the direct + the echo images
    want = vz_correction(omega * math.cos(tilt), omega * math.sin(tilt))
    assert cal.recovered_tilt[0] == pytest.approx(want, abs=0.01)
    assert res.proposal["qubit/0/x90/vz"] == pytest.approx([want, want], abs=0.01)
