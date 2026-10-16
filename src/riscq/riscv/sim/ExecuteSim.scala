package riscq.riscv.sim

import spinal.core._
import spinal.core.sim._
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.regfile.RegFilePlugin

import scala.collection.mutable.ArrayBuffer

/**
 * Execute testbench — a self-checking **lock-step against a Scala golden RV32I model**.
 *
 * A directed program exercises every ALU op (ADD/SUB, the logicals, SLT(I)(U), the shifts,
 * LUI/AUIPC), all six branches taken and not-taken (including a backward-branch loop), and
 * JAL/JALR — plus several back-to-back dependent pairs (e.g. AUIPC→JALR, ADDI→BNE) that only
 * pass if the no-bypass single-stage register file forwards correctly across the clock edge.
 *
 * Each cycle the execute stage commits a real instruction, we check the committed PC, the
 * committed word, and the register-file write (enable/addr/data) against the golden model,
 * then step the model. Flushed wrong-path instructions never commit, so the model and the
 * core stay in program order. At ECALL we stop and compare the whole register file.
 *
 * Run with `mill runMain riscq.riscv.sim.ExecuteSim`.
 */
object ExecuteSim extends App {
  val param = RiscqParam(memWords = 256)
  val base  = param.resetVector.toLong

  // ---- Instruction encoders (build a 32-bit word from fields) ----
  def r(funct7: Int, rs2: Int, rs1: Int, funct3: Int, rd: Int, op: Int): BigInt =
    (BigInt(funct7) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | (BigInt(rd) << 7) | BigInt(op)
  def i(imm: Int, rs1: Int, funct3: Int, rd: Int, op: Int): BigInt =
    ((BigInt(imm) & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(funct3) << 12) |
      (BigInt(rd) << 7) | BigInt(op)
  def b(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    val im = BigInt(imm) & 0x1fff // 13-bit, bit0 implicitly 0
    (((im >> 12) & 1) << 31) | (((im >> 5) & 0x3f) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(funct3) << 12) | (((im >> 1) & 0xf) << 8) |
      (((im >> 11) & 1) << 7) | BigInt(0x63)
  }
  def s(imm: Int, rs2: Int, rs1: Int, funct3: Int): BigInt = {
    val im = BigInt(imm) & 0xfff
    ((im >> 5) << 25) | (BigInt(rs2) << 20) | (BigInt(rs1) << 15) |
      (BigInt(funct3) << 12) | ((im & 0x1f) << 7) | BigInt(0x23)
  }
  def u(imm: Int, rd: Int, op: Int): BigInt =
    ((BigInt(imm) & 0xfffff) << 12) | (BigInt(rd) << 7) | BigInt(op)
  def j(imm: Int, rd: Int): BigInt = {
    val im = BigInt(imm) & 0x1fffff // 21-bit, bit0 implicitly 0
    (((im >> 20) & 1) << 31) | (((im >> 1) & 0x3ff) << 21) | (((im >> 11) & 1) << 20) |
      (((im >> 12) & 0xff) << 12) | (BigInt(rd) << 7) | BigInt(0x6f)
  }

  // Convenience wrappers for the specific instructions used below.
  def ADDI(rd: Int, rs1: Int, imm: Int)  = i(imm, rs1, 0x0, rd, 0x13)
  def XORI(rd: Int, rs1: Int, imm: Int)  = i(imm, rs1, 0x4, rd, 0x13)
  def ORI(rd: Int, rs1: Int, imm: Int)   = i(imm, rs1, 0x6, rd, 0x13)
  def ANDI(rd: Int, rs1: Int, imm: Int)  = i(imm, rs1, 0x7, rd, 0x13)
  def SLLI(rd: Int, rs1: Int, sh: Int)   = i(sh, rs1, 0x1, rd, 0x13)
  def SRLI(rd: Int, rs1: Int, sh: Int)   = i(sh, rs1, 0x5, rd, 0x13)
  def SRAI(rd: Int, rs1: Int, sh: Int)   = i(0x400 | sh, rs1, 0x5, rd, 0x13)
  def ADD(rd: Int, rs1: Int, rs2: Int)   = r(0x00, rs2, rs1, 0x0, rd, 0x33)
  def SUB(rd: Int, rs1: Int, rs2: Int)   = r(0x20, rs2, rs1, 0x0, rd, 0x33)
  def AND(rd: Int, rs1: Int, rs2: Int)   = r(0x00, rs2, rs1, 0x7, rd, 0x33)
  def OR(rd: Int, rs1: Int, rs2: Int)    = r(0x00, rs2, rs1, 0x6, rd, 0x33)
  def XOR(rd: Int, rs1: Int, rs2: Int)   = r(0x00, rs2, rs1, 0x4, rd, 0x33)
  def SLT(rd: Int, rs1: Int, rs2: Int)   = r(0x00, rs2, rs1, 0x2, rd, 0x33)
  def SLTU(rd: Int, rs1: Int, rs2: Int)  = r(0x00, rs2, rs1, 0x3, rd, 0x33)
  def LUI(rd: Int, imm: Int)             = u(imm, rd, 0x37)
  def AUIPC(rd: Int, imm: Int)           = u(imm, rd, 0x17)
  def BEQ(rs1: Int, rs2: Int, off: Int)  = b(off, rs2, rs1, 0x0)
  def BNE(rs1: Int, rs2: Int, off: Int)  = b(off, rs2, rs1, 0x1)
  def BLT(rs1: Int, rs2: Int, off: Int)  = b(off, rs2, rs1, 0x4)
  def BGE(rs1: Int, rs2: Int, off: Int)  = b(off, rs2, rs1, 0x5)
  def BLTU(rs1: Int, rs2: Int, off: Int) = b(off, rs2, rs1, 0x6)
  def BGEU(rs1: Int, rs2: Int, off: Int) = b(off, rs2, rs1, 0x7)
  def JAL(rd: Int, off: Int)             = j(off, rd)
  def JALR(rd: Int, rs1: Int, imm: Int)  = i(imm, rs1, 0x0, rd, 0x67)
  def SW(rs2: Int, rs1: Int, off: Int)   = s(off, rs2, rs1, 0x2)
  def SH(rs2: Int, rs1: Int, off: Int)   = s(off, rs2, rs1, 0x1)
  def SB(rs2: Int, rs1: Int, off: Int)   = s(off, rs2, rs1, 0x0)
  def LW(rd: Int, rs1: Int, off: Int)    = i(off, rs1, 0x2, rd, 0x03)
  def LH(rd: Int, rs1: Int, off: Int)    = i(off, rs1, 0x1, rd, 0x03)
  def LB(rd: Int, rs1: Int, off: Int)    = i(off, rs1, 0x0, rd, 0x03)
  def LHU(rd: Int, rs1: Int, off: Int)   = i(off, rs1, 0x5, rd, 0x03)
  def LBU(rd: Int, rs1: Int, off: Int)   = i(off, rs1, 0x4, rd, 0x03)
  val ECALL                              = BigInt(0x00000073L)
  val POISON                             = ADDI(30, 0, 0xBAD) // must never be reached (flushed)

  // ---- The program (index n lives at base + 4n; branch/jump offsets are in bytes) ----
  val program: Seq[BigInt] = Seq(
    /* 0*/ ADDI(1, 0, 5),       // x1 = 5
    /* 1*/ ADDI(2, 0, 3),       // x2 = 3
    /* 2*/ ADD(3, 1, 2),        // x3 = 8
    /* 3*/ SUB(4, 1, 2),        // x4 = 2
    /* 4*/ AND(5, 1, 2),        // x5 = 1
    /* 5*/ OR(6, 1, 2),         // x6 = 7
    /* 6*/ XOR(7, 1, 2),        // x7 = 6
    /* 7*/ SLT(8, 2, 1),        // x8 = 1  (3 < 5)
    /* 8*/ SLT(9, 1, 2),        // x9 = 0
    /* 9*/ SLTU(10, 1, 2),      // x10 = 0
    /*10*/ SLLI(11, 1, 3),      // x11 = 40
    /*11*/ SRLI(12, 11, 2),     // x12 = 10   (depends on x11 written previous cycle)
    /*12*/ ADDI(13, 0, -1),     // x13 = 0xFFFFFFFF
    /*13*/ SRAI(14, 13, 1),     // x14 = 0xFFFFFFFF (arithmetic)
    /*14*/ SRLI(15, 13, 28),    // x15 = 0xF
    /*15*/ SLTU(16, 0, 13),     // x16 = 1  (0 < 0xFFFFFFFF unsigned)
    /*16*/ SLT(17, 13, 0),      // x17 = 1  (-1 < 0 signed)
    /*17*/ XORI(18, 1, -1),     // x18 = ~5 = 0xFFFFFFFA
    /*18*/ ANDI(19, 13, 0xF),   // x19 = 0xF
    /*19*/ ORI(20, 0, 0x7F),    // x20 = 0x7F
    /*20*/ LUI(21, 0x12345),    // x21 = 0x12345000
    /*21*/ AUIPC(22, 0x1),      // x22 = (base+84) + 0x1000
    /*22*/ BEQ(1, 2, 8),        // not taken (5 != 3) -> fall through
    /*23*/ BNE(1, 2, 8),        // taken -> idx 25 (skip poison)
    /*24*/ POISON,
    /*25*/ ADDI(23, 0, 1),      // x23 = 1
    /*26*/ ADDI(24, 0, 3),      // loop counter = 3
    /*27*/ ADDI(25, 0, 0),      // accumulator = 0
    /*28*/ ADDI(25, 25, 1),     // loop body: acc++
    /*29*/ ADDI(24, 24, -1),    // counter--
    /*30*/ BNE(24, 0, -8),      // back to idx 28 while counter != 0  (x25 ends at 3)
    /*31*/ BLT(2, 1, 8),        // taken (3 < 5) -> idx 33
    /*32*/ POISON,
    /*33*/ ADDI(26, 0, 1),      // x26 = 1
    /*34*/ BGE(1, 2, 8),        // taken (5 >= 3) -> idx 36
    /*35*/ POISON,
    /*36*/ ADDI(27, 0, 1),      // x27 = 1
    /*37*/ BLTU(0, 13, 8),      // taken (0 < 0xFFFFFFFF unsigned) -> idx 39
    /*38*/ POISON,
    /*39*/ ADDI(28, 0, 1),      // x28 = 1
    /*40*/ BGEU(13, 1, 8),      // taken (0xFFFFFFFF >= 5 unsigned) -> idx 42
    /*41*/ POISON,
    /*42*/ ADDI(29, 0, 1),      // x29 = 1
    /*43*/ JAL(31, 8),          // link x31 = base+176; jump -> idx 45
    /*44*/ POISON,
    /*45*/ ADDI(5, 0, 42),      // x5 = 42  (clobbers x5; golden tracks it)
    /*46*/ AUIPC(6, 0),         // x6 = base + 184
    /*47*/ JALR(7, 6, 12),      // target = x6+12 = base+196 -> idx 49 (depends on x6)
    /*48*/ POISON,
    /*49*/ ADDI(8, 0, 99),      // x8 = 99  (clobbers x8; golden tracks it)
    // ---- Cacheless LSU: stores then loads over Tilelink, exercising every width/lane ----
    /*50*/ LUI(5, 0x80000),     // x5 = 0x80000000
    /*51*/ ADDI(5, 5, 0x400),   // x5 = 0x80000400  (data base, just past the loaded image)
    /*52*/ SW(1, 5, 0),         // mem[+0]  = x1 (=5)
    /*53*/ LW(6, 5, 0),         // x6 = 5
    /*54*/ SB(2, 5, 4),         // mem[+4]  = byte x2 (=3)
    /*55*/ LBU(7, 5, 4),        // x7 = 3
    /*56*/ SH(3, 5, 8),         // mem[+8]  = half x3 (=8)
    /*57*/ LHU(9, 5, 8),        // x9 = 8
    /*58*/ ADDI(10, 0, -1),     // x10 = 0xFFFFFFFF
    /*59*/ SB(10, 5, 12),       // mem[+12] = byte 0xFF
    /*60*/ LB(11, 5, 12),       // x11 = sign-extended 0xFF = 0xFFFFFFFF
    /*61*/ LBU(12, 5, 12),      // x12 = 0x000000FF
    /*62*/ SH(10, 5, 16),       // mem[+16] = half 0xFFFF
    /*63*/ LH(13, 5, 16),       // x13 = sign-extended = 0xFFFFFFFF
    /*64*/ LHU(14, 5, 16),      // x14 = 0x0000FFFF
    /*65*/ SW(4, 5, 20),        // mem[+20] = x4 (=2)
    /*66*/ LW(15, 5, 20),       // x15 = 2   (store → dependent load pair)
    /*67*/ ADD(16, 15, 1),      // x16 = x15 + x1 = 7  (load feeds a dependent op across the stall)
    /*68*/ SB(2, 5, 5),         // mem[+5]  = byte 3   (byte lane 1)
    /*69*/ LBU(20, 5, 5),       // x20 = 3
    /*70*/ SH(3, 5, 10),        // mem[+10] = half 8   (half lane 2)
    /*71*/ LHU(21, 5, 10),      // x21 = 8
    /*72*/ LUI(18, 0x80000),    // x18 = 0x80000000  (code base)
    /*73*/ LW(19, 18, 0),       // x19 = mem[base] = program word 0 (preloaded image)
    /*74*/ ECALL                // end of test
  )
  val image: Array[BigInt] = Array.tabulate(param.memWords)(idx =>
    if (idx < program.length) program(idx) else ADDI(0, 0, 0) // pad with NOP
  )

  // ---- Scala golden RV32I model (the authority for the lock-step) ----
  def u32(x: BigInt): BigInt = x & 0xffffffffL
  def s32(x: BigInt): BigInt = { val m = x & 0xffffffffL; if ((m >> 31) != 0) m - (BigInt(1) << 32) else m }
  def field(w: BigInt, hi: Int, lo: Int): BigInt = (w >> lo) & ((BigInt(1) << (hi - lo + 1)) - 1)
  def sext(v: BigInt, width: Int): BigInt = {
    val signBit = BigInt(1) << (width - 1)
    if ((v & signBit) != 0) v - (BigInt(1) << width) else v
  }

  case class Step(pc: Long, instr: BigInt, wEn: Boolean, wAddr: Int, wData: BigInt, isEcall: Boolean)

  class Golden {
    val regs = Array.fill(32)(BigInt(0))
    var pc: Long = base

    // Byte-addressed memory model: defaults to the preloaded image, overlaid by stores.
    val memBytes    = scala.collection.mutable.Map[Long, Int]()
    val storedAddrs = scala.collection.mutable.Set[Long]()
    def initByte(addr: Long): Int =
      if (addr >= base && addr < base + image.length.toLong * 4) {
        val word = image(((addr - base) / 4).toInt)
        ((word >> (((addr - base) % 4).toInt * 8)) & 0xff).toInt
      } else 0
    def rb(addr: Long): Int = memBytes.getOrElse(addr, initByte(addr))
    def wb(addr: Long, v: Int): Unit = { memBytes(addr) = v & 0xff; storedAddrs += addr }
    def loadWord(addr: Long): BigInt =
      (0 until 4).foldLeft(BigInt(0))((acc, k) => acc | (BigInt(rb(addr + k)) << (k * 8)))

    def step(): Step = {
      val curPc  = pc
      val idx    = ((curPc - base) / 4).toInt
      val w      = image(idx)
      val opcode = (w & 0x7f).toInt
      val rd     = ((w >> 7) & 0x1f).toInt
      val funct3 = ((w >> 12) & 0x7).toInt
      val funct7 = ((w >> 25) & 0x7f).toInt
      val a      = regs(((w >> 15) & 0x1f).toInt)
      val bb     = regs(((w >> 20) & 0x1f).toInt)
      val immI   = sext(field(w, 31, 20), 12)
      val immB   = sext((field(w, 31, 31) << 12) | (field(w, 7, 7) << 11) |
        (field(w, 30, 25) << 5) | (field(w, 11, 8) << 1), 13)
      val immS   = sext((field(w, 31, 25) << 5) | field(w, 11, 7), 12)
      val immU   = sext(field(w, 31, 12) << 12, 32)
      val immJ   = sext((field(w, 31, 31) << 20) | (field(w, 19, 12) << 12) |
        (field(w, 20, 20) << 11) | (field(w, 30, 21) << 1), 21)

      var wEn     = false
      var wData   = BigInt(0)
      var nextPc  = curPc + 4
      var isEcall = false

      // `alt` (funct7 bit 5) selects SUB and SRA. For OP-IMM it is only meaningful for the
      // shifts (SRAI) — ADDI must stay ADD even though the immediate's upper bits set bit 5 —
      // so SUB is gated on `isRegOp`.
      val alt = (funct7 & 0x20) != 0
      def arith(f3: Int, isRegOp: Boolean, rhs: BigInt): BigInt = f3 match {
        case 0x0 => if (isRegOp && alt) u32(s32(a) - rhs) else u32(s32(a) + rhs) // SUB / ADD(I)
        case 0x1 => u32(u32(a) << (rhs.toInt & 31))                              // SLL(I)
        case 0x2 => if (s32(a) < s32(rhs)) 1 else 0                              // SLT(I)
        case 0x3 => if (u32(a) < u32(rhs)) 1 else 0                              // SLT(I)U
        case 0x4 => u32(a) ^ u32(rhs)                                            // XOR(I)
        case 0x5 => val sh = rhs.toInt & 31
          if (alt) u32(s32(a) >> sh) else u32(u32(a) >> sh)                      // SRA(I) / SRL(I)
        case 0x6 => u32(a) | u32(rhs)                                            // OR(I)
        case 0x7 => u32(a) & u32(rhs)                                            // AND(I)
      }

      opcode match {
        case 0x13 => wEn = true; wData = arith(funct3, isRegOp = false, immI) // OP-IMM
        case 0x33 => wEn = true; wData = arith(funct3, isRegOp = true, bb)    // OP
        case 0x37 => wEn = true; wData = u32(immU)                                 // LUI
        case 0x17 => wEn = true; wData = u32(curPc + immU.toLong)                  // AUIPC
        case 0x63 =>                                                               // BRANCH
          val take = funct3 match {
            case 0x0 => s32(a) == s32(bb)
            case 0x1 => s32(a) != s32(bb)
            case 0x4 => s32(a) < s32(bb)
            case 0x5 => s32(a) >= s32(bb)
            case 0x6 => u32(a) < u32(bb)
            case 0x7 => u32(a) >= u32(bb)
          }
          if (take) nextPc = curPc + immB.toLong
        case 0x6f => wEn = true; wData = u32(curPc + 4); nextPc = curPc + immJ.toLong            // JAL
        case 0x67 => wEn = true; wData = u32(curPc + 4); nextPc = (u32(s32(a) + immI).toLong) & ~1L // JALR
        case 0x03 =>                                                               // LOAD
          val addr = (s32(a) + immI).toLong & 0xffffffffL
          val w4   = loadWord(addr)
          val v = funct3 match {
            case 0x0 => sext(w4 & 0xff, 8)    // LB
            case 0x1 => sext(w4 & 0xffff, 16) // LH
            case 0x2 => w4                    // LW
            case 0x4 => w4 & 0xff             // LBU
            case 0x5 => w4 & 0xffff           // LHU
          }
          wEn = true; wData = u32(v)
        case 0x23 =>                                                               // STORE
          val addr   = (s32(a) + immS).toLong & 0xffffffffL
          val nbytes = 1 << funct3 // SB=1, SH=2, SW=4
          for (k <- 0 until nbytes) wb(addr + k, ((bb >> (k * 8)) & 0xff).toInt)
        case 0x73 => isEcall = true                                               // SYSTEM
        case _    => sys.error(f"golden: unhandled opcode 0x$opcode%x at idx $idx")
      }

      val effEn = wEn && rd != 0
      if (effEn) regs(rd) = wData
      pc = nextPc
      Step(curPc, w, effEn, rd, wData, isEcall)
    }
  }

  SimConfig.withFstWave.compile(Riscq(param.plugins())).doSim(seed = 42) { dut =>
    val rf  = dut.host[RegFilePlugin].logic
    val dbg = rf.exec

    dut.clockDomain.forkStimulus(10)
    val mem = SimMem(dut, base, image) // code + data share one SparseMemory over Tilelink

    val golden  = new Golden
    var commits = 0
    var done    = false
    val maxCycles = 4000

    var cycle = 0
    while (!done && cycle < maxCycles) {
      dut.clockDomain.waitSampling()
      cycle += 1
      if (dbg.dbgFiring.toBoolean) {
        val dutPc    = dbg.dbgPc.toBigInt
        val dutInstr = dbg.dbgInstr.toBigInt
        val s        = golden.step()

        assert(dutPc == BigInt(s.pc),
          f"PC mismatch at commit $commits: dut 0x${dutPc.toString(16)} != golden 0x${s.pc.toHexString}")
        assert(dutInstr == s.instr,
          f"instr mismatch at pc 0x${s.pc.toHexString}: dut 0x${dutInstr.toString(16)} != image 0x${s.instr.toString(16)}")
        assert(dbg.dbgWrite.toBoolean == s.wEn,
          f"rd-write-enable mismatch at pc 0x${s.pc.toHexString} (instr 0x${s.instr.toString(16)}): " +
            s"dut ${dbg.dbgWrite.toBoolean} != golden ${s.wEn}")
        if (s.wEn) {
          assert(dbg.dbgRd.toBigInt == s.wAddr,
            f"rd-addr mismatch at pc 0x${s.pc.toHexString}: dut ${dbg.dbgRd.toBigInt} != golden ${s.wAddr}")
          assert(dbg.dbgRdData.toBigInt == s.wData,
            f"rd-data mismatch at pc 0x${s.pc.toHexString} (x${s.wAddr}): " +
              f"dut 0x${dbg.dbgRdData.toBigInt.toString(16)} != golden 0x${s.wData.toString(16)}")
        }
        commits += 1
        if (s.isEcall) done = true
      }
    }

    assert(done, s"never retired the ECALL (only $commits commits in $maxCycles cycles)")

    // The commit whitebox is sampled at executeAt, but the regfile write port lives one stage
    // later at regWriteAt (executeAt+1) and is synchronous. So when ECALL fires at executeAt the
    // instruction just ahead of it (here the final LW) is still at regWriteAt with its write not
    // yet clocked in. Drain the writeback stage before snapshotting architectural state.
    dut.clockDomain.waitSampling(4)

    // Whole-register-file cross-check at the end of the run.
    for (rIdx <- 1 until 32) {
      val got = rf.regs.getBigInt(rIdx)
      assert(got == golden.regs(rIdx),
        f"final x$rIdx mismatch: dut 0x${got.toString(16)} != golden 0x${golden.regs(rIdx).toString(16)}")
    }

    // Memory cross-check: every byte the program stored landed in the Tilelink memory.
    for (addr <- golden.storedAddrs.toSeq.sorted) {
      val got = mem.read(addr) & 0xff
      assert(got == golden.memBytes(addr),
        f"final mem[0x${addr.toHexString}] mismatch: dut 0x$got%02x != golden 0x${golden.memBytes(addr)}%02x")
    }

    println(
      s"[ExecuteSim] PASS  $commits instructions retired in lock-step over $cycle cycles " +
        "(ALU + shifts + LUI/AUIPC + all 6 branches taken/not-taken + JAL/JALR + " +
        s"LB/LH/LW/LBU/LHU + SB/SH/SW over Tilelink); register file and ${golden.storedAddrs.size} " +
        "stored memory bytes match the golden model."
    )
    simSuccess()
  }
}
