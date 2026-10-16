package riscq.soc.fabric

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.fabric.Node
import riscq.memory.Bram

/**
 * Write-only sibling of [[BramFiber]]: a bank of `num` clock-crossing [[Bram]]s whose host-side
 * (`slowPort`) is exposed as a **write-only** Tilelink slave via [[TileLinkMemWriteFiber]] — for the
 * memories the host only ever *loads* and never reads back (the gate / readout-drive / demod
 * pulse-envelope banks). The device-side (`fastPort`) is left for the caller to wire to the datapath,
 * exactly as in [[BramFiber]] (same `up` / `rams` handles, a drop-in swap).
 *
 * Two resources are saved versus the read/write [[BramFiber]]:
 *   - the host-side slave collapses from [[TileLinkMemReadWriteLogic]] (a 4-entry read-reorder buffer
 *     + elastic pipeline, there only to cover d-channel back-pressure on reads) down to the tiny
 *     combinational [[TileLinkMemWriteLogic]];
 *   - [[TileLinkMemWriteLogic]] bridges a narrow (32-bit) host bus into a wider RAM word by sub-word
 *     byte addressing, so the wide banks need NO fabric `WidthAdapter` — the `up` node wires straight
 *     to the 32-bit host region bus and each 4-byte host beat writes one addressed lane of the line.
 *
 * `outReg` maps onto [[Bram]]'s output-register select (device-side read latency 1 vs 2), matching the
 * datapath's `memLatency`; the unused slow-side read port (`douta`/`doutb`) is left dangling for
 * synthesis to trim.
 *
 * @param hostCd  clock domain of the host (`slowPort`) side and the Tilelink fabric here.
 * @param dspCd   clock domain of the device (`fastPort`) side.
 */
case class BramWriteFiber(
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
      depth  = depth,
      slowCd = hostCd,
      fastCd = dspCd,
      outReg = withOutReg
    )
  )

  val step = 1 << log2Up(width * depth / 8)
  val ramFibers = for (i <- 0 until num) yield new Area {
    // posted write flow driven by the write-only fiber, wired straight into the Bram's host port; the
    // host never reads an envelope back, so `slowPort.rdata` is ignored (`write` tied high).
    val slow  = rams(i).slowPort
    val flow  = Flow(MemWriteCmd(Bits(width bits), slow.address.getWidth, maskWidth = width / 8))
    val fiber = hostCd(TileLinkMemWriteFiber(flow))
    fiber.up at SizeMapping(i * step, step) of up

    slow.enable  := flow.valid
    slow.write   := True
    slow.address := flow.payload.address
    slow.wdata   := flow.payload.data
    slow.mask    := flow.payload.mask
  }
}
