# ComplexMul — 3-multiplier complex product (+ the `Complex` type)

**Source:** `src/riscq/dsp/{ComplexMul,Complex}.scala` · **Package:** `riscq.dsp` · **Type:**
Component (+ `Complex` Bundle, integer model object)

A fully-pipelined complex product `p = a·b` for two [`SF(width)`](DSP.md#1-number-format--signed-fixed-point-sfn) operands,
one result per clock (**II = 1**), using the **3-multiplier (Karatsuba)** form so it costs three
DSP48E2 instead of four.

## The `Complex` type

```scala
case class Complex(width: Int) extends Bundle { val re = SInt(width bits); val im = SInt(width bits) }
```

A plain `Bundle` of two `SF(width)` codes — **no `AFix`** anywhere in the pulse datapath; the
fixed-point convention is carried by hand. `value(re/im) = code · 2^-(width-1) ∈ [-1, +1)`, largest
magnitude `Amax = 2^(width-1) − 1`. `ComplexBatch(n, w)` is just `Vec.fill(n)(Complex(w))` — a batch
of one-DAC-sample-per-lane values, with no per-lane `valid` (batch validity is the pulse toplevel's
duration gate, not a per-lane concern).

## Role in the system

The complex-arithmetic primitive wherever a *product* (not a rotation) is needed:
[`ReadoutDecoder`](ReadoutDecoder.md) (demod sample × reference carrier),
[`PulseGenerator`](PulseGenerator.md) / [`PulseGeneratorAligned`](PulseGeneratorAligned.md), and
[`CarrierBatchGenerator`](CarrierBatchGenerator.md). For pure rotation by a phase the pulse side uses
[`Cordic`](Cordic.md) instead (amplitude rides its input for free); `ComplexMul` is for genuine
complex×complex.

## How it works — Karatsuba (3 mult)

For `a = ar + i·ai`, `b = br + i·bi`:

```
m  = (ar − ai)·bi                          // one shared product
pr = (br − bi)·ar + m  =  ar·br − ai·bi     // Re(a·b)
pi = (br + bi)·ai + m  =  ar·bi + ai·br     // Im(a·b)
```

Three `(w+1)×w` products map to three DSP48E2; the pre-adds and recombination are in fabric. The full
integer product carries `2(w−1)` fractional bits; the output drops `w−1` of them with an arithmetic
`>>(w−1)` (a free constant slice), **round-to-nearest** (a `+½ ulp` constant folded into the
recombination adder — no separate rounder), then **saturates to `[−Amax, +Amax]`**.

Saturation is needed because `|a·b|` can reach 2 (e.g. `a = b = −1−i ⇒ a·b = 2i`), so the slice alone
would wrap. It is **distortion-free in contract** — carrier/phasor operands always have magnitude
`< 1`, so the product never saturates — and merely bounds the error for out-of-contract user
envelopes.

## DSP48E2 packing — the two structural rules

The pipeline is laid out specifically so Vivado packs the arithmetic into exactly **3 DSP48E2 per
multiply** (full reference: [DSP48.md](DSP48.md)):

- **Each Karatsuba pre-add sits exactly one stage before its multiply** (`DA = ar−ai` → `M`;
  `DB = br−bi`, `SB = br+bi` → `PRE`/`PIM`). The link register between them becomes the DSP's
  **`ADREG`**, so Vivado packs `(D±A)·B` into the slice's *internal* pre-adder — the CARRY8 pre-add
  and its route into the operand port leave fabric entirely. A pre-add *two* stages early would be
  stranded in fabric.
- **The shared term `M` is produced one stage ahead, then copied per output** (`MCR` for `SRE`, `MCI`
  for `SIM`) the stage before recombination. Each copy maps to its recombination DSP's **`CREG`**, so
  `M` arrives as a settled C-port register (reg-to-reg route, then a DSP-internal `MREG + CREG` add)
  instead of routing combinationally from `M`'s DSP output into both ALUs as one high-fanout net.
  This also keeps the recombination one DSP-ALU op deep — no live `PCIN` cascade (which would be a
  two-ALU chain and a 4th DSP per multiply).

Do **not** `KEEP_HIERARCHY` this block — a fence here cuts the DSP48E2 pipeline and blocks the
register retiming the 3-DSP/lane packing relies on. Fence the *enclosing* datapath instead.

## Latency / timing

Shared latency **6** (7 register nodes, `n0..n6`). The saturating output adds **one more register**
(latency 7) so the `±Amax` clamp is a registered DSP-output **barrier** — without it Vivado over-folds
the cascade and FF/route blow up. The no-sat output is a bare bit-slice, left combinational off the
recombination `PREG`. Post-route ~720 MHz (sat) / 727 MHz (no-sat) OOC on the `-2` part. II = 1.

## Interface & configuration

```scala
case class ComplexMul(width: Int, saturate: Boolean = true, resetValid: Boolean = true)

io.cmd : slave  Flow(ComplexMulCmd(width))  // .a, .b : Complex(width) — II = 1, no backpressure
io.rsp : master Flow(Complex(width))        // valid is cmd.valid delayed by `latency`
def latency: Int                            // 6, or 7 when saturate
```

- **`saturate`** — `true` clamps to `±Amax` (parallel rails + `max_fanout` cap, like `Cordic`);
  `false` builds no clamp (bare truncate) for a caller that keeps both operands in the unit disk —
  simpler/faster, latency 6.
- **`resetValid`** — `true` gives the standalone `Flow` contract (valid = false from reset); `false`
  makes the valid chain reset-free (SRL-inferable, off the global reset net), safe when the parent
  reads the payload at fixed latency and gates on its own reset-bearing counter instead (e.g.
  `PulseGenerator` / `ReadoutDecoder` gate on the duration counter / time compare).

## Verification

`riscq.dsp.sim.ComplexMulSim` is the two-tier self-check: (1) bit-exact against `ComplexMul.model`
(the shared 3-mult Karatsuba mirror — same round, same saturation — reused by the carrier/pulse
goldens too), zero tolerance every cycle; (2) a `≤ ½ ulp` round-to-nearest bound against the true
product when it is in range. Plus a `[−Amax, Amax]` range assertion and the II=1 / fixed-latency
scoreboard, under full load and randomized valid gaps.

```bash
mill runMain riscq.dsp.sim.ComplexMulSim
```

## Related

- [Cordic](Cordic.md) — rotation (amplitude-on-input) vs this complex×complex multiply.
- [DSP48](DSP48.md) — the canonical DSP48E2 packing reference; this module is its worked example.
- [AdderTree](AdderTree.md) — sums the per-lane `ComplexMul` outputs in the readout decoder.
- [ReadoutDecoder](ReadoutDecoder.md) / [PulseGenerator](PulseGenerator.md) — the users.
