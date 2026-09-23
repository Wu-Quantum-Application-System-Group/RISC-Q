"""White Rabbit software tests (specs/white-rabbit/07 §6).

Three layers:
  - pure math: the offset/fit functions on synthetic vectors (wrap-free 64-bit scale,
    Scala-rounding semantics);
  - golden parity: WrNodeCtl/WrLink replayed on the WrTwoNodeSim register trace
    (data/wr_two_node_trace.jsonl) must issue the identical op sequence and land the
    identical decision (mean/corr). Regenerate the trace with
    `mill runMain riscq.wr.sim.WrTwoNodeSim` and copy simWorkspace/wr_two_node_trace.jsonl here;
  - cosim smoke (--cosim): single-node self-loopback on the sim-wr build over the real
    AXI path — bring-up, one self-exchange per direction pair, marker arm/fire.
"""

import json
from collections import deque
from pathlib import Path

import pytest

from riscq import wr
from riscq.wr import SYNC, WrNodeCtl, WrLink, WrRegs

SW_ROOT = Path(__file__).resolve().parents[1]
CONFIGS = SW_ROOT / "configs"
TRACE = Path(__file__).parent / "data" / "wr_two_node_trace.jsonl"


# ── pure math ────────────────────────────────────────────────────────────────────────────────

def test_fit_recovers_line():
    pts = [(float(x), 3.25 - 1.5e-6 * x) for x in range(0, 4000, 37)]
    mean, slope = wr.fit(pts)
    xs = [p[0] for p in pts]
    mx = sum(xs) / len(xs)
    assert abs(mean - (3.25 - 1.5e-6 * mx)) < 1e-9
    assert abs(slope - (-1.5e-6)) < 1e-12


def test_offset_identity_at_64bit_scale():
    # offset via delay_MM (spec 07 §3) equals the symmetric-link identity (README §3), exactly,
    # at real refTime magnitudes (> 2^32) — wrap-free integer arithmetic end to end.
    t1 = (1 << 40) + 12345
    t2 = 987654321098
    t3 = t2 + 61
    t4 = t1 + 173
    ex = wr.Exchange(t1, t2, t3, t4)
    assert ex.delay_mm == (t4 - t1) - (t3 - t2) == 112
    assert ex.offset() == ((t1 - t2) + (t4 - t3)) / 2
    assert ex.offset(eps=3.0) == ex.offset() + 1.5


def test_round_half_up_matches_scala():
    # Scala Math.round: half toward +inf, both signs (Python round() is banker's rounding).
    for x, want in [(0.5, 1), (1.5, 2), (-0.5, 0), (-1.5, -1), (2.4, 2), (-2.6, -3)]:
        assert wr.round_half_up(x) == want


# ── golden parity on the W3 trace ────────────────────────────────────────────────────────────

class TraceDriver:
    """Replays one node's recorded register ops; asserts the Python client issues the identical
    sequence (spec 07 §6 golden parity — cheap lock-step, no re-simulation)."""

    def __init__(self, name: str, ops: deque):
        self.name = name
        self.ops = ops

    def read32(self, addr: int) -> int:
        op, a, v = self.ops.popleft()
        assert (op, a) == ("r", addr), \
            f"{self.name}: golden did {op}@{a:#x}, client read {addr:#x}"
        return v

    def write32(self, addr: int, value: int) -> None:
        op, a, v = self.ops.popleft()
        assert (op, a, v) == ("w", addr, value & 0xFFFFFFFF), \
            f"{self.name}: golden did {op}@{a:#x}={v:#x}, client wrote {addr:#x}={value:#x}"

    def read_block(self, addr, nbytes):
        raise NotImplementedError

    def write_block(self, addr, data):
        raise NotImplementedError


def _load_trace():
    ops = {"A": deque(), "B": deque()}
    exchanges, decision = [], None
    for line in TRACE.read_text().splitlines():
        rec = json.loads(line)
        if "n" in rec:
            ops[rec["n"]].append((rec["op"], rec["a"], rec["v"]))
        elif "exchange" in rec:
            exchanges.append(rec)
        elif "decision" in rec:
            decision = rec["decision"]
    return ops, exchanges, decision


