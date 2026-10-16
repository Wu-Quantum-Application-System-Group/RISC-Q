package riscq.riscv.test

import spinal.core.sim._
import rvls.spinal.TraceBackend
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.csr.CsrPlugin
import riscq.riscv.execute.LsuPlugin
import riscq.riscv.fetch.{FetchPlugin, PcPlugin}
import riscq.riscv.regfile.RegFilePlugin

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Drives external trace consumers from the running core's whitebox signals: any number of
 * `rvls.spinal.TraceBackend`s (a `FileBackend` text trace today, an `RvlsBackend` Spike
 * lock-step once `librvls.so` is built) plus an optional Konata pipeline trace.
 *
 * riscq is **single-issue, in-order, one retire per cycle**, so this is a tiny fraction of
 * VexiiRiscv's probe: no fetch/decode/uop id rings, no reorder buffer, no multi-hart. Each
 * cycle it reads the one execute slot — and reconstructs the Konata stage timeline from a small
 * in-flight FIFO (instructions leave execute in fetch order; a redirect flushes everything
 * still younger). A retiring `ECALL`/`EBREAK`/illegal becomes a `trap`, everything else a
 * `commit`; `commitsCallbacks` lets a testbench watch the committed PC (e.g. the rv32ui-p
 * `pass`/`fail` symbols).
 */
class RiscqProbe(dut: Riscq, p: RiscqParam, hartId: Int = 0, kb: Option[konata.Backend] = None) {
  val backends         = ArrayBuffer[TraceBackend]()
  val commitsCallbacks = ArrayBuffer[(Int, Long) => Unit]()      // (hartId, committed PC)
  val storeCallbacks   = ArrayBuffer[(Long, Long) => Unit]()     // (address, data) of each committed store
  var checkLiveness    = true
  var livenessThreshold = 16000L

  // Whitebox handles (see the *Plugin `dbg*` simPublic signals).
  private val rf  = dut.host[RegFilePlugin].logic.exec
  private val fp  = dut.host[FetchPlugin].logic.join
  private val lsu = dut.host[LsuPlugin].logic.lsu
  private val csr = dut.host[CsrPlugin].logic.csr
  private val pcp = dut.host[PcPlugin].logic

  private val mask = (BigInt(1) << p.xlen) - 1
  private def pcOf(v: BigInt): Long = (v & mask).toLong

  // Spike stores integer registers sign-extended to 64 bits even in RV32 mode, so a write of a
  // negative-looking 32-bit value (bit 31 set) must be widened the same way before it's compared.
  private def xlenExtend(v: Long): Long = if (p.xlen == 32) (v << 32) >> 32 else v

  /** Register a backend and announce the (single) CPU, its flat RAM, and its reset PC to it. */
  def add(b: TraceBackend): this.type = {
    backends += b
    b.newCpuMemoryView(hartId, 16, 16)
    // ISA string for Spike must match the enabled extensions, else it decodes them as illegal.
    // The core is multiply-only (no divide), so the extension is Zmmul, not full M.
    val isa = s"RV${p.xlen}I" + (if (p.withMul) "_Zmmul" else "")
    b.newCpu(hartId, isa, "M", p.xlen, 0, hartId)
    // RVLS/Spike treats every address as MMIO and faults on any fetch/load/store outside a
    // declared region. riscq has a single flat RAM (the testbench's SparseMemory image), so
    // announce the whole XLEN space as one main-memory region (kind 0 = RAM).
    b.addRegion(hartId, 0, 0L, (BigInt(1) << p.xlen).toLong)
    b.setPc(hartId, p.resetVector.toLong)
    this
  }

  // ---- Konata reconstruction state ----
  private case class InFlight(fetchCycle: Long, pc: Long, word: Long)
  private val inflight       = mutable.Queue[InFlight]()
  private val konataThread   = kb.map(_.newThread())
  private var cycle          = 0L
  private var lastCommitCycle = 0L

