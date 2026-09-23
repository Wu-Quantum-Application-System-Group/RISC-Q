"""B3 acceptance (spec 08 §4): the run-layer batch protocol — `setup` loads once, `rerun` re-runs
an already-loaded image with fresh params + input arrays and no reload. A pulse-free echo kernel
exercises the whole path (params + a host input Array + an output Array) without any DSP, so this
validates the protocol, not the physics. The key invariant is that a `rerun` costs O(1) driver ops:
the count is independent of the batch size (block writes/reads don't scale the op count)."""

import pytest

from riscq import run as rq
from riscq.lang import Array, compile_kernel, kernel

pytestmark = pytest.mark.cosim


class CountingDriver:
    """Wraps a Driver, tallying the 5 seam ops; forwards `.sim`/`.remote` unchanged so poll_done
    still amortizes server-side (spec 08 B3). A host-window array read counts as ONE op, like the
    block read it replaces (specs/software/22 §2.6)."""

    def __init__(self, drv):
        self._drv = drv
        self.ops = 0

    def read32(self, addr):
        self.ops += 1
        return self._drv.read32(addr)

    def write32(self, addr, value):
        self.ops += 1
        return self._drv.write32(addr, value)

    def read_block(self, addr, nbytes):
        self.ops += 1
        return self._drv.read_block(addr, nbytes)

    def write_block(self, addr, data):
        self.ops += 1
        return self._drv.write_block(addr, data)

    def read_host(self, offset, nbytes):
        self.ops += 1
        return self._drv.read_host(offset, nbytes)

    def __getattr__(self, name):
        return getattr(self._drv, name)


@kernel
def k_echo(offset: int, xs: Array, out: Array, n: int):
    """out[i] = xs[i] + offset — a pulse-free batch: a host input array `xs`, a param `offset`, and
    an output array `out`. `+` (not `*`) avoids a __mulsi3 libgcc call on with_mul=false builds."""
    for i in range(n):
        out[i] = xs[i] + offset


def test_run_still_works(cosim):
    """Sanity: the refactored one-shot `run` (= setup + one rerun) still boots and returns."""
    drv, m = cosim
    prog = compile_kernel(k_echo, m, xs=Array(4, input=True), out=Array(4))
    out = rq.run(drv, m, {0: prog}, params={0: {"offset": 10, "n": 4}},
                 arrays={0: {"xs": [1, 2, 3, 4]}})[0]["out"]
    assert list(out) == [11, 12, 13, 14], f"one-shot run wrong: {list(out)}"


def test_rerun_reuses_image(cosim):
    """`setup` once, then two `rerun`s with different params + input arrays and NO reload: both are
    correct — the image stays loaded and the .bss output re-zeroes across reruns."""
    drv, m = cosim
    prog = compile_kernel(k_echo, m, xs=Array(4, input=True), out=Array(4))
    rq.setup(drv, m, {0: prog})
    r1 = rq.rerun(drv, m, {0: prog}, params={0: {"offset": 100, "n": 4}},
                  arrays={0: {"xs": [5, 6, 7, 8]}})[0]["out"]
    assert list(r1) == [105, 106, 107, 108], f"first rerun wrong: {list(r1)}"
    r2 = rq.rerun(drv, m, {0: prog}, params={0: {"offset": 1, "n": 4}},
                  arrays={0: {"xs": [9, 9, 9, 9]}})[0]["out"]
    assert list(r2) == [10, 10, 10, 10], f"second rerun wrong (image not reused?): {list(r2)}"


def test_rerun_op_budget(cosim):
    """One `rerun` after a `setup` costs only a handful of seam ops (the setup/load cost is not the
    batch cost, so it runs on the raw driver)."""
    drv, m = cosim
    prog = compile_kernel(k_echo, m, xs=Array(4, input=True), out=Array(4))
    rq.setup(drv, m, {0: prog})
    cd = CountingDriver(drv)
    cd.ops = 0
    rq.rerun(cd, m, {0: prog}, params={0: {"offset": 10, "n": 4}},
             arrays={0: {"xs": [1, 2, 3, 4]}})
    print(f"\n[budget] one rerun = {cd.ops} seam ops")
    assert cd.ops <= 12, f"rerun cost {cd.ops} seam ops (expected ~9)"


@pytest.mark.batch_cap(22_000)
def test_rerun_op_budget_size_independent(cosim):
    """THE invariant: a `rerun`'s op count does not scale with the batch size — block writes/reads
    move O(n) bytes in O(1) seam ops. Same loaded image, a small then a large batch, equal counts.

    FLOOR: ~20 k = one `setup` of the 64-word image plus two `rerun`s that each move a 64-word input
    block in and a 64-word output block out (a co-sim AXI word is ~22 dspClk cycles, so the blocks
    ARE the cost). Running two batch sizes is the whole claim, and the large one has to be large."""
    drv, m = cosim
    N = 64
    prog = compile_kernel(k_echo, m, xs=Array(N, input=True), out=Array(N))
    rq.setup(drv, m, {0: prog})
    cd = CountingDriver(drv)

    cd.ops = 0
    small = rq.rerun(cd, m, {0: prog}, params={0: {"offset": 0, "n": 4}},
                     arrays={0: {"xs": [1, 2, 3, 4]}})[0]["out"]
    small_ops = cd.ops
    assert list(small[:4]) == [1, 2, 3, 4], f"small rerun wrong: {list(small[:4])}"

    cd.ops = 0
    large = rq.rerun(cd, m, {0: prog}, params={0: {"offset": 0, "n": N}},
                     arrays={0: {"xs": list(range(N))}})[0]["out"]
    large_ops = cd.ops
    assert list(large) == list(range(N)), "large rerun wrong"

    print(f"\n[size-indep] n=4 -> {small_ops} ops, n={N} -> {large_ops} ops")
    assert small_ops == large_ops, \
        f"op count scales with batch size: n=4 -> {small_ops}, n={N} -> {large_ops}"
