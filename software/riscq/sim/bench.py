"""The cocotb co-sim bench: clocks/reset on PulseTableSoc, a hand-rolled single-beat AXI4
master BFM, and a Pyro5 daemon (in a python thread) whose requests a cocotb coroutine services
as AXI transactions. Between requests the sim free-runs the clock in bounded ticks, so the
cores keep executing while the host thinks.

Runs inside the verilator sim process (MODULE=riscq.sim.bench, driven by riscq.sim.server)."""

from __future__ import annotations

import json
import os
import queue
import threading
from collections import deque
from pathlib import Path

import cocotb
import numpy as np
from cocotb.clock import Clock
from cocotb.triggers import ClockCycles, FallingEdge, RisingEdge, Timer
from cocotb.utils import get_sim_time

import Pyro5.api
import serpent

from riscq.map import ADC_BATCH, BATCH_SIZE, DIO_PIPE, SocMap, SocParams
from riscq.sim import models

CLK_PERIOD_NS = 10        # both clk and dspClk (period equality is fine in sim)
IDLE_TICK = 200           # cycles free-run per idle service-loop pass
POLL_INTERVAL = 200       # cycles between re-reads inside poll_word
AXI_TIMEOUT = 100_000     # cycles before an AXI handshake is declared dead (loud, not hung)
DAC_GET_TIMEOUT = 1_000_000  # cycles dac_capture_get waits for an armed capture to finish

# Modelled physical base of the PS DDR4 result buffer (specs/software/22). On hardware this is what
# `pynq.allocate` handed back; here it is any plausible constant — the funnel adds it to
# `(core << 24) + offset`, and read_host is buffer-relative, so the value only has to round-trip.
HOST_BASE = 0x70000000

# dspClk cycles from refTime's free-running origin (the dspRst-release cycle, captured once at sim
# start via TimeMirror.set_origin) to the cycle whose io_dac_* sample carries batch time 0 for a
# single-channel DAC (dac_pipe = 1). refTime lives in dspCd and is NOT reset by riscqReset (spec 08),
# so batch time is monotonic across runs and there is one fixed anchor for the whole session — not a
# per-run riscqReset-release anchor. Empirical for this bench's fixed stimulus (deterministic under
# Verilator: both clocks 10 ns, in phase); every M1 pulse test asserts window position against it, so
# drift fails loudly. Calibrated: a program's pulse at startTime = t lands at capture stamps [t, t+dur).
SIMSTART_TO_TIME0 = 2


class AxiMaster:
    """Single-beat AXI4 master (len=0, size=2, INCR, strb=0xF); AW/W handshakes independent,
    B/R awaited. Proven mechanics from the old cosim driver."""

    def __init__(self, dut):
        self.dut = dut
        self.clk = dut.clk

    async def _await_ready(self, valid, ready, what: str):
        valid.value = 1
        for _ in range(AXI_TIMEOUT):
            await RisingEdge(self.clk)
            if ready.value == 1:
                valid.value = 0
                return
        raise RuntimeError(f"AXI {what} handshake timeout after {AXI_TIMEOUT} cycles")

    def _addr_phase(self, prefix: str, addr: int):
        d = self.dut
        getattr(d, f"io_axi_{prefix}_payload_addr").value = addr & 0xFFFFFFFF
        for field, value in (("id", 0), ("region", 0), ("len", 0), ("size", 2),
                             ("burst", 1), ("lock", 0), ("cache", 0), ("qos", 0), ("prot", 0)):
            getattr(d, f"io_axi_{prefix}_payload_{field}").value = value

    async def write_word(self, addr: int, data: int) -> None:
        d = self.dut
        self._addr_phase("aw", addr)
        d.io_axi_w_payload_data.value = data & 0xFFFFFFFF
        d.io_axi_w_payload_strb.value = 0xF
        d.io_axi_w_payload_last.value = 1
        d.io_axi_b_ready.value = 1
        await self._await_ready(d.io_axi_aw_valid, d.io_axi_aw_ready, "AW")
        await self._await_ready(d.io_axi_w_valid, d.io_axi_w_ready, "W")
        for _ in range(AXI_TIMEOUT):
            if d.io_axi_b_valid.value == 1:
                break
            await RisingEdge(self.clk)
        else:
            raise RuntimeError(f"AXI B response timeout at addr {addr:#x}")
        d.io_axi_b_ready.value = 0

    async def read_word(self, addr: int) -> int:
        d = self.dut
        self._addr_phase("ar", addr)
        d.io_axi_r_ready.value = 1
        await self._await_ready(d.io_axi_ar_valid, d.io_axi_ar_ready, "AR")
        for _ in range(AXI_TIMEOUT):
            if d.io_axi_r_valid.value == 1:
                break
            await RisingEdge(self.clk)
        else:
            raise RuntimeError(f"AXI R response timeout at addr {addr:#x}")
        data = int(d.io_axi_r_payload_data.value) & 0xFFFFFFFF
        d.io_axi_r_ready.value = 0
        return data


