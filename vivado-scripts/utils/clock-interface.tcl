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

# connect clk
connect_bd_net [get_bd_pins ${ZYNQ_PS}/pl_clk0] [get_bd_pins ${AXI_CONNECT}/aclk] [get_bd_pins ${ZYNQ_PS}/maxihpm0_lpd_aclk]
connect_bd_net [get_bd_pins ${CLKIFC}/hostClk] [get_bd_pins ${AXI_CONNECT}/aclk1] [get_bd_pins ${PS_RST}/slowest_sync_clk] [get_bd_pins ${TOP}/hostClk]
connect_bd_net [get_bd_pins ${CLKIFC}/dspClk] [get_bd_pins ${TOP}/dspClk] [get_bd_pins ${DSP_RST}/slowest_sync_clk]

set_property -dict [list CONFIG.FREQ_HZ {500000000}] [get_bd_intf_ports dspClk]
set_property -dict [list CONFIG.FREQ_HZ {500000000}] [get_bd_pins ${TOP}/dspClk]
set_property -dict [list CONFIG.FREQ_HZ {500000000}] [get_bd_pins ${CLKIFC}/dspClk]
set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_ports hostClk]
set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_pins ${TOP}/hostClk]
set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_pins ${TOP}/S_AXIS]
