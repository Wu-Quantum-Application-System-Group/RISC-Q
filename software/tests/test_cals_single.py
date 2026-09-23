"""L0 gates for universal-cal V3: the EF aliases (Amplitude / Frequency / Phase on the EF gate
after a GE π, read as P(|2>)) recover their planted EF truths on the host-pure responder, as do
Spectroscopy, T2(echoes / apply_freq) and Amplitude(derive_hpi)."""

import math
import re
from pathlib import Path

import numpy as np
import pytest

from riscq.cal.axes import Axis
from riscq.cal.cals import Amplitude, EFAmplitude, EFFrequency, EFPhase, Spectroscopy, T2
from riscq.cal.sequence import compile_sequence, emit_header, POST_MARGIN
from riscq.map import LEAD
from riscq.pulses import units
from tests.responder import counts
from tests.cal_fixtures import F_GE, _MEANS, _cfg, _clf
from tests.test_sequence import _axis, _phase_axis

SIM2Q = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"


def _s(n, m):
    return units.ns(n, m.params) * 1e-9


def _ef_cfg(m):
    c = _cfg(m, F_GE)
    c["qubit/0/x/amp"] = 0.9
    c["qubit/0/x90/vz"] = [0.1, 0.2]
    c["qubit/0/EF/freq"] = 45e6
    c["qubit/0/EF/x90/amp"] = 0.4
    c["qubit/0/EF/x/amp"] = 0.7
    c["qubit/0/EF/x90/vz"] = [-0.05, 0.15]
    return c


def _levels_rows(p2, shots):
    """RAW IQ per point: round(p·shots) shots on the |2> centroid, the rest on |1>."""
    rows = []
    for p in np.atleast_1d(p2):
        n2 = int(round(float(np.clip(p, 0, 1)) * shots))
        rows.append(np.vstack([np.tile(_MEANS[2], (n2, 1)), np.tile(_MEANS[1], (shots - n2, 1))]))
    return np.vstack(rows).reshape(-1).astype(np.int64)


def _q16(prog, params, names):
    s = {**prog.bindings, **(params or {})}
    for x0, dx in names:
        if x0 in s:
            n = int(prog.bindings["npts"])
            return ((int(s[x0]) + np.arange(n, dtype=np.int64) * int(s[dx])) >> 16).astype(np.int64)
    raise KeyError(names)


# (gate, n_gates, amp span). The amplified train returns to |2>'s starting point exactly where the
# single EF gate is right, so the planted π/2 (X90) / π (X) amp code is `angle / RABI` on every
# rung; the n > 1 spans bracket ONE return, which is what makes the parabola rule meaningful.
_EF_AMP_CASES = [("X90", 1, (0.03, 0.5)), ("X90", 4, (0.04, 0.11)), ("X", 2, (0.08, 0.215))]
_GATE_ANGLE = {"X90": math.pi / 2, "X": math.pi}


@pytest.mark.parametrize("gate,n_gates,span", _EF_AMP_CASES)
def test_ef_amplitude_recovers_the_planted_ef_rabi(responder, socmap, gate, n_gates, span):
    """P(|2>) is planted as (1 − cos(RABI·n·a))/2 against the swept EF amplitude CODE, so the EF
    gate's own angle is reached at a* = angle / RABI codes; the proposal is that amplitude."""
    m = socmap
    r = responder(SIM2Q)
    RABI = 1.1e-3

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            xs = _q16(prog, params.get(q), (("x0", "dx0"),))
            out[q] = {"out": _levels_rows((1 - np.cos(RABI * n_gates * xs)) / 2, prog.bindings["shots"])}
        return out

    points = 15
    res = EFAmplitude(_ef_cfg(m), 0, _clf(), gate=gate, n_gates=n_gates, amp_span=span,
                      points=points, shots=32).run(r.drv)
    assert res.ok
    a_star = _GATE_ANGLE[gate] / RABI / units.AMP_SCALE
    path = f"qubit/0/EF/{'x90' if gate == 'X90' else 'x'}/amp"
    assert res.proposal[path] == pytest.approx(a_star, abs=(span[1] - span[0]) / (points - 1))