def _cycle() -> int:
    """Current dspClk cycle index (both clocks are fixed 10 ns from t=0)."""
    return int(get_sim_time(units="ps")) // (CLK_PERIOD_NS * 1000)


class TimeMirror:
    """Bench-side mirror of the SoC batch time. refTime is a free-running dspCd counter (spec 08: NOT
    reset by riscqReset), so batch time is MONOTONIC across runs — one fixed anchor for the whole
    session, set at refTime's dspRst-release origin (set_origin), plus the host-written timeOffset.
    Updated by watching the AXI writes the bench performs. Convention: time_of_cycle(c) is the batch
    time whose pulse-generator output a dac_pipe=1 DAC port carries in cycle c (set timeOffset only
    while riscqReset is asserted)."""

    def __init__(self, m: SocMap):
        self._reset_addr = m.host_ctrl + m.HOST_RESET
        self._lo_addr = m.host_ctrl + m.HOST_TIME_OFF_LO
        self._hi_addr = m.host_ctrl + m.HOST_TIME_OFF_HI
        self.origin_cycle: int | None = None   # refTime free-running origin (dspRst release), fixed once
        self.release_cycle: int | None = None  # latest riscqReset release — gates ADC injection only
        self._off_lo = 0
        self._off_hi = 0

    def set_origin(self, cycle: int) -> None:
        """Pin the batch-time anchor to refTime's origin (dspRst release), set once at sim start."""
        self.origin_cycle = cycle

    def on_write(self, addr: int, data: int) -> None:
        if addr == self._reset_addr and data == 0:
            self.release_cycle = _cycle()
        elif addr == self._lo_addr:
            self._off_lo = data
        elif addr == self._hi_addr:
            self._off_hi = data

    @property
    def offset(self) -> int:
        return ((self._off_hi << 32) | self._off_lo) & 0xFFFFFFFF   # 32-bit batch time

    def time_of_cycle(self, cycle: int) -> int:
        if self.origin_cycle is None:
            raise RuntimeError("refTime origin not set (dspRst not released) — batch time undefined")
        return cycle - self.origin_cycle - SIMSTART_TO_TIME0 + self.offset

    def cycle_of_time(self, t: int) -> int:
        if self.origin_cycle is None:
            raise RuntimeError("refTime origin not set (dspRst not released) — batch time undefined")
        return t - self.offset + self.origin_cycle + SIMSTART_TO_TIME0


class DacCapture:
    """One armed capture: samples a port on consecutive dspClk cycles — `io_dac_<id>_payload` for a
    DAC (`pipe` = the DAC's output pipe, modelled out of the stamps) or `io_dio_<name>_out` for a
    timed-DIO bank (`pipe` = DIO_PIPE)."""

    def __init__(self, dac_id: int, n_batches: int, start_batch: int | None,
                 sig: str | None = None, pipe: int | None = None):
        self.dac_id = dac_id
        self.sig = sig if sig is not None else f"io_dac_{dac_id}_payload"
        self.pipe = pipe
        self.n_batches = n_batches
        self.start_batch = start_batch
        self.first_cycle: int | None = None
        self.vals: list[int] = []
        self.origin_cycle: int | None = None
        self.offset = 0
        self.done = False
        self.error: str | None = None


