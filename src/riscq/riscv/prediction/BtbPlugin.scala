package riscq.riscv.prediction

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.fetch.PcPlugin
import riscq.riscv.execute.BranchPlugin
import riscq.riscv.misc.PipelinePlugin

/**
 * Branch Target Buffer — a direct-mapped, full-tag, register-based predictor read at the **PC
 * stage** (`pcAt`).
 *
 * On a hit it steers [[PcPlugin]]'s next-PC straight to the stored target (via `newPredictor()`),
 * so a correctly predicted taken branch/jump costs **zero** bubbles — no wrong-path instruction
 * is ever fetched, hence no flush. The execute-stage [[BranchPlugin]] remains the authoritative
 * corrector and feeds learning back here through its `learn` Flow.
 *
 * Design choices (all "simple over clever", per the riscq guidelines):
 *  - **Register array, not a RAM.** `idCount` is tiny; `valid = RegInit(False)` gives a free
 *    boot-clear (no init counter, no x-prop), and avoids dual-port-RAM / read-during-write
 *    machinery. The async read is a known fmax cost (a sync BRAM read at `pcAt` + predict one
 *    stage later would be the 1-bubble alternative).
 *  - **Full tag** (every PC bit above the index). Combined with the free boot-clear, the *only*
 *    BTB hits are PCs we have actually learned — which are only real taken branches/jumps. So the
 *    predictor never fires on a non-branch, and the two execute correctors (BranchPlugin for
 *    branches/jumps, CsrPlugin for traps/mret) still never collide.
 *  - **Direction:** unconditional jumps are always predicted taken; a conditional branch is
 *    predicted taken per [[GSharePlugin]]'s direction bit, **cached per entry** here (refreshed at
 *    commit from GShare's `learnTaken`) so the predict-stage read stays out of the PC self-loop
 *    (GShare is an *optional* dependency — with none present a hit simply predicts always-taken).
 *    Each entry records `isCond` so the read knows which rule applies.
 *
 * This is the depth-1 stripping of VexiiRiscv's `BtbPlugin` (no chunks/slices/aligner, no RAS,
 * no dual-port RAM, no branch-history layers).
 */
class BtbPlugin(p: RiscqParam) extends FiberPlugin {
  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val pcp   = host[PcPlugin]
    val lock  = retains(pp.elaborationLock)
    val plock = retains(pcp.predictorRetainer)
    val frlock = if (p.btbPredictLate) retains(pcp.fetchRedirectRetainer) else null // late-redirect path
    awaitBuild()

    val sets = p.btbSets
    assert(isPow2(sets) && sets >= 2, "btbSets must be a power of two >= 2")
    val setBits = log2Up(sets)
    // PC = [ tag | index | 2'b00 ]  (RV32I word-aligned, no compressed instructions).
    val indexRange = (2 + setBits - 1) downto 2
    val tagRange   = (Global.FETCH_PC_WIDTH - 1) downto (2 + setBits)

    val valid   = Vec.fill(sets)(RegInit(False))
    val tags    = Vec.fill(sets)(Reg(UInt(tagRange.size bits)))
    val targets = Vec.fill(sets)(Reg(UInt(Global.FETCH_PC_WIDTH bits)))
    val isCond  = Vec.fill(sets)(Reg(Bool())) // conditional branch (GShare decides) vs jump (always taken)

    // Cache GShare's predicted-taken bit per entry so the predict-stage read is a tiny `sets`:1
    // register mux instead of the live 2^w:1 counter mux in the PC self-loop. (No GShare ⇒ no cache;
    // a conditional hit then predicts always-taken.)
    val gsharePresent = host.get[GSharePlugin].nonEmpty
    // No reset on the cached direction bit (intentional): it is a pure hint and is
    // write-before-read anyway (written when a conditional entry is installed below, read only when
    // that entry is valid && isCond), so its boot-clear is unnecessary and the init is dropped to keep
    // it out of the async-reset control-set group.
    val dir = if (gsharePresent) Vec.fill(sets)(Reg(Bool())) else null

    def idxOf(pc: UInt) = pc(indexRange)
    def tagOf(pc: UInt) = pc(tagRange)

