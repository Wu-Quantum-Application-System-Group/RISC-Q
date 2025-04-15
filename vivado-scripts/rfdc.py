ids = [f'{i}'+f'{j}' for i in range(4) for j in range (4)]

# dac_map = {
#     'DAC0': '00',
#     'DAC1': '01',
#     'DAC2': '02',
#     'DAC3': '03'
# }

# adc_map = {
#     'ADC0': '00',
#     'ADC1': '01',
#     'ADC2': '02',
#     'ADC3': '03'
# }

# 4 qubits

dac_map = {
    'DAC0': '00',
    'DAC1': '01',
    'DAC2': '02',
    'DAC3': '03',
    'DAC4': '10',
    'DAC5': '11',
    'DAC6': '12',
    'DAC7': '13',
}

adc_map = {
    'ADC0': '00',
    'ADC1': '01',
    'ADC2': '02',
    'ADC3': '03',
}

# 8 qubits

# dac_map = {
#     'DAC0': '00',
#     'DAC1': '01',
#     'DAC2': '02',
#     'DAC3': '03',
#     'DAC4': '10',
#     'DAC5': '11',
#     'DAC6': '12',
#     'DAC7': '13',
#     'DAC8': '20',
#     'DAC9': '21',
#     'DAC10': '22',
#     'DAC11': '23',
#     'DAC12': '30',
#     'DAC13': '31',
#     'DAC14': '32',
#     'DAC15': '33',
# }

# adc_map = {
#     'ADC0': '00',
#     'ADC1': '01',
#     'ADC2': '02',
#     'ADC3': '03',
#     'ADC4': '10',
#     'ADC5': '11',
#     'ADC6': '12',
#     'ADC7': '13'
# }

def dac_intf_to_tile(intf: str):
    return intf[0]

def collect_dac_tile(intf_map):
    res = set()
    for k, v in intf_map.items():
        res.add(dac_intf_to_tile(v))
    return list(res)

def create_intfs(dac_map, adc_map):
    res = 'create_bd_cell -type ip -vlnv xilinx.com:ip:usp_rf_data_converter:2.6 rf_data_converter\n'

    # for k, v in dac_map.items():
    for v in ids:
        res += f'create_bd_intf_port -mode Master -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vout{v}\n'
    # for k, v in adc_map.items():
    for v in ids:
        res += f'create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_analog_io_rtl:1.0 vin{v}\n'
    res += 'create_bd_intf_port -mode Slave -vlnv xilinx.com:display_usp_rf_data_converter:diff_pins_rtl:1.0 sysref_in\n'
    res += 'create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 dac_clk\n'
    res += 'create_bd_intf_port -mode Slave -vlnv xilinx.com:interface:diff_clock_rtl:1.0 adc_clk\n'
    return res

def connect_intfs(dac_map, adc_map):
    res = ''
    riscq = '${RISCQ}'
    # for k, v in adc_map.items():
    for v in ids:
        res += f'connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vin{v}] [get_bd_intf_ports vin{v}]\n'
        res += f'connect_bd_intf_net [get_bd_intf_pins rf_data_converter/vout{v}] [get_bd_intf_ports vout{v}]\n'
    for k, v in dac_map.items():
        res += f'connect_bd_intf_net [get_bd_intf_pins {riscq}/{k}_AXIS] [get_bd_intf_pins rf_data_converter/s{v}_axis]\n'
    for k, v in adc_map.items():
        res += f'connect_bd_intf_net [get_bd_intf_pins {riscq}/{k}_AXIS] [get_bd_intf_pins rf_data_converter/m{v}_axis]\n'
    return res

def connect_clk_rst(dac_tiles, adc_tiles):
    res = ''
    res += 'connect_bd_intf_net [get_bd_intf_ports sysref_in] [get_bd_intf_pins rf_data_converter/sysref_in]\n'
    res += 'connect_bd_intf_net [get_bd_intf_ports dac_clk] [get_bd_intf_pins rf_data_converter/dac2_clk]\n'
    res += 'connect_bd_intf_net [get_bd_intf_ports adc_clk] [get_bd_intf_pins rf_data_converter/adc2_clk]\n'
    # res += 'connect_bd_net -net rf_data_converter_clk [get_bd_pins ibufds_clk500m/IBUF_OUT] [get_bd_pins ${RISCQ}/clk500m] [get_bd_pins ${DSP_RST}/slowest_sync_clk]'
    res += 'connect_bd_net -net rf_data_converter_clk [get_bd_pins ${CLKIFC}/clk500m]'
    # for i in dac_tiles:
    for i in range(4):
        res += f' [get_bd_pins rf_data_converter/s{i}_axis_aclk]'
    # for i in adc_tiles:
    for i in range(4):
        res += f' [get_bd_pins rf_data_converter/m{i}_axis_aclk]'
    res += '\n'
    # res += 'connect_bd_net [get_bd_pins ibufds_clk100m/IBUF_OUT] [get_bd_pins rf_data_converter/s_axi_aclk]\n'
    res += 'connect_bd_net [get_bd_pins ${CLKIFC}/clk100m] [get_bd_pins rf_data_converter/s_axi_aclk]\n'
    res += 'connect_bd_net -net rf_data_converter_reset [get_bd_pins dsp_rst/peripheral_aresetn]'
    # for i in dac_tiles:
    for i in range(4):
        res += f' [get_bd_pins rf_data_converter/s{i}_axis_aresetn]'
    # for i in adc_tiles:
    for i in range(4):
        res += f' [get_bd_pins rf_data_converter/m{i}_axis_aresetn]'
    res += '\n'
    res += 'connect_bd_net [get_bd_pins ${PS_RST}/peripheral_aresetn] [get_bd_pins rf_data_converter/s_axi_aresetn]\n'
    return res

