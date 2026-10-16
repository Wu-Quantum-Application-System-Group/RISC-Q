package riscq.riscv.bench

import riscq.bench.{VivadoBench => Engine}

import java.io.File
import scala.io.Source

/**
 * Control-set bench for the **riscq RISC-V core**. Replicating one core many times on the RFSoC
 * congests routing, and a major driver of that is the number of distinct **control sets** — unique
 * `(clock, clock-enable, set/reset)` triples. On UltraScale+ all 16 FFs in a CLB must share a clock
 * and a set/reset (each half of 8 shares one enable), so many small/unique control sets fragment the
 * FFs across CLBs and inflate routing demand. This bench measures that directly.
 *
 * It reuses [[VivadoBench]]'s core DUT + `RiscqParam` env A/B knobs and the generic
 * [[riscq.bench.VivadoBench]] OOC-synthesis flow (which now also emits `control_sets.rpt` via
 * `report_control_sets -verbose`), then parses that report for:
 *   - the headline **unique control-set count** (the number to drive down), and
 *   - the **enable / set-reset histograms** (how many sets use a real CE vs a real SR — the small
 *     ones are the congestion offenders, the ones to merge or let synthesis fold into LUT logic).
 *
 * Run:
 *   mill runMain riscq.riscv.bench.ControlSetBench                       # synth + report
 *   mill runMain riscq.riscv.bench.ControlSetBench --reparse <file.rpt>  # re-parse an existing report
 *
 * The same env knobs as [[VivadoBench]] select the config (`RISCQ_WITH_MUL`, `RISCQ_GSHARE_MEM`,
 * `RISCQ_CSR_WARL`, `RISCQ_PLUGINS`, …), so a one-line flip lets you A/B a control-set experiment.
 */
object ControlSetBench {
  val workspace = sys.env.getOrElse("RISCQ_CSET_WS", "bench/ControlSetBench")

  /** Pull the first integer that follows a label like "Number of unique control sets". */
  def num(label: String, report: String): Option[Int] =
    s"(?i)$label[^0-9]*([0-9]+)".r.findFirstMatchIn(report).map(_.group(1).toInt)

  /** Print the body of a named report section (`N. Title`) up to the next numbered section. */
  def section(title: String, report: String): Seq[String] = {
    val lines = report.linesIterator.toSeq
    // The same headers appear in the Table of Contents, so take the *last* match (the real section).
    val start = lines.lastIndexWhere(_.matches(s"\\s*\\d+\\.\\s*$title\\s*"))
    if (start < 0) return Nil
    val rest = lines.drop(start + 1)
    val end  = rest.indexWhere(_.matches("\\s*\\d+\\.\\s+\\S.*"))
    (if (end < 0) rest else rest.take(end)).filter(_.trim.nonEmpty)
  }

  /** Count how many detailed-table control sets are driven by an enable matching `re`. */
  def setsMatching(re: String, report: String): Int =
    section("Detailed Control Set Information", report).count(l => l.contains("clk") && l.matches(s".*$re.*"))

  def printReport(report: String, ws: String): Unit = {
    println()
    println("=" * 72)
    println("  riscq core — Vivado control-set report   (OOC synthesis)")
    println("=" * 72)

    // The headline figure congestion tracks: fewer unique (clk, CE, SR) triples = denser packing.
    val total = num("Total control sets", report)
    total.foreach(n => println(f"\n    TOTAL control sets ......... $n%5d   <-- drive this down\n"))

    // Sections 2 (fanout histogram) and 3 (CE/SR distribution) are the actionable summaries: the
    // low-fanout bucket counts the tiny sets that fragment slices; the CE/SR table shows how many
    // registers carry a real reset (init) we might be able to drop.
    println("  2. Histogram (control sets by FF fanout):")
    section("Histogram", report).foreach(l => println(s"    $l"))
    println("\n  3. Flip-Flop Distribution (enable / set-reset mix):")
    section("Flip-Flop Distribution", report).foreach(l => println(s"    $l"))

    // Attribute the count to the worst offenders so an experiment can target them directly.
    val gshare = setsMatching("regCounters", report)
    val csr    = setsMatching("CsrPlugin", report)
    if (gshare + csr > 0) {
      println("\n  attribution (detailed table):")
      if (gshare > 0) println(f"    GShare counter sets (regCounters) .. $gshare%5d")
      if (csr > 0)    println(f"    CSR register sets (CsrPlugin) ...... $csr%5d")
    }

    println(s"\n  full per-set detail: $ws/control_sets.rpt")
    println("=" * 72)
  }

  def main(args: Array[String]): Unit = {
    val reparseIdx = args.indexOf("--reparse")
    val report =
      if (reparseIdx >= 0 && reparseIdx + 1 < args.length) {
        val f = new File(args(reparseIdx + 1))
        if (!f.isFile) sys.error(s"[ControlSetBench] --reparse: file not found: ${f.getPath}")
        println(s"[ControlSetBench] re-parsing existing report ${f.getPath}")
        Source.fromFile(f).mkString
      } else {
        println(s"[ControlSetBench] plugin set: ${VivadoBench.pluginSet}")
        Engine.runVivado(VivadoBench.dut, workspace, route = false) // also writes control_sets.rpt
        Source.fromFile(s"$workspace/control_sets.rpt").mkString
      }
    printReport(report, workspace)
  }
}