async def _capture_run(dut, m: SocMap, mirror: TimeMirror, cap: DacCapture) -> None:
    """Sample the DAC payload each dspClk falling edge (registered outputs settled), starting
    now (arm) or at the cycle stamped `start_batch`. The batch stamp of sample j resolves to
    time_of_cycle(first_cycle + j) - dac_pipe(dac_id) at get time, so a pulse played at t
    occupies stamps [t, t+dur) on EVERY DAC (the summed-DAC extra RegNext is modeled out)."""
    try:
        sig = getattr(dut, cap.sig)
        pipe = m.dac_pipe(cap.dac_id) if cap.pipe is None else cap.pipe
        clk = dut.dspClk
        await FallingEdge(clk)
        if cap.start_batch is not None:
            target = mirror.cycle_of_time(cap.start_batch) + pipe
            delta = target - _cycle()
            if delta < 0:
                raise RuntimeError(f"start_batch {cap.start_batch} is {-delta} cycles in the past")
            if delta > 0:
                await ClockCycles(clk, delta)
                await FallingEdge(clk)
        cap.first_cycle = _cycle()
        for _ in range(cap.n_batches):
            cap.vals.append(int(sig.value))
            await FallingEdge(clk)
        # snapshot the mirror NOW (origin is fixed for the session; offset may change between runs),
        # so a later timeOffset write cannot skew the stamps of an already-finished capture.
        cap.origin_cycle = mirror.origin_cycle
        cap.offset = mirror.offset
    except Exception as exc:
        cap.error = f"{type(exc).__name__}: {exc}"
    finally:
        cap.done = True


def _read_dac(dut, dac_id: int) -> np.ndarray:
    """The current io_dac_<id> payload as BATCH_SIZE signed int16 lanes (lane k = bits [16k+15:16k])."""
    raw = int(getattr(dut, f"io_dac_{dac_id}_payload").value)
    return np.frombuffer(raw.to_bytes(BATCH_SIZE * 2, "little"), dtype="<i2").astype(np.int64)


def _pack_adc(lanes) -> int:
    """Pack ADC_BATCH int16 lanes into the little-endian io_adc payload word (lane j at bits
    [16j+15:16j])."""
    return int.from_bytes(np.asarray(lanes, dtype="<i2").tobytes(), "little")


async def _adc_stimulus(dut, st: "_BenchState") -> None:
    """Background loop closing the ADC loop through the active QuantumModel (spec 05 §3): every
    dspClk batch, sample the DACs the model reads, call adc_batch(batch_time, dac), and drive the
    returned io_adc payloads. With the default ZeroModel (or before the reset release, when batch
    time is undefined) there is nothing to do, so it free-runs in coarse chunks and leaves every
    ADC at 0 — keeping the DAC-only tests untouched. When a model is set (drv.sim.set_model) it
    ticks per batch. The falling edge matches the DAC-capture sampling (registered outputs settled)."""
    clk = dut.dspClk
    adc_sigs = {i: getattr(dut, f"io_adc_{i}_payload") for i in range(st.m.params.adc_num)}
    while True:
        model = st.model
        if isinstance(model, models.ZeroModel) or st.mirror.release_cycle is None:
            await ClockCycles(clk, IDLE_TICK)
            continue
        await FallingEdge(clk)
        t = st.mirror.time_of_cycle(_cycle())
        dac = {did: _read_dac(dut, did) for did in model.dac_ids()}
        for aid, lanes in model.adc_batch(t, dac).items():
            adc_sigs[aid].value = _pack_adc(lanes)


def _int_or_zero(sig) -> int:
    """A port value as an int, or 0 while it is still X (pre-reset)."""
    try:
        return int(sig.value)
    except ValueError:
        return 0


