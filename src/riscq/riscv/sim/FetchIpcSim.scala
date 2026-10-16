package riscq.riscv.sim

import spinal.core._
import spinal.core.sim._
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.fetch.FetchPlugin

import scala.collection.mutable

/**
 * Fetch-IPC testbench: prove the pipelined fetch sustains **one committed instruction per
 * cycle** even when the instruction memory has multi-cycle latency.
 *
 * The memory model here is deliberately *not* the random `MemoryAgent` (whose latency varies and
 * occasionally spikes past the buffer depth). It is a hand-written, **fixed-latency,
 * always-accepting** Tilelink slave on the `iBus`: it accepts a `Get` every cycle and returns its
 * word exactly `L` cycles later, echoing the request's source id. With the fetch placed so
 * `fetchLatency >= L` (here L = 3, fetchLatency = 3 ⇒ fetchDataAt = 3, idCount = 4), every Get issued at the
 * fork has its response ready by the time the slot reaches the join — so after the one-time
 * cold-fill the join commits a word every cycle (IPC = 1). The old single-outstanding fetch would
 * have committed one word every L cycles (IPC ≈ 1/L) on the same memory.
 *
 * Run with `mill runMain riscq.riscv.sim.FetchIpcSim`.
 */
object FetchIpcSim extends App {
  val L       = 3 // fixed instruction-memory latency, in cycles
  // RISCQ_FETCH_PC_WIDTH=N narrows the carried PC (IPC-neutral); the 16KB region (memWords 1<<12) fits N=14.
  val fetchPcWidth = sys.env.get("RISCQ_FETCH_PC_WIDTH").map(_.toInt).orElse(RiscqParam().fetchPcWidth)
  val param   = RiscqParam(fetchLatency = 3, memWords = 1 << 12, fetchPcWidth = fetchPcWidth)
  val base    = param.resetVector.toLong
  val pattern = BigInt("c0de0000", 16) // word[i] = pattern + i, a recognisable instruction stand-in
  val image   = Array.tabulate(param.memWords)(i => pattern + i)
  def word(addr: Long): BigInt = image(((addr - base) / 4).toInt)

  SimConfig.withFstWave.compile(Riscq(param.fetchPlugins())).doSim(seed = 42) { dut =>
    val iBus = dut.host[FetchPlugin].logic.iBus
    val join = dut.host[FetchPlugin].logic.join

    // ---- Fixed-latency, always-accepting instruction memory on the iBus. ----
    // A request accepted at edge t is answered at edge t+L; responses are kept in order (one Get
    // per cycle ⇒ at most one due per cycle). d.ready is held True by the core, so a presented D
    // always fires next edge.
    iBus.a.ready  #= true
    iBus.d.valid  #= false
    val pend = mutable.Queue[(Long, BigInt, BigInt)]() // (dueEdge, source, data)
    var t    = 0L
    dut.clockDomain.onSamplings {
      if (iBus.a.valid.toBoolean && iBus.a.ready.toBoolean) { // a Get fired this edge
        pend.enqueue((t + L, iBus.a.source.toBigInt, word(iBus.a.address.toLong)))
      }
      if (iBus.d.valid.toBoolean && iBus.d.ready.toBoolean) pend.dequeue() // the presented D fired
      iBus.d.valid #= false                               // present the response due next cycle
      if (pend.nonEmpty && pend.front._1 == t + 1) {
        iBus.d.valid  #= true
        iBus.d.source #= pend.front._2
        iBus.d.data   #= pend.front._3
      }
      t += 1
    }

    dut.clockDomain.forkStimulus(10)

    // ---- Observe committed fetches and their cycle indices. ----
    val commitCycle = mutable.ArrayBuffer[Long]()
    val commitPc    = mutable.ArrayBuffer[BigInt]()
    val commitWord  = mutable.ArrayBuffer[BigInt]()
    val target      = 256
    var cycle       = 0L
    while (commitCycle.length < target && cycle < 4000) {
      dut.clockDomain.waitSampling()
      if (join.dbgFiring.toBoolean) {
        commitCycle += cycle
        commitPc    += join.dbgPc.toBigInt
        commitWord  += join.dbgWord.toBigInt
      }
      cycle += 1
    }
    assert(commitCycle.length == target, s"only ${commitCycle.length} fetches committed in $cycle cycles")

    // Correctness: each committed word matches its PC, PCs stride by 4.
    for (i <- commitPc.indices) {
      assert(commitWord(i) == word(commitPc(i).toLong),
        s"WORD mismatch at commit $i: got 0x${commitWord(i).toString(16)} for pc 0x${commitPc(i).toString(16)}")
      if (i > 0) assert(commitPc(i) == commitPc(i - 1) + 4,
        s"PC stride wrong at commit $i: 0x${commitPc(i - 1).toString(16)} -> 0x${commitPc(i).toString(16)}")
    }

    // IPC: after a short warm-up (cold-fill), a commit lands every single cycle (gap == 1).
    val warmup = 16
    for (i <- warmup until commitCycle.length) {
      assert(commitCycle(i) == commitCycle(i - 1) + 1,
        s"fetch bubble: commit $i at cycle ${commitCycle(i)} follows ${commitCycle(i - 1)} (gap " +
          s"${commitCycle(i) - commitCycle(i - 1)}), expected one commit per cycle under latency $L")
    }

    val steady = commitCycle.length - warmup
    val span   = commitCycle.last - commitCycle(warmup) + 1
    val ipc    = steady.toDouble / span
    assert(ipc == 1.0, f"steady-state IPC $ipc%.3f != 1.0")
    println(
      f"[FetchIpcSim] PASS  latency $L cyc, idCount ${dut.host[FetchPlugin].logic.idCount}: " +
        f"$target fetches, steady-state IPC = $ipc%.3f (one instruction per cycle)"
    )
    simSuccess()
  }
}
