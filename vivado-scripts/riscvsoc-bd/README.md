# `riscvsoc-bd/` — block-design implementation of the floorplan

This is the **block-design counterpart** of the out-of-context bench in [`../riscvsoc`](../riscvsoc).
Same SoC (`PulseTableSoc`, 14 cores), same **floorplan** (cores → X0 Y3–Y7 bands 3/row, datapath →
`X1Y0:X5Y7`), same recipe (`keepCoreHierarchy`, retiming, `df`+`1h`, `place ExtraNetDelay_high` /
`route AggressiveExplore`) — but built in a **real device context**: `PulseTableSoc` packaged as a user
IP in a Vivado block design alongside the **Zynq UltraScale+ PS + RF Data Converter + AXI SmartConnect**,
with real **ClockInterface BUFG** clocking from the board LVDS clocks and the real host-released reset.

**Purpose — evaluate the difference.** The OOC bench (`../riscvsoc`) reports `dspClk` WNS ≈ **−0.156 ns**
for the SoC *in isolation* with synthetic port clocks. This flow puts the identical SoC + floorplan into
the full ZCU216 image. The gap between the two WNS numbers is the **block-design / real-device penalty**:
the PS + RFDC sharing the die, BUFG clock insertion, the SmartConnect routing, and the fact that the SoC
synthesises **out-of-context as an IP child run** while place+route happen globally at the wrapper.

## How it differs from the OOC bench (what the comparison measures)

| | `../riscvsoc` (OOC) | `riscvsoc-bd` (this) |
|---|---|---|
| RTL | `GenPulseTableSocOoc` — `vivado=false`, plain `dspClk`/`clk` ports | `GenPulseTableSocJson <cfg> <dir> vivado` — `vivado=true`, `hostClk` + `X_INTERFACE`, `ClockInterface.v` |
| context | SoC alone, OOC | SoC IP + Zynq PS + RFDC + SmartConnect in a BD |
| clocks | `create_clock` on raw ports, async-grouped | board LVDS → `ClockInterface` BUFG → real clock tree |
| synth | one `synth_design -mode out_of_context -retiming` | BD wrapper `synth_1` + the SoC as an OOC IP child run (retiming pushed onto it via `RISCQ_IP_RETIMING`) |
| P&R | non-project `place_design`→`route_design` | project `impl_1` (`Performance_NetDelay_high` + post-route phys_opt) |
| floorplan | `../riscvsoc/pblocks-riscvsoc.tcl` (top-level names) | `pblocks-bd.tcl` (same floorplan, BD prefix `riscq_bd_i/top/inst/…` + PS/RFDC guard) |
| reset | synthetic | real host-released reset network |

Everything that *can* be held identical is — the divergences above are exactly the BD-context effects
being measured.

## Run

```bash
cd vivado-scripts/riscvsoc-bd
./build-riscvsoc-bd.sh                       # 14q, floorplan, synth + impl in the block design
RISCQ_QUBITS=3 ./build-riscvsoc-bd.sh    # smaller config (faster)
./close-incremental.sh                       # then: the incremental TimingClosure pass on that build
```

**Re-build, 8 cores (2026-09-18, `x6y3.json` + the wrap-safe `waitTimeCmp` — SOC_TIPS §5):**
`RISCQ_CONFIG=software/configs/x6y3.json RISCQ_PROJ_NAME=x6y3-bd-wrapfix ./build-riscvsoc-bd.sh` meets
timing first pass again: dspClk **+0.005 ns** / 0 failing of 844 208 endpoints (WHS +0.010), all
constraints met, XSA at `build/x6y3-bd-wrapfix/PulseTableSoc.xsa`. Tighter than the 2026-09-15 +0.037
(the router dipped to −0.008 before its final pass recovered it), so treat a re-run of this tree as
marginal-but-closing rather than comfortable.

**Result of record, 8 cores (2026-09-15, `x6y3.json`, same tree — put-network hub + the channel-list
spec):** `RISCQ_CONFIG=software/configs/x6y3.json RISCQ_PROJ_NAME=x6y3-bd ./build-riscvsoc-bd.sh`
**meets timing first pass, no incremental close needed**: dspClk **+0.037 ns** / 0 failing of 779 428
endpoints (WHS +0.009), `clk_pl_0` +2.205, `hostClk` +3.422, "All user specified timing constraints
are met". ~82 min wall; LUT 29 %, FF 27 %, BRAM 18 %, URAM 10 %, DSP 38 %. The 8 cores take the same
X0 bands the 14q floorplan defines (`RISCQ_ROW`/`RISCQ_PERROW` defaults, cores 0–7 → X0Y3 band 0 …
X0Y5 band 1), so nothing but `RISCQ_CONFIG` changes — six fewer cores competing for the column is
the likely reason this closes where 14q needs `close-incremental.sh`.

