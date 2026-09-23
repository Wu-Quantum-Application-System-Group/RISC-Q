"""White Rabbit Stage-1 measurement & correction (specs/white-rabbit/07).

Everything goes through the 4-method Driver seam, so the same code runs against the cosim
backend, locally on the board ARM, or over RemoteDriver. Both boards run the same bitstream;
software assigns roles. Stage 1 has no servos, no syntonization, no enhancement: bring up the
link, run timestamped exchanges, average, write the slave's timeOffset, verify.

Register offsets mirror WrNode.scala (spec 06 §2). The register-op sequence of every method
deliberately mirrors the WrTwoNodeSim Scala host — its recorded register trace is the replayable
golden (tests/test_wr.py parity, spec 07 §6).
"""

from __future__ import annotations

import math
import random
import time
from dataclasses import dataclass

from riscq.map import SocMap


class WrRegs:
    """WR node register offsets, relative to SocMap.wr_base (WrNode.scala / spec 06 §2)."""

    CTRL = 0x000           # [0] resetAll [1] resetRxDatapath [2] calMode [3] role (scratch)
    #                        [4] pmaLoopback (near-end PMA loopback, the W6 self-test)
    CTRL_RESET_ALL = 1 << 0
    CTRL_RESET_RX = 1 << 1
    CTRL_CAL_MODE = 1 << 2
    CTRL_ROLE = 1 << 3
    CTRL_PMA_LOOPBACK = 1 << 4
    STATUS = 0x004         # [0] ready [1] aligned [2] synced [15:8] diceCount
    TXTS_LO = 0x010        # ts[31:0]; the read latches ts[63:32] into TXTS_HI
    TXTS_HI = 0x014
    TXTS_CTRL = 0x018      # r: [0] valid [1] overrun [7:4] seq; w: ack
    RXTS_LO = 0x020
    RXTS_HI = 0x024
    RXTS_CTRL = 0x028
    MARKER_LO = 0x030      # markerTime (syncTime compare)
    MARKER_HI = 0x034
    MARKER_CTRL = 0x038    # w: [7:0] width + arm strobe; r: [0] armed [1] missed
    MARKER_PERIOD = 0x03C  # re-arm period (0 = one-shot)
    TXF_DATA = 0x040       # w: [7:0] byte [8] last
    TXF_STAT = 0x044
    RXF_DATA = 0x050       # r: [7:0] byte [8] last [16] valid (pop-on-read)
    RXF_STAT = 0x054
    COUNTERS = 0x060       # 5 words: codeErr, dispErr, crcErr, dropped, syncLoss (torn: read twice)


SYNC = 1        # marker-frame types (payload = [type, seq]; spec README D4)
DELAY_REQ = 2

POLL_SPINS = 3000


def round_half_up(x: float) -> int:
    """Scala Math.round semantics (half toward +inf) — Python round() is banker's rounding."""
    return math.floor(x + 0.5)


def fit(points: list[tuple[float, float]]) -> tuple[float, float]:
    """(mean-y at x-bar, slope) least squares — arithmetic mirrors WrTwoNodeSim.fit."""
    n = len(points)
    my = sum(p[1] for p in points) / n
    if n < 2:
        return my, 0.0
    mx = sum(p[0] for p in points) / n
    b = (sum((x - mx) * (y - my) for x, y in points)
         / sum((x - mx) * (x - mx) for x, _ in points))
    return my, b


@dataclass(frozen=True)
class Exchange:
    """One two-way exchange: t1/t4 master refTime captures, t2/t3 slave (2 ns cycles)."""

    t1: int
    t2: int
    t3: int
    t4: int

    @property
    def delay_mm(self) -> int:
        """Round-trip minus turnaround (paper eq 3.9, all-coarse)."""
        return (self.t4 - self.t1) - (self.t3 - self.t2)

    def offset(self, eps: float = 0.0) -> float:
        """offset_MS = refTime_M - refTime_S at this exchange (spec README §3); `eps` is the
        role-swap-calibrated residual asymmetry (spec 07 §5), in cycles."""
        return (self.t1 - self.t2) + self.delay_mm / 2 + eps / 2

    @property
    def t_mid(self) -> float:
        """Exchange midpoint on the master's refTime axis (the fit's x)."""
        return (self.t1 + self.t4) / 2


