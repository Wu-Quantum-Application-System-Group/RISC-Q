# riscq.bench — DUT-agnostic analysis & fmax/floorplan benches

**Package:** `riscq.bench` · **Source:** `src/riscq/bench/*.scala`

The shared tooling for *analysing*, *fmax-benching*, and *floorplan-fitting* any module in the repo —
the RISC-V core, a DSP module, a memory block, the whole SoC, or a one-off experiment. Every engine
here is **DUT-agnostic by design**: none imports a concrete module. You point it at a netlist and it
answers one question. The per-area `riscq.*.bench.*` objects (e.g. `riscq.riscv.bench.VivadoBench`,
`riscq.dsp.pulse.bench.VivadoRouteBench`, `riscq.memory.bench.UramVivadoBench`) are **thin entry
points**: each owns its module's knobs (plugin set, params), builds the netlist, hands it to one of
these engines, and has the `main`. The engines below have no `main`.

## The shared `Dut` seam

The three Vivado-driven engines take a `Dut`:

```scala
case class Dut(label: String, emit: String => SpinalReport[_ <: Component])
```

— a human label for the logs plus a function that **emits the DUT's Verilog into the given workspace
directory and returns the SpinalHDL report** (the engine reads the toplevel name and the list of
generated RTL sources out of it). This is the one decoupling that lets the same flow time the core, a
DSP block, or the SoC:

```scala
Dut("riscq core", ws => SpinalConfig(targetDirectory = ws).generateVerilog(Riscq(plugins)))
```

`PathAnalysis` is the exception — it works on an *already-elaborated* netlist with no tool in the
loop, so it takes a `Component` or a `SpinalReport` directly (`PathAnalysis(SpinalVerilog(MyModule()))`).

## Which engine when

| Engine | Tool? | What it answers | Cost |
|---|---|---|---|
| [PathAnalysis](PathAnalysis.md) | none (static) | *Where* is the deep logic / high fanout? Pipeline latency between two signals? | instant |
| [VivadoBench](VivadoBench.md) | Vivado synth (OOC) | Estimated fmax + the *shape* of the top critical paths | seconds–minute |
| [VivadoRouteBench](VivadoRouteBench.md) | Vivado synth→place→route (OOC) | The **authoritative** post-route fmax + real critical path | minutes |
| [PblockBench](PblockBench.md) | Vivado synth + hard-Pblock place(/route) | **FIT / NOFIT** — do these cells fit a clock-region rectangle? | minutes |

The natural workflow: `PathAnalysis` (free, narrows *where* to look) → `VivadoBench` (fast
edit/measure loop on the critical-path *shape*) → `VivadoRouteBench` (sign-off the number before
committing a structural change) → `PblockBench` (size the floorplan rectangle). `PathAnalysis` reports
SpinalHDL-level proxies, **not nanoseconds** — always confirm a path with Vivado. `VivadoBench` is
synthesis-only and therefore **optimistic** vs the routed number `VivadoRouteBench` gives.

`VivadoBench` and `VivadoRouteBench` share Vivado settings via the environment (defaults match the
riscq RFSoC target): `RISCQ_VIVADO_BIN`, `RISCQ_DEVICE` (default `xczu49dr-ffvf1760-2-e`),
`RISCQ_FMAX_TARGET_MHZ` (the tight clock; default 1000), `RISCQ_FMAX_TOP_PATHS`. `PblockBench` adds
the `RISCQ_PBLOCK_*` floorplan knobs and `RISCQ_RETIMING` — see its doc.

> **Vivado synthesis is fully deterministic and Verilog-order-insensitive** — zero run-to-run
> spread, and signal-declaration order has no effect on the result. A re-run reproduces the number.

## Docs

- [PathAnalysis.md](PathAnalysis.md) — static, pre-synth longest-path / fanout / register-level views.
- [VivadoBench.md](VivadoBench.md) — synthesis-only estimated fmax + critical-path shape.
- [VivadoRouteBench.md](VivadoRouteBench.md) — full place&route, the authoritative post-route number.
- [PblockBench.md](PblockBench.md) — OOC floorplan-fit (FIT/NOFIT + per-resource utilisation).

## Related

- [../soc/ARCH.md](../soc/ARCH.md#the-two-region-floorplan) — the two-region floorplan these benches size.
- `vivado-scripts/README.md` — the production ZCU216 flows that bake the proven floorplan.
