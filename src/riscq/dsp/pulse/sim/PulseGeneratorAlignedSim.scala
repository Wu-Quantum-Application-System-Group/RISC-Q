package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp._
import riscq.dsp.pulse._

import scala.collection.mutable

/**
 * End-to-end self-checking testbench for [[PulseGeneratorAligned]] (the QubiC-style SRL-alignment
 * generator). It reuses the [[PulseGeneratorSim]] golden verbatim — `PulseGeneratorAligned` is
 * bit-exact with [[PulseGenerator]] by construction (every consumer still sees its parameter at
 * `startTime − leadₓ`), so the same composed golden, the same exact `[startTime, startTime+dur)`
 * window, and the same zeroed-between-pulses checks must all hold.
 *
 * The only driver difference is the interface: {amp, phase, addr, dur} are pushed as '''one''' combined
 * `io.params` Flow (one shared valid), while `freq` is its own Flow. The virtual-Z pulse (phase-only in
 * [[PulseGeneratorSim]]) re-pushes the held `amp` in the combined entry and pushes no `freq` — exactly
 * what the real [[riscq.soc.rf.PulseParamBuffer]] fire path does (a table fire always carries all four).
 *
 * Run: `mill runMain riscq.dsp.pulse.sim.PulseGeneratorAlignedSim`.
 */
object PulseGeneratorAlignedSim extends App {

  def envB(k: String, d: Boolean) = sys.env.get(k).map(_.toBoolean).getOrElse(d)
  val basep = PulseGeneratorParams(batchSize = 4, dataWidth = 16, timeWidth = 16,
    addrWidth = 6, durWidth = 8, queueDepth = 4, memLatency = 2,
    queueUseVec = envB("RISCQ_QUEUE_USEVEC", false), queueForFMax = envB("RISCQ_QUEUE_FORFMAX", false))
  val N     = basep.batchSize
  val w     = basep.dataWidth
  val amax  = (BigInt(1) << (w - 1)) - 1
  val maskW = BigInt(1) << w
  val nA    = 1 << basep.addrWidth

  def signedW(pat: BigInt): BigInt = if (pat.testBit(w - 1)) pat - maskW else pat
  def uMod(x: BigInt, m: BigInt): BigInt = ((x % m) + m) % m

