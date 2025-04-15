create_bd_cell -type ip -vlnv xilinx.com:ip:usp_rf_data_converter:2.6 rf_data_converter
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout00
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout01
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout02
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout03
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout10
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout11
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout12
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout13
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout20
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout21
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout22
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout23
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout30
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout31
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout32
create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout33
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin00
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin01
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin02
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin03
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin10
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin11
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin12
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin13
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin20
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin21
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin22
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin23
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin30
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin31
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin32
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin33
create_bd_intf_port -mode Slave -vlnv xilinx.com:display_usp_rf_data_converter:diff_pins_rtl:1.0 sysref_in
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 dac_clk
create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 adc_clk

set_property -dict {
CONFIG.ADC0_Clock_Dist {0}
CONFIG.ADC0_Clock_Source {2}
CONFIG.ADC0_Enable {1}
CONFIG.ADC0_Link_Coupling {0}
CONFIG.ADC0_Multi_Tile_Sync {true}
CONFIG.ADC0_Outclk_Freq {250.000}
CONFIG.ADC0_PLL_Enable {false}
CONFIG.ADC0_Refclk_Freq {2000.000}
CONFIG.ADC0_Sampling_Rate {2.000}
CONFIG.ADC1_Clock_Dist {0}
CONFIG.ADC1_Clock_Source {2}
CONFIG.ADC1_Enable {1}
CONFIG.ADC1_Link_Coupling {0}
CONFIG.ADC1_Multi_Tile_Sync {true}
CONFIG.ADC1_Outclk_Freq {250.000}
CONFIG.ADC1_PLL_Enable {false}
CONFIG.ADC1_Refclk_Freq {2000.000}
CONFIG.ADC1_Sampling_Rate {2.000}
CONFIG.ADC2_Clock_Dist {2}
CONFIG.ADC2_Clock_Source {2}
CONFIG.ADC2_Enable {1}
CONFIG.ADC2_Link_Coupling {0}
CONFIG.ADC2_Multi_Tile_Sync {true}
CONFIG.ADC2_Outclk_Freq {250.000}
CONFIG.ADC2_PLL_Enable {true}
CONFIG.ADC2_Refclk_Freq {500.000}
CONFIG.ADC2_Sampling_Rate {2.000}
CONFIG.ADC3_Clock_Dist {0}
CONFIG.ADC3_Clock_Source {2}
CONFIG.ADC3_Enable {1}
CONFIG.ADC3_Link_Coupling {0}
CONFIG.ADC3_Multi_Tile_Sync {true}
CONFIG.ADC3_Outclk_Freq {250.000}
CONFIG.ADC3_PLL_Enable {false}
CONFIG.ADC3_Refclk_Freq {2000.000}
CONFIG.ADC3_Sampling_Rate {2.000}
CONFIG.ADC_Coarse_Mixer_Freq00 {3}
CONFIG.ADC_Data_Width00 {4}
CONFIG.ADC_Decimation_Mode00 {1}
CONFIG.ADC_Dither00 {false}
CONFIG.ADC_Mixer_Type00 {1}
CONFIG.ADC_OBS00 {0}
CONFIG.ADC_Slice00_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq01 {3}
CONFIG.ADC_Data_Width01 {4}
CONFIG.ADC_Decimation_Mode01 {1}
CONFIG.ADC_Dither01 {false}
CONFIG.ADC_Mixer_Type01 {1}
CONFIG.ADC_OBS01 {0}
CONFIG.ADC_Slice01_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq02 {3}
CONFIG.ADC_Data_Width02 {4}
CONFIG.ADC_Decimation_Mode02 {1}
CONFIG.ADC_Dither02 {false}
CONFIG.ADC_Mixer_Type02 {1}
CONFIG.ADC_OBS02 {0}
CONFIG.ADC_Slice02_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq03 {3}
CONFIG.ADC_Data_Width03 {4}
CONFIG.ADC_Decimation_Mode03 {1}
CONFIG.ADC_Dither03 {false}
CONFIG.ADC_Mixer_Type03 {1}
CONFIG.ADC_OBS03 {0}
CONFIG.ADC_Slice03_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq10 {3}
CONFIG.ADC_Data_Width10 {4}
CONFIG.ADC_Decimation_Mode10 {1}
CONFIG.ADC_Dither10 {false}
CONFIG.ADC_Mixer_Type10 {1}
CONFIG.ADC_OBS10 {0}
CONFIG.ADC_Slice10_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq11 {3}
CONFIG.ADC_Data_Width11 {4}
CONFIG.ADC_Decimation_Mode11 {1}
CONFIG.ADC_Dither11 {false}
CONFIG.ADC_Mixer_Type11 {1}
CONFIG.ADC_OBS11 {0}
CONFIG.ADC_Slice11_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq12 {3}
CONFIG.ADC_Data_Width12 {4}
CONFIG.ADC_Decimation_Mode12 {1}
CONFIG.ADC_Dither12 {false}
CONFIG.ADC_Mixer_Type12 {1}
CONFIG.ADC_OBS12 {0}
CONFIG.ADC_Slice12_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq13 {3}
CONFIG.ADC_Data_Width13 {4}
CONFIG.ADC_Decimation_Mode13 {1}
CONFIG.ADC_Dither13 {false}
CONFIG.ADC_Mixer_Type13 {1}
CONFIG.ADC_OBS13 {0}
CONFIG.ADC_Slice13_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq20 {3}
CONFIG.ADC_Data_Width20 {4}
CONFIG.ADC_Decimation_Mode20 {1}
CONFIG.ADC_Dither20 {false}
CONFIG.ADC_Mixer_Type20 {1}
CONFIG.ADC_OBS20 {0}
CONFIG.ADC_Slice20_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq21 {3}
CONFIG.ADC_Data_Width21 {4}
CONFIG.ADC_Decimation_Mode21 {1}
CONFIG.ADC_Dither21 {false}
CONFIG.ADC_Mixer_Type21 {1}
CONFIG.ADC_OBS21 {0}
CONFIG.ADC_Slice21_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq22 {3}
CONFIG.ADC_Data_Width22 {4}
CONFIG.ADC_Decimation_Mode22 {1}
CONFIG.ADC_Dither22 {false}
CONFIG.ADC_Mixer_Type22 {1}
CONFIG.ADC_OBS22 {0}
CONFIG.ADC_Slice22_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq23 {3}
CONFIG.ADC_Data_Width23 {4}
CONFIG.ADC_Decimation_Mode23 {1}
CONFIG.ADC_Dither23 {false}
CONFIG.ADC_Mixer_Type23 {1}
CONFIG.ADC_OBS23 {0}
CONFIG.ADC_Slice23_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq30 {3}
CONFIG.ADC_Data_Width30 {4}
CONFIG.ADC_Decimation_Mode30 {1}
CONFIG.ADC_Dither30 {false}
CONFIG.ADC_Mixer_Type30 {1}
CONFIG.ADC_OBS30 {0}
CONFIG.ADC_Slice30_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq31 {3}
CONFIG.ADC_Data_Width31 {4}
CONFIG.ADC_Decimation_Mode31 {1}
CONFIG.ADC_Dither31 {false}
CONFIG.ADC_Mixer_Type31 {1}
CONFIG.ADC_OBS31 {0}
CONFIG.ADC_Slice31_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq32 {3}
CONFIG.ADC_Data_Width32 {4}
CONFIG.ADC_Decimation_Mode32 {1}
CONFIG.ADC_Dither32 {false}
CONFIG.ADC_Mixer_Type32 {1}
CONFIG.ADC_OBS32 {0}
CONFIG.ADC_Slice32_Enable {true}
CONFIG.ADC_Coarse_Mixer_Freq33 {3}
CONFIG.ADC_Data_Width33 {4}
CONFIG.ADC_Decimation_Mode33 {1}
CONFIG.ADC_Dither33 {false}
CONFIG.ADC_Mixer_Type33 {1}
CONFIG.ADC_OBS33 {0}
CONFIG.ADC_Slice33_Enable {true}
CONFIG.DAC0_Clock_Source {6}
CONFIG.DAC0_Enable {1}
CONFIG.DAC0_Link_Coupling {0}
CONFIG.DAC0_Multi_Tile_Sync {true}
CONFIG.DAC0_Outclk_Freq {500.000}
CONFIG.DAC0_PLL_Enable {false}
CONFIG.DAC0_Refclk_Freq {8000.000}
CONFIG.DAC0_Sampling_Rate {8.000}
CONFIG.DAC0_Clock_Dist {0}
CONFIG.DAC1_Clock_Source {6}
CONFIG.DAC1_Enable {1}
CONFIG.DAC1_Link_Coupling {0}
CONFIG.DAC1_Multi_Tile_Sync {true}
CONFIG.DAC1_Outclk_Freq {500.000}
CONFIG.DAC1_PLL_Enable {false}
CONFIG.DAC1_Refclk_Freq {8000.000}
CONFIG.DAC1_Sampling_Rate {8.000}
CONFIG.DAC1_Clock_Dist {0}
CONFIG.DAC2_Clock_Source {6}
CONFIG.DAC2_Enable {1}
CONFIG.DAC2_Link_Coupling {0}
CONFIG.DAC2_Multi_Tile_Sync {true}
CONFIG.DAC2_Outclk_Freq {500.000}
CONFIG.DAC2_PLL_Enable {true}
CONFIG.DAC2_Refclk_Freq {500.000}
CONFIG.DAC2_Sampling_Rate {8.000}
CONFIG.DAC2_Clock_Dist {2}
CONFIG.DAC3_Clock_Source {6}
CONFIG.DAC3_Enable {1}
CONFIG.DAC3_Link_Coupling {0}
CONFIG.DAC3_Multi_Tile_Sync {true}
CONFIG.DAC3_Outclk_Freq {500.000}
CONFIG.DAC3_PLL_Enable {false}
CONFIG.DAC3_Refclk_Freq {8000.000}
CONFIG.DAC3_Sampling_Rate {8.000}
CONFIG.DAC3_Clock_Dist {0}
CONFIG.DAC_Coarse_Mixer_Freq00 {3}
CONFIG.DAC_Interpolation_Mode00 {1}
CONFIG.DAC_Mixer_Type00 {1}
CONFIG.DAC_Mode00 {3}
CONFIG.DAC_Nyquist00 {0}
CONFIG.DAC_Slice00_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq01 {3}
CONFIG.DAC_Interpolation_Mode01 {1}
CONFIG.DAC_Mixer_Type01 {1}
CONFIG.DAC_Mode01 {3}
CONFIG.DAC_Nyquist01 {0}
CONFIG.DAC_Slice01_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq02 {3}
CONFIG.DAC_Interpolation_Mode02 {1}
CONFIG.DAC_Mixer_Type02 {1}
CONFIG.DAC_Mode02 {3}
CONFIG.DAC_Nyquist02 {0}
CONFIG.DAC_Slice02_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq03 {3}
CONFIG.DAC_Interpolation_Mode03 {1}
CONFIG.DAC_Mixer_Type03 {1}
CONFIG.DAC_Mode03 {3}
CONFIG.DAC_Nyquist03 {0}
CONFIG.DAC_Slice03_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq10 {3}
CONFIG.DAC_Interpolation_Mode10 {1}
CONFIG.DAC_Mixer_Type10 {1}
CONFIG.DAC_Mode10 {3}
CONFIG.DAC_Nyquist10 {0}
CONFIG.DAC_Slice10_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq11 {3}
CONFIG.DAC_Interpolation_Mode11 {1}
CONFIG.DAC_Mixer_Type11 {1}
CONFIG.DAC_Mode11 {3}
CONFIG.DAC_Nyquist11 {0}
CONFIG.DAC_Slice11_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq12 {3}
CONFIG.DAC_Interpolation_Mode12 {1}
CONFIG.DAC_Mixer_Type12 {1}
CONFIG.DAC_Mode12 {3}
CONFIG.DAC_Nyquist12 {0}
CONFIG.DAC_Slice12_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq13 {3}
CONFIG.DAC_Interpolation_Mode13 {1}
CONFIG.DAC_Mixer_Type13 {1}
CONFIG.DAC_Mode13 {3}
CONFIG.DAC_Nyquist13 {0}
CONFIG.DAC_Slice13_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq20 {3}
CONFIG.DAC_Interpolation_Mode20 {1}
CONFIG.DAC_Mixer_Type20 {1}
CONFIG.DAC_Mode20 {3}
CONFIG.DAC_Nyquist20 {0}
CONFIG.DAC_Slice20_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq21 {3}
CONFIG.DAC_Interpolation_Mode21 {1}
CONFIG.DAC_Mixer_Type21 {1}
CONFIG.DAC_Mode21 {3}
CONFIG.DAC_Nyquist21 {0}
CONFIG.DAC_Slice21_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq22 {3}
CONFIG.DAC_Interpolation_Mode22 {1}
CONFIG.DAC_Mixer_Type22 {1}
CONFIG.DAC_Mode22 {3}
CONFIG.DAC_Nyquist22 {0}
CONFIG.DAC_Slice22_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq23 {3}
CONFIG.DAC_Interpolation_Mode23 {1}
CONFIG.DAC_Mixer_Type23 {1}
CONFIG.DAC_Mode23 {3}
CONFIG.DAC_Nyquist23 {0}
CONFIG.DAC_Slice23_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq30 {3}
CONFIG.DAC_Interpolation_Mode30 {1}
CONFIG.DAC_Mixer_Type30 {1}
CONFIG.DAC_Mode30 {3}
CONFIG.DAC_Nyquist30 {0}
CONFIG.DAC_Slice30_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq31 {3}
CONFIG.DAC_Interpolation_Mode31 {1}
CONFIG.DAC_Mixer_Type31 {1}
CONFIG.DAC_Mode31 {3}
CONFIG.DAC_Nyquist31 {0}
CONFIG.DAC_Slice31_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq32 {3}
CONFIG.DAC_Interpolation_Mode32 {1}
CONFIG.DAC_Mixer_Type32 {1}
CONFIG.DAC_Mode32 {3}
CONFIG.DAC_Nyquist32 {0}
CONFIG.DAC_Slice32_Enable {true}
CONFIG.DAC_Coarse_Mixer_Freq33 {3}
CONFIG.DAC_Interpolation_Mode33 {1}
CONFIG.DAC_Mixer_Type33 {1}
CONFIG.DAC_Mode33 {3}
CONFIG.DAC_Nyquist33 {0}
CONFIG.DAC_Slice33_Enable {true}
} [get_bd_cells rf_data_converter]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin00] [get_bd_intf_ports vin00]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout00] [get_bd_intf_ports vout00]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin01] [get_bd_intf_ports vin01]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout01] [get_bd_intf_ports vout01]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin02] [get_bd_intf_ports vin02]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout02] [get_bd_intf_ports vout02]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin03] [get_bd_intf_ports vin03]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout03] [get_bd_intf_ports vout03]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin10] [get_bd_intf_ports vin10]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout10] [get_bd_intf_ports vout10]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin11] [get_bd_intf_ports vin11]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout11] [get_bd_intf_ports vout11]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin12] [get_bd_intf_ports vin12]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout12] [get_bd_intf_ports vout12]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin13] [get_bd_intf_ports vin13]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout13] [get_bd_intf_ports vout13]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin20] [get_bd_intf_ports vin20]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout20] [get_bd_intf_ports vout20]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin21] [get_bd_intf_ports vin21]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout21] [get_bd_intf_ports vout21]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin22] [get_bd_intf_ports vin22]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout22] [get_bd_intf_ports vout22]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin23] [get_bd_intf_ports vin23]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout23] [get_bd_intf_ports vout23]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin30] [get_bd_intf_ports vin30]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout30] [get_bd_intf_ports vout30]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin31] [get_bd_intf_ports vin31]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout31] [get_bd_intf_ports vout31]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin32] [get_bd_intf_ports vin32]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout32] [get_bd_intf_ports vout32]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin33] [get_bd_intf_ports vin33]
connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout33] [get_bd_intf_ports vout33]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC0_AXIS] [get_bd_intf_pins rf_data_converter/s00_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC1_AXIS] [get_bd_intf_pins rf_data_converter/s01_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC2_AXIS] [get_bd_intf_pins rf_data_converter/s02_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC3_AXIS] [get_bd_intf_pins rf_data_converter/s03_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC4_AXIS] [get_bd_intf_pins rf_data_converter/s10_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC5_AXIS] [get_bd_intf_pins rf_data_converter/s11_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC6_AXIS] [get_bd_intf_pins rf_data_converter/s12_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/DAC7_AXIS] [get_bd_intf_pins rf_data_converter/s13_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/ADC0_AXIS] [get_bd_intf_pins rf_data_converter/m00_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/ADC1_AXIS] [get_bd_intf_pins rf_data_converter/m01_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/ADC2_AXIS] [get_bd_intf_pins rf_data_converter/m02_axis]
connect_bd_intf_net [get_bd_intf_pins ${RISCQ}/ADC3_AXIS] [get_bd_intf_pins rf_data_converter/m03_axis]

