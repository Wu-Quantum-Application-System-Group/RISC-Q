# ZCU216 physical + timing constraints for the PulseTableSoc block-design wrapper.
# Pin assignments are taken from the RISC-Q reference (utils/constraints-zcu216.xdc), trimmed to the
# interfaces this SoC actually exposes (no GTY / LED / clk125). The external converter clocks
# (dac_clk / adc_clk / sysref) come from the board clock chip. The three async clock groups keep the
# 500 MHz dsp domain, the 100 MHz host domain, and the PS clock independent for timing.

# 500 MHz dsp clock (LVDS)
set_property -dict {PACKAGE_PIN E10 IOSTANDARD LVDS_25} [get_ports {dspClk_clk_p}]
set_property -dict {PACKAGE_PIN E9  IOSTANDARD LVDS_25} [get_ports {dspClk_clk_n}]
create_clock -period 2.000 -name dspClk_clk_p [get_ports {dspClk_clk_p}]

# user sysref (LVDS)
set_property -dict {PACKAGE_PIN E11 IOSTANDARD LVDS_25} [get_ports {user_sysref_clk_p}]
set_property -dict {PACKAGE_PIN D11 IOSTANDARD LVDS_25} [get_ports {user_sysref_clk_n}]

# 100 MHz host clock (LVDS)
set_property -dict {PACKAGE_PIN G12 IOSTANDARD LVDS_25} [get_ports {hostClk_clk_p}]
set_property -dict {PACKAGE_PIN G11 IOSTANDARD LVDS_25} [get_ports {hostClk_clk_n}]
create_clock -period 10.000 -name hostClk_clk_p [get_ports {hostClk_clk_p}]

# RF Data Converter sample clocks (from the board clock chip)
create_clock -period 2.000 -name dac_clk_clk_p [get_ports {dac_clk_clk_p}]
create_clock -period 2.000 -name adc_clk_clk_p [get_ports {adc_clk_clk_p}]

set_clock_groups -asynchronous -group {dspClk_clk_p}
set_clock_groups -asynchronous -group {hostClk_clk_p}
