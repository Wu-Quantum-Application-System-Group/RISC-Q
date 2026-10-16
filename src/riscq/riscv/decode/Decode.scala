package riscq.riscv.decode

import spinal.core._
import spinal.lib.misc.pipeline.Payload
import riscq.riscv.Global

/**
 * Decode-stage pipeline payloads (single lane, no aligner).
 *
 * Only the *intrinsic* decode results live here — the ones the decoder produces the same way for
 * every consumer: the positional fields, the legality flag, and the sign-extended immediate. The
 * per-instruction *control* signals (source-select, ALU op, is-load, …) now live in the plugins that
 * consume them and are decoded generically by `DecoderPlugin` (mirrors VexiiRiscv: each execute
 * plugin owns its own decode keys). So adding an optional extension touches only its own plugin.
 *
 * Mirrors VexiiRiscv's `Decode` object, stripped to what a base RV32I core needs.
 */
object Decode extends AreaObject {
  // RV32I has a fixed 32-entry integer register file (x0..x31); this is an ISA invariant,
  // not a riscq configuration knob, hence not a RiscqParam.
  val ARCH_REGS     = 32
  val RF_ADDR_WIDTH = log2Up(ARCH_REGS)

  val RS1    = Payload(UInt(RF_ADDR_WIDTH bits))
  val RS2    = Payload(UInt(RF_ADDR_WIDTH bits))
  val RD     = Payload(UInt(RF_ADDR_WIDTH bits))
  val FUNCT3 = Payload(Bits(3 bits))
  val FUNCT7 = Payload(Bits(7 bits))
  // Sign-extended immediate (U/J already shifted into place), ready to feed the ALU / branch unit.
  val IMM = Payload(Bits(Global.XLEN bits))
  // Immediate *format* (I/S/B/U/J/NONE). Only carried when `deriveImmFromWord` is on: the 3-bit format
  // travels decode→regRead in place of the 32-bit IMM, which is then rebuilt at the consumer stage from
  // the (already-carried) `Fetch.WORD`. See DecoderPlugin.
  val IMM_TYPE = Payload(Rv32i.ImmType())
  // True when the fetched word is a legal RV32I (or enabled-extension) instruction.
  val LEGAL = Payload(Bool())
}
