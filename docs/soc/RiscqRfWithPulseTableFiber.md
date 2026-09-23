# RiscqRfWithPulseTableFiber — one core and its channel list

**Source:** `src/riscq/soc/RiscqRfWithPulseTableFiber.scala` · **Package:** `riscq.soc` · **Type:** Area
(case class, instantiated per core by [`PulseTableSoc`](PulseTableSoc.md))

One core: a RISC-V control core + its private I/D RAM + the CPU control block, plus a converter-edge DSP
datapath built from the core's [`CoreSpec`](SocSpec.md) **channel list**, reached over the **narrow posted
link**. `PulseTableSoc` instantiates one per core of the spec. Read [`ARCH.md`](ARCH.md) for the link
rationale.

## Structure

After the registered-boundary floorplan carve-out this is a **thin shell** around two pieces split at the
posted link's already-registered seam:

```
   ┌──────────── RiscvSoc (hard Component, pinned to X0) ────────────┐
   │  RiscqFiber core + I/D RAM + control block (time/done)          │
   │  + PutBridge (acks CPU stores locally) + the event sinks     │
   └──── cmd: Flow(Put) ─┬────────────────────────  resultIn ◀─────┘
                           │ getPipe(linkPipe)                 │ getPipe(linkPipe)
        ┌──────────────────┴── posted (dspCd, datapath in X1–X5) ────┴──────────┐
        │  PutLink.demux ─┬─ channel 0 @0x00000 ─┐                               │
        │                ├─ channel 1 @0x10000  ├─ mkChannel(spec.channels(k), k)│
        │                └─ channel k @k·0x10000┘                                │
        │                   decoder (ReadoutDecoder) ◀─ demod carrier ─┐         │
        │  <channel>MemFiber  (host envelope BRAMs, one per banked channel)      │
        │  EventLink.merge(reporters) ─────────────────────────────────┴▶ up-link│
        └────────────────────────────────────────────────────────────────────────┘
```

- **`riscvSoc`** — the [`RiscvSoc`](RiscvSoc.md) hard `Component`: the timing-critical RISC-V core
  ([`RiscqFiber`](RiscqFiber.md)) + real BRAM/UltraRAM I/D RAM + the CPU-mapped control block + the
  [`PutBridge`](PutBridge.md) (which acks every CPU RF store locally in one cycle) + the core's
  [event sinks](EventLink.md). Its narrow registered I/O is `time` in, `cmd: Flow(Put)` out,
  `resultIn: Flow(Put)` in (puts into the inbox), `hostCmd` out, `done` out (plus `iLoad`/`dTap` slave-IO). The
  `0x10000` put window and `0x80000000` data-RAM maps are all inside it.
- **The shell** applies the `linkPipe` `RegNext` stages each way (`getPipe(riscvSoc.cmd, linkPipe)` down,
  `getPipe(upSrc, linkPipe)` up) and demuxes `cmd` to the channels. Everything past the pipe — the demux,
  the channels, the decoder, the envelope BRAMs, dac/adc — lives **here** (the parent), not in
  `RiscvSoc`, so the core can be floorplanned far from the converters.

### The RF datapath (`posted`, dspCd) — one channel per spec entry

The piped `cmd` stream is fanned by [`PutLink.demux`](PutLink.md) to one `0x10000`-wide sub-window per
channel: channel `k` owns `k · 0x10000` of the core's put window — node `k` of
`putAddrWidth = SocSpecMap.putAddrWidth = 28` ([`SocSpecMap`](SocSpec.md)); nodes past the channel count
stay unmapped here (the decoder has no CPU-facing registers) and nodes `≥ 16` are system puts for the
board hub ([`PutLink.nonLocal`](PutLink.md)). `mkChannel` is the one place a kind is named:

| `kind` | Block | Datapath |
|---|---|---|
| `pulse` | [`PulseDriveChannel`](RfChannels.md) | `batchSize` lanes, `realOutput = true` → a DAC |
| `demod` | [`DemodChannel`](RfChannels.md) | `adcBatch` lanes, complex → the decoder's carrier |
| `dio` | [`TimedDio`](TimedDio.md) | no bank, no converter → the board's DIO port pair |

Every channel is built with the core's `queueDepth`, its slot count (`slots`) and its bank's own
`envAddrWidth = log2Up(envDepth)`, and is `setCompositeName`d `<channel>Channel`. Each wraps a
[`PulseParamBuffer`](PulseParamBuffer.md) (the DSP-side register file driven by the demuxed
`Flow(Put)`), which owns the per-buffer `startTime` (the software contract from [`ARCH.md`](ARCH.md));
`io.timeBcast` takes the shared `time` broadcast. The drive generators run with `realOutput = true` — the
DAC carries only the real lane, so the imaginary cone is tied off inside the generator and synthesis
prunes the dead DSPs (see [`SOC_TIPS.md`](SOC_TIPS.md) §7.7).

**Exactly one `demod` channel per core** (a `require` — one decoder, one ADC). Its complex `carrier` Flow
feeds `decoder.io.carrier` through one register stage, so a scheduled, envelope-weighted matched filter
replaces the old free-running LO — and the carrier's `valid` **is** the decoder's integration window
([specs/new-readout-decoder](../../specs/new-readout-decoder/README.md)): the envelope batch `env[base+i]`
weights integrated batch `startTime+i` by construction (verified bit-exact by `DemodDecoderSim`).

### The up-link

[`EventPlan(spec)`](EventLink.md) says which channels report, with which sink kind and payload width; the
shell collects their `EventSource`s **in the plan's order**, serialises each into puts at its sink's offsets, merges them onto one `Flow(Put)` and pipes
it `linkPipe` stages into `RiscvSoc`'s sinks. The demod's source is built here from the decoder
(`EventLink.resultSource`) — it is the one kind whose reporter needs the ADC; every other reporting kind
supplies its own `Channel.event`. The readout result therefore still reaches the CPU as a latched level
(`real`, `imag`, then `{valid, res}` puts; the level rebuilt at the sink), so the halting `res` read is unchanged and still a short
local arc, not a round-trip across the gap.

