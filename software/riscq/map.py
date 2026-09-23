"""The hardware contract in one place: SocSpec (per-build JSON) -> SocMap (every address).

The formulas mirror the SpinalHDL elaboration (`riscq.soc.spec.SocSpecMap`, checked against
`SocMemoryMap` on every RTL generation); no address may appear literally anywhere else in the stack.
The host map is a list of regions derived from the channel lists: region 0 = the core RAMs, then one
region per channel slot index (each core's j-th channel envelope RAM), then the readout trace and the
host control block — the same stride algebra as before, with the named role regions gone
(specs/universal-control/01 §2.2).
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

# ── fixed architecture constants (assert, never parameterize) — defined once in riscq.spec ──
from riscq.spec import (ADC_BATCH, BATCH_SIZE, DATA_WIDTH, READOUT_ACC_WIDTH,  # noqa: E402,F401
                        READOUT_MAX_WIN_LOG2, ChannelSpec, CoreSpec, SocSpec)

MEM_BASE = 0x80000000    # CPU boot vector = base of the unified I+D RAM

# Readout-result freshness lead (batches): the carrier-triggered decoder's res.valid is forwarded up
# the link as a LEVEL that holds the PREVIOUS shot until this window's winStart reaches the sink
# (~Ldemod + carrier-stage + link_pipe + sink ~ 16-29 batches after the demod startTime). After firing
# the demod at `t`, software waits `t + READOUT_LEAD` before read_res so the stale level has dropped and
# the halting read returns THIS window. Conservative (covers link_pipe up to ~32 plus the 3-put
# serialisation of a settled result, specs/cross-core/02 §3.3); over-waiting is free.
READOUT_LEAD = 48

# conservative schedule lead (batches): covers linkPipe + worst timed-queue lead + CPU jitter.
# Pinned by the M1 margin test: the CPU's now()-read -> fire-landing path measured ~40 batches,
# on top of the worst FIRED queue lead (phase, 35) — the M0 guess of 64 left the first ~9 window
# batches with stale amp/phase on a fresh SoC. 96 = 40 + 35 + ~20 margin; the freq (phasor
# regen) lead of linkPipe+52 only binds when freq is written against the pulse's startTime —
# programs write freq early against startTime 0 (see test_pulse.PULSE_SRC).
LEAD = 96

# Stamp correction of a timed-DIO capture (the DIO counterpart of dac_pipe): an entry scheduled at
# batch t shows its edge at capture stamp t + DIO_PIPE. Measured 0 on the sim-dio build
# (tests/test_dio.py) — the bench's time mirror already absorbs the time-replica stages.
DIO_PIPE = 0


# 16-bit pulse-parameter fields live at data[31:16] of the posted write word (PulseParamBuffer
# bitOffset = 16; the hardware ignores data[15:0] on those addresses — only startTime/fire read the
# low bits). pack16 seats a code there — THE software-side field packer, replacing the firmware
# RQ_PACK16 (spec 12). A seated code is code << 16: its low 16 bits are zero, so a compile-time
# literal loads in one `lui`, and an on-core Q16 accumulator carries its fraction in the ignored low
# half and is written raw (no >> 16 extraction). Codes wider than 16 bits (phase wraps) fold mod 2^16.
# The result is a SIGNED int32 (the value a C int32_t register holds): a code with bit 15 set seats
# to a NEGATIVE word — so it passes the kernel's int32 binding range check and emits as a clean C
# literal. Byte/word writers mask with & 0xFFFFFFFF for serialization.
def pack16(code: int) -> int:
    w = (int(code) & 0xFFFF) << 16
    return w - (1 << 32) if w >= (1 << 31) else w


def pow2ceil(x: int) -> int:
    """1 << log2Up(x), matching SpinalHDL's log2Up = (x-1).bitLength."""
    assert x > 0
    return 1 << (x - 1).bit_length()


# The build description. `SocParams` is the historical name every module imports; it is the same
# class (the legacy JSON form is converted on load, spec 24 / universal-control/01 P0).
SocParams = SocSpec


