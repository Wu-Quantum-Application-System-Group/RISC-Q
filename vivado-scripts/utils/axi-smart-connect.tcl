set AXI_CONNECT_NAME smartconnect
set AXI_CONNECT [create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 $AXI_CONNECT_NAME]
set_property CONFIG.NUM_SI 1 $AXI_CONNECT
set_property CONFIG.NUM_MI 2 $AXI_CONNECT
set_property CONFIG.NUM_CLKS {2} [get_bd_cells $AXI_CONNECT]
set_property CONFIG.HAS_ARESETN {0} [get_bd_cells $AXI_CONNECT]

# connect axi
connect_bd_intf_net [get_bd_intf_pins ${AXI_CONNECT}/M00_AXI] [get_bd_intf_pins $TOP/S_AXIS]
connect_bd_intf_net [get_bd_intf_pins ${AXI_CONNECT}/M01_AXI] [get_bd_intf_pins rf_data_converter/s_axi]
connect_bd_intf_net [get_bd_intf_pins ${ZYNQ_PS}/M_AXI_HPM0_LPD] [get_bd_intf_pins ${AXI_CONNECT}/S00_AXI]

# connect clocks
connect_bd_net [get_bd_pins ${ZYNQ_PS}/pl_clk0] [get_bd_pins ${AXI_CONNECT}/aclk]
connect_bd_net [get_bd_pins ${CLKIFC}/hostClk] [get_bd_pins ${AXI_CONNECT}/aclk1]


# assign_bd_address
assign_bd_address -offset 0x80000000 -range 0x10000000 -target_address_space [get_bd_addr_spaces ${ZYNQ_PS}/Data] [get_bd_addr_segs ${TOP}/S_AXIS/reg0] -force
assign_bd_address 
