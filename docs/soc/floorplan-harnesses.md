# Floorplan-exploration harnesses — RiscqFarmTop & RiscqCloneTop

**Source:** `src/riscq/soc/RiscqFarmTop.scala`, `src/riscq/soc/RiscqCloneTop.scala` ·
**Package:** `riscq.soc` · **Type:** `Component` toplevels (OOC bench scaffolding)

These are **developer toplevels for floorplan / fmax experiments**, not part of the shipped SoC. They
stand in for the converter-edge DSP datapath with a cheap placement anchor so a place-and-route trial is
fast and a per-core placement can be measured in isolation. Both build on the hard
[`RiscvSoc`](RiscvSoc.md) unit and rely on its registered posted-link boundary (see [ARCH](ARCH.md#the-two-region-floorplan)):
because the boundary is registered on both sides, swapping the real datapath for a dummy cannot change a
core's internal timing, so the fmax measured here transfers to [`PulseTableSoc`](PulseTableSoc.md).

## RiscqFarmTop — the registered-boundary sweep top

A flat out-of-context `Component` holding `coreNum` [`RiscvSoc`](RiscvSoc.md) hard Components plus a tiny
**dummy register block** per core — no PS, no RFDC, no converters, pure `dspClk` logic. The dummy is a
placement *anchor*: it sinks the core's posted `cmd` (through the down `linkPipe`) into a `DONT_TOUCH`
accumulator and drives `resultIn` (through the up `linkPipe`), so both registered boundary paths are real
and neither port is optimized away. Pinned one clock-region column over from the cores, it gives each
pinned core's link ports a fixed adjacent home (one short hop instead of a die-spanning float). `out`
XOR-reduces every accumulator to one registered pin so synthesis cannot prune the farm.

Key params: `coreNum`, `linkPipe` (per-direction `RegNext` depth on the link), `coreParam` (the
congestion-lean RISC-V config replicated across all cores).

## RiscqCloneTop — the clone-assembly region (`FarmRegion`)

One **floorplan region** for the reuse-clone assembly: a hard `Component` holding `perRegion`
[`RiscvSoc`](RiscvSoc.md) cores, implemented once OOC, pinned to a single clock-region, then imported
(locked) into an assembled top. The region boundary gives Vivado a self-contained module to place+route
in isolation (no inter-region simultaneous-placement congestion), so a region placement that meets timing
transfers to every clock-region row. Two modes: `includeDummies = false` clones only the cores (dummy
anchors live in the parent); `includeDummies = true` also clones each core's dummy placement so it too is
implemented OOC and locked. The per-core body mirrors `RiscqFarmTop` so the measured timing stays
faithful to the farm sweep.

## When to use

Reach for these only when doing floorplan/timing-closure work on the multi-core SoC — to measure a
per-core pblock in isolation, or to validate that a placement clones across rows. For functional or
register-level work use [`PulseTableSoc`](PulseTableSoc.md) and its sims. For generic OOC fmax/floorplan
of any module, see the [bench engines](../bench/README.md) (`VivadoRouteBench`, `PblockBench`).

## Related

[RiscvSoc](RiscvSoc.md) · [ARCH](ARCH.md#the-two-region-floorplan) · [PulseTableSoc](PulseTableSoc.md) ·
[bench engines](../bench/README.md)
