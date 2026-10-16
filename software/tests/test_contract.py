"""Contract tests: every SocMap window exercised over real AXI on the verilator co-sim,
parametrized straight from SocMap.entries() so a wrong formula fails the test rather than
agreeing with itself."""

from pathlib import Path

import pytest

from riscq import run as rq
from riscq.build import Program, compile_c
from riscq.map import SocMap, SocParams

pytestmark = pytest.mark.cosim

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
LOCAL_MAP = SocMap(SocParams.load(CONFIGS / "sim-2q.json"))
ENTRIES = LOCAL_MAP.entries()


def test_server_params_match_local_config(cosim):
    _, m = cosim
    assert m.params == LOCAL_MAP.params
    assert m.entries() == ENTRIES


def _rw_words(drv, addr: int, seed: int, nwords: int = 4):
    """Write a pattern block, read it back exactly."""
    pattern = bytes(b"".join(((seed + 0x01010101 * i) & 0xFFFFFFFF).to_bytes(4, "little")
                             for i in range(nwords)))
    drv.write_block(addr, pattern)
    got = drv.read_block(addr, len(pattern))
    assert got == pattern, f"mismatch at {addr:#x}: {got.hex()} != {pattern.hex()}"


@pytest.mark.parametrize("entry", [e for e in ENTRIES if e.kind == "ram_rw"],
                         ids=lambda e: e.name)
def test_core_ram_rw(cosim, entry):
    drv, m = cosim
    rq.reset(drv, m, on=True)   # cores held; the RAM host port is independent of core reset
    _rw_words(drv, entry.host_addr, 0xA5000000)                          # first words
    _rw_words(drv, entry.host_addr + entry.nbytes - 16, 0x5A000000)      # last words


def _w_words(drv, addr: int, seed: int, nwords: int = 4):
    """Write a pattern block to a write-only window (no readback)."""
    pattern = bytes(b"".join(((seed + 0x01010101 * i) & 0xFFFFFFFF).to_bytes(4, "little")
                             for i in range(nwords)))
    drv.write_block(addr, pattern)


@pytest.mark.parametrize("entry", [e for e in ENTRIES if e.kind in ("env_gate", "env_ro")],
                         ids=lambda e: e.name)
def test_envelope_writable(cosim, entry):
    """Envelope RAMs are host WRITE-ONLY (BramWriteFiber — no read port; the generator reads them
    internally). Exercise that the window decodes and accepts AXI writes across its full range; the
    write→DAC content path is checked bit-exact in test_pulse.test_dac_window_bit_exact_*. A following
    readable-window check (test_server_params_match_local_config et al.) confirms the sim survived."""
    drv, m = cosim
    nwords = 8 if entry.kind == "env_gate" else 4   # 2 full 128-bit gate lines / 4 ro words
    _w_words(drv, entry.host_addr, 0xC3000000, nwords)
    _w_words(drv, entry.host_addr + entry.nbytes - 4 * nwords, 0x3C000000, nwords)


@pytest.mark.parametrize("entry", [e for e in ENTRIES if e.kind == "robs_ro"],
                         ids=lambda e: e.name)
def test_robs_readable(cosim, entry):
    drv, _ = cosim
    a = drv.read_block(entry.host_addr, 16)
    b = drv.read_block(entry.host_addr, 16)
    assert len(a) == 16
    assert a == b, "robs read must be deterministic while no capture is running"


TICK_SRC = """
#include "riscq.h"
volatile int32_t tick;
int main(void) {
    for (;;) tick = (int32_t)now();
}
"""


def test_host_ctrl_effect(cosim):
    """ctrl_wo window proven by effect: releasing riscqReset with a now()-spinning program
    loaded makes batch time advance, and a written timeOffset shows up in now()."""
    drv, m = cosim
    prog = Program.from_image(compile_c(TICK_SRC, m))
    offset = 0x100000

    rq.reset(drv, m, on=True)
    rq.load_program(drv, m, 0, prog.image)
    rq.check_magic(drv, m, 0, prog)
    for core in range(1, m.params.qubit_num):
        rq.park_core(drv, m, core)   # earlier RAM tests left patterns at core 1's reset vector
    rq.set_time_offset(drv, m, offset)
    rq.reset(drv, m, on=False)

    addr = m.to_host_addr(0, prog.var_addr("tick"))
    t1 = drv.sim.poll_word(addr, not_equal=0, timeout_cycles=500_000)
    assert t1 != 0, "core never wrote tick — reset release had no effect"
    assert t1 >= offset, f"tick {t1:#x} below timeOffset {offset:#x} — timeOffset write lost"
    drv.sim.advance(1000)
    t2 = drv.read32(addr)
    assert t2 != t1, "batch time is not advancing"

    rq.reset(drv, m, on=True)
    rq.set_time_offset(drv, m, 0)
