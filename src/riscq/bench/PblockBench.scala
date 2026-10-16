package riscq.bench

import org.apache.commons.io.FileUtils
import spinal.core._
import spinal.lib.DoCmd

import java.io.{File, PrintWriter}
import scala.io.Source

/**
 * A small, self-contained, **DUT-agnostic** "does it fit?" benchmark: given a top module and a target
 * Pblock region (a rectangle of clock regions), it synthesises the module out-of-context, pins the
 * whole netlist into a **hard** Pblock over that region, runs `place_design`, and reports whether the
 * design fits — both as the placer's pass/fail verdict and as a per-resource utilisation table
 * (LUT / FF / CARRY8 / DSP / BRAM …, Used vs the sites the region actually offers).
 *
 * This answers a different question from [[VivadoBench]] (how fast?) — namely *can these cells live in
 * this much silicon?* — which is exactly what a floorplanner asks when sizing the per-core Pblock
 * anchors used by the Vivado-flow floorplans (`vivado-scripts/riscvsoc/pblocks-riscvsoc.tcl` and
 * `vivado-scripts/riscvsoc-bd/pblocks-bd.tcl`). It mirrors those
 * scripts' proven idioms: `IS_SOFT FALSE` (advisory Pblocks are ignored by the placer), and the
 * BRAM/DSP/URAM **site-float** trick — a small clock-region rectangle may contain no BRAM (or DSP, or
 * URAM) column, so pinning those hard-macro cells would fail for *geometry*, not capacity; removing
 * their sites from the Pblock lets them float to the nearest column while the logic stays contained.
 *
 * Two views come out of it:
 *   - the **placement verdict** (FIT / NOFIT). `place_design` against a hard Pblock errors when the
 *     cells cannot be legally placed in the region — capacity *or* the CLB-shape constraints
 *     (e.g. a MUXF8 tree needs a whole column). That error is caught and reported as NOFIT.
 *   - the **capacity table** from `report_utilization -pblocks`: Used vs Available per resource, so a
 *     NOFIT is explained (which resource overflowed) and a FIT shows the headroom.
 *
 * It is DUT-agnostic by design (no DUT import) — drive it from a thin per-DUT wrapper that supplies a
 * [[Dut]] (e.g. [[riscq.riscv.bench.RiscqCorePblockBench]]); this object has no `main`.
 *
 * Config is environment-overridable (defaults match the riscq RFSoC target / per-core anchor):
 *   - `RISCQ_VIVADO_BIN`, `RISCQ_DEVICE`, `RISCQ_FMAX_TARGET_MHZ` (clock for timing-driven place).
 *   - `RISCQ_PBLOCK_REGION` — the region, as a clock-region rectangle. Shorthand `X1Y1:X2Y1` is
 *     expanded to `CLOCKREGION_X1Y1:CLOCKREGION_X2Y1`; a fully-qualified `CLOCKREGION_…`/`SLICE_…`
 *     string is passed through unchanged. Default `X1Y1:X2Y1` (a 2-CR per-core rectangle).
 *   - `RISCQ_PBLOCK_FLOAT` — comma list of SITE_TYPE prefixes to float out of the region
 *     (default `RAMB`; use e.g. `RAMB,DSP,URAM` to fit logic into a region with no DSP/URAM column,
 *     or empty to pin everything strictly).
 *   - `RISCQ_PBLOCK_CLOCKS` — comma list of clock port names to constrain if present (best-effort;
 *     default `clk,dspClk,hostClk` covers single-clock modules and the SoC's two domains).
 *   - `RISCQ_PBLOCK_PLACE` (default true) — run `place_design` for the authoritative verdict; set
 *     false for a fast capacity-only check (synth + assign + `report_utilization -pblocks`, no place).
 *   - `RISCQ_PBLOCK_ROUTE` (default true) — also `route_design`, write a routed `post_route.dcp` and
 *     dump timing (the post-route WNS / fmax sign-off); set false to stop after place.
 *
 * Each stage drops a Vivado checkpoint into the workspace (`open_checkpoint` to inspect): always a
 * `post_synth.dcp` (netlist + Pblock constraints), then `post_place.dcp` and `post_route.dcp` as the
 * flow reaches them — so the placed/routed result is saved, not just the text report.
 */
