package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.Axi4WriteOnly
import spinal.lib.sim.{StreamDriver, StreamMonitor, StreamReadyRandomizer}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Sim-side model of what the ZCU216's `S_AXI_HP0_FPD` does with the host window's write-only AXI master:
 * accept `aw`/`w` with random stalls, answer `b`, and keep a **sparse byte memory** plus the ordered beat
 * log. Shared by [[HostWindowFunnelSim]] (the unit gate) and [[HostWindowCpuSim]] (the SoC gate).
 *
 * Every beat is checked to be a legal single transfer (`len = 0`, `size = 2`, `INCR`, `last`, one ID) as
 * it arrives — the funnel emits nothing else by construction.
 *
 * Construct inside `doSim` (it forks a collector thread).
 */
case class HostMemModel(axi: Axi4WriteOnly, cd: ClockDomain) {
  /** (address, data, strb) in AXI order. */
  val writes = ArrayBuffer[(BigInt, BigInt, BigInt)]()
  /** byte address → byte value, applied through the strobes. */
  val mem = mutable.Map[BigInt, Int]()

  private val awQ = mutable.Queue[BigInt]()
  private val wQ  = mutable.Queue[(BigInt, BigInt)]()
  private var pendingB = 0

  val awReady = StreamReadyRandomizer(axi.aw, cd)
  val wReady  = StreamReadyRandomizer(axi.w, cd)

  StreamMonitor(axi.aw, cd) { aw =>
    assert(aw.len.toInt == 0,   s"aw.len must be 0 (single beat), got ${aw.len.toInt}")
    assert(aw.size.toInt == 2,  s"aw.size must be 2 (4 bytes), got ${aw.size.toInt}")
    assert(aw.burst.toInt == 1, s"aw.burst must be INCR, got ${aw.burst.toInt}")
    assert(aw.id.toInt == 0,    s"aw.id must be 0 (one ID ⇒ in-order), got ${aw.id.toInt}")
    awQ += aw.addr.toBigInt
  }
  StreamMonitor(axi.w, cd) { w =>
    assert(w.last.toBoolean, "w.last must be set on every (single-beat) write")
    wQ += ((w.data.toBigInt, w.strb.toBigInt))
  }
  StreamDriver(axi.b, cd) { b =>
    if (pendingB > 0) { pendingB -= 1; b.id #= 0; b.resp #= 0; true } else false
  }

  // aw and w are separate channels; with one ID and single beats they pair up in issue order.
  fork {
    while (true) {
      cd.waitSampling()
      while (awQ.nonEmpty && wQ.nonEmpty) {
        val addr = awQ.dequeue()
        val (data, strb) = wQ.dequeue()
        writes += ((addr, data, strb))
        for (b <- 0 until 4 if ((strb >> b) & 1) == 1) mem(addr + b) = ((data >> (8 * b)) & 0xFF).toInt
        pendingB += 1
      }
    }
  }

  def clear(): Unit = { writes.clear(); mem.clear() }

  /** The beats addressed to `core`'s slice, rebased to a window offset and masked to the bytes each beat
   *  actually writes. */
  def beatsFor(base: BigInt, core: Int, offsetWidth: Int): Seq[(Int, BigInt, Int)] = {
    val winBytes = BigInt(1) << offsetWidth
    writes.collect {
      case (addr, data, strb) if ((addr - base) >> offsetWidth) == core =>
        (((addr - base) % winBytes).toInt, HostMemModel.maskWord(data, strb.toInt), strb.toInt)
    }
  }
}

object HostMemModel {
  /** Keep only the enabled byte lanes — the disabled ones are don't-care on AXI. */
  def maskWord(data: BigInt, strb: Int): BigInt =
    (0 until 4).filter(b => ((strb >> b) & 1) == 1)
      .foldLeft(BigInt(0))((acc, b) => acc | (((data >> (8 * b)) & 0xFF) << (8 * b)))
}
