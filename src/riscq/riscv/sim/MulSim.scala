package riscq.riscv.sim

import spinal.core.sim._
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.regfile.RegFilePlugin

import scala.util.Random

/**
 * Zmmul multiply testbench — a self-checking **lock-step against a Scala golden model**.
 *
 * A directed program multiplies many operand pairs (random + the signed/unsigned corner cases:
 * 0, ±1, INT_MIN, INT_MAX, all-ones) with all four ops (MUL/MULH/MULHSU/MULHU), then exercises the
 * forwarding the multi-cycle (halting) multiplier must survive: an ALU op feeding a MUL, a MUL
 * feeding a MUL, and a MUL feeding an ALU op (the producer-interlock + 2-deep forward paths).
 *
 * Each cycle the execute stage commits a real instruction, we check the committed PC/word and the
 * register-file write (enable/addr/data) against the golden model, then step it. At ECALL we stop
 * and compare the whole register file.
 *
 * Run with `mill runMain riscq.riscv.sim.MulSim`.
 */
object MulSim extends App {
  val param = RiscqParam(memWords = 4096, withMul = true)
  val base  = param.resetVector.toLong

  // ---- Instruction encoders ----
  def r(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(0x33)
  def i(imm: Int, rs1: Int, funct3: Int, rd: Int, op: Int): BigInt =
    ((BigInt(imm) & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(op)
  def u(imm: Int, rd: Int): BigInt = ((BigInt(imm) & 0xfffff) << 12) | (BigInt(rd) << 7) | BigInt(0x37)

  def ADDI(rd: Int, rs1: Int, imm: Int) = i(imm, rs1, 0x0, rd, 0x13)
  def LUI(rd: Int, imm: Int)            = u(imm, rd)
  def ADD(rd: Int, rs1: Int, rs2: Int)  = r(0x00, rs2, rs1, 0x0, rd)
  def MUL(rd: Int, rs1: Int, rs2: Int)    = r(0x01, rs2, rs1, 0x0, rd)
  def MULH(rd: Int, rs1: Int, rs2: Int)   = r(0x01, rs2, rs1, 0x1, rd)
  def MULHSU(rd: Int, rs1: Int, rs2: Int) = r(0x01, rs2, rs1, 0x2, rd)
  def MULHU(rd: Int, rs1: Int, rs2: Int)  = r(0x01, rs2, rs1, 0x3, rd)
  val ECALL = BigInt(0x00000073L)

  // Load an arbitrary 32-bit constant into rd with LUI + ADDI (sign-correcting the low 12 bits).
  def li(rd: Int, value: Long): Seq[BigInt] = {
    val lo  = (value & 0xfff).toInt
    val loS = if (lo >= 0x800) lo - 0x1000 else lo // sign-extended value the ADDI will add
    val hi  = (((value - loS) >> 12) & 0xfffff).toInt
    Seq(LUI(rd, hi), ADDI(rd, rd, loS))
  }

  // ---- The program ----
  val rng   = new Random(1)
  val corner = Seq(0L, 1L, -1L & 0xffffffffL, 0x7fffffffL, 0x80000000L, 0xffffffffL, 2L, 0x12345678L)
  val pairs = (for (a <- corner; b <- corner) yield (a, b)) ++
    Seq.fill(48)((rng.nextInt() & 0xffffffffL, rng.nextInt() & 0xffffffffL))

  val prog = scala.collection.mutable.ArrayBuffer[BigInt]()
  for ((a, b) <- pairs) {
    prog ++= li(1, a)
    prog ++= li(2, b)
    prog += MUL(3, 1, 2)
    prog += MULH(4, 1, 2)
    prog += MULHSU(5, 1, 2)
    prog += MULHU(6, 1, 2)
  }
  // Forwarding stress: ALU→MUL, MUL→MUL (back-to-back dependent), MUL→ALU.
  for ((a, b) <- Seq((0x0003abcdL, 0x7fffffffL), (0xfffffff0L, 0x00000007L), (0x12340000L, 0x0000aaaaL))) {
    prog ++= li(1, a)
    prog ++= li(2, b)
    prog += ADD(7, 1, 2)     // x7 = a+b (ALU)
    prog += MUL(8, 7, 2)     // ALU result feeds MUL (interlock + srcB forward)
    prog += MULH(9, 8, 1)    // MUL result feeds MUL
    prog += ADD(10, 9, 8)    // MUL result feeds ALU
  }
  prog += ECALL

  val program: Seq[BigInt] = prog.toSeq
  val image: Array[BigInt] = Array.tabulate(param.memWords)(idx =>
    if (idx < program.length) program(idx) else ADDI(0, 0, 0) // pad with NOP
  )

  // ---- Scala golden model (the authority) ----
  val mask32 = 0xffffffffL
  val mask64 = (BigInt(1) << 64) - 1
  def u32(x: BigInt): BigInt = x & mask32
  def s32(x: BigInt): BigInt = { val m = x & mask32; if ((m >> 31) != 0) m - (BigInt(1) << 32) else m }
  def sext(v: BigInt, width: Int): BigInt = {
    val s = BigInt(1) << (width - 1); if ((v & s) != 0) v - (BigInt(1) << width) else v
  }
  def mulOp(f3: Int, a: BigInt, b: BigInt): BigInt = {
    val sa   = if (f3 != 3) s32(a) else u32(a) // signed except MULHU
    val sb   = if (f3 < 2) s32(b) else u32(b)  // signed for MUL/MULH only
    val full = (sa * sb) & mask64
    if (f3 == 0) full & mask32 else (full >> 32) & mask32
  }

  case class Step(pc: Long, instr: BigInt, wEn: Boolean, wAddr: Int, wData: BigInt, isEcall: Boolean)

  class Golden {
    val regs = Array.fill(32)(BigInt(0))
    var pc: Long = base
    def step(): Step = {
      val idx    = ((pc - base) / 4).toInt
      val w      = image(idx)
      val opcode = (w & 0x7f).toInt
      val rd     = ((w >> 7) & 0x1f).toInt
      val funct3 = ((w >> 12) & 0x7).toInt
      val funct7 = ((w >> 25) & 0x7f).toInt
      val a      = regs(((w >> 15) & 0x1f).toInt)
      val bb     = regs(((w >> 20) & 0x1f).toInt)
      val immI   = sext((w >> 20) & 0xfff, 12)
      val immU   = sext(((w >> 12) & 0xfffff) << 12, 32)

      var wEn = false; var wData = BigInt(0); var isEcall = false
      opcode match {
        case 0x37 => wEn = true; wData = u32(immU)                 // LUI
        case 0x13 => wEn = true; wData = u32(s32(a) + immI)        // ADDI (only funct3 0 used)
        case 0x33 =>                                              // OP
          wEn = true
          wData = if (funct7 == 0x01) mulOp(funct3, a, bb)         // M multiply
                  else u32(s32(a) + s32(bb))                       // ADD (only one used)
        case 0x73 => isEcall = true
        case _    => sys.error(f"golden: unhandled opcode 0x$opcode%x at idx $idx")
      }
      val effEn = wEn && rd != 0
      if (effEn) regs(rd) = wData
      val cur = pc; pc = pc + 4
      Step(cur, w, effEn, rd, wData, isEcall)
    }
  }

  SimConfig.withFstWave.compile(Riscq(param.plugins())).doSim(seed = 42) { dut =>
    val rf  = dut.host[RegFilePlugin].logic
    val dbg = rf.exec
    dut.clockDomain.forkStimulus(10)
    SimMem(dut, base, image)

    val golden = new Golden
    var commits = 0; var done = false
    val maxCycles = 20000
    var cycle = 0
    while (!done && cycle < maxCycles) {
      dut.clockDomain.waitSampling(); cycle += 1
      if (dbg.dbgFiring.toBoolean) {
        val dutPc = dbg.dbgPc.toBigInt; val dutInstr = dbg.dbgInstr.toBigInt
        val s = golden.step()
        assert(dutPc == BigInt(s.pc),
          f"PC mismatch at commit $commits: dut 0x${dutPc.toString(16)} != golden 0x${s.pc.toHexString}")
        assert(dutInstr == s.instr,
          f"instr mismatch at pc 0x${s.pc.toHexString}: dut 0x${dutInstr.toString(16)} != 0x${s.instr.toString(16)}")
        assert(dbg.dbgWrite.toBoolean == s.wEn,
          f"rd-write-enable mismatch at pc 0x${s.pc.toHexString} (0x${s.instr.toString(16)}): " +
            s"dut ${dbg.dbgWrite.toBoolean} != golden ${s.wEn}")
        if (s.wEn) {
          assert(dbg.dbgRd.toBigInt == s.wAddr,
            f"rd-addr mismatch at pc 0x${s.pc.toHexString}: dut ${dbg.dbgRd.toBigInt} != ${s.wAddr}")
          assert(dbg.dbgRdData.toBigInt == s.wData,
            f"rd-data mismatch at pc 0x${s.pc.toHexString} (x${s.wAddr}, instr 0x${s.instr.toString(16)}): " +
              f"dut 0x${dbg.dbgRdData.toBigInt.toString(16)} != golden 0x${s.wData.toString(16)}")
        }
        commits += 1
        if (s.isEcall) done = true
      }
    }
    assert(done, s"never retired the ECALL (only $commits commits in $maxCycles cycles)")
    dut.clockDomain.waitSampling(4)
    for (ri <- 1 until 32) {
      val got = rf.regs.getBigInt(ri)
      assert(got == golden.regs(ri),
        f"final x$ri mismatch: dut 0x${got.toString(16)} != golden 0x${golden.regs(ri).toString(16)}")
    }
    println(s"[MulSim] PASS  $commits instructions retired in lock-step over $cycle cycles " +
      s"(${pairs.size} operand pairs × MUL/MULH/MULHSU/MULHU + ALU/MUL forwarding); register file matches golden.")
    simSuccess()
  }
}
