package riscq.soc.link

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.Opcode
import spinal.lib.bus.tilelink.fabric.Node

/**
 * One posted store on its way to the host buffer: a **word-aligned** byte `offset` inside the core's
 * 16 MB host window, the 32-bit `data` and its byte-enable `strb`. The byte lane of a `sb`/`sh` rides
 * in `strb` (the data is already lane-aligned), so the AXI beat the funnel emits is always a legal
 * size-2 single transfer at an aligned address.
 */
case class HostCmd(offsetWidth: Int) extends Bundle {
  val offset = UInt(offsetWidth bits)
  val data   = Bits(32 bits)
  val strb   = Bits(4 bits)
}

/**
 * Core-side write-only funnel into the **host window** — the twin of [[RfLinkBridge]] for results
 * headed at the PS DDR4 (specs/software/22). A tiny TileLink slave mapped over the core's 16 MB window
 * (`0x4000_0000` in `RiscvSoc.dMemPortDec`): every accepted `Put` becomes exactly one ordered
 * `Stream(HostCmd)` beat that a clock-crossing FIFO carries to the shared [[HostWindowFunnel]].
 *
 * Three differences from [[RfLinkBridge]], all forced by the far side being real memory:
 *
 *   - **`Stream`, not `Flow`.** DDR refresh, Linux traffic on the HP port and the `enable` gate can all
 *     stall the far side, so the AccessAck is issued only when the command is *accepted*: a full FIFO
 *     withholds the ack and the CPU stalls on that store. Nothing is ever dropped.
 *   - **the byte mask travels** (`strb`), so `sb`/`sh` stores work.
 *   - **`Get` is refused** (not advertised, so the fabric never routes a load here) — results are
 *     write-only, exactly like the envelope banks.
 *
 * The core's contract is otherwise unchanged: `RiscqFiber`'s `PostedStoreShim` retires stores in one
 * cycle and drains them in order, so a window store costs the program nothing beyond the store itself
 * and store→store order **within a core** is preserved end to end (one FIFO, one AXI ID).
 *
 * @param offsetWidth byte-address width of the window (24 ⇒ 16 MB per core).
 */
case class HostWindowBridge(offsetWidth: Int = 24) extends Area {
  val up  = Node.up()
  val cmd = Stream(HostCmd(offsetWidth))   // ordered posted-write stream → CC FIFO → funnel

  val logic = Fiber build new Area {
    // write-only window: advertise only single-word Put (no Get). The LSU issues every store — byte,
    // half or word — as a WORD PutPartial with a narrow mask (`LsuPlugin.scala:148-150`), so one size
    // is all that is ever emitted.
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = offsetWidth,
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

    // Fork the accepted Put two ways — the local AccessAck and the outgoing command — and let BOTH gate
    // the A channel, so a stalled far side back-pressures the store instead of losing it. Neither
    // `cmd.ready` (a FIFO push) nor `rsp.ready` (an m2sPipe) depends on its own valid, so there is no
    // combinational loop.
    val rsp = cloneOf(bus.d)
    bus.a.ready := cmd.ready && rsp.ready
    cmd.valid   := bus.a.valid && rsp.ready
    rsp.valid   := bus.a.valid && cmd.ready

    rsp.opcode := Opcode.D.ACCESS_ACK()
    rsp.param  := 0
    rsp.source := bus.a.source
    rsp.sink   := 0
    rsp.size   := bus.a.size
    rsp.denied := False
    if (bus.p.withDataD) { rsp.data := 0; rsp.corrupt := False }  // put-only ⇒ no D data
    bus.d << rsp.stage()

    // Word-align the offset: the LSU already masks the low two address bits off, and clearing them here
    // makes the funnel's AXI beat provably aligned whatever master drives the window.
    val byteOffset = bus.a.address.resize(offsetWidth)
    cmd.payload.offset := byteOffset(offsetWidth - 1 downto 2) @@ U"00"
    cmd.payload.data   := bus.a.data
    cmd.payload.strb   := bus.a.mask
  }
}