@dataclass(frozen=True)
class MapEntry:
    """One host-AXI window, for the generated contract test. kind:
    ram_rw (core RAM, host R/W), env_gate (via 512/gate_interp-bit WidthAdapter, R/W),
    env_ro (direct 32-bit, R/W), env_demod (demod-carrier bank, direct 32-bit at interp 4, R/W),
    robs_ro (shared trace BRAM, read-only for software), ctrl_wo (host control block, write-only)."""

    name: str
    host_addr: int
    nbytes: int
    kind: str


@dataclass(frozen=True)
class ChannelInfo:
    """One channel of one core as the compiler and the run layer see it (spec 02 §3.2): a ParamTable
    folds to this. `index` is the channel's position in its core's list (the int a ParamTable
    carries), `base` the CPU-local RF sub-window base, `slot_count` the pulse-table depth,
    `samples_per_line`/`line_bytes` the channel's envelope-RAM grid, `cname` the riscq_map.h macro
    (RF_CHi) the backend emits for it. `core`, `name`, `kind`, `env_depth`, `lanes`, `dac`/`adc`
    carry the rest of the ChannelSpec for the callers that need it."""

    index: int
    cname: str
    base: int
    slot_count: int
    samples_per_line: int
    line_bytes: int
    core: int = 0
    name: str = ""
    kind: str = "pulse"
    env_depth: int = 0
    lanes: int = BATCH_SIZE
    dac: int | None = None
    adc: int | None = None


