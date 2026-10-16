package riscq.riscv.execute

import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib.{OHMasking, OhMux}
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.csr.CsrPlugin
import riscq.riscv.misc.PipelinePlugin

import scala.collection.mutable.ArrayBuffer

/**
 * Selects what gets written back to rd and presents it to the register file.
 *
 * With a single execute stage this is a small priority mux over the rd sources each producing
 * plugin computed: a CSR read returns the old CSR value (`CSR_RD_DATA`), a load returns memory data
 * (`LOAD_DATA`), a jump its return address (`LINK_PC`), everything else the ALU result. The selected
 * value/enable go to the register-file write port via the `RD_DATA`/`RD_WRITE` payloads.
 *
 * Optional extensions add their own rd source generically with [[addRdSource]] (e.g. the M
 * extension's product) — so an extension contributes a write-back value without this base plugin
 * importing it, and a config without the extension is byte-identical to before. The base four and
 * every registered source are mutually exclusive (one-hot for any rd-writing op), so their mux
 * priority is irrelevant.
 *
 * (Traps/`ECALL` are handled by [[riscq.riscv.csr.CsrPlugin]] now, not here.)
 *
 * VexiiRiscv's `WriteBackPlugin` pipelines many write ports from arbitrary stages down to one
 * register-file port; with a single execute stage that whole mechanism collapses to this mux.
 */
class WriteBackPlugin(p: RiscqParam) extends FiberPlugin {
  // Extra rd-write sources contributed by optional extensions. Each is a (select, data) payload pair:
  // when `select` is set, the instruction writes `data` to rd. Registered during the contributor's
  // setup while it holds `elaborationLock` (mirrors DecoderPlugin's registration handshake).
  val elaborationLock = Retainer()
  private val extraSources = ArrayBuffer[(Payload[Bool], Payload[Bits])]()
  def addRdSource(sel: Payload[Bool], data: Payload[Bits]): Unit = extraSources += sel -> data

  val logic = during setup new Area {
    val pp   = host[PipelinePlugin]
    val lock = retains(pp.elaborationLock)
    awaitBuild()
    // Wait until every extension has registered its rd source before building the mux below.
    elaborationLock.await()

    val ce = pp.ctrl(p.executeAt)
    val cw = pp.ctrl(p.regWriteAt)

    // RD_WRITE (cheap decoded selects) + the sim-only dbg rd value stay at executeAt. The pre-merge
    // keeps the late-arriving ALU result one 2:1 from the output: the non-ALU sources are mutually
    // exclusive and arrive early, so they fold off the SRC path (extension `addRdSource` sources in
    // front of the base CSR/load/jump). With lateWriteback, only that final 2:1 moves to regWriteAt;
    // the executeAt copy here (RD_DATA_DBG) keeps the RVLS retire at executeAt and is pruned in synth
    // (no hardware consumer).
    val wb = new ce.Area {
      val isCsr       = apply(CsrPlugin.IS_CSR)
      val isJump      = apply(BranchPlugin.IS_JUMP)
      val isLoad      = apply(LsuPlugin.IS_LOAD)
      val aluWritesRd = apply(IntAluPlugin.ALU_WRITES_RD)
      val aluResult   = Execute.ALU_RESULT
      val csrData     = apply(Execute.CSR_RD_DATA)
      val loadData    = apply(Execute.LOAD_DATA)
      val linkPc      = Execute.LINK_PC
      val baseNonAlu = isCsr ? csrData | (isLoad ? loadData | (isJump ? Global.fullPc(linkPc).asBits | B(0, Global.XLEN bits)))
      val nonAlu = extraSources.foldRight(baseNonAlu) { case ((sel, data), fallback) =>
        apply(sel) ? apply(data) | fallback
      }
      val extraWrites = extraSources.map(s => apply(s._1)).foldLeft(False)(_ || _)
      Execute.RD_WRITE    := isCsr || isLoad || isJump || aluWritesRd || extraWrites
      Execute.RD_DATA_DBG := aluWritesRd ? apply(aluResult) | nonAlu
      // Baseline: the real RD_DATA mux is also here (registered to regWriteAt for the write + srcB).
      if (!p.lateWriteback) Execute.RD_DATA := Execute.RD_DATA_DBG
    }

    // lateWriteback: the real RD_DATA 2:1 mux at regWriteAt — off the executeAt→RD_DATA-register path.
    // srcB already forwards RD_DATA from regWriteAt and the regfile write is here, so this adds no
    // pipeline depth / IPC cost; only the mux's combinational delay moves.
    val wbLate = p.lateWriteback generate new cw.Area {
      val isCsr       = apply(CsrPlugin.IS_CSR)
      val isJump      = apply(BranchPlugin.IS_JUMP)
      val isLoad      = apply(LsuPlugin.IS_LOAD)
      val aluWritesRd = apply(IntAluPlugin.ALU_WRITES_RD)
      val aluResult   = apply(Execute.ALU_RESULT)
      val csrData     = apply(Execute.CSR_RD_DATA)
      val loadData    = apply(Execute.LOAD_DATA)
      val linkPc      = Global.fullPc(Execute.LINK_PC).asBits

      // The rd sources are mutually exclusive, so the priority mux above is really a one-hot select.
      // Collect (select, data) in the same priority order — ALU result, extension sources, then
      // CSR/load/jump — force strict one-hot with OHMasking.first (keeps the lowest, i.e. highest
      // priority, set bit) and fold the data with a balanced OR tree (OhMux.or) rather than a chained
      // 2:1 priority mux. Same result as the priority mux, but flatter logic on the regWriteAt write path.
      val sels  = ArrayBuffer(aluWritesRd)
      val datas = ArrayBuffer(aluResult)
      for ((sel, data) <- extraSources) { sels += apply(sel); datas += apply(data) }
      sels += isCsr;  datas += csrData
      sels += isLoad; datas += loadData
      sels += isJump; datas += linkPc

      Execute.RD_DATA := OhMux.or(OHMasking.firstV2(Vec(sels)), datas)
    }

    lock.release()
  }
}
