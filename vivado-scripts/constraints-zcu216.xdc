#set_property -dict { PACKAGE_PIN H16   IOSTANDARD LVCMOS33 } [get_ports { sysclk }]; #IO_L13P_T2_MRCC_35 Sch=sysclk
#create_clock -add -name sys_clk_pin -period 8.00 -waveform {0 4} [get_ports { sysclk }];
# proc constrain {DSP_PERIOD} {
# global DSP_PERIOD
set_property -dict {PACKAGE_PIN E10 IOSTANDARD LVDS_25} [get_ports {dspClk_clk_p}]
set_property -dict {PACKAGE_PIN E9 IOSTANDARD LVDS_25} [get_ports {dspClk_clk_n}]
set_property -dict {PACKAGE_PIN E11 IOSTANDARD LVDS_25} [get_ports {user_sysref_clk_p}]
set_property -dict {PACKAGE_PIN D11 IOSTANDARD LVDS_25} [get_ports {user_sysref_clk_n}]
set_property -dict {PACKAGE_PIN G12 IOSTANDARD LVDS_25} [get_ports {hostClk_clk_p}]
set_property -dict {PACKAGE_PIN G11 IOSTANDARD LVDS_25} [get_ports {hostClk_clk_n}]

set_property -dict {PACKAGE_PIN AN14 IOSTANDARD LVCMOS12} [get_ports {ledR}]
set_property -dict {PACKAGE_PIN AR21 IOSTANDARD LVCMOS12} [get_ports {ledB}]

create_clock -period 2.000 -name dspClk_clk_p [get_ports {dspClk_clk_p}]
create_clock -period 10.000 -name hostClk_clk_p [get_ports {hostClk_clk_p}]
create_clock -period 2.000 -name dac_clk_clk_p [get_ports {dac_clk_clk_p}]
create_clock -period 2.000 -name adc_clk_clk_p [get_ports {adc_clk_clk_p}]
set_clock_groups -asynchronous -group {dspClk_clk_p} -group {hostClk_clk_p}
# }