def test_ef_frequency_recovers_the_planted_ef_detuning(responder, socmap):
    """The Config's EF carrier sits `d0` frequency codes above the planted EF line, so the EF
    Ramsey fringes run at |applied + d0|: the V-fit must put the EF carrier back on the line."""
    m = socmap
    r = responder(SIM2Q)
    d0 = 40
    delta_hz = units.code_to_freq(d0, m.params)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            waits = _axis(prog, params.get(q), (("x0", "dx0"),))
            phi = (_phase_axis(prog, params[q], (("x1", "dx1"),))
                   + 2 * math.pi * delta_hz * units.ns(waits, m.params) * 1e-9)
            out[q] = {"out": _levels_rows(0.5 + 0.5 * np.exp(-waits / 3000.0) * np.cos(phi), prog.bindings["shots"])}
        return out

    cfg = _ef_cfg(m)
    f_ef = units.code_to_freq(units._freq_code(45e6, m.params), m.params)      # the planted EF line
    cfg["qubit/0/EF/freq"] = units.code_to_freq(units._freq_code(45e6, m.params) + d0, m.params)
    cal = EFFrequency(cfg, 0, _clf(), detune=units.code_to_freq(200, m.params), n_detune=4,
                      t0=_s(8, m), dt=_s(4, m), points=12, shots=32)
    res = cal.run(r.drv)
    assert res.ok
    assert cal.recovered_detuning_code[0] == pytest.approx(d0, abs=2.0)
    assert res.proposal["qubit/0/EF/freq"] == pytest.approx(f_ef, abs=units.code_to_freq(2, m.params))


@pytest.mark.parametrize("gate", ["X90", "X"])
def test_ef_phase_recovers_the_planted_ef_axis(responder, socmap, gate):
    """The EF crossing circuits are planted to cross at φ* in P(|2>) (and the EF X circuit to
    minimise there); the proposal is the EF vz pair / the EF X's own axis phase."""
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
                p2 = 0.5 - 0.5 * np.cos(2 * (phi - star))
            else:
                d = np.sin(phi - star)
                p2 = 0.5 * (1 - d) if c == "Y180_X90" else 0.5 * (1 + d)
            out[q] = {"out": _levels_rows(p2, prog.bindings["shots"])}
        return out

    points, span = 11, 0.3 if gate == "X90" else math.pi
    res = EFPhase(_ef_cfg(m), 0, _clf(), gate=gate, points=points,
                  span=0.3 if gate == "X90" else None, shots=32).run(r.drv)
    assert res.ok
    step = 2 * span / (points - 1)
    if gate == "X90":
        assert res.proposal["qubit/0/EF/x90/vz"] == pytest.approx([star, star], abs=step)
    else:
        assert res.proposal["qubit/0/EF/x/phase"] == pytest.approx(star, abs=step)