def test_golden_parity():
    ops, exchanges, decision = _load_trace()
    # the marker target the Scala host armed (replayed through verify() below)
    a_ops = list(ops["A"])
    lo = next(v for op, a, v in a_ops if op == "w" and a == WrRegs.MARKER_LO)
    hi = next(v for op, a, v in a_ops if op == "w" and a == WrRegs.MARKER_HI)
    target = (hi << 32) | lo

    link = WrLink(WrNodeCtl(TraceDriver("A", ops["A"]), 0),
                  WrNodeCtl(TraceDriver("B", ops["B"]), 0))
    link.link_up()
    pts = [link.exchange() for _ in exchanges]
    for ex, rec in zip(pts, exchanges):
        assert (ex.t1, ex.t2, ex.t3, ex.t4) == (rec["t1"], rec["t2"], rec["t3"], rec["t4"])
        assert ex.offset() == rec["offset"]

    mean, slope = wr.fit([(ex.t_mid, ex.offset()) for ex in pts])
    assert abs(mean - decision["mean"]) < 1e-6
    assert abs(slope - decision["slope"]) < 1e-9   # x-origin differs; slope is shift-invariant
    assert wr.round_half_up(mean) == decision["corr"]   # the written correction, exactly

    link.verify(target, width=50)
    assert not ops["A"] and not ops["B"], "trace not fully consumed — op-sequence drift"


# ── cosim smoke (single-node self-loopback over the real AXI path) ───────────────────────────

@pytest.fixture(scope="module")
def wr_cosim(request):
    """A running verilator co-sim of the sim-wr build: (CosimDriver, SocMap)."""
    if not request.config.getoption("--cosim"):
        pytest.skip("needs --cosim")
    from riscq.map import SocMap, SocParams
    from riscq.sim import server

    drv = server.start(CONFIGS / "sim-wr.json", SW_ROOT / "build" / "sim-wr")
    m = SocMap(SocParams.from_json(drv.sim.get_params()))
    yield drv, m
    server.stop(drv)


@pytest.mark.cosim
def test_wr_cosim_smoke(wr_cosim):
    drv, m = wr_cosim
    ctl = WrNodeCtl(drv, m.wr_base, m.host_ctrl)

    # power-up resetAll, then bring-up through the bench loopback
    assert drv.read32(m.wr_base + WrRegs.CTRL) & 1
    ctl.bringup()
    assert ctl.status() & 0x7 == 0x7

    # two self-exchanges: both TSUs capture our own frame, body round-trips, latency constant
    ctl.send_marker(SYNC, 0)
    t1 = ctl.txts()
    t2 = ctl.rxts()
    assert ctl.pop_frame() == [SYNC, 0]
    d1 = t2 - t1
    assert 0 < d1 < 400

    ctl.send_marker(SYNC, 1)
    t1b = ctl.txts()
    t2b = ctl.rxts()
    assert ctl.pop_frame() == [SYNC, 1]
    assert abs((t2b - t1b) - d1) <= 2   # constant link latency (±1-cycle capture dither)

    # marker: arm ahead of now, confirm armed, run past the target -> fired (one-shot, not
    # missed) — and the spare-DAC route (wr_marker_dac = 4) carries it as a full-scale step
    target = t2b + 20000
    handle = drv.sim.dac_capture_arm(4, 120, start_batch=target - 20)
    ctl.set_marker(target, width=50)
    armed, missed = ctl.marker_status()
    assert armed and not missed
    _, samples = drv.sim.dac_capture_get(handle)   # runs the sim past the marker
    full = samples[:, 0] == 0x7FFF
    assert full.sum() in range(48, 53)             # one width-50 step
    assert not full[:5].any()                      # clean zeros before the target
    assert 14 <= full.argmax() <= 26               # step lands at ~target (loose: stamp conventions)
    armed, missed = ctl.marker_status()
    assert not armed and not missed

    # clean loopback: error counters zero and stable (read-twice torn discipline)
    counters = [drv.read32(m.wr_base + WrRegs.COUNTERS + 4 * i) for i in range(5)]
    drv.sim.advance(2000)
    again = [drv.read32(m.wr_base + WrRegs.COUNTERS + 4 * i) for i in range(5)]
    assert counters == again == [0, 0, 0, 0, 0]
