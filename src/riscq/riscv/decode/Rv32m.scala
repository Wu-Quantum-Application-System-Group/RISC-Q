package riscq.riscv.decode

import spinal.core._

/**
 * RV32 'M' extension encodings (opcode `OP` = 0110011, funct7 = 0000001).
 *
 * Parallels [[Rv32i]] but holds only the optional multiply (Zmmul) instructions, so an extension
 * plugin can make its own opcodes legal via `DecoderPlugin.addInstruction` without touching the base
 * RV32I catalog. Only the multiply group is listed today; DIV/REM (full M, a future `DivPlugin`) would
 * join here.
 */
object Rv32m {
  // Multiply (R-type, funct7 = 0000001). funct3: MUL=000, MULH=001, MULHSU=010, MULHU=011.
  val MUL    = M"0000001----------000-----0110011"
  val MULH   = M"0000001----------001-----0110011"
  val MULHSU = M"0000001----------010-----0110011"
  val MULHU  = M"0000001----------011-----0110011"

  /** The four multiply variants (all R-type ⇒ no immediate). */
  val muls: Seq[MaskedLiteral] = Seq(MUL, MULH, MULHSU, MULHU)
}
