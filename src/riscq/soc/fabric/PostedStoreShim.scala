package riscq.soc.fabric

import spinal.core._
import spinal.lib._
import riscq.riscv.misc.{DataMemBus, DataMemBusParam, DataMemCmd}

/**
 * Data-bus **posted-store adapter** on the core's simplified [[DataMemBus]] — spliced between the
 * [[riscq.riscv.execute.LsuPlugin]] and the `DataMemBusToTilelink` bridge inside [[RiscqFiber]]. It
 * leaves the verified LSU untouched — the core keeps its plain blocking, single-outstanding contract
 * (send `cmd`, wait for `rsp`) — but satisfies a **store** locally so it retires fast, making writes
 * effectively non-blocking:
 *
 *  - A read (`cmd.write = False`) is forwarded transparently and returns the slave's real `rsp`.
 *  - A write is dropped into a small store buffer and **acked locally in one cycle**, so the CPU store
 *    commits next cycle while the write drains downstream in the background.
 *
 * Program order is preserved by the buffer: a read is held until the buffer is empty, so it can never
 * overtake a not-yet-written store (store→load RAW stays correct — the read is only forwarded after the
 * prior store's ack, i.e. after the slave committed the write). One transaction is kept outstanding
 * downstream, so a returning `rsp` belongs unambiguously to the last issued `cmd` (a reg remembers
 * read-vs-write).
 *
 * Because the upstream master is **blocking single-outstanding** (the LSU issues one access and halts
 * until its `rsp`), the local write-ack and a forwarded read response are never live in the same cycle
 * — the master is idle of writes whenever a read is in flight — so `up.rsp` stays a backpressure-free
 * [[Flow]] (no arbiter needed). A returning store ack from downstream is swallowed.
 *
 * Caveat: posted stores relax store→store ordering across *different* downstream slaves (the early ack
 * lets the CPU proceed before the write lands). Same-slave order and store→load RAW are preserved.
 *
 * Standalone `Component` so it can be unit-tested in isolation (drive `io.up`, model `io.down` with a
 * golden memory — see `PostedStoreShimSim`).
 *
 * @param bufDepth store-buffer depth — the number of posted stores in flight before the CPU
 *                 back-pressures.
 */
case class PostedStoreShim(p: DataMemBusParam, bufDepth: Int = 4) extends Component {
  val io = new Bundle {
    val up   = slave(DataMemBus(p))
    val down = master(DataMemBus(p))
  }

  val isWrite = io.up.cmd.write

  // ── store buffer: the full cmd of each accepted write, drained downstream in order ──
  val sbuf = StreamFifo(DataMemCmd(p), bufDepth, forFMax = true)

  // ── one transaction outstanding downstream; `inflightIsRead` remembers its kind so the returning
  //    `rsp` is routed (read → forward to up.rsp, write → swallow). One `down.cmd` is issued at a time,
  //    so its single `down.rsp` is unambiguous. ──
  val busy           = RegInit(False)
  val inflightIsRead = RegInit(False)
  val canIssue       = !busy

  val drainStore = sbuf.io.pop.valid                              // a buffered store is waiting to go out
  val fwdRead    = !drainStore && io.up.cmd.valid && !isWrite    // a read goes out only when buffer empty

  io.down.cmd.valid   := canIssue && (drainStore || fwdRead)
  io.down.cmd.payload := drainStore ? sbuf.io.pop.payload | io.up.cmd.payload
  sbuf.io.pop.ready   := canIssue && drainStore && io.down.cmd.ready

  when(io.down.cmd.fire) { busy := True; inflightIsRead := !drainStore }
  when(io.down.rsp.valid) { busy := False }

  // ── push an accepted write into the buffer; ack it locally one cycle later (mirrors the slave's
  //    single-cycle response latency). The push back-pressures the CPU only when the buffer is full. ──
  sbuf.io.push.valid   := io.up.cmd.valid && isWrite
  sbuf.io.push.payload := io.up.cmd.payload

  // up.cmd.ready: a write fires when buffered; a read fires when forwarded downstream
  io.up.cmd.ready := isWrite ? sbuf.io.push.ready | (canIssue && !drainStore && io.down.cmd.ready)

  // up.rsp: the synthetic write-ack (registered one cycle after accept) OR'd with the forwarded read
  // response — mutually exclusive by the blocking-master invariant above, so a plain OR is safe.
  val writeAck = RegNext(io.up.cmd.fire && isWrite) init (False)
  val readRsp  = io.down.rsp.valid && inflightIsRead
  io.up.rsp.valid := writeAck || readRsp
  io.up.rsp.data  := io.down.rsp.data    // meaningful only for a read; a write ignores rsp.data
}
