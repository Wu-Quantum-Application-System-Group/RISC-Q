global PROJ_NAME
global TOP_MODULE
global SOURCE_PATH
global BUILD_PREFIX
global BD_NAME
global DSP_FREQ

set TOP_MODULE ""
set SOURCE_PATH "../rtl"
set PROJ_NAME ${TOP_MODULE}
set BUILD_PREFIX "./build"
set DSP_FREQ 500000000

proc create {} {
  global PROJ_NAME
  global TOP_MODULE
  global SOURCE_PATH
  global BUILD_PREFIX
  global BD_NAME
  global DSP_FREQ

  create_project ${PROJ_NAME} ${BUILD_PREFIX}/${PROJ_NAME} -part xczu49dr-ffvf1760-2-e -force

  add_files ${SOURCE_PATH}/${TOP_MODULE}.v
  add_files ${SOURCE_PATH}/ClockInterface.v
  add_files [glob ${SOURCE_PATH}/*.bin]

  set BD_NAME riscq_bd
  create_bd_design -dir ${BUILD_PREFIX}/${PROJ_NAME}/bd $BD_NAME
  # open_bd_design {${BUILD_PREFIX}/bd/zynq_bd.bd}
  # set_property bitstream.config.unusedpin pulldown [current_design]

  set ZYNQ_PS_NAME zynq_ps
  set ZYNQ_PS [create_bd_cell -type ip -vlnv xilinx.com:ip:zynq_ultra_ps_e:3.5 $ZYNQ_PS_NAME]
  apply_bd_automation -rule xilinx.com:bd_rule:zynq_ultra_ps_e -config {apply_board_preset "1" }  [get_bd_cells ${ZYNQ_PS_NAME}]

  # axi connect
  set AXI_CONNECT_NAME smartconnect
  set AXI_CONNECT [create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 $AXI_CONNECT_NAME]
  set_property CONFIG.NUM_SI 1 $AXI_CONNECT
  set_property CONFIG.NUM_MI 1 $AXI_CONNECT
  set_property CONFIG.NUM_CLKS {2} [get_bd_cells $AXI_CONNECT]
  set_property CONFIG.HAS_ARESETN {0} [get_bd_cells $AXI_CONNECT]

  # reset module for different clock domain
  set PS_RST_NAME ps_rst
  set PS_RST [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $PS_RST_NAME]

  set DSP_RST_NAME dsp_rst
  set DSP_RST [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $DSP_RST_NAME]

  # set TOP [create_bd_cell -type ip -vlnv user.org:user:${TOP_MODULE}:1.0 ${TOP_MODULE}]
  set TOP [create_bd_cell -type module -reference $TOP_MODULE $TOP_MODULE]

  set CLKIFC [create_bd_cell -type module -reference ClockInterface clkifc]

  # dsp clock
  create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 dspClk
  connect_bd_intf_net [get_bd_intf_ports dspClk] [get_bd_intf_pins ${CLKIFC}/dspClk_diff]

  # hostClk for bus
  create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 hostClk
  connect_bd_intf_net [get_bd_intf_ports hostClk] [get_bd_intf_pins ${CLKIFC}/hostClk_diff]

  # user sysref
  create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 user_sysref
  connect_bd_intf_net [get_bd_intf_ports user_sysref] [get_bd_intf_pins ${CLKIFC}/user_sysref_diff]

  # connect axi
  connect_bd_intf_net [get_bd_intf_pins ${AXI_CONNECT}/M00_AXI] [get_bd_intf_pins $TOP/S_AXIS]
  connect_bd_intf_net [get_bd_intf_pins ${ZYNQ_PS}/M_AXI_HPM0_LPD] [get_bd_intf_pins ${AXI_CONNECT}/S00_AXI]

  # connect clk
  connect_bd_net [get_bd_pins ${ZYNQ_PS}/pl_clk0] [get_bd_pins ${AXI_CONNECT}/aclk1] [get_bd_pins ${ZYNQ_PS}/maxihpm0_lpd_aclk]

  connect_bd_net [get_bd_pins ${CLKIFC}/hostClk] [get_bd_pins ${AXI_CONNECT}/aclk] [get_bd_pins ${PS_RST}/slowest_sync_clk] [get_bd_pins ${TOP}/clk]
  connect_bd_net [get_bd_pins ${CLKIFC}/dspClk] [get_bd_pins ${DSP_RST}/slowest_sync_clk]

  global DSP_FREQ
  # set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_pins ${AXI_CONNECT}/aclk1]
  set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_intf_ports dspClk]
  set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_pins ${CLKIFC}/dspClk]
  set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_ports hostClk]
  set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_pins ${TOP}/clk]
  set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_pins ${TOP}/S_AXIS]

  # connect rst
  connect_bd_net [get_bd_pins ${ZYNQ_PS}/pl_resetn0] [get_bd_pins ${PS_RST}/ext_reset_in] [get_bd_pins ${DSP_RST}/ext_reset_in]
  connect_bd_net [get_bd_pins ${PS_RST}/peripheral_reset] [get_bd_pins ${TOP}/reset]

  # assign_bd_address
  assign_bd_address -offset 0x80000000 -range 0x10000000 -target_address_space [get_bd_addr_spaces ${ZYNQ_PS}/Data] [get_bd_addr_segs ${TOP}/S_AXIS/reg0] -force

  # create LED port
  create_bd_port -dir O ledR
  create_bd_port -dir O ledB
  set ledR_pin [get_bd_pins -quiet ${TOP}/io_ledR]
  set ledB_pin [get_bd_pins -quiet ${TOP}/io_ledB]
  if {[llength $ledR_pin] > 0} {
    connect_bd_net [get_bd_pins ${ledR_pin}] [get_bd_ports ledR]
  }
  if {[llength $ledB_pin] > 0} {
    connect_bd_net [get_bd_pins ${ledB_pin}] [get_bd_ports ledB]
  }


  validate_bd_design

  make_wrapper -files [get_files ${BD_NAME}.bd] -top -import -force

  # save_bd_design_as zynq_bdps
  close_bd_design $BD_NAME 
  # generate_target all [get_files ${BUILD_PREFIX}/bd/${BD_NAME}/${BD_NAME}.bd]
  generate_target all [get_files ${BD_NAME}.bd]
  set_property -name "top" -value "${BD_NAME}_wrapper" -objects [get_filesets sources_1]

  add_files -fileset constrs_1 ${SOURCE_PATH}/constraints-zcu216.xdc
}

proc gen {} {
  launch_runs synth_1
  wait_on_run synth_1

  launch_runs impl_1 -to_step write_bitstream
  wait_on_run impl_1

  global PROJ_NAME
  global TOP_MODULE
  global SOURCE_PATH
  global BUILD_PREFIX
  global BD_NAME

  file copy -force ${BUILD_PREFIX}/${PROJ_NAME}/${PROJ_NAME}.runs/impl_1/${BD_NAME}_wrapper.bit ./${TOP_MODULE}.bit
  file copy -force ${BUILD_PREFIX}/${PROJ_NAME}/bd/${BD_NAME}/hw_handoff/${BD_NAME}.hwh ./${TOP_MODULE}.hwh
}