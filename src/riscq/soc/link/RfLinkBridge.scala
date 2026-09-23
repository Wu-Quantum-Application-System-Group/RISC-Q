package riscq.soc.link

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.bus.misc.SizeMapping

/**
 * Core-side posted-write funnel — a tiny TileLink slave mapped over the core's whole RF window. It is
 * **write-only**: every accepted `Put` is **acked locally** in one cycle (`bus.d << rsp.stage()`,
 * AccessAck, no data) so the CPU store retires next cycle and its bus arc stays short and in the core
 * region, while a narrow ordered `Flow(RfCmd){address, data}` carries the write **posted** down the
 * long link.
 *
 * Because the ack is local and the down path is a `Flow` (no back-pressure), the link can be pipelined
 * to any length with plain `RegNext` stages; the lead-time scheduler absorbs the constant latency. No
 * Get/read is supported here — RF reads (the readout decoder result) return on a separate up-`Flow`
 * into a core-local [[ReadoutResultSink]], and the control-block reads are core-local.
 *
 * @param rfAddrWidth byte-address width of the RF window (the `RfCmd.address` field).
 */
case class RfLinkBridge(rfAddrWidth: Int) extends Area {
  val up  = Node.up()
  val cmd = Flow(RfCmd(rfAddrWidth))   // ordered posted-write stream → far-side demux

  val logic = Fiber build new Area {
    // write-only window: advertise only single-word Put (no Get) so the fabric never routes a read here.
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = rfAddrWidth,
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

    // posted write out: one RfCmd per accepted Put, in order (single path = no reordering).
    cmd.valid           := bus.a.fire
    cmd.payload.address := bus.a.address.resize(rfAddrWidth)
    cmd.payload.data    := bus.a.data
  }
}

object RfLink {
  /**
   * Demux a posted [[RfCmd]] stream to one sub-window: valid only when the address falls in
   * `[base, base+size)`, rebased to the window (low `outWidth` bits). A `Flow` has no back-pressure,
   * so this is pure combinational routing — no arbiter, no collision (the far-side channels are
   * independent). Used to fan the bridge's single stream to each `PulseParamBuffer` / channel.
   */
  def demux(cmd: Flow[RfCmd], base: BigInt, size: BigInt, outWidth: Int): Flow[RfCmd] = {
    val out = Flow(RfCmd(outWidth))
    out.valid           := cmd.valid && SizeMapping(base, size).hit(cmd.payload.address)
    out.payload.address := (cmd.payload.address - base).resize(outWidth)
    out.payload.data    := cmd.payload.data
    out
  }

  /** Pipeline a posted stream by `depth` plain `RegNext` stages — the timing-insensitive long-haul
   *  link. `depth = 0` is identity. */
  /**
   * The non-local half of a core's posted stream (specs/cross-core/02 §3.2): beats whose node
   * (`address >> 16`) is at or past `localNodes` are system puts for the board hub, not the core's own
   * channels. The address is passed through untouched — the hub dispatches on the full `{node, offset}`.
   */
  def nonLocal(cmd: Flow[RfCmd], localNodes: Int): Flow[RfCmd] = {
    val out = cloneOf(cmd)
    out.valid   := cmd.valid && (cmd.payload.address >> 16) >= localNodes
    out.payload := cmd.payload
    out
  }

  def pipe[T <: Data](flow: Flow[T], depth: Int): Flow[T] =
    (0 until depth).foldLeft(flow)((f, _) => f.stage())
}
