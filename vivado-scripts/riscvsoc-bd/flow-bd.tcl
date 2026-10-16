# ===========================================================================================
# riscvsoc-bd driver — block design + IP packager (PulseTableSoc on ZCU216 / xczu49dr).
#
# Deliberately proc-free: this file sets script-scope configuration variables, then `source`s the
# ordered `inc/*.tcl` steps. Because nothing runs inside a `proc`, every step sees those variables
# directly at global scope — no `global` declarations, no upvar bookkeeping. Each step may publish its
# own handles (e.g. the BD cell paths $TOP / $ZYNQ_PS / $RFDC) for later steps the same way.
# ===========================================================================================

set SCRIPT_DIR [file dirname [file normalize [info script]]]
set INC        $SCRIPT_DIR/inc

source $INC/config.tcl            ;# part / top / freqs / run-stage flags (env-overridable)

source $INC/create-project.tcl    ;# create_project, add top RTL + ROM .bin
source $INC/package-ip.tcl        ;# package the top as a user IP, associate AXIS↔clocks
source $INC/bd-build.tcl          ;# assemble the block design (PS + RFDC + SmartConnect + clocks + resets)
source $INC/bd-finalize.tcl       ;# validate, wrapper, constraints, set top
source $INC/run.tcl               ;# synth (+ optional impl / bitstream), gated by the run flags

puts "\[flow-bd\] done."
