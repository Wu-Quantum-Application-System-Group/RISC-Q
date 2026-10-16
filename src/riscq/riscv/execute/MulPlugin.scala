package riscq.riscv.execute

import spinal.core._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.{Decode, DecoderPlugin, Rv32i, Rv32m}
import riscq.riscv.misc.PipelinePlugin

/** Control + result payloads the Zmmul multiplier owns and shares. */
object MulPlugin extends AreaObject {
  /** Decoded: this instruction is MUL/MULH/MULHSU/MULHU. Read here (halt + result) and by
   *  WriteBackPlugin (as the rd-write select, registered via `addRdSource`). */
  val SEL_MUL = Payload(Bool())
  /** The selected 32-bit product half (low for MUL, high for MULH*), handed to WriteBackPlugin. */
  val MUL_RD_DATA = Payload(Bits(Global.XLEN bits))
}

/**
 * The optional RV32 **M** multiply unit: `MUL`, `MULH`, `MULHSU`, `MULHU`.
 *
 * riscq has a single execute stage, so — like the LSU — this plugin lives at `executeAt` and
 * **halts** the stage for a fixed number of cycles while a small internal pipeline computes the
 * product, then fires. It is its own `FiberPlugin`, gated by `RiscqParam.withMul`, and contributes
 * nothing to the RV32I datapath beyond one registered rd-write source (`WriteBackPlugin.addRdSource`).
 *
 * **Datapath.** Each 32-bit operand is sign-extended to a 33-bit signed value (so one signed
 * 33×33 → 64-bit multiply covers all four ops: MUL keeps the low word, the MULH* group the high
 * word). The product is built from four partial products, each sized to fit a single Xilinx
 * **27×18 DSP**: every operand is split into a low (17-bit, → 18-bit signed non-negative) and a
 * high (16-bit signed) chunk, with `op = hi·2^17 + lo` exactly. The four chunk multiplies are
 * registered (one cycle), then their `2^0 / 2^17 / 2^34` weighted sum is folded into the 64-bit
 * product across **two** further cycles — a two-level adder tree split over a register, so no single
 * cycle chains all three additions (that one-cycle accumulate was timing-critical; see the sum block).
 * Three-cycle latency ⇒ a 3-cycle bubble per multiply, trading IPC for timing.
 *
 * This is the depth-1 stripping of VexiiRiscv's `MulPlugin` (no multi-lane, no `MulSpliter`
 * generality, no `rsUnsignedPlugin`, no 64-bit MULW).
 */
class MulPlugin(p: RiscqParam) extends FiberPlugin {
  import MulPlugin._

  // How many cycles the execute stage is held while the internal multiply pipeline fills. The three
  // register layers below (partial products, then the weighted sum split across two cycles) set this.
  val latency = 3

  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val dec   = host[DecoderPlugin]
    val wbp   = host[WriteBackPlugin]
    val lock  = retains(pp.elaborationLock)
    val dlock = retains(dec.elaborationLock)
    val wlock = retains(wbp.elaborationLock)
    awaitBuild()

    // ---- Make the four multiply encodings legal (R-type) and decode SEL_MUL for them. ----
    dec.addDecodingDefault(SEL_MUL, False)
    for (enc <- Rv32m.muls) {
      dec.addInstruction(enc, Rv32i.ImmType.NONE)
      dec.addDecoding(enc, SEL_MUL, True)
    }
    dlock.release()

    // ---- Contribute the product as an rd-write source to the generic write-back mux. ----
    wbp.addRdSource(SEL_MUL, MUL_RD_DATA)
    wlock.release()