object PblockBench {
  // Default to the `vivado` on PATH (its bin dir); RISCQ_VIVADO_BIN overrides.
  val vivadoBin = sys.env.getOrElse("RISCQ_VIVADO_BIN", VivadoBench.resolveVivadoBin)
  val device    = sys.env.getOrElse("RISCQ_DEVICE", "xczu49dr-ffvf1760-2-e")
  val targetMhz = sys.env.getOrElse("RISCQ_FMAX_TARGET_MHZ", "1000").toDouble
  val periodNs  = 1000.0 / targetMhz

  /** Read a boolean env flag, falling back to `default` when unset. */
  def flag(name: String, default: Boolean): Boolean =
    sys.env.get(name).map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(default)

  // Env-driven defaults for the run knobs (shared by every wrapper unless it overrides a param).
  val regionEnv = sys.env.getOrElse("RISCQ_PBLOCK_REGION", "X1Y1:X2Y1")
  val floatEnv  = csv(sys.env.getOrElse("RISCQ_PBLOCK_FLOAT", "RAMB"))
  val clocksEnv = csv(sys.env.getOrElse("RISCQ_PBLOCK_CLOCKS", "clk,dspClk,hostClk"))
  val placeEnv  = flag("RISCQ_PBLOCK_PLACE", default = true)
  val routeEnv  = flag("RISCQ_PBLOCK_ROUTE", default = true)
  // RISCQ_RETIMING=1 adds `-retiming` to `synth_design` (synth-flow lever, OFF by default → behaviour
  // unchanged). NB Vivado 2024.2 prints a deprecation note suggesting `-global_retiming`, but that token is
  // rejected as a synth_design CLI option (Common 17-157) — `-retiming` is the working boolean switch.
  // Retiming is the cycle-behaviour-preserving move of registers across combinational logic,
  // so the RTL/sim and RVLS are unaffected by construction; it only rebalances feed-forward register
  // regions (e.g. EX→regWriteAt late-writeback), never feedback loops (ALU forward / PC redirect).
  val retimingEnv = flag("RISCQ_RETIMING", default = false)

  private def csv(s: String): Seq[String] = s.split(",").map(_.trim).filter(_.nonEmpty).toSeq

  /** One parsed `report_utilization -pblocks` resource row (the fields we report on). */
  case class ResRow(name: String, used: Double, avail: Double, utilPct: Double) {
    /** Demand exceeds what the region offers — a definite capacity miss. */
    def overflow: Boolean = avail > 0 && utilPct > 100.0
  }

  /** The outcome of one fit run. `wnsNs` is the post-route worst slack (Some only when routed). */
  case class Result(region: String, fit: Boolean, why: String, placed: Boolean, routed: Boolean,
                    rows: Seq[ResRow], wnsNs: Option[Double], label: String, ws: String)

  /** Headline resources surfaced in the report (other sub-rows are parsed but not printed). */
  val keyResources = Seq("CLB LUTs", "CLB Registers", "CARRY8", "F7 Muxes", "F8 Muxes",
                         "Block RAM Tile", "DSPs", "URAM")

  /** Expand a `X1Y1:X2Y1` shorthand to a `CLOCKREGION_…` range; pass a qualified string through. */
  def expandRegion(r: String): String =
    if (r.contains("_")) r
    else r.split(":").map(c => s"CLOCKREGION_${c.trim}").mkString(":")

