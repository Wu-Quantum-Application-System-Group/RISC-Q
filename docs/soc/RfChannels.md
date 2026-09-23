# RfChannels — the `Channel` contract and its `PulseDriveChannel` / `DemodChannel` kinds

**Source:** `src/riscq/soc/rf/Channel.scala`, `src/riscq/soc/rf/RfChannels.scala` ·
**Package:** `riscq.soc.rf` · **Type:** trait (`Channel`) + Components

The two self-contained converter-edge boxes the floorplan pins. Each binds a DSP datapath block to a
DAC/ADC edge and exposes a single CPU-facing input — the demuxed posted `Flow(Put)` plus the shared
`time` broadcast — so the core can be floorplanned far from the converters. They are thin wiring: a
[PulseParamBuffer](PulseParamBuffer.md) driving a [PulseGenerator](../dsp/PulseGenerator.md) — the drive
channels route the pulse to a DAC, the demod channel routes it to the decoder as the carrier. Every
channel's buffer inherits the spec 09 B0 `startTime` **auto-advance** and its one-stage export (see
[PulseParamBuffer](PulseParamBuffer.md)), so a contiguous pulse train on any channel is one `play` + N−1
bare `fire`s while absolute timing stays bit-identical.

## `Channel` — what the shell instantiates a kind through

Every converter-edge channel kind implements this one trait, and the core shell
([RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md)) builds a core's channel list through it,
naming a kind nowhere but its one `mkChannel` dispatch:

| Member | What |
|---|---|
| `cmd: Flow[Put]` | the channel's demuxed posted sub-window (its only CPU-facing input) |
| `timeBcast: UInt` | the shared batch-time broadcast |
| `memPort: Option[MemReadPort[Bits]]` | the envelope-RAM read port — `None` for a kind with no bank |
| `envLanes: Int` | lanes a stored envelope line expands to (0 = no bank) |
| `dacOut: Option[Flow[Vec[Complex]]]` | the DAC-bound output, for drive kinds |
| `carrier: Option[Flow[Vec[Complex]]]` | the decoder-bound carrier, for the demod kind |
| `event: Option[EventSource]` | the channel's up-link reporter, for kinds that own one — the shell serialises it into inbox puts ([EventLink](EventLink.md)) |

`PulseDriveChannel` and `DemodChannel` below implement it; [TimedDio](TimedDio.md) (`kind: "dio"`) is the
third kind — no bank, no converter, and a `fifo`-kind reporter. The demod's reporter is the one built by
the shell rather than by the channel, since its source is the decoder the carrier feeds.

## `PulseDriveChannel` — a gate/readout drive bound to a DAC

```
  Flow(Put) ─▶ PulseParamBuffer ─(phase + phaseOffset)─▶ PulseGenerator ─(re + dcOffset)─▶ io.pulse → DAC
  time bcast  ─▶                                                          ─▶ io.memPort (external envelope RAM)
```

A [PulseParamBuffer](PulseParamBuffer.md) + a [PulseGenerator](../dsp/PulseGenerator.md) + its
envelope-RAM read port, packaged into one Component. Its only CPU-facing input is the `Put` Flow; it
emits the DAC `pulse` and forwards an external envelope-memory `MemReadPort` (the host-writable envelope
RAM lives outside the channel, in [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md)). Used twice
per qubit core: gate drive (`pulseNum` several) and readout drive (`pulseNum = 1`).

**Two memory-mapped biases wrap the generator** (both held in the buffer, applied here):

- `phaseOffset`@0xC (virtual Z) is added **combinationally** to the generator's phase input — the phase
  `Flow` stays cycle-aligned with amp/addr/dur, and the add wraps modulo 2^w, which is exactly a phase
  rotation (a full turn = 2^w).
- `dcOffset`@0x8 is added in **one `RegNext` stage** to every real output lane on the way out (the imag
  lane and `valid` registered alongside to keep the batch aligned).

Both default to 0; when both are 0 the emitted pulse is bit-identical to the un-packaged composition, and
the `dcOffset` register stage costs the whole channel one extra output cycle of latency.

`realOutput` is set `true` here — only the real lane reaches the DAC, so the generator ties its imaginary
output off internally, letting synthesis prune the dead imag multiply (the DSP-saving reason is in
[SOC_TIPS](SOC_TIPS.md) §7.7).

**`useAligned` variant.** With `useAligned = false` (default) the channel drives a `PulseGenerator` whose
parameters each ride their own lead-time `TimedQueue`. With `useAligned = true` it drives
`PulseGeneratorAligned` — the QubiC-style single combined-params FIFO + SRL alignment — instead. The
emitted pulse is **bit-identical** either way; only the alignment hardware differs (a few percent of LUTs
vs a few percent of post-route fmax). See [QUBIC_DATAPATH_COMPARISON](QUBIC_DATAPATH_COMPARISON.md) §5.

## `DemodChannel` — the demod carrier

```
  Flow(Put) ─▶ PulseParamBuffer ─(phase + phaseOffset)─▶ PulseGenerator ─▶ io.carrier → ReadoutDecoder
  time bcast  ─▶                                                          ─▶ io.memPort (external envelope RAM)
```

