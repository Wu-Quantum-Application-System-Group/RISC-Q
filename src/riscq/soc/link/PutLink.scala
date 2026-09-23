package riscq.soc.link

import spinal.core._
import spinal.lib._
import spinal.lib.bus.misc.SizeMapping

/**
 * The posted-write link. Every link that crosses a core's boundary carries the same beat, a [[Put]]:
 * the down-link from the core to its channels (out of the [[PutBridge]]), the up-link into the core's
 * inbox ([[EventLink]]) and the board hub's puts ([[PutHub]]). The links replace the wide, bidirectional
 * `tilelink.fabric` decode tree between a RISC-V core and its converter-edge DSP with pipelined,
 * unidirectional, timing-insensitive `Flow`s — so the core can be floorplanned far from what it controls
 * (every link stage is a plain `RegNext`, absorbed by the lead-time scheduler).
 */

/**
 * One posted register write. A narrow, ordered `Flow` payload: the byte `address` (offset within the
 * receiver's window) plus the 32-bit write `data`. The far side decodes it by address — a channel
 * exactly as the old `SlaveFactory` did (16-bit fields packed at bit 16), an inbox sink into its
 * register window. Posted ⇒ no ack on this path; on the down-link the `PutBridge` next to the core
 * terminates the CPU's TileLink D channel locally.
 */
case class Put(addrWidth: Int) extends Bundle {
  val address = UInt(addrWidth bits)
  val data    = Bits(32 bits)
}

object PutLink {
  /**
   * Demux a posted [[Put]] stream to one sub-window: valid only when the address falls in
   * `[base, base+size)`, rebased to the window (low `outWidth` bits). A `Flow` has no back-pressure,
   * so this is pure combinational routing — no arbiter, no collision (the far-side channels are
   * independent). Used to fan the bridge's single stream to each `PulseParamBuffer` / channel.
   */
  def demux(cmd: Flow[Put], base: BigInt, size: BigInt, outWidth: Int): Flow[Put] = {
    val out = Flow(Put(outWidth))
    out.valid           := cmd.valid && SizeMapping(base, size).hit(cmd.payload.address)
    out.payload.address := (cmd.payload.address - base).resize(outWidth)
    out.payload.data    := cmd.payload.data
    out
  }

  /**
   * The non-local half of a core's posted stream (specs/cross-core/02 §3.2): beats whose node
   * (`address >> 16`) is at or past `localNodes` are system puts for the board hub, not the core's own
   * channels. The address is passed through untouched — the hub dispatches on the full `{node, offset}`.
   */
  def nonLocal(cmd: Flow[Put], localNodes: Int): Flow[Put] = {
    val out = cloneOf(cmd)
    out.valid   := cmd.valid && (cmd.payload.address >> 16) >= localNodes
    out.payload := cmd.payload
    out
  }

  /** Pipeline a posted stream by `depth` plain `RegNext` stages — the timing-insensitive long-haul
   *  link. `depth = 0` is identity. */
  def pipe[T <: Data](flow: Flow[T], depth: Int): Flow[T] =
    (0 until depth).foldLeft(flow)((f, _) => f.stage())
}
