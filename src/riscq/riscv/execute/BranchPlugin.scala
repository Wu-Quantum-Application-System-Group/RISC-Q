package riscq.riscv.execute

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.{Decode, DecoderPlugin, Rv32i}
import riscq.riscv.fetch.{Fetch, PcPlugin}
import riscq.riscv.misc.PipelinePlugin
import riscq.riscv.prediction.LearnCmd

/** Control-flow classification signals this plugin owns and registers with the decoder. */
object BranchPlugin extends AreaObject {
  val IS_BRANCH = Payload(Bool())
  val IS_JAL    = Payload(Bool())
  val IS_JALR   = Payload(Bool())
  val IS_JUMP   = Payload(Bool()) // JAL || JALR (rd = PC+4), read by WriteBackPlugin
}

/**
 * Branch/jump resolution and the **predictor corrector**: `BEQ/BNE/BLT/BGE/BLTU/BGEU`, `JAL`,
 * `JALR`.
 *
 * The compare results (`EQ`, `LESS`) come from `SrcPlugin`, shared with the ALU. The plugin
 * computes the *true* successor of a control-flow instruction (`take ? target | PC+4`) and
 * compares it to the **predicted** successor fetch actually chose (`Fetch.NEXT_PC_PRED`, set by
 * [[riscq.riscv.fetch.PcPlugin]] from the [[riscq.riscv.prediction.BtbPlugin]]). On a **misprediction** it:
 *  - drives `PcPlugin.redirect` with the true successor, and
 *  - flushes the younger stages (PC/fetch/decode) holding wrong-path instructions, with
 *    `throwWhen` — the SpinalHDL `CpuDemo` flush idiom.
 *
 * With no predictor `NEXT_PC_PRED` is always PC+4, so a misprediction reduces to exactly
 * "taken" — i.e. this same corrector degrades to the "redirect only when taken" rule as a special
 * case. A correctly predicted taken branch needs neither redirect nor flush.
 *
 * It also publishes a [[LearnCmd]] `learn` Flow on every committed control-flow instruction, so
 * the BTB (and later GShare) can learn the target/outcome. Still far simpler than VexiiRiscv's
 * `BranchPlugin`: single lane, no branch-history feed, no misaligned-target trap.
 */
class BranchPlugin(p: RiscqParam) extends FiberPlugin {
  import BranchPlugin._

  // Branch-resolution learn broadcast to the fetch predictors (BTB / GShare). Exposed as a
  // field (the Flow itself is built inside `logic`); consumers `learnRetainer.await()` then read
  // `learn`, mirroring how PcPlugin hands out its redirect/predictor ports.
  val learnRetainer = Retainer()
  var learn: Flow[LearnCmd] = null

  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val pcp   = host[PcPlugin]
    val dec   = host[DecoderPlugin]
    val lock  = retains(pp.elaborationLock)
    val rlock = retains(pcp.redirectRetainer)
    val llock = retains(learnRetainer)
    val dlock = retains(dec.elaborationLock)
    awaitBuild()

    // ---- Register this plugin's control-signal decodings with the decoder (built at decodeAt). ----
    import Rv32i._
    val branch = Seq(BEQ, BNE, BLT, BGE, BLTU, BGEU)
    dec.addDecodingDefault(IS_BRANCH, False)
    dec.addDecodingDefault(IS_JAL, False)
    dec.addDecodingDefault(IS_JALR, False)
    dec.addDecodingDefault(IS_JUMP, False)
    branch.foreach(dec.addDecoding(_, IS_BRANCH, True))
    dec.addDecoding(JAL, IS_JAL, True)
    dec.addDecoding(JALR, IS_JALR, True)
    Seq(JAL, JALR).foreach(dec.addDecoding(_, IS_JUMP, True))

    // ---- SrcPlugin operand/compare knobs for the conditional branches: the payloads (and their
    //      defaults) belong to SrcPlugin, but each per-instruction override is registered by us, the
    //      implementer. Every branch compares rs1 vs rs2 via SRC1-SRC2; BLTU/BGEU compare unsigned.
    //      (JAL/JALR don't touch these — JALR forms `rs1+imm` directly below.) ----
    branch.foreach(dec.addDecoding(_, SrcPlugin.SRC2_RS2, True)) // compare rs1 vs rs2 (not imm)
    branch.foreach(dec.addDecoding(_, SrcPlugin.DO_SUB, True))   // branches test via SRC1-SRC2
    Seq(BLTU, BGEU).foreach(dec.addDecoding(_, SrcPlugin.CMP_UNSIGNED, True))
    dlock.release()

