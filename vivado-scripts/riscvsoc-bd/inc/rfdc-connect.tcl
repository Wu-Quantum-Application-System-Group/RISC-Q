# RF Data Converter wiring: analog vin/vout, DAC/ADC AXIS to the top, sysref/sample clocks, AXIS/AXI
# clocks + resets. The top exposes DAC/ADC AXIS as a flat 0..15; the RFDC names them per tile/slice
# (s/m {tile}{slice}_axis), so DAC{i} -> s{i/4}{i%4}_axis (and likewise ADC{i} -> m..). Loop-driven
# rather than 64 hand-written lines (RISC-Q utils/rfdc.tcl), but the same connections.

for {set t 0} {$t < 4} {incr t} {
  for {set s 0} {$s < 4} {incr s} {
    set i [expr {$t * 4 + $s}]
    create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout${t}${s}
    connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout${t}${s}] [get_bd_intf_ports vout${t}${s}]
    create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin${t}${s}
    connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin${t}${s}] [get_bd_intf_ports vin${t}${s}]
    connect_bd_intf_net [get_bd_intf_pins $TOP/DAC${i}_AXIS] [get_bd_intf_pins rf_data_converter/s${t}${s}_axis]
    connect_bd_intf_net [get_bd_intf_pins $TOP/ADC${i}_AXIS] [get_bd_intf_pins rf_data_converter/m${t}${s}_axis]
  }
}

# sysref + per-tile sample clocks (dac2_clk / adc2_clk are the distributing tiles, CONFIG.*_Clock_Dist 2)
create_bd_intf_port -mode Slave -vlnv xilinx.com:display_usp_rf_data_converter:diff_pins_rtl:1.0 sysref_in
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 dac_clk
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 adc_clk
connect_bd_intf_net [get_bd_intf_ports sysref_in] [get_bd_intf_pins rf_data_converter/sysref_in]
connect_bd_intf_net [get_bd_intf_ports dac_clk]   [get_bd_intf_pins rf_data_converter/dac2_clk]
connect_bd_intf_net [get_bd_intf_ports adc_clk]   [get_bd_intf_pins rf_data_converter/adc2_clk]

# AXIS clocks (dspClk) and AXIS resets (dsp domain, active-low) over all four DAC + four ADC tiles
set axis_aclks   {}
set axis_aresets {}
foreach grp {s0 s1 s2 s3 m0 m1 m2 m3} {
  lappend axis_aclks   [get_bd_pins rf_data_converter/${grp}_axis_aclk]
  lappend axis_aresets [get_bd_pins rf_data_converter/${grp}_axis_aresetn]
}
connect_bd_net [get_bd_pins $CLKIFC/dspClk] {*}$axis_aclks
connect_bd_net [get_bd_pins dsp_rst/peripheral_aresetn] {*}$axis_aresets

# s_axi control port lives in the PS pl_clk0 domain
connect_bd_net [get_bd_pins zynq_ps/pl_clk0]    [get_bd_pins rf_data_converter/s_axi_aclk]
connect_bd_net [get_bd_pins zynq_ps/pl_resetn0] [get_bd_pins rf_data_converter/s_axi_aresetn]
connect_bd_net [get_bd_pins $CLKIFC/user_sysref] \
  [get_bd_pins rf_data_converter/user_sysref_adc] [get_bd_pins rf_data_converter/user_sysref_dac]