  // ================================================================================================
  // Run: emit the DUT's Verilog, write a tcl that synthesises OOC, pins everything into a hard Pblock
  // over `region`, places it (optionally routes), and records FIT/NOFIT + the per-Pblock utilisation.
  // ================================================================================================
  def runVivado(dut: Dut, ws: String,
                region: String = regionEnv,
                place: Boolean = placeEnv,
                route: Boolean = routeEnv,
                floatSites: Seq[String] = floatEnv,
                clockPorts: Seq[String] = clocksEnv,
                retiming: Boolean = retimingEnv): Result = {
    val regionEx = expandRegion(region)
    val wsDir = new File(ws)
    FileUtils.deleteDirectory(wsDir)
    wsDir.mkdirs()

    // 1) emit the DUT's RTL straight into the workspace
    val gen = dut.emit(ws)
    val readCmds = gen.rtlSourcesPaths.toSeq
      .filter(p => p.endsWith(".v") || p.endsWith(".sv"))
      .map(p => s"read_verilog ${new File(p).getName}")
      .mkString("\n")

    // 2) place flow (authoritative verdict) vs capacity-only; optional route+timing inside it
    val routeFlow =
      if (route)
        """|  if {[catch {route_design} em]} {
           |    set why "placed OK but route_design failed: $em"
           |  } else {
           |    write_checkpoint -force post_route.dcp
           |    report_timing_summary -delay_type max -file timing_summary.rpt
           |    report_timing -delay_type max -max_paths 40 -nworst 1 -path_type full -file timing_paths.rpt
           |  }
           |""".stripMargin
      else ""
    val placeFlow =
      if (place)
        s"""|set fit 1
            |set why ""
            |if {[catch {opt_design} em]} { set fit 0; set why "opt_design failed: $$em" }
            |if {$$fit} { if {[catch {place_design} em]} { set fit 0; set why "place_design failed (does not fit region): $$em" } }
            |if {$$fit} {
            |  report_utilization -pblocks [get_pblocks $$pb] -file util_pblock.rpt
            |  write_checkpoint -force post_place.dcp
            |$routeFlow}
            |""".stripMargin
      else
        """|set fit 1
           |set why "capacity-only (no place_design; verdict from region Used-vs-Available)"
           |""".stripMargin

    // 3) the full tcl — token-substituted so the tcl `$var`/`[expr]` stay literal (no s-interp clash)
    // Retiming is *timing-driven*: synth_design skips it ("No global retiming is needed without any
    // clock constraint") unless a clock exists AT SYNTH TIME. The clock create below runs post-synth
    // (for placement), so when retiming is on we additionally read a pre-synth clock XDC — otherwise
    // `-retiming` is silently a no-op. Only emitted when retiming is on, so the OFF tcl is unchanged.
    // XDC is parsed during synth elaboration (ports exist there) but forbids procedural Tcl (`if`),
    // so emit one plain `create_clock` per listed port. A port that does not exist on this DUT yields
    // only a warning ("no objects matched"), creating no clock — fine for the single-clock core.
    val preSynth =
      if (retiming) {
        val xdc = clockPorts.map(p =>
          s"create_clock -name $p -period ${f"$periodNs%.3f"} [get_ports $p]"
        ).mkString("\n")
        write(s"$ws/synth_clocks.xdc", xdc + "\n")
        "read_xdc synth_clocks.xdc\n"
      } else ""

    val tcl = template
      .replace("@READCMDS@", readCmds)
      .replace("@PRESYNTH@", preSynth)
      .replace("@TOP@", gen.toplevelName)
      .replace("@DEVICE@", device)
      .replace("@PERIOD@", f"$periodNs%.3f")
      .replace("@CLOCKS@", clockPorts.mkString(" "))
      .replace("@REGION@", regionEx)
      .replace("@FLOAT@", floatSites.mkString(" "))
      .replace("@RETIMING@", if (retiming) " -retiming" else "")
      .replace("@PLACEFLOW@", placeFlow)
    write(s"$ws/bench.tcl", tcl)

    val flow = if (route) "place&route" else if (place) "place" else "capacity-only"
    println(s"[PblockBench] $flow ${gen.toplevelName} (${dut.label}) into pblock $regionEx on $device" +
      s"${if (floatSites.nonEmpty) s" (floating ${floatSites.mkString("/")})" else ""}" +
      s"${if (retiming) " [synth -retiming]" else ""}…")
    DoCmd.doCmd(s"$vivadoBin/vivado -nojournal -log vivado.log -mode batch -source bench.tcl", ws)

    // 4) read the verdict + the per-Pblock utilisation (post-place if it got there, else the pre table)
    val (rawFit, why) = {
      val f = new File(s"$ws/fit_result.txt")
      if (f.isFile) {
        val lines = Source.fromFile(f).getLines().toList
        (lines.headOption.contains("FIT"), lines.drop(1).mkString(" ").trim)
      } else (false, "vivado produced no fit_result.txt — the run failed early (see vivado.log)")
    }
    val postRpt = new File(s"$ws/util_pblock.rpt")
    val preRpt  = new File(s"$ws/util_pblock_pre.rpt")
    val rptFile = if (postRpt.isFile) postRpt else preRpt
    val rows    = if (rptFile.isFile) parsePblockUtil(Source.fromFile(rptFile).mkString) else Seq.empty
    val placed  = place && postRpt.isFile
    // place mode → trust the placer; capacity-only → fit iff no headline resource overflows the region
    val fit = if (place) rawFit else !rows.filter(r => keyResources.contains(r.name)).exists(_.overflow)

    // routed sign-off: the routed checkpoint + post-route worst slack (reuse VivadoBench's timing parser)
    val routed = new File(s"$ws/post_route.dcp").isFile
    val wnsNs = {
      val tf = new File(s"$ws/timing_paths.rpt")
      if (tf.isFile) {
        val paths = VivadoBench.parse(Source.fromFile(tf).mkString)
        if (paths.nonEmpty) Some(paths.map(_.slackNs).min) else None
      } else None
    }

    Result(regionEx, fit, why, placed, routed, rows, wnsNs, dut.label, ws)
  }