async def _host_window_slave(dut, st: "_BenchState") -> None:
    """Model of `S_AXI_HP0_FPD`: accept the host window's write-only AXI master, answer `b`, and
    store every beat into `st.host_mem` at `addr - HOST_BASE` under its `wstrb` (specs/software/22
    §2.2). Single-beat writes with one ID, so `aw` and `w` pair in issue order.

    Timing: runs on the falling edge and *commits* that cycle's `ready` there — AXI holds `valid`
    and its payload until the handshake, so "valid now && I drive ready now" IS the transfer at the
    coming rising edge. It **sleeps on `aw_valid`** whenever the funnel is idle, so the per-cycle
    python costs nothing between shots (the same reason `_adc_stimulus` free-runs in coarse chunks).

    `ready` is held high rather than randomised: back-pressure on this port is signed off in
    SpinalSim by `HostWindowFunnelSim`/`HostWindowCpuSim` (random ready stalls, golden memory), and
    stalling here would only slow the co-sim down.
    """
    clk = dut.clk
    awq: deque = deque()
    wq: deque = deque()
    pending_b = 0
    dut.io_hostMem_b_payload_id.value = 0
    dut.io_hostMem_b_payload_resp.value = 0
    dut.io_hostMem_aw_ready.value = 1
    dut.io_hostMem_w_ready.value = 1
    dut.io_hostMem_b_valid.value = 0
    while True:
        if not (_int_or_zero(dut.io_hostMem_aw_valid) or _int_or_zero(dut.io_hostMem_w_valid)
                or pending_b or awq or wq):
            await RisingEdge(dut.io_hostMem_aw_valid)   # idle: wake only when a write starts
            continue
        await FallingEdge(clk)
        if _int_or_zero(dut.io_hostMem_aw_valid):
            awq.append(_int_or_zero(dut.io_hostMem_aw_payload_addr))
        if _int_or_zero(dut.io_hostMem_w_valid):
            wq.append((_int_or_zero(dut.io_hostMem_w_payload_data),
                       _int_or_zero(dut.io_hostMem_w_payload_strb)))
        while awq and wq:
            addr = awq.popleft()
            data, strb = wq.popleft()
            st.host_write(addr, data, strb)
            pending_b += 1
        if pending_b and _int_or_zero(dut.io_hostMem_b_ready):
            dut.io_hostMem_b_valid.value = 1
            pending_b -= 1
        else:
            dut.io_hostMem_b_valid.value = 0

async def _wr_loopback(dut):
    """Self-loopback stand-in for the WR phy (with_white_rabbit builds; specs/white-rabbit 06 §4):
    62.5 MHz clkRef/clkRx togglers, each TX word replayed to RX after a constant small queue
    delay — bit offset 0 by construction, so `aligned` needs no dice-throw model — with
    ready/aligned mirroring the node's resetAll. Enough for the W5 cosim smoke: bring-up,
    self-exchange timestamps, marker; the real dice-throw/latency model is the SpinalSim
    GtySimPhy (WrNodeSim / WrTwoNodeSim)."""
    half_ns = 8
    q = deque([0] * 4)
    dut.wrPhy_clkRef.value = 0
    dut.wrPhy_clkRx.value = 0
    dut.wrPhy_rxDataRaw.value = 0
    dut.wrPhy_ready.value = 0
    dut.wrPhy_aligned.value = 0
    dut.wrPhy_diceCount.value = 1
    up = 0
    while True:
        dut.wrPhy_clkRef.value = 1
        dut.wrPhy_clkRx.value = 1
        await Timer(1, units="ns")               # let the TX PCS regs settle after the edge
        q.append(int(dut.wrPhy_txDataRaw.value))
        await Timer(half_ns - 1, units="ns")
        dut.wrPhy_clkRef.value = 0
        dut.wrPhy_clkRx.value = 0
        dut.wrPhy_rxDataRaw.value = q.popleft()  # data moves on the falling edge — clean setup
        if int(dut.wrPhy_resetAll.value) or int(dut.wrPhy_resetRxDatapath.value):
            dut.wrPhy_ready.value = 0
            dut.wrPhy_aligned.value = 0
            up = 0
        elif up < 8:
            up += 1
            if up == 8:                          # token bring-up delay (the dice-throw stand-in)
                dut.wrPhy_ready.value = 1
                dut.wrPhy_aligned.value = 1
        await Timer(half_ns, units="ns")


class _Req:
    __slots__ = ("op", "args", "done", "result", "error")

    def __init__(self, op: str, args: tuple):
        self.op, self.args = op, args
        self.done = threading.Event()
        self.result = None
        self.error: Exception | None = None


