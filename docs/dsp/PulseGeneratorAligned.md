# PulseGeneratorAligned — QubiC-style SRL-aligned pulse generator (variant)

**Source:** `src/riscq/dsp/pulse/PulseGeneratorAligned.scala` · **Generate:** `riscq.dsp.pulse.PulseGenAligned`
· **Package:** `riscq.dsp.pulse` · **Type:** Component

A drop-in alternative to [PulseGenerator](PulseGenerator.md) with the **same datapath and bit-exact
output**, but a different *parameter-alignment* scheme borrowed from the QubiC `element`/`ammod`. It
exists to A/B the resource/fmax cost of the two alignment styles; the datapath sub-blocks
([PhasorBatchGenerator](PhasorBatchGenerator.md), [CarrierBatchGenerator](CarrierBatchGenerator.md),
[EnvelopeReader](EnvelopeReader.md), the N envelope [ComplexMul](ComplexMul.md), the duration gate) are
identical.

## How it differs from PulseGenerator

| | [PulseGenerator](PulseGenerator.md) | PulseGeneratorAligned |
|---|---|---|
| Alignment | **one [TimedQueue](TimedQueue.md) per parameter** (six), each popped at `startTime − leadₓ` | the four co-fired parameters `{amp, phase, addr, dur}` share **one TimedQueue**, popped once at the *latest* of their leads, `maxLead` |
| Per-parameter timing | the queue itself delivers each parameter at its lead | each parameter is realigned with a static **SRL delay** `maxLead − leadₓ` (the QubiC `reg_delay1`); one shared valid shift register (the QubiC `busy_sr`) is tapped at those depths |
| Parameter interface | five separate `Flow`s | one combined `io.params` Flow (`{amp,phase,addr,dur}`, one shared valid) + `io.freq` |
| Queues vs SRLs | 6 queues (six 32-bit registered time compares) | 1 combined queue + 2 freq queues + SRLs |

`freq` keeps its **own separate queues** in both designs (it fires on a different cycle than the table
entry and drives two consumers — phasor regen and carrier — at very different latencies; QubiC also
routes the frequency word on a separate, longer-latency path). The narrow-vs-wide SRL choice mirrors
QubiC: `addr`/`dur` are delayed as narrow SRLs (`addrWidth`/`durWidth` bits), the cheap side, the same
way QubiC delays the envelope **address** rather than the 512-bit envelope data.

## Why it is bit-exact

Every consumer still sees its parameter at exactly `startTime − leadₓ`: the combined queue pops at
`startTime − maxLead`, and the per-parameter SRL adds back `maxLead − leadₓ`. So the carrier / envelope
/ gate inputs are timing-identical to [PulseGenerator](PulseGenerator.md), the output pulse is bit-exact,
and the same [`PulseGeneratorSim`](PulseGenerator.md) golden applies. Only the alignment hardware differs
(3 queues + SRLs here vs 6 queues there) — which is the resource/fmax question the variant lets you
measure. The combined fire (all four parameters in one entry) also matches what the real
[PulseParamBuffer](../soc/PulseParamBuffer.md) fire path does: a table fire always carries all four
co-fired parameters.

## When it exists / how to choose

A variant kept for evaluation, not the production default — the SoC uses
[PulseGenerator](PulseGenerator.md). Reach for `PulseGeneratorAligned` only when measuring the alignment
trade (queue count vs SRL chains) for a given floorplan; the `AlignedComparisonBench` (below) reports
the side-by-side diff so you can pick on real numbers.

## Interface & configuration

Same `PulseGeneratorParams` as [PulseGenerator](PulseGenerator.md) (so `saturate`/`prescaleAmp`/
`phasorMethod`/etc. behave identically). The only interface change: parameters arrive as one
`io.params : Flow(AlignedPulseParams)` (`{amp, phase, addr, dur}`) plus a separate `io.freq` Flow,
instead of five separate parameter Flows.

## Usage / verification

```bash
mill runMain riscq.dsp.pulse.PulseGenAligned                      # emit Verilog
mill runMain riscq.dsp.pulse.sim.PulseGeneratorAlignedSim         # end-to-end golden (reuses PulseGeneratorSim's)
# side-by-side resource + fmax vs the 6-queue PulseGenerator (same config, only alignment differs):
mill runMain riscq.dsp.pulse.bench.AlignedComparisonBench                       # synth (fast, optimistic)
RISCQ_CMP_ROUTE=true mill runMain riscq.dsp.pulse.bench.AlignedComparisonBench  # post-route (sign-off)
```

`PulseGeneratorAlignedSim` reuses the [PulseGenerator](PulseGenerator.md) golden verbatim (bit-exact by
construction): same composed golden, same exact `[startTime, startTime+dur)` window, same
zeroed-between-pulses checks. `AlignedComparisonBench` synthesises both DUTs out-of-context against a
tight clock and prints the fmax + `report_utilization` diff (LUT / SRL / FF / CARRY8 / DSP / BRAM).

## Related

- [PulseGenerator](PulseGenerator.md) — the production default this variant mirrors
- [TimedQueue](TimedQueue.md) · [CarrierBatchGenerator](CarrierBatchGenerator.md)
  · [PhasorBatchGenerator](PhasorBatchGenerator.md) · [EnvelopeReader](EnvelopeReader.md)
- SoC fire path: [PulseParamBuffer](../soc/PulseParamBuffer.md) · QubiC comparison: [QUBIC_DATAPATH_COMPARISON](../soc/QUBIC_DATAPATH_COMPARISON.md)
