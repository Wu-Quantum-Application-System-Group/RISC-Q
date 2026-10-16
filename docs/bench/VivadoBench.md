# VivadoBench — synthesis-only fmax bench (estimated fmax + critical-path shape)

**Source:** `src/riscq/bench/VivadoBench.scala` · **Package:** `riscq.bench` · **Type:** `object` (+ the `Dut` case class)

## Purpose

A small, self-contained, **DUT-agnostic** Vivado fmax benchmark whose report we **fully control**. It
synthesises a module out-of-context against a deliberately tight clock, then parses Vivado's own
`report_timing` output and prints the estimated fmax *and* the top critical paths — the thing the
edit/measure loop actually needs: not just the headline number, but **where** to optimise.

It exists because `spinal.lib.eda.bench.Bench` + `VivadoFlow` throw away everything except one fmax
number and a utilisation string — Vivado's per-path critical-path detail is never surfaced. This tool
keeps it.

## What it does, and why synthesis-only

- Emits the DUT's RTL into the workspace (via the [`Dut`](README.md) seam), reads it back, constrains a
  tight `create_clock` on `clk` (the SpinalHDL default clock-port name), runs `synth_design
  -mode out_of_context` on the target part, and dumps `report_timing` + summary + utilisation +
  control-sets reports.
- **Synthesis-only is intentional:** fast enough for an edit → measure loop, and the *shape* of the
  critical path (logic depth, logic/route split, cell mix) is what you tune. It is **optimistic** vs
  post-route timing because net delays are pre-placement estimates — use [VivadoRouteBench](VivadoRouteBench.md)
  for sign-off. It complements [PathAnalysis](PathAnalysis.md), which gives the same "where" view with
  no tool in the loop at all.

> **Vivado synthesis is fully deterministic and Verilog-order-insensitive** — zero run-to-run spread,
> and signal-declaration order has no effect. The number reproduces on a re-run.

## The report

- **Estimated fmax** from the worst negative slack against the tight clock (achievable period = target
  period − WNS), with the WNS and endpoint count.
- **Top critical paths, collapsed by `(source reg → dest reg)`** — the worst slack per group, so all 32
  bits of one bus (and every high-fanout replica of one register) show as a single "where to optimise"
  row. Each row carries the path's logic depth (levels), the **logic % / route %** split, and the cell
  mix on the path (CARRY8 / LUT… counts straight from Vivado's "Logic Levels"). The register-name
  normaliser strips the pin (`/C`, `/CE`, `/D`), the bit index, the `_reg` suffix, and Vivado's
  `_rep` high-fanout replica suffixes so the grouping is by *logical* register.

The logic/route split is the load-bearing detail: a route-dominated path is a placement/fanout problem,
a logic-dominated one is a depth problem, and they call for different fixes.

## Configuration (environment-overridable; defaults match the riscq RFSoC target)

- `RISCQ_VIVADO_BIN` — Vivado `bin` dir (default: the `vivado` on `PATH`).
- `RISCQ_DEVICE` — target part (default `xczu49dr-ffvf1760-2-e`).
- `RISCQ_FMAX_TARGET_MHZ` — the tight clock to constrain against (default 1000).
- `RISCQ_FMAX_TOP_PATHS` — how many collapsed critical-path rows to print (default 15).

## Usage

This object has **no `main`** — drive it from a thin per-DUT wrapper that supplies a [`Dut`](README.md)
and owns the module's knobs. The core wrapper (`riscq.riscv.bench.VivadoBench`) additionally selects
the plugin set and applies `RiscqParam` env A/B overrides, and supports `--reparse <file.rpt>` to
re-print an existing report without re-running Vivado:

```bash
mill runMain riscq.riscv.bench.VivadoBench
mill runMain riscq.dsp.bench.VivadoBench
```

A new bench is just `Engine.printReport(Engine.parse(Engine.runVivado(dut, ws)))`.

## Related

- [VivadoRouteBench](VivadoRouteBench.md) — same parser/report, but post-place&route (sign-off).
- [PathAnalysis](PathAnalysis.md) — the no-tool, pre-synth "where to optimise" view.
- [PblockBench](PblockBench.md) — the floorplan-fit sibling (does it fit, vs how fast).
- [README](README.md) — the `riscq.bench` engine overview.
