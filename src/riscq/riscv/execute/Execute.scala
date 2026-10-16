package riscq.riscv.execute

import spinal.core._
import spinal.lib.misc.pipeline.Payload
import riscq.riscv.Global

/**
 * Execute-stage pipeline payloads (single lane).
 *
 * These payloads are how the execute plugins hand values to each other. Each is written by
 * exactly one plugin (noted below) and read by the others — the same "shared payload"
 * dataflow as VexiiRiscv's `SrcPlugin.SRC1` / `IntAluPlugin.ALU_RESULT`, minus the
 * per-lane / per-uop machinery.
 */
object Execute extends AreaObject {
  // --- RegFilePlugin: the raw async read at `regReadAt`, before forwarding. The
  //     regReadAt→executeAt StageLink registers + holds these, so the distributed-RAM read is off
  //     the executeAt cone. ---
  val RS1_RAW = Payload(Bits(Global.XLEN bits))
  val RS2_RAW = Payload(Bits(Global.XLEN bits))

  // --- RegFilePlugin: the two forwarded operand values (x0 ⇒ 0), driven at `regReadAt` and
  //     registered into executeAt. Read by Src/Branch/Lsu/Csr/WriteBack. ---
  val RS1_DATA = Payload(Bits(Global.XLEN bits))
  val RS2_DATA = Payload(Bits(Global.XLEN bits))

  // --- SrcPlugin: the muxed ALU/branch operands and the shared add/sub + comparators ---
  val SRC1    = Payload(SInt(Global.XLEN bits))
  val SRC2    = Payload(SInt(Global.XLEN bits))
  val ADD_SUB = Payload(SInt(Global.XLEN bits)) // SRC1 + SRC2, or SRC1 - SRC2 when subtracting
  val LESS    = Payload(Bool())                 // SRC1 < SRC2 (signed or unsigned per instruction)
  val EQ      = Payload(Bool())                 // SRC1 === SRC2

  // --- IntAluPlugin: the integer result. Whether this op writes rd is IntAluPlugin.ALU_WRITES_RD. ---
  val ALU_RESULT = Payload(Bits(Global.XLEN bits))

  // --- IntAluPlugin: a shallow tap of the ALU result covering only cheap ops (ADD/SUB/SLT/logic) —
  //     drops the barrel shifter (SHIFT maps to the add: a don't-care, since a shift producer is
  //     interlocked, never forwarded cheaply). Used as RegFilePlugin's srcA forward source to keep
  //     the shifter out of the 1-ahead forward loop. Equals ALU_RESULT for every op a cheap producer
  //     can be (≠ SHIFT). ---
  val ALU_FAST = Payload(Bits(Global.XLEN bits))

  // --- BranchPlugin: the return address (PC+4). Whether this op is a jump is `BranchPlugin.IS_JUMP`. ---
  val LINK_PC = Payload(UInt(Global.FETCH_PC_WIDTH bits))

  // --- BranchPlugin: the BRANCH/JAL target `pc+imm`, produced at decodeAt (no register operand
  //     needed; Decode.IMM is already available there) and registered into executeAt by the
  //     decodeAt→executeAt StageLink. Only meaningful for BRANCH/JAL — JALR computes its
  //     operand-dependent target (`rs1+imm`) at executeAt. ---
  val BRANCH_TARGET = Payload(UInt(Global.FETCH_PC_WIDTH bits))

  // --- BranchPlugin: the resolved control-flow decision, computed at executeAt and registered one
  //     stage into jumpAt (VexiiRiscv-style split). The expensive 32-bit *target* compare is done here
  //     and registered as a single bit (BAD_TARGET); only the cheap 1-bit *direction* logic + the
  //     redirect/flush run at jumpAt, off the high-fanout flush's critical cone. ---
  val TAKE           = Payload(Bool())                      // the branch/jump is taken
  val TARGET_ALIGNED = Payload(UInt(Global.FETCH_PC_WIDTH bits))  // the taken target (redirect payload when taken)
  val BAD_TARGET     = Payload(Bool())                      // predicted next-PC ≠ resolved taken target
  // lateBadTarget (off by default): the *direction*-based mispredict (cfi & predicted-taken disagrees
  // with the resolved take, `take ⇒ isCfi`), precomputed at executeAt and registered so that when the
  // 32-bit target compare moves to jumpAt, jumpAt's high-fanout flush root stays shallow —
  // `isFiring & (DIR_MISPREDICT | (TAKE & badTarget))`. Cheap 1-bit logic, off the BAD_TARGET cone.
  val DIR_MISPREDICT = Payload(Bool())

  // --- LsuPlugin: the (sign/zero-extended) value read by a load. Whether this op is a load is
  //     the decoded `LsuPlugin.IS_LOAD`. ---
  val LOAD_DATA = Payload(Bits(Global.XLEN bits))

  // --- CsrPlugin: the old CSR value a Zicsr read returns to rd. Whether this op is a CSR
  //     access is the decoded `CsrPlugin.IS_CSR`. ---
  val CSR_RD_DATA = Payload(Bits(Global.XLEN bits))

  // --- WriteBackPlugin: the value + enable presented to the register-file write port ---
  val RD_DATA  = Payload(Bits(Global.XLEN bits))
  val RD_WRITE = Payload(Bool())
  // The rd value computed at executeAt, used only by the RVLS whitebox so the retire point stays at
  // executeAt while the real RD_DATA mux moves to regWriteAt (when lateWriteback is on). Sim-only (no
  // hardware consumer ⇒ pruned in synthesis). Equals RD_DATA when lateWriteback is off.
  val RD_DATA_DBG = Payload(Bits(Global.XLEN bits))
}
