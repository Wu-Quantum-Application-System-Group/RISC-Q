#!/bin/bash
# ============================================================================================
# coreband — single-core band bench: one RiscvSoc in the exact
# SoC context (fetchLatency=4 lever stack, URAM mem, coreTime replica, registered posted-link
# boundary anchors), floorplanned into the same X0 SLICE band the 14q flow uses (band 0 of 3 of
# X0Y3) and routed with the same recipe. Minutes-scale A/B vehicle for core timing levers;
# the verdict is the per-cone table (cones_impl.rpt), target: every core cone ≥ +0.10 ns.
#
# Reuses riscvsoc-pnr.tcl + pblocks-riscvsoc.tcl unchanged (RISCQ_TOP=CoreBandBench; the bench
# names its core instance riscqArea_riscqCores_0_riscvSoc / coreTime_0 so the floorplan discovery,
# datapath confine and report-cones classification all apply as-is).
#
# Usage:
#   ./build-coreband.sh                     # gen RTL + OOC P&R → <repo>/build/coreband
#   RISCQ_SKIP_GEN=1 ./build-coreband.sh    # reuse the RTL already in the workspace
#   RISCQ_PROJ_NAME=coreband-b1 ./build-coreband.sh   # separate build folder per experiment
# Env knobs read by riscvsoc-pnr.tcl (RISCQ_DEVICE, RISCQ_FMAX_TARGET_MHZ, RISCQ_ROW, RISCQ_PERROW,
# RISCQ_CONFINE, RISCQ_RETIMING, RISCQ_ROUTE, RISCQ_PHYSOPT, RISCQ_PLACE_DIR, RISCQ_ROUTE_DIR) apply.
# ============================================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VIVADO_BIN="${RISCQ_VIVADO_BIN:-$(dirname "$(command -v vivado)")}"
WS="$REPO_DIR/build/${RISCQ_PROJ_NAME:-coreband}"
# report per-cone margins even when the bench MEETS timing (the gate is ≥ +0.10 per core cone)
export RISCQ_CONES_SLACK_LT="${RISCQ_CONES_SLACK_LT:-0.15}"
# Bench target = 526 MHz (1.9 ns), NOT the SoC's 500: every flow stage is timing-driven and stops
# improving a path once it meets, so at 2.0 ns all cones land "barely met" and the margins
# compress into the ~±0.1 placement-reseed noise (measured in a lever sweep). 1.9 ns IS the gate
# ("every cone ≥ +0.10 @ 500" ⇔ "WNS ≥ 0 @ 1.9 ns") expressed where the tools keep working toward
# it, while staying close enough to 2.0 ns that retiming/placement decisions stay representative
# (a 2× overconstraint would distort both and break the standalone↔in-SoC calibration).
# NOTE: margins are NOT comparable across targets — 500 MHz numbers predate the 526 re-baseline.
export RISCQ_FMAX_TARGET_MHZ="${RISCQ_FMAX_TARGET_MHZ:-526}"

if [ "${RISCQ_SKIP_GEN:-0}" != "1" ]; then
  rm -rf "$WS"; mkdir -p "$WS"
  echo "[coreband] generating RTL (CoreBandBench) → $WS"
  ( cd "$REPO_DIR" && mill runMain riscq.soc.GenCoreBandBench "$WS" )
else
  echo "[coreband] RISCQ_SKIP_GEN=1 — reusing RTL in $WS"
  [ -f "$WS/CoreBandBench.v" ] || { echo "[coreband] no $WS/CoreBandBench.v — run once without RISCQ_SKIP_GEN" >&2; exit 1; }
fi

echo "[coreband] running Vivado OOC place&route (band floorplan) in $WS …"
( cd "$WS" && RISCQ_TOP=CoreBandBench "$VIVADO_BIN/vivado" -nojournal -log vivado.log -mode batch -source "$SCRIPT_DIR/riscvsoc-pnr.tcl" )

echo "[coreband] ===================================================================="
echo -n "[coreband] verdict: "; cat "$WS/fit_result.txt" 2>/dev/null | head -1 || echo "(no fit_result.txt — see $WS/vivado.log)"
if [ -f "$WS/timing_paths.rpt" ]; then
  echo "[coreband] dspClk worst slack:"
  grep -m1 -E "Slack \(" "$WS/timing_paths.rpt" | sed 's/^/[coreband]   /'
fi
if [ -f "$WS/cones_impl.rpt" ]; then
  echo "[coreband] per-cone report:"
  sed 's/^/[coreband]   /' "$WS/cones_impl.rpt"
fi
echo "[coreband] reports + checkpoints: $WS"
