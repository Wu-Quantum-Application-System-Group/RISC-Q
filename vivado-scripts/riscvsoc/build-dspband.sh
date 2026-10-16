#!/bin/bash
# ============================================================================================
# dspband — DSP-cone A/B vehicle: the real PulseTableSoc on a 3q
# config — real channel×core geometry at a fraction of the 14q runtime —
# through the unchanged riscvsoc OOC recipe (floorplan, directives, report-cones). The verdict
# is the DSP rows of cones_impl.rpt; gate for each lever: its target cone's worst margin ≥ 0
# @ 1.9 ns without a new cone appearing below it.
#
# Per-build lever state lives in the config JSON, one JSON per variant
# under configs/; the JSON is copied into the build folder so every build records its config.
#
# Bench target = 526 MHz (1.9 ns), NOT the SoC's 500: at 2.0 ns every flow stage stops working
# once a path meets, so all cones land "barely met" and lever deltas compress into placement
# noise (see build-coreband.sh). "Every DSP cone ≥ 0 @ 1.9 ns" ⇔
# "≥ +0.10 @ 500", expressed where the tools keep optimizing.
#
# Usage:
#   ./build-dspband.sh                             # baseline → <repo>/build/dspband-3q
#   ./build-dspband.sh configs/dspband-3q-b2.json  # lever variant → <repo>/build/dspband-3q-b2
#   RISCQ_SKIP_GEN=1 ./build-dspband.sh            # reuse the RTL already in the workspace
# Env knobs read by riscvsoc-pnr.tcl (RISCQ_DEVICE, RISCQ_FMAX_TARGET_MHZ, RISCQ_ROW, RISCQ_PERROW,
# RISCQ_CONFINE, RISCQ_RETIMING, RISCQ_ROUTE, RISCQ_PHYSOPT, RISCQ_PLACE_DIR, RISCQ_ROUTE_DIR) apply.
# ============================================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VIVADO_BIN="${RISCQ_VIVADO_BIN:-$(dirname "$(command -v vivado)")}"
CFG="$(realpath "${1:-$SCRIPT_DIR/configs/dspband-3q.json}")"   # absolute: mill runs from the repo root
NAME="$(basename "$CFG" .json)"
WS="$REPO_DIR/build/${RISCQ_PROJ_NAME:-$NAME}"
export RISCQ_CONES_SLACK_LT="${RISCQ_CONES_SLACK_LT:-0.15}"
export RISCQ_FMAX_TARGET_MHZ="${RISCQ_FMAX_TARGET_MHZ:-526}"

if [ "${RISCQ_SKIP_GEN:-0}" != "1" ]; then
  rm -rf "$WS"; mkdir -p "$WS"
  echo "[dspband] generating RTL from $CFG → $WS"
  # mill must run with the repo root as cwd — from anywhere else it treats that dir as a project
  # root (creates a stray out/) and can't find the riscq classes.
  ( cd "$REPO_DIR" && mill runMain riscq.soc.GenPulseTableSocJson "$CFG" "$WS" )
  cp "$CFG" "$WS/config.json"
else
  echo "[dspband] RISCQ_SKIP_GEN=1 — reusing RTL in $WS"
  [ -f "$WS/PulseTableSoc.v" ] || { echo "[dspband] no $WS/PulseTableSoc.v — run once without RISCQ_SKIP_GEN" >&2; exit 1; }
fi

echo "[dspband] running Vivado OOC place&route (floorplan) in $WS …"
( cd "$WS" && "$VIVADO_BIN/vivado" -nojournal -log vivado.log -mode batch -source "$SCRIPT_DIR/riscvsoc-pnr.tcl" )

echo "[dspband] ===================================================================="
echo -n "[dspband] verdict: "; cat "$WS/fit_result.txt" 2>/dev/null | head -1 || echo "(no fit_result.txt — see $WS/vivado.log)"
if [ -f "$WS/timing_paths.rpt" ]; then
  echo "[dspband] dspClk worst slack:"
  grep -m1 -E "Slack \(" "$WS/timing_paths.rpt" | sed 's/^/[dspband]   /'
fi
if [ -f "$WS/cones_impl.rpt" ]; then
  echo "[dspband] per-cone report:"
  sed 's/^/[dspband]   /' "$WS/cones_impl.rpt"
fi
echo "[dspband] reports + checkpoints: $WS"
