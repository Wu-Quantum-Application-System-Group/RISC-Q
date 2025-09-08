from riscq.client import RiscQClient
import math

class MultiCoreClient(RiscQClient):
  def __init__(self, host, port=5000):
    super().__init__(host, port)
    pulse_mem_width = 256
    pulse_mem_depth = 1024
    self.pulse_mem_offset = 1 << 25
    self.node_pulse_mem_offset = 1 << 18
    self.single_pulse_mem_size = pulse_mem_width * pulse_mem_depth / 8

    self.cpu_mem_offset = 0
    self.node_mem_size = 1 << 16
    
  def pulse_mem_addr(self, node_idx, pulse_mem_idx, addr):
    return self.pulse_mem_offset \
    + self.node_pulse_mem_offset * node_idx \
    + self.single_pulse_mem_size * pulse_mem_idx \
    + addr

  def cpu_mem_addr(self, node_idx, addr):
    return self.cpu_mem_offset + self.node_mem_size * node_idx + addr

  def load_elf(self, filename, node_idx, offset):
    from elftools.elf.elffile import ELFFile
    from elftools.elf.constants import SH_FLAGS

    with open(filename, 'rb') as f:
      elf = ELFFile(f)
      for section in elf.iter_sections():
        if((section['sh_flags'] & SH_FLAGS.SHF_ALLOC)):
          addr = section['sh_addr']
          data = section.data()
          for i in range(0, len(data), 4):
            word = int.from_bytes(data[i:i+4], 'little')
            self.write_word(self.cpu_mem_addr(node_idx, addr + i + offset), word)

  def load_pulse_mem(self, node_idx, pulse_mem_idx, offset, env):
    cur_addr = offset
    if len(env) % 2 == 1:
      env.append(0)
    grouped_env = [env[i:i+2] for i in range(0, len(env), 2)]
    grouped_env_array = [(int(d2 * (2 ** 15)) << 16) + int(d1 * (2 ** 15)) for [d1, d2] in grouped_env]
    self.write_word_array(self.pulse_mem_addr(node_idx, pulse_mem_idx, cur_addr), grouped_env_array)
    
    # grouped_env = [env[i:i+2] for i in range(0, len(env), 2)]
    # for [d1, d2] in grouped_env:
    #   d1_int = int(d1 * (2 ** 15))
    #   d2_int = int(d2 * (2 ** 15))
    #   d1_bytes = d1_int.to_bytes(2, 'little', signed = True)
    #   d2_bytes = d2_int.to_bytes(2, 'little', signed = True)
    #   self.write_word(self.pulse_mem_addr(node_idx, pulse_mem_idx, cur_addr), d1_bytes+d2_bytes)
    #   cur_addr += 4