# CarrierBatchGenerator — per-batch carrier samples (NCO + CORDIC + phasor combine)

**Source:** `src/riscq/dsp/pulse/CarrierBatchGenerator.scala` · **Package:** `riscq.dsp.pulse`
· **Type:** Component

Generates one batch of `N` carrier samples per `io.time` tick:

```
carrier[k](t) = amp · exp(iπ·gPhase) · phasor[k]
              = amp·Amax · exp( iπ·( freq·(N·t + k) + phase ) ),   gPhase = freq·(N·t) + phase (mod 2)
```

with `phasor[k] = Amax·exp(iπ·f·k)` supplied by [PhasorBatchGenerator](PhasorBatchGenerator.md). It is
the modulation stage of [PulseGenerator](PulseGenerator.md): the envelope is multiplied onto this
carrier downstream. Number format is SF(w) (see [DSP.md](DSP.md)).

## Role / dataflow

```
amp ──RegNextWhen──┐
phase RegNextWhen──┤   gPhase
freq RegNextWhen───┴─▶ (NCO) ─▶ CORDIC(xy=(amp,0), z=gPhase) ─▶ cBase ─┐
io.time ───────────┘                                                   ├─▶ N × ComplexMul ─▶ carrier[k]
phasors ─Flow(snapshot)──────────────────────────────────────────────┘
```

Inputs are the popped parameter `Flow`s (`amp`/`phase`/`freq`, each latched in a parameter register)
plus the phasor batch `Flow` and `io.time`. Output `io.carrier` is a plain (always-valid) `ComplexBatch`
— validity is the duration gate's job, one level up.

## Design rationale

- **Intra-batch factorization.** `exp(iπf(Nt+k)) = exp(iπfNt)·exp(iπfk)`. One CORDIC computes the
  per-batch, time-dependent factor `amp·exp(iπ·gPhase)`; the N static per-lane phasors carry the
  per-sample offsets; N `ComplexMul`s combine them. Only the multiplies scale with N — one CORDIC, not N.
- **Time-product phase, not an accumulator.** `gPhase = freq·((t·N) mod 2^w) + phase` is recomputed
  from absolute `io.time` every cycle. Truncating `t·N` (an exact left shift, since N is a power of two)
  to w bits is **exact mod 2π**: one wrap of the truncated factor is a whole number of turns. So pulses
  are **phase-coherent across arbitrary time gaps and across channels** — the property qubit control
  needs — without carrying a running accumulator.
- **Amplitude rides the CORDIC `x` input.** Rotation-mode CORDIC rotates `(amp, 0)` by the phase, so
  amplitude scaling is free — no separate amp multiplier. This is the reason CORDIC fits here (a LUT
  `SinCos` would need an extra multiply). See [Cordic](Cordic.md).
- **Operand-magnitude contract.** `|amp·exp(iπ·gPhase)| ≤ amp < 1` and `|phasor[k]| < 1`, so the carrier
  `ComplexMul` never saturates. The overall carrier scale is `amp·(Amax·2^-(w-1))²` — a pure gain the
  golden model must include.
- **Buffered phasor snapshot (fanout/reset gotcha).** `io.phasors.valid` would otherwise be one
  combinational net driving the clock-enable of all N·2·w (= 512 at N=16) phasor-snapshot FFs. Instead
  the enable is registered (`phValBuf`, `max_fanout`-capped so the tool replicates it beside the lanes)
  and the payload delayed one matching stage to stay aligned. Cost: the batch lands one cycle later,
  folded into the exported `phasorLatency`. The parameter/snapshot registers carry **no reset init**
  (write-before-read: popped at lead time before the duration-gated output is ever observed), which keeps
  them out of the async-reset group for denser packing on the replicated multi-qubit instances. Do not
  restore the inits.

## Latency

II = 1, all stages registered. Per-input latencies (param-Flow fire → carrier) are **exported as sums
of sub-module latencies** — no `+7/+8/+9` literals like the RISC-Q reference — so a pipeline edit
re-derives [PulseGenerator](PulseGenerator.md)'s lead times automatically:

```
tail          = cordic.latency + 1 (broadcast reg) + ComplexMul.latency
ampLatency    = 1 + tail        phaseLatency  = 2 + tail
freqLatency   = 3 + tail        timeLatency   = 3 + tail
phasorLatency = 2 + ComplexMul.latency
```

With defaults (`correctGain = false` via `prescaleAmp`, `saturate = false`, w = 16): `cordic = 19`,
`ComplexMul = 6` ⇒ `tail = 26`, so `amp/phase/freq/time = 27/28/29/29`, `phasor = 8`.

## Interface & configuration

`CarrierBatchGenerator(batchSize, dataWidth, timeWidth, correctGain = true, saturate = true)`.
`correctGain = false` runs the CORDIC uncorrected (its output carries the gain `K`, software prescales
`amp` by `1/K`) — [PulseGenerator](PulseGenerator.md) sets it from `prescaleAmp`. `saturate` flows into
the CORDIC and the `ComplexMul`s (see [PulseGenerator](PulseGenerator.md) for the saturating vs wrapping
contract). `batchSize` must be a power of two (so `t·N` is an exact shift).

## Usage / verification

```bash
mill runMain riscq.dsp.pulse.sim.CarrierBatchGeneratorSim
```

`CarrierBatchGeneratorSim` drives static amp/phase/freq/phasors and, for each test time, checks
`io.carrier[k]` two ways: **bit-exact** vs the composed model `ComplexMul(Cordic(amp,0,gPhase),
phasor[k])`, and against the **Double** reference `amp·exp(iπ·gPhase)·phasor[k]` with a composed bound
(CORDIC error through the `ComplexMul` + ½ ulp). Phase coherence is exercised with very large and
wrap-straddling time values (including the `t·N` batch-time wrap), so the carrier must still match after
an arbitrary time jump. Both gain modes (`correctGain` true/false) are run.

## Related

- [PulseGenerator](PulseGenerator.md) · [PhasorBatchGenerator](PhasorBatchGenerator.md) · [Cordic](Cordic.md)
  · [ComplexMul](ComplexMul.md) · [SinCos](SinCos.md) · [DSP48.md](DSP48.md) · [DSP.md](DSP.md)