    val c = pp.ctrl(p.executeAt)
    val mul = new c.Area {
      val funct3 = apply(Decode.FUNCT3)
      val sel    = apply(SEL_MUL)

      // A multiply occupies this stage; drop it on a wrong-path flush (an older branch resolves one
      // stage ahead at jumpAt and can cancel us — same gate as the LSU). `up.isCancel` raises then.
      val cancelled = up.isCancel
      val active    = isValid && !cancelled && sel

      // ---- Fixed-latency sequencer. `counter` counts resident cycles (0..latency); the stage halts
      //      until the product register is full. It resets whenever this isn't an in-progress
      //      multiply — a non-mul, a flushed mul, or the completing cycle (busy=False) — so the next
      //      multiply always starts from 0. ----
      val counter = Reg(UInt(log2Up(latency + 1) bits)) init 0
      val busy    = counter =/= latency
      when(active && busy) { counter := counter + 1 } otherwise { counter := 0 }
      // Baked in: `cancelled` (up.isCancel) is kept out of the halt qualifier — same reason as the
      // LSU. A cancelled multiply is only ever hit on its first cycle (counter stays 0 since the
      // increment keeps `active`), and the flush clears this stage's valid next edge off the ready spine,
      // so the one extra halted cycle is inside the mispredict flush shadow — zero IPC.
      val haltActive = isValid && sel
      haltWhen(haltActive && busy)

      val first  = active && counter === 0 // capture operands (their bypass window is one cycle)
      val second = active && counter === 1 // partial products ready: start the weighted-sum tree
      val third  = active && counter === 2 // partial sums ready: fold into the final product

      // ---- Operand sign handling. funct3 picks which operand is signed (MUL/MULH signed×signed,
      //      MULHSU signed×unsigned, MULHU unsigned×unsigned); sign-extend each to 33-bit signed. ----
      val high      = funct3 =/= B"000" // MULH/MULHSU/MULHU return the upper word
      val rs1Signed = funct3 =/= B"011" // rs1 is unsigned only for MULHU
      val rs2Signed = !funct3(1)        // rs2 is unsigned for MULHSU/MULHU
      val rs1 = apply(Execute.RS1_DATA)
      val rs2 = apply(Execute.RS2_DATA)
      val op1 = (rs1Signed && rs1.msb) ## rs1 // 33-bit: {sign, rs1}
      val op2 = (rs2Signed && rs2.msb) ## rs2

      // ---- Split each 33-bit operand into low (17b → 18b signed non-negative) and high (16b signed)
      //      chunks, so every partial product fits one 27×18 DSP. `op = hi·2^17 + lo` exactly. ----
      val a1Lo = (False ## op1(16 downto 0)).asSInt // 18-bit, non-negative
      val a1Hi = op1(32 downto 17).asSInt           // 16-bit, signed
      val a2Lo = (False ## op2(16 downto 0)).asSInt
      val a2Hi = op2(32 downto 17).asSInt

      // ---- Partial products, registered on the first cycle (the live operands' bypass window).
      //      Each is a single signed DSP multiply. ----
      val ppLL = Reg(SInt(36 bits)); val ppLH = Reg(SInt(34 bits))
      val ppHL = Reg(SInt(34 bits)); val ppHH = Reg(SInt(32 bits))
      when(first) {
        ppLL := a1Lo * a2Lo
        ppLH := a1Lo * a2Hi
        ppHL := a1Hi * a2Lo
        ppHH := a1Hi * a2Hi
      }

      // ---- Weighted sum, split across two cycles so no single cycle chains all three additions
      //      (that one-cycle accumulate was timing-critical). Cycle 2 forms two independent partial sums
      //      in parallel: the two weight-2^17 partials (pre-shifted into the 64-bit lane), and the
      //      2^0 / 2^34 "ends" (which overlap in only two bits). Cycle 3 adds the two. Two's-complement
      //      addition is modular, so the low 64 bits hold the correct value for every signed/unsigned
      //      case (the product fits in 64). ----
      val sum17   = Reg(SInt(64 bits)) // (ppLH + ppHL) at weight 2^17, pre-shifted into the 64-bit lane
      val sumEnds = Reg(SInt(64 bits)) // ppLL (2^0) + ppHH (2^34)
      when(second) {
        sum17   := ((ppLH +^ ppHL).resize(64) << 17).resize(64)
        sumEnds := ppLL.resize(64) + ((ppHH.resize(64)) << 34).resize(64)
      }
      val product = Reg(SInt(64 bits))
      when(third) {
        product := sumEnds + sum17
      }

      // ---- Result half. Valid on the completing cycle (counter == latency); WriteBackPlugin samples
      //      it only when the stage fires, so the in-progress value is never committed. ----
      val pBits = product.asBits
      MUL_RD_DATA := high ? pBits(63 downto 32) | pBits(31 downto 0)
    }

    lock.release()
  }
}
