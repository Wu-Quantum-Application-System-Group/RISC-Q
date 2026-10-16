package riscq.riscv

import spinal.core._
import spinal.lib.misc.plugin.FiberPlugin

/**
 * Parameter trampoline: publishes the globally shared constants that other plugins read
 * from `Global`. Kept as its own plugin (like VexiiRiscv's `RiscvPlugin`) so the config
 * has a single, obvious source of truth.
 */
class RiscqPlugin(xlen: Int, fetchPcWidth: Int, pcBase: BigInt) extends FiberPlugin {
  val logic = during setup new Area {
    Global.XLEN.set(xlen)
    // fetchPcWidth == xlen ⇒ full-width PC (baseline); < xlen ⇒ narrowed, with pcBase the constant high
    // bits the reconstruction sites OR back in.
    Global.FETCH_PC_WIDTH.set(fetchPcWidth)
    Global.PC_BASE.set(pcBase)
  }
}
