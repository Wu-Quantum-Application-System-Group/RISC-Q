package riscq.dsp.pulse.bench

import riscq.bench.{VivadoBench => Engine, VivadoRouteBench => RouteEngine}

import java.io.File
import scala.io.Source

/**
 * Post-place&route fmax sign-off bench for the [[riscq.dsp.pulse.ReadoutDecoder]] — full
 * implementation flow for the real fmax. Reuses [[ReadoutVivadoBench.dut]].
 *
 * Run:
 *   mill runMain riscq.dsp.pulse.bench.ReadoutVivadoRouteBench                       # generate + P&R + report
 *   mill runMain riscq.dsp.pulse.bench.ReadoutVivadoRouteBench --reparse <file.rpt>  # re-parse a report
 */
object ReadoutVivadoRouteBench {
  val workspace = sys.env.getOrElse("RISCQ_READOUT_ROUTE_WS", "bench/dsp/pulse/ReadoutVivadoRouteBench")

  def runVivado(ws: String = workspace): String = RouteEngine.runVivado(ReadoutVivadoBench.dut, ws)

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[ReadoutVivadoRouteBench] --reparse: file not found: ${f.getPath}")
        println(s"[ReadoutVivadoRouteBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else {
        runVivado()
      }
    Engine.printReport(Engine.parse(report), workspace, label = "ReadoutDecoder", flow = "post-route")
  }
}
