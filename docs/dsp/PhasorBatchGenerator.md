# PhasorBatchGenerator — per-frequency phasor batch (serial CORDIC regen)

**Source:** `src/riscq/dsp/pulse/PhasorBatchGenerator.scala` · **Package:** `riscq.dsp.pulse`
· **Type:** Component

Holds the static per-lane phasor batch `phasor[k] = Amax·exp(iπ·f·k)`, `k = 0..N−1`, and regenerates
it whenever the frequency `f` changes. It supplies the per-sample phase offsets that
[CarrierBatchGenerator](CarrierBatchGenerator.md) combines with the per-batch time factor (the
intra-batch factorization `exp(iπf(Nt+k)) = exp(iπfNt)·exp(iπfk)`). Number format is SF(w)
(see [DSP.md](DSP.md); phase units of π).

## Role / dataflow

```
io.freq (Flow) ─▶ FSM ─▶ phasor compute block (CORDIC or Taylor) ─▶ shift register ─▶ io.phasors (Flow)
```

`io.phasors` is a `Flow(ComplexBatch(N, w))`: **invalid during regeneration**, valid (holding the
current batch) otherwise. [CarrierBatchGenerator](CarrierBatchGenerator.md) snapshots it on valid and
holds it between updates.

## Design rationale

- **One shared compute block, serial over N cycles.** Phasors only change on a `freq` update, so
  regenerating them serially — one CORDIC issuing N commands over N cycles — costs **one** rotator
  instead of N parallel ones. The FSM, on a `freq` fire: latches `f`, clears the SF phase accumulator
  `zAcc`, then issues N commands with phase `zAcc` and `zAcc += f` each cycle (truncating add = exact
  phase wrap, so the batch is phase-exact).
- **Write index = count of accepted responses, not a latency-coupled counter.** CORDIC responses arrive
  in order at II = 1; acceptance is gated until `Lc` (the compute-block latency) cycles after the
  restart, which **also discards any in-flight responses from an aborted regen** (a `freq` update mid-
  regen restarts cleanly). This kills the RISC-Q reference's fragile "start the write index at
  `maxId − cordic.latency + 1`" trick.
- **Collected as a shift register, one shared enable.** The N responses are shifted in on a single
  shared clock-enable rather than written to a lane addressed by the response count. A per-lane one-hot
  write decode would give each lane its own enable (N control sets); a shift uses one (1 control set) —
  a real congestion win for the replicated multi-qubit instances. The newest response enters lane 0 and
  shifts up, so `io.phasors` reads the batch back **reversed** (`payload(k) = phRe(N-1-k)`), bit-
  identical to the indexed write. The accept decision (a wide compare) is itself buffered into a register
  (`collect`, `max_fanout`-capped) before driving the 2·N-FF array's enable, with the payload delayed one
  matching stage — one extra cycle, folded into `regenCycles`.
- **No reset init on the phasor batch / `fReg` / `zAcc`.** The batch is undefined until the first `freq`
  write regenerates it, which is safe: the pulse output is hard-gated to zero by the duration counter,
  and every consumer programs `freq` (triggering a full regen) before its window opens — the freq-queue
  lead time covers `regenCycles`, so the window always reads a freshly regenerated batch, never the boot
  value. Dropping the inits lightens the reset net. Do not restore.

## `regenCycles` — the load-bearing latency

`regenCycles = N + Lc + 2` (freq fire → phasors valid): `Lc` to the first response, N lane shifts, plus
the one extra cycle from the registered `collect` enable. It is **exported** so [PulseGenerator](PulseGenerator.md)'s
freq→phasor queue lead time covers the regen window — the new phasors are guaranteed ready before the
scheduled start. With the CORDIC method at w = 16 (`Lc = 19`): `regenCycles = N + 21`.

## `method` — phasor compute block

- **`SinCosMethod.Cordic`** (default): rotation-mode [Cordic](Cordic.md), 0 DSP, ~17-stage pipeline, no
  BRAM. `correctGain`/`saturate` apply as in the standalone CORDIC. In no-saturation mode the hardwired
  input magnitude is pulled under full-scale (`Amax − ⌈errorBound⌉ − 1`, pre-divided by `K` when
  uncorrected) so the un-clamped rotation still fits in w bits.
- **`SinCosMethod.Taylor`**: [SinCos](SinCos.md) LUT + first-order Taylor correction, 2 DSP, 8-stage
  pipeline, 1 BRAM36. Always outputs at Amax and saturates at ±Amax (`correctGain`/`saturate` ignored).
  Shorter regen (`regenCycles = N + 10`) and fewer LUTs, at the cost of 1 BRAM36 + 2 DSP48E2.

## Interface & configuration

`PhasorBatchGenerator(batchSize, dataWidth, correctGain = true, saturate = true, method = Cordic)`.
Ports: `io.freq` (`slave Flow(SInt(w))`), `io.phasors` (`master Flow(ComplexBatch(N, w))`). II = 1
for command issue; the module is busy for `regenCycles` after each `freq` fire.

## Usage / verification

```bash
mill runMain riscq.dsp.pulse.sim.PhasorBatchGeneratorSim
```

`PhasorBatchGeneratorSim` checks: after a `freq` write the output is invalid for **exactly**
`regenCycles` cycles then valid; the regenerated batch is bit-exact vs the CORDIC model
`phasor[k] = Cordic.rotate((Amax,0), z = k·f mod 2^w)`; and a `freq` update arriving mid-regen restarts
cleanly (final batch matches the second freq). Both gain modes are run.

## Related

- [PulseGenerator](PulseGenerator.md) · [CarrierBatchGenerator](CarrierBatchGenerator.md) · [Cordic](Cordic.md)
  · [SinCos](SinCos.md) · [ComplexMul](ComplexMul.md) · [DSP.md](DSP.md)
