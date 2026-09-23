package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4WriteOnly
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.DebugId
import spinal.lib.bus.tilelink.fabric.MasterBus
import spinal.lib.bus.tilelink.sim.{IdAllocator, MasterAgent}
import riscq.soc.link.{HostCmd, HostWindowBridge, HostWindowFunnel}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * W0 sign-off for the host-window write path (specs/software/22 §4): `N` TileLink masters →
 * [[HostWindowBridge]] → per-core `StreamFifoCC` (riscq → host clock) → [[HostWindowFunnel]] → a
 * write-only AXI4 slave model with a **sparse golden memory** and random `ready` stalls.
 *
 * What it proves:
 *
 *   - **placement**: every store lands at `base + (core << 24) + offset` with its byte mask — checked
 *     both as a per-core beat sequence and as a byte-exact golden memory;
 *   - **order and conservation**: per-core store order is preserved end to end, nothing is lost and
 *     nothing is duplicated (the beat sequence must match the issued list *exactly*);
 *   - **back-pressure**: with the AXI slave stalling at random, no store is dropped — the bridge
 *     withholds the AccessAck and the master stalls instead;
 *   - **the `enable` gate**: while `enable = 0` not a single AXI beat leaves and the masters block once
 *     the FIFOs fill; after `enable = 1` everything lands, in order;
 *   - **a 40-bit base**: a second pass runs the same traffic against a base above 4 GB;
 *   - the AXI beats are legal single transfers (`len = 0`, `size = 2`, `INCR`, `last`, one ID).
 *
 * Run with `mill runMain riscq.soc.sim.HostWindowFunnelSim`.
 */
object HostWindowFunnelSim extends App {
  val coreNum     = 3
  val offsetWidth = 24
  val fifoDepth   = 16
  val winBytes    = 1 << offsetWidth
  val addrWidth   = 40
  val seed        = args.headOption.map(_.toInt).getOrElse(42)   // `runMain ... <seed>` to re-draw

  case class Dut() extends Component {
    val hostCd   = ClockDomain.current
    val riscqClk = in Bool ()
    val riscqRst = in Bool ()
    val riscqCd  = ClockDomain(riscqClk, riscqRst)

    val base   = in UInt (addrWidth bits)
    val enable = in Bool ()
    val axi    = master port Axi4WriteOnly(HostWindowFunnel.axiConfig(addrWidth))

    val funnel = HostWindowFunnel(coreNum, offsetWidth, addrWidth)
    funnel.io.base   := base
    funnel.io.enable := enable
    axi << funnel.io.axi

    // one core-side leg per core: a TileLink master (the sim's MasterAgent drives it) → the bridge →
    // the clock-crossing FIFO that carries the posted stream into the host domain.
    val cores = List.tabulate(coreNum) { i =>
      val leg = riscqCd(new Area {
        val tlBus = new MasterBus(tilelink.M2sParameters(
          addressWidth = offsetWidth, dataWidth = 32,
          masters = List(tilelink.M2sAgent(name = null, mapping = List(tilelink.M2sSource(
            id = SizeMapping(0, 4),
            emits = tilelink.M2sTransfers(
              putFull = tilelink.SizeRange(4), putPartial = tilelink.SizeRange(4))))))))
        val bridge = HostWindowBridge(offsetWidth)
        bridge.up at 0 of tlBus.node
      })
      val fifo = StreamFifoCC(HostCmd(offsetWidth), fifoDepth, riscqCd, hostCd)
      fifo.io.push << leg.bridge.cmd
      funnel.io.cmd(i) << fifo.io.pop
      leg
    }

    spinal.core.fiber.Fiber build new Area {
      cores.foreach(_.tlBus.node.bus.get.simPublic())
    }
  }

  // ── stimulus: (offset, 32-bit data word, strobe) ────────────────────────────────────────────────
  type Store = (Int, BigInt, Int)

  def leBytes(v: BigInt): Seq[Byte] = (0 until 4).map(i => ((v >> (8 * i)) & 0xFF).toByte)

  /** Corner cases first (both window ends, every byte lane, both half-word lanes), then random word
   *  offsets with random data and a random non-empty strobe. */
  def stores(core: Int, randomNum: Int): Seq[Store] = {
    val corners: Seq[Store] = Seq(
      (0x000000,        BigInt("deadbeef", 16), 0xF),   // first word of the slice
      (0x000004,        BigInt(1),              0xF),
      (winBytes - 4,    BigInt("cafebabe", 16), 0xF),   // last word of the slice
      (winBytes - 8,    BigInt("12345678", 16), 0xF),
      (0x000100,        BigInt("000000ab", 16), 0x1),   // sb — every byte lane
      (0x000100,        BigInt("0000cd00", 16), 0x2),
      (0x000100,        BigInt("00ef0000", 16), 0x4),
      (0x000100,        BigInt("77000000", 16), 0x8),
      (0x000200,        BigInt("0000babe", 16), 0x3),   // sh — both half lanes
      (0x000200,        BigInt("f00d0000", 16), 0xC),
      (0x000200,        BigInt("55aa33cc", 16), 0xF)    // full word over the same address
    )
    val rnd: Seq[Store] = (0 until randomNum).map { _ =>
      val off  = (simRandom.nextInt(winBytes / 4)) * 4
      val data = BigInt(simRandom.nextInt() & 0xFFFFFFFFL.toInt) & 0xFFFFFFFFL
      val strb = 1 + simRandom.nextInt(15)
      (off, data, strb)
    }
    // vary the length per core so the arbiter sees uneven traffic.
    (corners ++ rnd).take(corners.size + randomNum - core)
  }

