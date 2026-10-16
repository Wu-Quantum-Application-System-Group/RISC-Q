package riscq.riscv.execute

import spinal.core._
import spinal.lib.KeepAttribute
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.{Decode, DecoderPlugin}
import riscq.riscv.misc.PipelinePlugin

/** Source-select + add/sub control signals this plugin owns and registers with the decoder. */
object SrcPlugin extends AreaObject {
  /** SRC1 source: the register value, a zero (LUI), or the PC (AUIPC). */
  object Src1Sel extends SpinalEnum { val RF, ZERO, PC = newElement() }
  val SRC1_SEL     = Payload(Src1Sel()) // consumed at the source-select stage (regReadAt)
  val SRC2_RS2     = Payload(Bool())    // SRC2 is rs2 (OP / BRANCH) vs the immediate
  val DO_SUB       = Payload(Bool())    // subtract for SUB / SLT* / BRANCH
  val CMP_UNSIGNED = Payload(Bool())    // unsigned LESS for SLTU / SLTIU / BLTU / BGEU
}

/**
 * Builds the two ALU/branch operands and the shared add/sub + comparators.
 *
 * Mirrors VexiiRiscv's `SrcPlugin` (one SRC1/SRC2 mux feeding a single `ADD_SUB`/`LESS`
 * reused by the ALU and the branch unit). The few control bits (which source each operand mux
 * picks, whether to subtract, signed vs unsigned compare) are this plugin's own decode keys: it
 * *defines* them (companion object) and reads them back here as registered control payloads, so it
 * never compares opcode/funct itself. It registers only their **defaults** with `DecoderPlugin`;
 * each per-instruction override is registered by the plugin that implements that instruction
 * (IntAluPlugin for OP / OP-IMM / LUI / AUIPC, BranchPlugin for the conditional branches), keeping
 * this plugin instruction-agnostic.
 */
class SrcPlugin(p: RiscqParam) extends FiberPlugin {
  import SrcPlugin._

  val logic = during setup new Area {
    val pp   = host[PipelinePlugin]
    val dec  = host[DecoderPlugin]
    val lock  = retains(pp.elaborationLock)
    val dlock = retains(dec.elaborationLock)
    awaitBuild()

    // ---- Register only the control payloads' *defaults* with the decoder (built at decodeAt). The
    //      per-instruction overrides live in the plugins that implement those instructions
    //      (IntAluPlugin / BranchPlugin), so this plugin needs no instruction list of its own. ----
    dec.addDecodingDefault(SRC1_SEL, Src1Sel.RF)
    dec.addDecodingDefault(SRC2_RS2, False)
    dec.addDecodingDefault(DO_SUB, False)
    dec.addDecodingDefault(CMP_UNSIGNED, False)
    dlock.release()

    // The SRC1/SRC2 source-select muxes are built at `regReadAt` (one stage before executeAt), so the
    // regReadAt→executeAt StageLink *registers* SRC1/SRC2 and the 32-bit add below starts from a
    // register (off the operand-select path).
    val cs = pp.ctrl(p.regReadAt)
    val c  = pp.ctrl(p.executeAt)

    // ---- SRC1/SRC2 source-select muxes (at regReadAt). ----
    // The selects are this plugin's decoded payloads; imm/PC are available at regReadAt (decode is
    // upstream); rs1/rs2 are the forwarded RS1_DATA/RS2_DATA, which RegFilePlugin drives at regReadAt.
    val select = new cs.Area {
      val rs1 = Execute.RS1_DATA.asSInt
      val rs2 = Execute.RS2_DATA.asSInt
      val imm = Decode.IMM.asSInt
      val pc  = Global.fullPc(Global.PC).asSInt.resize(Global.XLEN) // AUIPC: rd = full PC + imm

      // SRC1 is rs1, except LUI (start from 0) and AUIPC (start from the PC).
      Execute.SRC1 := rs1
      switch(apply(SRC1_SEL)) {
        is(Src1Sel.ZERO) { Execute.SRC1 := S(0, Global.XLEN bits) }
        is(Src1Sel.PC)   { Execute.SRC1 := pc }
        default          { Execute.SRC1 := rs1 }
      }

      // SRC2 is rs2 for register-register ops and branches; the immediate otherwise.
      Execute.SRC2 := apply(SRC2_RS2) ? rs2 | imm
    }

    // ---- The shared add/sub + comparators (at executeAt). SRC1/SRC2 are the registered payloads, so
    //      the 32-bit add starts from a register. ----
    val src = new c.Area {
      // Cap the operand-register fanout so Vivado replicates SRC1/SRC2: these high-fanout nets feed the
      // adder, comparators, shifter, bitwise ops and the ALU_FAST forward, and the single-cycle ALU
      // result-forward loop through them is route-dominated. A timing hint only — bit-exact.
      if (p.aluOperandMaxFanout > 0) {
        apply(Execute.SRC1).addAttribute("max_fanout", p.aluOperandMaxFanout)
        apply(Execute.SRC2).addAttribute("max_fanout", p.aluOperandMaxFanout)
      }

      // Subtract for SUB, the SLT* compares, and every branch (they all compare via SRC1-SRC2).
      // KeepAttribute so Vivado doesn't fold the shared add/sub into the comparators' LUT
      // chain (VexiiRiscv does the same) — cheap fmax insurance.
      val addSub = apply(DO_SUB) ? (Execute.SRC1 - Execute.SRC2) | (Execute.SRC1 + Execute.SRC2)
      KeepAttribute(addSub)
      Execute.ADD_SUB := addSub

      // Unsigned compare for SLTU/SLTIU and BLTU/BGEU.
      val unsigned = apply(CMP_UNSIGNED)
      Execute.LESS := unsigned ? (Execute.SRC1.asUInt < Execute.SRC2.asUInt) | (Execute.SRC1 < Execute.SRC2)
      Execute.EQ   := Execute.SRC1 === Execute.SRC2
    }

    lock.release()
  }
}
