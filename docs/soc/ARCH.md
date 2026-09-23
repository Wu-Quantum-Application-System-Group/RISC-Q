# SoC architecture — the narrow posted-link RF tree

This is the conceptual entry point for `riscq.soc`. It explains the one architectural decision the whole
SoC is built around: the RF tree between each RISC-V core and its converter-edge DSP is a **narrow,
unidirectional, posted, pipelined link**, so the cores can be floorplanned **far from the DAC/ADC
converter edge**. That frees the converter edge for the resource-hungry [`PulseGenerator`](../dsp/PulseGenerator.md)s
and breaks the timing wall you hit when 14 cores and 14 DSP datapaths fight for the same clock regions.

The toplevel that assembles this is [`PulseTableSoc`](PulseTableSoc.md); each core is a
[`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md) built from its **channel list**
([`SocSpec`](SocSpec.md)); the board wrapper is [`Zcu216Top`](Zcu216Top.md).

---

## The problem

The RISC-V core closes ~590 MHz standalone, but a naïve multi-qubit SoC does not. The binder is the
**core crammed next to the converter edge**: with a conventional bus, every core↔DSP control path is a
same-clock arc, so the core *must* sit beside the DSP it talks to. Pack 14 cores plus 14 DSP datapaths
into the converter-edge clock regions and routing congestion adds ~1 ns over the standalone core.

To move a core away, the **core↔DSP wire must tolerate physical distance**. A conventional
`tilelink.fabric` connection cannot, for two reasons:

1. **It is wide.** A full decode tree carries the a-channel (opcode/param/size/source/address/mask/data)
   *plus* a d-channel return, *plus* the per-peripheral `SlaveFactory` and the shared read-mux trees.
2. **It is bidirectional and back-pressured.** Reads halt the bus; writes wait for an ack. Pipelining it
   to span distance lengthens a *round-trip*, and a halting read across a long pipe stalls the CPU for
   the whole there-and-back.

The fix is to make the wire **narrow, one-way, and posted** so it can be pipelined to any length without
back-pressure.

## The key enabler — lead-time scheduling decouples distance from timing

Why this is correct at all: the **CPU is a lead-time scheduler, not a real-time driver**. It writes a
pulse-table entry and a `startTime = time + lead`, then "fires" by writing the entry index; the DSP's
[`TimedQueue`](../dsp/TimedQueue.md) latches `startTime` at enqueue and emits the pulse on the exact
`dspClk` cycle `time == startTime`. The *arrival time of the enqueue is irrelevant* as long as it lands
before `time` reaches `startTime` — i.e. `lead > link latency`.

So a long, pipelined, posted path from core to DSP costs only a **constant, predictable latency that the
lead-time absorbs**. Nothing on that path is timing-critical; every stage is a plain `RegNext`. This is
the property the whole architecture rests on.

## What crosses the gap

Three pipelined, unidirectional, timing-insensitive `Flow`s cross between a core and its DSP, per core:

| Bundle | Dir | Contents | Pipelinable because |
|---|---|---|---|
| **time** | down | the 32-bit batch-time counter, broadcast to all buffers with equal delay | continuous; lead-time absorbs constant delay |
| **put** (`Put`) | down | `{address, data}` — one posted register write into a channel (table / `freq` / `startTime` / fire) | posted, no ack, single ordered path |
| **put** (`Put`) | up | `{offset, data}` — one word written into the core's inbox: a channel's report serialised (the readout result, a DIO edge, …) or the board hub's re-puts (group words, barrier releases, signals — [PutHub](PutHub.md)) | posted, polled locally; no round-trip |

This is roughly half the wires of a TileLink a+d, one-way, and it sheds the fabric decode/arbiter logic
at the converter edge entirely. The building blocks:

- [`PutBridge`](PutBridge.md) sits next to the core: a tiny write-only TileLink slave over the put
  window that **acks every CPU store locally in one cycle** (so the core's bus arc stays short and in the
  core region) and emits one ordered `Flow(Put)` downstream.
- [`PutLink`](PutLink.md) `pipe`s the stream (`linkPipe` `RegNext` stages) and `demux`es it to one
  `0x10000` sub-window per entry of the core's `spec.channels` (pure combinational routing — a `Flow` has
  no back-pressure, so no arbiter).
- [`PulseParamBuffer`](PulseParamBuffer.md) is the DSP-side register file for one channel, driven by
  the demuxed `Put` instead of a `SlaveFactory`. It is the only thing that must sit at the converter
  edge with the datapath block. A **channel kind** packages the two behind one
  [`Channel`](RfChannels.md) interface the shell instantiates through:
  [`PulseDriveChannel`](RfChannels.md) (`pulse`, DAC-bound), [`DemodChannel`](RfChannels.md) (`demod`,
  the decoder's carrier) and [`TimedDio`](TimedDio.md) (`dio`, 16 timed output + 16 timestamped input
  lines).
- [`EventLink`](EventLink.md) carries every reporting channel's events up the return `Flow` into
  core-local sinks the CPU polls — the readout result is one kind (a latched level rebuilt from
  settled/cleared beats), a DIO edge another (a consume-on-read FIFO with the cause time) — so the
  halting `res` / `pop_event` read is a short local arc, not a long round-trip.

## The `startTime` software contract

`time` is broadcast down the link, but **`startTime` is per-buffer** — written by that buffer's own
`Put` stream (`startTime` folded into the RF address map), not broadcast. This is deliberate: it puts
`startTime` and the `outId` fire on the **same ordered posted path**, so the fire always enqueues exactly
the `startTime` just written — no cross-path race between a separately-broadcast `startTime` and a
separately-posted fire. Within one generator, `Put`s are an ordered stream (single posted path), so
table writes precede their fire as the program intends. Cross-generator order is not guaranteed, but the
pop is by `startTime` *value*, not arrival, so it doesn't matter for the rise cycle.

The price is that the **same-cycle-rise guarantee moves from hardware to software**: for two channels
(e.g. gate drive + readout drive) to rise on the same output cycle, software writes the *same* `startTime`
value to both buffers. Hardware backs this with one structural requirement — every buffer's local `time`
copy has **equal pipeline delay** (one regional pipe → per-buffer `RegNext`, low fanout). Equal values
plus equal delay ⇒ same cycle. The SoC sim / scheduling software must write both `startTime`s.

**`startTime` also auto-advances on every fire** (spec 09 B0): a fire adds the fired entry's `dur` to that
buffer's `startTime`, so a contiguous pulse train is one `set_start` + N−1 bare fires (an explicit
`set_start` on the beat right after a fire overrides that increment — beat-order, absolute wins). The
generator-facing export gained one **uniform** `RegNext` stage so each fired pulse captures `startTime` as
of its own fire beat (pre-increment); because that stage is identical in every buffer and the `TimedQueue`
still pops by the *captured* `startTime` value, absolute pulse timing stays bit-identical for all existing
`set_start`+`fire` software — the same-cycle-rise contract above is unchanged.

The constant link delay `D` makes the core's `time` copy read `dspTime − D`, so the CPU's
`startTime = localTime + lead` fires at `dspTime + (lead − D)` — a constant, predictable effective lead.
The invariant: `lead − D > down-link latency`. `waitTimeCmp` gains a few cycles of jitter, which is
acceptable — real-time precision lives in the DSP `TimedQueue`, not the CPU wait.

## Read paths are split

Of every CPU `dBus` read, only the **channels' reports** cross the gap (on the up-`Flow`, into the core's
event sinks). Everything else is made core-local: the data RAM, the `time` copy and `timeCmp`/
`waitTimeCmp` (a local compare against the piped `time`) all live in the core region and never cross. The
control block that holds these is [`ControlMemMaps`](ControlMemMaps.md).

## `linkPipe`

`linkPipe` is the **per-direction `RegNext` depth** of the long haul (default 4). It is the one knob that
trades floorplan span for latency: raise it for a wider core↔converter floorplan, lower it if the cores
sit close. Because the link is timing-insensitive, more stages never hurt fmax — they only add to the
constant `D`, which bumps the software lead-time. The link is single-clock today; because it is posted
and narrow, a future clock split is a clean follow-on (each `Flow` crosses once with a 2-FF synchroniser,
the same pattern as the host↔dsp [`DualClockRamFiber`](DualClockRamFiber.md) crossing).

## The two-region floorplan

The link's whole purpose is to let the placer separate two regions on the `xczu49dr`:

- **Core region — column X0.** The left-edge column is **DSP-free** (and the only URAM column). Each core
  is pinned here, away from the converters: just `RiscvSoc` (core + I/D RAM + control block + bridge +
  result sink) and nothing else.
- **DSP region — columns X1–X5.** DSP-dense, tight against the converter edge (RFDAC/RFADC live on X5).
  Holds the per-qubit datapath: the pulse-drive channels, the demod carrier, the decoder, and the envelope
  RAMs.

The only thing spanning the two is the pipelined link, which can be as long as the die requires.

The decisive structural choice that makes this work is that [`RiscvSoc`](RiscvSoc.md) is a **hard
SpinalHDL `Component` with a narrow, registered I/O boundary** (the posted link). Earlier two-region
attempts failed because the core↔fabric net was a *combinational* arc into the core's hottest register,
so pulling the core off the datapath stretched a critical-path net. Cutting at the **already-registered
seam** (the posted link, with `RegNext` stages on both sides) makes `RiscvSoc` a *closed timing unit*:
its worst path is internal, and its placement is parent-independent. The hard boundary also keeps each
core a distinct synth macro (`KEEP_HIERARCHY`) so `opt_design` cannot merge the identical cores' shared
host-load logic into a MUXF7/F8 that straddles two per-core pblocks.

Two further levers fall out of this geometry:

- **Replicated batch clock.** A single broadcast `time` net across the X0 column costs ~0.12 ns once
  ≥3 cores share it. Instead each core gets its own `coreTime_i` register fed from the *same* `syncTime`
  (value-identical every cycle, zero skew, `EQUIVALENT_REGISTER_REMOVAL=NO` so Vivado cannot fold the
  replicas back into one net), pinned to its own band.
- **Hard datapath confinement.** Confining the whole datapath to an X1–X5 pblock — as a *routing* wall,
  not just a placement one — keeps datapath nets off the X0 column, so they cannot congest the cores'
  internal routing. (A strict *per-region* datapath confine is DSP-infeasible: a 3-qubit row needs more
  DSP than one clock-region row provides, so the realizable form is the global X1–X5 confine.)

The full ZCU216 build pins 14 cores at 3-per-row across the five live X0 rows (Y3–Y7). With this floorplan
the SoC reaches **dspClk WNS ≈ −0.156 ns (~484 MHz) OOC** on the `-2` part, worst path internal to a core;
with the put network's board hub ([PutHub](PutHub.md), four pipeline stages, in the floating region) the
same flow closes at **−0.111 ns**, worst path in the hub; the block-design flow (`vivado-scripts/riscvsoc-bd`,
`zcu216-14q.json`) meets **0.000 ns** after its incremental TimingClosure pass (`close-incremental.sh`).
fmax is a soft constraint for this project; routability and `dspClk` closure are the point. The floorplan
is baked by the per-flow `pblocks-*.tcl` in the Vivado flows — see [`Zcu216Top`](Zcu216Top.md) and
`../../vivado-scripts/README.md`.

---

## Document index — `riscq.soc`

- [`PulseTableSoc`](PulseTableSoc.md) — multi-qubit toplevel (host bridge, batch clock, channel maps).
- [`Zcu216Top`](Zcu216Top.md) — ZCU216 board wrapper(s) + the Vivado flows.
- [`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md) — one qubit (core + datapath over the link).
- [`RiscvSoc`](RiscvSoc.md) — the hard, registered-boundary core unit (the floorplan target).
- [`SocSpec`](SocSpec.md) — the build description (cores → channel lists) and the derived host map.
- Posted link: [`PutBridge`](PutBridge.md) · [`PutLink`](PutLink.md) ·
  [`EventLink`](EventLink.md) · [`PostedStoreShim`](PostedStoreShim.md).
- RF datapath: [`RfChannels`](RfChannels.md) · [`TimedDio`](TimedDio.md) ·
  [`PulseParamBuffer`](PulseParamBuffer.md) · [`ControlMemMaps`](ControlMemMaps.md).
- Fabric glue: [`RiscqFiber`](RiscqFiber.md) · [`TileLinkMemFiber`](TileLinkMemFiber.md) ·
  [`MemMapFiber`](MemMapFiber.md) · [`DualClockRamFiber`](DualClockRamFiber.md) · [`BramFiber`](BramFiber.md).
- [`SOC_TIPS.md`](SOC_TIPS.md) — SoC/fabric/SpinalSim gotchas (read before SoC work).
- [`QUBIC_DATAPATH_COMPARISON.md`](QUBIC_DATAPATH_COMPARISON.md) — datapath vs the QubiC reference.
- DSP it drives: [`PulseGenerator`](../dsp/PulseGenerator.md) · [`ReadoutDecoder`](../dsp/ReadoutDecoder.md)
  · [`TimedQueue`](../dsp/TimedQueue.md). Core: [`RISCV.md`](../riscv/RISCV.md).
