# AdderTree — pipelined balanced reduction to one sum

**Source:** `src/riscq/dsp/AdderTree.scala` · **Package:** `riscq.dsp` · **Type:** object (helper)

A balanced, fully-registered integer reduction of a lane vector to a single sum, one result per clock
(**II = 1**). A thin wrapper over SpinalHDL's `reduceBalancedTree`:

```scala
object AdderTree {
  def apply(inputs: Seq[SInt], accW: Int): SInt =
    inputs.map(_.resize(accW bits)).reduceBalancedTree(_ + _, (s, _) => RegNext(s))
  def latency(n: Int): Int = log2Up(n)
}
```

## Role in the system

Used wherever several lanes have to be **summed into one** at the converter edge:

- [`ReadoutDecoder`](ReadoutDecoder.md) sums its per-lane [`ComplexMul`](ComplexMul.md) products
  (`treeR`, `treeI`) into the windowed integral.
- [`PulseTableSoc`](../soc/PulseTableSoc.md) **sums co-mapped channels** onto a shared DAC: when
  several pulse channels map to one converter, their batch lanes are reduced with `AdderTree`; the
  ADC side likewise sums buffered input lanes.

It is a building block, not a `Component` — it returns an `SInt` you wire into the enclosing datapath.

## Why it is built this way

Two deliberate choices, both load-bearing:

- **Pre-resize each lane to `accW` once, up front** (sign-extend), so the non-growing `_ + _` inside
  the tree can never overflow. The true sum of `n` lanes of `inWidth` needs only
  `inWidth + log2Up(n)` bits, and the caller sizes `accW` well above that. Resizing once means every
  adder adds at full width with **no per-level growth and no index arithmetic** — the tree is just
  `RegNext(a + b)` repeated.
- **`levelBridge = RegNext` registers *every* tree level**, including the odd element carried over at
  a level with an odd input count. So all paths see the same depth, the latency is a uniform
  `log2Up(n)` regardless of whether `n` is a power of two, and Vivado is free to retime/pack the
  adders.

The resize is combinational (no latency); the first register stage is the first add level. A
single-input tree is the degenerate case — `reduceBalancedTree` returns the resized input
**un-registered**, so `latency(1) = 0`.

## Latency / timing

`latency(n) = log2Up(n)` register stages, II = 1 (one full input vector reduced per cycle). Because
the latency is exported and consumed by callers (the readout decoder's lead-time math depends on it),
the sim's first check is that the exported latency matches the hardware.

## Verification

`riscq.dsp.sim.AdderTreeSim`:

- **Latency** — a single held input vector into a zeroed tree reaches the expected sum after exactly
  `AdderTree.latency(n)` rising edges, validating the exported latency.
- **Bit-exact + II = 1** — streaming random vectors, the output every cycle equals the
  two's-complement-wrapped integer sum of the inputs driven `latency` edges earlier (a depth-`L`
  scoreboard), so the tree produces one correct result per cycle.

Covers power-of-two and non-power-of-two `n` (the odd-element-through-a-register path keeps latency
uniform) and the single-input degenerate case (latency 0).

```bash
mill runMain riscq.dsp.sim.AdderTreeSim
```

## Related

- [ComplexMul](ComplexMul.md) — its per-lane products are what the readout decoder's trees sum.
- [ReadoutDecoder](ReadoutDecoder.md) — the windowed-integrate user.
- [PulseTableSoc](../soc/PulseTableSoc.md) — the co-mapped-channel summing at the converter edge.
- [DSP overview](DSP.md) — `SF(n)` format and the II=1 contract.
