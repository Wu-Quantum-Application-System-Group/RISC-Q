package riscq.riscv.fetch

import spinal.core._
import spinal.lib.misc.pipeline.Payload
import riscq.riscv.Global

/** Fetch-stage pipeline payloads. */
object Fetch extends AreaObject {
  // RV32 with no compressed instructions: exactly one 32-bit instruction per fetch.
  val WORD = Payload(Bits(32 bits))

  // The PC fetch *decided to fetch next* after this instruction (its predicted successor):
  // the branch predictor's target on a predicted-taken control transfer, otherwise PC+4. The
  // execute corrector ([[riscq.riscv.execute.BranchPlugin]]) redirects iff this disagrees with the
  // real successor, so with no predictor (always PC+4) the corrector degrades to the plain
  // "redirect only when taken" rule. Driven once, by [[PcPlugin]].
  val NEXT_PC_PRED = Payload(UInt(Global.FETCH_PC_WIDTH bits))

  // Whether the predictor said this instruction is a *taken* control transfer (= BtbPlugin's
  // `predict.valid`; always False with no predictor). Lets BranchPlugin split the misprediction check
  // into a 1-bit **direction** test (`PREDICTED_TAKEN =/= take`) + a single 32-bit **target** compare
  // (predicted target vs the true target, only when taken) — instead of two 32-bit NEXT_PC_PRED
  // compares muxed by `take`. Driven by [[PcPlugin]].
  val PREDICTED_TAKEN = Payload(Bool())
}
