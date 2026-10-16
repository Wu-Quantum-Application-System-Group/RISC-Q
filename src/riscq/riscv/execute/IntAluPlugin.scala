package riscq.riscv.execute

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.{DecoderPlugin, Rv32i}
import riscq.riscv.misc.PipelinePlugin

/** ALU op-select / shift / write-rd control signals this plugin owns and registers with the decoder. */
object IntAluPlugin extends AreaObject {
  /** ALU result source (funct3 select, with LUI/AUIPC folded into ADD so no post-mux override). */
  object AluSel extends SpinalEnum { val ADD, SHIFT, SLT, XOR, OR, AND = newElement() }
  val ALU_SEL       = Payload(AluSel())  // IntAluPlugin result-mux select
  val SHIFT_LEFT    = Payload(Bool())    // SLL / SLLI (else right shift)
  val SHIFT_ARITH   = Payload(Bool())    // SRA / SRAI (arithmetic right shift)
  val ALU_WRITES_RD = Payload(Bool())    // OP / OP_IMM / LUI / AUIPC write rd from the ALU
}

/**
 * The integer ALU: every RV32I register-writing op that isn't a load or a jump.
 *
 * `ADD/ADDI`, `SUB`, `AND(I)/OR(I)/XOR(I)`, `SLT(I)(U)`, the shifts `SLL(I)/SRL(I)/SRA(I)`,
 * and `LUI/AUIPC`. The add/sub and the less-than comparison come pre-computed from
 * `SrcPlugin` (shared with the branch unit); this plugin only adds the bitwise ops, the
 * shifter, and the result mux. The op/shift selects are this plugin's own decode keys —
 * registered with `DecoderPlugin` below (LUI/AUIPC fold into the ADD select, so the result mux
 * needs no post-switch opcode override) and read back here as registered control payloads. It also
 * registers, for its own instructions, the operand/compare knobs `SrcPlugin` exposes (`SRC1_SEL`,
 * `SRC2_RS2`, `DO_SUB`, `CMP_UNSIGNED`) — those payloads are `SrcPlugin`'s, but the per-instruction
 * values are the implementer's to declare.
 */
class IntAluPlugin(p: RiscqParam) extends FiberPlugin {
  import IntAluPlugin._

  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val dec   = host[DecoderPlugin]
    val lock  = retains(pp.elaborationLock)
    val dlock = retains(dec.elaborationLock)
    awaitBuild()

    // ---- Register this plugin's control-signal decodings with the decoder (built at decodeAt). ----
    import Rv32i._
    val rType = Seq(ADD, SUB, SLL, SLT, SLTU, XOR, SRL, SRA, OR, AND)
    val iAlu  = Seq(ADDI, SLTI, SLTIU, XORI, ORI, ANDI, SLLI, SRLI, SRAI)
    dec.addDecodingDefault(ALU_SEL, AluSel.ADD) // ADD / ADDI / SUB / LUI / AUIPC
    dec.addDecodingDefault(SHIFT_LEFT, False)
    dec.addDecodingDefault(SHIFT_ARITH, False)
    dec.addDecodingDefault(ALU_WRITES_RD, False)
    Seq(SLL, SLLI, SRL, SRLI, SRA, SRAI).foreach(dec.addDecoding(_, ALU_SEL, AluSel.SHIFT))
    Seq(SLT, SLTI, SLTU, SLTIU).foreach(dec.addDecoding(_, ALU_SEL, AluSel.SLT))
    Seq(XOR, XORI).foreach(dec.addDecoding(_, ALU_SEL, AluSel.XOR))
    Seq(OR, ORI).foreach(dec.addDecoding(_, ALU_SEL, AluSel.OR))
    Seq(AND, ANDI).foreach(dec.addDecoding(_, ALU_SEL, AluSel.AND))
    Seq(SLL, SLLI).foreach(dec.addDecoding(_, SHIFT_LEFT, True))
    Seq(SRA, SRAI).foreach(dec.addDecoding(_, SHIFT_ARITH, True))
    // Shifts write rd from the ALU result mux like every other ALU op.
    (rType ++ iAlu ++ Seq(LUI, AUIPC)).foreach(dec.addDecoding(_, ALU_WRITES_RD, True))

    // ---- SrcPlugin operand/compare knobs for the ALU's instructions: the payloads (and their
    //      defaults) belong to SrcPlugin, but each per-instruction override is registered by us, the
    //      implementer. SrcPlugin builds the muxes/add-sub/comparator from the decoded result. ----
    dec.addDecoding(LUI, SrcPlugin.SRC1_SEL, SrcPlugin.Src1Sel.ZERO)   // SRC1 starts from 0
    dec.addDecoding(AUIPC, SrcPlugin.SRC1_SEL, SrcPlugin.Src1Sel.PC)   // SRC1 starts from the PC
    rType.foreach(dec.addDecoding(_, SrcPlugin.SRC2_RS2, True))        // OP uses rs2 (else imm)
    Seq(SUB, SLT, SLTU, SLTI, SLTIU).foreach(dec.addDecoding(_, SrcPlugin.DO_SUB, True))
    Seq(SLTU, SLTIU).foreach(dec.addDecoding(_, SrcPlugin.CMP_UNSIGNED, True))
    dlock.release()

