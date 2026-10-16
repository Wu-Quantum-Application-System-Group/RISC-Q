# ============================================================================================
# riscvsoc — floorplan (sourced by riscvsoc-pnr.tcl at global scope, proc-free like inc/*.tcl).
#
# The two-region floorplan (docs/soc/ARCH.md). Reads three globals set by riscvsoc-pnr.tcl:
#   BASE_ROW  base X0 clock-region row (Y3..Y7)   — default 3
#   PER_ROW   RiscvSoc cores per X0 row            — default 3  (14 -> 3,3,3,3,2)
#   CONFINE   datapath confine: 2=per-region 1=global(X1Y0:X5Y7) 0=none  — default 1
#
# Floorplan: pin each RiscvSoc core (riscqArea_riscqCores_${i}_riscvSoc/*) + its replicated coreTime_i
# register into a hard SLICE band of its X0 row (EXCLUDE_PLACEMENT reserves the band for that core), and
# confine the whole floating DSP/RF datapath into one hard X1Y0:X5Y7 block so no datapath net routes over
# the X0 core column. BRAM/DSP sites are floated out of each core band (a small X0 rectangle has no DSP
# column, and the I/D BRAM spills to the adjacent column — pinning them would fail for geometry).
# ============================================================================================

# ── pblock helpers (same idioms as ../riscvsoc-bd/pblocks-bd.tcl) ──
proc riscq_make_pblock_sites {name cells rect} {
  set pb [create_pblock $name]
  add_cells_to_pblock -quiet $pb $cells
  resize_pblock $pb -add $rect
  set ramb [get_sites -quiet -of_objects [get_pblocks $pb] -filter {SITE_TYPE =~ RAMB* || SITE_TYPE =~ DSP*}]
  if {[llength $ramb] > 0} { resize_pblock $pb -remove $ramb }
  set_property IS_SOFT FALSE $pb
  # EXCLUDE_PLACEMENT reserves the band's SLICEs for THIS core only — keeps the floating datapath /
  # shared fabric out of the X0 core column (Place 30-487 otherwise).
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

# ── discover the RiscvSoc cores ──
set ids {}
for {set i 0} {$i < 64} {incr i} {
  if {[llength [get_cells -quiet -hierarchical -filter "NAME =~ *riscqArea_riscqCores_${i}_riscvSoc/*"]] > 0} { lappend ids $i }
}
set n [llength $ids]
if {$n == 0} { error "riscvsoc: found no RiscvSoc cores under riscqArea_riscqCores_*_riscvSoc" }
set perRow $PER_ROW
if {$perRow <= 0} { set perRow $n }
set confine $CONFINE
set baseRow $BASE_ROW
puts "\[riscvsoc\] $n cores, perRow=$perRow → X0 rows from Y$baseRow; datapath confine=$confine (2=region 1=global 0=none)"

# ── datapath confine (created BEFORE the core bands so the per-core coreTime replica, which falls into
#    the shared/global group here, is later MOVED into its core band by add_cells_to_pblock) ──
if {$confine == 2} {
  # per-region: each qubit's datapath glue → the X1..X5 columns of ITS row (one pblock per row).
  # (DSP-infeasible at 14q — kept for the record / smaller qubitNum; see docs/soc/ARCH.md.)
  array set dpRowCells {}
  set idx 0
  foreach i $ids {
    set rr [expr {$baseRow + $idx / $perRow}]
    set glue [get_cells -quiet -hierarchical -filter "PRIMITIVE_LEVEL == LEAF && NAME =~ *riscqArea_riscqCores_${i}_* && NAME !~ *_riscvSoc/*"]
    if {[llength $glue] > 0} { lappend dpRowCells($rr) {*}$glue }
    incr idx
  }
  foreach rr [lsort -integer [array names dpRowCells]] {
    set pb [create_pblock pb_dp_y${rr}]
    add_cells_to_pblock -quiet $pb $dpRowCells($rr)
    resize_pblock $pb -add CLOCKREGION_X1Y${rr}:CLOCKREGION_X5Y${rr}
    set_property IS_SOFT FALSE $pb
    puts "\[riscvsoc\] datapath row Y${rr} → X1Y${rr}:X5Y${rr} ([llength $dpRowCells($rr)] cells)"
  }
  # the genuinely SHARED fabric (host AXI bridge, DAC-sum trees, ADC buffers, robs — fan to every core)
  # is not under any riscqCores_i; float it across the whole X1Y0:X5Y7 (off the X0 bands).
  set pbs [create_pblock pb_shared]
  set shared [get_cells -quiet -hierarchical -filter "PRIMITIVE_LEVEL == LEAF && NAME !~ *riscqArea_riscqCores_* && NAME !~ *_riscvSoc/*"]
  add_cells_to_pblock -quiet $pbs $shared
  resize_pblock $pbs -add CLOCKREGION_X1Y0:CLOCKREGION_X5Y7
  set_property IS_SOFT FALSE $pbs
  puts "\[riscvsoc\] shared fabric → X1Y0:X5Y7 ([llength $shared] cells)"
} elseif {$confine == 1} {
  # global form: the whole floating datapath into one X1Y0:X5Y7 block.
  set pbd [create_pblock pb_datapath]
  set dpCells [get_cells -hierarchical -filter {PRIMITIVE_LEVEL == LEAF && NAME !~ "*_riscvSoc/*"}]
  add_cells_to_pblock -quiet $pbd $dpCells
  resize_pblock $pbd -add CLOCKREGION_X1Y0:CLOCKREGION_X5Y7
  set_property IS_SOFT FALSE $pbd
  puts "\[riscvsoc\] datapath confined globally → X1Y0:X5Y7 ([llength $dpCells] cells)"
}

# ── pin every RiscvSoc core into its X0 band; pull its coreTime replica in with it ──
set idx 0
foreach i $ids {
  set rowIdx  [expr {$idx / $perRow}]
  set bandIdx [expr {$idx % $perRow}]
  set rr      [expr {$baseRow + $rowIdx}]
  # keep the core's `mem` URAM OUT of the band at the membership level (X0 rows carry no URAM column,
  # and the site-float trick can't float a site type the band doesn't contain) — it floats to the
  # nearest URAM column instead of raising Place 30-640. Same fix as pblocks-bd.tcl (94eaee4).
  set coreCells [get_cells -hierarchical -filter "NAME =~ *riscqArea_riscqCores_${i}_riscvSoc/* && NAME !~ *riscqArea_riscqCores_${i}_riscvSoc/mem && NAME !~ *riscqArea_riscqCores_${i}_riscvSoc/mem/*"]
  # match the FF cells `coreTime_i_reg[*]` exactly — the trailing `[` excludes the decoder's
  # RegNext(time) copy `coreTime_i_regNext_reg[*]`, which belongs to the (confined) datapath.
  set tcells [get_cells -quiet -hierarchical -filter "NAME =~ *coreTime_${i}_reg\[*"]
  if {[llength $tcells] > 0} { set coreCells [concat $coreCells $tcells] }
  set rect [riscq_slice_band X0Y${rr} $bandIdx $perRow]
  riscq_make_pblock_sites pb_core${i} $coreCells $rect
  puts "\[riscvsoc\] core $i → X0Y${rr} band $bandIdx/$perRow ([llength $coreCells] cells, $rect)"
  incr idx
}
