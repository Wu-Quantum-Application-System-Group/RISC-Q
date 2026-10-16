# `riscvsoc/` — standalone Vivado flow reproducing the floorplanned 14q result

This directory reproduces the **floorplanned** timing result with a **complete set of hand-written
Vivado Tcl scripts** instead of a Scala bench. It is a drop-in, scriptable equivalent of a
[`riscq.bench.PblockBench`](../../docs/bench/PblockBench.md) run on the 14-qubit `PulseTableSoc`.

**Target result** (full 14-qubit config, `xczu49dr-ffvf1760-2-e`):
**`dspClk` WNS ≈ −0.156 ns → fmax ≈ 484 MHz**, the worst path **internal to a `RiscvSoc` core**
(`riscqArea_riscqCores_1_riscvSoc/…BranchPlugin_IS_JAL → Execute_LOAD_DATA`); the posted-link
`cmd`/`resultIn` boundary never binds. Background: "The two-region floorplan" in [`docs/soc/ARCH.md`](../../docs/soc/ARCH.md).

## What it does (the recipe, in pure Tcl)

1. Generate the **real** `PulseTableSoc` RTL out-of-context (`vivado=false`, plain `dspClk`/`clk` ports;
   `PulseTableSoc` tags every core `KEEP_HIERARCHY` so the 14 cores stay distinct macros). All other levers — `replicateTime`,
   `aluNoFastForward` (df), `aluResultOneHot` (1h), `linkPipe=4`, the congestion-lean `coreParam` — are
   the `PulseTableSoc` constructor defaults, which already *are* the floorplan stack.
2. **OOC synth with `-retiming`** (a pre-synth clock XDC makes the timing-driven retiming active).
3. Two-clock setup: tight **`dspClk` @ 500 MHz**, loose host **`clk` @ 100 MHz**, the two
   **async-grouped** (the iLoad/CDC crossings).
4. **Floorplan** (`pblocks-riscvsoc.tcl`): pin each `RiscvSoc` core + its replicated `coreTime_i` into a
   hard SLICE band of its X0 row — **3 cores/row × 5 rows Y3–Y7 (3,3,3,3,2)**, `EXCLUDE_PLACEMENT` per
   band — and confine the whole floating DSP/RF datapath to one hard **`X1Y0:X5Y7`** block, so no
   datapath net routes over the X0 core column. BRAM/DSP sites float out of the core bands.
5. **`place_design -directive ExtraNetDelay_high`** → `phys_opt_design` →
   **`route_design -directive AggressiveExplore`** → `phys_opt_design`.
6. Report: `FIT`/`NOFIT` (`fit_result.txt`), the `dspClk`-only worst path (`timing_paths.rpt`), the full
   summary incl. the async reset-recovery group (`timing_summary.rpt` / `timing_paths_all.rpt`), and the
   per-pblock utilisation (`util_pblock.rpt`). Each stage drops a `.dcp` (`post_synth`/`post_place`/`post_route`).

## Run

```bash
cd vivado-scripts/riscvsoc
./build-riscvsoc.sh                      # full 14q recipe (≈ the documented −0.156 ns)
```

Quick variants:

```bash
RISCQ_QUBITS=3 ./build-riscvsoc.sh   # smaller config, fast iteration (3q)
RISCQ_ROUTE=0  ./build-riscvsoc.sh   # stop after place — fast FIT check, no route timing
RISCQ_SKIP_GEN=1   ./build-riscvsoc.sh   # reuse the RTL already generated in the workspace
```

Outputs land in `<repo>/build/riscvsoc/` (git-ignored): `PulseTableSoc.v` + `.bin`, the checkpoints,
`vivado.log`, and the reports above. `build-riscvsoc.sh` echoes the verdict and the worst `dspClk` slack
at the end. Set `RISCQ_PROJ_NAME=<name>` to build into `<repo>/build/<name>` instead (parallel designs).

## Files

| File | Role |
|---|---|
| `build-riscvsoc.sh` | driver: generate RTL via mill, then run Vivado OOC P&R in the workspace |
| `build-coreband.sh` | single-core band bench (`GenCoreBandBench`) — one `RiscvSoc` in SoC context @ 526 MHz |
| `build-dspband.sh` | DSP-cone A/B vehicle (3q `PulseTableSoc`) — same flow on a per-variant **config JSON** |
| `configs/dspband-3q*.json` | one JSON per dspband variant (baseline + one lever each + the stack) |
| `cone-shapes.sh` | mechanism-level shape counts over `cones_paths.tsv` (the dspband verdict currency) |
| `riscvsoc-pnr.tcl` | the OOC flow — config, synth+retiming, two-clock setup, P&R, reports |
| `pblocks-riscvsoc.tcl` | the floorplan — pblock helpers + datapath confine + per-core X0 bands |

