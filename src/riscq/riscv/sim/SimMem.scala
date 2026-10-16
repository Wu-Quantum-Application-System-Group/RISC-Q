package riscq.riscv.sim

import spinal.core.ClockDomain
import spinal.core.sim._
import spinal.lib.bus.tilelink.sim.MemoryAgent
import spinal.lib.sim.SparseMemory
import riscq.riscv.Riscq
import riscq.riscv.execute.LsuPlugin
import riscq.riscv.fetch.FetchPlugin
import riscq.riscv.misc.DataMemBus

import scala.collection.mutable

/**
 * Sim memory harness for the core. Attaches a Tilelink `MemoryAgent` to the core's `iBus` and a small
 * [[DataMemBus]] model to the LSU's `dBus`, both over a shared `SparseMemory`, so fetch / load / store
 * all hit the same image. The data model answers each command after a random 1..3-cycle latency —
 * which is exactly what exercises the LSU's halt / pipeline back-pressure.
 *
 * Two entry points: build the memory from a word image (the directed sims), or wire it to a
 * caller-built memory already preloaded from an ELF (the rv32ui-p test harness).
 *
 * Call inside `doSim` after `forkStimulus`. Returns the shared memory so the testbench can
 * cross-check stores at the end (and a golden model can read the same image for loads).
 */
object SimMem {
  /** Build a memory from `image` (word `i` lives at `base + 4*i`) and wire the model(s). */
  def apply(dut: Riscq, base: Long, image: Array[BigInt], seed: Long = 0): SparseMemory = {
    val mem = SparseMemory(seed)
    for (i <- image.indices) mem.writeInt(base + 4L * i, image(i).toInt)
    wire(dut, mem, seed)
    mem
  }

  /** Wire the model(s) to a caller-built memory (e.g. one preloaded from an ELF). */
  def apply(dut: Riscq, mem: SparseMemory, seed: Long): SparseMemory = {
    wire(dut, mem, seed)
    mem
  }

  private def wire(dut: Riscq, mem: SparseMemory, seed: Long): Unit = {
    val cd = dut.clockDomain
    new MemoryAgent(dut.host[FetchPlugin].logic.iBus, cd, seed = seed, memArg = Some(mem))(null)
    dut.host.get[LsuPlugin].foreach { lsu =>
      driveData(lsu.logic.dBus, mem, cd, seed)
    }
  }

  /**
   * Sim model for the simplified single-outstanding [[DataMemBus]]: accept the command, apply a masked
   * write (or read the word), then answer on `rsp` after a random 1..3-cycle latency. The LSU keeps at
   * most one access in flight, so a single pending response is enough; a write is acked the same way
   * (its `rsp.data` is don't-care, mirroring a Tilelink AccessAck).
   */
  private def driveData(dBus: DataMemBus, mem: SparseMemory, cd: ClockDomain, seed: Long): Unit = {
    val rng  = new scala.util.Random(seed ^ 0x5101L)
    val pend = mutable.Queue[(Long, BigInt)]() // (dueEdge, word) — at most one outstanding
    var t    = 0L

    dBus.cmd.ready #= true                      // always accept the one in-flight command
    dBus.rsp.valid #= false

    cd.onSamplings {
      if (dBus.cmd.valid.toBoolean && dBus.cmd.ready.toBoolean) {
        val addr = dBus.cmd.address.toLong & ~3L
        if (dBus.cmd.write.toBoolean) {
          val data = dBus.cmd.data.toBigInt
          val mask = dBus.cmd.mask.toInt
          var i = 0
          while (i < 4) {
            if ((mask & (1 << i)) != 0) mem.write(addr + i, (((data >> (8 * i)) & 0xff).toInt).toByte)
            i += 1
          }
        }
        val word = BigInt(mem.readInt(addr).toLong & 0xffffffffL) // don't-care for a write
        pend.enqueue((t + 1 + rng.nextInt(3), word))              // 1..3-cycle response latency
      }

      dBus.rsp.valid #= false
      if (pend.nonEmpty && pend.front._1 == t + 1) {
        dBus.rsp.valid #= true
        dBus.rsp.data  #= pend.front._2
        pend.dequeue()
      }
      t += 1
    }
  }
}
