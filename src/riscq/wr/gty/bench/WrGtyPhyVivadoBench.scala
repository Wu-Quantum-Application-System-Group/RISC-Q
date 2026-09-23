package riscq.wr.gty.bench

import spinal.core._
import riscq.bench.{Dut, VivadoBench => Engine}
import riscq.wr.gty.WrGtyPhy

import java.io.File
import scala.io.Source

/**
 * Vivado OOC synthesis of the real-GTY [[WrGtyPhy]] — the W4 blackbox-correctness gate
 * (spec 01 §9): the harvested `GTYE4_CHANNEL`/`GTYE4_COMMON` wrappers, the `BUFG_GT`/
 * `IBUFDS_GTE4` netlist, and the fabric controllers must synthesize clean on the ZCU216 part.
 *
 * Run: `mill runMain riscq.wr.gty.bench.WrGtyPhyVivadoBench`
 */
object WrGtyPhyVivadoBench {
  val workspace = sys.env.getOrElse("RISCQ_BENCH_WS", "bench/wr/gty/VivadoBench")

  def dut: Dut = Dut(
    "WrGtyPhy (1 GTY channel, QPLL0, raw 20-bit, buffers bypassed)",
    ws => SpinalConfig(targetDirectory = ws).generateVerilog(WrGtyPhy())
  )

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[WrGtyPhyVivadoBench] --reparse: file not found: ${f.getPath}")
        Source.fromFile(f).mkString
      } else {
        Engine.runVivado(dut, workspace, route = false)
      }
    Engine.printReport(Engine.parse(report), workspace, label = "WrGtyPhy")
  }
}
