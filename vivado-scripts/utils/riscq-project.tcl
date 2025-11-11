set TOP_MODULE DacSquare

global DSP_PERIOD
set DSP_PERIOD 2.000
global DSP_FREQ


global BOARD
set BOARD zcu216

global TOP_MODULE
global PROJ_NAME

global SOURCE_PATH
set SOURCE_PATH ../rtl

global BUILD_PREFIX
set BUILD_PREFIX ./

global UTILS_PATH
set UTILS_PATH ./utils


proc bd {} {
    global BUILD_PREFIX
    global BD_NAME
    global PROJ_NAME
    global UTILS_PATH
    global TOP_MODULE

    create_bd_design -dir ${BUILD_PREFIX}/bd $BD_NAME

    source ${UTILS_PATH}/zynq-ps.tcl

    # top module
    global TOP_MODULE
    set TOP [create_bd_cell -type ip -vlnv user.org:user:${TOP_MODULE}:1.0 top]

    source ${UTILS_PATH}/reset-modules.tcl

    source ${UTILS_PATH}/axi-smart-connect.tcl

    source ${UTILS_PATH}/gty-connection.tcl

    source ${UTILS_PATH}/clock-interface.tcl

    source ${UTILS_PATH}/rfdc.tcl

    validate_bd_design

    make_wrapper -files [get_files ${BD_NAME}.bd] -top -import -force

    close_bd_design $BD_NAME 
    generate_target all [get_files ${BD_NAME}.bd]
    set_property -name "top" -value "${BD_NAME}_wrapper" -objects [get_filesets sources_1]
}

proc create {NAME} {
    global PROJ_NAME
    global BOARD
    global BUILD_PREFIX
    global UTILS_PATH
    set PROJ_NAME $NAME
    create_project ${PROJ_NAME} ${BUILD_PREFIX}/ -part xczu49dr-ffvf1760-2-e -force

    global SOURCE_PATH
    global TOP_MODULE
    add_files ${SOURCE_PATH}/${TOP_MODULE}.v
    add_files ${SOURCE_PATH}/ClockInterface.v

    global BOARD
    global BD_NAME
    set BD_NAME riscq_bd
    source ${UTILS_PATH}/gty-ip.tcl
    source ${UTILS_PATH}/plip.tcl
    bd
    # source ${SCRIPT_PATH}/top-bd.tcl

    add_files -fileset constrs_1 ${UTILS_PATH}/constraints-zcu216.xdc
    add_files -fileset constrs_1 ${UTILS_PATH}/constraints-riscq-pblock.xdc
}

proc synth {} {
    set_property strategy Flow_PerfOptimized_high [get_runs synth_1]
    # set_property STEPS.SYNTH_DESIGN.ARGS.RETIMING true [get_runs synth_1]
    set_property STEPS.SYNTH_DESIGN.ARGS.GLOBAL_RETIMING on [get_runs synth_1]
    # set_property -name {STEPS.SYNTH_DESIGN.ARGS.MORE OPTIONS} -value {-mode out_of_context} -objects [get_runs synth_1]
    # set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY none [get_runs synth_1]
    launch_runs synth_1
    wait_on_run synth_1
}

proc impl {} {
    # set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE Explore [get_runs impl_1]
    # set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE AggressiveExplore [get_runs impl_1]
    set_property strategy "Performance_NetDelay_high" [get_runs impl_1]
    set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true [get_runs impl_1]
    launch_runs impl_1 -to_step write_bitstream
    wait_on_run impl_1
}

proc build {} {
    puts "start synthesis"
    synth

    puts "start implementation"
    impl
}

# create and build
proc cb {NAME} {
    if {[catch {current_project} result]} {
        puts "no opened project"
    } else {
        close_project
    }
    create ${NAME}
    build
    copy_files
}

proc copy_files {} {
    global PROJ_NAME
    global TOP_MODULE
    global SOURCE_PATH
    global BUILD_PREFIX
    global BD_NAME
    file copy -force ${BUILD_PREFIX}/${PROJ_NAME}.runs/impl_1/${BD_NAME}_wrapper.bit ./${BUILD_PREFIX}/${TOP_MODULE}.bit
    file copy -force ${BUILD_PREFIX}/bd/${BD_NAME}/hw_handoff/${BD_NAME}.hwh ./${BUILD_PREFIX}/${TOP_MODULE}.hwh
    exec tar -czf ${BUILD_PREFIX}/${TOP_MODULE}.tgz -C ${BUILD_PREFIX} ${TOP_MODULE}.bit ${TOP_MODULE}.hwh
}
# set_param general.maxThreads 1

if { $argc > 1 } {
    global TOP_MODULE
    set TOP_MODULE [lindex $argv 0]
    cb [lindex $argv 1]
} elseif { $argc > 0 } {
    global TOP_MODULE
    set TOP_MODULE [lindex $argv 0]
    cb $TOP_MODULE
}