@Pyro5.api.expose
class DriverServer:
    """Pyro5 face of the bench: marshals every call onto the request queue the cocotb
    coroutine services; blocks the (daemon-thread) caller until the sim replies."""

    def __init__(self, reqs: queue.Queue, params_text: str):
        self._reqs = reqs
        self._params = params_text
        self._m = None            # server-side SocMap, built on remote_setup (spec 08 §5)
        self._progs = {}          # core -> Program, rebuilt from the wire on remote_setup
        self.sim = self           # so riscq.run.poll_done finds `.sim.poll_word` locally
        self.host_base = HOST_BASE  # riscq.run.setup programs this into HOSTWIN_BASE_LO/HI

    def _submit(self, op: str, *args):
        req = _Req(op, args)
        self._reqs.put(req)
        if not req.done.wait(timeout=600):
            raise RuntimeError(f"cosim bench did not service {op!r} within 600 s (sim stalled?)")
        if req.error is not None:
            raise req.error
        return req.result

    def read32(self, addr):
        return self._submit("read32", int(addr))

    def write32(self, addr, value):
        return self._submit("write32", int(addr), int(value))

    def read_block(self, addr, nbytes):
        return self._submit("read_block", int(addr), int(nbytes))

    def write_block(self, addr, data):
        data = serpent.tobytes(data) if isinstance(data, dict) else bytes(data)
        return self._submit("write_block", int(addr), data)

    def advance(self, cycles):
        return self._submit("advance", int(cycles))

    def batch_time(self):
        """Current batch time (refTime + timeOffset). Monotonic across runs — the host reads it to
        schedule an absolute-time capture ahead of `now` (spec 08: refTime free-runs in dspCd)."""
        return self._submit("batch_time")

    def poll_word(self, addr, not_equal, timeout_cycles):
        return self._submit("poll_word", int(addr), int(not_equal), int(timeout_cycles))

    def dac_capture_arm(self, dac_id, n_batches, start_batch=None):
        return self._submit("dac_arm", int(dac_id), int(n_batches),
                            None if start_batch is None else int(start_batch))

    def dac_capture_get(self, handle):
        return self._submit("dac_get", int(handle))

    def dio_capture_arm(self, name, n_batches, start_batch=None):
        return self._submit("dio_arm", str(name), int(n_batches),
                            None if start_batch is None else int(start_batch))

    def dio_capture_get(self, handle):
        return self._submit("dac_get", int(handle))

    def dio_set(self, name, value):
        return self._submit("dio_set", str(name), int(value))

    def set_model(self, spec):
        return self._submit("set_model", dict(spec))

    def model_state(self):
        return self._submit("model_state")

    def read_host(self, offset, nbytes):
        return self._submit("read_host", int(offset), int(nbytes))

    def get_host_base(self):
        return int(self.host_base)

    def get_params(self):
        return self._params

    # ── server-side batch runner (spec 08 §5): run the SAME riscq.run functions next to the sim,
    # so a whole batch is one RPC instead of ~10 per-op round trips. The seam ops each method issues
    # go straight onto the request queue (self._submit — no network hop); `self.sim = self` gives
    # run.poll_done its `.sim.poll_word`, and DriverServer has no `.remote` attr so run.setup/rerun
    # take their LOCAL per-op path here.
    def remote_setup(self, params_json, progmap):
        from riscq import run as _run
        from riscq.map import SocMap, SocParams
        self._m = SocMap(SocParams.from_json(self._params))   # the server's own build is ground truth
        self._progs = {int(c): _run._prog_from_wire(w) for c, w in progmap.items()}
        _run.setup(self, self._m, self._progs)
        return None

    def remote_rerun(self, cores, params, arrays, results, timeout):
        from riscq import run as _run
        progs = {int(c): self._progs[int(c)] for c in cores}
        out = _run.rerun(self, self._m, progs,
                         params={int(c): v for c, v in dict(params).items()},
                         arrays={int(c): v for c, v in dict(arrays).items()},
                         results=(None if results is None else list(results)), timeout=int(timeout))
        return {c: {n: bytes(a.astype("<i4").tobytes()) for n, a in d.items()} for c, d in out.items()}

    def shutdown(self):
        return self._submit("shutdown")


