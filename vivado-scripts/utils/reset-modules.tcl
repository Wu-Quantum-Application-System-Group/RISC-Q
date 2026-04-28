    # reset module for different clock domain
    set PS_RST_NAME ps_rst
    set PS_RST [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $PS_RST_NAME]

    set DSP_RST_NAME dsp_rst
    set DSP_RST [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $DSP_RST_NAME]

    # connect rst
    connect_bd_net [get_bd_pins ${ZYNQ_PS}/pl_resetn0] [get_bd_pins ${PS_RST}/ext_reset_in] [get_bd_pins ${DSP_RST}/ext_reset_in]
    connect_bd_net [get_bd_pins ${DSP_RST}/peripheral_reset] [get_bd_pins ${TOP}/dspRst]
    connect_bd_net [get_bd_pins ${PS_RST}/peripheral_reset] [get_bd_pins ${TOP}/hostRst]

    # connect clocks
    connect_bd_net [get_bd_pins ${CLKIFC}/hostClk] [get_bd_pins ${PS_RST}/slowest_sync_clk] 
    connect_bd_net [get_bd_pins ${CLKIFC}/dspClk] [get_bd_pins ${DSP_RST}/slowest_sync_clk]