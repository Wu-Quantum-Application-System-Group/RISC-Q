package riscq.wr.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.DebugId
import spinal.lib.bus.tilelink.fabric.MasterBus
import spinal.lib.bus.tilelink.sim.{IdAllocator, IdCallback, MasterAgent}
import spinal.lib.bus.misc.SizeMapping
import riscq.wr.WrNode
import riscq.wr.gty.{GtySimPhy, WrPhyIo}

import java.io.PrintWriter
import scala.collection.mutable
import scala.util.Random

/**
 * W3 sign-off (spec 06 §4, 08): two complete `WrNode`s cross-wired through the `GtySimPhy`
 * serial model, each with an independent free-running 500 MHz `refTime` (arbitrary initial
 * values, independent phases, optional ppm offset) and the SoC-shaped
 * `syncTime = RegNext(refTime + BufferCC(timeOffset))`. A Scala host drives both register maps
 * over real Tilelink (`MasterAgent`) running the spec-07 exchange + averaging.
 *
 * Gates:
 *   - nominal (equal rates, symmetric link): mean `offset_MS` over N exchanges within
 *     **±0.25 cycle (±0.5 ns)** of the injected refTime difference, residual slope ≈ 0;
 *     then the modeled `timeOffset` correction is written and both sync markers armed at the
 *     same `syncTime` — the two marker pins rise within **1 dspClk cycle** of each other;
 *   - ppm: dspClk_B period stretched — the least-squares fit recovers the injected slope;
 *   - asymmetric link: the measured offset shifts by exactly `(d_BA − d_AB)/2` — pins the
 *     offset sign convention end-to-end (spec README §3).
 *
 * The nominal run's register-level trace is written to `simWorkspace/wr_two_node_trace.jsonl`
 * (the W5 Python golden-parity input, spec 07 §6).
 *
 * Run: `mill runMain riscq.wr.sim.WrTwoNodeSim`.
 */
object WrTwoNodeSim extends App {
  val HOST_P    = 10000L // 100 MHz host clock
  val REF_INIT_A = BigInt(1000)
  val REF_INIT_B = BigInt("987654321098") // > 32 bits: exercises the 64-bit latched reads

  case class TwoNodeTb() extends Component {
    val io = new Bundle {
      val phyA, phyB = slave(WrPhyIo())
      val dspClkA, dspClkB       = in Bool ()
      val timeOffsetA, timeOffsetB = in UInt (64 bits)
      val markerA, markerB       = out Bool ()
    }
    val hostCd  = clockDomain
    val bootCfg = ClockDomainConfig(resetKind = BOOT)

    def mkNode(phy: WrPhyIo, dspClk: Bool, timeOffset: UInt, refInit: BigInt) = new Area {
      val dspCd = ClockDomain(dspClk, config = bootCfg, frequency = FixedFrequency(500 MHz))
      val dsp = dspCd { new Area {
        val refTime = Reg(UInt(64 bits)) init refInit
        refTime := refTime + 1
        val syncTime = RegNext(refTime + BufferCC(timeOffset)) init 0 // the PulseTableSoc shape
      }}
      val node = WrNode(phy, dsp.refTime, dsp.syncTime, hostCd, dspCd)
      node.putTx.valid := False; node.putTx.payload.assignDontCare()   // no hub here: the put lane idles
      val tlBus = new MasterBus(tilelink.M2sParameters(addressWidth = 16, dataWidth = 32,
        masters = List(tilelink.M2sAgent(name = TwoNodeTb.this, mapping = List(tilelink.M2sSource(
          id = SizeMapping(0, 4), emits = tilelink.M2sTransfers(
            get = tilelink.SizeRange.upTo(0x40), putFull = tilelink.SizeRange.upTo(0x40),
            putPartial = tilelink.SizeRange.upTo(0x40))))))))
      node.regs.up at 0 of tlBus.node
    }
    val a = mkNode(io.phyA, io.dspClkA, io.timeOffsetA, REF_INIT_A)
    val b = mkNode(io.phyB, io.dspClkB, io.timeOffsetB, REF_INIT_B)
    io.markerA := a.node.marker
    io.markerB := b.node.marker
  }

