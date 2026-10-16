# DSP — signal-generation & decoding package (`riscq.dsp`)

**Package:** `riscq.dsp` (+ `riscq.dsp.pulse`) · **Type:** package overview

The DSP package holds the real-time signal datapath: the arithmetic primitives (sin/cos, CORDIC,
complex multiply, adder tree) and the pulse-side modules built from them (carrier/phasor batch
generation, envelope replay, the pulse generator, and the readout decoder). The RISC-V core writes
control parameters; these modules turn them into DAC samples and turn ADC samples back into a 1-bit
readout result, in real time at the converter rate.

This file is the package reference: the **shared number format**, the **phase encoding**, the
**design philosophy** common to every block, and an **index** of the per-module docs. Read it before
the component docs — the format and the II=1 contract below are assumed throughout and are hard to
recover from the code.

---

## 1. Number format — signed fixed-point `SF(n)`

Every value in the datapath uses one signed fixed-point format, **`SF(n)`**: an `SInt(n bits)` code
`x` represents the real number

```
value(x) = x · 2^-(n-1)        ∈ [-1, +1)
```

i.e. two's complement with the binary point right after the sign bit. `0b011` (n=3) is
`0.11₂ = +0.75`; `0b100` is `-1.0`. **`+1.0` is not representable** — the largest magnitude is
`Amax = 2^(n-1) − 1` codes (`= 1 − 2^-(n-1)` in value). [`Complex(width)`](ComplexMul.md) is just a
`Bundle` of two `SF(width)` `SInt`s (`re`, `im`); there is **no `AFix`** anywhere in the pulse
datapath — the format is carried by convention, not by a type.

### 1.1 Amplitude convention

Generators produce a **scaled** waveform with amplitude `A = Amax · 2^-(n-1) = 1 − 2^-(n-1)` rather
than saturating the single unrepresentable `+1.0` point. Scaling the whole waveform is a pure gain —
it adds no distortion and is the convention the Xilinx DDS compiler uses. **Every golden model
includes the factor `A`.** Internal datapaths carry guard bits and round once at the output;
multiplies (`|a·b|` can reach 2) saturate to `[−Amax, +Amax]` rather than wrapping.

### 1.2 Phase encoding

A phase input `x` (`SInt(phaseWidth bits)`) encodes the angle

```
P(x) = value(x) · π            ∈ [-π, +π)
```

Three consequences are load-bearing across the whole signal side:

- **Wraparound is exact.** Adding two phases (or a phase increment) with plain `SInt` overflow wraps
  `+π−ε → −π`, which is the correct phase wrap. NCOs and phase accumulators need no special wrap logic.
- **The top two bits are the quadrant.** Read the bits as unsigned `u = x.asBits.asUInt`; then
  `quad = u[n_p-1 : n_p-2]` selects the quadrant, and the remaining `m = n_p − 2` bits index the
  position within it. This is what [SinCos](SinCos.md)'s quarter-wave fold and [Cordic](Cordic.md)'s
  π pre-rotation key off.
- `0b0100…0 → +π/2`, `0b1000…0 → −π`. `−π` is just a valid input, never a special case.

---

## 2. Design philosophy

**Initiation interval 1, latency unconstrained.** Every block accepts a new sample every clock
(`II = 1`) and is a straight feed-forward pipeline that may take as many stages as it needs. The
interfaces are `Flow` (valid + payload, **no ready**): the pipeline never stalls, so there is no
backpressure to model. `rsp.valid` is always `cmd.valid` delayed by a compile-time `latency`, which
each block **exports** as a `def latency`; toplevels derive all lead times from those exports (no
literal latencies), and the end-to-end testbenches re-verify the delay.

This makes latency cheap: adding a pipeline stage to close timing ripples automatically through the
lead-time math, and bit-exactness is independent of pipeline depth (the golden model never changes),
so restructuring is safe. fmax is a soft constraint per the project guideline — pipeline generously,
measure later with the `riscq.bench` engines.

**Two-tier verification.** Each block has a self-checking SpinalSim that runs (1) a **bit-exact**
Scala mirror the DUT must match every cycle with zero tolerance — catching sign/width/addressing
bugs — and (2) an **analytic bound** against the ideal real-valued function, asserted with no fudge
factor (if the bound fails by a fraction of a ulp, the analysis is wrong). Plus a range assertion
(the most-negative code never appears, no overflow wrap) and an II=1 / fixed-latency scoreboard. This
mirrors the core's lock-step-against-RVLS philosophy.

