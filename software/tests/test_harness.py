"""The test harness's own tests.

`tests/responder.py` and `tests/probe.py` are load-bearing: an L0 test is only as good as the
axis its populations were computed on, and an L2 assertion is only as good as the ⟨σz⟩ projection
behind it. A silent bug in either weakens every test that uses them without failing anything, so
the helpers are pinned here against the production code they must agree with.
"""

import math
from pathlib import Path

import numpy as np
import pytest

from riscq.cal.base import gate_sigma, population, population_heralded, sweep_q16
from riscq.map import SocMap, SocParams
from riscq.pulses import Pulse, envelopes, units
from tests import probe
from tests.responder import counts, counts_heralded, int_axis, q16_axis, raw_iq

CONFIG = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"
M = SocMap(SocParams.load(CONFIG))


class _Prog:
    """Enough of a Program for the axis helpers: they read `bindings` only."""

    def __init__(self, **bindings):
        self.bindings = bindings
        self.c_source = "/* stub */"


# ── the point axes ──

@pytest.mark.parametrize("lo,hi,n", [(300, 19000, 21), (0, 100, 2), (5, 5, 1), (-8000, 8000, 13)])
def test_q16_axis_reproduces_sweep_q16(lo, hi, n):
    """THE property: the responder must compute its populations on exactly the codes the kernel
    realizes, or the class fits synthetic data against a shifted x-axis and the test is a lie.
    `sweep_q16` is what the calibration hands the kernel; `q16_axis` must invert it exactly —
    integer arithmetic, no float linspace."""
    a0q, daq, xs = sweep_q16(lo, hi, n)
    got = q16_axis(_Prog(npts=n), {"a0q": a0q, "daq": daq})
    assert np.array_equal(got, np.asarray(xs))


def test_axis_reads_runtime_params_and_compile_time_bindings():
    """The descriptor is split and which half depends on the cal: `Amplitude` passes a0q/daq as
    runtime params, `Frequency` bakes w0/dw as bindings. Both must resolve, and a runtime value
    must win over a stale binding of the same name (it is what that rerun actually realized)."""
    assert list(q16_axis(_Prog(npts=3, a0q=0, daq=1 << 16))) == [0, 1, 2]   # binding
    assert list(q16_axis(_Prog(npts=3), {"a0q": 0, "daq": 2 << 16})) == [0, 2, 4]  # param
    prog = _Prog(npts=3, a0q=0, daq=1 << 16)
    assert list(q16_axis(prog, {"a0q": 10 << 16, "daq": 1 << 16})) == [10, 11, 12]


def test_int_axis_is_the_plain_affine_grid():
    assert list(int_axis(_Prog(npts=4, w0=8, dw=16))) == [8, 24, 40, 56]


# ── the `out` encoders, against the production decoders ──

def test_counts_round_trips_through_population():
    """`counts` must be the exact inverse of what `base.population` does, or every L0 population
    is off by the encoder's own rounding."""
    p1 = np.array([0.0, 0.25, 0.5, 0.75, 1.0])
    assert np.allclose(population(counts(p1, 64), 64), p1)
    assert np.allclose(population(counts(p1, 64), 64, sign=-1), 1 - p1)


def test_counts_heralded_round_trips_and_defaults_to_every_shot_kept():
    """Heralded runs write interleaved (count, kept) and P = count/kept. The default `kept` is
    every shot — the clean-|0> qubit, where heralded and unheralded must agree."""
    p1 = np.array([0.1, 0.5, 0.9])
    out = counts_heralded(p1, 100)
    assert list(out) == [10, 100, 50, 100, 90, 100]
    assert np.allclose(population_heralded(out), p1)
    partial = counts_heralded(np.array([0.5]), 100, kept=[40])
    assert list(partial) == [20, 40] and np.allclose(population_heralded(partial), [0.5])


def test_raw_iq_is_point_major_interleaved():
    assert list(raw_iq([1 + 2j, 3 - 4j])) == [1, 2, 3, -4]


# ── the ⟨σz⟩ projection ──

