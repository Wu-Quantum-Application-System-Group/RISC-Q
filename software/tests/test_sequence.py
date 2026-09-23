"""L0 gates of specs/universal-cal V0: the sequence compiler's timing table and C against the
rules the old kernels hand-placed, the generated header through `k_batched` + gcc, and the
single-qubit classes recovering planted truths on the host-pure responder."""

import math
from pathlib import Path

import numpy as np
import pytest

from riscq.cal.axes import Axis, Lin, Param
from riscq.cal.base import TRAIN_STEP, SEP, TRAIN_AHEAD, train_step  # noqa: F401
from riscq.cal.batched import COUNTS, k_batched
from riscq.cal.cals import Amplitude
from riscq.cal.measure import Measure
from riscq.cal.sequence import (Gate, Idle, Par, Repeat, Rz, Train, compile_sequence, emit_header)
from riscq.lang import Array, compile_kernel
from riscq.map import LEAD, SocMap, SocParams
from riscq.pulses import Pulse, envelopes, units
from tests.responder import counts, q16_axis
from tests.cal_fixtures import F_GE, _cfg, _clf, _leakage_cfg, _levels_iq

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
SIM2Q = CONFIGS / "sim-2q.json"


def _ef_cfg(m):
    c = _cfg(m, F_GE)
    c["qubit/0/x/amp"] = 0.9
    c["qubit/0/x90/vz"] = [0.1, 0.2]
    c["qubit/0/EF/freq"] = 45e6
    c["qubit/0/EF/x90/amp"] = 0.4
    return c


def _t(row):
    return row.t.const


# ── the timing rules ──

def test_rabi_train_places_like_k_rabi(socmap):
    """R1/R4: n plays on the train_step grid, the last ending SEP before t_ro; plays k ≥ 4 wait
    for play k−4's start (k_rabi's `tp` marks)."""
    m = socmap
    cfg = _cfg(m, F_GE)
    a = Axis.amp(0.03, 0.97, 5)
    comp = compile_sequence(cfg, 0, [Train(Gate("x90", amp=a), 6)], m, (a,), ())
    d, step = 4, train_step(4)
    assert [_t(r) for r in comp.rows] == [-SEP - (5 - k) * step - d for k in range(6)]
    assert comp.seq_len == 5 * step + d
    hdr = emit_header(comp, 0, Measure.counts().tables(cfg, 0, m, comp.tables))
    waits = [ln.strip() for ln in hdr.splitlines() if "wait_until" in ln]
    assert waits == [f"wait_until(t_ro - {-_t(comp.rows[k - TRAIN_AHEAD])});" for k in (4, 5)]
    assert "set_amp(RF_CH0, 0, a);" in hdr and "play(RF_CH1, 0, t);" in hdr


def test_ramsey_wait_axis_and_frame_bracket(socmap):
    """A wait axis moves the FIRST X90 earlier (`t_ro - SEP - 2d - a`); the swept virtual-Z is a
    frame advance between the two plays, composed with each play's own bracket (k_ramsey)."""
    m = socmap
    cfg = _ef_cfg(m)
    w = Axis.wait_batches(8, 20, 5, m, detune=1e6)
    comp = compile_sequence(cfg, 0, [Gate("x90"), Idle(w), Rz("x90", w), Gate("x90")], m, (w,), ())
    first, second = comp.rows
    assert second.t.const == -SEP - 4 and first.t.terms == ((w, -1),) and first.t.const == -SEP - 8
    assert comp.seq_len == 8 + 88                       # 2d + the longest wait
    hdr = emit_header(comp, 0, None)
    lines = [ln.strip() for ln in hdr.splitlines()]
    vz0 = f"{units._phase_code(0.1) << 16:#X}".replace("0X", "0x")
    assert f"set_phase_offset(RF_CH0, f0 + {vz0});" in lines      # both plays: frame + vz0
    assert "f0 += a;" in lines                                      # the Rz on the swept axis
    assert lines.count(f"set_phase_offset(RF_CH0, f0 + {vz0});") == 2


