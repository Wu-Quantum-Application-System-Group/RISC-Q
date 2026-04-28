    set ZYNQ_PS_NAME zynq_ps
    set ZYNQ_PS [create_bd_cell -type ip -vlnv xilinx.com:ip:zynq_ultra_ps_e:3.5 $ZYNQ_PS_NAME]
    apply_bd_automation -rule xilinx.com:bd_rule:zynq_ultra_ps_e -config {apply_board_preset "1" }  [get_bd_cells ${ZYNQ_PS_NAME}]

    connect_bd_net [get_bd_pins ${ZYNQ_PS}/pl_clk0] [get_bd_pins ${ZYNQ_PS}/maxihpm0_lpd_aclk]