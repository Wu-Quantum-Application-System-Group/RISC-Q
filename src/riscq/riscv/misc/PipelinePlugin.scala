package riscq.riscv.misc

import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin.FiberPlugin

import riscq.riscv.RiscqParam

import scala.collection.mutable

/**
 * The single-issue, in-order pipeline skeleton: one `CtrlLink` chain whose stage indices
 * are partitioned into Fetch / Decode / Execute by `RiscqParam`.
 *
 * Feature plugins request `ctrl(i)` and inject logic with `new ctrl(i).Area { ... }`.
 * To make sure every stage is registered before the links/`Builder` are created, feature
 * plugins must hold `elaborationLock` (via `retains`) until they have finished registering.
 */
class PipelinePlugin(param: RiscqParam) extends FiberPlugin {
  setName("pipeline")
  val elaborationLock = Retainer()

  val idToCtrl = mutable.LinkedHashMap[Int, CtrlLink]()
  def ctrl(id: Int): CtrlLink =
    idToCtrl.getOrElseUpdate(id, CtrlLink().setCompositeName(this, "ctrl" + id))

  // One skid buffer (a spliced-in `CtrlLink`) per boundary index in `param.skidAfter`. Materialised
  // in the setup block below so the branch/CSR correctors — which inject their flushes in the build
  // phase — can reference these nodes via `throwSkidsBefore` before the `Builder` runs.
  val idToSkid = mutable.LinkedHashMap[Int, CtrlLink]()
  def skid(id: Int): CtrlLink =
    idToSkid.getOrElseUpdate(id, CtrlLink().setCompositeName(this, "skid" + id))

  /**
   * Flush (cancel) the wrong-path instruction buffered in every skid whose boundary lies inside a
   * corrector's flush range `[param.pcAt, untilExclusive)`.
   *
   * A skid at boundary `b` is `StageLink(ctrl(b).down -> skid.up)` + `S2MLink(skid.down ->
   * ctrl(b+1).up)`. The branch/CSR correctors already `throwWhen(usingReady = true)` the *ctrl*
   * stages in their range; that throw forces `ctrl(b+1).up.ready := True`, which clears the S2M
   * buffer — but it does **not** reach the StageLink register at `skid.up`. So whenever that buffer
   * is full during a flush, the (wrong-path) instruction held in `skid.up` survives and later commits.
   * (At the old fetch->decode boundary the buffer almost never filled during a flush, hiding this;
   * at decode->execute the execute halts routinely back-pressure decode, so it must be handled.)
   *
   * `skid.throwWhen(cond, usingReady = false)` raises a `forgetOne` on the skid that the StageLink
   * honours (`down.valid clearWhen forgetOne`), clearing that stalled register.
   *
   * A skid at `b` carries an instruction *between* `ctrl(b)` and `ctrl(b+1)`, so it is wrong-path
   * exactly when both surrounding stages are flushed: `b >= pcAt && b + 1 < untilExclusive`.
   */
  def throwSkidsBefore(cond: Bool, untilExclusive: Int): Unit =
    for (b <- param.skidAfter if b >= param.pcAt && b + 1 < untilExclusive)
      skid(b).throwWhen(cond, usingReady = false)

  /**
   * Flush (cancel) the younger wrong-path ctrl stages `[pcAt, untilExclusive)` on `cond` — the
   * branch/CSR correctors' stage flush, factored here so the head special-case lives in one place.
   *
   * Each stage is cleared **off the `up.ready` spine** (`throwWhen(usingReady = false)` = forgetOne):
   * the redirect clears that stage's StageLink valid register directly (`down.valid clearWhen forgetOne`)
   * rather than forcing `up.ready := True` and riding the backward ready chain into the register
   * clock-enables. This keeps the high-fanout `mispredict`/redirect net off the serial ready spine
   * (riscq's analog of VexiiRiscv's centralized age-based `isFlushedAt`).
   *
   * The pipeline **head** (`pcAt`) is special: it has no upstream StageLink register, so it cannot take a
   * `forgetOne` request — it is always thrown with `usingReady = true`. That is correctness-sufficient:
   * `throwWhen` always `terminate`s (`down.valid := False`), hiding the head's wrong-path instruction this
   * cycle, and PcPlugin redirects `pcReg` directly so the next fetch is correct.
   */
  def throwStagesBefore(cond: Bool, untilExclusive: Int): Unit =
    for (i <- param.pcAt until untilExclusive)
      ctrl(i).throwWhen(cond, usingReady = i == param.pcAt)

  // Create the skid nodes early (setup phase) so `throwSkidsBefore` (build phase) finds them.
  val skidSetup = during setup new Area {
    param.skidAfter.foreach(skid)
  }

  val logic = during build new Area {
    elaborationLock.await()

    val idMax = idToCtrl.keys.max
    for (i <- 0 to idMax) ctrl(i) // create any skipped intermediate nodes
    val ctrls = idToCtrl.toList.sortBy(_._1).map(_._2)

    // Connect each adjacent ctrl(i) -> ctrl(i+1). Normally a plain StageLink (one data register,
    // `ready` propagates combinationally backward). For boundaries listed in `param.skidAfter` we
    // instead splice in a skid buffer: StageLink(ctrl_i.down -> skid.up) keeps the single data
    // register, and S2MLink(skid.down -> ctrl_{i+1}.up) sets `skid.down.ready := !rValid` — a local
    // register — which *cuts* the backward ready chain (the execute halt/flush no longer ripples
    // combinationally to the fetch fork) while buffering one in-flight item, so steady-state
    // throughput/latency are unchanged. The S2M buffer is transparent when empty, so no extra
    // pipeline depth is added. Flush of a buffered wrong-path item is handled by `throwSkidsBefore`
    // (the correctors call it alongside their ctrl-stage throws). The skid `CtrlLink`s must also go
    // to the Builder.
    val links = mutable.ArrayBuffer[Link]()
    for (i <- 0 until ctrls.length - 1) {
      val from = ctrls(i)
      val to = ctrls(i + 1)
      if (param.skidAfter.contains(i)) {
        val sk = skid(i)
        links += StageLink(from.down, sk.up)
        links += S2MLink(sk.down, to.up)
      } else {
        links += StageLink(from.down, to.up)
      }
    }
    ctrls.last.down.ready := True // the core never back-pressures its own retirement (yet)
    // fmax hint: cap the regReadAt ready-net fanout. Guard for short sub-pipelines (the decode-only one
    // used by DecodeSim has only stages 0..decodeAt, so no stage 3) — full core has ≥4 stages.
    if (ctrls.length > param.fetchLatency + 2) ctrls(param.fetchLatency + 2).down.ready.addAttribute("MAX_FANOUT", 128)

    val all: Seq[Link] = links.toList ++ ctrls ++ idToSkid.values
    Builder(all)
  }
}