def test_phase_circuit_y180_x90(socmap):
    """qcal's Y180_X90 with the swept phi REPLACING the pair (vz0 = vz1 = phi): k_phase's frame
    arithmetic `f + phi; f += 2·phi` per play, Rz(±π/2) around the first two."""
    m = socmap
    cfg = _cfg(m, F_GE)
    p = Axis.phase(-0.25, 0.25, 7)
    seq = [Rz("x90", math.pi / 2), Gate("x90", vz=p), Gate("x90", vz=p), Rz("x90", -math.pi / 2),
           Gate("x90", vz=p)]
    comp = compile_sequence(cfg, 0, seq, m, (p,), ())
    assert [_t(r) for r in comp.rows] == [-SEP - 12, -SEP - 8, -SEP - 4]     # contiguous
    body = [ln.strip() for ln in emit_header(comp, 0, None).splitlines()]
    hpi = units._phase_code(math.pi / 2) << 16
    assert body.count("set_phase_offset(RF_CH0, f0 + a);") == 3
    assert body.count("f0 += 2 * a;") == 3
    assert f"f0 += {hpi:#X};".replace("0X", "0x") in body
    assert f"f0 += -{hpi:#X};".replace("0X", "0x") in body


def test_phase_circuit_x180_y90(socmap):
    """The second crossing circuit: the Rz(+π/2) sits between the second and third X90 (k_phase's
    X180_Y90 frame arithmetic) — pinned here because the L1 parity can only align a first run."""
    m = socmap
    cfg = _cfg(m, F_GE)
    p = Axis.phase(-0.25, 0.25, 7)
    from riscq.cal.cals.single import Phase
    comp = compile_sequence(cfg, 0, Phase.CIRCUITS["X180_Y90"](p), m, (p,), ())
    body = [ln.strip() for ln in emit_header(comp, 0, None).splitlines()]
    hpi = f"{units._phase_code(math.pi / 2) << 16:#X}".replace("0X", "0x")
    plays = [i for i, ln in enumerate(body) if ln.startswith("play(")]
    rz = body.index(f"f0 += {hpi};")
    assert plays[1] < rz < plays[2] and body.count("set_phase_offset(RF_CH0, f0 + a);") == 3


def test_ef_retune_gap_and_fresh_frame(socmap):
    """R5/R6: a channel with two carriers retunes its first play each shot (set_start + set_freq)
    and puts a LEAD gap before the EF play; the EF frame starts at 0 (k_ef_rabi)."""
    m = socmap
    cfg = _ef_cfg(m)
    a = Axis.amp(0.03, 0.97, 5)
    comp = compile_sequence(cfg, 0, [Gate("x"), Gate("EF/x90", amp=a)], m, (a,), ())
    x, ef = comp.rows
    assert ef.t.const == -SEP - 4 and x.t.const == -SEP - 4 - LEAD - 4
    assert x.retune and ef.retune
    hdr = emit_header(comp, 0, None)
    ge, efc = units.freq_to_code(F_GE, m.params), units.freq_to_code(45e6, m.params)
    assert f"set_freq(RF_CH0, {ge:#X});".replace("0X", "0x") in hdr
    assert f"set_freq(RF_CH0, {efc:#X});".replace("0X", "0x") in hdr
    assert "int32_t f1 = 0;" in hdr and "set_phase_offset(RF_CH0, f1);" in hdr


def test_runtime_train_is_a_c_loop(socmap):
    m = socmap
    cfg = _cfg(m, F_GE)
    n = Param("n", (1, 4, 8))
    comp = compile_sequence(cfg, 0, [Train(Gate("x90"), n)], m, (), (n,))
    assert comp.seq_len == 7 * train_step(4) + 4            # sized for the deepest rung (R9)
    hdr = emit_header(comp, 0, None)
    assert "for (int32_t k = 0; k < r0; k++) {" in hdr
    assert "wait_until(t_ro + (-" in hdr and "36 * k" in hdr
    assert comp.rows[0].ctx == "for k<r0"


def test_repeat_param_body_and_cond(socmap):
    m = socmap
    cfg = _ef_cfg(m)
    d, prep = Param("d", (1, 2, 3)), Param("prep", (0, 1))
    from riscq.cal.sequence import Cond
    seq = [Cond(prep, [Gate("x")]), Repeat(d, [Rz("x90", math.pi / 2), Gate("x90"), Gate("x90")])]
    comp = compile_sequence(cfg, 0, seq, m, (), (d, prep))
    hdr = emit_header(comp, 0, None)
    assert "if (r1) {" in hdr and "for (int32_t k = 0; k < r0; k++) {" in hdr
    assert comp.seq_len == 4 + 3 * 2 * TRAIN_STEP           # two 4-batch plays per iteration, on the train grid


