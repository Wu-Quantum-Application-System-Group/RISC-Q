import requests
import io
import pickle


class RiscQClient:
  def __init__(self, host, port=5000):
    self.host = host
    self.port = port

  def set_ref_clks(self, lmk_freq):
    response = requests.post(f'http://{self.host}:{self.port}/set-ref-clks', json={'lmk_freq': lmk_freq})
    return response.json()
  
  def upload_file(self, filename):
    response = requests.post(f'http://{self.host}:{self.port}/upload-file', files={'file': open(filename, 'rb')})
    return response.json()
  
  def write_bitstream(self, filename):
    response = requests.post(f'http://{self.host}:{self.port}/write-bitstream', json={'filename': filename})
    return response.json()
  
  def write_word(self, addr, data):
    response = requests.post(f'http://{self.host}:{self.port}/write-word', json={'addr': addr, 'data': data})
    return response.text

  def write_word_array(self, addr, array):
    buf = pickle.dumps(array)

    response = requests.post(f'http://{self.host}:{self.port}/write-word-array', files={'array': ('data.npy', buf)},
                         data={'addr': addr})
    return response.text
  
  def read_word(self, addr):
    response = requests.get(f'http://{self.host}:{self.port}/read-word', json={'addr': addr})
    return response.json()['data']
  