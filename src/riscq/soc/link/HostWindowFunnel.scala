package riscq.soc.link

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4Config, Axi4WriteOnly}

/**
 * The shared host-window funnel (specs/software/22 §2.2) — the whole "DMA engine": a round-robin
 * arbiter over the per-core [[HostCmd]] streams, one 40-bit address adder and a **write-only, single
 * beat** AXI4 master that drops each store into the PS DDR4 at
 *
 * {{{ base + (core << offsetWidth) + offset }}}
 *
 * No descriptors, no bursts, no read channel. Because the per-core slices are adjacent and
 * `offsetWidth`-aligned by construction, `core ## offset` *is* the buffer-relative address, so the
 * formula costs exactly one adder (registered by the `stage()` below); `base` therefore only needs page
 * alignment, not window alignment.
 *
 * Ordering: every write carries the same AXI ID, so the DDRC completes them in order and a core's
 * stores land in program order (one FIFO per core upstream, one ID here). Across cores nothing needs
 * ordering — each core owns a disjoint slice. `b` is accepted and discarded: the window is inside the
 * host buffer by construction, so there is no error path, and completion is posted (spec §2.4 — the
 * host reads the buffer from Python after `poll_done`, never from compiled code).
 *
 * `enable` is the reset-state guard: while it is low the arbiter output is **held**, so a core that
 * stores to the window before the host has programmed `base` stalls visibly instead of writing DDR
 * address 0 — the Linux kernel's memory.
 *
 * Lives in the host clock domain (100 MHz), outside every core's hard band.
 *
 * @param coreNum     number of qubit cores funnelled here.
 * @param offsetWidth per-core window width (24 ⇒ 16 MB).
 * @param addressWidth PS physical address width (40 on the ZynqMP HP ports).
 */
case class HostWindowFunnel(coreNum: Int, offsetWidth: Int = 24, addressWidth: Int = 40) extends Component {
  val io = new Bundle {
    val cmd    = Vec(slave Stream (HostCmd(offsetWidth)), coreNum)
    val base   = in UInt (addressWidth bits)   // PS physical address of the host buffer
    val enable = in Bool ()                    // 0 ⇒ hold (reset state, `base` not programmed yet)
    val axi    = master(Axi4WriteOnly(HostWindowFunnel.axiConfig(addressWidth)))
  }

  /** One accepted store, addressed. */
  case class HostWrite() extends Bundle {
    val addr = UInt(addressWidth bits)
    val data = Bits(32 bits)
    val strb = Bits(4 bits)
  }

  val arbiter = new StreamArbiterFactory().roundRobin.buildOn(io.cmd)
  // the winning input index IS the core id (`io.cmd(i)` is core i's stream).
  val core = if (coreNum > 1) arbiter.io.chosen else U(0, 1 bits)

  val write = arbiter.io.output.haltWhen(!io.enable).translateWith {
    val p = HostWrite()
    p.addr := io.base + (core @@ arbiter.io.output.payload.offset).resize(addressWidth)
    p.data := arbiter.io.output.payload.data
    p.strb := arbiter.io.output.payload.strb
    p
  }.stage()                                    // register the 40-bit adder

  // AXI needs `aw` and `w` accepted independently, so fork the one command onto both channels.
  val (awCmd, wCmd) = StreamFork2(write)

  io.axi.aw.arbitrationFrom(awCmd)
  io.axi.aw.addr  := awCmd.payload.addr
  io.axi.aw.id    := 0                         // one ID ⇒ the DDRC keeps the writes in order
  io.axi.aw.len   := 0                         // single beat
  io.axi.aw.size  := log2Up(4)                 // 4 bytes
  io.axi.aw.setBurstINCR()
  io.axi.aw.cache := 0                         // plain non-coherent write on an HP (not HPC) port
  io.axi.aw.prot  := 0

  io.axi.w.arbitrationFrom(wCmd)
  io.axi.w.data := wCmd.payload.data
  io.axi.w.strb := wCmd.payload.strb
  io.axi.w.last := True

  io.axi.b.ready := True                       // accepted and discarded — no error path
}

object HostWindowFunnel {
  /** The `M_AXI_HOST` port shape: write-only, 32-bit data, 40-bit PS physical address, one ID, and no
   *  region/lock/qos (nothing on the path uses them — see spec §2.2's field table). */
  def axiConfig(addressWidth: Int = 40) = Axi4Config(
    addressWidth = addressWidth, dataWidth = 32, idWidth = 1,
    useRegion = false, useLock = false, useQos = false)
}
