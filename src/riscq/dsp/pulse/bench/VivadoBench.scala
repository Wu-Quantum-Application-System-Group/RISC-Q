package riscq.dsp.pulse.bench

import spinal.core._
import riscq.dsp.{SinCosMethod}
import riscq.dsp.pulse.{PulseGenerator, PulseGeneratorParams}
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Vivado synthesis (out-of-context) fmax bench for the [[PulseGenerator]] toplevel — the
 * resource-budget DUT (DSP48E2 use is dominated by 2 × 16 carrier/envelope `ComplexMul` + 1
 * `freq×time` multiplier; the two CORDIC gains are fabric shift-add). Reuses the DUT-agnostic
 * [[riscq.bench.VivadoBench]] engine.
 *
 * Run:
 *   mill runMain riscq.dsp.pulse.bench.VivadoBench                       # generate + synth + report
 *   mill runMain riscq.dsp.pulse.bench.VivadoBench --reparse <file.rpt>  # just re-parse an existing report
 */
object VivadoBench {
  val workspace = sys.env.getOrElse("RISCQ_BENCH_WS", "bench/dsp/pulse/VivadoBench")

  // Default production config (N = 16, w = 16) — matches PulseGen / the end-to-end TB.
  // `RISCQ_PULSE_SATURATE=false` benches the simple mode (no saturation).
  // `RISCQ_PHASOR_METHOD=Taylor` benches the Taylor SinCos phasor (2 DSP, 1 BRAM, shorter latency).
  def params: PulseGeneratorParams = {
    val saturate = sys.env.getOrElse("RISCQ_PULSE_SATURATE", "true").toBoolean
    val phasorMethod = sys.env.getOrElse("RISCQ_PHASOR_METHOD", "Cordic") match {
      case "Taylor" => SinCosMethod.Taylor
      case _        => SinCosMethod.Cordic
    }
    PulseGeneratorParams(saturate = saturate, phasorMethod = phasorMethod)
  }

  def dut: Dut = Dut(
    "PulseGenerator (N=16, w=16)",
    ws => SpinalConfig(targetDirectory = ws).generateVerilog(PulseGenerator(params))
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
    Engine.printReport(Engine.parse(report), workspace, label = "PulseGenerator")
  }
}
