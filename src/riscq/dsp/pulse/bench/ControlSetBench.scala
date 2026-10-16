package riscq.dsp.pulse.bench

import spinal.core._
import riscq.dsp.SinCosMethod
import riscq.dsp.pulse.{PulseGenerator, PulseGeneratorParams}
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Control-set bench for the [[PulseGenerator]] — the congestion analogue of the core's
 * `riscq.riscv.bench.ControlSetBench`. Replicating one pulse generator per qubit/channel on the RFSoC
 * congests routing, and a major driver of that is the number of distinct **control sets** — unique
 * `(clock, clock-enable, set/reset)` triples. On UltraScale+ all 16 FFs in a CLB slice must share a
 * clock and a set/reset (each half of 8 shares one enable), so many small/unique control sets fragment
 * the FFs across CLBs and inflate routing demand. This bench measures that directly.
 *
 * It synthesises the PulseGenerator OOC via the generic [[riscq.bench.VivadoBench]] engine (which emits
 * `report_control_sets -verbose` → `control_sets.rpt`), then parses and prints the headline **total
 * control sets**, the **fanout histogram** (how many tiny sets), the **CE/SR flip-flop distribution**
 * (how many FFs carry a real reset/init), and a per-offender **attribution** (phasor write-decode array,
 * CORDIC, TimedQueue fifo, carrier param regs).
 *
 * The DUT defaults to the **deployed gate-drive config** — the most-replicated instance — i.e.
 * `saturate = false`, `prescaleAmp = true`, `realOutput = true` (see
 * `riscq.soc.RiscqRfWithPulseTableFiber`). Env knobs A/B an experiment:
 *
 *   mill runMain riscq.dsp.pulse.bench.ControlSetBench                       # baseline (deployed cfg)
 *   RISCQ_PHASOR_SHIFT=1 mill runMain riscq.dsp.pulse.bench.ControlSetBench  # an optimisation A/B
 *   mill runMain riscq.dsp.pulse.bench.ControlSetBench --reparse <file.rpt>  # re-parse an existing report
 *
 * Correctness gate (every kept change): `riscq.dsp.pulse.sim.PulseGeneratorSim` stays bit-exact vs the
 * composed golden. fmax is a soft constraint — note it, don't tune it.
 */
object ControlSetBench {
  val workspace = sys.env.getOrElse("RISCQ_CSET_WS", "bench/dsp/pulse/ControlSetBench")

  /** The deployed gate-drive config (env-overridable for A/B experiments). */
  def params: PulseGeneratorParams = {
    def envB(k: String, d: Boolean) = sys.env.get(k).map(_.toBoolean).getOrElse(d)
    val phasorMethod = sys.env.getOrElse("RISCQ_PHASOR_METHOD", "Cordic") match {
      case "Taylor" => SinCosMethod.Taylor
      case _        => SinCosMethod.Cordic
    }
    PulseGeneratorParams(
      saturate     = envB("RISCQ_PULSE_SATURATE", false),
      prescaleAmp  = envB("RISCQ_PRESCALE_AMP", true),
      realOutput   = envB("RISCQ_REAL_OUTPUT", true),
      queueUseVec  = envB("RISCQ_QUEUE_USEVEC", false),   // default: distributed-RAM FIFO payload
      queueForFMax = envB("RISCQ_QUEUE_FORFMAX", false),  // default: plain binary FIFO pointers
                                                          // (set =true to A/B the higher-fmax register FIFO)
      phasorMethod = phasorMethod
    )
  }

  def dut: Dut = Dut(
    "PulseGenerator gate-drive (N=16, w=16; sat=false, realOut=true)",
    ws => SpinalConfig(targetDirectory = ws).generateVerilog(PulseGenerator(params))
  )

  /** Pull the first integer that follows a label like "Total control sets". */
  def num(label: String, report: String): Option[Int] =
    s"(?i)$label[^0-9]*([0-9]+)".r.findFirstMatchIn(report).map(_.group(1).toInt)

  /** The body of a named report section (`N. Title`) up to the next numbered section. */
  def section(title: String, report: String): Seq[String] = {
    val lines = report.linesIterator.toSeq
    val start = lines.lastIndexWhere(_.matches(s"\\s*\\d+\\.\\s*$title\\s*"))
    if (start < 0) return Nil
    val rest = lines.drop(start + 1)
    val end  = rest.indexWhere(_.matches("\\s*\\d+\\.\\s+\\S.*"))
    (if (end < 0) rest else rest.take(end)).filter(_.trim.nonEmpty)
  }

  /** Count detailed-table control sets whose enable name matches `re`. */
  def setsMatching(re: String, report: String): Int =
    section("Detailed Control Set Information", report).count(l => l.contains("clk") && l.matches(s".*$re.*"))

  def printReport(report: String, ws: String): Unit = {
    println()
    println("=" * 72)
    println("  PulseGenerator — Vivado control-set report   (OOC synthesis)")
    println("=" * 72)

    val total = num("Total control sets", report)
    total.foreach(n => println(f"\n    TOTAL control sets ......... $n%5d   <-- drive this down\n"))

    println("  2. Histogram (control sets by FF fanout):")
    section("Histogram", report).foreach(l => println(s"    $l"))
    println("\n  3. Flip-Flop Distribution (enable / set-reset mix):")
    section("Flip-Flop Distribution", report).foreach(l => println(s"    $l"))

    // Attribute the count to the candidate offenders so an experiment can target them directly.
    val buckets = Seq(
      "phasor write-decode (phasorGen phRe/phIm)" -> "phasorGen.*ph(Re|Im)",
      "carrier phasor snapshot (carrierGen ph)"   -> "carrierGen.*ph(Re|Im)",
      "CORDIC pipeline (X/Y/Z)"                   -> "cordic|_X_|_Y_|_Z_",
      "TimedQueue fifo"                           -> "fifo|Queue",
      "carrier param regs (amp/phase/freq)"       -> "(amp|phase|freq)Reg"
    )
    println("\n  attribution (detailed table, sets whose enable matches):")
    for ((name, re) <- buckets) {
      val n = setsMatching(re, report)
      if (n > 0) println(f"    $name%-42s $n%5d")
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
        println(s"[ControlSetBench] config: $params")
        Engine.runVivado(dut, workspace, route = false) // also writes control_sets.rpt
        Source.fromFile(s"$workspace/control_sets.rpt").mkString
      }
    printReport(report, workspace)
  }
}
