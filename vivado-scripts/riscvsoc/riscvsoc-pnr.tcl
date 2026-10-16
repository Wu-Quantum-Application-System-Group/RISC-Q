# ============================================================================================
# riscvsoc — standalone Vivado OOC place&route bench reproducing the documented floorplan result.
#
# Hand-written Tcl equivalent of a riscq.bench.PblockBench run on the 14q PulseTableSoc.
# Synthesises the real PulseTableSoc out-of-context, applies the two-region floorplan (sourced pblocks tcl),
# places + routes with the proven directive stack, and reports the dspClk worst slack. The documented
# result on the full 14q config is dspClk WNS ~ −0.156 ns / ~484 MHz, worst path internal to a core.
#
# Run from the workspace dir that holds the generated PulseTableSoc.v + register-file .bin (the .bin is
# $readmemb'd by a relative path, so cwd must be the RTL dir) — build-riscvsoc.sh does this.
#
# Config below is env-overridable; the defaults ARE the documented recipe, so a bare run is the
# recipe. RTL-level levers (replicateTime / df / 1h / linkPipe / per-core KEEP_HIERARCHY) are baked into
# the .v by riscq.soc.GenPulseTableSocOoc — they are not Tcl knobs.
# ============================================================================================

# ---- helper: read a boolean env knob (1/true/yes = on), else the default ----
proc riscq_envbool {name default} {
  if {![info exists ::env($name)]} { return $default }
  return [expr {[string tolower $::env($name)] in {1 true yes on}}]
}

# ---- config (defaults = the recipe) ----
set TOP        PulseTableSoc
set PART       xczu49dr-ffvf1760-2-e
set DSP_MHZ    500.0
set HOST_MHZ   100.0
set BASE_ROW   3                  ;# base X0 clock-region row (Y3..Y7)
set PER_ROW    3                  ;# RiscvSoc cores per X0 row (14 -> 3,3,3,3,2)
set CONFINE    1                  ;# datapath confine: 2=per-region 1=global(X1Y0:X5Y7) 0=none
set RETIMING   1
set DO_ROUTE   1
set DO_PHYSOPT 1
set PLACE_DIR  ExtraNetDelay_high
set ROUTE_DIR  AggressiveExplore

if {[info exists ::env(RISCQ_TOP)]}         { set TOP       $::env(RISCQ_TOP) }
if {[info exists ::env(RISCQ_DEVICE)]}          { set PART      $::env(RISCQ_DEVICE) }
if {[info exists ::env(RISCQ_FMAX_TARGET_MHZ)]} { set DSP_MHZ   $::env(RISCQ_FMAX_TARGET_MHZ) }
if {[info exists ::env(RISCQ_SOC_HOST_MHZ)]}    { set HOST_MHZ  $::env(RISCQ_SOC_HOST_MHZ) }
if {[info exists ::env(RISCQ_ROW)]}         { set BASE_ROW  $::env(RISCQ_ROW) }
if {[info exists ::env(RISCQ_PERROW)]}      { set PER_ROW   $::env(RISCQ_PERROW) }
if {[info exists ::env(RISCQ_CONFINE)]} {
  switch -- [string tolower $::env(RISCQ_CONFINE)] {
    region  { set CONFINE 2 }
    global  { set CONFINE 1 }
    default { set CONFINE 0 }
  }
}
set RETIMING   [riscq_envbool RISCQ_RETIMING $RETIMING]
set DO_ROUTE   [riscq_envbool RISCQ_ROUTE    $DO_ROUTE]
set DO_PHYSOPT [riscq_envbool RISCQ_PHYSOPT  $DO_PHYSOPT]
if {[info exists ::env(RISCQ_PLACE_DIR)]}   { set PLACE_DIR $::env(RISCQ_PLACE_DIR) }
if {[info exists ::env(RISCQ_ROUTE_DIR)]}   { set ROUTE_DIR $::env(RISCQ_ROUTE_DIR) }

set DSP_NS  [format %.3f [expr {1000.0 / $DSP_MHZ}]]
set HOST_NS [format %.3f [expr {1000.0 / $HOST_MHZ}]]
puts "\[riscvsoc\] top=$TOP part=$PART dsp=${DSP_MHZ}MHz host=${HOST_MHZ}MHz row=$BASE_ROW perRow=$PER_ROW confine=$CONFINE retiming=$RETIMING route=$DO_ROUTE physopt=$DO_PHYSOPT place=$PLACE_DIR route_dir=$ROUTE_DIR"

