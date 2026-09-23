# ReadoutDecoder — carrier-triggered readout integrator (`riscq.dsp.pulse`)

**Source:** `src/riscq/dsp/pulse/ReadoutDecoder.scala`, `src/riscq/dsp/pulse/ReadoutGen.scala`
· **Package:** `riscq.dsp.pulse` · **Type:** Component (`ReadoutDecoder`) + params case class

> Carrier-triggered decoder — built and verified (`ReadoutDecoderSim`, both `saturate` modes; the
> SoC sims `DemodDecoderSim`/`ReadoutResultLinkSim`/`PulseTableSocSim`). The previous software-armed
> decoder is in the git history; the rewrite rationale is
> [specs/new-readout-decoder](../../specs/new-readout-decoder/README.md).

The **inverse of the [PulseGenerator](PulseGenerator.md)**, and the back end of a dispersive qubit
readout. Where the pulse generator *synthesises* a complex-envelope pulse onto a carrier, the
readout decoder *demodulates* an incoming ADC batch against a reference carrier (the digital LO),
*integrates* the product over the carrier's own pulse window, and emits the integrated I/Q point
plus a one-bit state discrimination:

```
demod[t][k] = adc[t][k] · carrier[t][k]         // complex × complex, per lane k  (down-conversion)
real/imag   = Σ_{t ∈ carrier window} Σ_k demod[t][k]   // integrated I / Q
res         = sign(real)                        // 1-bit discrimination, threshold 0 on the real axis
```

One tick is one **batch** of `batchSize` (= N) ADC samples; the module runs at ADC-rate / N on
`dspClk`.

**The window is the carrier's `valid`.** The decoder has no schedule of its own — no `time`,
`startTime` or `dur` inputs, no arming write. `io.carrier` is a `Flow`: the **rising edge** of its
`valid` restarts the I/Q accumulator, the **falling edge** completes the integral and raises
`res.valid`. In the SoC the carrier is the [DemodChannel](../soc/RfChannels.md)'s scheduled,
envelope-shaped [PulseGenerator](PulseGenerator.md) pulse, whose `valid` is exactly the scheduled
window `[startTime, startTime + dur)` and whose payload is forced to zero outside it — so firing
the demod pulse *is* the readout, and the integration window aligns to the carrier by
construction (no cross-channel `startTime` bookkeeping, no arm-ordering contract).

## Role in the system

Lives at the converter edge in the SoC's DSP region. Its inputs:

- `io.adc` — one ADC batch per cycle (the captured readout tone).
- `io.carrier` — `Flow(ComplexBatch)`: the reference carrier batch plus the window-defining
  `valid`. The decoder is agnostic to how it is produced; it requires only the
  **zero-outside-window invariant** (below).

Its outputs: `io.real`/`io.imag` (the raw integrated I/Q, read back by software as one word
each), `io.res` (`Flow(Bool)`, valid once the integral settles, cleared by the next window's
start — see [EventLink](../soc/EventLink.md)), and `io.demodData`
(`Flow(ComplexBatch)`, the raw per-batch demod trace, valid/payload self-aligned).

## Pipeline

```
adc[k]             ─▶ ComplexMul[k] ─ demod[k] ─┬─▶ Flow demodData        (per-batch trace)
carrier[k] payload ─▶  (per lane)               │
                                                ├─▶ +reg─▶ AdderTree(Re) ─▶ sumR ─▶ msb ─▶ res
                                                └─▶ +reg─▶ AdderTree(Im) ─▶ sumI       real/imag
carrier valid ─▶ Delay(Ldemod) ─▶ inWin ─▶ rise: restart accumulator / fall: res.valid
```

