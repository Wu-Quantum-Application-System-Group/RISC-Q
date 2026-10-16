package riscq.dsp.pulse.bench

import spinal.core._
import riscq.dsp.pulse.ReadoutDecoder
import riscq.bench.{PathAnalysis => PA}

/**
 * Static pre-synthesis path analysis of the [[ReadoutDecoder]] — thin, tool-free entry point over
 * [[riscq.bench.PathAnalysis]] (longest combinational paths + high-fanout nets + register levels).
 * Analyses the same DUT `ReadoutVivadoBench`/`ReadoutVivadoRouteBench` synthesise.
 *
 * Run:
 *   mill runMain riscq.dsp.pulse.bench.ReadoutPathAnalysis              # longest paths + fanout
 *   mill runMain riscq.dsp.pulse.bench.ReadoutPathAnalysis <from> <to>  # register + combinational levels
 */
object ReadoutPathAnalysis extends App {
  val report = SpinalVerilog(ReadoutDecoder(ReadoutVivadoBench.params))
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
