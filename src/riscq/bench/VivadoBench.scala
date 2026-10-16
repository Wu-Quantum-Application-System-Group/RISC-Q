package riscq.bench

import org.apache.commons.io.FileUtils
import spinal.core._
import spinal.lib.DoCmd

import java.io.{File, PrintWriter}
import scala.io.Source

/**
 * A device-under-test for the Vivado benches: a human label for the logs, plus a function that emits
 * the DUT's Verilog into the given workspace directory and returns the SpinalHDL report (we read the
 * toplevel name and the list of generated RTL sources out of it). Decoupling "what to build" from
 * "how to bench it" is what lets the same flow time the riscq core, a DSP module, or anything else.
 *
 * Example (the riscq core):
 * {{{
 *   Dut("riscq core", ws => SpinalConfig(targetDirectory = ws).generateVerilog(Riscq(plugins)))
 * }}}
 */
case class Dut(label: String, emit: String => SpinalReport[_ <: Component])

/**
 * A small, self-contained, **DUT-agnostic** Vivado fmax benchmark whose report we fully control.
 *
 * `spinal.lib.eda.bench.Bench` + `VivadoFlow` run Vivado and then throw away everything except one
 * fmax number and a utilization string: Vivado's per-path critical-path detail is never surfaced
 * (`Report` only exposes `getFMax`/`getArea`). This tool keeps it, because the question is *where*
 * to optimise, not just the headline number.
 *
 * It runs synthesis only, out-of-context, on the configured target part against a deliberately tight
 * clock, then parses Vivado's `report_timing` output and prints:
 *   - the estimated fmax (from the worst negative slack vs the tight clock), and
 *   - the top critical paths, collapsed by source/destination register so the 32 bits of one bus
 *     show as a single "where to optimise" entry, each with its logic depth, logic/route split and
 *     the cell mix on the path (CARRY8/LUT… counts straight from Vivado's "Logic Levels").
 *
 * Synthesis-only is intentional: fast enough for an edit/measure loop, and the *shape* of the
 * critical path is what we tune. It is optimistic vs post-route timing — use [[VivadoRouteBench]] for
 * sign-off. This complements [[PathAnalysis]], which gives the same "where" view with no tool in the
 * loop. Drive it from a thin per-DUT wrapper (e.g. `riscq.riscv.bench.VivadoBench`) that supplies a
 * [[Dut]]; this object has no `main`.
 *
 * Config is environment-overridable and project-wide (defaults match the riscq RFSoC target):
 *   - `RISCQ_VIVADO_BIN`, `RISCQ_DEVICE`, `RISCQ_FMAX_TARGET_MHZ`, `RISCQ_FMAX_TOP_PATHS`.
 */
object VivadoBench {
  val vivadoBin = sys.env.getOrElse("RISCQ_VIVADO_BIN", resolveVivadoBin)
  // Default to the `vivado` on PATH (its bin dir); RISCQ_VIVADO_BIN overrides. Falls back to the
  // 2026.1 install if PATH has none.
  def resolveVivadoBin: String =
    sys.env.getOrElse("PATH", "").split(File.pathSeparatorChar)
      .map(new File(_, "vivado")).find(_.canExecute).map(_.getParent)
      .getOrElse("/opt/Xilinx/2026.1/Vivado/bin")
  val device    = sys.env.getOrElse("RISCQ_DEVICE", "xczu49dr-ffvf1760-2-e")
  val targetMhz = sys.env.getOrElse("RISCQ_FMAX_TARGET_MHZ", "1000").toDouble
  val topPaths  = sys.env.getOrElse("RISCQ_FMAX_TOP_PATHS", "15").toInt

  val periodNs = 1000.0 / targetMhz

  /** One parsed `report_timing` path block (the fields we report on). */
  case class Path(slackNs: Double, met: Boolean, source: String, dest: String,
                  delayNs: Double, logicNs: Double, routeNs: Double,
                  levels: Int, cells: String)

  // ================================================================================================
  // Run: emit the DUT's Verilog, write a tcl that synthesises out-of-context (optionally also
  // place&route — see VivadoRouteBench) + dumps timing, run Vivado, return report_timing text.
  // ================================================================================================
  def runVivado(dut: Dut, ws: String, route: Boolean = false): String = {
    val wsDir = new File(ws)
    FileUtils.deleteDirectory(wsDir)
    wsDir.mkdirs()

    // 1) emit the DUT's RTL straight into the workspace
    val gen = dut.emit(ws)
    val readCmds = gen.rtlSourcesPaths.toSeq
      .filter(p => p.endsWith(".v") || p.endsWith(".sv"))
      .map(p => s"read_verilog ${new File(p).getName}")
      .mkString("\n")

    // 2) constrain a tight clock on `clk` (the SpinalHDL default clock port name)
    write(s"$ws/bench.xdc", f"create_clock -period $periodNs%.3f [get_ports clk]")

    // 3) synthesis (optionally opt+place+route), then the reports we actually want to keep
    val implement = if (route) "opt_design\nplace_design\nroute_design\n" else ""
    write(s"$ws/bench.tcl",
      s"""$readCmds
         |read_xdc bench.xdc
         |synth_design -top ${gen.toplevelName} -part $device -mode out_of_context
         |${implement}report_timing_summary -delay_type max -file timing_summary.rpt
         |report_timing -delay_type max -max_paths ${topPaths * 4} -nworst 1 -path_type full -file timing_paths.rpt
         |report_utilization -file util.rpt
         |report_control_sets -verbose -file control_sets.rpt
         |""".stripMargin)

    val flow = if (route) "place&route" else "synthesise"
    println(s"[VivadoBench] $flow ${gen.toplevelName} (${dut.label}) for $device (target ${targetMhz.toInt} MHz)…")
    DoCmd.doCmd(s"$vivadoBin/vivado -nojournal -log vivado.log -mode batch -source bench.tcl", ws)

    Source.fromFile(s"$ws/timing_paths.rpt").mkString
  }

