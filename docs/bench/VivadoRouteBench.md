# VivadoRouteBench — post-place&route fmax bench (the authoritative number)

**Source:** `src/riscq/bench/VivadoRouteBench.scala` · **Package:** `riscq.bench` · **Type:** `object`

## Purpose

The post-**place&route** sibling of [VivadoBench](VivadoBench.md), and equally **DUT-agnostic**. It
runs the full `synth → opt → place → route` flow (still out-of-context on the target part) and
re-parses `report_timing`, so you get the **real achievable fmax and the real critical-path shape after
placement** — the authoritative, sign-off number. Use it to confirm where the post-route critical path
actually is before committing to a structural change that OOC synthesis alone suggested.

## How it differs from VivadoBench

[VivadoBench](VivadoBench.md) runs synthesis only: its net delays are pre-placement *estimates* —
optimistic and rough, never sign-off, but fast enough for the edit/measure loop. `VivadoRouteBench`
actually places and routes, so its numbers reflect real interconnect. On this FPGA routing dominates
the critical path, so the route number can differ substantially from the synth estimate — which is
exactly why a structural change is confirmed here, not on the synth estimate.

It is a **thin wrapper**: it asks `VivadoBench.runVivado(dut, ws, route = true)` to run the same flow
with place&route enabled, and the report is parsed and printed by `VivadoBench`'s own `parse` /
`printReport` (identical format) — only the numbers are post-route. It shares all of `VivadoBench`'s
configuration (`RISCQ_VIVADO_BIN`, `RISCQ_DEVICE`, `RISCQ_FMAX_TARGET_MHZ`, `RISCQ_FMAX_TOP_PATHS`) and
its determinism (Vivado synth/P&R is reproducible, Verilog-order-insensitive).

## Cost

Slower than [VivadoBench](VivadoBench.md) because it places and routes — but a small module is still a
few **minutes**, not an hour. The whole multi-qubit SoC is the heavy case; single modules and the bare
core are quick enough to run on demand.

## Usage

No `main` — drive it from a per-DUT wrapper that supplies a [`Dut`](README.md):

```bash
mill runMain riscq.riscv.bench.VivadoRouteBench
mill runMain riscq.dsp.pulse.bench.VivadoRouteBench
```

The full per-design ZCU216 place&route flows (with the production floorplan baked in) live under
`vivado-scripts/` — see `vivado-scripts/README.md`. This bench is the OOC, single-module version of
that.

## Related

- [VivadoBench](VivadoBench.md) — the fast synthesis-only estimate (same parser/report).
- [PblockBench](PblockBench.md) — the floorplan-fit engine (also routes, but answers FIT/NOFIT).
- [README](README.md) — the `riscq.bench` engine overview.