  // envelope memory content: shared with PulseGeneratorSim (same per-lane re/im scheme).
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a) else BigInt(a * 5 + k * 11 + 3)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 1) & (maskW - 1)
  val content: Seq[BigInt] = (0 until nA).map { a =>
    var word = BigInt(0)
    for (k <- 0 until N) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }

  /** test top: PulseGeneratorAligned + preloaded envelope Mem with a memLatency read path. */
  case class Tb(p: PulseGeneratorParams) extends Component {
    val io = new Bundle {
      val time      = in port UInt(p.timeWidth bits)
      val startTime = in port UInt(p.timeWidth bits)
      val freq      = slave port Flow(SInt(w bits))
      val params    = slave port Flow(AlignedPulseParams(w, p.addrWidth, p.durWidth))
      val pulse     = master port Flow(ComplexBatch(N, w))
    }
    val pg = PulseGeneratorAligned(p)
    pg.io.time := io.time; pg.io.startTime := io.startTime
    pg.io.freq << io.freq; pg.io.params << io.params
    io.pulse << pg.io.pulse
    val mem = Mem.fill(content.length)(Bits(p.batchSize * 2 * w bits)) init (content.map(c => B(c, p.batchSize * 2 * w bits)))
    pg.io.memPort.rsp := Delay(mem.readSync(pg.io.memPort.cmd.payload), p.memLatency - 1)
  }

  def gPhase(freq: BigInt, phase: BigInt, tau: BigInt): BigInt = {
    val bt    = signedW((tau * N) & (maskW - 1))
    val tpLow = signedW(uMod(freq * bt, maskW))
    signedW(uMod(tpLow + phase, maskW))
  }
  def env(addr: Int, k: Int): (BigInt, BigInt) =
    (signedW(reK(addr & (nA - 1), k)), signedW(imK(addr & (nA - 1), k)))

  case class Pulse(startTime: Int, amp: BigInt, freq: BigInt, phase: BigInt, base: Int, dur: Int)

  def run(prescaleAmp: Boolean, saturate: Boolean, realOutput: Boolean = false): Unit = {
    val p  = basep.copy(prescaleAmp = prescaleAmp, saturate = saturate, realOutput = realOutput)
    val cp = CordicParams(xyWidth = w, zWidth = w, correctGain = !prescaleAmp, saturate = saturate)
    val phMag: BigInt =
      if (saturate) amax else amax - BigInt(scala.math.ceil(Cordic.errorBound(cp, amax.toDouble)).toLong) - 1
    val phasorMag: BigInt = if (prescaleAmp) BigInt((phMag.toDouble / Cordic.gain(cp.iters)).round) else phMag

    def cmul(ar: BigInt, ai: BigInt, br: BigInt, bi: BigInt): (BigInt, BigInt) = {
      val v = ComplexMul.model(w, ar, ai, br, bi, saturate)
      if (!saturate) assert(v == ComplexMul.model(w, ar, ai, br, bi, saturate = true),
        s"[no-sat] ComplexMul overflowed: ($ar,$ai)×($br,$bi) — headroom too small")
      v
    }
    def crot(mag: BigInt, z: BigInt): (BigInt, BigInt) = {
      val v = Cordic.rotate(cp, mag, BigInt(0), z)
      if (!saturate) assert(v == Cordic.rotate(cp.copy(saturate = true), mag, BigInt(0), z),
        s"[no-sat] Cordic overflowed: mag=$mag z=$z — headroom too small")
      v
    }
    def phasor(freq: BigInt, k: Int): (BigInt, BigInt) =
      crot(phasorMag, signedW((BigInt(k) * freq) & (maskW - 1)))
    def carrier(amp: BigInt, freq: BigInt, phase: BigInt, tau: BigInt, k: Int): (BigInt, BigInt) = {
      val (cr, ci) = crot(amp, gPhase(freq, phase, tau))
      val (pr, pi) = phasor(freq, k)
      cmul(cr, ci, pr, pi)
    }
    def goldenPulse(amp: BigInt, freq: BigInt, phase: BigInt, base: Int, i: Int, tau: BigInt, k: Int): (BigInt, BigInt) = {
      val (cr, ci) = carrier(amp, freq, phase, tau, k)
      val (er, ei) = env(base + i, k)
      cmul(cr, ci, er, ei)
    }

    SimConfig.compile(Tb(p)).doSim(s"pulseAligned_pre${prescaleAmp}_sat$saturate", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.params.valid #= false; dut.io.freq.valid #= false
      dut.io.time #= 0; dut.io.startTime #= 0
      dut.clockDomain.waitSampling(2)

      val timeToPulse = dut.pg.timeToPulse
      val totalCycles = 320
      val capV  = Array.ofDim[Boolean](totalCycles)
      val capRe = Array.ofDim[BigInt](totalCycles, N)
      val capIm = Array.ofDim[BigInt](totalCycles, N)

      // combined-params push (amp,phase,addr,dur together) and an optional separate freq push per cycle.
      case class ParamPush(amp: BigInt, phase: BigInt, addr: BigInt, dur: BigInt, st: Int)
      val paramPushes = mutable.Map[Int, ParamPush]()
      val freqPushes  = mutable.Map[Int, (BigInt, Int)]()

      val pA = Pulse(startTime = 90, amp = 10000, freq = 2000, phase = 3000, base = 10, dur = 6)
      val pB = Pulse(startTime = 150, amp = 7000, freq = -1500, phase = 1000, base = 20, dur = 5)
      // C is virtual-Z: new phase, but re-states B's amp in the combined entry and pushes no freq.
      val pC = Pulse(startTime = 210, amp = pB.amp, freq = pB.freq, phase = -8000, base = 30, dur = 4)
      for (pu <- Seq(pA, pB)) {
        val c = pu.startTime - 60
        paramPushes(c) = ParamPush(pu.amp, pu.phase, BigInt(pu.base), BigInt(pu.dur), pu.startTime)
        freqPushes(c)  = (pu.freq, pu.startTime)
      }
      val cC = pC.startTime - 60
      paramPushes(cC) = ParamPush(pC.amp, pC.phase, BigInt(pC.base), BigInt(pC.dur), pC.startTime) // amp re-stated, no freq

      for (c <- 0 until totalCycles) {
        dut.io.time #= c
        dut.io.params.valid #= false; dut.io.freq.valid #= false
        paramPushes.get(c).foreach { pp =>
          dut.io.startTime #= pp.st
          dut.io.params.valid #= true
          dut.io.params.payload.amp   #= pp.amp
          dut.io.params.payload.phase #= pp.phase
          dut.io.params.payload.addr  #= pp.addr
          dut.io.params.payload.dur   #= pp.dur
        }
        freqPushes.get(c).foreach { case (f, st) =>
          dut.io.startTime #= st
          dut.io.freq.valid #= true; dut.io.freq.payload #= f
        }
        dut.clockDomain.waitSampling()
        capV(c) = dut.io.pulse.valid.toBoolean
        for (k <- 0 until N) {
          capRe(c)(k) = dut.io.pulse.payload(k).re.toBigInt
          capIm(c)(k) = dut.io.pulse.payload(k).im.toBigInt
        }
      }

      def checkPulse(pu: Pulse, label: String): Unit = {
        val win = (0 until totalCycles).filter(capV)
          .filter(c => c >= pu.startTime - 5 && c < pu.startTime + pu.dur + 5)
        assert(win.nonEmpty, s"[$label] no valid window found near startTime=${pu.startTime}")
        val c0 = win.head
        assert(win == (c0 until c0 + pu.dur), s"[$label] valid window $win != [$c0, ${c0 + pu.dur})")
        assert(c0 == pu.startTime, s"[$label] window starts at io.time=$c0, expected startTime=${pu.startTime}")
        for (i <- 0 until pu.dur; k <- 0 until N) {
          val tau = BigInt(pu.startTime + i - timeToPulse)
          val (gre, gimFull) = goldenPulse(pu.amp, pu.freq, pu.phase, pu.base, i, tau, k)
          val gim = if (realOutput) BigInt(0) else gimFull
          assert(capRe(c0 + i)(k) == gre && capIm(c0 + i)(k) == gim,
            s"[$label] batch $i lane $k: dut(${capRe(c0 + i)(k)},${capIm(c0 + i)(k)}) != golden($gre,$gim)")
        }
      }
      checkPulse(pA, "A")
      checkPulse(pB, "B")
      checkPulse(pC, "C-virtualZ")

      val totalValid = (0 until totalCycles).count(capV)
      assert(totalValid == pA.dur + pB.dur + pC.dur,
        s"total valid cycles $totalValid != ${pA.dur + pB.dur + pC.dur}")
      for (c <- 0 until totalCycles if !capV(c); k <- 0 until N)
        assert(capRe(c)(k) == 0 && capIm(c)(k) == 0, s"non-zero payload at idle cycle $c lane $k")

      simSuccess()
    }
    println(s"[PulseGeneratorAlignedSim] PASS  N=$N w=$w prescaleAmp=$prescaleAmp saturate=$saturate " +
      s"realOutput=$realOutput: 3 pulses (full / back-to-back / virtual-Z) — single combined params FIFO + " +
      s"SRL alignment, valid window exactly [startTime,+dur), bit-exact composed golden, zeroed between pulses.")
  }

  run(prescaleAmp = false, saturate = true)
  run(prescaleAmp = true, saturate = true)
  run(prescaleAmp = true, saturate = false)
  run(prescaleAmp = false, saturate = false)
  run(prescaleAmp = true, saturate = false, realOutput = true)
}
