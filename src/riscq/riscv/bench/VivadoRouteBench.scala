package riscq.riscv.bench

import riscq.bench.{VivadoBench => Engine, VivadoRouteBench => RouteEngine}

import java.io.File
import scala.io.Source

/**
 * Post-place&route fmax bench for the **riscq RISC-V core**: the same core DUT and env-variable A/B
 * knobs as [[VivadoBench]] (it reuses `VivadoBench.dut`), but run through the generic
 * [[riscq.bench.VivadoRouteBench]] full implementation flow for the real, sign-off fmax.
 *
 * Run:
 *   mill runMain riscq.riscv.bench.VivadoRouteBench                        # generate + P&R + report
 *   mill runMain riscq.riscv.bench.VivadoRouteBench --reparse <file.rpt>   # just re-parse an existing report
 */
object VivadoRouteBench {
  val workspace = sys.env.getOrElse("RISCQ_ROUTE_WS", "bench/VivadoRouteBench")

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
        println(s"[VivadoRouteBench] plugin set: ${VivadoBench.pluginSet}  "
          + s"skidAfter: ${VivadoBench.benchParam().skidAfter.mkString("[", ",", "]")}")
        runVivado()
      }
    Engine.printReport(Engine.parse(report), workspace, label = "riscq core", flow = "post-route")
  }
}