def test_par_end_aligned_on_two_lines():
    """Two lines of one multi-channel core end together (sim-mm: gate + f0g1 on core 0)."""
    m = SocMap(SocParams.load(CONFIGS / "sim-mm.json"))
    cfg = _cfg(m, F_GE)
    cfg["lines/f0g1/core"] = 0
    probe = Pulse(envelopes.square(16 * 4), 0.3, freq_hz=20e6)
    comp = compile_sequence(cfg, 0, [Par([Gate("x90"), Gate(probe, line="f0g1")])], m, (), ())
    x90, pr = comp.rows
    assert pr.channel == "f0g1" and x90.t.const == -SEP - 4 and pr.t.const == -SEP - 16
    assert comp.seq_len == 16
    with pytest.raises(ValueError, match="share a line"):
        compile_sequence(cfg, 0, [Par([Gate("x90"), Gate("x90")])], m, (), ())


def test_flat_top_dur_axis():
    """R7: a flat-top gate is up / body×reps / down; `dur=` sweeps the body slot and moves the
    down ramp with it."""
    m = SocMap(SocParams.load(CONFIGS / "sim-mm.json"))
    cfg = _cfg(m, F_GE)
    cfg["lines/f0g1/core"] = 0
    from riscq.pulses import flat_top
    ft = flat_top(m.channel_named("f0g1", 0), ramp_batches=8, flat_batches=100, amp=0.4, freq_hz=20e6)
    dur = Axis.wait_batches(50, 10, 3, m)
    out = compile_sequence(cfg, 0, [Gate(ft, line="f0g1", dur=dur)], m, (dur,), ())
    assert [r.key.split("/")[-1] for r in out.rows] == ["up", "body", "down"]
    assert out.rows[0].t.terms == ((dur, -1),) and out.rows[1].dur == Lin.of(dur)
    assert out.seq_len == 8 + 70 + 8
    hdr = emit_header(out, 0, None)
    assert "set_dur(RF_CH1, 1, a);" in hdr and hdr.count("play(RF_CH1") == 3


# ── the header through the kernel and gcc ──

def test_header_compiles_with_k_batched(socmap):
    m = socmap
    cfg = _cfg(m, F_GE)
    a = Axis.amp(0.03, 0.97, 5)
    comp = compile_sequence(cfg, 0, [Train(Gate("x90", amp=a), 4)], m, (a,), ())
    mi = Measure.counts().tables(cfg, 0, m, comp.tables)
    hdr = emit_header(comp, 0, mi, "test")
    from riscq.lang import Group
    prog = compile_kernel(k_batched, m, core=0, grp=Group([0]), tables=comp.tables.for_core(0),
                          include=[("seq_core0.h", hdr)], out=Array(5), npts=5, shots=2,
                          period=400, mode=COUNTS, herald=0, hoff=0, sh=0)
    assert set(prog.tables) == {"tbl_gate", "tbl_ro", "tbl_demod"}
    assert '#include "seq_core0.h"' in prog.c_source
    assert set(prog.params) == {"x0", "dx0", "x1", "dx1", "r0", "r1", "r2", "r3"}


# ── Amplitude on the planted Rabi rate ──

# (n_gates, gate, amp span). The train of `n_gates` returns to |0> exactly where the single gate
# is right, so the planted π/2 (X90) / π (X) amp code is `angle / RABI` on every rung: the n=1
# cosine fit and the n>1 parabola vertex are two readings of the SAME plant. The amplified spans
# bracket ONE return (≈ 3.5…9.4 rad of train angle), which is what makes a parabola the right rule.
_AMP_CASES = [(1, "X90", (0.03, 0.97)), (4, "X90", (0.05, 0.13)), (2, "X", (0.10, 0.26))]
_GATE_ANGLE = {"X90": math.pi / 2, "X": math.pi}