@dataclass(frozen=True)
class Fit:
    """measure() result: the offset at the measurement moment + link-health statistics."""

    offset: float          # offset_MS at x-bar (cycles) — the deliverable
    slope: float           # drift rate, cycles per master cycle — must be ~0 on a shared ref
    delay_mm_mean: float
    delay_mm_std: float
    points: tuple          # the Exchange records


class WrNodeCtl:
    """One board's WR node over a Driver. `wr_base` is SocMap.wr_base; `host_ctrl` (SocMap.
    host_ctrl) is needed only for the timeOffset write. The hostCtrl timeOffset registers are
    write-only, so the current value is a client-side shadow (signed, unbounded; wrapped to
    64 bits at write time) — the host wrote every value it ever holds (spec 06 §5)."""

    def __init__(self, drv, wr_base: int, host_ctrl: int | None = None):
        self.drv = drv
        self.base = wr_base
        self.host_ctrl = host_ctrl
        self._time_offset = 0
        self._tx_seq = 0   # expected TSU capture counters (4-bit, one per TSU)
        self._rx_seq = 0

    def _r(self, off: int) -> int:
        return self.drv.read32(self.base + off) & 0xFFFFFFFF

    def _w(self, off: int, v: int) -> None:
        self.drv.write32(self.base + off, v & 0xFFFFFFFF)

    # ── bring-up ──
    def release(self, ctrl: int = 0) -> None:
        """Release resetAll (the dice-throw runs in hardware), optionally with other CTRL bits —
        e.g. CTRL_PMA_LOOPBACK for the W6 self-test, set in the same write so it is stable
        before the sequenced GT resets deassert. On a link, release BOTH nodes before polling
        either — the link couples them."""
        self._w(WrRegs.CTRL, ctrl)

    def await_up(self) -> None:
        spins = 0
        while (self._r(WrRegs.STATUS) & 0x7) != 0x7:
            spins += 1
            if spins >= POLL_SPINS:
                raise TimeoutError("WR link never reached ready+aligned+synced")

    def bringup(self, ctrl: int = 0) -> None:
        """Single-node (self-loopback) bring-up: release, then poll up."""
        self.release(ctrl)
        self.await_up()

    def status(self) -> int:
        return self._r(WrRegs.STATUS)

    # ── frames & timestamps ──
    def send_marker(self, typ: int, seq: int) -> None:
        """Push one [type, seq] marker frame into TXF; its SOF triggers the TX TSU."""
        self._w(WrRegs.TXF_DATA, typ & 0xFF)
        self._w(WrRegs.TXF_DATA, 0x100 | (seq & 0xFF))

    def _take_ts(self, ts_base: int, exp_seq: int) -> int:
        """Poll a TSU until valid, verify seq + no overrun, read the 64-bit ts (the lo read
        latches hi — torn-read safe), ack."""
        spins = 0
        while (self._r(ts_base + 8) & 1) == 0:
            spins += 1
            if spins >= POLL_SPINS:
                raise TimeoutError(f"TSU@{ts_base:#x}: no capture")
        ctrl = self._r(ts_base + 8)
        if ctrl & 2:
            raise RuntimeError(f"TSU@{ts_base:#x}: overrun — one outstanding exchange at a time")
        if (ctrl >> 4) & 0xF != exp_seq & 0xF:
            raise RuntimeError(f"TSU@{ts_base:#x}: seq {(ctrl >> 4) & 0xF} != {exp_seq & 0xF}")
        lo = self._r(ts_base)       # latches hi
        hi = self._r(ts_base + 4)
        self._w(ts_base + 8, 1)     # ack
        return (hi << 32) | lo

    def txts(self) -> int:
        self._tx_seq += 1
        return self._take_ts(WrRegs.TXTS_LO, self._tx_seq)

    def rxts(self) -> int:
        self._rx_seq += 1
        return self._take_ts(WrRegs.RXTS_LO, self._rx_seq)

    def pop_frame(self) -> list[int]:
        """Pop one complete frame body from RXF (poll until the last byte drained)."""
        out: list[int] = []
        spins = 0
        while True:
            w = self._r(WrRegs.RXF_DATA)
            if w & 0x10000:
                out.append(w & 0xFF)
                if w & 0x100:
                    return out
            else:
                spins += 1
                if spins >= POLL_SPINS:
                    raise TimeoutError("RXF starved")

    # ── sync marker ──
    def set_marker(self, sync_time: int, width: int, period: int = 0) -> None:
        """Arm the scope marker at syncTime `sync_time` (width/period in dspClk cycles)."""
        self._w(WrRegs.MARKER_PERIOD, period)
        self._w(WrRegs.MARKER_LO, sync_time & 0xFFFFFFFF)
        self._w(WrRegs.MARKER_HI, (sync_time >> 32) & 0xFFFFFFFF)
        self._w(WrRegs.MARKER_CTRL, width & 0xFF)   # arms

    def marker_status(self) -> tuple[bool, bool]:
        r = self._r(WrRegs.MARKER_CTRL)
        return bool(r & 1), bool(r & 2)   # (armed, missed)

    # ── timeOffset (hostCtrl window; spec 06 §5) ──
    def time_offset(self) -> int:
        return self._time_offset

    def set_time_offset(self, v: int) -> None:
        if self.host_ctrl is None:
            raise RuntimeError("WrNodeCtl needs host_ctrl to write timeOffset")
        w = v & ((1 << 64) - 1)
        self.drv.write32(self.host_ctrl + SocMap.HOST_TIME_OFF_LO, w & 0xFFFFFFFF)
        self.drv.write32(self.host_ctrl + SocMap.HOST_TIME_OFF_HI, (w >> 32) & 0xFFFFFFFF)
        self._time_offset = v