  private def emitKonata(in: InFlight, endCycle: Long, committed: Boolean): Unit = {
    for (t <- konataThread; back <- kb) {
      val i = new konata.Instruction()
      i += new konata.Spawn(in.fetchCycle, hartId)
      i += new konata.Comment(in.fetchCycle, f"${in.pc}%08x : ${in.word}%08x")
      i += new konata.Stage(in.fetchCycle, "F")
      i += new konata.Stage(endCycle, "E")
      if (committed) i += new konata.Retire(endCycle) else i += new konata.Flush(endCycle)
      back.insert(i)
    }
  }

  dut.clockDomain.onSamplings {
    cycle += 1

    // A new instruction has cleared the fetch stage — record it for the Konata timeline.
    if (fp.dbgFiring.toBoolean) {
      inflight += InFlight(cycle, pcOf(fp.dbgPc.toBigInt), fp.dbgWord.toBigInt.toLong)
    }

    // The execute slot retired something this cycle.
    if (rf.dbgFiring.toBoolean) {
      val pc    = pcOf(rf.dbgPc.toBigInt)
      val instr = rf.dbgInstr.toBigInt.toLong
      val trap  = csr.dbgTrap.toBoolean
      val done  = if (inflight.nonEmpty) inflight.dequeue() else InFlight(cycle, pc, instr)

      if (trap) {
        backends.foreach(_.trap(hartId, false, csr.dbgCause.toInt))
        emitKonata(done, cycle, committed = false)
      } else {
        // Memory access (single outstanding ⇒ id 0).
        val memValid = lsu.dbgMemValid.toBoolean
        val isStore  = lsu.dbgIsStore.toBoolean
        if (memValid) {
          val a = lsu.dbgAddr.toBigInt.toLong; val len = lsu.dbgSize.toLong; val d = lsu.dbgData.toBigInt.toLong
          if (isStore) backends.foreach(_.storeExecute(hartId, 0, a, len, d))
          else         backends.foreach(_.loadExecute(hartId, 0, a, len, d))
          if (isStore) backends.foreach(_.storeCommit(hartId, 0))
          else         backends.foreach(_.loadCommit(hartId, 0))
        }
        // Integer rd write-back.
        if (rf.dbgWrite.toBoolean) backends.foreach(_.writeRf(hartId, 0, rf.dbgRd.toInt, xlenExtend(rf.dbgRdData.toBigInt.toLong)))
        // CSR read/write (rfKind 4).
        if (csr.dbgCsrValid.toBoolean) {
          val ca = csr.dbgCsrAddr.toInt
          backends.foreach(_.readRf(hartId, 4, ca, csr.dbgCsrRdata.toBigInt.toLong))
          if (csr.dbgCsrWrite.toBoolean) backends.foreach(_.writeRf(hartId, 4, ca, csr.dbgCsrWdata.toBigInt.toLong))
        }
        backends.foreach(_.commit(hartId, pc, instr))
        if (memValid && isStore) backends.foreach(_.storeBroadcast(hartId, 0))
        emitKonata(done, cycle, committed = true)
        commitsCallbacks.foreach(_(hartId, pc))
        if (memValid && isStore) {
          val a = lsu.dbgAddr.toBigInt.toLong; val d = lsu.dbgData.toBigInt.toLong
          storeCallbacks.foreach(_(a, d))
        }
      }
      lastCommitCycle = cycle
    }

    // A taken branch / jump / trap / mret flushed every younger in-flight instruction.
    if (pcp.redirectFire.toBoolean) {
      while (inflight.nonEmpty) emitKonata(inflight.dequeue(), cycle, committed = false)
    }

    // Konata only needs cycles up to the oldest still-in-flight instruction to be replayable.
    konataThread.foreach(_.cycleLock = inflight.headOption.map(_.fetchCycle).getOrElse(cycle))
    if ((cycle & 0x7FF) == 0) kb.foreach(_.flush())

    if (checkLiveness && cycle - lastCommitCycle > livenessThreshold) {
      simFailure(f"liveness: no commit for $livenessThreshold cycles (cycle $cycle, pc would be the stuck slot)")
    }
  }

  onSimEnd {
    konataThread.foreach(_.cycleLock = Long.MaxValue)
    kb.foreach(_.flush())
    kb.foreach(_.close())
    backends.foreach(_.close())
  }
}
