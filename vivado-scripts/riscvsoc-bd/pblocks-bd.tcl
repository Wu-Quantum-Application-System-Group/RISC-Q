# ============================================================================================
# riscvsoc-bd — the floorplan, ported from the OOC bench (vivado-scripts/riscvsoc) into the
# BLOCK-DESIGN hierarchy. Sourced as STEPS.PLACE_DESIGN.TCL.PRE (after opt_design, before place_design)
# when build-riscvsoc-bd.sh runs the shared Flow-A assembly with RISCQ_PBLOCK_TCL pointed here.
#
# Same floorplan as vivado-scripts/riscvsoc/pblocks-riscvsoc.tcl (docs/soc/floorplan_plan.md §11):
#   - each RiscvSoc core (+ its replicated coreTime_i) → a hard SLICE band of its X0 row, 3 cores/row
#     across Y3..Y7 (3,3,3,3,2), EXCLUDE_PLACEMENT per band, BRAM/DSP site-floated out, and the core's
#     `mem` URAM kept out of the band by membership (X0 has no URAM column — see the core loop below);
#   - the whole floating DSP/RF datapath → one hard CLOCKREGION_X1Y0:X5Y7 block.
#
# The ONE thing that changes vs the OOC port is the cell prefix: in the block design PulseTableSoc is a
# user IP, so every cell lives under `riscq_bd_i/top/inst/...`. That prefix ($base) both (a) qualifies
# the core/datapath filters and (b) GUARDS the datapath confine so it grabs only PulseTableSoc-internal
# leaf cells — NOT the sibling Zynq PS / RF Data Converter / SmartConnect (which would otherwise match
# a bare `NAME !~ *_riscvSoc/*`). Needs the per-core `KEEP_HIERARCHY` (PulseTableSoc tags it) so the
# `_riscvSoc/` boundary survives opt_design.
#
# Env: RISCQ_ROW (3), RISCQ_PERROW (3), RISCQ_CONFINE (global|region|none, global),
#      RISCQ_BD_BASE (riscq_bd_i/top/inst — the IP instance path).
# ============================================================================================

set base [expr {[info exists ::env(RISCQ_BD_BASE)] ? $::env(RISCQ_BD_BASE) : "riscq_bd_i/top/inst"}]
set baseRow [expr {[info exists ::env(RISCQ_ROW)]    ? $::env(RISCQ_ROW)    : 3}]
set perRow  [expr {[info exists ::env(RISCQ_PERROW)] ? $::env(RISCQ_PERROW) : 3}]
set confineStr [expr {[info exists ::env(RISCQ_CONFINE)] ? [string tolower $::env(RISCQ_CONFINE)] : "global"}]
switch -- $confineStr { region { set confine 2 } global { set confine 1 } default { set confine 0 } }

# Never truncate (and thus error on) large cell collections when one is stringified.
set_param tcl.collectionResultDisplayLimit 0

# ── pblock helpers (same as the OOC port) ──
proc riscq_make_pblock_sites {name cells rect} {
  set pb [create_pblock $name]
  add_cells_to_pblock -quiet $pb $cells
  resize_pblock $pb -add $rect
  set ramb [get_sites -quiet -of_objects [get_pblocks $pb] -filter {SITE_TYPE =~ RAMB* || SITE_TYPE =~ DSP* || SITE_TYPE =~ URAM* || SITE_TYPE =~ BLOCKRAM}]
  if {[llength $ramb] > 0} { resize_pblock $pb -remove $ramb }
  set_property IS_SOFT FALSE $pb
  set_property EXCLUDE_PLACEMENT TRUE $pb
  return $pb
}
proc riscq_slice_band {region idx n} {
  set sites [get_sites -quiet -of_objects [get_clock_regions $region] -filter {SITE_TYPE =~ SLICE*}]
  if {[llength $sites] == 0} { error "riscq_slice_band: no SLICE sites in $region" }
  set xs {}; set ys {}
  foreach s $sites { if {[regexp {SLICE_X(\d+)Y(\d+)} $s -> x y]} { lappend xs $x; lappend ys $y } }
  set xlo [tcl::mathfunc::min {*}$xs]; set xhi [tcl::mathfunc::max {*}$xs]
  set ylo [tcl::mathfunc::min {*}$ys]; set yhi [tcl::mathfunc::max {*}$ys]
  set rows [expr {$yhi - $ylo + 1}]; set per [expr {$rows / $n}]
  set ba [expr {$ylo + $idx * $per}]
  set bb [expr {($idx == $n - 1) ? $yhi : ($ba + $per - 1)}]
  return "SLICE_X${xlo}Y${ba}:SLICE_X${xhi}Y${bb}"
}

# ── discover the RiscvSoc cores under the IP instance ──
set ids {}
for {set i 0} {$i < 64} {incr i} {
  if {[llength [get_cells -quiet -hierarchical -filter "NAME =~ ${base}/riscqArea_riscqCores_${i}_riscvSoc/*"]] > 0} { lappend ids $i }
}
set n [llength $ids]
if {$n == 0} { error "riscvsoc-bd: found no RiscvSoc cores under ${base}/riscqArea_riscqCores_*_riscvSoc (check RISCQ_BD_BASE / keepCoreHierarchy)" }
if {$perRow <= 0} { set perRow $n }
puts "\[riscvsoc-bd\] base=$base; $n cores, perRow=$perRow → X0 rows from Y$baseRow; datapath confine=$confine (2=region 1=global 0=none)"