# ---- read RTL (every generated .v in the workspace) ----
set vsrcs [lsort [glob -nocomplain *.v]]
if {[llength $vsrcs] == 0} { error "riscvsoc: no *.v in [pwd] — run gen first (build-riscvsoc.sh without RISCQ_SKIP_GEN)" }
foreach v $vsrcs { read_verilog $v }

# ---- OOC synth. Retiming is timing-driven: synth_design skips it without a clock at synth time, so
#      when on we read a pre-synth clock XDC (exactly as the bench did). ----
if {$RETIMING} {
  set fh [open synth_clocks.xdc w]
  puts $fh "create_clock -name dspClk -period $DSP_NS \[get_ports dspClk\]"
  puts $fh "create_clock -name clk    -period $HOST_NS \[get_ports clk\]"
  close $fh
  read_xdc synth_clocks.xdc
  synth_design -top $TOP -part $PART -mode out_of_context -retiming
} else {
  synth_design -top $TOP -part $PART -mode out_of_context
}
report_utilization -file util_synth.rpt
set_param tcl.collectionResultDisplayLimit 0

# ---- two-clock OOC: tight dspClk (critical domain), loose host clk; CDC (iLoad FifoCc / BufferCC) async. ----
foreach pp [list [list dspClk $DSP_NS] [list clk $HOST_NS]] {
  set pn [lindex $pp 0]; set per [lindex $pp 1]
  if {[llength [get_ports -quiet $pn]] > 0 && [llength [get_clocks -quiet $pn]] == 0} {
    create_clock -name $pn -period $per [get_ports $pn]
  }
}
if {[llength [get_clocks -quiet clk]] > 0 && [llength [get_clocks -quiet dspClk]] > 0} {
  set_clock_groups -asynchronous -group [get_clocks dspClk] -group [get_clocks clk]
}
opt_design

# ---- floorplan: pblock helpers + datapath confine + per-core X0 bands (reads BASE_ROW/PER_ROW/CONFINE) ----
source [file join [file dirname [file normalize [info script]]] pblocks-riscvsoc.tcl]

write_checkpoint -force post_synth.dcp
set fit 1
set why ""
if {[catch {place_design -directive $PLACE_DIR} em]} { set fit 0; set why "place_design failed (does not fit): $em" }
if {$fit} {
  catch { foreach pb [get_pblocks] { report_utilization -pblocks $pb -append -file util_pblock.rpt } }
  write_checkpoint -force post_place.dcp
  if {$DO_ROUTE} {
    if {$DO_PHYSOPT} { catch { phys_opt_design } }
    if {[catch {route_design -directive $ROUTE_DIR} em]} {
      set why "placed OK but route_design failed: $em"
    } else {
      if {$DO_PHYSOPT} { catch { phys_opt_design } }
      write_checkpoint -force post_route.dcp
      report_timing_summary -delay_type max -file timing_summary.rpt
      # Headline = the functional dspClk group only (the host-released dsp reset recovery fans out
      # device-wide into async_default — a one-time release, not a functional path; the full report
      # incl. it is kept in timing_paths_all.rpt).
      report_timing -delay_type max -max_paths 80 -nworst 1 -path_type full -group dspClk -file timing_paths.rpt
      report_timing -delay_type max -max_paths 40 -nworst 1 -path_type full -file timing_paths_all.rpt
      # per-cone failing-endpoint classifier (../report-cones.tcl) → cones_impl.rpt / cones_paths.tsv
      if {[catch {
        set CONES_DIR .
        source [file join [file dirname [file normalize [info script]]] .. report-cones.tcl]
      } _ce]} { puts "\[riscvsoc\] WARN: report-cones failed: $_ce" }
    }
  }
}
set f [open fit_result.txt w]
if {$fit} { puts $f "FIT" } else { puts $f "NOFIT" }
puts $f $why
close $f
puts "\[riscvsoc\] -> [expr {$fit ? {FIT} : {NOFIT}}]  $why"
