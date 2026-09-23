package riscq.soc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import riscq.riscv.RiscqParam
import riscq.soc.link.{EventLink, RfCmd}

import scala.collection.mutable.ArrayBuffer

/**
 * Prototype floorplan top for the registered-boundary sweep: a flat out-of-context Component — no PS, no
 * RFDC, no converters, pure `dspClk` logic so each place+route trial is fast — holding `coreNum`
 * [[RiscvSoc]] hard Components plus a tiny **dummy register block** per core standing in for the
 * converter-edge DSP datapath.
 *
 * The dummy is a placement **anchor**: it consumes the core's posted `cmd` (through the down `linkPipe`)
 * into a `DONT_TOUCH` accumulator and drives the core's `resultIn` (through the up `linkPipe`), so both
 * registered boundary paths are real and neither port is optimized away. Pinned one clock-region column
 * over from the cores, it gives each pinned core's `cmd`/`resultIn` ports a fixed adjacent home — a
 * single short hop instead of a die-spanning float. Because the boundary is registered on both sides
 * (the bridge inside [[RiscvSoc]] + these `keepPipe` stages), swapping this dummy for the real datapath
 * cannot change a core's internal timing — the fmax measured here transfers.
 *
 * `out` XOR-reduces every dummy accumulator to one registered pin so synthesis can't prune the farm.
 *
 * @param coreNum   cores in the farm.
 * @param linkPipe  per-direction `RegNext` depth on the posted link (start 4).
 * @param coreParam the RISC-V plugin config, replicated across all cores (the congestion-lean default).
 */
case class RiscqFarmTop(
    coreNum: Int,
    linkPipe: Int = 4,
    coreParam: RiscqParam = RiscqParam(gshareMem = true, csrWarl = true),
    readoutAccWidth: Int = 32,
    // diagnostic: drop the BTB + GShare predictors (PcPlugin + BranchPlugin corrector stay) — measures
    // whether the fmax binder lives in the *prediction* hardware (PC self-loop / read_hit) or the
    // execute resolution/ALU cluster. NOT a real config (no prediction ⇒ every taken branch full-penalty).
    noPredict: Boolean = false,
    // tag each core instance `(* KEEP_HIERARCHY = "TRUE" *)` so synthesis can't flatten/optimize across
    // the per-core boundary — keeps each core a distinct optimization unit (probes whether cross-core
    // flattening is part of the simultaneous-placement route inflation).
    keepCoreHierarchy: Boolean = false
) extends Component {
  // Pure dspClk OOC top: the host-load CDC moved out of RiscvSoc (iLoad is dspCd now, tied off below), so
  // there is no host clock domain left in this floorplan bench — only dspClk logic.
  val dspClk, dspRst = in Bool()
  val dspCd   = ClockDomain(dspClk, dspRst)
  val riscqCd = ClockDomain(dspClk, dspRst)

  val anchorOut = out port Bool()               // anti-prune: one registered reduction of all anchors

  val logic = dspCd on new Area {
    // a pipe that keeps every stage a distinct, un-optimizable FF (a placeable anchor).
    def keepPipe[T <: Data](f: Flow[T], n: Int): Flow[T] =
      (0 until n).foldLeft(f) { (s, _) =>
        val r = s.stage(); r.payload.addAttribute("DONT_TOUCH"); r.valid.addAttribute("DONT_TOUCH"); r
      }

    val outs = ArrayBuffer[Bool]()
    for (i <- 0 until coreNum) new Composite(this, s"core_$i") {
      val riscvSoc = RiscvSoc(
        plugins = if (noPredict) coreParam.pluginsNoPredict() else coreParam.plugins(),
        riscqCd = riscqCd,
        readoutAccWidth = readoutAccWidth)
      if (keepCoreHierarchy) riscvSoc.addAttribute("KEEP_HIERARCHY", "TRUE")
      // Per-core local batch-time counter, DONT_TOUCH'd so Vivado can't merge the identical counters back
      // into one high-fanout broadcast net (a shared 32-bit `time` net costs timing once several cores
      // share it, and scales worse with core count). Each core's `time` is private — mirroring the SoC's
      // decision to replicate refTime+timeOffset per core rather than broadcast.
      val coreTime = Reg(UInt(32 bits)) init 0
      coreTime := coreTime + 1
      coreTime.addAttribute("DONT_TOUCH")
      riscvSoc.time     := coreTime

      // ── dummy anchor: fold every posted cmd into a kept accumulator ──
      val cmdDn = keepPipe(riscvSoc.cmd, linkPipe)
      val acc = Reg(Bits(32 bits)) init 0
      acc.addAttribute("DONT_TOUCH")
      when(cmdDn.valid)(acc := acc ^ cmdDn.payload.data ^ cmdDn.payload.address.asBits.resize(32))

      // drive the up-link result from the accumulator (exercises the full resultIn boundary width).
      val res = Flow(RfCmd(EventLink.inboxAddrWidth))   // the up-link: puts into the inbox
      res.valid        := cmdDn.valid && (cmdDn.payload.address === 0x30000)
      res.payload.address := acc(0, EventLink.inboxAddrWidth bits).asUInt
      res.payload.data    := acc
      riscvSoc.resultIn << keepPipe(res, linkPipe)

      // anti-prune contribution: a 1-bit reduction of this core's accumulator, registered LOCALLY so the
      // cross-region combine carries 1 bit/core instead of a 32-bit reduction net spanning the core rows.
      outs += RegNext(acc.xorR) init False

      // host-window stream tied off: the bridge + its cells stay inside the core (the pblock
      // target); the CC FIFO and the funnel live outside it and are not part of this bench.
      riscvSoc.hostCmd.ready := True
      // iLoad tied off by a quiet host master (no program needed for timing).
      val tieILoad = Fiber build { riscvSoc.iLoad.node.bus.a.setIdle(); riscvSoc.iLoad.node.bus.d.ready := True }
    }

    anchorOut := RegNext(outs.reduce(_ ^ _)) init False
  }
}

/**
 * Elaboration / RTL gen for the farm sweep. `args(0)` = coreNum (default 5), `args(1)` = linkPipe
 * (default 4). Run e.g. `./.metals/mill runMain riscq.soc.GenRiscqFarm 5`.
 */
object GenRiscqFarm extends App {
  val coreNum  = args.lift(0).filter(_.forall(_.isDigit)).map(_.toInt).getOrElse(5)
  val linkPipe = args.lift(1).filter(_.forall(_.isDigit)).map(_.toInt).getOrElse(4)
  SpinalConfig(mode = Verilog, targetDirectory = "./build/rtl", romReuse = true)
    .generate(RiscqFarmTop(coreNum = coreNum, linkPipe = linkPipe))
  println(s"[GenRiscqFarm] elaborated RiscqFarmTop coreNum=$coreNum linkPipe=$linkPipe OK")
}