@pytest.mark.parametrize("n_gates,gate,span", _AMP_CASES)
def test_amplitude_recovers_the_planted_rabi(responder, socmap, n_gates, gate, span):
    """The responder plants P(1) = (1 − cos(RABI·n·a))/2 against the swept amplitude CODE, so the
    gate's own angle is reached at a* = angle / RABI codes. `Amplitude` must propose that amplitude
    (normalised) to within one sweep step."""
    m = socmap
    cfg = _ef_cfg(m)
    r = responder(SIM2Q)
    RABI = 0.9e-3
    angle = _GATE_ANGLE[gate]

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            xs = q16_axis(prog, params.get(q), x0="x0", dx="dx0")
            p1 = (1 - np.cos(RABI * n_gates * xs)) / 2
            out[q] = {"out": counts(p1, prog.bindings["shots"])}
        return out

    points = 15
    res = Amplitude(cfg, 0, gate=gate, n_gates=n_gates, amp_span=span, points=points,
                    shots=200).run(r.drv)
    assert res.ok
    a_star = angle / RABI / units.AMP_SCALE                    # normalised amplitude
    step = (span[1] - span[0]) / (points - 1)
    path = f"qubit/0/{'x90' if gate == 'X90' else 'x'}/amp"
    assert res.proposal[path] == pytest.approx(a_star, abs=step)
    assert len(r.setups) == 1                                  # ONE compile: the amp is an on-core axis


# ── V1: the ported classes against the planted physics on the responder ──

def _src(prog, params):
    return {**prog.bindings, **(params or {})}


def _axis(prog, params, names):
    """A plain int on-core axis from whichever descriptor the program carries."""
    s = _src(prog, params)
    for x0, dx in names:
        if x0 in s:
            return int(s[x0]) + np.arange(int(prog.bindings["npts"]), dtype=np.int64) * int(s[dx])
    raise KeyError(names)


def _unseat(word) -> int:
    c = (int(word) >> 16) & 0xFFFF
    return c - (1 << 16) if c >= (1 << 15) else c


def _phase_axis(prog, params, names):
    s = _src(prog, params)
    for p0, dp in names:
        if p0 in s:
            n = int(prog.bindings["npts"])
            return (_unseat(s[p0]) + np.arange(n) * _unseat(s[dp])) * math.pi / (1 << 15)
    raise KeyError(names)


def _cnt(p1, prog):
    from tests.responder import counts_heralded
    shots = int(prog.bindings["shots"])
    return counts_heralded(p1, shots) if prog.bindings.get("herald") else counts(p1, shots)


def _s(n, m):
    return units.ns(n, m.params) * 1e-9


@pytest.mark.parametrize("d0_code", [60, -60])
def test_frequency_recovers_the_planted_detuning(responder, socmap, d0_code):
    """The carrier the Config drives at sits `d0_code` frequency codes ABOVE the planted qubit, so
    every fringe runs at |applied + d0|: the V-fit's vertex must recover d0 and the proposal put
    the carrier back on F_GE."""
    from riscq.cal.cals import Frequency
    m = socmap
    r = responder(SIM2Q)
    drive = units.code_to_freq(units._freq_code(F_GE, m.params) + d0_code, m.params)
    delta_hz = units.code_to_freq(d0_code, m.params)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            waits = _axis(prog, params.get(q), (("x0", "dx0"),))
            phi = (_phase_axis(prog, params[q], (("x1", "dx1"),))
                   + 2 * math.pi * delta_hz * units.ns(waits, m.params) * 1e-9)
            out[q] = {"out": _cnt(0.5 + 0.5 * np.exp(-waits / 3000.0) * np.cos(phi), prog)}
        return out

    cal = Frequency(cfg := _cfg(m, drive, relax=800), 0,
                    detune=units.code_to_freq(200, m.params), n_detune=4, t0=_s(8, m), dt=_s(4, m),
                    points=12, shots=96)
    res = cal.run(r.drv)
    assert res.ok and cfg["qubit/0/freq"] == drive                 # the Config is untouched
    assert cal.recovered_detuning_code[0] == pytest.approx(d0_code, abs=2.0)
    assert res.proposal["qubit/0/freq"] == pytest.approx(F_GE, abs=units.code_to_freq(2, m.params))
    assert len(r.setups) == 1                                      # ONE image: the detunings are reruns


