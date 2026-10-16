package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.sim.{StreamMonitor, StreamReadyRandomizer}
import riscq.riscv.misc.DataMemBusParam
import riscq.soc.fabric.PostedStoreShim

import scala.collection.mutable
import scala.util.Random

/**
 * Unit sign-off for [[riscq.soc.fabric.PostedStoreShim]] (the posted-store adapter on the simplified
 * [[riscq.riscv.misc.DataMemBus]]).
 *
 * The testbench drives `io.up` exactly as the core's LSU would — strictly **single-outstanding** and
 * blocking (issue one `cmd`, wait for its `rsp`, then issue the next) — and models `io.down` as a
 * byte-masked golden memory with **randomised back-pressure** (`down.cmd.ready`) and **random response
 * latency** (`down.rsp`). It then fires a random read/write mix over a small address set (so store→load
 * RAW collisions are frequent) and checks every read against an architectural reference updated at
 * issue time. A final read-sweep proves every posted store eventually drained to memory correctly.
 *
 * What this proves: writes are acked (the CPU never deadlocks), reads return correct data, store→load
 * RAW is honoured (a read is held until the prior store has drained), and the buffer/back-pressure path
 * is sound.
 *
 *   mill runMain riscq.soc.sim.PostedStoreShimSim
 */
object PostedStoreShimSim extends App {
  val p        = DataMemBusParam(addressWidth = 32, dataWidth = 32)
  val base     = 0x80000000L
  val NWORDS   = 8                       // small ⇒ frequent same-address (RAW) collisions
  val bufDepth = 4

  def applyMask(orig: Long, data: Long, mask: Int): Long = {
    var w = orig
    for (b <- 0 until 4 if ((mask >> b) & 1) == 1) {
      val byte = (data >> (b * 8)) & 0xFF
      w = (w & ~(0xFFL << (b * 8))) | (byte << (b * 8))
    }
    w & 0xFFFFFFFFL
  }

  SimConfig.compile(PostedStoreShim(p, bufDepth)).doSim("rand", seed = 42) { dut =>
    dut.clockDomain.forkStimulus(10)

    // init
    dut.io.up.cmd.valid   #= false
    dut.io.down.rsp.valid #= false
    dut.clockDomain.waitSampling()

    val mem = Array.fill(NWORDS)(0L)     // the down-slave's memory
    val ref = Array.fill(NWORDS)(0L)     // architectural reference (updated at issue, program order)

    // watchdog: a deadlock would otherwise hang forever
    fork { dut.clockDomain.waitSampling(500000); simFailure("timeout / deadlock") }

    // ── down slave: capture every accepted command, respond after a random delay ──
    val cmdQ = mutable.Queue[(Boolean, Int, Long, Int)]() // (isWrite, wordIdx, data, mask)
    StreamReadyRandomizer(dut.io.down.cmd, dut.clockDomain)
    StreamMonitor(dut.io.down.cmd, dut.clockDomain) { c =>
      val wa = ((c.address.toLong - base) / 4).toInt
      cmdQ.enqueue((c.write.toBoolean, wa, c.data.toLong, c.mask.toInt))
    }
    val dRnd = new Random(1)
    fork {
      dut.io.down.rsp.valid #= false
      while (true) {
        while (cmdQ.isEmpty) dut.clockDomain.waitSampling()
        val (isWrite, wa, data, mask) = cmdQ.dequeue()
        for (_ <- 0 until dRnd.nextInt(3)) dut.clockDomain.waitSampling()
        val rdata = if (isWrite) { mem(wa) = applyMask(mem(wa), data, mask); 0L } else mem(wa)
        dut.io.down.rsp.valid #= true    // rsp is a Flow: a single-cycle pulse, the shim is always ready
        dut.io.down.rsp.data  #= rdata
        dut.clockDomain.waitSampling()
        dut.io.down.rsp.valid #= false
      }
    }

    // ── core side: strictly single-outstanding, blocking ──
    def issueCmd(isWrite: Boolean, wa: Int, data: Long, mask: Int): Unit = {
      dut.io.up.cmd.valid   #= true
      dut.io.up.cmd.write   #= isWrite
      dut.io.up.cmd.address #= base + 4L * wa
      dut.io.up.cmd.data    #= data
      dut.io.up.cmd.mask    #= mask
      dut.clockDomain.waitSamplingWhere(dut.io.up.cmd.ready.toBoolean)
      dut.io.up.cmd.valid #= false
    }
    def waitRsp(): Long = {
      dut.clockDomain.waitSamplingWhere(dut.io.up.rsp.valid.toBoolean)
      dut.io.up.rsp.data.toLong & 0xFFFFFFFFL
    }

    val rnd   = new Random(7)
    val iters = 3000
    for (i <- 0 until iters) {
      val wa = rnd.nextInt(NWORDS)
      if (rnd.nextBoolean()) {                       // read
        issueCmd(isWrite = false, wa, 0, 0xF)
        val got = waitRsp()
        assert(got == ref(wa), s"iter $i read@$wa got ${got.toHexString} exp ${ref(wa).toHexString}")
      } else {                                       // write
        val data = rnd.nextInt().toLong & 0xFFFFFFFFL
        val mask = 1 + rnd.nextInt(15)               // nonzero byte mask
        issueCmd(isWrite = true, wa, data, mask)
        waitRsp()                                     // local write-ack (value irrelevant)
        ref(wa) = applyMask(ref(wa), data, mask)
      }
    }

    // final sweep: every posted store must have drained correctly
    for (wa <- 0 until NWORDS) {
      issueCmd(isWrite = false, wa, 0, 0xF)
      val got = waitRsp()
      assert(got == ref(wa), s"final sweep @$wa got ${got.toHexString} exp ${ref(wa).toHexString}")
    }

    println(s"[PostedStoreShimSim] PASS — $iters random ops + ${NWORDS}-word drain sweep")
    simSuccess()
  }
}
