set mgtrefclk_0_pin [get_bd_intf_pins -quiet ${TOP}/mgtrefclk_0_diff]
if {[llength $mgtrefclk_0_pin] > 0} {
  create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 mgtrefclk_0
  connect_bd_intf_net [get_bd_intf_ports mgtrefclk_0] [get_bd_intf_pins -quiet ${TOP}/mgtrefclk_0_diff]

  create_bd_port -dir I gtyrxp_0_in
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtyrxp_in] [get_bd_ports gtyrxp_0_in]
  create_bd_port -dir I gtyrxn_0_in
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtyrxn_in] [get_bd_ports gtyrxn_0_in]
  create_bd_port -dir O gtytxp_0_out
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtytxp_out] [get_bd_ports gtytxp_0_out]
  create_bd_port -dir O gtytxn_0_out
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtytxn_out] [get_bd_ports gtytxn_0_out]
}

set mgtrefclk_1_pin [get_bd_intf_pins -quiet ${TOP}/mgtrefclk_1_diff]
if {[llength $mgtrefclk_1_pin] > 0} {
  create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 mgtrefclk_1
  connect_bd_intf_net [get_bd_intf_ports mgtrefclk_1] [get_bd_intf_pins -quiet ${TOP}/mgtrefclk_1_diff]

  create_bd_port -dir I gtyrxp_1_in
  connect_bd_net [get_bd_pins ${TOP}/io_gts_1_gtyrxp_in] [get_bd_ports gtyrxp_1_in]
  create_bd_port -dir I gtyrxn_1_in
  connect_bd_net [get_bd_pins ${TOP}/io_gts_1_gtyrxn_in] [get_bd_ports gtyrxn_1_in]
  create_bd_port -dir O gtytxp_1_out
  connect_bd_net [get_bd_pins ${TOP}/io_gts_1_gtytxp_out] [get_bd_ports gtytxp_1_out]
  create_bd_port -dir O gtytxn_1_out
  connect_bd_net [get_bd_pins ${TOP}/io_gts_1_gtytxn_out] [get_bd_ports gtytxn_1_out]
}
