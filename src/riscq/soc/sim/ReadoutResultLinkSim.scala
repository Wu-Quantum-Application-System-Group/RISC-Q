package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.DebugId
import spinal.lib.bus.tilelink.fabric.MasterBus
import spinal.lib.bus.tilelink.sim.{IdAllocator, IdCallback, MasterAgent}
import spinal.lib.bus.misc.SizeMapping
import riscq.dsp._
import riscq.dsp.pulse._
import riscq.soc.fabric.MemMapFiber
import riscq.soc.link.{EventLink, ReadoutResultSink, PutLink, SinkSpec}

import scala.math.{cos, sin}

/**
 * Sign-off for the readout-result up-path: a real carrier-triggered [[ReadoutDecoder]] integrates a
 * tone over two scheduled carrier-`Flow` windows; its `res.valid` level is carried as settled/cleared
 * beats on the upstream posted `Flow(Event)` ([[EventLink.resultSource]] + [[EventLink.merge]]),
 * **pipelined** by `linkPipe` stages, and rebuilt by a core-side [[ReadoutResultSink]]. The CPU (a `MasterAgent`) reads
 * `res`@4 / `real`@8 / `imag`@12 from the sink's **local** map — the `res` read HALTS locally until the
 * integral settles — and the values are checked bit-exact vs the windowed-demod golden.
 *
 * The freshness contract (spec §2.4) is exercised directly:
 *   - a `res` read issued **before the first window settles** halts until it does, then returns A;
 *   - re-reading with no new window returns A again (`read_res` is **idempotent** — no consume);
 *   - the sink's mirrored `valid` **drops** while window B integrates (the level mechanism) and rises
 *     on its settle; a `res` read issued **after waiting past B's opening** returns B (not the stale A).
 *
 * Swept over `linkPipe ∈ {0, 4, 16}` to show the path is distance-tolerant (every stage a plain
 * `RegNext`, absorbed by the software wait). Run with `mill runMain riscq.soc.sim.ReadoutResultLinkSim`.
 */
object ReadoutResultLinkSim extends App {
  val N = 4; val w = 16; val accWidth = 32
  val amax = (BigInt(1) << (w - 1)) - 1
  val saturate = false

  // ── tone + golden (windowed-demod model) ──
  val freq = 1.0 / 16
  def clampCode(s: Long): Int = (if (s > amax) amax.toLong else if (s < -amax) -amax.toLong else s).toInt
  def toCode(v: Double): Int  = clampCode(scala.math.round(v * (1 << (w - 1))))
  def signFor(c: Int): Int    = if (c < 130) 1 else -1
  def carrierCode(c: Int, k: Int): (Int, Int) = {
    val th = (c.toLong * N + k) * freq; (toCode(0.5 * cos(th)), toCode(0.5 * sin(th)))
  }
  def adcCode(c: Int, k: Int): (Int, Int) = { val (cr, ci) = carrierCode(c, k); val s = signFor(c); (s * cr, -s * ci) }
  def demodModel(c: Int, k: Int): (BigInt, BigInt) = {
    val (ar, ai) = adcCode(c, k); val (br, bi) = carrierCode(c, k); ComplexMul.model(w, ar, ai, br, bi, saturate)
  }
  def add(a: (BigInt, BigInt), b: (BigInt, BigInt)) = (a._1 + b._1, a._2 + b._2)
  def batchSum(c: Int): (BigInt, BigInt) = (0 until N).map(demodModel(c, _)).foldLeft((BigInt(0), BigInt(0)))(add)
  def wrap(x: BigInt): BigInt = { val m = BigInt(1) << accWidth; val r = ((x % m) + m) % m; if (r >= (BigInt(1) << (accWidth - 1))) r - m else r }
  def windowSum(st: Int, dur: Int): (BigInt, BigInt) = {   // window = exactly the `dur` valid batches [st, st+dur)
    val s = (st until st + dur).map(batchSum).foldLeft((BigInt(0), BigInt(0)))(add); (wrap(s._1), wrap(s._2))
  }
  def signedAcc(u: BigInt): BigInt = { val m = BigInt(1) << 32; val r = ((u % m) + m) % m; if (r >= (BigInt(1) << 31)) r - m else r }

  // two carrier windows: A (positive sign, c<130) and B (negative sign, c≥130).
  val rA = (90, 6); val rB = (200, 5); val totalCycles = 360

  case class Dut(linkPipe: Int) extends Component {
    val tlBus = new MasterBus(tilelink.M2sParameters(addressWidth = 16, dataWidth = 32,
      masters = List(tilelink.M2sAgent(name = this, mapping = List(tilelink.M2sSource(
        id = SizeMapping(0, 4), emits = tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(0x40), putFull = tilelink.SizeRange.upTo(0x40),
          putPartial = tilelink.SizeRange.upTo(0x40))))))))
    val adcIn     = in    port ComplexBatch(N, w)
    val carrierIn = slave port Flow(ComplexBatch(N, w))    // the demod carrier window (drives the decoder)

    // ── DSP side: carrier-triggered decoder fed the tone + carrier windows ──
    val rd = ReadoutDecoder(ReadoutDecoderParams(batchSize = N, dataWidth = w, accWidth = accWidth,
      maxWinLog2 = 14, saturate = saturate))
    rd.io.adc     := adcIn
    rd.io.carrier << carrierIn