### Host-writable complex pulse-envelope RAM

One [`BramWriteFiber`](BramWriteFiber.md) bank per channel **that has one** — `envMems` is an
`Option` per channel, and a bank-less kind (`dio`, `env_depth = 0`) gets `None` and leaves its host-map
slot a hole. Each is named after its channel, `<channel>MemFiber` (`gateMemFiber` / `roMemFiber` / `demodMemFiber` on
the qubit builds) and is **write-only** on the host side, since the host only ever loads it (no read-back),
which drops the read-reorder buffer and lets each 32-bit host beat steer straight into a sub-word lane of
the wide line (no fabric `WidthAdapter`). They are host-written through the host→dsp CDC.

A bank stores the **interpolated** line: `envWidth = lanes·2·w / interp` (a drive channel's full line is
`batchSize·2·w` = 512 bits, the demod's `adcBatch·2·w` = 128), which shrinks the widest BRAM banks. On
read, `expandEnv` reconstructs the full per-lane batch from the interpolated line (each output lane reads
the `m/interp`-th stored sample) over the channel's own `envLanes`. Each channel's `interp` must divide
its lane count.

### Host image load, host window & test tap

`iMemPortArb` re-exposes `RiscvSoc`'s `iLoad` slave-IO as a fabric node so the toplevel's host AXI fabric
loads the program/data image into the BRAM slow port; this node carries the **host→dsp clock crossing**,
which lands on the fabric arc *outside* the hard `RiscvSoc` Component (so it stays out of the per-core
pblock). The fabric cannot cross a hard Component boundary, so a `bridgeLoad` helper wires the (narrower)
host master onto `RiscvSoc`'s generous fixed `iLoad` param by resizing the per-top-varying fields — which
keeps `RiscvSoc` byte-identical across tops (the floorplan-transfer requirement, see [`RiscvSoc`](RiscvSoc.md)).
`dMemTap`/`dMemPortDec` mirror this for the `withTestTap` sim path (null in the real SoC).

The core's posted result stream crosses dsp → host here too: `hostWinFifo` (a `StreamFifoCC`, push side in
`dspCd` — *not* `riscqCd`, whose per-run reset would desync it, [`SOC_TIPS.md`](SOC_TIPS.md) §8.8) takes
`riscvSoc.hostCmd` to the shared [`HostWindowFunnel`](HostWindow.md) in `hostCd`. The sticky `done` level
leaves as a plain port for the toplevel's `BufferCC`.

## Exported handles

Addressed by channel **name or kind, never by position**: `channel(name)` (the built `Channel`),
`dacPulses` (the DAC-bound channels' `Flow(ComplexBatch)` outputs in list order — the index the SoC's
`(core, k) → dac` map uses), `tracePulses` (the `trace: true` channels, which drive the shared `robs`
trace), `dios` (the `(ChannelSpec, TimedDio)` pairs the toplevel wires to board ports), `decoderRd` (the
decoder), `envMems`, and `dac`/`adc` (the real-lane converter nets). Three qubit-build conveniences the
sims observe — `gatePulse`, `readoutPulse` and `startTime` (the `gate` buffer's schedule value) — resolve
by name, so they raise at elaboration on a build without those channels.

## Key parameters

- **`spec`** — the core's [`CoreSpec`](SocSpec.md): its channel list, `memDepth`, `withMul`, `queueDepth`.
- **`plugins`** — the RISC-V plugin config (from `PulseTableSoc.coreParam`, `withMul` per core).
- **`linkPipe`** (default 4) — per-direction `RegNext` depth of the posted link.
- **`batchSize` / `adcBatch` / `dataWidth` / `durWidth` / `timeWidth`** — the datapath widths the channel
  kinds are built at.
- **`readoutAccWidth`** — the decoder's accumulator width (32 = one-word readback; the window-length
  bound is the decoder's `maxWinLog2` no-overflow contract, enforced in software on the demod `dur`).
- **`hostWinAddrWidth`** (24 ⇒ 16 MB) — the per-core [host window](HostWindow.md).
- **`time`** — the shared batch-time replica, passed in from the toplevel.

## Verification

No standalone sim — verified through the toplevel sims (`PulseTableSocSim`, `PulseTableSocCpuSim`) and the
posted-link building-block sims (`PulseParamBufferSim`, `PutBridgeSim`, `ReadoutResultLinkSim`,
`EventSinkSim`, `TimedDioSim`). Multi-channel cores are gated in co-sim by
`software/tests/test_multichannel.py` (the `sim-mm` build) and `software/tests/test_dio.py` (`sim-dio`).
See [`PulseTableSoc`](PulseTableSoc.md) for the commands.

## Related

- [`RiscvSoc`](RiscvSoc.md) — the hard, registered-boundary core unit this wraps.
- [`ARCH.md`](ARCH.md) — the posted-link architecture (why the split is at the registered seam).
- [`SocSpec`](SocSpec.md) — the `CoreSpec` channel list this shell is built from.
- Posted link: [`PutBridge`](PutBridge.md) · [`PutLink`](PutLink.md) · [`EventLink`](EventLink.md).
- Datapath: [`RfChannels`](RfChannels.md) · [`TimedDio`](TimedDio.md) ·
  [`PulseParamBuffer`](PulseParamBuffer.md) · [`PulseGenerator`](../dsp/PulseGenerator.md) ·
  [`ReadoutDecoder`](../dsp/ReadoutDecoder.md).
