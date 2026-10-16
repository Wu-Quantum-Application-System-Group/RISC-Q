#!/bin/bash
# ============================================================================================
# riscvsoc-bd — BLOCK-DESIGN implementation of the floorplan, to evaluate the gap vs the
# out-of-context bench in ../riscvsoc.
#
# Same SoC, same floorplan, same recipe — but built in a REAL device context: PulseTableSoc packaged as
# a user IP in a Vivado block design alongside the Zynq UltraScale+ PS, the RF Data Converter and an AXI
# SmartConnect, with real clocking (ClockInterface BUFGs from the board LVDS clocks) and the real
# host-released reset network. So the difference between this flow's WNS and the ~ −0.156 ns the OOC
# bench reports isolates the **block-design / real-device penalty** (PS+RFDC sharing the die, BUFG
# clocking, the IP synthesised out-of-context as a child run, SmartConnect routing).
#
# Self-contained: it generates the BD (vivado=true) RTL with GenPulseTableSocJson from a per-build
# SocParams JSON (the same file the python riscq.map / co-sim flows load), then runs the BD assembly in
# flow-bd.tcl + inc/{config,create-project,package-ip,bd-build,bd-finalize,run}.tcl, with the floorplan
# from pblocks-bd.tcl. RTL, the Vivado project, the reports and vivado.log all land in one folder under
# the repo-root build/ — <repo>/build/<PROJ> — so several designs can build in parallel.
#
# Prereq: a Vivado with the Zynq PS / RFDC / SmartConnect IP (the full ZCU216 BD flow). The RTL is the
# vivado=true form (host clock = hostClk, X_INTERFACE attrs) — distinct from the OOC bench's plain ports.
# The qubit count / DAC-ADC maps / interpolation all come from the JSON config; the tcl flow discovers the
# core count from the netlist, so a different config just needs a matching floorplan (pblocks-bd.tcl).
#
# By default this runs the full flow — synth + impl + write_bitstream + XSA export — so it drops both a
# PulseTableSoc.bit and a PulseTableSoc.xsa (the fixed hardware handoff for the Vitis / PetaLinux software
# flow, bitstream embedded) in the build dir. Set RISCQ_RUN_BITSTREAM=0 to stop after implementation.
#
# Usage:
#   ./build-riscvsoc-bd.sh                    # zcu216-14q config, full floorplan, synth+impl+bitstream+xsa
#   RISCQ_RUN_BITSTREAM=0 ./build-riscvsoc-bd.sh # stop after impl (no bitstream / xsa)
#   RISCQ_CONFIG=software/configs/sim-2q.json ./build-riscvsoc-bd.sh # a different SocParams JSON
#   RISCQ_SKIP_GEN=1   ./build-riscvsoc-bd.sh # reuse the RTL already in the build dir (skip mill)
#   RISCQ_PROJ_NAME=foo ./build-riscvsoc-bd.sh # build into <repo>/build/foo (parallel designs)
#
# Env: RISCQ_VIVADO_BIN, RISCQ_CONFIG (default software/configs/zcu216-14q.json), RISCQ_SKIP_GEN,
#   RISCQ_RUN_BITSTREAM (default 1 — bitstream + xsa; set 0 for impl-only),
#   RISCQ_PROJ_NAME (default riscvsoc-bd), RISCQ_PLACE_DIRECTIVE (default ExtraNetDelay_high — the
#   placer directive), plus the floorplan knobs read by pblocks-bd.tcl: RISCQ_{ROW,PERROW,CONFINE},
#   RISCQ_BD_BASE, and RISCQ_MREG_LOCK=1 (freezes the carrierGen ComplexMul product DSPs against
#   phys_opt MREG-stripping). inc/run.tcl also reads RISCQ_PHYSOPT_DIRECTIVE / RISCQ_CSET_THRESH.
#   See README.md.
# ============================================================================================
set -e
BD_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"   # vivado-scripts/riscvsoc-bd
REPO_DIR="$(cd "$BD_DIR/../.." && pwd)"                  # repo root
VIVADO_BIN="${RISCQ_VIVADO_BIN:-$(dirname "$(command -v vivado)")}"
CONFIG="${RISCQ_CONFIG:-$REPO_DIR/software/configs/zcu216-14q.json}"
PROJ="${RISCQ_PROJ_NAME:-riscvsoc-bd}"
BUILD="$REPO_DIR/build/$PROJ"
mkdir -p "$BUILD"

# 1) RTL — the BD (vivado=true) form — emitted INTO the project build dir from the SocParams JSON.
if [ "${RISCQ_SKIP_GEN:-0}" != "1" ]; then
  echo "[riscvsoc-bd] generating BD RTL (GenPulseTableSocJson $CONFIG, vivado=true) → $BUILD"
  ( cd "$REPO_DIR" && mill runMain riscq.soc.GenPulseTableSocJson "$CONFIG" "$BUILD" vivado )
else
  echo "[riscvsoc-bd] RISCQ_SKIP_GEN=1 — reusing RTL in $BUILD"
  [ -f "$BUILD/PulseTableSoc.v" ] || { echo "[riscvsoc-bd] no $BUILD/PulseTableSoc.v — run once without RISCQ_SKIP_GEN" >&2; exit 1; }
fi

# 2) Build via flow-bd.tcl, selecting the floorplan + IP retiming + place directive. RISCQ_PBLOCK
#    just enables the pre-place hook (any value); RISCQ_PBLOCK_TCL is the actual floorplan file.
export RISCQ_PROJ_NAME="$PROJ"
export RISCQ_BUILD_DIR="$BUILD"
export RISCQ_PBLOCK=1
export RISCQ_PBLOCK_TCL="$BD_DIR/pblocks-bd.tcl"
export RISCQ_IP_RETIMING=1
export RISCQ_PLACE_DIRECTIVE="${RISCQ_PLACE_DIRECTIVE:-ExtraNetDelay_high}"   # route stays AggressiveExplore (run.tcl)
export RISCQ_RUN_IMPL=1
export RISCQ_RUN_BITSTREAM="${RISCQ_RUN_BITSTREAM:-1}"   # bitstream + XSA (hardware handoff) by default; set 0 for impl-only

echo "[riscvsoc-bd] building block design in $BUILD (floorplan, IP retiming, place=$RISCQ_PLACE_DIRECTIVE / route=AggressiveExplore, bitstream+xsa=$RISCQ_RUN_BITSTREAM) …"
"$VIVADO_BIN/vivado" -nojournal -mode batch -log "$BUILD/vivado.log" -source "$BD_DIR/flow-bd.tcl"

echo "[riscvsoc-bd] ===================================================================="
echo "[riscvsoc-bd] done. reports in $BUILD"
if [ -f "$BUILD/timing_impl.rpt" ]; then
  echo "[riscvsoc-bd] impl WNS/TNS (timing_impl.rpt):"
  grep -m2 -E "WNS|TNS|Worst Negative|Total Negative" "$BUILD/timing_impl.rpt" | sed 's/^/[riscvsoc-bd]   /' || true
else
  echo "[riscvsoc-bd] (no timing_impl.rpt — see $BUILD/vivado.log)"
fi
[ -f "$BUILD/PulseTableSoc.bit" ] && echo "[riscvsoc-bd] bitstream: $BUILD/PulseTableSoc.bit"
[ -f "$BUILD/PulseTableSoc.xsa" ] && echo "[riscvsoc-bd] hardware platform: $BUILD/PulseTableSoc.xsa"
echo "[riscvsoc-bd] compare against ../riscvsoc (OOC, ~ −0.156 ns). util_impl.rpt holds the per-pblock view."
