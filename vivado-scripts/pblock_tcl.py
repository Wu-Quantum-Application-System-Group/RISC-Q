n = 16
res = ''
for i in range(n):
  res += f'create_pblock {{pblock_riscq_core_{i}}} \n'
  # res += f'resize_pblock {{pblock_riscq_core_{i}}} -add {{CLOCKREGION_X1Y{7 - i//2}:CLOCKREGION_X1Y{7 - i//2}}} \n'
  res += f'resize_pblock {{pblock_riscq_core_{i}}} -add {{CLOCKREGION_X2Y{7 - i//2}:CLOCKREGION_X2Y{7 - i//2}}} \n'
  res += f'add_cells_to_pblock {{pblock_riscq_core_{i}}} [get_cells -hierarchical -filter {{NAME =~ riscq_bd_i/riscq/inst/riscqArea_riscqCores_{i}_riscqFiber_riscq/*}}]  -clear_locs\n'
print(res)
