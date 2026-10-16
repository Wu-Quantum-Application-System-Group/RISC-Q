package riscq.soc

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import riscq.riscv.RiscqParam
import riscq.soc.link.{EventLink, Put}
import riscq.soc.spec.SocSpecMap

import scala.collection.mutable.ArrayBuffer

/**
 * One **floorplan region** for the reuse-clone assembly: a hard SpinalHDL `Component` holding
 * `perRegion` [[RiscvSoc]] cores — the unit that gets **implemented once OOC, pinned to a single
 * clock-region, then imported (locked) into the assembled top** by
 * [[riscq.soc.bench.RiscqCloneAssembleBench]]. The point of the region boundary is to give Vivado a
 * self-contained module to place+route *in isolation* (no inter-region simultaneous-placement
 * congestion), so a per-region placement that meets timing transfers to every clock-region row.
 *
 * Two modes:
 *   - `includeDummies = false` ("clone the cores"): the region holds only the cores; each core's posted
 *     `cmd`/`resultIn` link is exposed as a region port. The dummy anchors live in the *parent* and are
 *     placed during the assembly route (only the core placement is cloned).
 *   - `includeDummies = true` ("also clone the dummy placement"): each core's dummy anchor (the
 *     `keepPipe` link FFs + accumulator) lives **inside** the region, so it too is implemented OOC and
 *     locked — the region holds cores + dummies and the assembly only routes clocks.
 *
 * The per-core body matches [[RiscqFarmTop]] (local DONT_TOUCH batch-time counter, iLoad tied off, the
 * keepPipe/accumulator dummy) so the measured timing is faithful to the farm sweep.
 */
