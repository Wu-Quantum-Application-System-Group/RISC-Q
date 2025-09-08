from riscq.client import RiscQClient

def main():
    client = RiscQClient('localhost')
    response = client.set_ref_clks(100)
    print(response)
    # client.write_bitstream('bitstream.bit')
    response = client.write_word(0x0, 0x1)
    print(response)
    response = client.write_word_array(0x123, [0x1, 0x2, 0x3, 0x4])
    print(response)
    data = client.read_word(0x0)
    print(data)

if __name__ == "__main__":
    main()
