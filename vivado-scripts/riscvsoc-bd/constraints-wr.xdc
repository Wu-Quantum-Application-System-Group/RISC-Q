# White Rabbit GTY link constraints (specs/white-rabbit/09) — added by bd-finalize.tcl only when
# the build's PulseTableSoc was generated with "with_white_rabbit": true (the wr* ports exist).
#
# Physical wiring (verified against the xczu49dr-ffvf1760 package + both board references —
# QubiC gty_sfp.tcl and the RISC-Q reference XDC): the link rides SFP0 = GTY bank 129 lane 0
# (channel X0Y4, RX W41/W42, TX P38/P39 — implied by the channel LOC), reference clock =
# MGTREFCLK1 of bank 129 (T34/T35) at 156.25 MHz, the frequency both reference designs run.

set_property PACKAGE_PIN T34 [get_ports wrRefClkP]
set_property PACKAGE_PIN T35 [get_ports wrRefClkN]
create_clock -period 6.400 -name wrRefClk [get_ports wrRefClkP]

# one WR channel in the design -> the SFP0 site; lane pads are dedicated (no PACKAGE_PIN needed)
set_property LOC GTYE4_CHANNEL_X0Y4 [get_cells -hierarchical -filter {REF_NAME == GTYE4_CHANNEL}]

# clk_free = hostClk/2 FF divider feeding the GT bring-up FSMs through a BUFG
# (PG182: free-run clock <= 62.5 MHz with both buffers bypassed)
create_generated_clock -name wrClkFree -divide_by 2 \
  -source [get_pins -hierarchical -filter {NAME =~ */wrClkFreeDiv*/C}] \
  [get_pins -hierarchical -filter {NAME =~ */wrClkFreeDiv*/Q}]

# The WR domains (refclk-derived TXOUTCLK/BUFG_GT 62.5 MHz clk_ref, recovered clk_rx — both
# auto-derived from wrRefClk through the GT — and clk_free) are asynchronous to everything:
# every crossing is a BufferCC/StreamFifoCC/toggle-CC inside WrNode/WrGtyPhy (spec 06 §1).
set_clock_groups -asynchronous -group [get_clocks -include_generated_clocks wrRefClk]
set_clock_groups -asynchronous -group [get_clocks wrClkFree]