def rfdc_properties(dac_tiles, adc_tiles):
    res = 'set_property -dict {\n'
    for i in range(4):
        clock_dist = '2' if i == 2 else '0'
        pll_enable = 'true' if i == 2 else 'false'
        refclk_freq = '500.000' if i == 2 else '2000.000'
        res += '''CONFIG.ADC%(i)d_Clock_Dist {%(clock_dist)s}
CONFIG.ADC%(i)d_Clock_Source {2}
CONFIG.ADC%(i)d_Enable {1}
CONFIG.ADC%(i)d_Link_Coupling {0}
CONFIG.ADC%(i)d_Multi_Tile_Sync {true}
CONFIG.ADC%(i)d_Outclk_Freq {250.000}
CONFIG.ADC%(i)d_PLL_Enable {%(pll_enable)s}
CONFIG.ADC%(i)d_Refclk_Freq {%(refclk_freq)s}
CONFIG.ADC%(i)d_Sampling_Rate {2.000}
''' % ({'i': i, 'clock_dist': clock_dist, 'pll_enable': pll_enable, 'refclk_freq': refclk_freq})
    for i in range(4):
        for j in range(4):
            res += '''CONFIG.ADC_Coarse_Mixer_Freq%(i)d%(j)d {3}
CONFIG.ADC_Data_Width%(i)d%(j)d {4}
CONFIG.ADC_Decimation_Mode%(i)d%(j)d {1}
CONFIG.ADC_Dither%(i)d%(j)d {false}
CONFIG.ADC_Mixer_Type%(i)d%(j)d {1}
CONFIG.ADC_OBS%(i)d%(j)d {0}
CONFIG.ADC_Slice%(i)d%(j)d_Enable {true}
''' % ({'i': i, 'j': j})

    for i in range(4):
        clock_dist = '2' if i == 2 else '0'
        pll_enable = 'true' if i == 2 else 'false'
        refclk_freq = '500.000' if i == 2 else '8000.000'
        res += '''CONFIG.DAC%(i)d_Clock_Source {6}
CONFIG.DAC%(i)d_Enable {1}
CONFIG.DAC%(i)d_Link_Coupling {0}
CONFIG.DAC%(i)d_Multi_Tile_Sync {true}
CONFIG.DAC%(i)d_Outclk_Freq {500.000}
CONFIG.DAC%(i)d_PLL_Enable {%(pll_enable)s}
CONFIG.DAC%(i)d_Refclk_Freq {%(refclk_freq)s}
CONFIG.DAC%(i)d_Sampling_Rate {8.000}
CONFIG.DAC%(i)d_Clock_Dist {%(clock_dist)s}
''' % ({'i': i, 'clock_dist': clock_dist, 'pll_enable': pll_enable, 'refclk_freq': refclk_freq})
    for i in range(4):
        for j in range(4):
            res += '''CONFIG.DAC_Coarse_Mixer_Freq%(i)d%(j)d {3}
CONFIG.DAC_Interpolation_Mode%(i)d%(j)d {1}
CONFIG.DAC_Mixer_Type%(i)d%(j)d {1}
CONFIG.DAC_Mode%(i)d%(j)d {3}
CONFIG.DAC_Nyquist%(i)d%(j)d {0}
CONFIG.DAC_Slice%(i)d%(j)d_Enable {true}
''' % ({'i': i, 'j': j})
    res += '} [get_bd_cells rf_data_converter]'
    return res

if __name__ == '__main__':
    dac_tiles = collect_dac_tile(dac_map)
    adc_tiles = collect_dac_tile(adc_map)
    print(create_intfs(dac_map, adc_map))
    print(rfdc_properties(dac_tiles, adc_tiles))
    print(connect_intfs(dac_map, adc_map))
    print(connect_clk_rst(dac_tiles, adc_tiles))