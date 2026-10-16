package riscq.riscv.bench

import spinal.core._
import riscq.riscv.{Riscq, RiscqParam}
import riscq.bench.{PathAnalysis => PA}

/**
 * Static pre-synthesis path analysis of the **riscq RISC-V core**, a thin entry point over the
 * generic [[riscq.bench.PathAnalysis]] engine.
 *
 * With no args it prints the longest register -> register combinational paths and the largest
 * fanouts (the classic fmax-tuning view). Pass two signal names to instead report, between those two
 * signals, both the register levels (pipeline latency, in clock cycles) and the combinational levels
 * (logic depth, in computational operators) — complementary views of how far apart they sit.
 *
 * Run:
 *   mill runMain riscq.riscv.bench.PathAnalysis                 # longest paths + fanout
 *   mill runMain riscq.riscv.bench.PathAnalysis <from> <to>     # register + combinational levels
 */
object PathAnalysis extends App {
  val report = SpinalVerilog(Riscq(RiscqParam().plugins()))
  val pa     = PA(report)

  args.toList match {
    case from :: to :: _ => // resolve each name once, then report both views between them
      for (f <- pa.resolveSignal(from); t <- pa.resolveSignal(to)) {
        pa.reportRegisterLevels(f, t)
        pa.reportCombinationalLevels(f, t)
      }
    case _ =>
      pa.reportLongestPaths()
      pa.reportFanout()
  }
}