    val c        = pp.ctrl(p.executeAt)
    // Where the redirect + flush act: one stage after the compare (`jumpAt = executeAt+1`), reading the
    // registered resolution payloads. This ends the resolve→redirect→PC cone at a register (at the cost
    // of one mispredict-penalty cycle) and lets Vivado replicate the redirect broadcast.
    val jumpAt   = p.executeAt + 1
    val cj       = pp.ctrl(jumpAt)
    val redirect = pcp.newRedirect() // single redirect source for a single-issue core
    learn        = Flow(LearnCmd(p))

    val br = new c.Area {
      val funct3 = apply(Decode.FUNCT3) // a value: it drives the switch selector below
      val pc     = Global.PC
      val eq     = Execute.EQ
      val less   = Execute.LESS

      // CFI flags come pre-decoded from DecoderPlugin.
      val isBranch = apply(IS_BRANCH)
      val isJal    = apply(IS_JAL)
      val isJalr   = apply(IS_JALR)
      val isCfi    = isBranch || isJal || isJalr // a control-flow instruction (predictable)

      // Conditional-branch test, selected by funct3.
      val cond = False
      switch(funct3) {
        is(B"000") { cond := eq }    // BEQ
        is(B"001") { cond := !eq }   // BNE
        is(B"100") { cond := less }  // BLT
        is(B"101") { cond := !less } // BGE
        is(B"110") { cond := less }  // BLTU
        is(B"111") { cond := !less } // BGEU
      }
      val take = isJal || isJalr || (isBranch && cond)

      // Target (single expression both `learn` and the redirect/compares use). JALR is the only
      // operand-dependent case: `rs1 + imm` with the low bit cleared. That add is exactly SrcPlugin's
      // shared `ADD_SUB` (JALR takes the default src decode: SRC1=rs1, SRC2=imm, no subtract), so we
      // reuse it instead of adding a second adder. BRANCH/JAL use the PC-relative `pc+imm`, precomputed
      // one stage early (registered into executeAt as `Execute.BRANCH_TARGET`) to keep its adder off
      // this cone.
      val jalrTarget    = Execute.ADD_SUB.asUInt.resize(Global.FETCH_PC_WIDTH) // = rs1 + imm (SrcPlugin adder)
      val jalrAligned   = CombInit(jalrTarget); jalrAligned(0) := False
      val branchTarget  = apply(Execute.BRANCH_TARGET) // precomputed pc+imm (registered)
      val targetAligned = isJalr ? jalrAligned | branchTarget
      val seqNext       = pc + 4

      // Hand the return address to WriteBackPlugin (rd = PC+4 on JAL/JALR). The jump flag itself is
      // now a decoded payload (BranchPlugin.IS_JUMP), read directly by WriteBackPlugin.
      Execute.LINK_PC := seqNext

      // Resolve the outcome here, but defer the misprediction decision to jumpAt (VexiiRiscv
      // `BAD_TARGET`/`ALIGNED_JUMPED` split). The expensive part — the 32-bit target compare
      // (predicted next-PC vs the resolved taken target) — runs here and is registered as a single
      // bit (`BAD_TARGET`); the cheap 1-bit direction test + the redirect/flush then run at jumpAt,
      // off the high-fanout flush's critical cone. The not-taken/fall-through successor is always PC+4
      // (never a target misprediction), so only the taken-target compare is needed.
      // (Note: do NOT KeepAttribute targetAligned — unlike VexiiRiscv's kept PC_TRUE, keeping it here
      //  only over-constrains the placer. Left un-kept.)
      Execute.TAKE           := take
      Execute.TARGET_ALIGNED := targetAligned
      if (!p.lateBadTarget) {
        // baseline: the 32-bit target compare runs here (chained after the JALR adder) and is registered
        // as a single bit into jumpAt — a timing-critical chain.
        Execute.BAD_TARGET := apply(Fetch.NEXT_PC_PRED) =/= targetAligned
      } else {
        // lateBadTarget: keep the 32-bit compare OFF this adder cone — register only TARGET_ALIGNED
        // (already done above) and the cheap direction-mispredict bit; jumpAt runs the compare. The
        // precompute sits in the direction cone (comparators→take), parallel to the adder cone.
        Execute.DIR_MISPREDICT := isCfi && (apply(Fetch.PREDICTED_TAKEN) =/= take)
      }

      // Teach the fetch predictors from every committed control-flow instruction. Stays at execute
      // (the branch is the oldest on its own path, never flushed by itself), so BTB/GShare train
      // exactly once regardless of where the redirect acts.
      //
      // The learn payload is computed combinationally here, then registered one cycle so the BTB/GShare
      // write-decode no longer sits at the end of the `operand → subtract → branch-resolve →
      // predictor-write` cone — it starts from a register. The train is hint-only and the branch
      // re-fetches many stages later, so a 1-cycle-later learn is correctness-neutral. GSharePlugin
      // delays its HASH read by a matching cycle to train the same counter.
      val cmdValid = down.isFiring && isCfi
      val pcValue  = apply(pc) // a concrete value (RegNext needs data, not the Payload reference)
      learn.valid                 := RegNext(cmdValid) init False
      learn.payload.pc            := RegNext(pcValue)
      learn.payload.target        := RegNext(targetAligned)
      learn.payload.taken         := RegNext(take)
      learn.payload.isConditional := RegNext(isBranch)
    }

