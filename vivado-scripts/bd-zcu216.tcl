    set BD_NAME riscq_bd
    create_bd_design -dir ${BUILD_PREFIX}/${PROJ_NAME}/bd $BD_NAME
    # open_bd_design {${BUILD_PREFIX}/bd/zynq_bd.bd}
    # set_property bitstream.config.unusedpin pulldown [current_design]

    set ZYNQ_PS_NAME zynq_ps
    set ZYNQ_PS [create_bd_cell -type ip -vlnv xilinx.com:ip:zynq_ultra_ps_e:3.5 $ZYNQ_PS_NAME]
    apply_bd_automation -rule xilinx.com:bd_rule:zynq_ultra_ps_e -config {apply_board_preset "1" }  [get_bd_cells ${ZYNQ_PS_NAME}]

    # axi connect
    set AXI_CONNECT_NAME smartconnect
    set AXI_CONNECT [create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 $AXI_CONNECT_NAME]
    set_property CONFIG.NUM_SI 1 $AXI_CONNECT
    set_property CONFIG.NUM_MI 2 $AXI_CONNECT
    set_property CONFIG.NUM_CLKS {2} [get_bd_cells $AXI_CONNECT]
    set_property CONFIG.HAS_ARESETN {0} [get_bd_cells $AXI_CONNECT]

    # reset module for different clock domain
    set PS_RST_NAME ps_rst
    set PS_RST [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $PS_RST_NAME]

    set DSP_RST_NAME dsp_rst
    set DSP_RST [create_bd_cell -type ip -vlnv xilinx.com:ip:proc_sys_reset:5.0 $DSP_RST_NAME]

    set AXI_GPIO_NAME axi_gpio
    set AXI_GPIO [create_bd_cell -type ip -vlnv xilinx.com:ip:axi_gpio:2.0 $AXI_GPIO_NAME]

    # connect_bd_intf_net []

    # RISCQ
    global RISCQ
    set RISCQ_NAME riscq
    # set RISCQ [create_bd_cell -type module -reference QubicSoc ${RISCQ_NAME}]
    global TOP_MODULE
    set RISCQ [create_bd_cell -type ip -vlnv user.org:user:${TOP_MODULE}:1.0 ${RISCQ_NAME}]

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

    # connect axi
    connect_bd_intf_net -intf_net ${AXI_CONNECT_NAME}_M00_AXI [get_bd_intf_pins ${AXI_CONNECT}/M00_AXI] [get_bd_intf_pins $RISCQ/S_AXIS]
    connect_bd_intf_net -intf_net ${AXI_CONNECT_NAME}_M01_AXI [get_bd_intf_pins ${AXI_CONNECT}/M01_AXI] [get_bd_intf_pins $AXI_GPIO/S_AXI]
    connect_bd_intf_net -intf_net ${ZYNQ_PS_NAME}_M_AXI_HPM0_LPD [get_bd_intf_pins ${ZYNQ_PS}/M_AXI_HPM0_LPD] [get_bd_intf_pins ${AXI_CONNECT}/S00_AXI]

    # rfdc
    source ${SCRIPT_PATH}/rfdc.tcl

    # connect clk
    connect_bd_net -net ${ZYNQ_PS_NAME}_pl_clk0 [get_bd_pins ${ZYNQ_PS}/pl_clk0] [get_bd_pins ${AXI_CONNECT}/aclk] [get_bd_pins ${ZYNQ_PS}/maxihpm0_lpd_aclk]

    connect_bd_net [get_bd_pins ${CLKIFC}/user_sysref] [get_bd_pins rf_data_converter/user_sysref_adc] [get_bd_pins rf_data_converter/user_sysref_dac] 
    connect_bd_net [get_bd_pins ${CLKIFC}/hostClk] [get_bd_pins ${AXI_CONNECT}/aclk1] [get_bd_pins ${AXI_GPIO}/s_axi_aclk] [get_bd_pins ${PS_RST}/slowest_sync_clk] [get_bd_pins ${RISCQ}/hostClk]
    connect_bd_net -net rf_data_converter_clk [get_bd_pins ${CLKIFC}/dspClk] [get_bd_pins ${RISCQ}/dspClk] [get_bd_pins ${DSP_RST}/slowest_sync_clk]
    # connect_bd_net -net ${RISCQ_NAME}_dspClk [get_bd_pins ibufds_dspClk/IBUF_OUT] [get_bd_pins ${RISCQ}/clk] [get_bd_pins ${AXI_CONNECT}/aclk1] [get_bd_pins ${DSP_RST}/slowest_sync_clk]
    # connect_bd_net -net ${RISCQ_NAME}_dspClk [get_bd_pins ibufds_dspClk/IBUF_OUT] [get_bd_pins ${RISCQ}/clk] [get_bd_pins ${AXI_CONNECT}/aclk1] [get_bd_pins ${DSP_RST}/slowest_sync_clk] [get_bd_pins rf_data_converter/m0_axis_aclk] [get_bd_pins rf_data_converter/s0_axis_aclk] [get_bd_pins rf_data_converter/m1_axis_aclk] [get_bd_pins rf_data_converter/s1_axis_aclk] [get_bd_pins rf_data_converter/m2_axis_aclk] [get_bd_pins rf_data_converter/s2_axis_aclk] [get_bd_pins rf_data_converter/m3_axis_aclk] [get_bd_pins rf_data_converter/s3_axis_aclk] [get_bd_pins rf_data_converter/s_axi_aclk]

    global DSP_FREQ
    # set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_pins ${AXI_CONNECT}/aclk1]
    set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_intf_ports dspClk]
    set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_pins ${RISCQ}/dspClk]
    set_property -dict [list CONFIG.FREQ_HZ ${DSP_FREQ}] [get_bd_pins ${CLKIFC}/dspClk]
    set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_ports hostClk]
    set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_pins ${RISCQ}/hostClk]
    set_property -dict [list CONFIG.FREQ_HZ {625000}] [get_bd_pins rf_data_converter/user_sysref_dac]
    set_property -dict [list CONFIG.FREQ_HZ {625000}] [get_bd_pins rf_data_converter/user_sysref_adc]
    set_property -dict [list CONFIG.FREQ_HZ {100000000}] [get_bd_intf_pins ${RISCQ}/S_AXIS]

    # connect rst
    connect_bd_net -net ${ZYNQ_PS_NAME}_pl_resetn0 [get_bd_pins ${ZYNQ_PS}/pl_resetn0] [get_bd_pins ${PS_RST}/ext_reset_in] [get_bd_pins ${DSP_RST}/ext_reset_in]
    connect_bd_net -net ${DSP_RST_NAME}_gpio_reset [get_bd_pins ${AXI_GPIO}/gpio_io_o] [get_bd_pins ${RISCQ}/riscq_rst]
    connect_bd_net -net ${DSP_RST_NAME}_peripheral_reset [get_bd_pins ${DSP_RST}/peripheral_reset] [get_bd_pins ${RISCQ}/dspRst]
    connect_bd_net -net ${PS_RST_NAME}_peripheral_reset [get_bd_pins ${PS_RST}/peripheral_reset] [get_bd_pins ${RISCQ}/hostRst]
    connect_bd_net -net ${PS_RST_NAME}_peripheral_aresetn [get_bd_pins ${PS_RST}/peripheral_aresetn] [get_bd_pins ${AXI_GPIO}/s_axi_aresetn]
    # connect_bd_net -net ${PS_RST_NAME}_peripheral_aresetn [get_bd_pins ${PS_RST}/peripheral_aresetn] [get_bd_pins ${AXI_CONNECT}/aresetn] [get_bd_pins ${RISCQ}/hostRst]

    # assign_bd_address
    assign_bd_address -offset 0x80000000 -range 0x10000000 -target_address_space [get_bd_addr_spaces ${ZYNQ_PS}/Data] [get_bd_addr_segs ${RISCQ}/S_AXIS/reg0] -force
    assign_bd_address -offset 0x90000000 -range 0x00000100 -target_address_space [get_bd_addr_spaces ${ZYNQ_PS}/Data] [get_bd_addr_segs ${AXI_GPIO}/S_AXI/Reg] -force

    validate_bd_design

    make_wrapper -files [get_files ${BD_NAME}.bd] -top -import -force

    # save_bd_design_as zynq_bdps
    close_bd_design $BD_NAME 
    # generate_target all [get_files ${BUILD_PREFIX}/bd/${BD_NAME}/${BD_NAME}.bd]
    generate_target all [get_files ${BD_NAME}.bd]
    set_property -name "top" -value "${BD_NAME}_wrapper" -objects [get_filesets sources_1]