- **Demod** reuses `riscq.dsp.ComplexMul` (the same DSP48-packed, II=1 product the pulse side
  uses) — see [ComplexMul](ComplexMul.md). One `ComplexMul` per lane, `resetValid = false` (the
  mul's own valid chain is unused; the window rides the delayed carrier valid).
- **Lane sum** is the balanced [AdderTree](AdderTree.md) (`reduceBalancedTree(_ + _, RegNext)`),
  with one extra input register. Splitting "sum across lanes" (a tree, one batch) from "sum
  across batches" (a single feedback accumulator) keeps each adder narrow.
- **Window accumulator** `sumR`/`sumI` is **free-running** — it adds the tree output every
  cycle, with no clock enable and no FSM. The window's first product *loads* the accumulator
  (`sum := tree` on the delayed rising edge) instead of adding; out-of-window products are
  exactly zero, so the settled integral holds from window end until the next window start.

`KEEP_HIERARCHY = TRUE` fences the component so its N `ComplexMul`s pack into DSP48E2s
per-instance (3 DSP48E2 per lane, demod only) rather than spilling to LUTs via the global
resource balancer; the adder tree and accumulator are fabric CARRY8.

## Derived window alignment (the load-bearing contract)

Still the central rule, and still zero magic constants: the accumulator reads the tree output
`Ldemod` cycles after the corresponding ADC/carrier batch entered, where

```
Ldemod = ComplexMul.latency(saturate) + 1 + log2Up(N)      // exported as demodLatency
```

The carrier `valid` is delayed through a `Ldemod`-deep 1-bit shift register so its edges arrive
at the accumulator aligned with the products they delimit. The integrated batches are therefore
**exactly** the carrier's in-window batches — `dur` of them for a demod pulse of duration `dur`,
i.e. the generator's `[startTime, startTime + dur)` — and the end-to-end sim asserts this with a
±1-shifted-window sharpness cross-check. Every offset is a sum of exported sub-block latencies,
so a future pipeline edit to `ComplexMul` or the tree shifts the alignment automatically.

There is no `timeOffset` calibration parameter: converter/analog/cable latency is absorbed into
*when software fires the demod pulse* — the one scheduling knob that already exists.

## The zero-outside-window invariant

The free-running accumulator rests on: **whenever `io.carrier.valid` is low, the tree output
`Ldemod` cycles later is exactly 0.** The chain: the generator muxes every payload lane to 0 when
idle (from reset too) → `ComplexMul` of a zero operand rounds to exactly zero in both saturate
modes (`(0 + ½ulp) >> (w−1) = 0`) → a tree of zeros is zero. The testbench asserts the invariant
on every idle cycle, so an edit that breaks it (e.g. a dirty-zero rounding change) fails the sim.
Fallback if it ever must break: gate the accumulate with `inWin` (one clock enable, bit-identical
results).

## Result protocol & discrimination

`res.valid` rises one cycle after the last in-window product settles into `sumR`/`sumI` (the
falling-edge detect registers), and is cleared by the **next window's** rising edge — so it is high
from a window's settle until the next window opens, i.e. **low exactly while a fresh window
integrates**. [EventLink.resultSource](../soc/EventLink.md) forwards this level up to the
core-local sink (which the CPU's halting `res` read polls). `io.res.payload = sumR.msb` — `sign(sumR)`, a fixed threshold of 0
on the real axis (`true` ⇒ negative). The raw `real`/`imag` are exposed so software applies its
own IQ rotation/threshold off-line. A programmable IQ-rotation discriminator remains a future
optional stage.

Consecutive windows need **≥ 1 idle batch** between them: zero-gap windows have no falling edge
and merge into one integral (the software pacing rule in
[specs/software 01 §5](../../specs/software/01-hardware-contract.md)).

## Accumulator width

The accumulator must not overflow over the longest window, enforced by
`require(accWidth ≥ dataWidth + log2Up(batchSize) + maxWinLog2)` (worst case: per-lane `Amax` · N
lanes · `2^maxWinLog2` batches). `maxWinLog2` (default 14) elaborates no hardware — it states the
contract; the window length is the demod pulse's `dur` (a 16-bit field), so the driver enforces
`dur ≤ 2^maxWinLog2` on the demod channel. `accWidth = 32` keeps the one-word readback.

## Latency & timing

- ADC/carrier batch → tree output: `Ldemod` cycles (see above).
- `res.valid` rises two cycles after the last in-window batch's product reaches the tree output
  (falling-edge detect + register) — the same settle behavior software saw before.
- `io.demodData` is valid for exactly `dur` cycles per window, `valid` and payload self-aligned
  (`valid = Delay(carrier.valid, mulLatency)`, payload the `ComplexMul` outputs).
- The armed design closed OOC post-route ~720 MHz on the `-2` part (N=16, w=16,
  `saturate=false`); the rewrite only removes logic from that path (the 32-bit time compare, the
  FSM, the accumulator enables), so no regression is expected — re-measure at sign-off.

## Interface & configuration

`ReadoutDecoderParams(batchSize, dataWidth, accWidth, maxWinLog2, saturate)`:

- `maxWinLog2` — the no-overflow window bound (above); checks the params, elaborates nothing.
- `saturate` — threaded straight into the per-lane `ComplexMul`; default `false` matches
  `PulseGeneratorParams.saturate`, so a paired generate/decode SoC shares one mode. With
  `saturate = false` the operands must stay inside the unit disk (`|adc|, |carrier| < 1`), which
  the converter scaling and LO amplitude already guarantee.

The component exports `mulLatency` and `demodLatency` for the SoC's time-alignment math.

## Usage

```bash
mill runMain riscq.dsp.pulse.ReadoutGen               # emit ReadoutDecoder.v
mill runMain riscq.dsp.pulse.sim.ReadoutDecoderSim    # end-to-end golden integral + window
```

## Verification

`riscq.dsp.pulse.sim.ReadoutDecoderSim` drives `io.carrier` as a Flow — scheduled windows with
known batches, zero payload + low valid outside — against a **golden model composed from the
per-block bit-exact models** (`ComplexMul.model` per lane, summed across lanes and the window):
`real`/`imag` bit-exact over exactly the `dur` valid batches (±1-shifted-window sharpness
cross-check); the `res.valid` protocol (low in-window, rises after settle, cleared by the next
window's start); repeated windows including the minimum 1-batch gap (two distinct integrals); the
zero-outside-window invariant on every idle cycle; `demodData` self-aligned with bit-exact
payload. Runs for `saturate` false and true.

## Related

- [PulseGenerator](PulseGenerator.md) — the forward path this inverts; in the SoC an instance
  also produces `io.carrier` (the [DemodChannel](../soc/RfChannels.md)), whose `valid` window
  *is* the integration window.
- [ComplexMul](ComplexMul.md), [AdderTree](AdderTree.md) — the reused datapath blocks.
- [DSP48](DSP48.md) — DSP48E2 packing notes; [DSP](DSP.md) — number formats.
- [SoC ARCH](../soc/ARCH.md) §2 — why lead-time scheduling makes the result link
  distance-tolerant; [EventLink](../soc/EventLink.md) — how `res`/`real`/`imag`
  return to the core.
- [specs/new-readout-decoder](../../specs/new-readout-decoder/README.md) — the rewrite spec
  (rationale, software contract, migration).
