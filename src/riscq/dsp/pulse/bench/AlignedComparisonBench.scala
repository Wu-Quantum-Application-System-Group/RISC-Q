package riscq.dsp.pulse.bench

import spinal.core._
import riscq.dsp.pulse.{PulseGenerator, PulseGeneratorAligned, PulseGeneratorParams}
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Side-by-side Vivado resource + fmax comparison of the two parameter-alignment schemes for the same
 * datapath:
 *   - `PulseGenerator`        — one lead-time `TimedQueue` per parameter (six queues).
 *   - `PulseGeneratorAligned` — one combined `TimedQueue` for {amp,phase,addr,dur} + QubiC-style SRL
 *                               alignment (freq keeps its own queues).
 *
 * Both DUTs use the identical config ([[VivadoBench.params]], env-overridable the same way), so the
 * only delta is the alignment hardware. The engine ([[riscq.bench.VivadoBench]]) synthesises each
 * out-of-context against a tight clock; this object parses the worst-slack fmax and the
 * `report_utilization` table (LUT / SRL / FF / CARRY8 / DSP / BRAM) of each and prints the diff.
 *
 * Run:
 *   mill runMain riscq.dsp.pulse.bench.AlignedComparisonBench            # synth (fast, optimistic)
 *   RISCQ_CMP_ROUTE=true mill runMain riscq.dsp.pulse.bench.AlignedComparisonBench  # post-P&R (sign-off)
 */
object AlignedComparisonBench {
  val baseWs = sys.env.getOrElse("RISCQ_CMP_WS", "bench/dsp/pulse/AlignedCompare")
  val route  = sys.env.getOrElse("RISCQ_CMP_ROUTE", "false").toBoolean

  /** Resource counts pulled from a workspace's `util.rpt` (Vivado report_utilization). */
  case class Util(lut: Int, srl: Int, ff: Int, carry8: Int, dsp: Int, bram36: Int)

  /** Parse one `report_utilization` value row: `| <name> | <used> | ...`. First numeric column. The
   *  `\*?` tolerates the trailing `*` Vivado adds to some row names in the *synthesis* report but not
   *  the *post-route* one (e.g. `CLB LUTs*` vs `CLB LUTs`, `RAMB36/FIFO*` vs `RAMB36/FIFO`). */
  private def row(rpt: String, name: String): Int = {
    val re = ("(?m)^\\|\\s*" + java.util.regex.Pattern.quote(name) + "\\*?\\s*\\|\\s*(\\d+)\\s*\\|").r
    re.findFirstMatchIn(rpt).map(_.group(1).toInt).getOrElse(-1)
  }
  private def readUtil(ws: String): Util = {
    val f = new File(s"$ws/util.rpt")
    if (!f.isFile) return Util(-1, -1, -1, -1, -1, -1)
    val s = Source.fromFile(f).mkString
    Util(
      lut    = row(s, "CLB LUTs"),
      srl    = row(s, "LUT as Shift Register"),
      ff     = row(s, "CLB Registers"),
      carry8 = row(s, "CARRY8"),
      dsp    = row(s, "DSPs"),
      bram36 = row(s, "RAMB36/FIFO"))
  }

  case class Result(label: String, fmax: Double, wns: Double, util: Util)

  private def benchOne(label: String, ws: String, dut: Dut): Result = {
    val report = Engine.runVivado(dut, ws, route = route)
    val paths  = Engine.parse(report)
    val wns    = if (paths.isEmpty) Double.NaN else paths.map(_.slackNs).min
    val fmax   = if (paths.isEmpty) Double.NaN else 1000.0 / (Engine.periodNs - wns)
    Result(label, fmax, wns, readUtil(ws))
  }

  def main(args: Array[String]): Unit = {
    val params = VivadoBench.params
    val flow   = if (route) "post-route (sign-off)" else "synthesis, out-of-context (optimistic)"

    val baseline = benchOne("PulseGenerator (6 queues)", s"${baseWs}_queued",
      Dut("PulseGenerator", ws => SpinalConfig(targetDirectory = ws).generateVerilog(PulseGenerator(params))))
    val aligned = benchOne("PulseGeneratorAligned (1 queue + SRL)", s"${baseWs}_aligned",
      Dut("PulseGeneratorAligned", ws => SpinalConfig(targetDirectory = ws).generateVerilog(PulseGeneratorAligned(params))))

    def pct(a: Int, b: Int): String =
      if (a <= 0 || b < 0) "  n/a" else f"${100.0 * (b - a) / a}%+5.1f%%"
    def fpct(a: Double, b: Double): String = f"${100.0 * (b - a) / a}%+5.1f%%"

    println()
    println("=" * 78)
    println(s"  PulseGenerator alignment comparison   ($flow)")
    println(f"    device ${Engine.device}   N=${params.batchSize} w=${params.dataWidth} " +
      f"saturate=${params.saturate} phasor=${params.phasorMethod} queueDepth=${params.queueDepth}")
    println("=" * 78)
    val b = baseline; val a = aligned
    println(f"  ${"metric"}%-16s ${"queued (baseline)"}%18s ${"aligned (QubiC)"}%18s ${"Δ"}%9s")
    println("  " + "-" * 64)
    println(f"  ${"fmax (MHz)"}%-16s ${b.fmax}%18.0f ${a.fmax}%18.0f ${fpct(b.fmax, a.fmax)}%9s")
    println(f"  ${"WNS (ns)"}%-16s ${b.wns}%18.3f ${a.wns}%18.3f ${""}%9s")
    println(f"  ${"LUT (total)"}%-16s ${b.util.lut}%18d ${a.util.lut}%18d ${pct(b.util.lut, a.util.lut)}%9s")
    println(f"  ${"  as SRL"}%-16s ${b.util.srl}%18d ${a.util.srl}%18d ${pct(b.util.srl, a.util.srl)}%9s")
    println(f"  ${"FF"}%-16s ${b.util.ff}%18d ${a.util.ff}%18d ${pct(b.util.ff, a.util.ff)}%9s")
    println(f"  ${"CARRY8"}%-16s ${b.util.carry8}%18d ${a.util.carry8}%18d ${pct(b.util.carry8, a.util.carry8)}%9s")
    println(f"  ${"DSP48E2"}%-16s ${b.util.dsp}%18d ${a.util.dsp}%18d ${pct(b.util.dsp, a.util.dsp)}%9s")
    println(f"  ${"BRAM36"}%-16s ${b.util.bram36}%18d ${a.util.bram36}%18d ${pct(b.util.bram36, a.util.bram36)}%9s")
    println("  " + "-" * 64)
    println(s"  (Δ = aligned relative to queued; negative LUT/FF = aligned is smaller)")
    println(s"  reports: ${baseWs}_queued/  ${baseWs}_aligned/  (util.rpt, timing_paths.rpt, control_sets.rpt)")
    println()
  }
}
