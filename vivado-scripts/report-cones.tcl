# ============================================================================================
# report-cones.tcl — per-cone failing-endpoint classifier (specs/riscv-fmax.md Phase A1).
#
# Classifies every failing endpoint of the open routed design (worst path per endpoint, all
# path groups except async_default reset recovery) into the named logic cones of
# specs/riscv-fmax.md §2 and specs/new-readout-decoder/soc-fmax.md §2.1, and writes:
#   cones_impl.rpt   — cone × {n, TNS, worst, CE/D/SR/other endpoint-pin split} + unmatched
#   cones_paths.tsv  — raw worst-path-per-endpoint dump (slack, levels, datapath delay,
#                      endpoint clock, endpoint pin, startpoint pin) for offline drill-down
# into $CONES_DIR (default: cwd — set the variable before sourcing).
#
# Usage:
#   in-flow    : `source report-cones.tcl` with the routed design open (riscvsoc-pnr.tcl and
#                riscvsoc-bd/inc/run.tcl do this right after their timing reports)
#   standalone : vivado -mode batch -source report-cones.tcl -tclargs <routed.dcp> [outdir]
#
# The cone table is the STABLE unit of timing tracking (the spec's "what not to do" ground
# rule: don't chase individual paths — only 448 of 5792 failing endpoints repeated between the
# two analyzed builds, while the cone totals moved coherently). Gate every Phase B–D lever on these
# numbers, not on a WNS glance. Cone families:
#   core.C1-jumpBroadcast  jumpAt decision fan-out: LOAD_DATA capture S-pins, CSR commit
#                          enables/data, pcReg redirect data      (riscv-fmax §2 C1)
#   core.C2-fetchFrontCE   halt/ready ripple into the fetch-front clock-enables: ctrl0–6
#                          CE pins, pcReg CE                      (riscv-fmax §2 C2)
#   core.C3-operandAlu     regRead→execute operand muxes + ALU result data pins
#                                                                 (riscv-fmax §2 C3)
#   core.other             everything else inside the CPU (riscqFiber_riscq)
#   rvsoc-boundary         RiscvSoc outside the CPU: posted-store shim, dBus/iBus pipes,
#                          iLoad arbiter, control block, PutBridge, result sink
#   rf-link                posted-link pipes at the top (cmd down / upSrc result up)
#   rf-buf                 PulseParamBuffer register files (the cmd demux targets)
#   rf-channel             converter-edge channel glue (envReader, output stages, carrier
#                          distribution) outside the cordic/queue/buf subsets
#   pg-cordic              pulse-generator carrier CORDIC (carrierGen)
#   pg-timedqueue          pulse-generator TimedQueues (ampQ/phaseQ/freqCQ/durQ/…)
#   decoder                readout decoder (decoderRd: demod muls + integrators)
#   adc-pipe               ADC input pipes/replicas + adcSum trace reduction + robs
#   dac-edge / time / env-bank / host-fabric / other — see the rules below
# ============================================================================================

# ---- standalone mode: no open design → open the checkpoint given on the command line ----
if {[catch {current_design}]} {
  if {[llength $argv] < 1} { error "report-cones: no open design and no <routed.dcp> argument" }
  open_checkpoint [lindex $argv 0]
  if {[llength $argv] > 1} { set CONES_DIR [lindex $argv 1] }
}
if {![info exists CONES_DIR]} { set CONES_DIR . }

# ---- ordered classification rules: first regexp match on the endpoint pin name wins ----
set CONE_RULES {
  {core.C1-jumpBroadcast {riscqFiber_riscq/.*ctrl8_up_Execute_LOAD_DATA_reg[^/]*/(S|R|D)$}}
  {core.C1-jumpBroadcast {riscqFiber_riscq/CsrPlugin_logic_[^/]*/(CE|D|S|R)$}}
  {core.C1-jumpBroadcast {riscqFiber_riscq/PcPlugin_logic_pcReg[^/]*/D$}}
  {core.C2-fetchFrontCE  {riscqFiber_riscq/.*ctrl[0-6]_up_[^/]*/CE$}}
  {core.C2-fetchFrontCE  {riscqFiber_riscq/PcPlugin_logic_pcReg[^/]*/CE$}}
  {core.C3-operandAlu    {riscqFiber_riscq/.*ctrl[78]_up_Execute_(SRC1|SRC2|RS2_DATA|ALU_RESULT)[^/]*/D$}}
  {core.other            {riscqFiber_riscq/}}
  {rvsoc-boundary        {_riscvSoc/}}
  {rf-link               {(^|/)cmd_regNext|riscqCores_[0-9]+_cmd|upSrc|resultIn}}
  {pg-timedqueue         {_posted_(gate|ro|demod)Channel/(pg|pulseGenerator[^/]*)/[a-zA-Z]*Q/}}
  {pg-cordic             {_posted_(gate|ro|demod)Channel/(pg|pulseGenerator[^/]*)/carrierGen/}}
  {rf-buf                {_posted_(gate|ro|demod)Channel/buf_1/}}
  {rf-channel            {_posted_(gate|ro|demod)Channel/|io_carrier}}
  {rf-link               {_posted_}}
  {decoder               {decoderRd/|(^|/)decoder}}
  {adc-pipe              {_zz_[^/]*adc[^/]*/|adcSum|adcBuf|robs|riscqArea_adcs_}}
  {dac-edge              {dac}}
  {time                  {coreTime|syncTime|refTime|timeOffset}}
  {env-bank              {(pulse|readout|demod)MemFiber}}
  {host-fabric           {iMemLoad|riscqMemBus|pulseMemBus|readoutEnvBus|demodEnvBus|bridge|hostBus|_cc/}}
}