class SocMap:
    """Every base/stride/offset of one build, derived from its SocSpec with the elaboration's
    own formulas. Host-AXI addresses are absolute on the SoC's AXI slave; core-local addresses
    (CTRL_*/RF_*) are CPU addresses shared with the generated riscq_map.h."""

    # ── core-local control block (CPU addresses; ControlMemMaps.scala + ReadoutResultSink) ──
    CTRL_FROM_HOST = 0x2000
    CTRL_TIME_CMP = 0x4000
    CTRL_WAIT_TIME_CMP = 0x4008     # read HALTS until time + 3 >= timeCmp
    CTRL_DONE = 0x4010              # write bit 0 = "this program has finished" (specs/software/23);
                                    # sticky, cleared only by riscqReset
    CTRL_RES = 0x4200               # read HALTS until the armed readout completes
    CTRL_REAL = 0x4204
    CTRL_IMAG = 0x4208
    CTRL_TIME = 0xBFF8

    # ── RF window (CPU addresses): the RfLinkBridge sits at RF_WINDOW; channel k of a core owns the
    # sub-window RF_WINDOW + k * RF_CH_STRIDE (RfLink.demux in the core shell). The qubit builds list
    # gate / ro / demod, so the role aliases below still hold there; 0x40000 stays unmapped on them. ──
    RF_WINDOW = 0x10000
    RF_CH_STRIDE = 0x10000
    # ── the put window (specs/cross-core/02 §3.2): a store's address is RF_WINDOW + node * 2^16 + offset.
    # Nodes 0..LOCAL_NODES-1 are the core's own channels (node k = channel k, hence RF_CH_STRIDE); every
    # node from LOCAL_NODES up is a system unit routed to the board hub. Mirrors SocSpecMap.
    NODE_BITS = 12
    LOCAL_NODES = 16
    GROUP_NODES = 4                 # shared group words (board[g])
    BARRIER_IDS = 16                # counted barriers per hub
    GROUP_NODE0 = LOCAL_NODES                     # node of group g = GROUP_NODE0 + g
    BARRIER_NODE0 = LOCAL_NODES + GROUP_NODES     # node of barrier b = BARRIER_NODE0 + b
    INBOX_NODE0 = LOCAL_NODES + GROUP_NODES + BARRIER_IDS   # inbox unit of core i = INBOX_NODE0 + i; the
    #                                                          node carries its board: (board << 8) | unit
    # the cross-core inbox registers (EventLink), past the channels' sinks: board words, the barrier
    # release mailbox, the signal mailboxes (one 0x20 window each; a put offset is CPU addr - SINK_BASE)
    INBOX_BOARD = 0x4300
    INBOX_RELEASE = 0x4320
    INBOX_MAILBOX0 = 0x4340
    MAILBOX_NUM = 2
    RF_GATE = 0x10000               # alias: channel 0 (gate drive) on the qubit builds
    RF_READOUT = 0x20000            # alias: channel 1 (readout drive)
    RF_DEMOD = 0x30000              # alias: channel 2 (demod carrier: fire@0/freq@4/table@0x10../startTime@0x4100)
    #                                 firing the demod IS the readout (carrier-triggered decoder, no arm).
    # per drive channel (offsets from the channel base; PulseParamBufferParams):
    RF_FIRE = 0x0                   # write slot index (low bits) -> enqueue
    RF_FREQ = 0x4                   # carrier freq code at data[31:16]
    RF_DC_OFFSET = 0x8              # DC bias added to the real output lanes, code at data[31:16]
    RF_PHASE_OFFSET = 0xC           # virtual-Z phase added to the generator's phase input, code at data[31:16]
    RF_SLOT_STRIDE = 0x10           # slot i at (i+1)*0x10: +0 phase, +4 amp, +8 env, +12 dur
    RF_START_TIME = 0x4100          # full 32-bit batch time (low bits)

    # ── host window (CPU addresses; HostWindowBridge in RiscvSoc, specs/software/22) ──
    # Write-only: a store here lands in PS DDR4 at host_base + (core << 24) + offset, so result
    # arrays are not bounded by the 16 KB unified RAM. No loads (the fabric routes none here).
    HOSTWIN = 0x40000000
    HOSTWIN_BYTES = 1 << 24         # per core; overridden per build from hostwin_bits (the funnel's core shift)

    # ── host control block (offsets from host_ctrl; PulseTableSoc hostCtrlDriver) ──
    HOST_RESET = 0x0                # write 1 = assert (power-up state), 0 = release
    HOST_FROM_HOST = 0x10           # legacy mailbox, unused by this framework
    HOST_TIME_OFF_LO = 0x40
    HOST_TIME_OFF_HI = 0x44
    HOST_DONE = 0x50                # read-only: bit i = core i set its CTRL_DONE since the last reset
    HOST_HOSTWIN_LO = 0x48          # host-buffer physical base[31:0]
    HOST_HOSTWIN_HI = 0x4C          # [7:0] = base[39:32], [31] = enable (0 at reset ⇒ funnel holds)

    LEAD = LEAD

    def __init__(self, params: SocSpec):
        self.params = params
        p = params
        n = len(p.cores)

        self.rob_width = ADC_BATCH * 32
        self.mem_bytes = p.mem_depth * 4                   # unified I+D RAM bytes (uniform across cores)
        self.HOSTWIN_BYTES = 1 << p.hostwin_bits           # per-core host-window slice (16 MB at 24)
        self.rob_bytes = self.rob_width * p.rob_depth // 8

        # host map: region 0 = core RAMs; region 1+j = every core's j-th channel envelope RAM
        # (stride = the widest such bank); then the shared readout trace and the host control block.
        # Per-core sub-windows are a power-of-two stride apart; every region is region_size wide.
        self.core_stride = pow2ceil(1 << 16)               # PulseTableSoc passes coreMemBytes = 1 << 16
        self.n_slots = p.max_channels
        self.slot_strides = [                               # a bank-less slot (dio) is a 1-byte hole
            pow2ceil(max(1, *(c.channels[j].env_bytes for c in p.cores if j < len(c.channels))))
            for j in range(self.n_slots)]
        self.rob_stride = pow2ceil(2 * pow2ceil(self.rob_bytes))
        strides = (self.core_stride, *self.slot_strides, self.rob_stride)
        self.region_size = pow2ceil(max(strides) * n)

        self.core_mem_base = 0
        self.slot_bases = [(1 + j) * self.region_size for j in range(self.n_slots)]
        self.rob_base = (1 + self.n_slots) * self.region_size
        self.host_ctrl = (2 + self.n_slots) * self.region_size
        self.wr_base = (3 + self.n_slots) * self.region_size   # White Rabbit node window (with_white_rabbit
        #                                                       builds; register offsets in riscq.wr.WrRegs)

    # ── legacy role views of the qubit builds' three regions (gate = slot 0, ro = 1, demod = 2) ──
    @property
    def pulse_stride(self) -> int:
        return self.slot_strides[0]

    @property
    def ro_env_stride(self) -> int:
        return self.slot_strides[1]

    @property
    def demod_env_stride(self) -> int:
        return self.slot_strides[2]

    @property
    def pulse_mem_base(self) -> int:
        return self.slot_bases[0]

    @property
    def ro_env_base(self) -> int:
        return self.slot_bases[1]

    @property
    def demod_env_base(self) -> int:
        return self.slot_bases[2]

    # ── legacy per-role envelope geometry of the qubit builds (gate / ro / demod channels of core 0);
    # new code reads the ChannelInfo (channels()/channel_named()) — these aliases go in P6 ──
    def _legacy_channel(self, name: str) -> ChannelSpec:
        return self.params.core(0).channel(name)

    @property
    def gate_samples_per_line(self) -> int:
        return self._legacy_channel("gate").samples_per_line

    @property
    def gate_line_bytes(self) -> int:
        return self._legacy_channel("gate").line_bytes

    @property
    def gate_env_width(self) -> int:
        return self._legacy_channel("gate").env_width

    @property
    def gate_env_bytes(self) -> int:
        return self._legacy_channel("gate").env_bytes

    @property
    def ro_samples_per_line(self) -> int:
        return self._legacy_channel("ro").samples_per_line

    @property
    def ro_line_bytes(self) -> int:
        return self._legacy_channel("ro").line_bytes

    @property
    def ro_env_width(self) -> int:
        return self._legacy_channel("ro").env_width

    @property
    def ro_env_bytes(self) -> int:
        return self._legacy_channel("ro").env_bytes

    @property
    def demod_samples_per_line(self) -> int:
        return self._legacy_channel("demod").samples_per_line

    @property
    def demod_line_bytes(self) -> int:
        return self._legacy_channel("demod").line_bytes

    @property
    def demod_env_width(self) -> int:
        return self._legacy_channel("demod").env_width

    @property
    def demod_env_bytes(self) -> int:
        return self._legacy_channel("demod").env_bytes

    # ── host-AXI address helpers ──
    def _core(self, core: int) -> int:
        if not 0 <= core < len(self.params.cores):
            raise ValueError(f"core {core} out of range (have {len(self.params.cores)} cores)")
        return core

    def imem(self, core: int) -> int:
        """Host base of the core's unified I+D RAM window (CPU 0x80000000)."""
        return self.core_mem_base + self._core(core) * self.core_stride

    def gate_env(self, core: int) -> int:
        return self.env_base(self.params.core(core).index("gate"), core)

    def ro_env(self, core: int) -> int:
        return self.env_base(self.params.core(core).index("ro"), core)

    def demod_env(self, core: int) -> int:
        return self.env_base(self.params.core(core).index("demod"), core)

    def robs(self) -> int:
        """The ONE shared readout trace BRAM (not per-core)."""
        return self.rob_base

    # ── per-core channel table (spec 02 §3.2) ──
    def rf_addr_width(self, core: int = 0) -> int:
        """Width of the core's put window (the RfLinkBridge's rfAddrWidth): 16 + NODE_BITS."""
        assert len(self.params.core(core).channels) <= self.LOCAL_NODES
        return 16 + self.NODE_BITS

    def inbox_node(self, core: int, board: int = 0) -> int:
        """System node id of `core`'s inbox on `board` (D1: {board 4, unit 8})."""
        assert 0 <= board < 16 and 0 <= core < 256 - self.INBOX_NODE0
        return (board << 8) | (self.INBOX_NODE0 + core)

    def node_addr(self, node: int, offset: int = 0) -> int:
        """CPU address of `offset` within system node `node` (node >= LOCAL_NODES)."""
        assert self.LOCAL_NODES <= node < (1 << self.NODE_BITS)
        return self.RF_WINDOW + (node << 16) + offset

    def channels(self, core: int = 0) -> list[ChannelInfo]:
        """The core's channels in list order, indexed by the ParamTable `channel` field. On the
        qubit builds: 0 = gate drive (gate slots, 4 samples/line), 1 = readout drive (1 slot,
        1 sample/line), 2 = demod carrier (1 slot, 1 ADC-batch sample/line — an envelope fed to the
        readout decoder; its carrier freq is set separately via set_freq with an ADC-rate
        demod_freq_to_code code)."""
        cs = self.params.core(self._core(core))
        return [ChannelInfo(i, f"RF_CH{i}", self.RF_WINDOW + i * self.RF_CH_STRIDE, ch.slots,
                            ch.samples_per_line, ch.line_bytes, core=core, name=ch.name,
                            kind=ch.kind, env_depth=ch.env_depth, lanes=ch.lanes,
                            dac=ch.dac, adc=ch.adc)
                for i, ch in enumerate(cs.channels)]

    def channel(self, index: int, core: int = 0) -> ChannelInfo:
        chans = self.channels(core)
        if not 0 <= index < len(chans):
            raise ValueError(f"unknown channel index {index} (have {len(chans)} channels)")
        return chans[index]

    def channel_named(self, name: str, core: int = 0) -> ChannelInfo:
        return self.channel(self.params.core(core).index(name), core)

    # ── the core's up-link sinks (universal-control/01 §2.4): one per reporting channel, at
    # 0x4200 + 0x20·k in the plan's order; the qubit builds' demod sink is CTRL_RES/REAL/IMAG ──
    SINK_BASE = 0x4200
    SINK_STRIDE = 0x20
    REPORTER_KINDS = {"demod": "result", "dio": "fifo"}   # channel kind -> sink kind (EventPlan's table)

    def sinks(self, core: int = 0) -> list[tuple[str, str, int]]:
        """`(name, sink kind, base)` per inbox register window of `core`: the reporting channels in
        tag order, then the cross-core registers (EventPlan.allSinks)."""
        cs = self.params.core(self._core(core))
        reporters = [ch for ch in cs.channels if ch.kind in self.REPORTER_KINDS]
        chans = [(ch.name, self.REPORTER_KINDS[ch.kind], self.SINK_BASE + k * self.SINK_STRIDE)
                 for k, ch in enumerate(reporters)]
        xcore = [("board", "latest", self.INBOX_BOARD), ("release", "mailbox", self.INBOX_RELEASE)] + \
                [(f"mbox{m}", "mailbox", self.INBOX_MAILBOX0 + m * self.SINK_STRIDE) for m in range(self.MAILBOX_NUM)]
        return chans + xcore

    def env_base(self, index: int, core: int) -> int:
        """Host base of `core`'s envelope RAM for its channel `index`."""
        self.channel(index, core)   # validate (ValueError on unknown)
        return self.slot_bases[index] + self._core(core) * self.slot_strides[index]

    # ── channel -> converter map, from the spec (the legacy dac_map/adc_map or the generic ZCU216
    # SocChannelMap layout, converted on load). The co-sim model reads these to drive/read the
    # converters the hardware wired. ──
    def dac_of(self, core: int, index: int) -> int:
        ch = self.channel(index, core)
        if ch.dac is None:
            raise ValueError(f"core {core} channel {index} ({ch.name}) is not DAC-bound")
        return ch.dac

    def gate_dac(self, core: int) -> int:
        """Alias: the DAC of the core's `gate` channel."""
        return self.channel_named("gate", core).dac

    def ro_dac(self, core: int) -> int:
        """Alias: the readout-drive DAC (the core's `ro` channel), shared/summed."""
        return self.channel_named("ro", core).dac

    def adc_of(self, core: int, index: int | None = None) -> int:
        """The ADC of channel `index` (default: the core's first demod channel)."""
        if index is None:
            ch = next((c for c in self.channels(core) if c.adc is not None), None)
            if ch is None:
                raise ValueError(f"core {core} has no ADC-bound channel")
            return ch.adc
        ch = self.channel(index, core)
        if ch.adc is None:
            raise ValueError(f"core {core} channel {index} ({ch.name}) is not ADC-bound")
        return ch.adc

    def dac_pipe(self, dac_id: int) -> int:
        """dspClk register stages from a channel's scheduled pulse to its io_dac port. `PulseTableSoc`
        pads EVERY driven DAC to one shared depth `dacAlignStages` (the deepest combine among the
        physical DACs) so all pulses leave the converter edge time-aligned, so this is **uniform** across
        driven DACs (the `dac_id` arg is kept for the per-DAC caller contract). A DAC's natural combine is
        0 for a pass-through single channel and `AdderTree.latency(n)+1` for an n-channel sum; on top of
        the shared align there is the channel's own dcOffset output register and one shared RFDC-edge
        stage (`dacAlignStages + 2`). E.g. 4 on the 2-core map, 6 on the 14-core map."""
        def combine_latency(n: int) -> int:
            return 0 if n <= 1 else (n - 1).bit_length() + 1   # AdderTree.latency(n) + 1
        def channels_on(d: int) -> int:
            return sum(1 for c in self.params.cores for ch in c.channels if ch.dac == d)
        align = max(combine_latency(channels_on(d)) for d in range(self.params.dac_num))
        return align + 2

    def hostwin_offset(self, core: int) -> int:
        """Offset of `core`'s 16 MB slice inside the host result buffer — the `core << 24` term of
        the funnel's `base + (core << 24) + offset` (specs/software/22 §2.2). Buffer-relative, so it
        is what `Driver.read_host` takes."""
        return self._core(core) * self.HOSTWIN_BYTES

    @property
    def hostwin_bytes_total(self) -> int:
        """Bytes the host result buffer must have: one 16 MB slice per core."""
        return len(self.params.cores) * self.HOSTWIN_BYTES

    def to_host_addr(self, core: int, cpu_addr: int) -> int:
        """Translate a core-local (CPU) RAM address into the core's host window."""
        if not MEM_BASE <= cpu_addr < MEM_BASE + self.mem_bytes:
            raise ValueError(f"cpu addr {cpu_addr:#x} outside RAM "
                             f"[{MEM_BASE:#x}, {MEM_BASE + self.mem_bytes:#x})")
        return self.imem(core) + (cpu_addr - MEM_BASE)

    # ── contract-test descriptors, generated from this object ──
    def entries(self) -> list[MapEntry]:
        out = []
        for c, cs in enumerate(self.params.cores):
            out.append(MapEntry(f"core{c}_ram", self.imem(c), self.mem_bytes, "ram_rw"))
            for j, ch in enumerate(cs.channels):
                if ch.env_bytes:                            # bank-less kinds (dio) have no host window
                    out.append(MapEntry(f"core{c}_{ch.name}_env", self.env_base(j, c), ch.env_bytes,
                                        f"env_{ch.name}"))
        out.append(MapEntry("robs", self.robs(), self.rob_bytes, "robs_ro"))
        out.append(MapEntry("host_ctrl", self.host_ctrl, 0x54, "ctrl_wo"))
        return out

    # ── generated firmware inputs ──
    def gen_header(self, core: int = 0) -> str:
        """riscq_map.h — the evaluated core-local contract for this build, for programs of `core`
        (every core of a uniform build shares it). Channel bases come out as RF_CH<i> (the macro a
        ParamTable folds to) and RQ_CH_<NAME>; the RQ_GATE/RQ_READOUT/RQ_DEMOD aliases exist only on
        builds that have channels of those names."""
        p = self.params
        cs = p.core(self._core(core))
        chans = self.channels(core)
        defines = [
            ("RQ_MEM_BASE", MEM_BASE), ("RQ_MEM_BYTES", cs.mem_depth * 4),
            ("RQ_CTRL_FROM_HOST", self.CTRL_FROM_HOST),
            ("RQ_CTRL_TIME_CMP", self.CTRL_TIME_CMP),
            ("RQ_CTRL_WAIT_TIME_CMP", self.CTRL_WAIT_TIME_CMP),
            ("RQ_CTRL_DONE", self.CTRL_DONE),
            ("RQ_CTRL_RES", self.CTRL_RES), ("RQ_CTRL_REAL", self.CTRL_REAL),
            ("RQ_CTRL_IMAG", self.CTRL_IMAG), ("RQ_CTRL_TIME", self.CTRL_TIME),
        ]
        for alias, name in (("RQ_GATE", "gate"), ("RQ_READOUT", "ro"), ("RQ_DEMOD", "demod")):
            if cs.find(name) is not None:
                defines.append((alias, self.channel_named(name, core).base))
        defines += [
            ("RQ_FIRE", self.RF_FIRE), ("RQ_FREQ", self.RF_FREQ),
            ("RQ_DC_OFFSET", self.RF_DC_OFFSET),
            ("RQ_PHASE_OFFSET", self.RF_PHASE_OFFSET),
            ("RQ_SLOT_STRIDE", self.RF_SLOT_STRIDE), ("RQ_START_TIME", self.RF_START_TIME),
            ("RQ_HOSTWIN", self.HOSTWIN), ("RQ_HOSTWIN_BYTES", self.HOSTWIN_BYTES),
            # the put network (specs/cross-core/02 §8.1): system nodes and the cross-core inbox
            ("RQ_RF_WINDOW", self.RF_WINDOW), ("RQ_GROUP_NODE0", self.GROUP_NODE0),
            ("RQ_BARRIER_NODE0", self.BARRIER_NODE0), ("RQ_INBOX_NODE0", self.INBOX_NODE0),
            ("RQ_GROUPS", self.GROUP_NODES), ("RQ_BARRIER_IDS", self.BARRIER_IDS),
            ("RQ_INBOX_BOARD", self.INBOX_BOARD), ("RQ_INBOX_RELEASE", self.INBOX_RELEASE),
            ("RQ_INBOX_MAILBOX0", self.INBOX_MAILBOX0), ("RQ_MAILBOX_NUM", self.MAILBOX_NUM),
            ("RQ_SINK_BASE", self.SINK_BASE), ("RQ_SINK_STRIDE", self.SINK_STRIDE),
        ]
        if cs.find("gate") is not None:
            defines.append(("RQ_GATE_PULSE_NUM", cs.channel("gate").slots))
        defines += [
            ("RQ_ENV_DEPTH", min(ch.env_depth for ch in cs.channels)),
            ("RQ_LEAD", LEAD), ("RQ_RO_LEAD", READOUT_LEAD),
            ("RQ_RO_MAX_WIN", 1 << READOUT_MAX_WIN_LOG2),
        ]
        # the comment names the CHANNEL LIST, not the core: two cores with the same channels get
        # a byte-identical header, so build.compile_c's cache compiles their programs once.
        lines = [f"/* GENERATED from SocSpec '{p.name}' (channels: "
                 f"{', '.join(ch.name for ch in cs.channels)}) by riscq.map — do not edit. */",
                 "#ifndef RISCQ_MAP_H", "#define RISCQ_MAP_H", ""]
        lines += [f"#define {name} 0x{value:x}" for name, value in defines]
        # per-core channel bases (spec 02 §3.2): a ParamTable folds to its RF_CHi; by name for hand-written C
        lines += [f"#define {ch.cname} 0x{ch.base:x}" for ch in chans]
        lines += [f"#define RQ_CH_{ch.name.upper()} 0x{ch.base:x}" for ch in chans]
        # the core's up-link sinks (result: res@+0/real@+4/imag@+8; fifo: pop@+0 ... count@+0x18)
        lines += [f"#define RQ_SINK_{name.upper()} 0x{base:x}" for name, _, base in self.sinks(core)]
        lines += ["", "#endif", ""]
        return "\n".join(lines)

    def gen_linker(self) -> str:
        """link.ld — one unified RAM region; .text first so entry = 0x80000000; 1 KB stack reserve."""
        return f"""\
/* GENERATED from SocSpec '{self.params.name}' by riscq.map — do not edit. */
ENTRY(_start)
MEMORY {{ RAM (rwx) : ORIGIN = 0x{MEM_BASE:x}, LENGTH = {self.mem_bytes} }}
SECTIONS {{
  .text   : {{ KEEP(*(.text.init)) *(.text*) }} > RAM
  .rodata : {{ *(.rodata*) *(.srodata*) }} > RAM
  .data   : {{ *(.data*) *(.sdata*) }} > RAM
  .bss (NOLOAD) : {{
    . = ALIGN(4);
    __bss_start = .;
    *(.bss*) *(.sbss*) *(COMMON)
    . = ALIGN(4);
    __bss_end = .;
  }} > RAM
  /DISCARD/ : {{ *(.eh_frame*) *(.comment) *(.riscv.attributes) }}
  _end = .;
  __stack_top = ORIGIN(RAM) + LENGTH(RAM);
  ASSERT(_end <= __stack_top - 1024, "riscq: program overflows RAM (1 KB stack reserve)")
}}
"""
