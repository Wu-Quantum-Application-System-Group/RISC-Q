package riscq.memory.bench

import riscq.bench.{VivadoBench => Engine, VivadoRouteBench => RouteEngine}

import java.io.File
import scala.io.Source

/**
 * Post-place&route fmax bench for [[riscq.memory.Uram]] — full implementation flow for the real,
 * sign-off fmax. Reuses [[UramVivadoBench.dut]] (size via the same `RISCQ_URAM_*` env vars).
 *
 * Run:
 *   mill runMain riscq.memory.bench.UramVivadoRouteBench                       # generate + P&R + report
 *   mill runMain riscq.memory.bench.UramVivadoRouteBench --reparse <file.rpt>  # re-parse an existing report
 */
object UramVivadoRouteBench {
  val workspace = sys.env.getOrElse("RISCQ_ROUTE_WS", "bench/memory/UramVivadoRouteBench")

  def runVivado(ws: String = workspace): String = RouteEngine.runVivado(UramVivadoBench.dut, ws)

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[UramVivadoRouteBench] --reparse: file not found: ${f.getPath}")
        println(s"[UramVivadoRouteBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else runVivado()
    Engine.printReport(Engine.parse(report), workspace, label = "Uram", flow = "post-route")
  }
}
