global DSP_PERIOD
global DSP_FREQ
global PROJ_NAME
global BOARD
global TOP_MODULE
global SOURCE_PATH
global SCRIPT_PATH
global BUILD_PREFIX
set BOARD zcu216
set DSP_PERIOD 2.000
set DSP_FREQ 500000000
set TOP_MODULE MultiCoreSoc
set SOURCE_PATH ../build/rtl
set SCRIPT_PATH ../vivado-scripts
set BUILD_PREFIX "../build"

proc create {SUFFIX} {
    puts ${SUFFIX}
    global PROJ_NAME
    global BOARD
    global BUILD_PREFIX
    set PROJ_NAME riscq-${BOARD}-${SUFFIX}
    create_project ${PROJ_NAME} ${BUILD_PREFIX}/${PROJ_NAME} -part xczu49dr-ffvf1760-2-e -force

    global SOURCE_PATH
    global SCRIPT_PATH
    global TOP_MODULE
    add_files ${SOURCE_PATH}/${TOP_MODULE}.v
    add_files ${SOURCE_PATH}/ClockInterface.v
    add_files [glob ${SOURCE_PATH}/*.bin]
    # set_property file_type {Memory Initialization Files} [get_files [glob ./rtl/*.bin]]
    set_property file_type {Memory File} [get_files [glob ${SOURCE_PATH}/*.bin]]

    global BOARD
    source ${SCRIPT_PATH}/plip.tcl
    source ${SCRIPT_PATH}/bd-${BOARD}.tcl

    add_files -fileset constrs_1 ${SCRIPT_PATH}/constraints-zcu216.xdc
}

proc synth {} {
    set_property strategy Flow_PerfOptimized_high [get_runs synth_1]

    # set_property STEPS.SYNTH_DESIGN.ARGS.GLOBAL_RETIMING on [get_runs synth_1]

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
proc cb {SUFFIX} {
    if {[catch {current_project} result]} {
        puts "no opened project"
    } else {
        close_project
    }
    create ${SUFFIX}
    build
}

proc rebuild {} {
    close_project
    create
    build
}

proc reopen {SUFFIX} {
    global BOARD
    set PROJ_NAME riscq-${BOARD}-${SUFFIX}
    open_project ${BUILD_PREFIX}/${PROJ_NAME}/${PROJ_NAME}.xpr
}

# set_param general.maxThreads 1

if { $argc > 0 } {
    cb [lindex $argv 0]
}