**Result of record (2026-09-14, `zcu216-14q.json` with the put-network hub, specs/cross-core/02):**
`build-riscvsoc-bd.sh` lands at dspClk **−0.015 ns** (86 failing endpoints, all core-internal and
pulse-buffer paths); `close-incremental.sh` — `inc/incr-close.tcl`, an incremental re-implementation
seeded by that run's own routed checkpoint with `read_checkpoint -incremental … -directive
TimingClosure` — closes it at **0.000 ns / 0 failing endpoints** (`clk_pl_0` +1.9, `hostClk` +2.1, the
async group +0.02), `routed_incr.dcp` being the closed design. Placer directives alone did not close it
(`Explore` −0.106, `AltSpreadLogic_high` −0.040, `ExtraTimingOpt` −0.123); an AggressiveExplore
phys_opt alone reproduced −0.015 exactly (the flow is deterministic).

Outputs land in `<repo>/build/riscvsoc-bd/` (git-ignored): the generated RTL, `timing_impl.rpt` /
`util_impl.rpt` (the headline WNS/TNS + per-pblock utilisation), the BD, the `*.runs/` and `vivado.log`.
The driver echoes the impl WNS/TNS at the end. Compare that WNS to the `../riscvsoc` OOC number. Set
`RISCQ_PROJ_NAME=<name>` to build into `<repo>/build/<name>` instead (parallel designs).

## Files

This flow is **self-contained** — everything it needs lives in this directory:

| File | Role |
|---|---|
| `build-riscvsoc-bd.sh` | driver: generate BD RTL into the build dir, then launch Vivado on `flow-bd.tcl` with the floorplan + IP retiming |
| `flow-bd.tcl` | proc-free driver: sets config vars, then `source`s the `inc/*.tcl` steps |
| `inc/{config,create-project,package-ip,bd-build,bd-finalize,rfdc-config,rfdc-connect,run}.tcl` | the BD assembly: project, IP packaging, PS + RFDC + SmartConnect, synth + impl |
| `constraints-zcu216.xdc` | ZCU216 pin/clock constraints (added to the BD wrapper) |
| `pblocks-bd.tcl` | the floorplan ported into the block-design hierarchy |

The driver selects the floorplan + core retiming through two `inc/run.tcl` hooks:

- **`RISCQ_PBLOCK_TCL`** — the pre-place floorplan file (→ `pblocks-bd.tcl`).
- **`RISCQ_IP_RETIMING`** — set `GLOBAL_RETIMING on` for the SoC's OOC IP synth run (the BD wrapper's
  `synth_1` never reaches the cores), so the cores are synthesised with retiming like the OOC bench.

## Env knobs

Handled by `build-riscvsoc-bd.sh`: `RISCQ_VIVADO_BIN`, `RISCQ_QUBITS` (14), `RISCQ_SKIP_GEN`,
`RISCQ_PROJ_NAME` (`riscvsoc-bd`), `RISCQ_PLACE_DIRECTIVE` (`ExtraNetDelay_high`), `RISCQ_PHYSOPT_DIRECTIVE` (both phys_opt passes, e.g. `AggressiveExplore`).

Read by `pblocks-bd.tcl`: `RISCQ_ROW` (3), `RISCQ_PERROW` (3), `RISCQ_CONFINE`
(`global`|`region`|`none`, default `global`), `RISCQ_BD_BASE` (`riscq_bd_i/top/inst`).

> RTL-level levers (`replicateTime`, `df`, `1h`, `linkPipe`, the unconditional `KEEP_HIERARCHY` on each
> core) are baked into the BD RTL by `GenPulseTableSocJson` (the `PulseTableSoc` defaults = the floorplan
> stack), as in the OOC flow. The qubit count and DAC/ADC maps come from the JSON config it reads.

## Caveats

- This needs the full ZCU216 BD IP set (Zynq PS, RF Data Converter, SmartConnect) — it is a heavier,
  longer run than the OOC bench, and produces a real `impl_1`.
- The impl strategy is `Performance_NetDelay_high` + **post-route** phys_opt (`inc/run.tcl`'s), vs the OOC
  bench's `place → phys_opt → route → phys_opt`. Close, not byte-identical — noted because it is one of
  the BD-vs-OOC differences, not a controlled variable.
