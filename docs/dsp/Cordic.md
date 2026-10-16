# Cordic — rotation-mode CORDIC rotator

**Source:** `src/riscq/dsp/Cordic.scala` · **Package:** `riscq.dsp` · **Type:** Component (+ params/
payload Bundles, integer model object)

A fully-pipelined rotation-mode CORDIC: it rotates a complex vector `xy` by the angle `π·value(z)`
and gain-corrects, so `rsp ≈ xy · exp(iπ·value(z))`, one result per clock (**II = 1**). All operands
are [`SF(n)`](DSP.md#1-number-format--signed-fixed-point-sfn): `xy.re/im` are `SF(xyWidth)`, and `z` is `SF(zWidth)` with
angle `value(z)·π ∈ [-π, π)` — plain two's-complement wrap is an exact phase wrap.

## Role in the system

CORDIC is the **rotation engine of the pulse side**. Its defining property is that **amplitude rides
the `xy` input for free**: rotating `(amp, 0)` yields `amp·exp(iπz)`, with no separate amplitude
multiply. That is why the pulse path rotates a complex envelope with CORDIC rather than a
[SinCos](SinCos.md) LUT + multiplier, and why a LUT would not fit. Two concrete users:

- [`SinCos`](SinCos.md) `SinCosMethod.Cordic` — drives `xy = (Amax, 0)`, `z = phase`, reading the
  rotated real/imaginary parts as `Amax·cos(P)` / `Amax·sin(P)`.
- [`CarrierBatchGenerator`](CarrierBatchGenerator.md) / [`PhasorBatchGenerator`](PhasorBatchGenerator.md)
  — the complex-envelope rotation that produces the pulse carrier/phasor batches.

## How it works

Rotation mode in three parts:

1. **π pre-rotation.** CORDIC converges only for `|angle| ≲ 99.9°`. When `z`'s top two bits differ
   (`|value| > ½`, i.e. `|angle| > π/2`) the rotator negates `xy` (a rotation by π) and flips `z`'s
   MSB (subtracting `±π`). This is exact — no adder — and leaves `|angle| ≤ π/2` for the iterations.
2. **Iterations.** `iters` micro-rotation stages, **one register each** (this is where the latency
   and II=1 come from). At stage `i`, with `d = sign(z)`: `x' = x ∓ d·(y>>i)`, `y' = y ± d·(x>>i)`,
   `z' = z ∓ d·α(i)`, where `α(i) = round(atan(2^-i)/π · 2^(zInternal-1))` is the angle table.
3. **Gain correction.** The iterations grow the vector by `K = Π√(1+2^-2i) ≈ 1.6468`; multiplying
   `x`, `y` by `1/K` undoes it. `1/K` is a **fabric constant multiply** (`use_dsp=no`, **0 DSP**),
   with its rounding folded into the output round + saturate.

Internally the `xy` datapath carries `xyIntGuard` integer guard bits (the gain growth `K·√2 ≈ 2.33`
needs ≥ 2) plus `g` fractional guard bits and rounds once; `z` carries `zg` guard bits so the
α-table rounding stays sub-ulp. The analytic worst-case bound (`Cordic.errorBound`, target ≤ 2 ulp)
sums the angle residual, the α-quantization walk, the per-iteration shift-truncation, the `1/K`
constant rounding, and the final round — and is asserted exactly in sim.

## Latency / timing

```
latency = 1 (load + π pre-rotation) + iters + (correctGain ? 1) + 1 (output round)
```

At the defaults (`iters = xyWidth + 1 = 17`, gain on) a 16-bit rotator is ~20 cycles. II = 1 throughout —
a new `(xy, z)` every cycle. The high latency is fine: every block exports its latency and the
toplevel derives lead times from it.

## Interface & configuration

```scala
case class CordicParams(
  xyWidth = 16,         // input/output Complex SF width
  zWidth  = 16,         // input angle SF width (angle = value·π)
  nIter   = -1,         // iterations; <0 ⇒ xyWidth+1 (general-rotation bound < 2 ulp)
  xyGuardBits = -1,     // g: xy fractional guard bits; <0 ⇒ log2Up(iters)+3
  zGuardBits  = -1,     // z fractional guard bits;  <0 ⇒ log2Up(iters)+3
  xyIntGuard  = 2,      // integer guard bits (gain growth K·√2 ≈ 2.33 < 2^2)
  gainScaleBits = 20,   // fractional bits of the 1/K KCM constant
  correctGain = true,
  saturate    = true,   // clamp output to ±Amax; false ⇒ bare truncate (caller keeps |xy| in range)
  resetValid  = true)   // reset the rsp.valid shift register; see below

io.cmd : slave  Flow(CordicCmd)        // .xy : Complex(xyWidth), .z : SInt(zWidth) — II = 1
io.rsp : master Flow(Complex(xyWidth)) // valid is cmd.valid delayed by `latency`
```

Two parameters are timing/area levers, not arithmetic:

- **`saturate`** — `true` clamps to `[−Amax, +Amax]` (tested as two parallel rails with a
  `max_fanout` cap so the select replicates next to the output muxes). `false` builds **no clamp
  logic** (a bare truncate) and is for a caller that guarantees the result already fits — simpler and
  faster. When gain correction is off, the output round is folded into the last micro-rotation's add,
  so the output stage becomes a bare truncate + saturate with no adder on the critical output path.
- **`resetValid`** — `true` gives a well-defined `false` from reset (the standalone `Flow` contract
  the unit tests assert). `false` makes the `latency`-deep valid chain **reset-free** — it leaves the
  global reset net and infers a single `SRL16E/SRL32E` instead of `latency` discrete FFs. That is a
  real win when the core is replicated (≈19 FFs × every CORDIC, all on one global reset). Only opt
  out when the parent's `rsp.valid` consumer is itself reset-gated, so a boot value is never acted on
  (e.g. `PhasorBatchGenerator` gates `collect` behind reset-bearing counters).

## Verification

`riscq.dsp.sim.CordicSim` is the two-tier self-check: (1) bit-exact against `Cordic.rotate` — the
integer mirror with the same pre-rotation, shifts, α table, `1/K` constant, rounding, and saturation
— zero tolerance every cycle; (2) the analytic `errorBound` against the ideal real rotation
`xy·exp(iπz)`, checked whenever the ideal stays in range, asserted exactly. Plus a `[−Amax, Amax]`
range assertion and the II=1 / fixed-latency scoreboard. Stimulus is random `(xy, z)` plus named
pre-rotation corner angles (`0, ±π/4, ±π/2±lsb, ±π`).

```bash
mill runMain riscq.dsp.sim.CordicSim
```

## Related

- [SinCos](SinCos.md) — the `SinCosMethod.Cordic` wrapper.
- [ComplexMul](ComplexMul.md) — the other complex-arithmetic primitive (the multiply vs the rotation).
- [PulseGenerator](PulseGenerator.md) / [PhasorBatchGenerator](PhasorBatchGenerator.md) — the
  pulse-side rotation users.
- [DSP overview](DSP.md) — `SF(n)`, phase encoding, II=1 contract.
