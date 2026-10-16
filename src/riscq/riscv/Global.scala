package riscq.riscv

import spinal.core._
import spinal.lib.misc.database.Database.blocking
import spinal.lib.misc.pipeline.Payload

/**
 * Globally shared elaboration-time constants and pipeline payloads.
 *
 * Mirrors VexiiRiscv's `Global`: values are published once (by `RiscqPlugin`) into the
 * fiber `Database` and read everywhere else. `blocking[T]` makes a reader fiber sleep
 * until the value is set, so plugin elaboration order doesn't matter.
 */
object Global extends AreaRoot {
  // Elaboration-time constants (set by RiscqPlugin)
  val XLEN = blocking[Int]
  // Fetch / code-address width: the width of the architectural PC and of every code-address value the
  // pipeline carries (predicted next-PC, branch/jump targets, mepc/mtvec, BTB hint state). Split from the
  // data-address width (XLEN) so the fetch PC can be narrowed independently of the LSU — whose data bus
  // spans the full 32-bit SoC map and must stay XLEN-wide. == XLEN today.
  val FETCH_PC_WIDTH = blocking[Int]
  // Constant high bits of every in-region code address = resetVector & ~(2^FETCH_PC_WIDTH − 1). 0 when the
  // fetch PC is full-width. Prepended to the carried PC offset to rebuild a full XLEN address (see fullPc).
  val PC_BASE = blocking[BigInt]

  // Pipeline payloads that flow through the stages
  val PC = Payload(UInt(FETCH_PC_WIDTH bits))

  /**
   * Rebuild a full XLEN code address from a (possibly narrowed) fetch-PC offset. Identity when the fetch
   * PC is full-width (FETCH_PC_WIDTH == XLEN) so the baseline netlist is unchanged; otherwise `pcBase |
   * offset` — a constant OR (pcBase's low FETCH_PC_WIDTH bits are 0), zero logic. Used at the four visible
   * boundaries where software observes a full 32-bit address.
   */
  def fullPc(pc: UInt): UInt = {
    // Force the Int values out of the blocking handles (`==` on the handles is reference equality).
    val fetchW: Int = FETCH_PC_WIDTH
    val xlen: Int   = XLEN
    if (fetchW == xlen) pc else U(PC_BASE, xlen bits) | pc.resize(xlen)
  }
}
