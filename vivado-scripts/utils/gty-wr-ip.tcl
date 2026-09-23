# White Rabbit GTY PHY — throwaway GT wizard instance for the attribute harvest
# (specs/white-rabbit/01-gty-phy.md §2). Not part of the hardware build: the project
# wrappers src/riscq/wr/gty/WrGty{Channel,Common}.v carry the harvested attributes;
# this script only reproduces the wizard instance they were harvested from.
#
# Config: 1.25 Gb/s TX+RX, 156.25 MHz refclk, QPLL0 (VCO 10 GHz, FBDIV 64, OUT_DIV 8),
# RAW 20-bit user data (internal width 20), TX & RX buffer BYPASS single-lane auto,
# no GT comma align, RXSLIDE off, LPM RX equalizer.
#
# Deviations from the spec 01 §1 sketch, forced by wizard validation (2026.1, wizard 1.7):
# - FREERUN_FREQUENCY 62.5 (not 100): with both buffers bypassed at this rate the wizard
#   caps the free-running clock at the 62.5 MHz USRCLK2 frequency (range (3.125,62.5]).
# - TX_OUTCLK_SOURCE TXPROGDIVCLK (not TXOUTCLKPCS): the only value the wizard allows
#   with the TX buffer bypassed, i.e. TXOUTCLKSEL = 3'b101 (TX_PROGDIV_CFG = 80.0).
# - RX_BUFFER_BYPASS_MODE is not set: with a single enabled channel the parameter is
#   disabled and the wizard elaborates single-lane auto mode by itself (verified in the
#   generated gtwiz_buffbypass_tx/rx: gen_auto_mode/gen_assign_one_chan; channel attrs
#   TX/RXSYNC_MULTILANE = 0, TX/RXSYNC_OVRD = 0).
create_ip -name gtwizard_ultrascale -vendor xilinx.com -library ip -version 1.7 -module_name gty_wr_ip
set_property -dict [list \
    CONFIG.CHANNEL_ENABLE {X0Y4} \
    CONFIG.TX_MASTER_CHANNEL {X0Y4} \
    CONFIG.RX_MASTER_CHANNEL {X0Y4} \
    CONFIG.FREERUN_FREQUENCY {62.5} \
    CONFIG.TX_LINE_RATE {1.25} \
    CONFIG.RX_LINE_RATE {1.25} \
    CONFIG.TX_PLL_TYPE {QPLL0} \
    CONFIG.RX_PLL_TYPE {QPLL0} \
    CONFIG.TX_REFCLK_FREQUENCY {156.25} \
    CONFIG.RX_REFCLK_FREQUENCY {156.25} \
    CONFIG.TX_DATA_ENCODING {RAW} \
    CONFIG.RX_DATA_DECODING {RAW} \
    CONFIG.TX_USER_DATA_WIDTH {20} \
    CONFIG.RX_USER_DATA_WIDTH {20} \
    CONFIG.TX_INT_DATA_WIDTH {20} \
    CONFIG.RX_INT_DATA_WIDTH {20} \
    CONFIG.TX_BUFFER_MODE {0} \
    CONFIG.RX_BUFFER_MODE {0} \
    CONFIG.TX_OUTCLK_SOURCE {TXPROGDIVCLK} \
    CONFIG.RX_OUTCLK_SOURCE {RXOUTCLKPMA} \
    CONFIG.RX_EQ_MODE {LPM} \
    CONFIG.RX_SLIDE_MODE {OFF} \
    CONFIG.RX_COMMA_PRESET {NONE} \
    CONFIG.RX_COMMA_M_ENABLE {false} \
    CONFIG.RX_COMMA_P_ENABLE {false} \
    CONFIG.TX_REFCLK_SOURCE {X0Y4 clk0} \
    CONFIG.RX_REFCLK_SOURCE {X0Y4 clk0} \
    CONFIG.ENABLE_OPTIONAL_PORTS {txresetdone_out rxresetdone_out loopback_in} \
] [get_ips gty_wr_ip]
