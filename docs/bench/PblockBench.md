# PblockBench — OOC floorplan-fit bench (does it fit a clock-region rectangle?)

**Source:** `src/riscq/bench/PblockBench.scala` · **Package:** `riscq.bench` · **Type:** `object`

## Purpose

A small, self-contained, **DUT-agnostic** "does it fit?" benchmark. Given a module and a target Pblock
region (a rectangle of clock regions), it synthesises the module out-of-context, pins the **whole
netlist into a hard Pblock** over that region, runs `place_design` (and optionally `route_design`), and
reports whether the design fits — both as the placer's pass/fail verdict and as a per-resource
utilisation table.

It answers a **different question** from [VivadoBench](VivadoBench.md) (*how fast?*) — namely *can
these cells live in this much silicon?* — which is exactly what a floorplanner asks when sizing the
per-core Pblock anchors of the two-region floorplan (see [../soc/ARCH.md](../soc/ARCH.md#the-two-region-floorplan)). It
mirrors the production floorplan scripts (`vivado-scripts/riscvsoc/pblocks-riscvsoc.tcl`,
`vivado-scripts/riscvsoc-bd/pblocks-bd.tcl`) so a FIT here predicts a fit there.

## The two proven idioms it bakes in

1. **`IS_SOFT FALSE` — a hard Pblock.** A soft/advisory Pblock is ignored by the placer, so it cannot
   give a real fit verdict; the Pblock is made hard so `place_design` actually has to honour it.
2. **The BRAM/DSP/URAM site-float trick.** A small clock-region rectangle may contain *no* BRAM (or
   DSP, or URAM) column at all. Pinning those hard-macro cells into it would fail for **geometry, not
   capacity**. So the listed hard-macro **site types are removed from the Pblock** ("floated"): their
   cells drift to the nearest column while the logic stays contained. The default float set is `RAMB`;
   widen to `RAMB,DSP,URAM` to fit pure logic into a region with no DSP/URAM column, or empty to pin
   everything strictly.

## The two views it reports

- **Placement verdict — FIT / NOFIT.** `place_design` against a hard Pblock errors when the cells
  cannot be legally placed: capacity *or* CLB-shape constraints (e.g. a MUXF8 tree needs a whole
  column). That error is caught and reported as **NOFIT** with the reason.
- **Capacity table** from `report_utilization -pblocks`: Used vs Available per resource (CLB LUTs, CLB
  Registers, CARRY8, F7/F8 muxes, Block RAM Tile, DSPs, URAM), with an `ok` / `OVER` / `float` marker
  and the **binding resource** (highest util%). A NOFIT is thus explained (which resource overflowed,
  or — if nothing overflowed — a CLB-shape/column constraint), and a FIT shows the headroom. When
  routed, the post-route WNS / fmax is appended as a soft, recorded-not-tuned sign-off.

## Configuration (environment-overridable)

- `RISCQ_VIVADO_BIN`, `RISCQ_DEVICE` (default `xczu49dr-ffvf1760-2-e`), `RISCQ_FMAX_TARGET_MHZ` — the
  Vivado settings + the clock used for timing-driven placement.
- `RISCQ_PBLOCK_REGION` — the region as a clock-region rectangle. Shorthand `X1Y1:X2Y1` expands to
  `CLOCKREGION_X1Y1:CLOCKREGION_X2Y1`; a fully-qualified `CLOCKREGION_…` / `SLICE_…` string passes
  through unchanged (use a `SLICE_…` band for a sub-clock-region rectangle). Default `X1Y1:X2Y1`.
- `RISCQ_PBLOCK_FLOAT` — comma list of `SITE_TYPE` prefixes to float out of the region. **Default
  `RAMB`.**
- `RISCQ_PBLOCK_CLOCKS` — comma list of clock ports to constrain *if present* (best-effort; default
  `clk,dspClk,hostClk` covers single-clock modules and the SoC's two domains).
- `RISCQ_PBLOCK_PLACE` (default true) — run `place_design` for the authoritative verdict; set false for
  a fast **capacity-only** check (synth + Pblock assign + `report_utilization`, no place; the verdict
  then comes from Used-vs-Available).
- `RISCQ_PBLOCK_ROUTE` (default true) — also `route_design`, write a routed checkpoint and dump the
  post-route timing; set false to stop after place.
- `RISCQ_RETIMING` (default false) — add `-retiming` to `synth_design` (and read a pre-synth clock
  XDC on the `RISCQ_PBLOCK_CLOCKS` ports, without which retiming is a no-op).

## Checkpoints

Each stage drops a Vivado `.dcp` into the workspace, so the result is saved, not just the text report
(`open_checkpoint <file>.dcp` to inspect): always `post_synth.dcp` (netlist + Pblock constraints), then
`post_place.dcp` and `post_route.dcp` as the flow reaches them.

## Usage

No `main` — drive it from a per-DUT wrapper supplying a [`Dut`](README.md). `riscq.riscv.bench.RiscqCorePblockBench`
sizes the bare RISC-V core (`Riscq` alone, no RAM or RF datapath) against a per-core band:

```bash
mill runMain riscq.riscv.bench.RiscqCorePblockBench
RISCQ_PBLOCK_REGION=SLICE_X0Y180:SLICE_X8Y239 \
  mill runMain riscq.riscv.bench.RiscqCorePblockBench
RISCQ_PBLOCK_FLOAT=RAMB,DSP,URAM RISCQ_PBLOCK_ROUTE=0 \
  mill runMain riscq.riscv.bench.RiscqCorePblockBench
```

## Related

- [../soc/ARCH.md](../soc/ARCH.md#the-two-region-floorplan) — the two-region floorplan this bench sizes.
- [VivadoBench](VivadoBench.md) / [VivadoRouteBench](VivadoRouteBench.md) — the fmax siblings.
- [README](README.md) — the `riscq.bench` engine overview.