**Components, not plugins.** The DSP blocks are leaf `Component`s, not `FiberPlugin`s, so they can be
instantiated many times (×N parallel DAC lanes, per-qubit readout mixers) without plugin ceremony.
The FiberPlugin host, Tilelink, and the SoC glue wrap them at the [soc](../soc/PulseTableSoc.md) level.

---

## 3. Capabilities

| Module | Role | Cost (default cfg) | Latency / II |
|---|---|---|---|
| [`SinCos`](SinCos.md) | phase → `(cos, sin)` (LUT / Taylor / CORDIC) | LUT: 1 BRAM; Taylor: ~256-LUTRAM + 2 DSP | 4 / 8 / ~20, II=1 |
| [`Cordic`](Cordic.md) | rotate `xy · exp(iπ·z)`, gain-corrected | 0 DSP (1/K KCM in fabric) | `1 + iters + 1 + 1` (~20 @ 16b), II=1 |
| [`ComplexMul`](ComplexMul.md) | complex product `a·b` (3-mult Karatsuba) | 3 DSP48E2 | 6 (7 with saturate), II=1 |
| [`AdderTree`](AdderTree.md) | balanced registered sum of `n` lanes | adders only | `log2Up(n)`, II=1 |

Pulse-side modules (in `riscq.dsp.pulse`, built from the above):

- [`CarrierBatchGenerator`](CarrierBatchGenerator.md) / [`PhasorBatchGenerator`](PhasorBatchGenerator.md)
  — parallel carrier / phasor batches feeding the multi-GS/s DACs.
- [`EnvelopeReader`](EnvelopeReader.md) — host-RAM complex envelope replay.
- [`PulseGenerator`](PulseGenerator.md) / [`PulseGeneratorAligned`](PulseGeneratorAligned.md) — the
  complex-envelope pulse datapath (CORDIC rotation × envelope) with the exact `[startTime,
  startTime+dur)` valid window.
- [`ReadoutDecoder`](ReadoutDecoder.md) — demod against a reference carrier (an envelope-shaped
  [`PulseGenerator`](PulseGenerator.md) pulse in the SoC), integrate over the carrier's own valid
  window (the carrier triggers the decoder), 1-bit discrimination.
- [`TimedQueue`](TimedQueue.md) — the time-ordered command scheduler shared by the pulse side.

Arithmetic-mapping reference: [DSP48.md](DSP48.md) — how SpinalHDL multiply/multiply-add pipelines
pack onto the DSP48E2 slice, and the levers to steer that packing for fmax. Read it before any
DSP-heavy datapath.

---

## 4. Generate & simulate

```bash
mill runMain riscq.dsp.SinCosGen                 # emit SinCos Verilog
mill runMain riscq.dsp.sim.SinCosSim             # SinCos golden (bit-exact + bound + range + II=1)
mill runMain riscq.dsp.sim.SinCosSfdrSim         # SinCos spectral (SFDR) bench
mill runMain riscq.dsp.sim.CordicSim             # CORDIC golden
mill runMain riscq.dsp.sim.ComplexMulSim         # complex-multiply golden
mill runMain riscq.dsp.sim.AdderTreeSim          # adder-tree golden
```

The pulse-side sims and the `*Gen` / `PathAnalysis` / `VivadoBench` / `VivadoRouteBench` targets are
listed in the per-module docs and in the [bench docs](../bench/README.md).

---

## 5. Related

- [SinCos](SinCos.md) · [Cordic](Cordic.md) · [ComplexMul](ComplexMul.md) · [AdderTree](AdderTree.md)
  · [DSP48](DSP48.md)
- Pulse side: [PulseGenerator](PulseGenerator.md) · [ReadoutDecoder](ReadoutDecoder.md) ·
  [CarrierBatchGenerator](CarrierBatchGenerator.md) · [PhasorBatchGenerator](PhasorBatchGenerator.md)
- SoC integration: [PulseTableSoc](../soc/PulseTableSoc.md) · [ARCH](../soc/ARCH.md)
