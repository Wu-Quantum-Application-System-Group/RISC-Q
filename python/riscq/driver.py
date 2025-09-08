import numpy as np

class DummyDriver:
  def __init__(self):
    pass
  
  def set_ref_clks(self, lmk_freq):
    print(f"Setting reference clocks to {lmk_freq} MHz")
  
  def write_bitstream(self, file_path):
    print(f"Writing bitstream from {file_path}")
  
  def write_word(self, addr, data):
    print(f"Writing word to {addr} with data {data}")

  def write_word_array(self, addr, array):
    print(f"addr: {addr}, array: {array}")
  
  def read_word(self, addr):
    print(f"Reading word from {addr}")
    return 123
  

class PynqZCU216Driver:
  def __init__(self):
    from pynq import MMIO
    mmio_base_addr = 0x80000000
    mmio_size = 0x20000000
    self.mmio = MMIO(mmio_base_addr, mmio_size)

  def set_ref_clks(self, lmk_freq):
    import xrfclk
    xrfclk.set_ref_clks(lmk_freq = lmk_freq)

  def write_bitstream(self, filename):
    from pynq import Overlay
    self.overlay = Overlay(filename)

  def write_word(self, addr, data):
    self.mmio.write(addr, data)

  def write_word_array(self, addr, array):
    cur_addr = addr
    for d in array:
      self.mmio.write(cur_addr, d)
      cur_addr += 4

  def read_word(self, addr):
    return self.mmio.read(addr, 4)
