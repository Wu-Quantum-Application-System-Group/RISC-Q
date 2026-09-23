"""M0 acceptance: hand-written C over the full run() protocol on the verilator co-sim —
boot, param write, named-array readback, DONE status, and the mid-run host->core input path."""

from pathlib import Path

import pytest

from riscq import run as rq
from riscq.build import Program, compile_c
from riscq.map import SocMap, SocParams
from riscq.run import STATUS_DONE, STATUS_DONE_MASK, STATUS_RUNNING

pytestmark = pytest.mark.cosim

FILL_SRC = """
#include "riscq.h"
volatile int32_t seed = 1;      /* host-written param */
volatile int32_t t_boot = 0;    /* now() at boot */
volatile int32_t out[8];        /* host-read result array */
int main(void) {
    t_boot = (int32_t)now();
    for (int i = 0; i < 8; i++) out[i] = seed * 3 + i;
    return 42;
}
"""

KNOB_SRC = """
#include "riscq.h"
volatile int32_t knob = 0;      /* host-written MID-RUN */
volatile int32_t out[1];
int main(void) {
    while (knob == 0) { }
    out[0] = knob;
    return 0;
}
"""


def test_boot_run_readback(cosim):
    """(1) load + params + release + poll DONE + read array by name; time nonzero."""
    drv, m = cosim
    prog = Program.from_image(compile_c(FILL_SRC, m))
    res = rq.run(drv, m, {0: prog}, params={0: {"seed": 7}}, results=["out"])
    assert list(res[0]["out"]) == [7 * 3 + i for i in range(8)]
    # RAM persists across the final reset re-assert: status/globals still readable
    status = rq.read_var(drv, m, 0, prog, "__rq_status")
    assert status == (STATUS_DONE | 42), f"__rq_status = {status:#010x}"
    t_boot = rq.read_var(drv, m, 0, prog, "t_boot")
    assert t_boot != 0, "core read now() == 0 — time base not running at boot"


def test_midrun_rewrite_steers_program(cosim):
    """(2) a symbol-addressed global rewritten while the core RUNS changes its behavior."""
    drv, m = cosim
    prog = Program.from_image(compile_c(KNOB_SRC, m))

    rq.reset(drv, m, on=True)
    rq.load_program(drv, m, 0, prog.image)
    rq.check_magic(drv, m, 0, prog)
    rq.park_core(drv, m, 1)          # core 1 boots too — keep it in a benign self-loop
    rq.reset(drv, m, on=False)

    status_addr = m.to_host_addr(0, prog.var_addr("__rq_status"))
    status = drv.sim.poll_word(status_addr, not_equal=0, timeout_cycles=500_000)
    assert status == STATUS_RUNNING, f"expected RUNNING, got {status:#010x}"

    drv.sim.advance(2_000)                       # let it really sit in the knob spin loop
    assert drv.read32(status_addr) == STATUS_RUNNING

    rq.write_var(drv, m, 0, prog, "knob", 42)    # the mid-run host->core input path
    done = rq.poll_done(drv, m, [0], timeout=1_000_000)
    assert done & 1, f"core 0 DONE bit not set in {done:#010x}"
    status = rq.read_var(drv, m, 0, prog, "__rq_status")
    assert status & STATUS_DONE_MASK == STATUS_DONE
    assert rq.read_var(drv, m, 0, prog, "out") == 42
    rq.reset(drv, m, on=True)


def test_run_shape_two_cores(cosim):
    """(3) one image on both cores with different params -> {core: {name: array}}."""
    drv, m = cosim
    prog = Program.from_image(compile_c(FILL_SRC, m))
    res = rq.run(drv, m, {0: prog, 1: prog},
                 params={0: {"seed": 5}, 1: {"seed": 9}}, results=["out"])
    assert set(res.keys()) == {0, 1}
    assert set(res[0].keys()) == {"out"}
    assert list(res[0]["out"]) == [5 * 3 + i for i in range(8)]
    assert list(res[1]["out"]) == [9 * 3 + i for i in range(8)]
    assert res[0]["out"].dtype.str == "<i4"