proc riscq_cone_classify {ep} {
  foreach rule $::CONE_RULES {
    lassign $rule cone pat
    if {[regexp $pat $ep]} { return $cone }
  }
  return other
}

# ---- collect the worst path per endpoint (setup) ----
# Default: failing endpoints only (slack < 0). RISCQ_CONES_SLACK_LT raises the threshold so a
# MEETING design still reports per-cone margins (the band bench uses 0.15 — its A2 target is
# every core cone ≥ +0.10). The TNS column always sums NEGATIVE slack only.
set cones_slt 0
if {[info exists ::env(RISCQ_CONES_SLACK_LT)]} { set cones_slt $::env(RISCQ_CONES_SLACK_LT) }
set cones_paths [get_timing_paths -quiet -max_paths 20000 -slack_lesser_than $cones_slt]
set cones_async 0
array unset cN;  array unset cTNS;  array unset cWorst;  array unset cPin
array unset cUnmatched
set tsvf [open [file join $CONES_DIR cones_paths.tsv] w]
puts $tsvf "slack\tlevels\tdp\tclk\tendpoint\tstartpoint"
foreach p $cones_paths {
  if {[get_property GROUP $p] eq "async_default"} { incr cones_async; continue }
  set slack [get_property SLACK $p]
  set ep    [get_property ENDPOINT_PIN $p]
  puts $tsvf "$slack\t[get_property LOGIC_LEVELS $p]\t[get_property DATAPATH_DELAY $p]\t[get_property ENDPOINT_CLOCK $p]\t$ep\t[get_property STARTPOINT_PIN $p]"
  set cone [riscq_cone_classify $ep]
  if {![info exists cN($cone)]} { set cN($cone) 0; set cTNS($cone) 0.0; set cWorst($cone) 1e9 }
  incr cN($cone)
  if {$slack < 0} { set cTNS($cone) [expr {$cTNS($cone) + $slack}] }
  if {$slack < $cWorst($cone)} { set cWorst($cone) $slack }
  switch -- [lindex [split $ep /] end] {
    CE      { set pc ce }
    D       { set pc d }
    S - R   { set pc sr }
    default { set pc oth }
  }
  if {![info exists cPin($cone,$pc)]} { set cPin($cone,$pc) 0 }
  incr cPin($cone,$pc)
  if {$cone eq "other"} {
    set base $ep
    regsub -all {\[[0-9]+\]} $base "" base
    regsub -all {_[0-9]+} $base "_N" base
    if {![info exists cUnmatched($base)]} { set cUnmatched($base) 0 }
    incr cUnmatched($base)
  }
}
close $tsvf

# ---- write the cone table, worst TNS first ----
set rows {}
set totN 0; set totTNS 0.0; set totWorst 1e9
foreach cone [array names cN] {
  lappend rows [list $cone $cTNS($cone) $cWorst($cone)]
  incr totN $cN($cone)
  set totTNS [expr {$totTNS + $cTNS($cone)}]
  if {$cWorst($cone) < $totWorst} { set totWorst $cWorst($cone) }
}
if {$totWorst == 1e9} { set totWorst 0.0 }
set rptf [open [file join $CONES_DIR cones_impl.rpt] w]
puts $rptf "per-cone endpoint report (specs/riscv-fmax.md A1) — worst path per endpoint, setup, slack < $cones_slt"
puts $rptf "async_default (reset recovery) paths excluded: $cones_async; TNS sums negative slack only"
puts $rptf ""
puts $rptf [format "%-24s %6s %9s %8s %6s %6s %6s %6s" cone n TNS worst CE D S/R oth]
foreach row [lsort -real -index 1 [lsort -real -index 2 $rows]] {
  set cone [lindex $row 0]
  set line [format "%-24s %6d %9.1f %8.3f" $cone $cN($cone) $cTNS($cone) $cWorst($cone)]
  foreach pc {ce d sr oth} {
    set v 0
    if {[info exists cPin($cone,$pc)]} { set v $cPin($cone,$pc) }
    append line [format " %6d" $v]
  }
  puts $rptf $line
}
puts $rptf [format "%-24s %6d %9.1f %8.3f" TOTAL $totN $totTNS $totWorst]
if {[array size cUnmatched] > 0} {
  puts $rptf ""
  puts $rptf "unmatched endpoint shapes (add a rule if one grows):"
  set ul {}
  foreach b [array names cUnmatched] { lappend ul [list $b $cUnmatched($b)] }
  foreach row [lrange [lsort -integer -decreasing -index 1 $ul] 0 9] {
    puts $rptf [format "  %5d  %s" [lindex $row 1] [lindex $row 0]]
  }
}
close $rptf
puts "\[report-cones\] $totN failing endpoints / TNS [format %.1f $totTNS] → [file join $CONES_DIR cones_impl.rpt]"
