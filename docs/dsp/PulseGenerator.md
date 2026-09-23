# PulseGenerator — timed complex-envelope CORDIC pulse generator

**Source:** `src/riscq/dsp/pulse/PulseGenerator.scala` · **Generate:** `src/riscq/dsp/pulse/PulseGen.scala`
· **Package:** `riscq.dsp.pulse` · **Type:** Component (flat, no inner/outer split)

The flagship of the generate datapath. Plays a stored **complex envelope** modulated onto an
analytic carrier and emits it as a batch of DAC samples over a scheduled time window:

```
pulse[k](t) = env[N·t + k] · amp · exp( iπ·( freq·(N·t + k) + phase ) ),   k = 0 .. N−1
```

One tick of `io.time` = one **batch** of `N` (= `batchSize`, typ. 16) DAC samples, so the module
runs at DAC-rate / N. Number format is SF(w) throughout (see [DSP.md](DSP.md): an `SInt(w bits)`
value `x` denotes `x·2^-(w-1) ∈ [−1, +1)`; phase is in units of π, and plain two's-complement wrap is
an exact phase wrap).

## Role in the system

```
        amp/freq/phase/addr/dur  (Flows, each + io.startTime)
                  │
   io.time ─▶  PulseGenerator  ─▶ io.pulse : Flow(ComplexBatch(N,w))  ─▶ DAC channel
                  │ ▲
       io.memPort │ │ rsp
                  ▼ │
          host-written envelope RAM
```