  // ================================================================================================
  // Parse: split report_timing output into per-path blocks and pull out the fields we report.
  // ================================================================================================
  def parse(report: String): Seq[Path] = {
    // each path starts at a "Slack (MET|VIOLATED)" line and runs up to the next one (or EOF)
    val blockRe = "(?ms)^Slack \\((MET|VIOLATED)\\)\\s*:\\s*(-?[0-9.]+)ns.*?(?=^Slack \\(|\\z)".r

    def field(re: String, s: String): Option[String] = re.r.findFirstMatchIn(s).map(_.group(1))

    blockRe.findAllMatchIn(report).flatMap { m =>
      val block = m.matched
      for {
        source <- field("Source:\\s*(\\S+)", block)
        dest   <- field("Destination:\\s*(\\S+)", block)
        delay  <- "Data Path Delay:\\s*([0-9.]+)ns\\s*\\(logic ([0-9.]+)ns.*?route ([0-9.]+)ns".r
                    .findFirstMatchIn(block)
      } yield {
        val levels = "Logic Levels:\\s*([0-9]+)\\s*\\(([^)]*)\\)".r.findFirstMatchIn(block)
        Path(
          slackNs = m.group(2).toDouble,
          met     = m.group(1) == "MET",
          source  = source,
          dest    = dest,
          delayNs = delay.group(1).toDouble,
          logicNs = delay.group(2).toDouble,
          routeNs = delay.group(3).toDouble,
          levels  = levels.map(_.group(1).toInt).getOrElse(0),
          cells   = levels.map(_.group(2)).getOrElse("?")
        )
      }
    }.toSeq
  }

  // ================================================================================================
  // Report: fmax from worst slack, then the top paths collapsed by (source reg -> dest reg).
  // ================================================================================================
  def printReport(paths: Seq[Path], ws: String = "bench", label: String = "DUT",
                  flow: String = "synthesis, out-of-context — optimistic"): Unit = {
    if (paths.isEmpty) {
      println("[VivadoBench] no timing paths parsed — check the Vivado run / report format.")
      return
    }

    val wns  = paths.map(_.slackNs).min
    val fmax = 1000.0 / (periodNs - wns) // MHz; achievable period (ns) = target period - slack

    println()
    println("=" * 72)
    println(s"  $label Vivado fmax benchmark   ($flow)")
    println(f"    device   $device")
    println(f"    clock    ${targetMhz.toInt} MHz target  (${periodNs}%.3f ns)")
    println(f"    fmax   ~ ${fmax}%.0f MHz   (WNS ${wns}%+.3f ns over ${paths.size} endpoints)")
    println("=" * 72)
    println()
    println(s"  Top ${topPaths} critical paths (collapsed by register, worst slack per group):")
    println()

    // Normalise a flop pin down to one logical register: drop the pin (/C, /CE, /D…), the bit index,
    // the FF "_reg" suffix, and Vivado's high-fanout replica suffixes (_rep, _rep__0, …) — so all 32
    // bits of a bus, and every replica of one register, collapse into a single "where to optimise" row.
    def regName(pin: String): String =
      pin.replaceAll("/.*$", "")
        .replaceAll("\\[[0-9]+\\]", "")
        .replaceAll("(_reg)?(_rep(__[0-9]+)?)?$", "")

    val groups = paths
      .groupBy(p => (regName(p.source), regName(p.dest)))
      .values.map(_.minBy(_.slackNs))
      .toSeq.sortBy(_.slackNs)
      .take(topPaths)

    for ((p, i) <- groups.zipWithIndex) {
      val pathFmax = 1000.0 / (periodNs - p.slackNs)
      val logicPct = if (p.delayNs > 0) 100 * p.logicNs / p.delayNs else 0.0
      println(f"  #${i + 1}%-2d slack ${p.slackNs}%+7.3f ns   fmax ${pathFmax}%4.0f MHz   "
        + f"levels ${p.levels}%2d   logic ${logicPct}%2.0f%% / route ${100 - logicPct}%2.0f%%")
      println(s"      ${regName(p.source)}  ->  ${regName(p.dest)}")
      println(s"      cells: ${p.cells}")
      println()
    }

    println(s"  full per-path detail: $ws/timing_paths.rpt   (summary: timing_summary.rpt)")
  }

  def computeFmax(paths: Seq[Path]): Double = {
    val wns = paths.map(_.slackNs).min
    1000.0 / (periodNs - wns)
  }

  private def write(path: String, content: String): Unit = {
    val w = new PrintWriter(new File(path)); try w.write(content) finally w.close()
  }
}