def test_t2_recovers_the_planted_decay(responder, socmap):
    """The fringe is planted with a 200-batch envelope; the damped-cosine τ the class reports is
    that decay in seconds."""
    from riscq.cal.cals import T2
    m = socmap
    r = responder(SIM2Q)
    TAU = 200.0                                        # batches

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            waits = _axis(prog, params.get(q), (("x0", "dx0"),))
            phi = _phase_axis(prog, params[q], (("x1", "dx1"),))
            out[q] = {"out": _cnt(0.5 + 0.5 * np.exp(-waits / TAU) * np.cos(phi), prog)}
        return out

    res = T2(_cfg(m, F_GE), 0, detune=units.code_to_freq(70, m.params), points=15, t0=_s(8, m),
             dt=_s(16, m)).run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/T2"] == pytest.approx(_s(TAU, m), rel=0.1)
    assert len(r.setups) == 1


@pytest.mark.parametrize("gate", ["X90", "X"])
def test_t1_recovers_the_planted_decay(responder, socmap, gate):
    """T1 idles `delay − SEP` after the prep (the sequence ends SEP early, R1), so the DELAY the
    decay is planted against is the swept idle + SEP — which is exactly the x-axis the class fits."""
    from riscq.cal.cals import T1
    m = socmap
    r = responder(SIM2Q)
    TAU = 120.0                                        # batches

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            delays = _axis(prog, params.get(q), (("x0", "dx0"),)) + SEP
            out[q] = {"out": _cnt(np.exp(-delays / TAU), prog)}
        return out

    cal = T1(_ef_cfg(m), 0, points=9, gate=gate)
    res = cal.run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/T1"] == pytest.approx(_s(TAU, m), rel=0.1)
    assert res.data[0]["x"][0] == SEP                  # the first delay is the SEP floor


@pytest.mark.parametrize("gate", ["X90", "X"])
def test_phase_recovers_the_planted_axis(responder, socmap, gate):
    """The two crossing circuits are planted to cross at φ* (Y180_X90 falls, X180_Y90 rises through
    ½ there) and the X circuit to minimise at φ*; the proposal — the vz PAIR for X90, the X's own
    axis phase for X — must land on φ*."""
    from riscq.cal.cals import Phase
    import re
    m = socmap
    r = responder(SIM2Q)
    star = 0.1

    def circuit(prog):
        return re.search(r"seq_Phase_(\w+?)_core", prog.c_source).group(1)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            phi = _phase_axis(prog, params[q], (("x0", "dx0"),))
            c = circuit(prog)
            if c == "X90_X_X90":
                p1 = 0.5 - 0.5 * np.cos(2 * (phi - star))
            else:
                d = np.sin(phi - star)
                p1 = 0.5 * (1 - d) if c == "Y180_X90" else 0.5 * (1 + d)
            out[q] = {"out": _cnt(p1, prog)}
        return out

    points, span = 11, 0.3 if gate == "X90" else math.pi
    res = Phase(_ef_cfg(m), 0, gate=gate, points=points, span=0.3 if gate == "X90" else None,
                shots=64).run(r.drv)
    assert res.ok
    step = 2 * span / (points - 1)
    if gate == "X90":
        assert res.proposal["qubit/0/x90/vz"] == pytest.approx([star, star], abs=step)
    else:
        assert res.proposal["qubit/0/x/phase"] == pytest.approx(star, abs=step)


def test_leakage_picks_the_planted_minimum(responder, socmap):
    """P(|2>) is planted as a parabola over the swept virtual-Z pair with its minimum at 0.1; the
    class recompiles once per value (the pair is a compile-time binding) and must write that pair."""
    from riscq.cal.cals import Leakage
    r = responder(SIM2Q)
    phases = [-0.2, -0.1, 0.0, 0.1, 0.2]
    star = 0.1
    state = {"runs": 0}

    @r.answer
    def _(progs, params):
        p = 0.05 + 2.0 * (phases[state["runs"] % len(phases)] - star) ** 2
        state["runs"] += 1
        return {q: {"out": _levels_iq(p, prog.bindings["shots"])} for q, prog in progs.items()}

    res = Leakage(_leakage_cfg(), 0, _clf(), "qubit/{q}/x90/vz", [[p, p] for p in phases],
                  n_gates=8, shots=8).run(r.drv)
    assert res.proposal == {"qubit/0/x90/vz": [star, star]}
    assert np.argmin(res.data[0]["y"]) == phases.index(star)
    assert len(r.setups) == len(phases)                          # one compile per swept value
    assert r.setups[-1][0].host_arrays == r.setups[0][0].host_arrays != {}
