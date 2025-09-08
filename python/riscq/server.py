from flask import Flask, request, jsonify
import numpy as np
from .driver import PynqZCU216Driver
import pickle

driver = PynqZCU216Driver()

app = Flask(__name__)

@app.route('/upload-file', methods=['POST'])
def upload_file():
  file = request.files['file']
  file.save(file.filename)
  return jsonify({'message': f'File uploaded successfully from {file.filename}'}), 200

@app.route('/write-bitstream', methods=['POST'])
def write_bitstream():
  data = request.json
  driver.write_bitstream(data['filename'])
  return jsonify({'message': f"Bitstream written successfully from {data['filename']}"}), 200

@app.route('/write-word', methods=['POST'])
def write_word():
  data = request.json
  driver.write_word(data['addr'], data['data'])
  return f"{data['addr']} <- {data['data']}", 200
  # return jsonify({'message': 'Word written successfully'}), 200

@app.route('/write-word-array', methods=['POST'])
def write_word_array():
  addr = int(request.form['addr'])
  array = pickle.loads(request.files['array'].read())

  driver.write_word_array(addr, array)
  return jsonify({'message': f'Write {len(array)} word to {addr}' }), 200


@app.route('/set-ref-clks', methods=['POST'])
def set_ref_clks():
  data = request.json
  driver.set_ref_clks(data['lmk_freq'])
  return jsonify({'message': f'Reference clocks set successfully to {data["lmk_freq"]} MHz'}), 200

@app.route('/read-word', methods=['GET'])
def read_word():
  data = request.json
  data = driver.read_word(data['addr'])
  return jsonify({'data': data}), 200


if __name__ == '__main__':
  app.run(host='0.0.0.0', port=5000, debug=True)
