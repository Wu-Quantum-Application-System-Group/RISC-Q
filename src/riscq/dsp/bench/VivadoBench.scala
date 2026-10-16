package riscq.dsp.bench

import spinal.core._
import riscq.dsp.{SinCos, SinCosParams, SinCosMethod}
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Vivado synthesis (out-of-context) fmax bench for the [[SinCos]] module.
 *
 * Run:
 *   mill runMain riscq.dsp.bench.VivadoBench                       # generate + synth + report
 *   mill runMain riscq.dsp.bench.VivadoBench --reparse <file.rpt>  # just re-parse an existing report
 */
object VivadoBench {
  val workspace = sys.env.getOrElse("RISCQ_BENCH_WS", "bench/dsp/VivadoBench")

  // v2 (Taylor) production config — the apples-to-apples DUT vs the DDS-compiler IP.
  def params: SinCosParams = SinCosParams(tableIndexBits = 8, method = SinCosMethod.Taylor)

  def dut: Dut = Dut(
    "SinCos v2 (16/16/8 Taylor)",
    ws => SpinalConfig(targetDirectory = ws).generateVerilog(SinCos(params))
  )

  def runVivado(ws: String = workspace): String = Engine.runVivado(dut, ws, route = false)

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[VivadoBench] --reparse: file not found: ${f.getPath}")
        println(s"[VivadoBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else {
        runVivado()
      }
    Engine.printReport(Engine.parse(report), workspace, label = "SinCos")
  }
}