    // ---- Decide + act at jumpAt (redirect + flush younger stages). ----
    // The misprediction *decision* is assembled here from the registered resolution (TAKE / BAD_TARGET /
    // TARGET_ALIGNED across the executeAt→jumpAt StageLink, PREDICTED_TAKEN/LINK_PC from the pipeline) —
    // only **1-bit** direction logic, so the high-fanout flush starts shallow:
    //  - directionWrong: the predictor's taken/not-taken bit disagreed with the resolved TAKE.
    //  - on a taken branch, BAD_TARGET (the registered 32-bit target compare) flags a wrong target.
    // With no predictor PREDICTED_TAKEN≡False ⇒ this reduces to "redirect iff taken", the prior rule.
    // CRUCIAL: the StageLink only refreshes payload registers when an instruction advances, so the
    // registered payloads are stale during a bubble. Gate on `down.isFiring` — jumpAt is the last stage
    // (`down.ready` is True, never cancelled), so this is just "a valid instruction is present".
    // The flush range is `pcAt .. jumpAt` (exclusive), which includes executeAt: a younger instruction
    // there (a store, or a CSR trap) is cancelled — see LsuPlugin's wrong-path gate and CsrPlugin's.
    val act = new cj.Area {
      val take       = apply(Execute.TAKE)
      // lateBadTarget: the 32-bit target compare runs HERE off the registered TARGET_ALIGNED/NEXT_PC_PRED,
      // ORed with the registered direction bit — `isFiring & (DIR_MISPREDICT | (TAKE & badTarget))`.
      // Baseline reads the executeAt-registered BAD_TARGET and re-derives isCfi + direction here.
      val mispredict = if (p.lateBadTarget) {
        val badTarget = apply(Fetch.NEXT_PC_PRED) =/= apply(Execute.TARGET_ALIGNED)
        down.isFiring && (apply(Execute.DIR_MISPREDICT) || (take && badTarget))
      } else {
        val isCfi          = apply(IS_BRANCH) || apply(IS_JAL) || apply(IS_JALR)
        val directionWrong = apply(Fetch.PREDICTED_TAKEN) =/= take
        down.isFiring && isCfi && (directionWrong || (take && apply(Execute.BAD_TARGET)))
      }
      // MAX_FANOUT cap (baked in): this 1-bit decision is the mispredict broadcast root (LOAD_DATA capture
      // pins, CSR wrong-path gates, PC redirect, per-stage throws) — cap its fanout so Vivado
      // replicates the shallow driving logic per consumer group. Bit-exact (attribute only).
      mispredict.addAttribute("MAX_FANOUT", 16)
      redirect.valid   := mispredict
      redirect.payload := take ? apply(Execute.TARGET_ALIGNED) | apply(Execute.LINK_PC) // target | PC+4
      // Flush the younger (wrong-path) stages — off the up.ready spine (see PipelinePlugin.throwStagesBefore).
      pp.throwStagesBefore(mispredict, jumpAt)
      pp.throwSkidsBefore(mispredict, jumpAt) // also cancel any skid-buffered wrong-path instruction
      // Whitebox: a committed branch/jump whose prediction was wrong (one per redirect+flush).
      val dbgMispredict = CombInit(mispredict); dbgMispredict.simPublic()
    }

    // ---- Precompute the BRANCH/JAL target (`pc + imm`) at executeAt-1. ----
    // BRANCH and JAL targets are PC-relative — they need no register operand, only PC (available at
    // every stage) and Decode.IMM (produced at decodeAt). Computing the adder at executeAt-1 (rather
    // than decodeAt) still keeps it out of the executeAt branch-resolve cone, but registers the result
    // only **once** (executeAt-1 → executeAt) instead of through every decodeAt..executeAt StageLink —
    // saving a PC-width pipeline register per intermediate stage. JALR (`rs1 + imm`) still resolves at
    // executeAt (operand-dependent; reuses SrcPlugin's ADD_SUB). No `isCfi` gate: only BRANCH/JAL read it.
    val cd = pp.ctrl(p.executeAt - 1)
    val precompute = new cd.Area {
      Execute.BRANCH_TARGET := (apply(Global.PC).asSInt + apply(Decode.IMM).asSInt).asUInt
        .resize(Global.FETCH_PC_WIDTH)
    }

    llock.release()
    rlock.release()
    lock.release()
  }
}
