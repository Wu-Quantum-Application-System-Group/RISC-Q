#set_property -dict { PACKAGE_PIN H16   IOSTANDARD LVCMOS33 } [get_ports { sysclk }]; #IO_L13P_T2_MRCC_35 Sch=sysclk
#create_clock -add -name sys_clk_pin -period 8.00 -waveform {0 4} [get_ports { sysclk }];
# proc constrain {DSP_PERIOD} {
# global DSP_PERIOD
set_property -dict {PACKAGE_PIN E10 IOSTANDARD LVDS_25} [get_ports {dspClk_clk_p}]
set_property -dict {PACKAGE_PIN E9 IOSTANDARD LVDS_25} [get_ports {dspClk_clk_n}]
create_clock -period 2.000 -name dspClk_clk_p [get_ports {dspClk_clk_p}]

set_property -dict {PACKAGE_PIN E11 IOSTANDARD LVDS_25} [get_ports {user_sysref_clk_p}]
set_property -dict {PACKAGE_PIN D11 IOSTANDARD LVDS_25} [get_ports {user_sysref_clk_n}]

set_property -dict {PACKAGE_PIN A13 IOSTANDARD LVDS_25} [get_ports {clk125_clk_p}]
set_property -dict {PACKAGE_PIN A12 IOSTANDARD LVDS_25} [get_ports {clk125_clk_n}]
create_clock -period 10.000 -name clk125_clk_p [get_ports {clk125_clk_p}]


set_property -dict {PACKAGE_PIN G12 IOSTANDARD LVDS_25} [get_ports {hostClk_clk_p}]
set_property -dict {PACKAGE_PIN G11 IOSTANDARD LVDS_25} [get_ports {hostClk_clk_n}]
create_clock -period 10.000 -name hostClk_clk_p [get_ports {hostClk_clk_p}]

# set_property -dict {PACKAGE_PIN AA36} [get_ports {mgtrefclk_clk_p}]
# set_property -dict {PACKAGE_PIN AA37} [get_ports {mgtrefclk_clk_n}]
# create_clock -period 6.4 -name mgtrefclk [get_ports {mgtrefclk_clk_p}]
set_property -dict {PACKAGE_PIN Y34} [get_ports {mgtrefclk_0_clk_p}]
set_property -dict {PACKAGE_PIN Y35} [get_ports {mgtrefclk_0_clk_n}]
create_clock -period 6.4 -name mgtrefclk_0 [get_ports {mgtrefclk_0_clk_p}]


set_property PACKAGE_PIN AC42     [get_ports "gtyrxn_in"] ;# Bank 128 - MGTYRXN0_128
set_property PACKAGE_PIN AC41     [get_ports "gtyrxp_in"] ;# Bank 128 - MGTYRXP0_128
set_property PACKAGE_PIN V39      [get_ports "gtytxn_out"] ;# Bank 128 - MGTYTXN0_128
set_property PACKAGE_PIN V38      [get_ports "gtytxp_out"] ;# Bank 128 - MGTYTXP0_128

set_property -dict {PACKAGE_PIN AN14 IOSTANDARD LVCMOS12} [get_ports {ledR}]
set_property -dict {PACKAGE_PIN AR21 IOSTANDARD LVCMOS12} [get_ports {ledB}]

create_clock -period 2.000 -name dac_clk_clk_p [get_ports {dac_clk_clk_p}]
create_clock -period 2.000 -name adc_clk_clk_p [get_ports {adc_clk_clk_p}]
set_clock_groups -asynchronous -group {dspClk_clk_p}
set_clock_groups -asynchronous -group {hostClk_clk_p}
set_clock_groups -asynchronous -group [get_clocks mgtrefclk -include_generated_clocks]
# }

# get_sites -filter {SITE_TYPE =~ BUFG*}
# get_cells -hierarchical -regexp .*riscqReset.()
# set_property LOC BUFGCE_X0Y98 [get_cells {riscq_bd_i/riscq/inst/riscqReset_BUFG}]
# set_property LOC SLICE_X81Y247 [get_cells {riscq_bd_i/riscq/inst/riscqResetReg_reg}]