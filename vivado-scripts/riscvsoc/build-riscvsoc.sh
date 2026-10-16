#!/bin/bash
# ============================================================================================
# riscvsoc — standalone Vivado OOC place&route bench reproducing the documented floorplan result (docs/soc/ARCH.md):
# the real 14-qubit PulseTableSoc, floorplanned with 14 RiscvSoc cores at 3-per-row × 5 X0 rows (Y3–Y7),
# the DSP/RF datapath confined to X1Y0:X5Y7, df+1h core levers, synth retiming, place ExtraNetDelay_high
# / route AggressiveExplore → dspClk WNS ~ −0.156 ns / ~484 MHz, worst path internal to a core.
#
# This is the pure-Tcl equivalent of a riscq.bench.PblockBench run. The ONLY Scala step is the unavoidable
# SpinalHDL→Verilog elaboration (riscq.soc.GenPulseTableSocOoc — vivado=false plain dspClk/clk ports; the
# per-core KEEP_HIERARCHY and every other lever are PulseTableSoc defaults, which already ARE the floorplan
# stack). Synth, floorplan, P&R and
# reporting are the Tcl in this directory (riscvsoc-pnr.tcl + pblocks-riscvsoc.tcl).
#
# Usage:
#   ./build-riscvsoc.sh                       # full 14q recipe (gen RTL, then OOC P&R)
#   RISCQ_QUBITS=3  ./build-riscvsoc.sh   # smaller config for faster iteration
#   RISCQ_SKIP_GEN=1    ./build-riscvsoc.sh   # reuse the RTL already in the workspace (skip mill)
#   RISCQ_ROUTE=0   ./build-riscvsoc.sh   # stop after place (fast FIT check, no route timing)
#
# Env knobs handled here: RISCQ_VIVADO_BIN, RISCQ_QUBITS, RISCQ_SKIP_GEN, RISCQ_PROJ_NAME.
# Env knobs read by riscvsoc-pnr.tcl: RISCQ_DEVICE, RISCQ_FMAX_TARGET_MHZ, RISCQ_SOC_HOST_MHZ,
#   RISCQ_{ROW,PERROW,CONFINE,RETIMING,ROUTE,PHYSOPT,PLACE_DIR,ROUTE_DIR,TOP}.  See README.md.
# ============================================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VIVADO_BIN="${RISCQ_VIVADO_BIN:-$(dirname "$(command -v vivado)")}"
QUBITS="${RISCQ_QUBITS:-14}"
# One folder per design under the repo-root build/ (git-ignored); RISCQ_PROJ_NAME picks the folder so
# several designs build in parallel. RTL + checkpoints + reports + vivado.log all land here.
WS="$REPO_DIR/build/${RISCQ_PROJ_NAME:-riscvsoc}"

if [ "${RISCQ_SKIP_GEN:-0}" != "1" ]; then
  rm -rf "$WS"; mkdir -p "$WS"
  echo "[riscvsoc] generating RTL (PulseTableSoc qubitNum=$QUBITS, vivado=false) → $WS"
  ( cd "$REPO_DIR" && mill runMain riscq.soc.GenPulseTableSocOoc "$QUBITS" "$WS" )
else
  echo "[riscvsoc] RISCQ_SKIP_GEN=1 — reusing RTL in $WS"
  [ -f "$WS/PulseTableSoc.v" ] || { echo "[riscvsoc] no $WS/PulseTableSoc.v — run once without RISCQ_SKIP_GEN" >&2; exit 1; }
fi

echo "[riscvsoc] running Vivado OOC place&route (floorplan) in $WS …"
# Vivado runs IN the workspace: the generated .v $readmemb's its register-file .bin by a relative path,
# and the tcl reads *.v / writes its checkpoints + reports there.
( cd "$WS" && "$VIVADO_BIN/vivado" -nojournal -log vivado.log -mode batch -source "$SCRIPT_DIR/riscvsoc-pnr.tcl" )

echo "[riscvsoc] ===================================================================="
echo -n "[riscvsoc] verdict: "; cat "$WS/fit_result.txt" 2>/dev/null | head -1 || echo "(no fit_result.txt — see $WS/vivado.log)"
if [ -f "$WS/timing_paths.rpt" ]; then
  echo "[riscvsoc] dspClk worst slack:"
  grep -m1 -E "Slack \(" "$WS/timing_paths.rpt" | sed 's/^/[riscvsoc]   /'
else
  echo "[riscvsoc] (no timing_paths.rpt — RISCQ_ROUTE=0 or route failed; see $WS/vivado.log)"
fi
echo "[riscvsoc] reports + checkpoints: $WS (timing_paths.rpt, timing_summary.rpt, util_pblock.rpt, post_route.dcp)"