`build-dspband.sh` notes: the config JSON is copied into the build folder as `config.json`. Only
`SocSpec` fields are read from it, so of the variant JSONs only `adc_pipe` still changes the RTL — the
`param_buffer_use_mem` / `dc_offset_max_fanout` / `queue_lean_pop` / `queue_reg_head` levers they set
are now baked into the RTL (or no longer plumbed) and those keys are ignored. The default bench target
is 526 MHz, but the calibrated DSP-cone operating point is
**1.8 ns** (`RISCQ_FMAX_TARGET_MHZ=555.56`) — and there per-build slack is placement-chaotic across
different netlists, so lever verdicts are **structural** (shape presence / logic levels / arc counts
via `cone-shapes.sh`), not slack deltas. A full config (e.g. `software/configs/zcu216-14q.json`) with
`RISCQ_FMAX_TARGET_MHZ=500 RISCQ_CONES_SLACK_LT=0` gives a 14q confirmation build.

The single Scala touch-point is the generator `riscq.soc.GenPulseTableSocOoc`
(in `src/riscq/soc/PulseTableSoc.scala`) — SpinalHDL→Verilog elaboration is the one step that cannot be
a Tcl script. Everything the old bench did *in Vivado* now lives in the two `.tcl` files here.

## Env knobs

Handled by `build-riscvsoc.sh`:

| Var | Default | Meaning |
|---|---|---|
| `RISCQ_VIVADO_BIN` | the `vivado` on `PATH` | Vivado install (`bin` dir) |
| `RISCQ_QUBITS` | `14` | qubit count → RTL gen |
| `RISCQ_PROJ_NAME` | `riscvsoc` | build-folder name under `<repo>/build/` (parallel designs) |
| `RISCQ_SKIP_GEN` | `0` | `1` = reuse existing workspace RTL, skip mill |

Read by `riscvsoc-pnr.tcl` (defaults = the documented recipe):

| Var | Default | Meaning |
|---|---|---|
| `RISCQ_TOP` | `PulseTableSoc` | top module (`build-coreband.sh` sets `CoreBandBench`) |
| `RISCQ_DEVICE` | `xczu49dr-ffvf1760-2-e` | target part |
| `RISCQ_FMAX_TARGET_MHZ` | `500` | `dspClk` target (→ 2.000 ns) |
| `RISCQ_SOC_HOST_MHZ` | `100` | host `clk` target (→ 10.000 ns) |
| `RISCQ_ROW` | `3` | base X0 clock-region row (Y3..Y7) |
| `RISCQ_PERROW` | `3` | cores per X0 row |
| `RISCQ_CONFINE` | `global` | datapath confine: `global` (X1Y0:X5Y7) / `region` (per-row, DSP-infeasible at 14q) / `none` |
| `RISCQ_RETIMING` | `1` | synth `-retiming` |
| `RISCQ_ROUTE` | `1` | run route (0 = stop after place) |
| `RISCQ_PHYSOPT` | `1` | run `phys_opt_design` around route |
| `RISCQ_PLACE_DIR` | `ExtraNetDelay_high` | placer directive |
| `RISCQ_ROUTE_DIR` | `AggressiveExplore` | router directive |

> RTL-level levers (`replicateTime`, `df`, `1h`, `linkPipe`, the per-core `KEEP_HIERARCHY`) are baked into
> the `.v` by `GenPulseTableSocOoc`, not Tcl knobs — to A/B them, edit the generator (e.g. its `coreParam`).

## Relationship to the other flows

This is a **bench/sign-off** flow (OOC, non-project, direct `synth_design`→`place_design`→`route_design`,
just like the Scala `PblockBench`). It is distinct from the **block-design hardware-build** flow in
[`../riscvsoc-bd`](../riscvsoc-bd) (`PulseTableSoc` packaged as a user IP + Zynq PS + RFDC + SmartConnect):
that one builds a programmable ZCU216 image and measures the real-device penalty; this one measures the
floorplanned core timing of the SoC in isolation, reproducing the documented number.
