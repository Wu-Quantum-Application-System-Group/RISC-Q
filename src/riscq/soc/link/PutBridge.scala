package riscq.soc.link

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode
import spinal.lib.bus.tilelink.fabric.Node

/**
 * Core-side posted-write funnel — a tiny TileLink slave mapped over the core's whole put window. It is
 * **write-only**: every accepted TileLink `Put` is **acked locally** in one cycle (`bus.d << rsp.stage()`,
 * AccessAck, no data) so the CPU store retires next cycle and its bus arc stays short and in the core
 * region, while a narrow ordered `Flow(Put){address, data}` carries the write **posted** down the
 * long link.
 *
 * Because the ack is local and the down path is a `Flow` (no back-pressure), the link can be pipelined
 * to any length with plain `RegNext` stages; the lead-time scheduler absorbs the constant latency. No
 * Get/read is supported here — channel reports (the readout decoder result) return on a separate up-`Flow`
 * into a core-local [[ReadoutResultSink]], and the control-block reads are core-local.
 *
 * @param putAddrWidth byte-address width of the put window (the `Put.address` field).
 */
case class PutBridge(putAddrWidth: Int) extends Area {
  val up  = Node.up()
  val cmd = Flow(Put(putAddrWidth))   // ordered posted-write stream → far-side demux

  val logic = Fiber build new Area {
    // write-only window: advertise only single-word Put (no Get) so the fabric never routes a read here.
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = putAddrWidth,
      dataWidth    = 32,
      transfers = up.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          putFull    = tilelink.SizeRange(4),
          putPartial = tilelink.SizeRange(4)
        )
      )
    )
    up.s2m.none()

    val bus = up.bus
    // local 1-cycle AccessAck (mirrors SlaveFactory's `bus.d << rspAsync.stage()`).
    val rsp = cloneOf(bus.d)
    bus.a.ready  := rsp.ready
    rsp.valid    := bus.a.valid
    rsp.opcode   := Opcode.D.ACCESS_ACK()
    rsp.param    := 0
    rsp.source   := bus.a.source
    rsp.sink     := 0
    rsp.size     := bus.a.size
    rsp.denied   := False
    if (bus.p.withDataD) { rsp.data := 0; rsp.corrupt := False }  // put-only ⇒ no D data
    bus.d << rsp.stage()

    // posted write out: one Put per accepted TileLink Put, in order (single path = no reordering).
    cmd.valid           := bus.a.fire
    cmd.payload.address := bus.a.address.resize(putAddrWidth)
    cmd.payload.data    := bus.a.data
  }
}