connect_bd_intf_net [get_bd_intf_ports sysref_in] [get_bd_intf_pins rf_data_converter/sysref_in]
connect_bd_intf_net [get_bd_intf_ports dac_clk] [get_bd_intf_pins rf_data_converter/dac2_clk]
connect_bd_intf_net [get_bd_intf_ports adc_clk] [get_bd_intf_pins rf_data_converter/adc2_clk]
connect_bd_net -net rf_data_converter_clk [get_bd_pins ${CLKIFC}/clk500m] [get_bd_pins rf_data_converter/s0_axis_aclk] [get_bd_pins rf_data_converter/s1_axis_aclk] [get_bd_pins rf_data_converter/s2_axis_aclk] [get_bd_pins rf_data_converter/s3_axis_aclk] [get_bd_pins rf_data_converter/m0_axis_aclk] [get_bd_pins rf_data_converter/m1_axis_aclk] [get_bd_pins rf_data_converter/m2_axis_aclk] [get_bd_pins rf_data_converter/m3_axis_aclk]
connect_bd_net [get_bd_pins ${CLKIFC}/clk100m] [get_bd_pins rf_data_converter/s_axi_aclk]
connect_bd_net -net rf_data_converter_reset [get_bd_pins dsp_rst/peripheral_aresetn] [get_bd_pins rf_data_converter/s0_axis_aresetn] [get_bd_pins rf_data_converter/s1_axis_aresetn] [get_bd_pins rf_data_converter/s2_axis_aresetn] [get_bd_pins rf_data_converter/s3_axis_aresetn] [get_bd_pins rf_data_converter/m0_axis_aresetn] [get_bd_pins rf_data_converter/m1_axis_aresetn] [get_bd_pins rf_data_converter/m2_axis_aresetn] [get_bd_pins rf_data_converter/m3_axis_aresetn]
connect_bd_net [get_bd_pins ${PS_RST}/peripheral_aresetn] [get_bd_pins rf_data_converter/s_axi_aresetn]