  SimConfig.withConfig(SpinalConfig()).compile(Dut()).doSim("hostWindowFunnel", seed = seed) { dut =>
    val hostCd  = dut.clockDomain
    val riscqCd = dut.riscqCd

    dut.enable #= false
    dut.base   #= 0
    dut.cores.foreach(_.tlBus.node.bus.a.valid #= false)
    hostCd.forkStimulus(10)
    riscqCd.forkStimulus(4)          // the core domain is the (faster) dsp clock

    // ── the write-only AXI slave model: random ready stalls + a sparse golden byte memory ──
    val host = HostMemModel(dut.axi, hostCd)

    // one allocator per core: `DebugId.width` is 0 here, so an allocator holds exactly one outstanding
    // transaction — which is what a core is anyway (the LSU is single-outstanding).
    val agents = dut.cores.map(c => new MasterAgent(c.tlBus.node.bus, riscqCd)(new IdAllocator(DebugId.width)))
    hostCd.waitSampling(10)

    /** Issue `plan` on every core in parallel while `enable` is low, prove nothing leaves and the
     *  masters block, then release and check the AXI stream against the golden model. */
    def pass(label: String, base: BigInt, plan: Seq[Seq[Store]]): Unit = {
      host.clear()
      dut.enable #= false
      dut.base   #= base
      hostCd.waitSampling(5)

      val done = Array.fill(coreNum)(0)
      val threads = plan.zipWithIndex.map { case (list, core) =>
        fork {
          for ((off, data, strb) <- list) {
            if (strb == 0xF) agents(core).putFullData(0, off, leBytes(data))
            else             agents(core).putPartialData(0, off, leBytes(data),
                               (0 until 4).map(b => ((strb >> b) & 1) == 1))
            done(core) += 1
          }
        }
      }

      // ── enable gate: nothing may leave, and every core must run out of FIFO and block ──
      hostCd.waitSampling(400)
      assert(host.writes.isEmpty, s"[$label] ${host.writes} AXI beat(s) escaped while enable = 0")
      for (core <- 0 until coreNum)
        assert(done(core) < plan(core).size,
          s"[$label] core $core completed all ${plan(core).size} stores with enable = 0 — the gate " +
            s"is not back-pressuring (FIFO depth $fifoDepth)")
      val heldOff = done.sum

      dut.enable #= true
      threads.foreach(_.join())

      val total = plan.map(_.size).sum
      var guard = 0
      while (host.writes.size < total && guard < 5000) { hostCd.waitSampling(); guard += 1 }
      hostCd.waitSampling(50)

      // ── per-core beat sequence: exactly the issued stores, in order, at the right address ──
      for (core <- 0 until coreNum) {
        val seen = host.beatsFor(base, core, offsetWidth)
        val want: Seq[Store] = plan(core)
          .map { case (off, data, strb) => (off, HostMemModel.maskWord(data, strb), strb) }
        assert(seen.size == want.size,
          s"[$label] core $core: ${seen.size} AXI writes for ${want.size} stores (lost or duplicated)")
        for (((got, exp), k) <- seen.zip(want).zipWithIndex)
          assert(got == exp, s"[$label] core $core beat $k mismatch: got $got want $exp")
      }
      assert(host.writes.size == total, s"[$label] ${host.writes.size} AXI writes for $total stores (stray beats)")

      // ── golden memory: the same stores applied in the same per-core order, byte-exact ──
      val golden = mutable.Map[BigInt, Int]()
      for ((list, core) <- plan.zipWithIndex; (off, data, strb) <- list) {
        val addr = base + (BigInt(core) << offsetWidth) + off
        for (b <- 0 until 4 if ((strb >> b) & 1) == 1) golden(addr + b) = ((data >> (8 * b)) & 0xFF).toInt
      }
      assert(host.mem == golden,
        s"[$label] golden memory mismatch: ${(host.mem.toSet diff golden.toSet).take(8)} unexpected, " +
          s"${(golden.toSet diff host.mem.toSet).take(8)} missing")

      println(f"[HostWindowFunnelSim] $label%-18s base=0x$base%010x: $total stores → ${host.writes.size} AXI " +
        f"beats, ${golden.size} golden bytes; $heldOff absorbed then held by enable=0.")
    }

    pass("low base",   BigInt("20000000", 16), (0 until coreNum).map(stores(_, 20)))
    pass("40-bit base", BigInt("840000000", 16), (0 until coreNum).map(stores(_, 12)))

    println(s"[HostWindowFunnelSim] PASS  $coreNum cores, ${winBytes / (1 << 20)} MB window each, " +
      s"CC FIFO depth $fifoDepth: order, placement, masks, back-pressure and the enable gate all hold.")
    simSuccess()
  }
}
