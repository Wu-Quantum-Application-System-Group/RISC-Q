create_ip -name gtwizard_ultrascale -vendor xilinx.com -library ip -version 1.7 -module_name gtwizard_ultrascale_0
set_property CONFIG.preset {GTY-Aurora_64B66B} [get_ips gtwizard_ultrascale_0]
set_property -dict [list \
    CONFIG.CHANNEL_ENABLE {X0Y4} \
    CONFIG.FREERUN_FREQUENCY {100} \
    CONFIG.ENABLE_OPTIONAL_PORTS {rxpolarity_in txresetdone_out rxresetdone_out} \
    CONFIG.RX_REFCLK_SOURCE {X0Y4 clk1} \
    CONFIG.TX_REFCLK_SOURCE {X0Y4 clk1} \
    CONFIG.LOCATE_RX_USER_CLOCKING {CORE} \
    CONFIG.LOCATE_TX_USER_CLOCKING {CORE} \
] [get_ips gtwizard_ultrascale_0]

create_ip -name gtwizard_ultrascale -vendor xilinx.com -library ip -version 1.7 -module_name gtwizard_ultrascale_1
set_property CONFIG.preset {GTY-Aurora_64B66B} [get_ips gtwizard_ultrascale_1]
set_property -dict [list \
    CONFIG.CHANNEL_ENABLE {X0Y8} \
    CONFIG.FREERUN_FREQUENCY {100} \
    CONFIG.ENABLE_OPTIONAL_PORTS {rxpolarity_in txresetdone_out rxresetdone_out} \
    CONFIG.RX_REFCLK_SOURCE {X0Y8 clk1} \
    CONFIG.TX_REFCLK_SOURCE {X0Y8 clk1} \
    CONFIG.LOCATE_RX_USER_CLOCKING {CORE} \
    CONFIG.LOCATE_TX_USER_CLOCKING {CORE} \
] [get_ips gtwizard_ultrascale_1]