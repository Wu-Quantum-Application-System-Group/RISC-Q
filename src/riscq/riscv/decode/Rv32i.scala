package riscq.riscv.decode

import spinal.core._

/**
 * RV32I base instruction encodings and the immediate format each one carries.
 *
 * Mirrors VexiiRiscv's `Rvi` table but lists only the base integer ISA (no M/A/F/C,
 * no Zicsr) — just enough for the decoder to (a) reject illegal words and (b) pick the
 * right immediate format. The masked literals use the usual
 * `funct7 | rs2 | rs1 | funct3 | rd | opcode` field layout.
 *
 * This table is the single source of truth: the same set drives both the legality check
 * and the immediate-format selection, so the two can never disagree.
 */
object Rv32i {

  /** Immediate format an instruction uses (RISC-V types I/S/B/U/J; `NONE` = R-type / no immediate). */
  object ImmType extends SpinalEnum {
    val NONE, I, S, B, U, J = newElement()
  }

  // Positional instruction fields — identical bit ranges for every RV32I instruction.
  def opcodeRange = 6 downto 0
  def rdRange     = 11 downto 7
  def funct3Range = 14 downto 12
  def rs1Range    = 19 downto 15
  def rs2Range    = 24 downto 20
  def funct7Range = 31 downto 25

  /**
   * The 7-bit major opcodes (the part the execute plugins switch on). Kept here next to the
   * field ranges so the encoding table above and the execute datapath share one source.
   */
  object Opcode {
    def LOAD   = B"0000011"
    def FENCE  = B"0001111"
    def OP_IMM = B"0010011"
    def AUIPC  = B"0010111"
    def STORE  = B"0100011"
    def OP     = B"0110011"
    def LUI    = B"0110111"
    def BRANCH = B"1100011"
    def JALR   = B"1100111"
    def JAL    = B"1101111"
    def SYSTEM = B"1110011"
  }

  // ---- Named encodings (the single source of truth). The `funct7 | rs2 | rs1 | funct3 | rd |
  //      opcode` masked literals; each instruction has a named val so the decoder can group them
  //      for the control specs and the immediate/legality tables. ----
  // Register-register (R-type)
  val ADD  = M"0000000----------000-----0110011"
  val SUB  = M"0100000----------000-----0110011"
  val SLL  = M"0000000----------001-----0110011"
  val SLT  = M"0000000----------010-----0110011"
  val SLTU = M"0000000----------011-----0110011"
  val XOR  = M"0000000----------100-----0110011"
  val SRL  = M"0000000----------101-----0110011"
  val SRA  = M"0100000----------101-----0110011"
  val OR   = M"0000000----------110-----0110011"
  val AND  = M"0000000----------111-----0110011"
  // Register-immediate (I-type)
  val ADDI  = M"-----------------000-----0010011"
  val SLTI  = M"-----------------010-----0010011"
  val SLTIU = M"-----------------011-----0010011"
  val XORI  = M"-----------------100-----0010011"
  val ORI   = M"-----------------110-----0010011"
  val ANDI  = M"-----------------111-----0010011"
  val SLLI  = M"0000000----------001-----0010011" // RV32: funct7 = 0000000
  val SRLI  = M"0000000----------101-----0010011"
  val SRAI  = M"0100000----------101-----0010011"
  // Loads (I-type)
  val LB  = M"-----------------000-----0000011"
  val LH  = M"-----------------001-----0000011"
  val LW  = M"-----------------010-----0000011"
  val LBU = M"-----------------100-----0000011"
  val LHU = M"-----------------101-----0000011"
  // Stores (S-type)
  val SB = M"-----------------000-----0100011"
  val SH = M"-----------------001-----0100011"
  val SW = M"-----------------010-----0100011"
  // Branches (B-type)
  val BEQ  = M"-----------------000-----1100011"
  val BNE  = M"-----------------001-----1100011"
  val BLT  = M"-----------------100-----1100011"
  val BGE  = M"-----------------101-----1100011"
  val BLTU = M"-----------------110-----1100011"
  val BGEU = M"-----------------111-----1100011"
  // Jumps
  val JAL  = M"-------------------------1101111"
  val JALR = M"-----------------000-----1100111"
  // Upper immediates (U-type)
  val LUI   = M"-------------------------0110111"
  val AUIPC = M"-------------------------0010111"
  // Fence / environment — no immediate operand to execute
  val FENCE   = M"-----------------000-----0001111"
  val FENCE_I = M"-----------------001-----0001111" // Zifencei; a nop here, no i-cache
  val ECALL   = M"00000000000000000000000001110011"
  val EBREAK  = M"00000000000100000000000001110011"
  val MRET    = M"00110000001000000000000001110011"
  val WFI     = M"00010000010100000000000001110011" // nop here
  // Zicsr: csr address = instr[31:20], source = rs1 (or zimm = rs1 field for the *I variants).
  // The CsrPlugin reads those fields straight off the word, so no immediate format is needed.
  val CSRRW  = M"-----------------001-----1110011"
  val CSRRS  = M"-----------------010-----1110011"
  val CSRRC  = M"-----------------011-----1110011"
  val CSRRWI = M"-----------------101-----1110011"
  val CSRRSI = M"-----------------110-----1110011"
  val CSRRCI = M"-----------------111-----1110011"

  // (encoding, immediate format) for every legal RV32I instruction. Built from the named vals
  // above so this table and the per-instruction control specs (DecoderPlugin) share one source.
  val instructions: Seq[(MaskedLiteral, ImmType.E)] = Seq(
    ADD -> ImmType.NONE, SUB -> ImmType.NONE, SLL -> ImmType.NONE, SLT -> ImmType.NONE,
    SLTU -> ImmType.NONE, XOR -> ImmType.NONE, SRL -> ImmType.NONE, SRA -> ImmType.NONE,
    OR -> ImmType.NONE, AND -> ImmType.NONE,
    ADDI -> ImmType.I, SLTI -> ImmType.I, SLTIU -> ImmType.I, XORI -> ImmType.I,
    ORI -> ImmType.I, ANDI -> ImmType.I, SLLI -> ImmType.I, SRLI -> ImmType.I, SRAI -> ImmType.I,
    LB -> ImmType.I, LH -> ImmType.I, LW -> ImmType.I, LBU -> ImmType.I, LHU -> ImmType.I,
    SB -> ImmType.S, SH -> ImmType.S, SW -> ImmType.S,
    BEQ -> ImmType.B, BNE -> ImmType.B, BLT -> ImmType.B, BGE -> ImmType.B,
    BLTU -> ImmType.B, BGEU -> ImmType.B,
    JAL -> ImmType.J, JALR -> ImmType.I,
    LUI -> ImmType.U, AUIPC -> ImmType.U,
    FENCE -> ImmType.NONE, FENCE_I -> ImmType.NONE,
    ECALL -> ImmType.NONE, EBREAK -> ImmType.NONE, MRET -> ImmType.NONE, WFI -> ImmType.NONE,
    CSRRW -> ImmType.NONE, CSRRS -> ImmType.NONE, CSRRC -> ImmType.NONE,
    CSRRWI -> ImmType.NONE, CSRRSI -> ImmType.NONE, CSRRCI -> ImmType.NONE
  )

  /** All legal encodings, for the illegal-instruction check. */
  def all: Seq[MaskedLiteral] = instructions.map(_._1)
}