def test_sigma_z_normalises_the_reference_to_plus_one():
    """The reference phasor sits at an arbitrary absolute angle (the demod LO), so the projection
    must divide by |z_ref|**2, not |z_ref| — otherwise ⟨σz⟩ comes back scaled by the integrator
    magnitude (~1.6e6), which no tolerance would catch as 'wrong' rather than 'huge'."""
    for ref in (1600000 + 0j, -3.7 + 2.1j, 1e6 * np.exp(1j * 2.3)):
        assert probe.sigma_z(ref, ref) == pytest.approx(1.0)
        assert probe.sigma_z(-ref, ref) == pytest.approx(-1.0)
        assert probe.sigma_z(0.5 * ref, ref) == pytest.approx(0.5)
        assert probe.sigma_z(1j * ref, ref) == pytest.approx(0.0, abs=1e-12)


def test_sigma_z_is_elementwise_over_a_sweep():
    ref = np.full(3, 2 + 1j)
    assert np.allclose(probe.sigma_z(np.array([2 + 1j, -2 - 1j, 0j]), ref), [1.0, -1.0, 0.0])


# ── the analytic-gate rate ──

@pytest.mark.parametrize("target", [math.pi / 2, math.pi, 2 * math.pi])
def test_rabi_for_makes_the_pulse_an_exact_rotation(target):
    """`rabi_for` is what lets an L2 test assert a textbook state: plant this rate and the pulse
    rotates by exactly `target`. The model integrates `rate · Σ amp_est`, so that product must be
    the target angle."""
    p = Pulse(envelopes.square(16), freq_hz=50e6, amp=0.5)
    rate = probe.rabi_for(M, p, 50e6, target)
    assert rate * gate_sigma(M, p, 50e6, p.amp_code()) == pytest.approx(target)


def test_rabi_for_takes_the_amplitude_from_the_pulse():
    """The footgun this helper exists to remove: AMP_SCALE is 19896, not 2**15. Hand-writing
    'half scale = 16384' inflates sigma by ~1.65x and silently mis-scales every angle — and the
    resulting ladder still looks self-consistent, so it does not announce itself."""
    assert units.AMP_SCALE == 19896
    p = Pulse(envelopes.square(16), freq_hz=50e6, amp=0.5)
    assert p.amp_code() == units._amp_code(0.5) == 9948
    naive = math.pi / 2 / gate_sigma(M, p, 50e6, 16384)
    assert probe.rabi_for(M, p, 50e6, math.pi / 2) / naive == pytest.approx(16384 / 9948, rel=1e-3)


# ── the Responder itself ──

def test_responder_fails_loud_without_an_answer(responder):
    r = responder(CONFIG)
    with pytest.raises(AssertionError, match="no answer function"):
        r._rerun(None, None, {0: _Prog()})


def test_responder_fails_loud_when_a_core_is_unanswered(responder):
    """A cal reads back every core it programmed; an answer that silently omits one would surface
    as a confusing KeyError deep inside the class instead of here."""
    r = responder(CONFIG)
    r.answer(lambda progs, params: {0: {"out": np.zeros(3)}})
    with pytest.raises(AssertionError, match=r"no data for core\(s\) \[1\]"):
        r._rerun(None, None, {0: _Prog(), 1: _Prog()})


def test_responder_records_setups_reruns_and_slot_writes(responder):
    r = responder(CONFIG)
    r.answer(lambda progs, params: {q: {"out": np.zeros(1)} for q in progs})
    prog = _Prog(npts=1)
    r._run(None, None, {0: prog}, params={0: {"prep": 1}})
    r._write_slot(None, None, 0, prog, "gate", 1, "amp", 4242)
    r._write_slot(None, None, 0, prog, "gate", 1, "amp", 99)
    assert len(r.setups) == 1 and len(r.reruns) == 1
    assert r.reruns[0][1] == {0: {"prep": 1}}
    assert r.slot(0, "gate", 1, "amp") == 99            # the LATEST write
    assert r.slot(0, "gate", 0, "amp") is None          # never written
    assert r.sources == ["/* stub */"]


def test_probe_params_must_be_keyed_by_core():
    """`rq.rerun` does `params.get(core, {})`, so a flat `{"n": 1}` matches no core, writes
    nothing, and the run silently uses compile-time defaults — which reads as a physics result.
    Fail loud instead."""
    from tests.probe import _check_params

    _check_params(None)
    _check_params({})
    _check_params({0: {"n": 1}, 1: {"n": 2}})
    with pytest.raises(AssertionError, match="keyed by core"):
        _check_params({"n": 1})
