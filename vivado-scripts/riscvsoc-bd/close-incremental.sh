#!/usr/bin/env bash
# close-incremental.sh — run the incremental TimingClosure pass (inc/incr-close.tcl) on a finished
# riscvsoc-bd build and echo the resulting dspClk slack.
#
#   ./close-incremental.sh                       # on <repo>/build/riscvsoc-bd
#   RISCQ_PROJ_NAME=foo ./close-incremental.sh   # on <repo>/build/foo
#
# The build must have completed impl_1 (build-riscvsoc-bd.sh). Outputs land next to the build's
# reports: timing_incr.rpt and routed_incr.dcp. Env: RISCQ_VIVADO_BIN, RISCQ_PROJ_NAME.
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
VIVADO_BIN="${RISCQ_VIVADO_BIN:-$(dirname "$(command -v vivado)")}"
PROJ="${RISCQ_PROJ_NAME:-riscvsoc-bd}"
BUILD="$REPO_DIR/build/$PROJ"
ls "$BUILD"/*.runs/impl_1/*_routed.dcp >/dev/null 2>&1 || { echo "[incr-close] no routed checkpoint in $BUILD — run build-riscvsoc-bd.sh first" >&2; exit 1; }

export RISCQ_BUILD_DIR="$BUILD"
export RISCQ_PBLOCK_TCL="$SCRIPT_DIR/pblocks-bd.tcl"
echo "[incr-close] incremental TimingClosure pass on $BUILD …"
( cd "$BUILD" && "$VIVADO_BIN/vivado" -nojournal -log incr-close.log -mode batch -source "$SCRIPT_DIR/inc/incr-close.tcl" )
echo "[incr-close] dspClk (timing_incr.rpt):"
grep -E "^\s*dspClk_clk_p\s+-?[0-9]" "$BUILD/timing_incr.rpt" | head -1 | sed 's/^/[incr-close]   /'