  // ================================================================================================
  // Parse: pull the per-resource rows out of `report_utilization -pblocks`. Each data row is a pipe
  // table line `| Site Type | Parent | Child | Non-Assigned | Used | Fixed | Prohibited | Available |
  // Util% |` — i.e. a name plus 8 numeric columns (sub-rows with blank tail columns are skipped).
  // ================================================================================================
  def parsePblockUtil(report: String): Seq[ResRow] = {
    def num(s: String) = s.matches("-?[0-9]+(\\.[0-9]+)?")
    report.linesIterator.flatMap { line =>
      val t = line.trim
      if (!t.startsWith("|")) None
      else {
        val cells = t.split("\\|", -1).map(_.trim).filter(_.nonEmpty)
        // a data row is: name + 8 cells (Parent Child Non-Assigned Used Fixed Prohibited Available Util%).
        // The first 7 number columns are always numeric; Util% is `-NA-` for a floated resource whose
        // region offers 0 sites — keep that row so it shows as "float", not silently dropped.
        if (cells.length == 9 && cells.slice(1, 8).forall(num)) {
          val tail = cells.tail
          // strip Vivado's trailing markers from the site-type name, e.g. "CLB LUTs*" → "CLB LUTs"
          val name = cells.head.replaceAll("[*]+$", "").trim
          Some(ResRow(name, used = tail(3).toDouble, avail = tail(6).toDouble,
                      utilPct = if (num(tail(7))) tail(7).toDouble else 0.0))
        } else None
      }
    }.toSeq
  }

