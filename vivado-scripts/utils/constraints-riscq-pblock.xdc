create_pblock {pblock_riscq_core_0} 
resize_pblock {pblock_riscq_core_0} -add {CLOCKREGION_X2Y7:CLOCKREGION_X2Y7} 
add_cells_to_pblock {pblock_riscq_core_0} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_0_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_1} 
resize_pblock {pblock_riscq_core_1} -add {CLOCKREGION_X2Y7:CLOCKREGION_X2Y7} 
add_cells_to_pblock {pblock_riscq_core_1} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_1_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_2} 
resize_pblock {pblock_riscq_core_2} -add {CLOCKREGION_X2Y6:CLOCKREGION_X2Y6} 
add_cells_to_pblock {pblock_riscq_core_2} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_2_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_3} 
resize_pblock {pblock_riscq_core_3} -add {CLOCKREGION_X2Y6:CLOCKREGION_X2Y6} 
add_cells_to_pblock {pblock_riscq_core_3} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_3_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_4} 
resize_pblock {pblock_riscq_core_4} -add {CLOCKREGION_X2Y5:CLOCKREGION_X2Y5} 
add_cells_to_pblock {pblock_riscq_core_4} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_4_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_5} 
resize_pblock {pblock_riscq_core_5} -add {CLOCKREGION_X2Y5:CLOCKREGION_X2Y5} 
add_cells_to_pblock {pblock_riscq_core_5} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_5_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_6} 
resize_pblock {pblock_riscq_core_6} -add {CLOCKREGION_X2Y4:CLOCKREGION_X2Y4} 
add_cells_to_pblock {pblock_riscq_core_6} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_6_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_7} 
resize_pblock {pblock_riscq_core_7} -add {CLOCKREGION_X2Y4:CLOCKREGION_X2Y4} 
add_cells_to_pblock {pblock_riscq_core_7} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_7_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_8} 
resize_pblock {pblock_riscq_core_8} -add {CLOCKREGION_X2Y3:CLOCKREGION_X2Y3} 
add_cells_to_pblock {pblock_riscq_core_8} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_8_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_9} 
resize_pblock {pblock_riscq_core_9} -add {CLOCKREGION_X2Y3:CLOCKREGION_X2Y3} 
add_cells_to_pblock {pblock_riscq_core_9} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_9_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_10} 
resize_pblock {pblock_riscq_core_10} -add {CLOCKREGION_X2Y2:CLOCKREGION_X2Y2} 
add_cells_to_pblock {pblock_riscq_core_10} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_10_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_11} 
resize_pblock {pblock_riscq_core_11} -add {CLOCKREGION_X2Y2:CLOCKREGION_X2Y2} 
add_cells_to_pblock {pblock_riscq_core_11} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_11_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_12} 
resize_pblock {pblock_riscq_core_12} -add {CLOCKREGION_X2Y1:CLOCKREGION_X2Y1} 
add_cells_to_pblock {pblock_riscq_core_12} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_12_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_13} 
resize_pblock {pblock_riscq_core_13} -add {CLOCKREGION_X2Y1:CLOCKREGION_X2Y1} 
add_cells_to_pblock {pblock_riscq_core_13} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_13_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_14} 
resize_pblock {pblock_riscq_core_14} -add {CLOCKREGION_X2Y0:CLOCKREGION_X2Y0} 
add_cells_to_pblock {pblock_riscq_core_14} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_14_riscqFiber_riscq/*}]  -clear_locs
create_pblock {pblock_riscq_core_15} 
resize_pblock {pblock_riscq_core_15} -add {CLOCKREGION_X2Y0:CLOCKREGION_X2Y0} 
add_cells_to_pblock {pblock_riscq_core_15} [get_cells -hierarchical -filter {NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_15_riscqFiber_riscq/*}]  -clear_locs