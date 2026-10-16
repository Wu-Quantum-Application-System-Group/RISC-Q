package riscq.riscv.fetch

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.{Bus, DebugId, Opcode}
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.misc.{MemBus, PipelinePlugin}

/**
 * Pipelined cacheless instruction fetch over a Tilelink master bus (`iBus`), achieving IPC = 1
 * on straight-line code despite instruction-memory latency.
 *
 * The fetch is split across the chain: a **fork** at `pcAt` issues a `Get` for the PC sitting
 * there, and a **join** `fetchLatency` stages later (`fetchDataAt = pcAt + fetchLatency`) picks the
 * word back up. Decoupling issue from consumption lets several Gets be in flight at once (one per
 * source id), so the `fetchLatency` fetch slots stay full across the memory latency instead of
 * stalling on every word. To sustain IPC = 1 under a memory response latency of `L` cycles, set
 * `fetchLatency >= L` (then `idCount` distinct ids cover the in-flight requests).
 *
 * A small **response reorder buffer** (one word slot + an "in flight" flag per id) absorbs the
 * decoupling: responses may return out of order (different ids) or before their slot reaches the
 * join, so they are demultiplexed by id here and read back at the join (with a same-cycle bypass
 * for a response landing exactly when its slot arrives). A branch flush can orphan an in-flight
 * Get; its response still drains (clearing the id) and the dropped word is simply never read,
 * while the reused id is held off by `inflight` until the orphan returns.
 *
 * This is the depth-N stripping of VexiiRiscv's `FetchCachelessPlugin` (no PMA/MMU/PMP/trap).
 * A fork at an upstream stage is safe precisely because each request carries its own id into
 * the buffer (vs a naive single-outstanding design where a fork at an upstream stage is not).
 */
class FetchPlugin(p: RiscqParam) extends FiberPlugin {
  val logic = during setup new Area {
    val pp   = host[PipelinePlugin]
    val lock = retains(pp.elaborationLock)
    awaitBuild()

    val forkAt  = p.pcAt
    val joinAt  = p.fetchDataAt
    // A power-of-two id space >= one-per-slot+1, so a binary counter cycles the ids cleanly and
    // every id stays free long enough for its slot to reach the join.
    val idCount = 1 << log2Up(joinAt - forkAt + 1)

    // iBus address is full XLEN-wide: it must reach the instruction RAM at the fabric's decode base
    // (0x8000_0000), so when the fetch PC is narrowed it is reconstructed (pcBase | pc) onto this bus.
    val iBus = master(Bus(MemBus.param(Global.XLEN, 32, withStore = false, idCount = idCount)))
    iBus.simPublic()

    // Source id chosen at the fork, carried to the join so it claims its own response.
    val ID = Payload(UInt(log2Up(idCount) bits))

    // Response reorder buffer, indexed by source id.
    val buffer = new Area {
      val reserveId = Counter(idCount)                    // id handed to the next fork
      val inflight  = Vec.fill(idCount)(RegInit(False))   // a Get is outstanding on this id
      val words     = Vec.fill(idCount)(Reg(Bits(32 bits)))
      // Baked in: cap the fanout of the halt-driving buffer control so Vivado replicates
      // reserveId/inflight per fetch-front consumer instead of routing one net into every stage's CE.
      // The fork halt `full = inflight(reserveId)` and the join halt `!inflight(id)` feed the fetch
      // `ready` spine → the pcReg/fetch-front clock-enables (~65 % route). Bit-exact (attribute only).
      reserveId.value.addAttribute("MAX_FANOUT", 16)
      inflight.foreach(_.addAttribute("MAX_FANOUT", 16))
    }
    iBus.d.ready := True
    when(iBus.d.fire) {                                   // latch the returning word against its id
      buffer.words(iBus.d.source)    := iBus.d.data
      buffer.inflight(iBus.d.source) := False
    }

    // ---- Fork: issue this PC's Get, tagged with the next source id. ----
    val forkCtrl = pp.ctrl(forkAt)
    val fork = new forkCtrl.Area {
      val full   = buffer.inflight(buffer.reserveId.value) // chosen id still awaiting its response
      val stream = forkStream().haltWhen(full)             // one Get per instruction; stall while full
      iBus.a.arbitrationFrom(stream)
      iBus.a.opcode  := Opcode.A.GET
      iBus.a.param   := 0
      iBus.a.source  := buffer.reserveId.value
      iBus.a.size    := log2Up(iBus.p.dataBytes)
      iBus.a.address := Global.fullPc(apply(Global.PC))    // pcBase|offset (full-width when not narrowed)
      if (DebugId.enabled) iBus.a.debugId := 0

      ID := buffer.reserveId.value
      when(iBus.a.fire) { buffer.inflight(buffer.reserveId.value) := True }
      when(up.isMoving) { buffer.reserveId.increment() }   // next instruction gets the next id
    }

    // ---- Join: pick up the word for this slot's id (bypassing the buffer when it lands now). ----
    val joinCtrl = pp.ctrl(joinAt)
    val join = new joinCtrl.Area {
      val id     = apply(ID)
      val bypass = iBus.d.fire && iBus.d.source === id
      val ready  = !buffer.inflight(id) || bypass          // our word is available this cycle
      Fetch.WORD := bypass ? iBus.d.data | buffer.words(id)
      haltWhen(isValid && !ready)

      // Whitebox the committed fetch (PC + word) for the fetch testbench / RVLS probe.
      val dbgFiring = CombInit(down.isFiring)
      val dbgPc     = CombInit(Global.fullPc(apply(Global.PC))) // full PC for the Konata trace
      val dbgWord   = CombInit(apply(Fetch.WORD))
      dbgFiring.simPublic()
      dbgPc.simPublic()
      dbgWord.simPublic()
    }

    lock.release()
  }
}
