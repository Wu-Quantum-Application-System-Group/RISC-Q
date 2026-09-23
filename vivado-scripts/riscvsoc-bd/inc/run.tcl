# ---- Synthesis (+ optional implementation / bitstream), gated by the config run flags --------------
set_param general.maxThreads 2
if {$RUN_SYNTH} {
  set_property strategy Flow_PerfOptimized_high [get_runs synth_1]
  set_property STEPS.SYNTH_DESIGN.ARGS.GLOBAL_RETIMING on [get_runs synth_1]
  # RISCQ_CSET_THRESH raises the control-set optimisation threshold: clock-enable / set-reset nets
  # whose flop fanout is below N are mapped to LUT recirculation instead of the FF's dedicated
  # CE/SR pin, collapsing low-fanout control sets so cells pack densely again. Attacks the per-core
  # slice saturation (97.7% slices at 62% LUT, 595 control sets) that route-binds the 14q dspClk
  # path after the F2 floorplan. Sweep N (e.g. 8/12/16); default off.
  #
  # CRITICAL (Flow A): the 14 cores are inside the `top` user-IP, which synthesises OUT-OF-CONTEXT in
  # its own child run `${BD_NAME}_top_0_synth_1`. `synth_1` only stitches the BD wrapper (≈no FFs), so
  # ANY synth arg set on synth_1 (strategy, retiming, this threshold) NEVER reaches the cores — that is
  # why the perf strategy above leaves Flow A at ~default LUT. The threshold must be set on the IP run.
  # That run does NOT exist yet: Vivado only materialises the BD's OOC child runs *at* launch_runs.
  # So create them first with `create_ip_run [get_files *.bd]` (the exact idiom Vivado's own
  # scripts/project/synth_bd.tcl uses), then set the threshold on the *_top_* run before it launches.
  # The same OOC-IP-run argument applies to RISCQ_IP_RETIMING: it turns on GLOBAL_RETIMING for the
  # cores (the OOC bench's `synth_design -retiming`), so a block-design impl is a fair compare to the
  # out-of-context vivado-scripts/riscvsoc bench — the synth_1 GLOBAL_RETIMING above only touches the
  # ≈FF-free BD wrapper, never the cores. Both levers reuse the one create_ip_run materialisation.
  if {[info exists ::env(RISCQ_CSET_THRESH)] || [info exists ::env(RISCQ_IP_RETIMING)]} {
    set _bd [get_files -quiet $BD_NAME.bd]
    if {[llength $_bd]} { catch { create_ip_run $_bd } }
    set _ipruns [get_runs -quiet -filter {IS_SYNTHESIS && NAME =~ *_top_*}]
    if {[llength $_ipruns] == 0} {
      puts "\[run\] WARN: an IP-synth lever (RISCQ_CSET_THRESH / RISCQ_IP_RETIMING) was set but no *_top_* IP synth run found — cores will NOT get it"
    }
    foreach _r $_ipruns {
      if {[info exists ::env(RISCQ_CSET_THRESH)]} {
        set_property STEPS.SYNTH_DESIGN.ARGS.CONTROL_SET_OPT_THRESHOLD $::env(RISCQ_CSET_THRESH) $_r
        puts "\[run\] control-set opt threshold $::env(RISCQ_CSET_THRESH) -> IP run $_r"
      }
      if {[info exists ::env(RISCQ_IP_RETIMING)]} {
        set_property STEPS.SYNTH_DESIGN.ARGS.GLOBAL_RETIMING on $_r
        puts "\[run\] global retiming on -> IP run $_r"
      }
    }
  }
  launch_runs synth_1 -jobs 1
  wait_on_run synth_1
  if {[get_property PROGRESS [get_runs synth_1]] != "100%"} {
    error "synthesis failed — see $BUILD_DIR/$PRJ.runs/synth_1"
  }
  open_run synth_1 -name synth_1
  report_utilization     -file $BUILD_DIR/util_synth.rpt
  report_timing_summary  -file $BUILD_DIR/timing_synth.rpt -max_paths 20
  puts "\[run\] synthesis OK — reports in $BUILD_DIR (util_synth.rpt / timing_synth.rpt)"
}

