n = 16
res = ''
pblock_map = {
  0: (2, 7),
  1: (2, 7),
  2: (2, 6),
  3: (2, 6),
  4: (2, 5),
  5: (2, 5),
  6: (2, 3),
  7: (2, 3),
  8: (2, 2),
  9: (2, 2),
  10: (2, 1),
  11: (2, 1),
  12: (2, 0),
  13: (2, 0),
  14: (2, 0),
  15: (2, 0),
}
for i in range(n):
  res += f'create_pblock {{pblock_riscq_core_{i}}} \n'
  # res += f'resize_pblock {{pblock_riscq_core_{i}}} -add {{CLOCKREGION_X1Y{7 - i//2}:CLOCKREGION_X1Y{7 - i//2}}} \n'
  res += f'resize_pblock {{pblock_riscq_core_{i}}} -add {{CLOCKREGION_X{pblock_map[i][0]}Y{pblock_map[i][1]}:CLOCKREGION_X{pblock_map[i][0]}Y{pblock_map[i][1]}}} \n'
  res += f'add_cells_to_pblock {{pblock_riscq_core_{i}}} [get_cells -hierarchical -filter {{NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_{i}_riscqFiber_riscq/*}}]  -clear_locs\n'
  res += f'add_cells_to_pblock {{pblock_riscq_core_{i}}} [get_cells -hierarchical -filter {{NAME =~ riscq_bd_i/top/inst/riscqArea_riscqCores_{i}_mem/*}}]  -clear_locs\n'
print(res)