  // ================================================================================================
  // Report: the verdict, then the headline resources (Used / Available / Util%, with a fit marker),
  // and the binding resource. A NOFIT points at the pre-place table that explains the overflow.
  // ================================================================================================
  def printReport(res: Result): Unit = {
    val key = res.rows.filter(r => keyResources.contains(r.name))
    val verdict = if (res.fit) "FIT" else "NOFIT"
    val flow = if (res.routed) "post-route" else if (res.placed) "post-place"
               else "capacity-only (synth + pblock assignment)"

    println()
    println("=" * 72)
    println(s"  ${res.label} — Pblock fit benchmark   ($flow)")
    println(f"    device   $device")
    println(f"    region   ${res.region}")
    println(f"    verdict  $verdict${if (res.why.nonEmpty) s"   (${res.why})" else ""}")
    println("=" * 72)
    println()

    if (key.isEmpty) {
      println("  no per-resource utilisation parsed — check the Vivado run / report format.")
      println(s"  reports: ${res.ws}/util_pblock*.rpt   log: ${res.ws}/vivado.log")
      return
    }

    println(f"  ${"resource"}%-16s ${"used"}%8s ${"avail"}%8s ${"util%"}%8s   fit")
    println("  " + "-" * 52)
    // keep the canonical resource ordering, only rows that are present
    for (name <- keyResources; r <- key.find(_.name == name)) {
      val marker = if (r.avail <= 0) "float" else if (r.overflow) "OVER" else "ok"
      println(f"  ${r.name}%-16s ${r.used}%8.0f ${r.avail}%8.0f ${r.utilPct}%7.2f%%   $marker")
    }
    println()

    val sized = key.filter(_.avail > 0)
    if (sized.nonEmpty) {
      val bind = sized.maxBy(_.utilPct)
      println(f"  binding resource: ${bind.name}%s at ${bind.utilPct}%.2f%% of the region")
    }
    // post-route timing sign-off when routed (fmax is a soft constraint here — recorded, not tuned)
    res.wnsNs.filter(_ => res.routed).foreach { wns =>
      val fmax = 1000.0 / (periodNs - wns)
      println(f"  post-route timing: WNS ${wns}%+.3f ns vs ${targetMhz.toInt} MHz target" +
        f"   →   fmax ~ ${fmax}%.0f MHz   ${if (wns >= 0) "(routes clean)" else "(timing not met at target — soft)"}")
    }
    if (!res.fit) {
      val over = sized.filter(_.overflow)
      if (over.nonEmpty)
        println(s"  overflow: ${over.map(r => f"${r.name} ${r.utilPct}%.0f%%").mkString(", ")}" +
          "  — region too small (or float a hard-macro type via RISCQ_PBLOCK_FLOAT).")
      else
        println("  placement failed without a capacity overflow — likely a CLB-shape / column " +
          "constraint (e.g. MUXF8 tree needs a whole column); try a larger region.")
    }
    println()
    val dcp = if (res.routed) "post_route.dcp" else if (res.placed) "post_place.dcp" else "post_synth.dcp"
    println(s"  checkpoint: ${res.ws}/$dcp   (open_checkpoint to inspect)")
    val timing = if (res.routed) s"    timing: ${res.ws}/timing_summary.rpt" else ""
    println(s"  pblock utilisation: ${res.ws}/util_pblock${if (res.placed) "" else "_pre"}.rpt$timing" +
      s"    log: ${res.ws}/vivado.log")
  }

  // The tcl template: tokens (@NAME@) are substituted in `runVivado`; everything else is literal tcl.
  // Note the escaped `\[PblockBench\]` in the final puts — bare `[..]` is a tcl command substitution.
  private val template =
    """|@READCMDS@
       |@PRESYNTH@synth_design -top @TOP@ -part @DEVICE@ -mode out_of_context@RETIMING@
       |report_utilization -file util_synth.rpt
       |
       |# constrain whatever clocks exist (best-effort; placement is timing-driven when present)
       |foreach p {@CLOCKS@} {
       |  if {[llength [get_ports -quiet $p]] > 0} {
       |    create_clock -name $p -period @PERIOD@ [get_ports $p]
       |  }
       |}
       |
       |# ── build the target pblock and pin the whole design into it (hard, like pblocks.tcl) ──
       |set_param tcl.collectionResultDisplayLimit 0
       |set pb [create_pblock pb_fit]
       |add_cells_to_pblock -quiet $pb [get_cells -hierarchical -filter {PRIMITIVE_LEVEL == LEAF}]
       |resize_pblock $pb -add @REGION@
       |# let the listed hard-macro site types float out of the region: a small clock-region rectangle
       |# may contain no BRAM/DSP/URAM column, so pinning them would fail for geometry, not capacity.
       |foreach stype {@FLOAT@} {
       |  set s [get_sites -quiet -of_objects [get_pblocks $pb] -filter "SITE_TYPE =~ ${stype}*"]
       |  if {[llength $s] > 0} { resize_pblock $pb -remove $s }
       |}
       |set_property IS_SOFT FALSE $pb
       |report_utilization -pblocks [get_pblocks $pb] -file util_pblock_pre.rpt
       |# checkpoint the synth netlist + pblock constraints (always available; the place flow adds a
       |# placed post_place.dcp on top). Open either with `open_checkpoint <file>.dcp`.
       |write_checkpoint -force post_synth.dcp
       |
       |@PLACEFLOW@
       |set f [open fit_result.txt w]
       |if {$fit} { puts $f "FIT" } else { puts $f "NOFIT" }
       |puts $f $why
       |close $f
       |puts "\[PblockBench\] region @REGION@ -> [expr {$fit ? {FIT} : {NOFIT}}]  $why"
       |""".stripMargin

  private def write(path: String, content: String): Unit = {
    val w = new PrintWriter(new File(path)); try w.write(content) finally w.close()
  }
}
