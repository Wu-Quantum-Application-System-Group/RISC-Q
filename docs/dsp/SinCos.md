# SinCos — phase-to-amplitude `(cos, sin)` generator

**Source:** `src/riscq/dsp/{SinCos,SinCosLut,SinCosGen}.scala` · **Package:** `riscq.dsp` ·
**Type:** Component (+ params/payload Bundles, build objects)

A fully-pipelined phase-to-amplitude converter: one `(cos, sin)` pair per clock,
**initiation interval 1**. The phase input and the amplitude outputs are all
[`SF(n)`](DSP.md#1-number-format--signed-fixed-point-sfn); the phase encodes `P = value(phase)·π ∈ [-π, π)` and the outputs
carry the scaled waveform `A·cos(P)`, `A·sin(P)` (the `A = 1 − 2^-(n_a-1)` gain is in the golden
model). One `SinCos` `Component`, one `SinCosParams`, with the algorithm chosen by `method` so the
testbench and generate targets are shared across all three implementations.

## Role in the system

The NCO/upconversion primitive: feed it a phase ramp and it produces a carrier; both `cos` and `sin`
come out together (they share the table by symmetry, so the pair is nearly free) for IQ
upconversion. It is a leaf `Component`, instantiated ×N behind a parallel phase generator to feed the
multi-GS/s DACs. Note that for the pulse datapath the project rotates a complex envelope with a
[Cordic](Cordic.md) instead — CORDIC carries amplitude on its `xy` input for free, which a LUT
cannot — so `SinCos` is the standalone carrier generator, and `SinCosMethod.Cordic` exists mainly so
the same testbench can compare all three methods head to head.

## The three implementations

All three reduce the full circle to one quarter-wave over `[0, π/2)` using the quadrant bits
(§ shared front-end below), then differ in how they fill in the quarter:

| `method` | How | Storage | DSP48E2 | Accuracy (n_a = 16) |
|---|---|---|---|---|
| `Lut` (v1) | quarter-wave folded table, one read + sign | 1 BRAM36 @ t=11 (LUTRAM if small) | 0 | ~12 effective bits |
| `Taylor` (v2) | v1 coarse read + 1st-order Taylor correction | 256×18 LUTRAM @ t=8 (1 BRAM if larger) | 2 | 16-bit faithful (< 1 ulp) |
| `Cordic` | rotate `(Amax, 0)` by the phase | none (1/K KCM in fabric) | 0 | ~115 dBc SFDR |

`Taylor` is the recommended production configuration (it is the Xilinx DDS-compiler architecture):
faithful 16-bit at a footprint of a few hundred LUTs + 2 DSPs, which is the per-replica cost that
matters when the block is instantiated ×8–16. `Lut` is the coarse/bring-up mode. `Multipartite` is
declared but not implemented.

### Shared front-end — quadrant folding (midpoint sampling)

With `u = phase bits as UInt`, `quad = {q1,q0} = u[n_p-1:n_p-2]`, and `idx` the remaining bits, the
table is addressed by the **top `t` bits** of `idx` or its bitwise complement:

```
addr_sin = q0 ? ~idxTop : idxTop      neg_sin = q1
addr_cos = q0 ? idxTop  : ~idxTop     neg_cos = q1 ^ q0
```

Each table entry stores sine at the **midpoint** of its bin:
`T[k] = round(Amax · sin((k+½)·2^-t·π/2))`. Midpoint sampling is the load-bearing trick:

1. **Folding is a pure bitwise NOT** — `~(idxTop)` is exact at the quadrant edge, so no `+1` adder
   and no special case (`~(idx[hi:lo]) == (~idx)[hi:lo]`).
2. **No endpoint values** — the table never stores `sin 0 = 0` or `sin(π/2) = 1.0`, so every entry
   fits unsigned in the magnitude bits and the signed output never reaches the most-negative code.
3. **Optimal truncation** — the midpoint halves the worst-case phase error when the phase has more
   bits than the table index (v2's fine bits).

Two's-complement negation's `+1` rides the final adder's carry-in; there are no separate negators.

### v1 LUT datapath (latency 4)

`s0` decode → `s1` true-dual-port table read (port A `addr_sin`, port B `addr_cos`) → `s2` BRAM
output register (kept for fmax — removing it is the #1 BRAM-path timing miss, and latency is free) →
`s3` conditional negate + register. One `Mem` with two synchronous read ports maps to a single
BRAM36 in TDP mode at `t=11`; smaller `t` drops into LUTRAM automatically (never force a primitive).
Worst-case error `ε ≤ Amax·π·2^-(t+2) + ½ ulp` (phase quantization to the bin midpoint + entry
rounding), asserted exactly in sim.

### v2 Taylor correction (latency 8)

The quarter index splits into **coarse `t` + fine `f`**. The table is read at the coarse-bin midpoint
giving the v1 full-circle values `S = A·sin(P_c)`, `C = A·cos(P_c)`; the centered fine residual
`δ = (fine − 2^(f-1))·2^-(t+f)·(π/2)` radians is then corrected with one rotation term:

```
sin_out = round( S + δ·C )      cos_out = round( C − δ·S )
```

Two non-obvious points:

- **Correct *after* the quadrant signs.** Applying the fold/negate first bakes the per-quadrant
  derivative sign-flips into `S`/`C`, so the correction has **no per-quadrant cases**. Correcting
  before the fold is a classic bug — the residual's sign flips in descending quadrants.
- **The only irrational factor is `π/2`.** `δ`'s `2^-(t+f)` is a free binary-point placement, so a
  fabric KCM (`use_dsp=no`) injects the `π/2` constant; the two DSP48E2 are spent only on the `δ·C`,
  `δ·S` products. The final round is split across two stages (s6 accumulate, s7 saturate) — the fused
  add+clamp was a long critical path, and splitting it costs one free latency cycle.

**Overflow corner.** First-order Taylor drops the one-signed 2nd-order term, largest at the ±peak, so
near `P = ±π/2` the rounded value can land at `Amax+1` and wrap. The output therefore **saturates to
`[−Amax, +Amax]`**. Saturation is lossless for faithful configs (the peak real value is `< Amax` and
the error `< 1` ulp, so it never triggers) and bounds the error within the accuracy bound for coarse
`t`. Worst-case error is the sum of the Taylor remainder `Amax·π²·2^-(2t+5)`, table-entry rounding,
the δ-KCM rounding, and the final round; at the default `(n_p,n_a,t,gw,Pb) = (16,16,8,2,10)` it is
≈ 0.81 code — faithful.

### CORDIC method

A thin wrapper that drives a [Cordic](Cordic.md) with `xy = (Amax, 0)` and `z = phase`: the rotated
vector's real part is `Amax·cos(P)` (→ `cos`), the imaginary part `Amax·sin(P)` (→ `sin`). No
separate amplitude multiply. Used in `SinCosSim`/`SinCosSfdrSim` as a third comparable converter.

## Interface & configuration

```scala
case class SinCosParams(
  phaseWidth = 16,  // n_p
  ampWidth   = 16,  // n_a
  tableIndexBits = 11,  // t (≤ phaseWidth-2; Taylor needs ≤ phaseWidth-3 for ≥1 fine bit)
  method = SinCosMethod.Lut,   // Lut | Taylor | Cordic
  tableGuardBits = 2,   // gw: extra fractional bits per table entry (Taylor)
  piScaleBits = 10)     // Pb: fractional bits of the π/2 KCM constant (Taylor)

io.cmd : slave  Flow(SinCosCmd)   // .phase  — no backpressure, II = 1
io.rsp : master Flow(SinCosRsp)   // .sin, .cos — valid is cmd.valid delayed by `latency`
def latency: Int                  // 4 (Lut) / 8 (Taylor) / Cordic.latency (Cordic)
```

`tableIndexBits` is the central accuracy/storage knob for the LUT modes; larger `t` is finer but
costs BRAM. For Taylor the `t`/`f` split trades table size against correction precision (see the
source for the exhaustive field list and the sizing table). The full circle is always resolved — at
the default 16-bit configs the sim sweep is exhaustive.

## Verification

`riscq.dsp.sim.SinCosSim` runs the two-tier check (bit-exact Scala mirror, zero tolerance every
cycle; analytic accuracy bound, no fudge factor) plus the range and II=1/fixed-latency scoreboard,
**exhaustively over all 2^n_p phases** at the 16-bit configs, for all three methods.
`riscq.dsp.sim.SinCosSfdrSim` is the spectral bench: it feeds a software phase accumulator, FFTs the
output, and reports the worst spur. Measured SFDR (coherent full-permutation sweep, which carries
~48 dB of processing gain — the per-tone spec is the lower number that matters):

| Config | SFDR (dBc) | Limited by |
|---|---|---|
| v1 `(16,16,11)` | 78.0 | phase truncation (`= −6.02·(t+2)`, validates the bench) |
| v2 `(16,16,8)` | 118.7 | amplitude / Taylor residual |
| CORDIC `(16,16)` | 114.6 | CORDIC angle residual / amplitude |

Resource parity is benchmarked against the Xilinx DDS Compiler v6.0 IP (96 dBc, 16-bit two's
complement, OOC on `xczu49dr-ffvf1760-2-e`): v2 `SinCos` is BRAM-lighter and DSP-equal (2 DSP), and
LUT-heavier (it does the fold/sign/round/saturate in fabric where the IP leans on BRAM+DSP); the FF
gap is the IP's phase accumulator, which `SinCos` does not include. Post-route v2 fmax is ~830 MHz
OOC on the `-2` part.

## Usage

```bash
mill runMain riscq.dsp.SinCosGen          # SinCos.v (v1) + SinCosTaylor.v (v2)
mill runMain riscq.dsp.sim.SinCosSim      # golden: bit-exact + bound + range + II=1
mill runMain riscq.dsp.sim.SinCosSfdrSim  # spectral SFDR bench
```

`Mem` init values are computed at elaboration with JVM `math.sin` (double, exact to ~52 bits);
compute in `Double`, round once. Instantiate the component **inside** `SimConfig.compile { }`
(GlobalData gotcha); expose internals to the TB with `simPublic`.

## Related

- [Cordic](Cordic.md) — the engine behind the CORDIC method and the pulse generator's rotation.
- [DSP48](DSP48.md) — how the Taylor δ-products pack into DSP48E2 slices.
- [DSP overview](DSP.md) — the `SF(n)` format, phase encoding, and the II=1 contract.
- [CarrierBatchGenerator](CarrierBatchGenerator.md) — the parallel-carrier user of phase generation.