    // ── res.valid forwarded UP (as a level) through the link into the level-mirror sink ──
    val spec  = SinkSpec("res", EventLink.resultKind, 0, EventLink.sinkBase, EventLink.resultDataWidth(accWidth))
    val upSrc = EventLink.merge(Seq(EventLink.resultSource(rd.io.res.valid, rd.io.res.payload, rd.io.real, rd.io.imag, accWidth)), Seq(spec))
    val sink  = ReadoutResultSink(accWidth, base = spec.base)
    sink.resultIn << PutLink.pipe(upSrc, linkPipe)
    sink.valid.simPublic()

    // ── core-side local read map for res/real/imag ──
    val sinkMap = MemMapFiber(addressWidth = 16, dataWidth = 32)
    sinkMap.addMapping(sink.mapping)
    sinkMap.up at 0 of tlBus.node

    def demodLatency: Int = rd.demodLatency
  }

  def runOne(linkPipe: Int): Unit = {
    SimConfig.compile(Dut(linkPipe)).doSim(s"readoutResultLink_pipe$linkPipe", seed = 42) { dut =>
      val cd = dut.clockDomain
      dut.tlBus.node.bus.a.valid #= false
      dut.carrierIn.valid #= false
      for (k <- 0 until N) { dut.adcIn(k).re #= 0; dut.adcIn(k).im #= 0; dut.carrierIn.payload(k).re #= 0; dut.carrierIn.payload(k).im #= 0 }
      cd.forkStimulus(10)
      implicit val idAllocator = new IdAllocator(DebugId.width)
      implicit val idCallback  = new IdCallback
      val agent = new MasterAgent(dut.tlBus.node.bus, cd)

      val Ld = dut.demodLatency
      def inWin(c: Int): Boolean = Seq(rA, rB).exists { case (st, dur) => c >= st && c < st + dur }

      // free-running tone (adc always live; carrier zero + valid low outside its window). `simTime`
      // (cooperative var) mirrors the driving cycle; svalid records the sink's mirrored level per cycle.
      var simTime = 0
      val svalid = Array.fill(totalCycles)(false)
      fork {
        for (c <- 0 until totalCycles) {
          simTime = c
          val on = inWin(c)
          dut.carrierIn.valid #= on
          for (k <- 0 until N) {
            val (ar, ai) = adcCode(c, k)
            dut.adcIn(k).re #= ar; dut.adcIn(k).im #= ai
            val (cr, ci) = if (on) carrierCode(c, k) else (0, 0)
            dut.carrierIn.payload(k).re #= cr; dut.carrierIn.payload(k).im #= ci
          }
          cd.waitSampling()
          svalid(c) = dut.sink.valid.toBoolean
        }
      }

      def readTriplet(): (Boolean, BigInt, BigInt) = {
        val resWord = agent.getInt(0, 0x4200)                 // HALTS locally until the integral has settled
        ((resWord & 1) != 0, signedAcc(agent.getInt(0, 0x4204)), signedAcc(agent.getInt(0, 0x4208)))
      }

      // ── read #1: issued BEFORE the first window settles ⇒ the res read halts until A settles ⇒ A ──
      waitUntil(simTime >= 20)
      val (sgnA1, reA1, imA1) = readTriplet()
      // ── read #2: no new window ⇒ read_res is idempotent ⇒ still A ──
      val (sgnA2, reA2, imA2) = readTriplet()
      assert((sgnA1, reA1, imA1) == (sgnA2, reA2, imA2), s"[pipe$linkPipe] read_res not idempotent: A1=($sgnA1,$reA1,$imA1) A2=($sgnA2,$reA2,$imA2)")

      // ── wait past window B's opening (its winStart drops the mirrored level) then read ⇒ fresh B ──
      waitUntil(simTime >= rB._1 + rB._2 + Ld + linkPipe + 6)
      val (sgnB, reB, imB) = readTriplet()

      // bit-exact vs golden, correct signs.
      for ((label, st, dur, sgn, re, im) <- Seq(("A", rA._1, rA._2, sgnA1, reA1, imA1), ("B", rB._1, rB._2, sgnB, reB, imB))) {
        val (gr, gi) = windowSum(st, dur)
        assert(re == gr && im == gi, s"[$label pipe$linkPipe] real/imag dut=($re,$im) golden=($gr,$gi)")
        assert(sgn == (gr < 0), s"[$label pipe$linkPipe] res.sign dut=$sgn golden=${gr < 0}")
      }
      assert(windowSum(rA._1, rA._2)._1 > 0 && windowSum(rB._1, rB._2)._1 < 0, "windows should be +/-")

      // the mirrored level dropped while B integrated (the freshness mechanism) and rose on its settle.
      val bLo = (rB._1 until rB._1 + rB._2 + Ld + linkPipe + 4).exists(c => c < totalCycles && !svalid(c))
      val bHi = (rB._1 + rB._2 + Ld + linkPipe until totalCycles).exists(c => svalid(c))
      assert(bLo, s"[pipe$linkPipe] sink.valid never dropped during window B (level mechanism broken)")
      assert(bHi, s"[pipe$linkPipe] sink.valid never rose after window B settled")

      println(s"[ReadoutResultLinkSim] PASS linkPipe=$linkPipe: level-mirror up-Flow — halting first read → A, " +
        s"idempotent re-read → A, level drops during B, wait-past-open read → B (bit-exact +/- integrals).")
      simSuccess()
    }
  }

  for (pipe <- Seq(0, 4, 16)) runOne(pipe)
}
