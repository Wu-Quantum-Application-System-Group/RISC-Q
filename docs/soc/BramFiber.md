# BramFiber — clock-crossing BRAM-blackbox bank as a fabric slave

**Source:** `src/riscq/soc/fabric/BramFiber.scala` · **Package:** `riscq.soc.fabric` · **Type:** fabric fiber (`Area`)

## Purpose

A drop-in sibling of [`DualClockRamFiber`](DualClockRamFiber.md): the same clock-crossing fabric-memory
bank, but backed by the Xilinx HDL-template blackbox [`Bram`](../memory/Bram.md) (inferred from
`BramBlackBox.v`) instead of a SpinalHDL `Mem`. Same shape, same ports, same role — one `up` Tilelink
node fanning `num` consecutive windows to per-RAM host-side slaves, with each `fastPort` left for the
caller's datapath.

## Role in the system

Used for the **`robs` readout-trace buffers** in [`PulseTableSoc`](PulseTableSoc.md): the datapath
writes an ADC trace on pulse fire at `dspCd` (`fastPort`) and the host reads it back over the fabric at
`hostCd` (`slowPort`) — so the host-readable side genuinely needs the read/write slave. The per-core
**pulse-envelope memories** the host only *loads* (never reads back) instead use the write-only sibling
[`BramWriteFiber`](BramWriteFiber.md), which drops the read path and the wide `WidthAdapter`. (The CPU
instruction/data RAM in [`RiscvSoc`](RiscvSoc.md) uses the `Bram`/`Uram` blocks directly rather than
through this fiber.)

```
hostCd  ── up : Node ──► [ ram(i).slowPort ║ Bram (BramBlackBox.v) ║ ram(i).fastPort ] ◄── readout-trace writer (dspCd)
```

## Design rationale

- **Why a sibling at all.** The two fibers are interchangeable from the fabric's point of view; the
  choice is purely about how the BRAM is brought into the netlist. `BramFiber` wraps the Xilinx HDL
  template directly (a blackbox), which gives explicit control over the BRAM primitive, where
  `DualClockRamFiber` relies on SpinalHDL `Mem` inference.
- **`withOutReg` maps onto the blackbox's output-register select.** `Bram`'s `highPerformance` output
  register becomes `outReg = withOutReg` (read latency 1 vs 2). Because it applies to *both* ports, the
  host-side latency matches the [`TileLinkMemReadWriteFiber`](TileLinkMemFiber.md)'s `withOutReg`
  reorder-buffer depth — the same latency-matching contract as the `DualClockRam` sibling.
- **Identical bank layout.** `step = 1 << log2Up(width*depth/8)`; each RAM's `slowPort` becomes a
  `TileLinkMemReadWriteFiber` connected `at SizeMapping(i*step, step) of up`. The `fastPort` is exposed
  raw for the caller's datapath.

## Interface & configuration

- `num`, `width`, `depth` — bank count and per-RAM geometry.
- `hostCd` — host (`slowPort`) side **and** fabric clock domain.
- `dspCd` — device (`fastPort`) side clock domain.
- `withOutReg` (default true) — output-register / read-latency select.

Exposes `up` (slave node) and `rams` (for the caller to reach each `fastPort`).

## Verification

The backing blackbox is signed off by [BramSim](../memory/Bram.md) (BRAM-blackbox golden, driving the
`.v`); the fiber bank is exercised through the assembled SoC by [PulseTableSocSim](PulseTableSoc.md).

```bash
mill runMain riscq.memory.sim.BramSim
```

## Related

[BramWriteFiber](BramWriteFiber.md) (the write-only sibling) ·
[DualClockRamFiber](DualClockRamFiber.md) (the `Mem`-inferred sibling) · [Bram](../memory/Bram.md) ·
[TileLinkMemFiber](TileLinkMemFiber.md) · [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md)
