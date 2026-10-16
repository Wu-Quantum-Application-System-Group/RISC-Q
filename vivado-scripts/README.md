# vivado-scripts — Vivado flows for `PulseTableSoc` on the ZCU216 (xczu49dr)

Two self-contained Vivado flows for the SpinalHDL [`PulseTableSoc`](../src/riscq/soc/PulseTableSoc.scala)
on the Zynq UltraScale+ RFSoC `xczu49dr-ffvf1760-2-e` (ZCU216; built with the `vivado` on `PATH`,
currently 2026.1). Each lives in its own
subfolder and is driven by a single `build-*.sh` script.

| | [`riscvsoc/`](riscvsoc/) | [`riscvsoc-bd/`](riscvsoc-bd/) |
|---|---|---|
| what | standalone **OOC place&route bench** — the SoC in isolation | **block-design** build — the SoC in a real ZCU216 image |
| driver | `riscvsoc/build-riscvsoc.sh` | `riscvsoc-bd/build-riscvsoc-bd.sh` |
| RTL | `GenPulseTableSocOoc` — `vivado=false`, plain `dspClk`/`clk` ports | `GenPulseTableSocJson <config> <dir> vivado` — `vivado=true`, `hostClk` + `X_INTERFACE`, `ClockInterface.v` |
| context | SoC alone, OOC | `PulseTableSoc` packaged as a user IP + Zynq PS + RF Data Converter + AXI SmartConnect |
| P&R | non-project `synth → place → route` (pure Tcl) | project `synth_1` (BD wrapper) + SoC as an OOC IP child run, then `impl_1` |
| purpose | reproduce the floorplanned timing number (`dspClk` WNS ≈ −0.156 ns) | the board image (`PulseTableSoc.bit` + `.xsa`); measures the block-design / real-device penalty vs that number |

Both bake the same recipe (per-core X0 floorplan + datapath confine + `df`/`1h`/retiming levers); the
RTL-level levers are constructor defaults of `PulseTableSoc`, the floorplan is the per-flow `pblocks-*.tcl`.
See "The two-region floorplan" in [`docs/soc/ARCH.md`](../docs/soc/ARCH.md).

## Build outputs — one folder per design under `<repo>/build/`

Each flow generates its RTL, runs Vivado, and writes every report + `vivado.log` into a single
git-ignored folder under the **repo root** `build/`:

- `riscvsoc/build-riscvsoc.sh`  → `../../build/riscvsoc/`
- `riscvsoc-bd/build-riscvsoc-bd.sh` → `../../build/riscvsoc-bd/`

Nothing is written under `vivado-scripts/` itself, so the scripts tree stays clean. Set
`RISCQ_PROJ_NAME=<name>` to pick a different folder and **evaluate several designs in parallel** without
clobbering each other:

```bash
RISCQ_PROJ_NAME=riscvsoc-a ./riscvsoc/build-riscvsoc.sh &
RISCQ_PROJ_NAME=riscvsoc-b ./riscvsoc/build-riscvsoc.sh &   # → build/riscvsoc-a, build/riscvsoc-b
```

## Quick start

```bash
cd vivado-scripts/riscvsoc      && ./build-riscvsoc.sh        # OOC bench, 14q  → <repo>/build/riscvsoc
cd vivado-scripts/riscvsoc-bd   && ./build-riscvsoc-bd.sh     # block design, 14q → <repo>/build/riscvsoc-bd
```

`RISCQ_QUBITS=3 ./build-riscvsoc.sh` (OOC) or `RISCQ_CONFIG=<SocSpec JSON> ./build-riscvsoc-bd.sh` (block
design; default `software/configs/zcu216-14q.json`) runs a smaller config for fast iteration. Each
subfolder's `README.md` documents its full env-knob set, recipe, and the reports it writes.

## Per-cone timing tracking — `report-cones.tcl`

Both flows classify every failing endpoint of the routed design into named logic cones — the RISC-V
core's (C1 jumpAt-broadcast / C2 fetch-front-CE / C3 operand-ALU) and the SoC datapath's (RF
channel/buf/link, CORDIC, TimedQueue, ADC pipe, decoder, …; the full list heads `report-cones.tcl`),
writing into the build folder:

- `cones_impl.rpt` — cone × {n, TNS, worst slack, CE/D/SR endpoint-pin split} + unmatched shapes
- `cones_paths.tsv` — the raw worst-path-per-endpoint dump for offline drill-down

The cone table is the **stable unit of timing tracking** (individual failing paths shuffle between
builds; the cone totals move coherently) — gate every timing lever on a cone-level number, not a WNS
glance. Runs standalone on any routed checkpoint too:

```bash
vivado -mode batch -source report-cones.tcl -tclargs <routed.dcp> [outdir]
```
