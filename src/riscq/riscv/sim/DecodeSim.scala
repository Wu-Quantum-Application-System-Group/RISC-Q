package riscq.riscv.sim

import spinal.core._
import spinal.core.sim._
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.decode.{DecoderPlugin, Rv32i}

import scala.collection.mutable

/**
 * Decode testbench. Preloads the instruction memory with a program that exercises every
 * RV32I immediate format (plus a NOP and an illegal word), then — each cycle — reads the
 * word present at the decode stage and checks the decoded fields against a Scala golden
 * model derived from the same word. Because the golden model is a pure function of the
 * observed word, every cycle is self-consistent regardless of pipeline fill / reset.
 *
 * Run with `mill runMain riscq.riscv.sim.DecodeSim`.
 */
object DecodeSim extends App {
  // Decode-only pipeline: the immediate is whiteboxed at the decode stage, so test the in-place build
  // (deriveImmFromWord off). The flag-on path rebuilds the same immediate one stage later (regReadAt) —
  // there is no consumer stage in a decode-only pipeline — and is covered end-to-end by RvTestSim (RVLS).
  val param = RiscqParam(memWords = 256, deriveImmFromWord = false)
  val xlen  = param.xlen

  // ---- Instruction encoders (build a 32-bit word from fields) ----
  def r(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, op: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(op)
  def i(imm: Int, rs1: Int, funct3: Int, rd: Int, op: Int): BigInt =
    ((BigInt(imm) & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(op)
  def s(imm: Int, rs2: Int, rs1: Int, funct3: Int, op: Int): BigInt = {
    val im = BigInt(imm) & 0xfff
    ((im >> 5) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | ((im & 0x1f) << 7) | BigInt(op)
  }
  def b(imm: Int, rs2: Int, rs1: Int, funct3: Int, op: Int): BigInt = {
    val im = BigInt(imm) & 0x1fff // 13-bit, bit0 implicitly 0
    (((im >> 12) & 1) << 31) | (((im >> 5) & 0x3f) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(funct3) << 12) | (((im >> 1) & 0xf) << 8) |
      (((im >> 11) & 1) << 7) | BigInt(op)
  }
  def u(imm: Int, rd: Int, op: Int): BigInt =
    ((BigInt(imm) & 0xfffff) << 12) | (BigInt(rd) << 7) | BigInt(op)
  def j(imm: Int, rd: Int, op: Int): BigInt = {
    val im = BigInt(imm) & 0x1fffff // 21-bit, bit0 implicitly 0
    (((im >> 20) & 1) << 31) | (((im >> 1) & 0x3ff) << 21) | (((im >> 11) & 1) << 20) |
      (((im >> 12) & 0xff) << 12) | (BigInt(rd) << 7) | BigInt(op)
  }

  val nop = BigInt(0x00000013L) // ADDI x0, x0, 0
  val program: Seq[BigInt] = Seq(
    r(0x00, 2, 1, 0x0, 3, 0x33), // ADD   x3, x1, x2     (R / NONE)
    r(0x20, 5, 4, 0x0, 6, 0x33), // SUB   x6, x4, x5     (R / NONE)
    i(-1, 1, 0x0, 7, 0x13),      // ADDI  x7, x1, -1      (I)
    i(20, 8, 0x2, 9, 0x03),      // LW    x9, 20(x8)      (I)
    s(-8, 11, 10, 0x2, 0x23),    // SW    x11, -8(x10)    (S)
    b(-4, 13, 12, 0x0, 0x63),    // BEQ   x12, x13, -4    (B)
    j(2048, 1, 0x6f),            // JAL   x1, +2048       (J)
    i(16, 5, 0x0, 1, 0x67),      // JALR  x1, 16(x5)      (I)
    u(0x12345, 14, 0x37),        // LUI   x14, 0x12345    (U)
    u(0x80000, 15, 0x17),        // AUIPC x15, 0x80000    (U)
    nop,                         // ADDI  x0, x0, 0       (I / NONE-ish)
    BigInt(0x00000073L),         // ECALL                 (NONE)
    BigInt(0xffffffffL)          // illegal
  )

  // ---- Scala golden decoder (pure function of the 32-bit word) ----
  def field(w: BigInt, hi: Int, lo: Int): BigInt = (w >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)
  def sext(v: BigInt, width: Int): BigInt = {
    val signBit = BigInt(1) << (width - 1)
    if ((v & signBit) != 0) v - (BigInt(1) << width) else v
  }
  val mask = (BigInt(1) << xlen) - 1

  def golden(w: BigInt) = {
    val matched = Rv32i.instructions.find { case (ml, _) => (w & ml.careAbout) == ml.value }
    val legal   = matched.isDefined
    val immI = sext(field(w, 31, 20), 12)
    val immS = sext((field(w, 31, 25) << 5) | field(w, 11, 7), 12)
    val immB = sext((field(w, 31, 31) << 12) | (field(w, 7, 7) << 11) |
      (field(w, 30, 25) << 5) | (field(w, 11, 8) << 1), 13)
    val immU = sext(field(w, 31, 12) << 12, 32)
    val immJ = sext((field(w, 31, 31) << 20) | (field(w, 19, 12) << 12) |
      (field(w, 20, 20) << 11) | (field(w, 30, 21) << 1), 21)
    val imm = matched.map(_._2) match {
      case Some(Rv32i.ImmType.I) => immI
      case Some(Rv32i.ImmType.S) => immS
      case Some(Rv32i.ImmType.B) => immB
      case Some(Rv32i.ImmType.U) => immU
      case Some(Rv32i.ImmType.J) => immJ
      case _                     => BigInt(0)
    }
    (legal, field(w, 19, 15), field(w, 24, 20), field(w, 11, 7),
      field(w, 14, 12), field(w, 31, 25), imm & mask)
  }

  val base  = param.resetVector.toLong
  val image = Array.tabulate(param.memWords)(idx => if (idx < program.length) program(idx) else nop)

  SimConfig.withFstWave.compile(Riscq(param.decodePlugins())).doSim(seed = 42) { dut =>
    val d = dut.host[DecoderPlugin].logic.decoder

    dut.clockDomain.forkStimulus(10)
    SimMem(dut, base, image) // instructions now arrive over Tilelink (variable latency)

    // The decode check is a pure function of the word present at the decode stage, so it holds
    // every cycle regardless of pipeline fill / stalls; we just need enough cycles (fetch now
    // stalls while a Get is outstanding) to see every program instruction reach decode.
    val seen      = mutable.Set[BigInt]()
    val maxCycles = 2000
    var cycle     = 0
    while (!program.forall(seen.contains) && cycle < maxCycles) {
      dut.clockDomain.waitSampling()
      cycle += 1
      val w  = d.instr.toBigInt
      val hx = w.toString(16)
      val (legal, rs1, rs2, rd, funct3, funct7, imm) = golden(w)
      assert(d.rs1.toBigInt == rs1, s"rs1 mismatch for 0x$hx: ${d.rs1.toBigInt} != $rs1")
      assert(d.rs2.toBigInt == rs2, s"rs2 mismatch for 0x$hx: ${d.rs2.toBigInt} != $rs2")
      assert(d.rd.toBigInt == rd, s"rd mismatch for 0x$hx: ${d.rd.toBigInt} != $rd")
      assert(d.funct3.toBigInt == funct3, s"funct3 mismatch for 0x$hx")
      assert(d.funct7.toBigInt == funct7, s"funct7 mismatch for 0x$hx")
      assert(d.legal.toBoolean == legal, s"legal mismatch for 0x$hx: ${d.legal.toBoolean} != $legal")
      if (legal)
        assert(d.imm.toBigInt == imm, s"imm mismatch for 0x$hx: 0x${d.imm.toBigInt.toString(16)} != 0x${imm.toString(16)}")
      seen += w
    }

    // Coverage: confirm every program instruction actually reached the decode stage.
    for (w <- program)
      assert(seen.contains(w), s"never observed instruction 0x${w.toString(16)} at decode")

    println(
      s"[DecodeSim] PASS  checked ${seen.size} distinct words over $cycle cycles " +
        s"(${program.length} program instructions covering R/I/S/B/U/J + illegal)"
    )
    simSuccess()
  }
}