    val c = pp.ctrl(p.executeAt)

    val alu = new c.Area {
      val src1   = Execute.SRC1
      val src2   = Execute.SRC2
      val addSub = Execute.ADD_SUB
      val less   = Execute.LESS

      // 0/1 result of SLT / SLTU (LESS is already signed or unsigned per SrcPlugin).
      val sltResult = less.asUInt.resize(Global.XLEN).asSInt

      // Shift amount is the low 5 bits of SRC2 (rs2 for SLL, or the I-immediate for SLLI).
      val shamt = src2(4 downto 0).asUInt
      val left  = apply(SHIFT_LEFT)  // SLL/SLLI shift left, else right
      val arith = apply(SHIFT_ARITH) // SRA/SRAI: arithmetic right shift

      // The shifted value: a single-cycle **barrel** shifter (~5 mux levels), fed into the result mux below.
      val shiftLeft   = (src1.asUInt |<< shamt).asSInt          // SLL / SLLI
      val shiftRightL = (src1.asUInt |>> shamt).asSInt          // SRL / SRLI (logical)
      val shiftRightA = src1 |>> shamt                          // SRA / SRAI (arithmetic)
      val shiftResult = left ? shiftLeft | (arith ? shiftRightA | shiftRightL)

      // Result mux on the decoded ALU select. LUI/AUIPC fold into ADD (SrcPlugin set SRC1=0/PC and
      // SRC2=imm, so the adder result is exactly the U-immediate / PC+immediate) ⇒ no opcode override.
      val result = SInt(Global.XLEN bits)
      if (p.aluResultOneHot) {
        // one-hot result mux: each candidate ANDed with its ALU_SEL one-hot bit, OR-reduced as a
        // balanced tree — a flat AND-OR cone instead of the priority switch's chained 2:1 muxes. AluSel
        // covers all six selects exactly, so precisely one term is non-zero ⇒ bit-identical to the switch.
        val sel = apply(ALU_SEL)
        val terms = Seq[(Bool, SInt)](
          (sel === AluSel.ADD)   -> addSub,        // ADD / ADDI / SUB / LUI / AUIPC
          (sel === AluSel.SHIFT) -> shiftResult,   // SLL(I) / SRL(I) / SRA(I)
          (sel === AluSel.SLT)   -> sltResult,     // SLT(I)(U)
          (sel === AluSel.XOR)   -> (src1 ^ src2), // XOR / XORI
          (sel === AluSel.OR)    -> (src1 | src2), // OR / ORI
          (sel === AluSel.AND)   -> (src1 & src2)) // AND / ANDI
        result := terms.map { case (h, v) => h ? v | S(0, Global.XLEN bits) }.reduceBalancedTree(_ | _)
      } else {
        result := addSub
        switch(apply(ALU_SEL)) {
          is(AluSel.ADD)   { result := addSub }       // ADD / ADDI / SUB / LUI / AUIPC
          is(AluSel.SHIFT) { result := shiftResult }  // SLL(I) / SRL(I) / SRA(I)
          is(AluSel.SLT)   { result := sltResult }    // SLT(I)(U)
          is(AluSel.XOR)   { result := src1 ^ src2 }  // XOR / XORI
          is(AluSel.OR)    { result := src1 | src2 }  // OR / ORI
          is(AluSel.AND)   { result := src1 & src2 }  // AND / ANDI
        }
      }

      Execute.ALU_RESULT := result.asBits
      // ALU_WRITES_RD is a decoded payload, read by WriteBackPlugin.

      // ---- The shallow ALU tap used by the srcA forward (RegFilePlugin). Same result mux as above but
      //      with the SHIFT case folded into ADD (don't-care: a shift producer interlocks, so its
      //      ALU_FAST is never forwarded), keeping the barrel shifter out of the srcA forward loop.
      //      Equals `result` for every non-shift ALU op. ----
      // aluFastAddOnly: forward ONLY the ADD class — `ALU_FAST := addSub` with no result mux, one logic
      //   level shorter in the forward loop. RegFilePlugin makes SLT/XOR/OR/AND "expensive" to match, so
      //   those cases here are dead and dropped. Costs IPC (back-to-back dependent SLT/bitwise pairs now
      //   bubble) for shorter timing.
      val fast = SInt(Global.XLEN bits)
      if (p.aluFastAddOnly) {
        fast := addSub
      } else {
        fast := addSub
        switch(apply(ALU_SEL)) {
          is(AluSel.SLT) { fast := sltResult }
          is(AluSel.XOR) { fast := src1 ^ src2 }
          is(AluSel.OR)  { fast := src1 | src2 }
          is(AluSel.AND) { fast := src1 & src2 }
          // ADD (default) and SHIFT both map to addSub — SHIFT is never forwarded cheap.
        }
      }
      Execute.ALU_FAST := fast.asBits
    }

    lock.release()
  }
}