    // ---- Predict @ pcAt. Baseline: a hit folds straight into PcPlugin's next-PC (0 bubbles).
    //      btbPredictLate: the same target is instead carried to pcAt+1 and issued as a registered
    //      fetch redirect there, keeping the BTB tag-compare + 32-bit target mux off the pcReg self-loop. ----
    val predict       = pcp.newPredictor()
    val fetchRedirect = if (p.btbPredictLate) pcp.newFetchRedirect() else null
    // Payloads carrying the late-predict decision from pcAt to pcAt+1 (btbPredictLate only; default-driven below).
    val BTB_REDIRECT = if (p.btbPredictLate) Payload(Bool()) else null
    val BTB_TARGET   = if (p.btbPredictLate) Payload(UInt(Global.FETCH_PC_WIDTH bits)) else null
    val c0 = pp.ctrl(p.pcAt)
    val read = new c0.Area {
      val pc  = up(Global.PC)
      val idx = idxOf(pc)
      val hit = valid(idx) && tags(idx) === tagOf(pc)
      // Jumps: always taken. Conditional branches: the cached per-entry GShare direction bit, or
      // always-taken when there is no GShare.
      val condTaken = if (gsharePresent) dir(idx) else True
      val taken     = hit && (!isCond(idx) || condTaken)
      predict.valid   := taken
      predict.payload := targets(idx)
      if (p.btbPredictLate) {            // carry the decision one stage down to act on it late
        BTB_REDIRECT := taken
        BTB_TARGET   := targets(idx)
      }
      val dbgHit = CombInit(hit); dbgHit.simPublic()
    }
    plock.release() // predictor registered — let PcPlugin build independently of the learn wiring

    // btbPredictLate: act on the prediction one cycle later (at pcAt+1) as a registered fetch redirect that
    // flushes the single wrong-path fall-through fetch (the pcAt stage) — 1 bubble on a predicted-taken transfer.
    // Gated on `down.isFiring`, so a prediction whose own instruction was flushed (by an older corrector,
    // or by this very redirect the previous cycle) auto-cancels — no stale re-steer. Mirrors BranchPlugin's
    // corrector, anchored a stage after the PC instead of after execute. NEXT_PC_PRED still carries the
    // prediction, so the execute corrector (unchanged) backstops every real misprediction.
    val lateAct = if (p.btbPredictLate) {
      val c1 = pp.ctrl(p.pcAt + 1)
      new c1.Area {
        val fire = down.isFiring && apply(BTB_REDIRECT)
        fetchRedirect.valid   := fire
        fetchRedirect.payload := apply(BTB_TARGET)
        pp.throwStagesBefore(fire, p.pcAt + 1) // flush the lone wrong-path fetch (pcAt)
        pp.throwSkidsBefore(fire, p.pcAt + 1)                    // (no skid in [pcAt,pcAt+1) by default)
      }
    } else null
    if (p.btbPredictLate) frlock.release()

    // ---- Learn @ execute: install every taken branch/jump (direction is GShare's job now). ----
    host[BranchPlugin].learnRetainer.await()
    val cmd = host[BranchPlugin].learn
    // Also read GShare's (pre-update) prediction for this branch, to refresh the cached direction bit.
    if (gsharePresent) host[GSharePlugin].learnDirRetainer.await()
    val gdir = if (gsharePresent) host[GSharePlugin].learnTaken else null
    val learn = new Area {
      val idx = idxOf(cmd.pc)
      when(cmd.valid && cmd.taken) {
        valid(idx)   := True
        tags(idx)    := tagOf(cmd.pc)
        targets(idx) := cmd.target
        isCond(idx)  := cmd.isConditional
      }
      // Refresh the cached direction from GShare on every conditional commit that owns this entry
      // (taken installs/refreshes it; a not-taken commit updates it only if the entry already
      // belongs to this branch, so an aliasing not-taken commit never corrupts another entry).
      if (gsharePresent) {
        val owns = valid(idx) && tags(idx) === tagOf(cmd.pc)
        when(cmd.valid && cmd.isConditional && (cmd.taken || owns)) {
          dir(idx) := gdir
        }
      }
    }

    lock.release()
  }
}
