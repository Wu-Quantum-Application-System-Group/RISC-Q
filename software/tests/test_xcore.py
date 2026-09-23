"""The put network's kernel surface (specs/cross-core/02 §8.1, R5): `Group` / `Mailbox` bindings fold
`publish` / `remote` / `barrier` / `signal` / `wait_signal` to the riscq.h ops per core (host-pure),
and on the sim-2q co-sim two cores publish, signal, rendezvous and read each other's bit — both
reading the SAME released time."""

import pytest

from riscq import run as rq
from riscq.lang import Array, Group, KernelCompileError, Mailbox, compile_kernel, kernel


@kernel
def k_xcore(core: int, pair: Group, go: Mailbox, other: int, out: Array):
    publish(pair, core == 0)             # core 0 publishes a 1, core 1 a 0
    if core == 0:
        signal(go, 0x77)
    t0 = barrier(pair)                   # the same t0 on both cores
    if core == 1:
        out[2] = wait_signal(go)
    out[0] = t0
    out[1] = remote(pair, other)         # fresh by ordering: the other core's bit
    wait_until(t0 + 200)


def _progs(m):
    pair, go = Group([0, 1], id=0), Mailbox(sender=0, receiver=1, index=0)
    return {q: compile_kernel(k_xcore, m, core=q, pair=pair, go=go, other=1 - q, out=Array(3))
            for q in (0, 1)}


def test_xcore_ops_fold_per_core(socmap):
    p = _progs(socmap)
    c0, c1 = p[0].c_source, p[1].c_source
    assert "publish(0, 0, 1)" in c0 and "publish(0, 1, 0)" in c1        # group 0, my slot, the bit
    assert "barrier(0, 2)" in c0 and "barrier(0, 2)" in c1               # id 0, count = |members|
    assert "remote(0, 1)" in c0 and "remote(0, 0)" in c1                 # the other's slot
    assert "signal(RQ_INBOX_NODE(0, 1), 0, 119)" in c0 and "    signal(" not in c1   # only the sender signals
    assert "wait_signal(0)" in c1 and "wait_signal(" not in c0            # only the receiver waits


def test_xcore_ops_reject_non_members(socmap):
    @kernel
    def k_bad(core: int, g: Group):
        publish(g, 1)

    with pytest.raises(KernelCompileError, match="not a member"):
        compile_kernel(k_bad, socmap, core=1, g=Group([0], id=1))

    @kernel
    def k_bad_signal(core: int, go: Mailbox):
        signal(go, 1)

    with pytest.raises(KernelCompileError, match="the sender"):
        compile_kernel(k_bad_signal, socmap, core=1, go=Mailbox(sender=0, receiver=1))


@pytest.mark.cosim
def test_two_cores_publish_barrier_remote_signal(cosim):
    drv, m = cosim
    progs = _progs(m)
    rq.setup(drv, m, progs)
    for q in progs:
        rq.write_params(drv, m, q, progs[q], {})
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0, 1], timeout=2_000_000)
    rq.reset(drv, m, on=True)
    out0 = [int(v) & 0xFFFFFFFF for v in rq.read_array(drv, m, 0, progs[0], "out")]
    out1 = [int(v) & 0xFFFFFFFF for v in rq.read_array(drv, m, 1, progs[1], "out")]
    assert out0[0] == out1[0] != 0, f"released time differs: core0 {out0[0]} core1 {out1[0]}"
    assert out0[1] == 0 and out1[1] == 1, f"remote bits: core0 saw {out0[1]}, core1 saw {out1[1]}"
    assert out1[2] == 0x77, f"core 1's signal: {out1[2]:#x}"
