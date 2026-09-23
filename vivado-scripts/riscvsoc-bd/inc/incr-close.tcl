# incr-close.tcl — the last-tens-of-ps closure pass for a finished riscvsoc-bd build.
#
# Re-implements the SoC INCREMENTALLY from its own routed checkpoint with the `TimingClosure`
# directive: the post-opt netlist is reopened, the floorplan (pblocks-bd.tcl) re-applied, the
# routed result read back as the incremental reference, and place → phys_opt → route → phys_opt
# (AggressiveExplore) run again. Vivado then keeps the placement/routing of everything that already
# meets timing and re-works only the failing paths. On the zcu216-14q build with the put-network hub
# this took dspClk from −0.015 ns / 86 failing endpoints to 0.000 ns / 0 (specs/cross-core/02 §9 R3),
# where placer-directive variants (Explore, AltSpreadLogic_high, ExtraTimingOpt) and phys_opt
# directives alone did not.
#
# Inputs (env): RISCQ_BUILD_DIR — the finished build dir (its <proj>.runs/impl_1/*_opt.dcp and
#               *_routed.dcp are the seeds); RISCQ_PBLOCK_TCL — the floorplan (pblocks-bd.tcl).
# Outputs: $RISCQ_BUILD_DIR/timing_incr.rpt (the headline), routed_incr.dcp (the closed design —
#          write_bitstream from it if it is the one to ship).
set BUILD_DIR $::env(RISCQ_BUILD_DIR)
set PBLOCKS   $::env(RISCQ_PBLOCK_TCL)
set opt    [lindex [glob $BUILD_DIR/*.runs/impl_1/*_opt.dcp] 0]
set routed [lindex [glob $BUILD_DIR/*.runs/impl_1/*_routed.dcp] 0]
puts "\[incr-close\] seed: $routed"

open_checkpoint $opt
source $PBLOCKS
read_checkpoint -incremental $routed -directive TimingClosure
place_design
phys_opt_design
route_design
phys_opt_design -directive AggressiveExplore
report_timing_summary -max_paths 20 -file $BUILD_DIR/timing_incr.rpt
write_checkpoint -force $BUILD_DIR/routed_incr.dcp
puts "\[incr-close\] done: $BUILD_DIR/timing_incr.rpt, routed_incr.dcp"
