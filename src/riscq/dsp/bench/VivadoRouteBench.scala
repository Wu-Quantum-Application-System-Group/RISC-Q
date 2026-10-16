package riscq.dsp.bench

import riscq.bench.{VivadoBench => Engine, VivadoRouteBench => RouteEngine}

import java.io.File
import scala.io.Source

/**
 * Post-place&route fmax bench for the [[riscq.dsp.SinCos]] module — full implementation flow
 * for the real, sign-off fmax. Reuses [[VivadoBench.dut]].
 *
 * Run:
 *   mill runMain riscq.dsp.bench.VivadoRouteBench                       # generate + P&R + report
 *   mill runMain riscq.dsp.bench.VivadoRouteBench --reparse <file.rpt>  # just re-parse an existing report
 */
object VivadoRouteBench {
  val workspace = sys.env.getOrElse("RISCQ_ROUTE_WS", "bench/dsp/VivadoRouteBench")

  def runVivado(ws: String = workspace): String = RouteEngine.runVivado(VivadoBench.dut, ws)

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[VivadoRouteBench] --reparse: file not found: ${f.getPath}")
        println(s"[VivadoRouteBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else {
        runVivado()
      }
    Engine.printReport(Engine.parse(report), workspace, label = "SinCos", flow = "post-route")
  }
}
