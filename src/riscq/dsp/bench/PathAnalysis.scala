package riscq.dsp.bench

import spinal.core._
import riscq.dsp.{SinCos, SinCosParams}
import riscq.bench.{PathAnalysis => PA}

/**
 * Static pre-synthesis path analysis of the [[SinCos]] module — thin entry point over [[riscq.bench.PathAnalysis]].
 *
 * Run:
 *   mill runMain riscq.dsp.bench.PathAnalysis              # longest paths + fanout
 *   mill runMain riscq.dsp.bench.PathAnalysis <from> <to>  # register + combinational levels
 */
object PathAnalysis extends App {
  // Analyse the v2 (Taylor) production config (matches VivadoBench.params).
  val report = SpinalVerilog(SinCos(VivadoBench.params))
  val pa     = PA(report)

  args.toList match {
    case from :: to :: _ =>
      for (f <- pa.resolveSignal(from); t <- pa.resolveSignal(to)) {
        pa.reportRegisterLevels(f, t)
        pa.reportCombinationalLevels(f, t)
      }
    case _ =>
      pa.reportLongestPaths()
      pa.reportFanout()
  }
}