In the SoC the parameter Flows are driven by [PulseParamBuffer](../soc/PulseParamBuffer.md) over the
posted link, `io.time` is the shared SoC batch clock, the envelope RAM is host-written, and
`io.pulse` feeds the converter-edge drive channel. See [PulseTableSoc](../soc/PulseTableSoc.md) and
[ARCH.md](../soc/ARCH.md). This component is a plain leaf `Component` (FiberPlugin/Tilelink
integration is the SoC's job); standalone it talks to an external `MemReadPort`.

## The two timing contracts (the heart of the design)

**1. Valid window — `[startTime, startTime + dur)` in batch units.** A duration down-counter is
loaded from the `dur` pop and counts down; `io.pulse.valid` is high while it is non-zero, and the
payload is **zero-muxed** to exactly 0 outside the window (a clean DAC zero between pulses). The
end-to-end sim asserts the window boundaries are exact.

**2. Lead-time scheduling — every parameter lands on the same output sample.** `io.time` is an
*external*, SoC-synchronized counter. Each parameter is pushed into its own [TimedQueue](TimedQueue.md)
together with `io.startTime`, and popped **early** — by exactly that parameter's pipeline latency to
the DAC output. So a parameter enters its consumer `startTime − leadₓ`, travels its pipeline, and
arrives at the DAC at `startTime`, simultaneously with every other parameter of the same pulse. This
is what lets the core be floorplanned far from the converters and still hit an exact sample.

The lead times are **derived, never literals.** In one place the toplevel sums the exported latencies
of every block on each parameter's path:

```
leadAmp   = carrierGen.ampLatency   + Lm + gate
leadPhase = carrierGen.phaseLatency + Lm + gate
leadFreqC = carrierGen.freqLatency  + Lm + gate
leadFreqP = phasorGen.regenCycles + carrierGen.phasorLatency + Lm + gate
leadAddr  = envReader.latency       + Lm + gate
leadDur   = gate + 1                            (Lm = ComplexMul.latency, gate = 1)
```

A future pipeline edit that shifts any sub-block's latency re-derives all six automatically, and the
sim *measures* each path and asserts it equals the derived constant — so a miscount **fails the sim**
instead of silently skewing a pulse by a sample. This replaces the RISC-Q reference's hand-counted
`shiftedTime(...)` constants, which nothing checked.

**Software contract** (per-buffer `startTime`): schedule each parameter with the `startTime` of the
pulse it belongs to; push in schedule order (queues are FIFO). Independent per-parameter queues mean
software can send a phase-only update (virtual-Z) or an amp-only change without re-sending the rest.

## Dataflow / sub-block composition

```
amp ──▶ TimedQueue ─┐
phase ▶ TimedQueue ─┼─▶ CarrierBatchGenerator ── carrier[k] ─┐
freq ─▶ TimedQueue ─┘        ▲ phasors                       │
freq ─▶ TimedQueue ──▶ PhasorBatchGenerator ────────────────┘
addr ─▶ TimedQueue ──▶ EnvelopeReader ── env[k] ─┐           ▼
dur ──▶ TimedQueue ──▶ duration gate             └─▶ N × ComplexMul ─▶ gate ─▶ pulse
```

- **[PhasorBatchGenerator](PhasorBatchGenerator.md)** holds `phasor[k] = Amax·exp(iπ·f·k)` and
  regenerates it (one shared CORDIC, serially over N cycles) on each `freq` update. Its `freq` queue
  lead covers the regen window, so the new phasors are ready before the scheduled start.
- **[CarrierBatchGenerator](CarrierBatchGenerator.md)** computes the per-batch time-dependent factor
  `amp·exp(iπ·gPhase)` with **one CORDIC** (amplitude rides the CORDIC `x` input — no separate amp
  multiply) and combines it with the static phasors via N [ComplexMul](ComplexMul.md). The carrier is
  factored as `exp(iπf(Nt+k)) = exp(iπfNt)·exp(iπfk)`, so only the N multiplies scale with N.
- **[EnvelopeReader](EnvelopeReader.md)** reads the complex envelope word from `io.memPort` and splits
  it into N complex lanes (the envelope is **complex**, unlike the real-envelope RISC-Q reference).
- **N envelope `ComplexMul`** multiply `carrier[k] × env[k]` (full complex × complex).
- **Duration gate** validates/zeroes the output.

`KEEP_HIERARCHY=TRUE` fences the whole datapath as one synthesis unit, so each instance packs its
`ComplexMul`s into DSP48E2s independently (3 DSP/lane) rather than letting the flattened multi-qubit
netlist's global resource balancer spill multiplies into LUTs at scale (see [DSP48.md](DSP48.md)).

## Saturating vs wrapping mode (`saturate`)

`saturate` (default **false**) selects the datapath's overflow behaviour:

- **`saturate = false`** (default, the simpler/faster HW): the CORDIC output clamp *and* every
  `ComplexMul` ±Amax clamp become bare two's-complement truncates. To keep the un-clamped datapath
  overflow-free, `PhasorBatchGenerator` pulls its phasor magnitude **just under full-scale**
  (`Amax − ⌈errorBound⌉ − 1`); the `Amax/2^(w-1)` attenuation then keeps every product `< Amax`.
  **Contract:** software must keep `amp` and `|env| ≤ Amax` (within the unit disk); the only cost is
  usable amplitude capping a few codes below full-scale. The sims assert *clamp == truncate* in this
  mode, so insufficient headroom fails the build.
- **`saturate = true`**: every product/rotation is clamped to ±Amax (a registered clamp barrier on the
  `ComplexMul` output — see [DSP48.md](DSP48.md)). Robust against out-of-contract user envelopes, at
  the cost of clamp logic, +1 cycle on every `ComplexMul`-path latency (re-derived automatically), and
  more LUT/FF.

`prescaleAmp` (default true) runs both CORDICs **uncorrected** — software prescales `amp` by `1/K` so
`K·(amp/K) = amp` — which drops the CORDIC gain stage and shaves one cycle off `cordic.latency`.

## Latency / timing characteristics

II = 1 by construction across the datapath (all `Flow`, no backpressure). The one non-II=1 element is
the `TimedQueue` pop (registered 32-bit due-compare), which is fine because each parameter's updates
are scheduled far apart.

With the current defaults (`prescaleAmp = true`, `saturate = false`, `w = 16`, `N = 16`,
`memLatency = 2`): `ComplexMul = 6`, `Cordic(16,16) = 19`, carrier per-input `amp/phase/freq =
27/28/29`, `phasor = 8`, `time = 29`; `PhasorBatchGenerator.regenCycles = N + 19 + 2 = 37`;
`EnvelopeReader = 4`; gate = 1. Derived lead times: `amp = 34`, `phase = 35`, `freqC = 36`,
`freqP = 52`, `addr = 11`, `dur = 2`; `timeToPulse = 36`. (`saturate = true` makes `ComplexMul = 7`,
so every `ComplexMul`-path lead picks up +1, re-derived and re-asserted.)

Post-route fmax OOC on the `-2` part: **~727 MHz** (`saturate = false`) / **~720 MHz**
(`saturate = true`); 97 DSP48E2, 0 BRAM/URAM. The binder is the scheduler `TimedQueue`'s registered
32-bit due-compare, off the DSP datapath. fmax is a soft constraint here.

## Interface & configuration

Ports (full list in source): `io.time`/`io.startTime` (UInt, batch units); `io.amp`/`io.freq`/
`io.phase` (`Flow(SInt(w))`), `io.addr` (`Flow(UInt(addrWidth))`), `io.dur`
(`Flow(UInt(durWidth))`, batches); `io.memPort` (`master MemReadPort(N·2·w bits, addrWidth)`);
`io.pulse` (`master Flow(ComplexBatch(N, w))`).

`PulseGeneratorParams` — the knobs that matter (rest in source): `batchSize` (N, power of two),
`dataWidth` (w), `timeWidth` (batch-time counter), `memLatency`, `queueDepth`; `saturate` and
`prescaleAmp` (above); `timeOffset` (external calibration added to every lead time); `phasorMethod`
([`SinCosMethod.Cordic`](Cordic.md) default, 0 DSP / ~17 stages / no BRAM, vs `Taylor` —
[SinCos](SinCos.md)-style LUT + first-order correction, 2 DSP / 1 BRAM36 but shorter `regenCycles`);
`realOutput` (tie `io.pulse.im = 0` for a real DAC so each envelope `ComplexMul`'s imag-output
multiply is pruned, 3→2 DSP/lane); `queueUseVec`/`queueForFMax` (TimedQueue storage/pointer levers,
congestion vs fmax — see [TimedQueue](TimedQueue.md)).

A QubiC-style alignment variant with the same bit-exact output lives in
[PulseGeneratorAligned](PulseGeneratorAligned.md).

## Usage

```scala
val pg = PulseGenerator(PulseGeneratorParams())   // defaults: N=16, w=16, timeWidth=32
// wire io.time, the parameter Flows (+ io.startTime), io.memPort to the envelope RAM, io.pulse to the DAC
```

```bash
MILL=mill
$MILL runMain riscq.dsp.pulse.PulseGen                       # emit PulseGenerator.v
$MILL runMain riscq.dsp.pulse.sim.PulseGeneratorSim          # end-to-end golden (exact window)
$MILL runMain riscq.dsp.pulse.bench.VivadoRouteBench         # post-route fmax (RISCQ_PULSE_SATURATE to A/B)
```

## Verification

`riscq.dsp.pulse.sim.PulseGeneratorSim` composes the per-block **bit-exact** Scala models into a
golden pulse and drives a random complex envelope image with scheduled parameter sets. It checks: the
valid window is **exactly** `[startTime, startTime+dur)`; the payload is bit-exact vs the composed
golden over the window (which, because every parameter must be aligned by its derived lead time,
simultaneously validates all six lead times); the payload is zeroed outside the window; and
back-to-back pulses + a phase-only (virtual-Z) update each land correctly — in both the `saturate` and
no-saturation datapaths. Each sub-block has its own golden sim (linked below).

## Related

- [CarrierBatchGenerator](CarrierBatchGenerator.md) · [PhasorBatchGenerator](PhasorBatchGenerator.md)
  · [EnvelopeReader](EnvelopeReader.md) · [TimedQueue](TimedQueue.md)
- [Cordic](Cordic.md) · [ComplexMul](ComplexMul.md) · [SinCos](SinCos.md) · [DSP48.md](DSP48.md) · [DSP.md](DSP.md)
- [PulseGeneratorAligned](PulseGeneratorAligned.md) — bit-exact alternative alignment scheme
- SoC context: [PulseParamBuffer](../soc/PulseParamBuffer.md) · [PulseTableSoc](../soc/PulseTableSoc.md) · [ARCH.md](../soc/ARCH.md)