case class FarmRegion(
    perRegion: Int,
    linkPipe: Int,
    coreParam: RiscqParam,
    dspCd: ClockDomain,
    riscqCd: ClockDomain,
    includeDummies: Boolean,
    readoutAccWidth: Int = 32,
    putAddrWidth: Int = SocSpecMap.putAddrWidth
) extends Component {
  val anchorOut = out port Bool()
  // cores-only mode exposes each core's posted link; dummies-in-region mode keeps it internal.
  val cmd      = (!includeDummies) generate Vec.fill(perRegion)(master port Flow(Put(putAddrWidth)))
  val resultIn = (!includeDummies) generate Vec.fill(perRegion)(slave port Flow(Put(EventLink.inboxAddrWidth)))

  val logic = dspCd on new Area {
    // a pipe that keeps every stage a distinct, un-optimizable FF (a placeable anchor).
    def keepPipe[T <: Data](f: Flow[T], n: Int): Flow[T] =
      (0 until n).foldLeft(f) { (s, _) =>
        val r = s.stage(); r.payload.addAttribute("DONT_TOUCH"); r.valid.addAttribute("DONT_TOUCH"); r
      }

    val outs = ArrayBuffer[Bool]()
    for (j <- 0 until perRegion) new Composite(this, s"core_$j") {
      val riscvSoc = RiscvSoc(
        plugins = coreParam.plugins(),
        riscqCd = riscqCd,
        readoutAccWidth = readoutAccWidth, putAddrWidth = putAddrWidth)
      // Per-core local batch-time counter, DONT_TOUCH'd so Vivado can't merge identical counters back
      // into one high-fanout broadcast net — mirrors the SoC's replicate-not-broadcast of the batch clock.
      val coreTime = Reg(UInt(32 bits)) init 0
      coreTime := coreTime + 1
      coreTime.addAttribute("DONT_TOUCH")
      riscvSoc.time     := coreTime

      if (includeDummies) {
        // ── dummy anchor folded INTO the region (cloned/locked along with the cores) ──
        val cmdDn = keepPipe(riscvSoc.cmd, linkPipe)
        val acc = Reg(Bits(32 bits)) init 0
        acc.addAttribute("DONT_TOUCH")
        when(cmdDn.valid)(acc := acc ^ cmdDn.payload.data ^ cmdDn.payload.address.asBits.resize(32))
        val res = Flow(Put(EventLink.inboxAddrWidth))   // the up-link: puts into the inbox
        res.valid        := cmdDn.valid && (cmdDn.payload.address === 0x30000)
        res.payload.address := acc(0, EventLink.inboxAddrWidth bits).asUInt
        res.payload.data    := acc
        riscvSoc.resultIn << keepPipe(res, linkPipe)
        outs += RegNext(acc.xorR) init False
      } else {
        // cores-only: hand the posted link out to the parent (dummy placed in the assembly route).
        cmd(j)            << riscvSoc.cmd
        riscvSoc.resultIn << resultIn(j)
        outs += RegNext(riscvSoc.cmd.valid) init False
      }

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
 * Region-grouped prototype top for the **reuse-clone assembly**. Same farm as [[RiscqFarmTop]] but the
 * `coreNum` cores are bundled into `ceil(coreNum/perRegion)` [[FarmRegion]] sub-Components, each a clean
 * Verilog module the bench can implement once OOC and import locked into its clock-region row — the
 * realizable form of cloning one good region placement into every row (faithful per-cell *relocation* of
 * a single placement is not stock-Tcl-able, so each row is implemented in isolation at its destination
 * instead).
 *
 * @param dummiesInRegion  false = clone only the core placement (dummies live here in the top and are
 *                         placed during assembly); true = also clone the dummy placement (dummies inside
 *                         each region, implemented + locked with the cores).
 */
case class RiscqCloneTop(
    coreNum: Int,
    perRegion: Int = 3,
    linkPipe: Int = 4,
    coreParam: RiscqParam = RiscqParam(gshareMem = true, csrWarl = true),
    dummiesInRegion: Boolean = false,
    readoutAccWidth: Int = 32,
    putAddrWidth: Int = SocSpecMap.putAddrWidth
) extends Component {
  // Pure dspClk OOC top (host-load CDC moved out of RiscvSoc; iLoad is dspCd, tied off) — no host clock.
  val dspClk, dspRst = in Bool()
  val dspCd   = ClockDomain(dspClk, dspRst)
  val riscqCd = ClockDomain(dspClk, dspRst)

  val anchorOut = out port Bool()
  val regionNum = (coreNum + perRegion - 1) / perRegion

  val logic = dspCd on new Area {
    def keepPipe[T <: Data](f: Flow[T], n: Int): Flow[T] =
      (0 until n).foldLeft(f) { (s, _) =>
        val r = s.stage(); r.payload.addAttribute("DONT_TOUCH"); r.valid.addAttribute("DONT_TOUCH"); r
      }

    val outs = ArrayBuffer[Bool]()
    for (rg <- 0 until regionNum) new Composite(this, s"region_$rg") {
      val region = FarmRegion(
        perRegion = perRegion, linkPipe = linkPipe, coreParam = coreParam,
        dspCd = dspCd, riscqCd = riscqCd,
        includeDummies = dummiesInRegion, readoutAccWidth = readoutAccWidth, putAddrWidth = putAddrWidth)
      outs += region.anchorOut

      if (!dummiesInRegion) {
        // top-level dummy anchors, one per core in the region (placed during the assembly route).
        for (j <- 0 until perRegion) {
          val cmdDn = keepPipe(region.cmd(j), linkPipe)
          val acc = Reg(Bits(32 bits)) init 0
          acc.addAttribute("DONT_TOUCH")
          when(cmdDn.valid)(acc := acc ^ cmdDn.payload.data ^ cmdDn.payload.address.asBits.resize(32))
          val res = Flow(Put(EventLink.inboxAddrWidth))   // the up-link: puts into the inbox
          res.valid        := cmdDn.valid && (cmdDn.payload.address === 0x30000)
          res.payload.address := acc(0, EventLink.inboxAddrWidth bits).asUInt
          res.payload.data    := acc
          region.resultIn(j) << keepPipe(res, linkPipe)
          outs += RegNext(acc.xorR) init False
        }
      }
    }
    anchorOut := RegNext(outs.reduce(_ ^ _)) init False
  }
}

/** Elaboration / RTL gen smoke test. `args(0)` = coreNum (15), `args(1)` = perRegion (3),
 *  `args(2)` = dummiesInRegion (false). */
object GenRiscqCloneTop extends App {
  val coreNum   = args.lift(0).filter(_.forall(_.isDigit)).map(_.toInt).getOrElse(15)
  val perRegion = args.lift(1).filter(_.forall(_.isDigit)).map(_.toInt).getOrElse(3)
  val dum       = args.lift(2).exists(s => s == "1" || s.equalsIgnoreCase("true"))
  SpinalConfig(mode = Verilog, targetDirectory = "./build/rtl", romReuse = true)
    .generate(RiscqCloneTop(coreNum = coreNum, perRegion = perRegion, dummiesInRegion = dum))
  println(s"[GenRiscqCloneTop] elaborated coreNum=$coreNum perRegion=$perRegion dummiesInRegion=$dum OK")
}
