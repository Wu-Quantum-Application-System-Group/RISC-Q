package riscq.soc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import riscq.riscv.RiscqParam
import riscq.soc.link.{EventLink, RfCmd}

/**
 * Single-core band bench (specs/riscv-fmax.md Phase A2): one [[RiscvSoc]] in the **exact SoC
 * context**, as a minutes-scale A/B vehicle for the core timing levers. The OOC 590 MHz core bench
 * over-predicts and a 14q build costs hours; this reproduces what the core actually sees in
 * [[PulseTableSoc]]:
 *
 *   - the SoC core config: the congestion-lean lever stack + `fetchLatency = 4` +
 *     `fetchPcWidth` over the 16 KiB `memDepth = 4096` — the geometry the drifted constants of
 *     riscv-fmax §2 were re-aimed for;
 *   - the URAM `mem` (floats out of the X0 band — no URAM column there);
 *   - the SoC's batch-clock chain `refTime → syncTime → coreTime_0` (replica register named exactly
 *     as the SoC's so `pblocks-riscvsoc.tcl` pulls it into the band);
 *   - the posted-link boundary: three `linkPipe`-deep DONT_TOUCH `cmd` pipes (the SoC pipes `cmd`
 *     once per channel: gate / readout / demod) into kept accumulator anchors, and the `resultIn`
 *     up-link piped back — both boundary directions registered, as in [[RiscqRfWithPulseTableFiber]],
 *     so swapping the anchors for the real datapath cannot change core-internal timing
 *     (the [[RiscqFarmTop]] transfer argument).
 *
 * Instance names match `pblocks-riscvsoc.tcl`'s discovery (`riscqArea_riscqCores_0_riscvSoc`,
 * `coreTime_0`), so the whole `riscvsoc` flow — floorplan, P&R recipe, `report-cones.tcl` — runs
 * unchanged via `RISCQ_TOP=CoreBandBench` (see `vivado-scripts/riscvsoc/build-coreband.sh`).
 * Success target (spec A2): every core cone ≥ +0.10 ns here ≈ ≥ 0 in-SoC (the measured
 * common-mode shift between this context and a full 14q build is ~0.11 ns).
 */
object CoreBandBench {
  /** The SoC's per-core lever stack (as in PulseTableSoc), hoisted so [[GenCoreBandBench]] can
   *  overlay env-knob overrides without duplicating it. */
  def defaultCoreParam: RiscqParam = RiscqParam(gshareMem = true, csrWarl = true,
    aluNoFastForward = true, aluResultOneHot = true, pcRegMaxFanout = 16)
}