if {$RUN_IMPL} {
  set_property strategy Performance_NetDelay_high [get_runs impl_1]
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true [get_runs impl_1]
  set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE Explore [get_runs impl_1]
  set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE AggressiveExplore [get_runs impl_1]
  # RISCQ_PLACE_DIRECTIVE overrides the placer directive (e.g. AltSpreadLogic_high) to relieve the
  # RF-DAC edge congestion — placement, not routing, is the binder.
  if {[info exists ::env(RISCQ_PLACE_DIRECTIVE)]} {
    set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE $::env(RISCQ_PLACE_DIRECTIVE) [get_runs impl_1]
    puts "\[run\] place directive override: $::env(RISCQ_PLACE_DIRECTIVE)"
  }
  # RISCQ_PHYSOPT_DIRECTIVE overrides the directive of BOTH phys_opt passes (post-place and post-route),
  # e.g. AggressiveExplore to chase the last tens of ps on a design that already places cleanly.
  if {[info exists ::env(RISCQ_PHYSOPT_DIRECTIVE)]} {
    set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE $::env(RISCQ_PHYSOPT_DIRECTIVE) [get_runs impl_1]
    set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE $::env(RISCQ_PHYSOPT_DIRECTIVE) [get_runs impl_1]
    puts "\[run\] phys_opt directive override: $::env(RISCQ_PHYSOPT_DIRECTIVE)"
  }
  # RISCQ_PBLOCK hooks a pre-place Tcl (pblocks.tcl) that creates 14 per-core Pblocks pinning ONLY
  # each RISC-V core + its RAM (riscqFiber_riscq + mem) to a clock region, the DSP/RF datapath left
  # to float — the fix for the X5-edge congestion wall that placer-directive experiments
  # could not break.
  # RISCQ_PBLOCK_TCL overrides the pre-place floorplan file (default pblocks-bd.tcl) — build-riscvsoc-bd.sh
  # points it at this dir's pblocks-bd.tcl, the OOC floorplan (cores → X0 Y3-Y7 bands, datapath →
  # X1Y0:X5Y7) ported into the BD hierarchy.
  if {[info exists ::env(RISCQ_PBLOCK)]} {
    set _ppre $SCRIPT_DIR/pblocks-bd.tcl
    if {[info exists ::env(RISCQ_PBLOCK_TCL)]} { set _ppre $::env(RISCQ_PBLOCK_TCL) }
    set_property STEPS.PLACE_DESIGN.TCL.PRE $_ppre [get_runs impl_1]
    puts "\[run\] pblock floorplan: $_ppre (RISCQ_PBLOCK=$::env(RISCQ_PBLOCK))"
  }
  if {$RUN_BITSTREAM} {
    launch_runs impl_1 -to_step write_bitstream -jobs 1
  } else {
    launch_runs impl_1 -jobs 1
  }
  wait_on_run impl_1
  if {[get_property PROGRESS [get_runs impl_1]] != "100%"} {
    error "implementation failed — see $BUILD_DIR/$PRJ.runs/impl_1"
  }
  open_run impl_1
  report_utilization    -file $BUILD_DIR/util_impl.rpt
  report_timing_summary -file $BUILD_DIR/timing_impl.rpt -max_paths 20
  # per-cone failing-endpoint classifier (specs/riscv-fmax.md A1) → cones_impl.rpt / cones_paths.tsv
  if {[catch {
    set CONES_DIR $BUILD_DIR
    source $SCRIPT_DIR/../report-cones.tcl
  } _ce]} { puts "\[run\] WARN: report-cones failed: $_ce" }
  puts "\[run\] implementation OK — reports in $BUILD_DIR (util_impl.rpt / timing_impl.rpt / cones_impl.rpt)"
  if {$RUN_BITSTREAM} {
    file copy -force $BUILD_DIR/$PRJ.runs/impl_1/${BD_NAME}_wrapper.bit $BUILD_DIR/$TOP_MODULE.bit
    puts "\[run\] bitstream -> $BUILD_DIR/$TOP_MODULE.bit"
    # Hardware handoff for the software flow (Vitis / PetaLinux): a fixed (non-DFX) platform with the
    # bitstream embedded. The implemented design is still open from open_run impl_1 above.
    write_hw_platform -fixed -include_bit -force $BUILD_DIR/$TOP_MODULE.xsa
    puts "\[run\] hardware platform -> $BUILD_DIR/$TOP_MODULE.xsa"
  }
}