# ── datapath confine (BEFORE the core bands, so a coreTime replica that lands here is later MOVED into
#    its core band by add_cells_to_pblock). The `NAME =~ ${base}/*` clause keeps the Zynq PS / RFDC /
#    SmartConnect (siblings of the IP) OUT of the datapath group. ──
if {$confine == 2} {
  # per-region: each qubit's datapath glue → the X1..X5 columns of ITS row (DSP-infeasible at 14q;
  # kept for the record / smaller qubitNum — see floorplan_plan.md §11).
  array set dpRowCells {}
  set idx 0
  foreach i $ids {
    set rr [expr {$baseRow + $idx / $perRow}]
    set glue [get_cells -quiet -hierarchical -filter "PRIMITIVE_LEVEL == LEAF && NAME =~ ${base}/riscqArea_riscqCores_${i}_* && NAME !~ ${base}/*_riscvSoc/*"]
    if {[llength $glue] > 0} { lappend dpRowCells($rr) {*}$glue }
    incr idx
  }
  foreach rr [lsort -integer [array names dpRowCells]] {
    set pb [create_pblock pb_dp_y${rr}]
    add_cells_to_pblock -quiet $pb $dpRowCells($rr)
    resize_pblock $pb -add CLOCKREGION_X1Y${rr}:CLOCKREGION_X5Y${rr}
    set_property IS_SOFT FALSE $pb
    puts "\[riscvsoc-bd\] datapath row Y${rr} → X1Y${rr}:X5Y${rr} ([llength $dpRowCells($rr)] cells)"
  }
  set pbs [create_pblock pb_shared]
  # White Rabbit builds: the GTY PHY subtree must stay OUT of any hard confine — its channel is
  # LOC'd at X0Y4 (SFP0) and the BUFG_GT/BUFG_GT_SYNCs it drives are only legal in the GT's own
  # clock region (rule_gt_bufggt: IO Clock Placer fails otherwise; the ANY_CMT_COLUMN workaround
  # would put the link clocks on general routing and break WR's deterministic latency).
  set shared [get_cells -quiet -hierarchical -filter "PRIMITIVE_LEVEL == LEAF && NAME =~ ${base}/* && NAME !~ ${base}/*riscqArea_riscqCores_* && NAME !~ ${base}/*_riscvSoc/* && NAME !~ ${base}/wrGtyPhy_1/*"]
  add_cells_to_pblock -quiet $pbs $shared
  resize_pblock $pbs -add CLOCKREGION_X1Y0:CLOCKREGION_X5Y7
  set_property IS_SOFT FALSE $pbs
  puts "\[riscvsoc-bd\] shared fabric → X1Y0:X5Y7 ([llength $shared] cells)"
} elseif {$confine == 1} {
  # global form: the whole floating datapath into one X1Y0:X5Y7 block (wrGtyPhy excluded — see above).
  set pbd [create_pblock pb_datapath]
  set dpCells [get_cells -hierarchical -filter "PRIMITIVE_LEVEL == LEAF && NAME =~ ${base}/* && NAME !~ ${base}/*_riscvSoc/* && NAME !~ ${base}/wrGtyPhy_1/*"]
  add_cells_to_pblock -quiet $pbd $dpCells
  resize_pblock $pbd -add CLOCKREGION_X1Y0:CLOCKREGION_X5Y7
  set_property IS_SOFT FALSE $pbd
  puts "\[riscvsoc-bd\] datapath confined globally → X1Y0:X5Y7 ([llength $dpCells] cells)"
}

# ── pin every RiscvSoc core into its X0 band; pull its coreTime replica in with it ──
set idx 0
foreach i $ids {
  set rowIdx  [expr {$idx / $perRow}]
  set bandIdx [expr {$idx % $perRow}]
  set rr      [expr {$baseRow + $rowIdx}]
  # The core's I/D memory is one URAM (RiscvSoc `useUram=true`, the `mem` cell). The X0 clock-region
  # rows carry NO URAM column, so a URAM cell cannot live in the core's X0 SLICE band. The site-float
  # trick can't rescue it (it only floats-out a site type the band actually contains), so keep the
  # `mem` subtree OUT of the band pblock at the MEMBERSHIP level — it then floats to the nearest URAM
  # column, exactly as the BRAM I/D RAM did before the switch. Without this: Place 30-640 (a URAM cell
  # trapped in a URAM-less band). `mem` carries KEEP_HIERARCHY, so its name survives opt_design; the
  # core logic still gets pinned.
  set core "${base}/riscqArea_riscqCores_${i}_riscvSoc"
  set coreCells [get_cells -hierarchical -filter "NAME =~ ${core}/* && NAME !~ ${core}/mem && NAME !~ ${core}/mem/*"]
  # the trailing `[` matches the FF cells `coreTime_i_reg[*]` but not the decoder's RegNext copy
  # `coreTime_i_regNext_reg[*]` (which belongs to the confined datapath).
  set tcells [get_cells -quiet -hierarchical -filter "NAME =~ ${base}/*coreTime_${i}_reg\[*"]
  if {[llength $tcells] > 0} { set coreCells [concat $coreCells $tcells] }
  set rect [riscq_slice_band X0Y${rr} $bandIdx $perRow]
  riscq_make_pblock_sites pb_core${i} $coreCells $rect
  puts "\[riscvsoc-bd\] core $i → X0Y${rr} band $bandIdx/$perRow ([llength $coreCells] cells, $rect)"
  incr idx
}
puts "\[riscvsoc-bd\] floorplan applied ($n cores, confine=$confineStr)."

