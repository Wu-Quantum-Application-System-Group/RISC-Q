package riscq.memory.bench

import spinal.core._
import riscq.memory.Uram
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Vivado synthesis (out-of-context) fmax bench for [[Uram]]. Synthesises the UltraRAM blackbox + its
 * Verilog template against the RFSoC `-2` part and reports fmax + the critical paths.
 *
 * Run:
 *   mill runMain riscq.memory.bench.UramVivadoBench                       # generate + synth + report
 *   mill runMain riscq.memory.bench.UramVivadoBench --reparse <file.rpt>  # re-parse an existing report
 *
 * Size via env: `RISCQ_URAM_WIDTH` (default 32), `RISCQ_URAM_ADDRW` (default 7),
 * `RISCQ_URAM_PIPE` (default 3).
 */
object UramVivadoBench {
  val workspace = sys.env.getOrElse("RISCQ_BENCH_WS", "bench/memory/UramVivadoBench")
  val width = sys.env.getOrElse("RISCQ_URAM_WIDTH", "32").toInt
  val addrW = sys.env.getOrElse("RISCQ_URAM_ADDRW", "7").toInt
  val pipe  = sys.env.getOrElse("RISCQ_URAM_PIPE", "3").toInt

  def dut: Dut = Dut(
    s"Uram ${width}b x ${1 << addrW} (NBPIPE=$pipe)",
    ws => SpinalConfig(targetDirectory = ws).generateVerilog(Uram(Bits(width bits), addrW, pipe))
  )

  def runVivado(ws: String = workspace): String = Engine.runVivado(dut, ws, route = false)

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[UramVivadoBench] --reparse: file not found: ${f.getPath}")
        println(s"[UramVivadoBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else runVivado()
    Engine.printReport(Engine.parse(report), workspace, label = "Uram")
  }
}
