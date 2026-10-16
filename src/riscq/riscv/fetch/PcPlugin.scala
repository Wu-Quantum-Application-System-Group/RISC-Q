package riscq.riscv.fetch

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.misc.PipelinePlugin

import scala.collection.mutable.ArrayBuffer

/**
 * Holds the architectural PC and drives the head of the pipeline.
 *
 * Each cycle the head stage fires, the PC advances to its **predicted successor**: the branch
 * predictor's target when it predicts a taken control transfer ([[riscq.riscv.prediction.BtbPlugin]]
 * registers it with `newPredictor()`), otherwise the sequential PC+4. That same predicted
 * successor is published as `Fetch.NEXT_PC_PRED` so the execute stage can later check it. A
 * sub-pipeline without a predictor (the fetch/decode unit tests) simply gets PC+4 — pure
 * sequential fetch.
 *
 * A taken-branch/jump **misprediction** (BranchPlugin) or a trap/mret (CsrPlugin) registers a
 * `newRedirect()` and overrides the predicted advance. Those correctors also flush the younger,
 * wrong-path stages (see [[riscq.riscv.execute.BranchPlugin]]); the *prediction* needs no flush, since
 * it steers the next fetch before any wrong-path instruction exists.
 */
class PcPlugin(p: RiscqParam) extends FiberPlugin {
  // Redirect contributors register before the logic below reads the list.
  val redirectRetainer = Retainer()
  val redirects        = ArrayBuffer[Flow[UInt]]()
  def newRedirect(): Flow[UInt] = {
    val port = Flow(Global.PC)
    redirects += port
    port
  }

  // At most one fetch-stage branch predictor (the BTB) steers the next PC speculatively.
  val predictorRetainer = Retainer()
  val predictors        = ArrayBuffer[Flow[UInt]]()
  def newPredictor(): Flow[UInt] = {
    val port = Flow(Global.PC)
    predictors += port
    port
  }

  // A *fetch* redirect: a fetch-stage predictor (the BTB in `btbPredictLate` mode) that re-steers
  // pcReg one cycle *after* the PC stage. Lower priority than the execute/trap correctors below — they
  // target older instructions and must override it. Kept a separate list so the corrector `redirects`
  // stay <= 2, and applied *before* them so the later corrector writes win (last-assignment priority).
  val fetchRedirectRetainer = Retainer()
  val fetchRedirects        = ArrayBuffer[Flow[UInt]]()
  def newFetchRedirect(): Flow[UInt] = {
    val port = Flow(Global.PC)
    fetchRedirects += port
    port
  }

  val logic = during setup new Area {
    val pp   = host[PipelinePlugin]
    val lock = retains(pp.elaborationLock)
    awaitBuild()

    val c0    = pp.ctrl(p.pcAt)
    // pcReg holds the (possibly narrowed) PC *offset*; its reset value is the resetVector's in-region low
    // bits (== resetVector when full-width). The constant high bits are rebuilt at the visible boundaries.
    val pcReg = Reg(Global.PC) init (p.resetPcOffset)
    pcReg.simPublic()
    // The predicted-PC self-loop (pcReg→pcReg via BTB/NEXT_PC_PRED) is route-dominated; capping pcReg's
    // fanout lets Vivado replicate it, giving each consumer (BTB idx/tag, +4 adder, fetcher) a near copy.
    if (p.pcRegMaxFanout > 0) pcReg.addAttribute("max_fanout", p.pcRegMaxFanout)

    predictorRetainer.await()
    assert(predictors.size <= 1, "riscq expects at most one fetch predictor (the BTB)")
    val predict = predictors.headOption.getOrElse {
      val idle = Flow(Global.PC) // no predictor ⇒ never steers ⇒ pure sequential fetch
      idle.valid   := False
      idle.payload := 0
      idle
    }

    val fetcher = new c0.Area {
      up.valid := True
      up(Global.PC) := pcReg
      // Predicted successor of the instruction at the PC stage: the predictor's target when it
      // fires, else PC+4. Recorded so the execute corrector can compare it to the real successor.
      val predictedNext = predict.valid ? predict.payload | (pcReg + 4)
      up(Fetch.NEXT_PC_PRED)    := predictedNext
      up(Fetch.PREDICTED_TAKEN) := predict.valid // 1-bit direction the corrector (BranchPlugin) checks
      // btbPredictLate: the prediction no longer folds into the self-loop — pcReg advances purely
      // sequentially and the BTB re-steers a cycle later via a fetchRedirect (below), leaving only
      // `pcReg→+4→pcReg` on the recurrence (no BTB tag-compare / target mux). Off: the 0-bubble fold.
      val pcAdvance = if (p.btbPredictLate) (pcReg + 4) else predictedNext
      val firing    = up.isFiring
    }

    // PC sources override the sequential advance. A misprediction (BranchPlugin) or a trap/mret
    // (CsrPlugin) is a *corrector* (highest priority); the BTB-late predictor is a *fetch redirect*
    // (middle); the sequential `pcAdvance` is the default (lowest). A single-issue core has at most two
    // correctors — a branch and a trap/mret — targeting different opcodes, so they never fire the same
    // cycle. (The speculative predictor isn't here — its instruction is youngest, so any corrector rightly
    // overrides + flushes it.)
    fetchRedirectRetainer.await()
    redirectRetainer.await()
    assert(redirects.size <= 2, "single-issue riscq expects at most two redirect sources")

    // whitebox: a corrector won the PC this cycle and flushed the younger stages.
    val redirectFire = redirects.map(_.valid).orR
    redirectFire.simPublic()

    // Next-PC select. Default: a serial priority `when`-chain (sequential advance, then fetch redirects,
    // then correctors override — last-assignment wins). The one-hot select (`pcOneHotRedirect`) is an
    // opt-in alternative that trades the stacked muxes for a balanced one-hot tree.
    if (p.pcOneHotRedirect) {
      // One balanced one-hot select (VexiiRiscv `OHMasking.firstV2` + `OhMux.or`) — the late corrector
      // `valid`s drive a shallow firstV2 + AND-OR payload mux instead of stacked 32-bit muxes. Priority
      // high→low (matches the chain's last-wins): correctors (last-registered first) → fetch → sequential.
      val srcs     = redirects.reverse ++ fetchRedirects.reverse
      val valids   = srcs.map(_.valid) :+ fetcher.firing
      val payloads = srcs.map(_.payload) :+ fetcher.pcAdvance
      val vbits    = Vec(valids).asBits
      val oh       = OHMasking.firstV2(vbits)
      when(vbits.orR) { pcReg := OhMux.or(oh, payloads) }
    } else {
      when(fetcher.firing) { pcReg := fetcher.pcAdvance }
      for (fetchRedirect <- fetchRedirects) when(fetchRedirect.valid) { pcReg := fetchRedirect.payload }
      for (redirect <- redirects) when(redirect.valid) { pcReg := redirect.payload }
    }

    lock.release()
  }
}
