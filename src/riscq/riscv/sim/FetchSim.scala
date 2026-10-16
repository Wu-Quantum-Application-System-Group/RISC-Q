package riscq.riscv.sim

import spinal.core._
import spinal.core.sim._
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.fetch.FetchPlugin

import scala.collection.mutable.ArrayBuffer

/**
 * Instruction-fetch testbench: fetch goes over a Tilelink `iBus` driven by a `MemoryAgent`
 * (variable latency). Preloads a recognisable pattern, then checks that each word *committed*
 * by the fetch join matches the word the committing PC addressed, and that committed PCs walk
 * sequentially by +4 (no branches in this sub-core, so fetch is in order).
 *
 * Progress is per-commit, not per-cycle — the pipeline stalls while a `Get` is outstanding.
 *
 * Run with `mill runMain riscq.riscv.sim.FetchSim`.
 */
object FetchSim extends App {
  val param   = RiscqParam(memWords = 256)
  val base    = param.resetVector.toLong
  val pattern = BigInt("10000000", 16) // word[i] = pattern + i
  val image   = Array.tabulate(param.memWords)(i => pattern + i)

  SimConfig.withFstWave.compile(Riscq(param.fetchPlugins())).doSim(seed = 42) { dut =>
    dut.clockDomain.forkStimulus(10)
    SimMem(dut, base, image)

    val join = dut.host[FetchPlugin].logic.join

    val pcs   = ArrayBuffer[BigInt]()
    val words = ArrayBuffer[BigInt]()
    var cycle = 0
    while (pcs.length < 16 && cycle < 2000) {
      dut.clockDomain.waitSampling()
      cycle += 1
      if (join.dbgFiring.toBoolean) {
        pcs   += join.dbgPc.toBigInt
        words += join.dbgWord.toBigInt
      }
    }
    assert(pcs.length == 16, s"only ${pcs.length} fetches committed in $cycle cycles")

    // Each committed word matches the word its PC addressed.
    for (i <- pcs.indices) {
      val idx      = ((pcs(i) - base) / 4).toInt
      val expected = pattern + idx
      assert(words(i) == expected,
        s"WORD mismatch at commit $i: got 0x${words(i).toString(16)} expected 0x${expected.toString(16)} " +
          s"for pc 0x${pcs(i).toString(16)}")
    }
    // Sequential fetch: PC strides by 4 between commits.
    for (i <- 1 until pcs.length) {
      assert(pcs(i) == pcs(i - 1) + 4,
        s"PC stride wrong at commit $i: 0x${pcs(i - 1).toString(16)} -> 0x${pcs(i).toString(16)}")
    }

    println(
      s"[FetchSim] PASS  ${pcs.length} fetches over Tilelink in $cycle cycles, " +
        s"pc 0x${pcs.head.toString(16)} .. 0x${pcs.last.toString(16)}, " +
        s"first word 0x${words.head.toString(16)}"
    )
    simSuccess()
  }
}