class _BenchState:
    """The bench's non-AXI state: the SocMap, the time mirror, and the armed captures."""

    def __init__(self, m: SocMap):
        self.m = m
        self.mirror = TimeMirror(m)
        self.captures: dict[int, DacCapture] = {}
        self._next_handle = 0
        self.model = models.ZeroModel()   # ADC seam; replaced at runtime via set_model
        # the modelled PS DDR4 result buffer: one 16 MB slice per core (specs/software/22 §3)
        self.host_mem = bytearray(m.hostwin_bytes_total)

    def host_write(self, addr: int, data: int, strb: int) -> None:
        """Apply one AXI beat to the modelled buffer. An address outside the buffer is a real bug
        (a bad HOSTWIN_BASE or a runaway offset), so it is loud rather than silently dropped."""
        off = addr - HOST_BASE
        if not 0 <= off <= len(self.host_mem) - 4:
            raise RuntimeError(f"host-window write to {addr:#x} outside the "
                               f"{len(self.host_mem)} B buffer at {HOST_BASE:#x}")
        for b in range(4):
            if strb >> b & 1:
                self.host_mem[off + b] = data >> (8 * b) & 0xFF

    def new_capture(self, cap: DacCapture) -> int:
        self._next_handle += 1
        self.captures[self._next_handle] = cap
        return self._next_handle


