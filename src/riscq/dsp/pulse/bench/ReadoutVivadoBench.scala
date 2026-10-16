package riscq.dsp.pulse.bench

import spinal.core._
import riscq.dsp.pulse.{ReadoutDecoder, ReadoutDecoderParams}
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Vivado out-of-context synthesis fmax bench for the [[ReadoutDecoder]]. Reuses the DUT-agnostic
 * [[riscq.bench.VivadoBench]] engine. The likely binders are the `accWidth` integrating accumulator
 * (a wide feedback add) and the per-lane `ComplexMul` datapath — fmax is a soft constraint here,
 * record the row, don't tune.
 *
 * `RISCQ_READOUT_SATURATE=true` benches the saturating ComplexMul demod (default false = simple mode).
 *
 * Run:
 *   mill runMain riscq.dsp.pulse.bench.ReadoutVivadoBench                       # generate + synth + report
 *   mill runMain riscq.dsp.pulse.bench.ReadoutVivadoBench --reparse <file.rpt>  # re-parse a report
 */
object ReadoutVivadoBench {
  val workspace = sys.env.getOrElse("RISCQ_READOUT_BENCH_WS", "bench/dsp/pulse/ReadoutVivadoBench")

  def params: ReadoutDecoderParams =
    ReadoutDecoderParams(saturate = sys.env.getOrElse("RISCQ_READOUT_SATURATE", "false").toBoolean)

  def dut: Dut = Dut(
    "ReadoutDecoder (N=16, w=16)",
    ws => SpinalConfig(targetDirectory = ws).generateVerilog(ReadoutDecoder(params))
  )

  def runVivado(ws: String = workspace): String = Engine.runVivado(dut, ws, route = false)

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[ReadoutVivadoBench] --reparse: file not found: ${f.getPath}")
        println(s"[ReadoutVivadoBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else {
        runVivado()
      }
    Engine.printReport(Engine.parse(report), workspace, label = "ReadoutDecoder")
  }
}
