"""B0 reproducer (spec 08 §3): the next-run drive drop.

A long multi-pulse batch schedules drive pulses far in the future and returns before batch time
reaches them. The drive channel's per-parameter TimedQueues (amp/phase/env/dur, depth queueDepth)
live in the DSP clock domain and are NOT flushed by riscqReset, and batch time restarts at ~0 each
run — so those stale entries (large startTimes from the previous run) sit at the FIFO head into the
NEXT run, not yet due, and the fresh pulse queued behind them never pops in its window. The DAC goes
silent where the fresh drive should be.

Discriminator (pins the mechanism, not just "a prior multi-pulse run"): a DRAINED burst — one that
waits past its last pulse so the queues empty — leaves the next run clean; only the UN-drained burst
drops it. Red before the B0 fix; green after.
"""

import numpy as np
import pytest

from riscq import run as rq
from riscq.lang import Array, ParamTable, compile_kernel, kernel
from riscq.map import LEAD
from riscq.pulses import Pulse, envelopes, golden

pytestmark = pytest.mark.cosim

_ENV = envelopes.square(64)   # 16-batch square gate pulse
_FREQ_HZ = 50e6


def _gate_table():
    return ParamTable(0, _FREQ_HZ, {"p": Pulse(_ENV, freq_hz=_FREQ_HZ, amp=0.5)})


@kernel
def k_burst(gate: ParamTable, out: Array, n: int, step: int, span: int, drain: int):
    """A long multi-pulse batch: play n gate pulses on a far-future grid. With drain=0 it returns
    immediately (the far pulses are still queued — the state that carries into the next run); with
    drain=1 it waits past the last pulse so the queues empty (the control)."""
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD + span  # noqa: F821
    for i in range(n):
        play(gate, gate["p"], t)  # noqa: F821
        t = t + step
    if drain == 1:
        wait_until(t + gate["p"].dur + 8)  # noqa: F821
    out[0] = n


@kernel
def k_one(gate: ParamTable, out: Array):
    """A fresh short program: play ONE gate pulse at now()+LEAD, record the fire time in out[0]."""
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    out[0] = t
    play(gate, gate["p"], t)  # noqa: F821
    wait_until(t + gate["p"].dur + 8)  # noqa: F821


# Sized, not generous (01 §3.3): `dac_capture_get` blocks until the armed window is full. The fresh
# pulse lands at capture index ~480 (boot + preamble + LEAD, measured), is 16 batches long, and the
# program exits straight after — so 1000 covers it with ~500 batches of margin, and 3 captures.
_N_CAPTURE = 1000


def _burst(drv, m, drain: int, n=6, step=300, span=2000):
    prog = compile_kernel(k_burst, m, tables=dict(gate=_gate_table()),
                          out=Array(1), n=n, step=step, span=span, drain=drain)
    rq.run(drv, m, {0: prog}, timeout=2_000_000)


def _capture_one(drv, m):
    """Run k_one on core 0 with a DAC capture; return (t_fire, t0, samples)."""
    prog = compile_kernel(k_one, m, tables=dict(gate=_gate_table()), out=Array(1))
    rq.reset(drv, m, on=True)
    rq.load_program(drv, m, 0, prog.image)
    rq.check_magic(drv, m, 0, prog)
    for core in range(1, m.params.qubit_num):
        rq.park_core(drv, m, core)
    rq.load_envelopes(drv, m, 0, prog)
    rq.load_tables(drv, m, 0, prog)
    handle = drv.sim.dac_capture_arm(m.gate_dac(0), _N_CAPTURE)
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0], timeout=1_000_000)
    t_fire = int(rq.read_array(drv, m, 0, prog, "out")[0])
    rq.reset(drv, m, on=True)
    t0, cap = drv.sim.dac_capture_get(handle)
    return t_fire, t0, cap


def _window_present(m, t_fire, t0, cap):
    """True iff the fresh pulse's bit-exact golden window is present at [t_fire, t_fire+dur)."""
    p = _gate_table().pulses["p"]
    lines = p.packed_lines(m, 0)
    dur = len(lines)
    idx = t_fire - t0
    if idx < 0 or idx + dur > len(cap):
        return False
    gold = golden.pulse_window(lines, p.amp_code(), p.freq_code(m), p.phase_code(), t_fire, dur)
    return bool(cap[idx:idx + dur].any()) and np.array_equal(cap[idx:idx + dur], gold)


@pytest.mark.batch_cap(44_000)   # 42.3k measured in the full tier after the put up-link (a settled result is 3 puts)
def test_drive_survives_prior_multipulse_batch(cosim):
    """FLOOR: ~40 k = FIVE core-0 program images (3 × `k_one` + 2 × `k_burst`, ~6.6 k each over AXI)
    plus the drained burst's own ~3.6 k wait. The claim is about queue state carried ACROSS run
    boundaries, and the discriminator needs three stages (fresh / after a drained burst / after an
    un-drained one) with a different program on core 0 between each — so every image load is part
    of the claim, and only the DAC captures (3 × 1000 batches) were free to size."""
    drv, m = cosim

    # 1. baseline: a fresh run with no prior batch plays its drive (sanity).
    t_fire, t0, cap = _capture_one(drv, m)
    assert _window_present(m, t_fire, t0, cap), "baseline fresh drive missing — harness broken"

    # 2. control: after a DRAINED multi-pulse batch (queues emptied), the next run is clean.
    _burst(drv, m, drain=1)
    t_fire, t0, cap = _capture_one(drv, m)
    assert _window_present(m, t_fire, t0, cap), "drained burst wrongly dropped the next drive"

    # 3. the bug: after an UN-drained long multi-pulse batch, the next run's drive is dropped.
    _burst(drv, m, drain=0)
    t_fire, t0, cap = _capture_one(drv, m)
    assert _window_present(m, t_fire, t0, cap), \
        "next-run drive DROPPED after an un-drained multi-pulse batch (B0 bug)"
