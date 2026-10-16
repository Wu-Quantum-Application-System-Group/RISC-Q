package riscq.riscv.prediction

import spinal.core._
import spinal.lib._
import riscq.riscv.{Global, RiscqParam}

/**
 * What the execute-stage branch resolution tells the fetch-stage predictors so they can learn.
 *
 * There is a single producer ([[riscq.riscv.execute.BranchPlugin]], which exposes a `Flow[LearnCmd]`)
 * and zero or more consumers ([[BtbPlugin]], and later the GShare counters). That is far
 * simpler than VexiiRiscv's `LearnPlugin`, which aggregates learn ports from several execute
 * lanes — riscq has exactly one branch unit, so a plain broadcast `Flow` is enough.
 */
case class LearnCmd(p: RiscqParam) extends Bundle {
  val pc            = UInt(Global.FETCH_PC_WIDTH bits) // address of the resolved branch/jump
  val target        = UInt(Global.FETCH_PC_WIDTH bits) // its taken target (aligned)
  val taken         = Bool()                     // was the transfer actually taken?
  val isConditional = Bool()                     // conditional branch (true) vs JAL/JALR (false)
}
