"""Co-sim gate of universal-control/01 P5 on the sim-dio build: a kernel schedules a TTL train on
the timed-DIO bank through the generic pulse ops (a DioTable), the board port shows the edges at
the exact scheduled batches, and the kernel's halting `pop_event` returns the input edge the host
drove — with the batch time it happened at and its sequence number."""

import numpy as np
import pytest

from riscq import run as rq
from riscq.lang import Array, DioTable, ParamTable, compile_kernel, kernel
from riscq.map import DIO_PIPE

pytestmark = pytest.mark.cosim


@kernel
def k_ttl(ttl: ParamTable, out: Array, lead: int):
    init_pulse_params(ttl.pulses)
    t = now() + lead
    out[3] = t
    play(ttl, ttl["on"], t)          # line 0 up at t, down at t+10, up at t+20, down at t+30
    fire(ttl, ttl["off"])
    fire(ttl, ttl["on"])
    fire(ttl, ttl["off"])
    w = pop_event(ttl.sink)          # HALTS until the host drives an input edge
    out[0] = w
    out[1] = event_time(ttl.sink)
    out[2] = event_seq(ttl.sink)
    out[4] = event_count(ttl.sink)
    wait_until(t + 40)


def test_ttl_train_and_input_edge_event(cosim_dio):
    drv, m = cosim_dio
    ttl = m.channel_named("ttl", 0)
    table = DioTable(ttl, {"on": (0x0001, 0x0001, 10), "off": (0x0001, 0x0000, 10)})
    prog = compile_kernel(k_ttl, m, tables={"ttl": table}, out=Array(5), lead=m.LEAD)
    rq.setup(drv, m, {0: prog})
    rq.write_params(drv, m, 0, prog, {})
    h = drv.sim.dio_capture_arm("q0_ttl", 1200)
    rq.reset(drv, m, on=False)
    drv.sim.advance(700)                     # boot + the train (the kernel is now halted in pop_event)
    t_before = drv.sim.batch_time()
    drv.sim.dio_set("q0_ttl", 0x0003)        # the input edge: lines 0 and 1 rise
    t_after = drv.sim.batch_time()
    rq.poll_done(drv, m, [0], timeout=2_000_000)
    rq.reset(drv, m, on=True)
    out = rq.read_array(drv, m, 0, prog, "out")
    word, ev_time, seq, t_sched, count = (int(v) & 0xFFFFFFFF for v in out)

    # the event: {changed[15:0], levels[15:0]}, stamped inside the host's set window, first in line
    assert word == (0x0003 << 16) | 0x0003, f"event data {word:#x}"
    assert t_before - 4 <= ev_time <= t_after + 4, f"event time {ev_time} outside [{t_before}, {t_after}]"
    assert seq == 0 and count == 0

    # the train on the board port: rises at t_sched, toggles every 10 batches, exactly
    t0, levels = drv.sim.dio_capture_get(h)
    i = t_sched - t0
    assert 0 <= i and i + 40 <= len(levels), f"train [{t_sched}, +40) outside capture [{t0}, {t0 + len(levels)})"
    edges = [(j + 1, int(levels[j + 1])) for j in range(len(levels) - 1) if levels[j] != levels[j + 1]]
    expect = [(i, 1), (i + 10, 0), (i + 20, 1), (i + 30, 0)]
    assert edges == expect, f"DIO edges {edges} != {expect} (DIO_PIPE={DIO_PIPE})"