  val compiled = SimConfig.compile(TwoNodeTb())

  // ── the Scala host: spec 07's WrNodeCtl over a MasterAgent ─────────────────────────────────
  class NodeCtl(name: String, agent: MasterAgent, cd: ClockDomain, trace: PrintWriter) {
    def read(addr: Long): Long = {
      val v = agent.getInt(0, addr).toLong & 0xffffffffL
      if (trace != null) trace.println(s"""{"n":"$name","op":"r","a":$addr,"v":$v}""")
      v
    }
    def write(addr: Long, data: Long): Unit = {
      agent.putInt(0, addr, data.toInt)
      if (trace != null) trace.println(s"""{"n":"$name","op":"w","a":$addr,"v":$data}""")
    }
    /** Record a free-form JSON line in the trace (the W5 Python-parity decision record). */
    def note(json: String): Unit = if (trace != null) trace.println(json)
    def release(): Unit = write(0x000, 0) // release resetAll — both nodes first (the link couples them)
    def awaitUp(): Unit = {
      var spins = 0
      while ((read(0x004) & 0x7) != 0x7) { spins += 1; assert(spins < 3000, s"$name: link never up") }
    }
    def sendFrame(typ: Int, seq: Int): Unit = {
      write(0x040, typ)
      write(0x040, 0x100 | seq) // last byte
    }
    /** Poll a TSU until valid, verify seq + no overrun, read 64-bit ts (lo latches hi), ack. */
    def takeTs(base: Long, expSeq: Int): BigInt = {
      var spins = 0
      while ((read(base + 8) & 1) == 0) { spins += 1; assert(spins < 3000, s"$name: TSU@$base no capture") }
      val ctrl = read(base + 8)
      assert((ctrl & 2) == 0, s"$name: TSU@$base overrun")
      assert(((ctrl >> 4) & 0xf) == (expSeq & 0xf), s"$name: TSU@$base seq ${(ctrl >> 4) & 0xf} != ${expSeq & 0xf}")
      val lo = read(base) // latches hi
      val hi = read(base + 4)
      write(base + 8, 1) // ack
      (BigInt(hi) << 32) | BigInt(lo)
    }
    /** Pop one full frame from RXF (poll until a complete message drained). */
    def popFrame(): Seq[Int] = {
      val bytes = mutable.ArrayBuffer[Int]()
      var spins = 0
      var done  = false
      while (!done) {
        val w = read(0x050)
        if ((w & 0x10000) != 0) {
          bytes += (w & 0xff).toInt
          done = (w & 0x100) != 0
        } else { spins += 1; assert(spins < 3000, s"$name: RXF starved") }
      }
      bytes.toSeq
    }
    def setMarker(at: BigInt, width: Int, period: Long): Unit = {
      write(0x03c, period)
      write(0x030, (at & 0xffffffffL).toLong)
      write(0x034, ((at >> 32) & 0xffffffffL).toLong)
      write(0x038, width) // arms
    }
  }

  // dspClk periods deliberately incommensurate with the 16000 ps link clock: the capture
  // dither (spec README §3) needs unrelated clocks — commensurate periods lock the trigger
  // phase and make the ±1-cycle quantization systematic (spec 08 risk 4).
  case class LinkSetup(dspPA: Long, dspPB: Long, cfg: GtySimPhy.Config)

