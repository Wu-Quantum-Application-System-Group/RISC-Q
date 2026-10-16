package riscq.riscv.bench

import spinal.core._
import spinal.lib.misc.plugin.Hostable
import riscq.riscv.{Riscq, RiscqParam}
import riscq.bench.{VivadoBench => Engine, Dut}

import java.io.File
import scala.io.Source

/**
 * Vivado synthesis fmax bench for the **riscq RISC-V core**: it owns the core-specific knobs
 * (plugin set + `RiscqParam` env-variable A/B overrides) and feeds the resulting netlist to the
 * generic [[riscq.bench.VivadoBench]] engine, which does the synthesis run, parsing and reporting.
 *
 * Run:
 *   mill runMain riscq.riscv.bench.VivadoBench                        # generate + synth + report
 *   mill runMain riscq.riscv.bench.VivadoBench --reparse <file.rpt>   # just re-parse an existing report
 */
object VivadoBench {
  val workspace = sys.env.getOrElse("RISCQ_BENCH_WS", "bench/VivadoBench")

  // Which plugin set to benchmark, so the same flow can A/B branch prediction without editing source:
  //   full      = core + BTB + GShare (the shipping default)
  //   btb       = core + BTB only (always-taken on a hit)
  //   nopredict = core, no fetch-stage predictor (degrades to redirect-when-taken)
  val pluginSet = sys.env.getOrElse("RISCQ_PLUGINS", "full")

  /** Read a boolean `RiscqParam` flag from the environment, falling back to the param default when
   *  unset (so a same-tree A/B can flip it without hard-coding the default). */
  def flag(name: String, default: Boolean): Boolean =
    sys.env.get(name).map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(default)

  /**
   * The `RiscqParam` to benchmark. Defaults to `RiscqParam()`, with env-variable A/B overrides:
   *   - `RISCQ_SKID_AFTER` — skid-buffer placement: comma-separated boundary indices,
   *     e.g. `=2` (decode->execute), or `=` (none). Default: `Seq(fetchLatency)`.
   *   - `RISCQ_GSHARE_MEM` — `=1`/`=0` toggles `gshareMem` (GShare counters in a `Mem`).
   *   - `RISCQ_CSR_WARL`   — `=1`/`=0` toggles `csrWarl` (CSR WARL/WLRL trimming).
   *   - `RISCQ_ALU_NO_FASTFWD` / `RISCQ_ALU_ONEHOT` — the `aluNoFastForward` / `aluResultOneHot` levers.
   *   - `RISCQ_CSRCE_MAXFANOUT` — the fanout cap on the CSR commit qualifier (0 = off, the default).
   *     (The structural levers and the jumpAt / fetch MAX_FANOUT=16 caps are baked in — no longer
   *     toggle-able.)
   *   - `RISCQ_FETCH_LATENCY` / `RISCQ_FETCH_PC_WIDTH` — the SoC fetch geometry
   *     (`PulseTableSoc` runs `fetchLatency=4, fetchPcWidth=14`; the core default is 1/full).
   * Shared with [[VivadoRouteBench]], [[VivadoABCheck]] and [[ControlSetBench]] — so any of them
   * can bench the exact SoC core config from the environment alone.
   */
  def benchParam(): RiscqParam = {
    val skid = sys.env.get("RISCQ_SKID_AFTER")
      .map(s => Some(s.split(",").map(_.trim).filter(_.nonEmpty).map(_.toInt).toSeq))
      .getOrElse(RiscqParam().skidAfterOverride)
    RiscqParam(
      skidAfterOverride = skid,
      withMul = flag("RISCQ_WITH_MUL", RiscqParam().withMul),
      gshareMem = flag("RISCQ_GSHARE_MEM", RiscqParam().gshareMem),
      csrWarl = flag("RISCQ_CSR_WARL", RiscqParam().csrWarl),
      aluOperandMaxFanout =
        sys.env.get("RISCQ_ALU_MAXFANOUT").map(_.toInt).getOrElse(RiscqParam().aluOperandMaxFanout),
      csrCommitMaxFanout =
        sys.env.get("RISCQ_CSRCE_MAXFANOUT").map(_.toInt).getOrElse(RiscqParam().csrCommitMaxFanout),
      // Latency-for-margin flags: late BTB re-steer / late BAD_TARGET compare (both default off).
      btbPredictLate = flag("RISCQ_BTB_LATE", RiscqParam().btbPredictLate),
      lateBadTarget = flag("RISCQ_LATE_BADTARGET", RiscqParam().lateBadTarget),
      aluFastAddOnly = flag("RISCQ_ALU_FAST_ADDONLY", RiscqParam().aluFastAddOnly),
      aluNoFastForward = flag("RISCQ_ALU_NO_FASTFWD", RiscqParam().aluNoFastForward),
      aluResultOneHot = flag("RISCQ_ALU_ONEHOT", RiscqParam().aluResultOneHot),
      deriveImmFromWord = flag("RISCQ_DERIVE_IMM", RiscqParam().deriveImmFromWord),
      fetchLatency = sys.env.get("RISCQ_FETCH_LATENCY").map(_.toInt).getOrElse(RiscqParam().fetchLatency),
      fetchPcWidth = sys.env.get("RISCQ_FETCH_PC_WIDTH").map(_.toInt).map(Some(_)).getOrElse(RiscqParam().fetchPcWidth)
    )
  }

  /** Build the selected plugin set from a param (shared with [[VivadoRouteBench]]). */
  def selectedPlugins(p: RiscqParam): Seq[Hostable] = pluginSet match {
    case "nopredict" => p.pluginsNoPredict()
    case "btb"       => p.pluginsBtbOnly()
    case "full"      => p.plugins()
    case other       => sys.error(s"RISCQ_PLUGINS=$other not one of full|btb|nopredict")
  }

  // RISCQ_SYNC_RESET=1 regenerates the core with **synchronous** reset (SpinalHDL's default is async)
  // to A/B the congestion effect: async-reset FFs move to the sync-reset column, where
  // UltraScale+ can pack them more freely and Vivado may fold some resets into LUT D-input logic.
  val syncReset = flag("RISCQ_SYNC_RESET", false)

  /** The core as a benchmarkable DUT for the generic engine (used here and by [[VivadoRouteBench]]). */
  def dut: Dut = Dut(
    s"riscq core [$pluginSet]${if (syncReset) " +sync-rst" else ""}",
    ws => {
      val cfg = if (syncReset)
        SpinalConfig(targetDirectory = ws, defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC))
      else SpinalConfig(targetDirectory = ws)
      cfg.generateVerilog(Riscq(selectedPlugins(benchParam())))
    }
  )

  /** Synthesise the core OOC and return the raw `report_timing` text (reused by [[VivadoABCheck]]). */
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
        println(s"[VivadoBench] plugin set: $pluginSet")
        runVivado()
      }
    Engine.printReport(Engine.parse(report), workspace, label = "riscq core")
  }
}