case class CoreBandBench(
    coreParam: RiscqParam = CoreBandBench.defaultCoreParam,
    linkPipe: Int = 4,
    memDepth: Int = 4096,
    readoutAccWidth: Int = 32
) extends Component {
  val dspClk, dspRst = in Bool()
  val dspCd   = ClockDomain(dspClk, dspRst)
  val riscqCd = ClockDomain(dspClk, dspRst)

  val anchorOut = out port Bool() // anti-prune: one registered reduction of all anchors

  val riscqArea = dspCd on new Area {
    // a pipe that keeps every stage a distinct, un-optimizable FF (a placeable anchor) — RiscqFarmTop.
    def keepPipe[T <: Data](f: Flow[T], n: Int): Flow[T] =
      (0 until n).foldLeft(f) { (s, _) =>
        val r = s.stage(); r.payload.addAttribute("DONT_TOUCH"); r.valid.addAttribute("DONT_TOUCH"); r
      }

    // the SoC's exact per-core config (PulseTableSoc.riscqArea.cp)
    val cp = coreParam.copy(fetchPcWidth = Some(log2Up(memDepth) + 2), fetchLatency = 4)

    val riscvSoc = RiscvSoc(plugins = cp.plugins(), riscqCd = riscqCd,
      readoutAccWidth = readoutAccWidth, memDepth = memDepth)
    riscvSoc.setName("riscqArea_riscqCores_0_riscvSoc")
    riscvSoc.addAttribute("KEEP_HIERARCHY", "TRUE")

    // the SoC's batch-clock chain: refTime → syncTime → per-core replica (names as in PulseTableSoc)
    val refTime = riscqCd(Reg(UInt(64 bits)) init 0)
    refTime := refTime + 1
    val syncTime = RegNext(refTime)
    val coreTime = RegNext(syncTime(0, 32 bits))
    coreTime.setName("coreTime_0")
    coreTime.addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
    coreTime.addAttribute("MAX_FANOUT", 16)
    riscvSoc.time := coreTime

    // ── posted-link boundary: one DONT_TOUCH cmd pipe per channel (gate / readout / demod), each
    //    into a kept accumulator anchor — the SoC's exact `getPipe(riscvSoc.cmd, linkPipe)` fan-out ──
    def mkAcc(p: Flow[RfCmd]): Bits = {
      val acc = Reg(Bits(32 bits)) init 0
      acc.addAttribute("DONT_TOUCH")
      when(p.valid)(acc := acc ^ p.payload.data ^ p.payload.address.asBits.resize(32))
      acc
    }
    val gatePipe  = keepPipe(riscvSoc.cmd, linkPipe)
    val roPipe    = keepPipe(riscvSoc.cmd, linkPipe)
    val demodPipe = keepPipe(riscvSoc.cmd, linkPipe)
    val gateAcc   = mkAcc(gatePipe)
    val roAcc     = mkAcc(roPipe)
    val demodAcc  = mkAcc(demodPipe)

    // up-link result back through the registered boundary (exercises the full resultIn width)
    val res = Flow(RfCmd(EventLink.inboxAddrWidth))   // the up-link: puts into the inbox
    res.valid        := demodPipe.valid && (demodPipe.payload.address === 0x30000)
    res.payload.address := demodAcc(0, EventLink.inboxAddrWidth bits).asUInt
    res.payload.data    := demodAcc
    riscvSoc.resultIn << keepPipe(res, linkPipe)

    // host-window stream tied off: the bridge cells stay inside the core (the pblock target); the CC
    // FIFO and the shared funnel live outside it and are not part of this bench.
    riscvSoc.hostCmd.ready := True

    // iLoad tied off by a quiet host master (no program needed for timing)
    val tieILoad = Fiber build { riscvSoc.iLoad.node.bus.a.setIdle(); riscvSoc.iLoad.node.bus.d.ready := True }

    val anchor = RegNext(gateAcc.xorR ^ roAcc.xorR ^ demodAcc.xorR) init False
  }
  anchorOut := riscqArea.anchor
}

/** RTL gen for the band bench: `args` = target dir (default `./build/coreband-rtl`). */
object GenCoreBandBench extends App {
  val dir = args.find(a => a.nonEmpty && !a.forall(_.isDigit)).getOrElse("./build/coreband-rtl")
  // CSR fanout-cap + C3 latency knobs — same env names as riscq.riscv.bench.VivadoBench.benchParam(),
  // so build-coreband.sh sweeps them without code edits. (B3/B4/E1–E3 and the jumpAt/fetch MAX_FANOUT=16
  // caps are baked in — no longer knobs.)
  val cp = CoreBandBench.defaultCoreParam.copy(
    csrCommitMaxFanout =
      sys.env.get("RISCQ_CSRCE_MAXFANOUT").map(_.toInt).getOrElse(RiscqParam().csrCommitMaxFanout),
    // C3 latency-for-margin flags: RISCQ_BTB_LATE takes the BTB tag-compare off the pcReg self-loop
    // (1-bubble predicted-taken); RISCQ_LATE_BADTARGET moves the 32-bit target compare to jumpAt.
    btbPredictLate = sys.env.get("RISCQ_BTB_LATE")
      .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(RiscqParam().btbPredictLate),
    lateBadTarget = sys.env.get("RISCQ_LATE_BADTARGET")
      .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(RiscqParam().lateBadTarget))
  SpinalConfig(mode = Verilog, targetDirectory = dir, romReuse = true)
    .generate(CoreBandBench(coreParam = cp))
  println(s"[GenCoreBandBench] emitted $dir/CoreBandBench.v")
}
