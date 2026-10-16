package riscq.soc.fabric

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.fabric.Node
import riscq.memory.Bram

/**
 * A bank of `num` clock-crossing [[Bram]]s, each with its host-side (`slowPort`) read/write port
 * exposed as its own Tilelink slave on one `up` node (consecutive `step`-sized windows). The
 * device-side (`fastPort`) is left for the caller to wire to the datapath.
 *
 * Drop-in sibling of [[DualClockRamFiber]]: same shape and ports, but the underlying storage is the
 * Xilinx HDL-template blackbox [[Bram]] (inferred from `BramBlackBox.v`) instead of a SpinalHDL `Mem`.
 * `Bram`'s `highPerformance` output-register select maps onto `withOutReg` (read latency 1 vs 2), and
 * since it applies to both ports the host side's latency matches the `TileLinkMemReadWriteFiber`'s
 * `withOutReg` reorder-buffer depth.
 *
 * @param hostCd  clock domain of the host (`slowPort`) side and the Tilelink fabric here.
 * @param dspCd   clock domain of the device (`fastPort`) side.
 */
case class BramFiber(
    num: Int,
    width: Int,
    depth: Int,
    hostCd: ClockDomain,
    dspCd: ClockDomain,
    withOutReg: Boolean = true
) extends Area {
  val up = hostCd(Node())
  val rams = List.fill(num)(
    Bram(
      Bits(width bits),
      depth           = depth,
      slowCd          = hostCd,
      fastCd          = dspCd,
      outReg = withOutReg
    )
  )

  val step = 1 << log2Up(width * depth / 8)
  val ramFibers = for (i <- 0 until num) yield new Area {
    val ramFiber = hostCd(TileLinkMemReadWriteFiber(rams(i).slowPort, withOutReg = withOutReg))
    ramFiber.up at SizeMapping(i * step, step) of up
  }
}