# ── fix #1: waive the iLoad clock-crossing FIFO buffered-reset recovery arcs ──
# Each core's iLoad CC FIFO (hostCd→dspCd) carries SpinalHDL's buffered cross-clock reset
# (asyncAssertSyncDeassert; crossClockBufferPushToPopResetGen defaults on). It derives from the single
# global dspRst, and because the 14 cores are the SAME Verilog module synthesis keeps ONE synchronizer
# (under core0) and fans it as a device-wide net into every core's CC async-clears (fo≈238). Its
# recovery arc (core0 sync → core13 /CLR) was the BD WNS — and post-route phys_opt, which optimises
# setup but NOT async_default recovery, drifts that unwaived net (routed −0.093 → physopt −0.238).
# The reset is synchronized and released once while the FIFOs are idle, and every FIFO pointer carries
# init(0) (GSR brings them up empty regardless), so this recovery/removal is a non-functional arc.
# Waive it here (sourced as PLACE_DESIGN.TCL.PRE, so it is active through place/route/phys_opt and
# persists into the routed checkpoint). See docs/soc/floorplan_plan.md.
set _ccrst [get_nets -hierarchical -filter "NAME =~ ${base}/*iLoad_node_to_iMemPortArb_cc*reset_synchronized*"]
if {[llength $_ccrst] > 0} {
  set_false_path -through $_ccrst
  puts "\[riscvsoc-bd\] waived iLoad-CC buffered-reset recovery arcs ([llength $_ccrst] nets)"
} else {
  puts "\[riscvsoc-bd\] WARN: no iLoad-CC reset_synchronized nets matched under ${base} — check naming"
}

# Same waiver for the host-window CC FIFOs (specs/software/22 §2.2): each core's result stream crosses
# dspCd -> hostCd in a StreamFifoCC that carries the identical buffered cross-clock reset, derived from
# the same global dspRst. Same argument: it is synchronized, released once while the FIFOs are idle, and
# every pointer carries init(0), so the recovery/removal arc is non-functional.
set _hwrst [get_nets -hierarchical -filter "NAME =~ ${base}/*hostWinFifo*synchronized*"]
if {[llength $_hwrst] > 0} {
  set_false_path -through $_hwrst
  puts "\[riscvsoc-bd\] waived hostWindow-CC buffered-reset recovery arcs ([llength $_hwrst] nets)"
} else {
  puts "\[riscvsoc-bd\] WARN: no hostWinFifo synchronized nets matched under ${base} — check naming"
}

# ── fix #2 (optional, RISCQ_MREG_LOCK): freeze the carrierGen ComplexMul product DSPs against phys_opt ──
# specs/dsp-fmax.md: synthesis packs each recombination product multiply fully pipelined (MREG=1 — the
# opt_design DRC shows DPOP-4=0), but post-route phys_opt's DSP-register optimization retimes the MREG
# back out on a placement-dependent subset of the carrier muls (routed DPOP-4 = 4..18, all on
# .../carrierGen/muls_*/{pim4,pre4}_reg) — an internally-unpipelined 27x18 mult->ALU that can't make
# 500 MHz. DONT_TOUCH on those DSP48E2 cells, set here at PLACE_DESIGN.TCL.PRE (after opt_design, so
# MREG is already 1), freezes their internal register configuration through place / route / phys_opt
# while still allowing the placer to move them. Zero area; scoped to the proven-fragile carrier products
# only (the envelope / decoder ComplexMuls are never stripped). Gated so it can be A/B'd against a
# baseline where phys_opt runs unconstrained.
if {[info exists ::env(RISCQ_MREG_LOCK)] && $::env(RISCQ_MREG_LOCK) ne "0"} {
  set _pdsp [get_cells -quiet -hierarchical -filter "REF_NAME =~ DSP48E2* && NAME =~ ${base}/*carrierGen/muls_*"]
  if {[llength $_pdsp] > 0} {
    set_property DONT_TOUCH true $_pdsp
    puts "\[riscvsoc-bd\] MREG lock: DONT_TOUCH on [llength $_pdsp] carrierGen ComplexMul DSP48E2 cells"
  } else {
    puts "\[riscvsoc-bd\] WARN: RISCQ_MREG_LOCK set but no carrierGen DSP48E2 cells matched under ${base}"
  }
}