The demod carrier is a **scheduled, envelope-shaped complex pulse** — a `PulseDriveChannel` pointed at the
decoder instead of a DAC. Same building blocks as a drive channel ([PulseParamBuffer](PulseParamBuffer.md)
+ [PulseGenerator](../dsp/PulseGenerator.md) + an envelope-RAM read port), so the demod carrier gets the
same host-writable envelope, `freq`/`phase` table and `startTime` scheduling. It runs at `adcBatch` lanes
(the ADC batch) and exposes `io.carrier` — a **`Flow(ComplexBatch)`**, the generator's pulse payload *and*
valid — for the paired [ReadoutDecoder](../dsp/ReadoutDecoder.md).

**Firing this channel is the readout.** The carrier `valid` (high for exactly the scheduled window
`[startTime, startTime+dur)`, payload zeroed outside) **triggers the decoder**: its rising edge restarts
the integration, its falling edge completes it — see
[specs/new-readout-decoder](../../specs/new-readout-decoder/README.md). Software programs a (typically
square) matched-filter envelope once and plays the demod pulse where the readout tone returns; the
envelope weights each demodulated batch, and the decoder integrates `Σ adc·env·carrier` over exactly the
demod window. There is no separate decoder arm and no cross-channel `startTime` to align.

Two deliberate differences from `PulseDriveChannel`: `realOutput = false` (the decoder needs the full
complex carrier), and **no `dcOffset` output stage** (a DC bias is meaningless on a demod carrier) — so
`io.carrier` is the raw generator output, one fewer register than the drive path, keeping the carrier↔time
alignment (`timeToPulse`) equal to the generator's. The `phaseOffset`@0xC (virtual Z) is kept: it is the
**live demod-phase tune** (no re-fire needed).

## Interface & configuration

Both are parameterized by the datapath widths (`batchSize`, `dataWidth`, `timeWidth`, …), the pulse-table
shape (`pulseNum`, `envAddrWidth`, `durWidth`, `memLatency` — the `PulseParamBuffer` storage style is
chosen from `pulseNum`) and the DSP options
(`prescaleAmp`, `saturate`, `phasorMethod`); `PulseDriveChannel` additionally has `realOutput` and the
`useAligned` scheduler A/B, which `DemodChannel` fixes (`realOutput = false`, no `dcOffset`). Both use the
`PulseParamBuffer` RF layout (`fire`@0, `freq`@4, `phaseOffset`@0xC, `table[i]`@`(i+1)*0x10`,
`startTime`@0x4100). `RiscqRfWithPulseTableFiber`'s `posted` area builds one channel per
`ChannelSpec` (its `mkChannel` dispatch on `kind`), each off its own demuxed RF sub-window, and connects
the envelope RAMs.

## Verification

`PulseDriveChannelSim` signs off the `phaseOffset` virtual-Z path directly: it fires one pulse three ways
on a channel — `(phase P, offset 0)`, `(phase P+Δ, offset 0)`, `(phase P, offset Δ)` — and asserts the
offset shot is **bit-identical** to folding Δ into the table phase (the add lands exactly on the phase
input, wrapping mod 2^w) and differs from the Δ=0 baseline (the rotation is not a no-op). Otherwise the
channels are thin packaging, signed off transitively: [PulseParamBuffer](PulseParamBuffer.md) by
`PulseParamBufferSim` (the register file bit-exact vs the generator golden, plus the `dcOffset`/`phaseOffset`
register read-back), the generators by the DSP goldens (`PulseGeneratorSim` / `PulseGeneratorAlignedSim` /
`ReadoutDecoderSim`). The **envelope-shaped demod path** is signed off by `DemodDecoderSim`: it wires a
`DemodChannel` into a `ReadoutDecoder`, programs a non-trivial envelope + schedule, and checks the readout
integral **bit-exact** vs the composed golden `Σ adc·(env·carrier)` over `[St, St+dur)` — the carrier's
own valid window, which triggers the decoder — and, since each batch differs, simultaneously pins the
demod→decoder alignment (a +1-shifted-window cross-check confirms the composition stays sharp). The
assembled channels are covered end-to-end by `PulseTableSocSim` (scheduled gate pulse through the `dacMap`
to the DAC; VNA-style readout through the scheduled demod + decode) and `PulseTableSocCpuSim`
(CPU-in-the-loop).

```bash
mill runMain riscq.soc.sim.PulseDriveChannelSim
mill runMain riscq.soc.sim.PulseParamBufferSim
mill runMain riscq.soc.sim.DemodDecoderSim
mill runMain riscq.soc.sim.PulseTableSocSim
```

## Related

- [PulseParamBuffer](PulseParamBuffer.md) — the register file inside `PulseDriveChannel`.
- [PulseGenerator](../dsp/PulseGenerator.md) / [ReadoutDecoder](../dsp/ReadoutDecoder.md) — the DSP blocks bound here.
- [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md) — instantiates the channels and the envelope RAM.
- [QUBIC_DATAPATH_COMPARISON](QUBIC_DATAPATH_COMPARISON.md) — the `useAligned` scheduling A/B.