  def run(name: String, setup: LinkSetup, exchanges: Int, tracePath: String = null)(
      checks: (Seq[(Double, Double)], NodeCtl, NodeCtl, TwoNodeTb, Long, Long) => Unit): Unit =
    compiled.doSim(name, seed = 7) { dut =>
      val rng = new Random(7)
      for (p <- Seq(dut.io.phyA, dut.io.phyB)) { // node-driven pins float until reset released
        p.clkRef #= false; p.clkRx #= false; p.rxDataRaw #= 0
        p.ready #= false; p.aligned #= false; p.diceCount #= 0
      }
      dut.io.timeOffsetA #= 0
      dut.io.timeOffsetB #= 0
      dut.a.tlBus.node.bus.a.valid #= false // phantom-A (SOC_TIPS §2.1)
      dut.b.tlBus.node.bus.a.valid #= false

      // dspClk togglers with private phases (the "arbitrary phase" of spec 06 §4)
      val phaseA = 1 + rng.nextInt(setup.dspPA.toInt - 2)
      val phaseB = 1 + rng.nextInt(setup.dspPB.toInt - 2)
      def dspClock(pin: Bool, period: Long, phase: Long) = fork {
        pin #= false
        sleep(phase)
        while (true) { pin #= true; sleep(period / 2); pin #= false; sleep(period - period / 2) }
      }
      dspClock(dut.io.dspClkA, setup.dspPA, phaseA)
      dspClock(dut.io.dspClkB, setup.dspPB, phaseB)

      GtySimPhy.link(dut.io.phyA, dut.io.phyB, setup.cfg)
      dut.clockDomain.forkStimulus(HOST_P)

      val trace = if (tracePath != null) new PrintWriter(tracePath) else null
      val agentA = {
        implicit val ida = new IdAllocator(DebugId.width); implicit val idc = new IdCallback
        new MasterAgent(dut.a.tlBus.node.bus, dut.clockDomain)
      }
      val agentB = {
        implicit val ida = new IdAllocator(DebugId.width); implicit val idc = new IdCallback
        new MasterAgent(dut.b.tlBus.node.bus, dut.clockDomain)
      }
      val A = new NodeCtl("A", agentA, dut.clockDomain, trace)
      val B = new NodeCtl("B", agentB, dut.clockDomain, trace)

      fork { sleep(1500000000L); simFailure("watchdog: run did not finish in 1.5 ms sim time") }
      dut.clockDomain.waitSampling(20)
      A.release()
      B.release()
      A.awaitUp()
      B.awaitUp()

      // spec 07 §2 exchange: one outstanding at a time, A = master, B = slave
      var seqA, seqB = 0 // expected TSU capture counters (4-bit, per TSU pair they move together)
      val points = mutable.ArrayBuffer[(Double, Double)]() // (tMid in master cycles, offset_MS)
      for (k <- 0 until exchanges) {
        seqA += 1
        A.sendFrame(typ = 1, seq = k & 0xff)
        val t1 = A.takeTs(0x010, seqA)
        val t2 = B.takeTs(0x020, seqA)
        assert(B.popFrame() == Seq(1, k & 0xff), "SYNC frame body mismatch")
        seqB += 1
        B.sendFrame(typ = 2, seq = k & 0xff)
        val t3 = B.takeTs(0x010, seqB)
        val t4 = A.takeTs(0x020, seqB)
        assert(A.popFrame() == Seq(2, k & 0xff), "DELAY_REQ frame body mismatch")
        val offset = ((t1 - t2) + (t4 - t3)).toDouble / 2 // README §3 symmetric-link identity
        val tMid   = ((t1 + t4).toDouble / 2) - REF_INIT_A.toDouble
        points += ((tMid, offset))
        if (trace != null)
          trace.println(s"""{"exchange":$k,"t1":$t1,"t2":$t2,"t3":$t3,"t4":$t4,"offset":$offset}""")
      }
      checks(points.toSeq, A, B, dut, phaseA, phaseB)
      if (trace != null) trace.close()
    }

  def fit(points: Seq[(Double, Double)]): (Double, Double) = { // (intercept at x̄, slope)
    val n  = points.size
    val mx = points.map(_._1).sum / n
    val my = points.map(_._2).sum / n
    val b  = points.map(p => (p._1 - mx) * (p._2 - my)).sum / points.map(p => (p._1 - mx) * (p._1 - mx)).sum
    (my, b)
  }

  // ═══ run 1: nominal — offset recovery, correction, marker coincidence ═══════════════════════
  val symCfg = GtySimPhy.Config(delayAtoBPs = 40000, delayBtoAPs = 40000, seed = 11)
  run("nominal", LinkSetup(1999, 1999, symCfg), exchanges = 40,
      tracePath = "simWorkspace/wr_two_node_trace.jsonl") { (points, aCtl, bCtl, dut, phaseA, phaseB) =>
    val (mean, slope) = fit(points)
    // truth: refA(t) − refB(t) with equal periods is time-independent up to the phase term
    val truth = (REF_INIT_A - REF_INIT_B).toDouble + (phaseB - phaseA).toDouble / 1999
    assert((mean - truth).abs <= 0.25,
      s"nominal: mean offset $mean vs truth $truth — outside ±0.25 cycle (±0.5 ns)")
    assert((slope * points.map(_._1).max).abs <= 0.25, s"nominal: spurious drift, slope=$slope")

    // spec 07 §4 correction: timeOffset_S += round(offset_syncTime); both timeOffsets were 0
    val corr = scala.math.round(mean)
    val wrap = BigInt(1) << 64
    dut.io.timeOffsetB #= ((BigInt(corr) % wrap) + wrap) % wrap // two's-complement wrap
    aCtl.note(s"""{"decision":{"mean":$mean,"slope":$slope,"corr":$corr}}""") // W5 parity golden
    println(f"[nominal] mean=$mean%.3f truth=$truth%.3f slope=$slope%.2e corr=$corr")

    // marker verification: same syncTime target on both boards → pins rise within 1 cycle
    val nowA   = REF_INIT_A + BigInt((simTime() - phaseA) / 1999)
    val target = nowA + 5000 // margin for the register writes below (~600 dspClk cycles)
    aCtl.setMarker(target, width = 50, period = 0)
    bCtl.setMarker(target, width = 50, period = 0)
    var tA, tB = 0L
    val wA = fork { waitUntil(dut.io.markerA.toBoolean); tA = simTime() }
    val wB = fork { waitUntil(dut.io.markerB.toBoolean); tB = simTime() }
    wA.join(); wB.join()
    assert((tA - tB).abs <= 1999,
      s"markers ${tA - tB} ps apart after correction — outside 1 dspClk cycle")
    println(f"[nominal] marker skew after correction: ${tA - tB} ps")
  }

  // ═══ run 2: ppm — the fit recovers the injected drift slope ════════════════════════════════
  run("ppm", LinkSetup(1999, 2003, symCfg), exchanges = 14) { (points, _, _, _, _, _) =>
    val (_, slope) = fit(points)
    val expected = 1.0 - 1999.0 / 2003.0 // d(offset_MS)/d(refTime_M), cycles per master cycle
    assert((slope - expected).abs <= 0.1 * expected,
      s"ppm: fitted slope $slope vs injected $expected — > 10% off")
    println(f"[ppm] slope=$slope%.3e expected=$expected%.3e")
  }

  // ═══ run 3: asymmetric link — pins the offset sign convention ══════════════════════════════
  val asymCfg = GtySimPhy.Config(delayAtoBPs = 40000, delayBtoAPs = 72000, seed = 12)
  run("asym", LinkSetup(1999, 1999, asymCfg), exchanges = 20) { (points, _, _, _, phaseA, phaseB) =>
    val (mean, _) = fit(points)
    val truth = (REF_INIT_A - REF_INIT_B).toDouble + (phaseB - phaseA).toDouble / 1999
    val shift = (72000.0 - 40000.0) / 2 / 1999 // (d_BA − d_AB)/2 in cycles
    assert((mean - (truth + shift)).abs <= 0.3,
      s"asym: mean $mean vs truth+shift ${truth + shift} — asymmetry does not shift by (dBA−dAB)/2")
    println(f"[asym] mean-truth=${mean - truth}%.3f expected shift=$shift%.3f")
  }

  println("[WrTwoNodeSim] all PASS")
}
