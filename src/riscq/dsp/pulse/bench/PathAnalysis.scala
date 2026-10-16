package riscq.dsp.pulse.bench

import spinal.core._
import riscq.dsp.pulse.{PulseGenerator, PulseGeneratorParams}
import riscq.bench.{PathAnalysis => PA}

/**
 * Static pre-synthesis path analysis of the [[PulseGenerator]] toplevel — thin entry point over
 * [[riscq.bench.PathAnalysis]]. Tool-free (no Vivado in the loop): the same "where to optimise"
 * longest-path / fanout / register-level view that [[VivadoBench]] gives, but instant.
 *
 * Run:
 *   mill runMain riscq.dsp.pulse.bench.PathAnalysis              # longest paths + fanout
 *   mill runMain riscq.dsp.pulse.bench.PathAnalysis <from> <to>  # register + combinational levels
 */
object PathAnalysis extends App {
  // Analyse the default production config — the same DUT VivadoBench/VivadoRouteBench synthesise.
  val report = SpinalVerilog(PulseGenerator(VivadoBench.params))
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