def test_spectroscopy_recovers_a_planted_line(responder, socmap):
    m = socmap
    r = responder(SIM2Q)
    line = {"f": F_GE + 1.3e6}

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            codes = _q16(prog, params.get(q), (("x0", "dx0"),))
            f = units.code_to_freq(codes, m.params)
            p1 = 0.05 + 0.8 / (1 + ((f - line["f"]) / 0.4e6) ** 2)
            out[q] = {"out": counts(p1, prog.bindings["shots"])}
        return out

    cfg = _cfg(m, F_GE)
    res = Spectroscopy(cfg, 0, span=8e6, points=41, probe=(0.02, _s(64, m)), shots=200).run(r.drv)
    assert res.ok
    assert res.proposal["qubit/0/freq"] == pytest.approx(line["f"], abs=30e3)
    # the EF line: bracketed by the GE π, the probe centred on EF/freq, proposing EF/freq
    cfg["qubit/0/EF/freq"] = 45e6
    line["f"] = 45e6 - 0.9e6
    res = Spectroscopy(cfg, 0, span=8e6, points=41, probe=(0.02, _s(64, m)), shots=200,
                       prep=("x90", "x90"), target="EF/freq").run(r.drv)
    assert set(res.proposal) == {"qubit/0/EF/freq"}
    assert res.proposal["qubit/0/EF/freq"] == pytest.approx(line["f"], abs=30e3)
    cal = Spectroscopy(cfg, 0, span=8e6, points=41, probe=(0.02, _s(64, m)), prep=("x90", "x90"),
                       target="EF/freq")
    cal.m = m
    ax = cal.axes(0, m)
    comp = compile_sequence(cfg, 0, cal._full(0, ax), m, ax, ())
    assert [r_.key.split("/")[0] for r_ in comp.rows] == ["x90", "x90", "lit"]   # two prep X90s, the probe
    assert comp.rows[2].retune and comp.rows[2].t.const == -8 - 64                # retuned, LEAD gap before


def test_t2_echoes_and_apply_freq(responder, socmap):
    m = socmap
    cfg = _ef_cfg(m)
    cal = T2(cfg, 0, detune=units.code_to_freq(70, m.params), points=5, t0=_s(16, m), dt=_s(32, m), echoes=2)
    ax = cal.axes(0, m)
    comp = compile_sequence(cfg, 0, cal.sequence(0, ax), m, ax, ())
    assert [r.key for r in comp.rows] == ["x90"] * 6           # X90 · (π π)×2 · X90
    # 4 idles of the swept quarter-wait, plus R4's constant gap before the fifth play: at the
    # shortest wait six 4-batch plays sit inside one LEAD and the depth-4 queue cannot post them
    lo = ax[0].range[0]
    gap = max(0, LEAD + POST_MARGIN - (4 * 4 + 3 * lo))
    assert gap > 0 and comp.seq_len == 6 * 4 + 4 * ax[0].range[1] + gap
    r = responder(SIM2Q)
    d_true = 15                                               # codes off the carrier

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            waits = _axis(prog, params.get(q), (("x0", "dx0"),))
            phi = _phase_axis(prog, params[q], (("x1", "dx1"),)) + \
                2 * math.pi * units.code_to_freq(d_true, m.params) * units.ns(waits, m.params) * 1e-9
            out[q] = {"out": counts(0.5 + 0.5 * np.exp(-waits / 3000.0) * np.cos(phi), prog.bindings["shots"])}
        return out

    cfg = _cfg(m, F_GE)
    res = T2(cfg, 0, detune=units.code_to_freq(200, m.params), points=24, t0=_s(8, m), dt=_s(8, m),
             shots=400, apply_freq=True).run(r.drv)
    assert res.ok
    # the carrier is d_true codes ABOVE the qubit: the fringe runs at |200 + 15| = 215; the
    # abs-smallest rule picks 200 − 215 = −15 codes → the proposal moves the carrier down by d_true
    assert res.proposal["qubit/0/freq"] == pytest.approx(F_GE - units.code_to_freq(d_true, m.params),
                                                         abs=units.code_to_freq(1, m.params))


def test_amplitude_derive_hpi(responder, socmap):
    m = socmap
    r = responder(SIM2Q)
    RABI = 0.9e-3

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            xs = _q16(prog, params.get(q), (("x0", "dx0"),))
            out[q] = {"out": counts((1 - np.cos(RABI * xs)) / 2, prog.bindings["shots"])}
        return out

    cfg = _ef_cfg(m)
    res = Amplitude(cfg, 0, gate="X", points=15, shots=200, derive_hpi=True).run(r.drv)
    assert res.ok and res.proposal["qubit/0/x90/amp"] == pytest.approx(res.proposal["qubit/0/x/amp"] / 2)