async def _handle(axi: AxiMaster, dut, st: _BenchState, op: str, args: tuple):
    if op == "read32":
        return await axi.read_word(args[0])
    if op == "write32":
        result = await axi.write_word(args[0], args[1])
        st.mirror.on_write(args[0], args[1])
        return result
    if op == "dac_arm":
        dac_id, n_batches, start_batch = args
        if not hasattr(dut, f"io_dac_{dac_id}_payload"):
            raise ValueError(f"no such DAC port: io_dac_{dac_id}_payload")
        cap = DacCapture(dac_id, n_batches, start_batch)
        handle = st.new_capture(cap)
        cocotb.start_soon(_capture_run(dut, st.m, st.mirror, cap))
        return handle
    if op == "dac_get":
        cap = st.captures.get(args[0])
        if cap is None:
            raise ValueError(f"unknown capture handle {args[0]}")
        spent = 0
        while not cap.done and spent < DAC_GET_TIMEOUT:
            await ClockCycles(dut.dspClk, POLL_INTERVAL)
            spent += POLL_INTERVAL
        if not cap.done:
            raise RuntimeError(f"capture {args[0]} not finished after {DAC_GET_TIMEOUT} cycles")
        if cap.error is not None:
            raise RuntimeError(f"capture failed: {cap.error}")
        if cap.origin_cycle is None:
            raise RuntimeError("capture finished with no refTime origin (dspRst not released) — no time base")
        pipe = st.m.dac_pipe(cap.dac_id) if cap.pipe is None else cap.pipe
        t0 = cap.first_cycle - cap.origin_cycle - SIMSTART_TO_TIME0 + cap.offset - pipe
        lane_bytes = BATCH_SIZE * 2 if cap.pipe is None else 4
        data = b"".join(v.to_bytes(lane_bytes, "little") for v in cap.vals)
        del st.captures[args[0]]
        return t0, cap.n_batches, data
    if op == "dio_arm":
        name, n_batches, start_batch = args
        sig = f"io_dio_{name}_out"
        if not hasattr(dut, sig):
            raise ValueError(f"no such DIO port: {sig}")
        cap = DacCapture(-1, n_batches, start_batch, sig=sig, pipe=DIO_PIPE)
        handle = st.new_capture(cap)
        cocotb.start_soon(_capture_run(dut, st.m, st.mirror, cap))
        return handle
    if op == "dio_set":
        name, value = args
        sig = f"io_dio_{name}_in"
        if not hasattr(dut, sig):
            raise ValueError(f"no such DIO port: {sig}")
        getattr(dut, sig).value = int(value) & 0xFFFF
        await ClockCycles(dut.dspClk, 1)
        return None
    if op == "read_block":
        addr, nbytes = args
        if nbytes % 4:
            raise ValueError(f"read_block nbytes {nbytes} not word-aligned")
        out = bytearray()
        for i in range(nbytes // 4):
            out += (await axi.read_word(addr + 4 * i)).to_bytes(4, "little")
        return bytes(out)
    if op == "write_block":
        addr, data = args
        if len(data) % 4:
            data = data + b"\x00" * (4 - len(data) % 4)
        for i in range(len(data) // 4):
            await axi.write_word(addr + 4 * i, int.from_bytes(data[4 * i:4 * i + 4], "little"))
        return None
    if op == "read_host":
        off, nbytes = args
        if not 0 <= off <= len(st.host_mem) - nbytes:
            raise ValueError(f"read_host [{off}, {off + nbytes}) outside the "
                             f"{len(st.host_mem)} B host buffer")
        return bytes(st.host_mem[off:off + nbytes])
    if op == "set_model":
        st.model = models.build_model(dict(args[0]), st.m)
        return None
    if op == "model_state":
        return st.model.ground_truth()
    if op == "advance":
        await ClockCycles(dut.clk, args[0])
        return None
    if op == "batch_time":
        return st.mirror.time_of_cycle(_cycle())
    if op == "poll_word":
        addr, not_equal, timeout_cycles = args
        value = await axi.read_word(addr)
        spent = 0
        while value == not_equal and spent < timeout_cycles:
            step = min(POLL_INTERVAL, timeout_cycles - spent)
            await ClockCycles(dut.clk, step)
            spent += step
            value = await axi.read_word(addr)
        return value
    raise ValueError(f"unknown op {op!r}")


@cocotb.test()
async def cosim_server(dut):
    uri_file = Path(os.environ["RISCQ_COSIM_URI_FILE"])
    params_text = Path(os.environ["RISCQ_COSIM_CONFIG"]).read_text()
    cfg = json.loads(params_text)
    st = _BenchState(SocMap(SocParams.from_json(params_text)))

    # idle inputs before the first edge (SOC_TIPS: never let bus valids float during reset)
    dut.reset.value = 1
    dut.dspRst.value = 1
    for sig in ("aw", "w", "ar"):
        getattr(dut, f"io_axi_{sig}_valid").value = 0
    dut.io_axi_b_ready.value = 0
    dut.io_axi_r_ready.value = 0
    dut.io_hostMem_aw_ready.value = 0
    dut.io_hostMem_w_ready.value = 0
    dut.io_hostMem_b_valid.value = 0
    for i in range(cfg["dac_num"]):
        getattr(dut, f"io_dac_{i}_ready").value = 1
    for i in range(cfg["adc_num"]):
        getattr(dut, f"io_adc_{i}_valid").value = 1
        getattr(dut, f"io_adc_{i}_payload").value = 0

    cocotb.start_soon(Clock(dut.clk, CLK_PERIOD_NS, units="ns").start())
    cocotb.start_soon(Clock(dut.dspClk, CLK_PERIOD_NS, units="ns").start())
    cocotb.start_soon(_adc_stimulus(dut, st))   # ADC seam (idle until a model is set)
    cocotb.start_soon(_host_window_slave(dut, st))   # PS DDR4 result buffer (specs/software/22)
    if cfg.get("with_white_rabbit", False):
        cocotb.start_soon(_wr_loopback(dut))    # WR phy self-loopback (test_wr.py cosim smoke)
    await Timer(200, units="ns")
    dut.reset.value = 0
    dut.dspRst.value = 0
    # refTime (dspCd, free-running) starts counting from this dspRst release — pin the batch-time anchor
    # here. Batch time is monotonic across runs, so this is the single session-wide origin (spec 08).
    st.mirror.set_origin(_cycle())
    await Timer(200, units="ns")
    # riscqReset stays asserted (powers up held); the host releases it over AXI (riscq.run.reset)

    axi = AxiMaster(dut)
    reqs: queue.Queue = queue.Queue()
    daemon = Pyro5.api.Daemon(host="127.0.0.1", port=0)
    uri = daemon.register(DriverServer(reqs, params_text), objectId="riscq.cosim")
    threading.Thread(target=daemon.requestLoop, daemon=True).start()
    tmp = uri_file.with_suffix(".tmp")
    tmp.write_text(str(uri))
    os.replace(tmp, uri_file)   # atomic: the client never sees a partial file
    dut._log.info(f"[riscq cosim] serving {uri}")

    while True:
        try:
            req = reqs.get_nowait()
        except queue.Empty:
            await ClockCycles(dut.clk, IDLE_TICK)   # bounded free-run between requests
            continue
        if req.op == "shutdown":
            req.result = True
            req.done.set()
            break
        try:
            req.result = await _handle(axi, dut, st, req.op, req.args)
        except Exception as exc:  # keep the sim alive; the client re-raises
            req.error = RuntimeError(f"{type(exc).__name__}: {exc}")
        req.done.set()

    dut._log.info("[riscq cosim] shutdown")
