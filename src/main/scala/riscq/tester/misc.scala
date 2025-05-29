package riscq.tester

object ByteHelper {
  def fromBinStr(binaryString: String): Array[Byte] = {
    // Ensure the binary string length is a multiple of 8
    require(binaryString.length % 8 == 0, "Binary string length must be a multiple of 8")

    // Convert binary string to byte array
    (0 until binaryString.length / 8).map { i =>
      // Get each byte (8 bits)
      val byteString = binaryString.slice(i * 8, (i + 1) * 8)
      // Convert to byte
      Integer.parseInt(byteString, 2).toByte
    }.toArray
  }

  def intToBinStr(x: BigInt, b: Int): String = {
    val compx = if (x < 0) (x + (1 << b)) else x
    compx.toString(2).reverse.padTo(b, '0').reverse
  }
}

class RvAssembler(wordWidth: Int) {
  def typeR(opcode: String, funct7: String, funct3: String, rs1: Int, rs2: Int, rd: Int) = {
    assert(opcode.length == 7)
    assert(funct7.length == 7)
    assert(funct3.length == 3)
    var res = funct7
    res += ByteHelper.intToBinStr(rs2, 5)
    res += ByteHelper.intToBinStr(rs1, 5)
    res += funct3
    res += ByteHelper.intToBinStr(rd, 5)
    res += opcode
    assert(res.length == 32)
    res = "0" * (wordWidth - 32) + res
    res
  }

  def add(rd: Int, rs1: Int, rs2: Int) = typeR("0110011", "0000000", "000", rs1, rs2, rd)
  def sub(rd: Int, rs1: Int, rs2: Int) = typeR("0110011", "0100000", "000", rs1, rs2, rd)

  def typeI(opcode: String, funct3: String, imm: Int, rs1: Int, rd: Int) = {
    assert(opcode.length == 7)
    assert(funct3.length == 3)
    var res = ByteHelper.intToBinStr(imm, 12)
    res += ByteHelper.intToBinStr(rs1, 5)
    res += funct3
    res += ByteHelper.intToBinStr(rd, 5)
    res += opcode
    assert(res.length == 32)
    res = "0" * (wordWidth - 32) + res
    res
  }

  def addi(rd: Int, rs1: Int, imm: Int) = typeI("0010011", "000", imm, rs1, rd)
  def nop = addi(0, 0, 0)
  def lw(rd: Int, imm: Int, rs1: Int): String = typeI("0000011", "010", imm = imm, rs1 = rs1, rd = rd)

  def typeB(opcode: String, funct3: String, imm: Int, rs1: Int, rs2: Int) = {
    assert(opcode.length == 7)
    assert(funct3.length == 3)
    val immStr = ByteHelper.intToBinStr(imm, 13).reverse
    var res = immStr.slice(12, 13)
    res += immStr.slice(5,11).reverse
    res += ByteHelper.intToBinStr(rs2, 5)
    res += ByteHelper.intToBinStr(rs1, 5)
    res += funct3
    res += immStr.slice(1, 5).reverse
    res += immStr.slice(11, 12)
    res += opcode
    assert(res.length == 32, s"length is ${res.length}")
    res = "0" * (wordWidth - 32) + res
    res
  }

  def beq(rs1: Int, rs2: Int, imm: Int) = typeB("1100011", "000", imm, rs1, rs2)
  def bne(rs1: Int, rs2: Int, imm: Int) = typeB("1100011", "001", imm, rs1, rs2)
  
  def typeS(opcode: String, funct3: String, imm: Int, rs1: Int, rs2: Int) = {
    assert(opcode.length == 7)
    assert(funct3.length == 3)
    val immStr = ByteHelper.intToBinStr(imm, 12).reverse
    println(s"immstr: ${immStr}")
    var res = immStr.slice(5, 12).reverse
    res += ByteHelper.intToBinStr(rs2, 5)
    res += ByteHelper.intToBinStr(rs1, 5)
    res += funct3
    res += immStr.slice(0, 5).reverse
    res += opcode
    assert(res.length == 32, s"length is ${res.length}")
    res = "0" * (wordWidth - 32) + res
    res
  }
  def sw(rs2: Int, imm: Int, rs1: Int): String = typeS("0100011", "010", imm = imm, rs1 = rs1, rs2 = rs2)

  def typeU(opcode: String, imm: Int, rd: Int): String = {
    assert(opcode.length == 7)
    val immStr = ByteHelper.intToBinStr(imm, 20)
    var res = immStr
    res += ByteHelper.intToBinStr(rd, 5)
    res += opcode
    assert(res.length == 32)
    res = "0" * (wordWidth - 32) + res
    res
  }

  def lui(rd: Int, imm20: Int) : String = typeU("0110111", imm20, rd)
}

class QubicAssembler {
  def pulse(start: Int, addr: Int, duration: Int, phase: Int, freq: Int, amp: Int, id: Int = 0): String = {
    import riscq.soc.QubicSocParams._
    var res = ""
    res += ByteHelper.intToBinStr(id, pulseIdWidth)
    res += ByteHelper.intToBinStr(phase, pulsePhaseWidth)
    res += ByteHelper.intToBinStr(freq, pulseFreqWidth)
    res += ByteHelper.intToBinStr(duration, pulseDurWidth)
    res += ByteHelper.intToBinStr(start, pulseStartWidth)
    res += ByteHelper.intToBinStr(addr, pulseAddrWidth)
    res += ByteHelper.intToBinStr(amp, pulseAmpWidth)
    val opcode = "011000001111111"
    val zeroLength = 128 - res.length() - opcode.length()
    res += "0" * zeroLength + opcode
    res
  }
}