class WrLink:
    """The board pair. `eps` is the stored role-swap calibration for this pair (spec 07 §5)."""

    def __init__(self, master: WrNodeCtl, slave: WrNodeCtl, eps: float = 0.0):
        self.master = master
        self.slave = slave
        self.eps = eps
        self._n = 0

    def link_up(self) -> None:
        """Release both first (the link couples them), then poll both up."""
        self.master.release()
        self.slave.release()
        self.master.await_up()
        self.slave.await_up()

    def exchange(self) -> Exchange:
        """One two-way exchange (spec 07 §2): one outstanding at a time, frames carry only
        [type, seq]; all four timestamps are read over MMIO."""
        k = self._n & 0xFF
        self._n += 1
        m, s = self.master, self.slave
        m.send_marker(SYNC, k)
        t1 = m.txts()
        t2 = s.rxts()
        body = s.pop_frame()
        if body != [SYNC, k]:
            raise RuntimeError(f"SYNC frame body mismatch: {body}")
        s.send_marker(DELAY_REQ, k)
        t3 = s.txts()
        t4 = m.rxts()
        body = m.pop_frame()
        if body != [DELAY_REQ, k]:
            raise RuntimeError(f"DELAY_REQ frame body mismatch: {body}")
        return Exchange(t1, t2, t3, t4)

    def measure(self, n: int = 100, delay_s: float = 0.0, jitter_s: float = 0.0) -> Fit:
        """n exchanges (optionally spaced by a jittered sleep so the capture-phase dither is
        sampled, spec 07 §3) -> least-squares offset(t) fit."""
        pts = []
        for _ in range(n):
            pts.append(self.exchange())
            if delay_s or jitter_s:
                time.sleep(delay_s + random.uniform(0.0, jitter_s))
        offset, slope = fit([(ex.t_mid, ex.offset(self.eps)) for ex in pts])
        delays = [ex.delay_mm for ex in pts]
        dm = sum(delays) / n
        ds = math.sqrt(sum((d - dm) ** 2 for d in delays) / n)
        return Fit(offset=offset, slope=slope, delay_mm_mean=dm, delay_mm_std=ds,
                   points=tuple(pts))

    def sync(self, n: int = 100, delay_s: float = 0.0, jitter_s: float = 0.0) -> Fit:
        """Coarse-correct the slave's timeOffset (spec 07 §4); returns the re-measured residual
        fit (|offset| <= 0.5 cycle expected — the sub-2 ns remainder logged for Stage 2)."""
        f = self.measure(n, delay_s, jitter_s)
        span = f.points[-1].t_mid - f.points[0].t_mid if n > 1 else 0.0
        if abs(f.slope) * span > 0.5:
            raise RuntimeError(
                f"refTime drift too large for a static correction: slope {f.slope:g} "
                f"cycles/cycle over a {span:g}-cycle measurement — check the shared RF reference")
        offset_sync = f.offset + self.master.time_offset() - self.slave.time_offset()
        self.slave.set_time_offset(self.slave.time_offset() + round_half_up(offset_sync))
        return self.measure(n, delay_s, jitter_s)

    def verify(self, at: int, width: int = 50, period: int = 0) -> None:
        """Arm both boards' sync markers at the same syncTime -> scope skew is the verdict."""
        self.master.set_marker(at, width, period)
        self.slave.set_marker(at, width, period)
