set mgtrefclk_pin [get_bd_intf_pins -quiet ${TOP}/mgtrefclk_0_diff]
if {[llength $mgtrefclk_pin] > 0} {
  create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 mgtrefclk_0
  connect_bd_intf_net [get_bd_intf_ports mgtrefclk_0] [get_bd_intf_pins ${mgtrefclk_pin}]

  create_bd_port -dir I gtyrxp_0_in
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtyrxp_in] [get_bd_ports gtyrxp_0_in]
  create_bd_port -dir I gtyrxn_0_in
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtyrxn_in] [get_bd_ports gtyrxn_0_in]
  create_bd_port -dir O gtytxp_0_out
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtytxp_out] [get_bd_ports gtytxp_0_out]
  create_bd_port -dir O gtytxn_0_out
  connect_bd_net [get_bd_pins ${TOP}/io_gts_0_gtytxn_out] [get_bd_ports gtytxn_0_out]
}
