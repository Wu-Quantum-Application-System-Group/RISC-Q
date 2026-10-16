package riscq.soc.fabric

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.fabric.Node
import riscq.memory.DualClockRam

/**
 * A bank of `num` clock-crossing [[DualClockRam]]s, each with its host-side (`slowPort`) read/write
 * port exposed as its own Tilelink slave on one `up` node (consecutive `step`-sized windows). The
 * device-side (`fastPort`) is left for the caller to wire to the datapath. Ported from the RISC-Q
 * reference (`riscq.soc.Misc`) for the per-core instruction RAM, pulse-envelope memory and readout
 * buffers.
 *
 * @param hostCd  clock domain of the host (`slowPort`) side and the Tilelink fabric here.
 * @param dspCd   clock domain of the device (`fastPort`) side.
 */
case class DualClockRamFiber(
    num: Int,
    width: Int,
    depth: Int,
    hostCd: ClockDomain,
    dspCd: ClockDomain,
    withOutReg: Boolean = true
) extends Area {
  val up = hostCd(Node())
  val rams = List.fill(num)(
    DualClockRam(
      width          = width,
      depth          = depth,
      slowCd         = hostCd,
      fastCd         = dspCd,
      withOutRegFast = withOutReg,
      withOutRegSlow = true
    )
  )

  val step = 1 << log2Up(width * depth / 8)
  val ramFibers = for (i <- 0 until num) yield new Area {
    val ramFiber = hostCd(TileLinkMemReadWriteFiber(rams(i).slowPort, withOutReg = withOutReg))
    ramFiber.up at SizeMapping(i * step, step) of up
  }
}
