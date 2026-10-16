package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp._
import riscq.dsp.pulse._

import scala.collection.mutable

/**
 * End-to-end self-checking testbench for [[PulseGenerator]]. A test top wraps the toplevel around a
 * preloaded envelope `Mem`.
 * The golden pulse is the '''composition of the per-block bit-exact models''' — for output batch `i`
 * (output cycle `startTime+i`): `pulse[k] = ComplexMul(carrier[k], env[base+i][k])`,
 * `carrier[k] = ComplexMul(Cordic(amp,0,gPhase(τ)), phasor[k])`,
 * `phasor[k] = Cordic(Amax,0, k·freq)`, `env` split from the mem, with `τ = (startTime+i) −
 * timeToPulse` (the carrier's absolute-time alignment) and `base+i` the auto-incrementing address.
 *
 * Checks: the valid window is '''exactly''' `[startTime, startTime+dur)`; the payload is bit-exact
 * vs the composed golden over the window (which, since every parameter must be aligned by its derived
 * lead time, simultaneously validates all six lead times); the payload is zeroed outside the window;
 * and back-to-back pulses + a phase-only (virtual-Z) update each land correctly. Run with
 * `mill runMain riscq.dsp.pulse.sim.PulseGeneratorSim`.
 */
object PulseGeneratorSim extends App {

  // queue knobs env-overridable to A/B the FIFO variants and the queue levers; the defaults
  // match the production default (lean distributed-RAM queue, levers off) so the standard run covers it.
  def envB(k: String, d: Boolean) = sys.env.get(k).map(_.toBoolean).getOrElse(d)
  val queueImpl = sys.env.getOrElse("RISCQ_QUEUE_IMPL", "RegHead") match {
    case "RegHead"   => TimedQueueImpl.RegHead
    case "Srl"       => TimedQueueImpl.Srl
    case "Shadow"    => TimedQueueImpl.Shadow
    case "SrlShadow" => TimedQueueImpl.SrlShadow
  }
  val basep = PulseGeneratorParams(batchSize = 4, dataWidth = 16, timeWidth = 16,
    addrWidth = 6, durWidth = 8, queueDepth = 4, memLatency = 2,
    queueUseVec = envB("RISCQ_QUEUE_USEVEC", false), queueForFMax = envB("RISCQ_QUEUE_FORFMAX", false),
    queueImpl = queueImpl)
  val N     = basep.batchSize
  val w     = basep.dataWidth
  val amax  = (BigInt(1) << (w - 1)) - 1
  val maskW = BigInt(1) << w
  val nA    = 1 << basep.addrWidth

  def signedW(pat: BigInt): BigInt = if (pat.testBit(w - 1)) pat - maskW else pat
  def uMod(x: BigInt, m: BigInt): BigInt = ((x % m) + m) % m

  // envelope memory content: re_0 = addr identifies it, distinct per-lane re/im otherwise.
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a) else BigInt(a * 5 + k * 11 + 3)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 1) & (maskW - 1)
  val content: Seq[BigInt] = (0 until nA).map { a =>
    var word = BigInt(0)
    for (k <- 0 until N) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }

  /** test top: PulseGenerator + preloaded envelope Mem with a memLatency read path. */
  case class Tb(p: PulseGeneratorParams) extends Component {
    val io = new Bundle {
      val time      = in port UInt(p.timeWidth bits)
      val startTime = in port UInt(p.timeWidth bits)
      val amp, freq, phase = slave port Flow(SInt(w bits))
      val addr      = slave port Flow(UInt(p.addrWidth bits))
      val dur       = slave port Flow(UInt(p.durWidth bits))
      val pulse     = master port Flow(ComplexBatch(N, w))
    }
    val pg = PulseGenerator(p)
    pg.io.time := io.time; pg.io.startTime := io.startTime
    pg.io.amp << io.amp; pg.io.freq << io.freq; pg.io.phase << io.phase
    pg.io.addr << io.addr; pg.io.dur << io.dur
    io.pulse << pg.io.pulse
    val mem = Mem.fill(content.length)(Bits(p.batchSize * 2 * w bits)) init (content.map(c => B(c, p.batchSize * 2 * w bits)))
    pg.io.memPort.rsp := Delay(mem.readSync(pg.io.memPort.cmd.payload), p.memLatency - 1)
  }

  // golden carrier / pulse (bit-exact composition of the per-block models). gPhase/env are
  // config-independent; phasor/carrier/goldenPulse depend on the gain mode and live inside run().
  def gPhase(freq: BigInt, phase: BigInt, tau: BigInt): BigInt = {
    val bt    = signedW((tau * N) & (maskW - 1))
    val tpLow = signedW(uMod(freq * bt, maskW))
    signedW(uMod(tpLow + phase, maskW))
  }
  def env(addr: Int, k: Int): (BigInt, BigInt) =
    (signedW(reK(addr & (nA - 1), k)), signedW(imK(addr & (nA - 1), k)))

  case class Pulse(startTime: Int, amp: BigInt, freq: BigInt, phase: BigInt, base: Int, dur: Int)

  /** Run both gain modes: prescaleAmp = false (default, output-corrected CORDICs) and true (both
   *  CORDICs uncorrected, the phasor constant K-prescaled, cordic.latency −1 so the lead times
   *  shrink; the composed golden mirrors all of it bit-exactly via `cp`/`phasorMag`). */
  def run(prescaleAmp: Boolean, saturate: Boolean, realOutput: Boolean = false): Unit = {
    val p  = basep.copy(prescaleAmp = prescaleAmp, saturate = saturate, realOutput = realOutput)
    val cp = CordicParams(xyWidth = w, zWidth = w, correctGain = !prescaleAmp, saturate = saturate)
    // phasor target: full-scale Amax with saturation, else pulled below it by the CORDIC error (mirrors
    // PhasorBatchGenerator.phMag) so the un-clamped phasor still fits in w bits.
    val phMag: BigInt =
      if (saturate) amax else amax - BigInt(scala.math.ceil(Cordic.errorBound(cp, amax.toDouble)).toLong) - 1
    val phasorMag: BigInt = if (prescaleAmp) BigInt((phMag.toDouble / Cordic.gain(cp.iters)).round) else phMag

    // model wrappers: pick clamp/wrap per the flag. In no-sat mode also assert the value did NOT need
    // clamping (clamp == wrap ⇔ it fit in range), so an insufficient headroom fails the sim instead of
    // silently wrapping past the bit-exact (the wrapping golden would otherwise still match the HW).
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

    SimConfig.compile(Tb(p)).doSim(s"pulse_pre${prescaleAmp}_sat$saturate", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.amp.valid #= false; dut.io.freq.valid #= false; dut.io.phase.valid #= false
      dut.io.addr.valid #= false; dut.io.dur.valid #= false
      dut.io.time #= 0; dut.io.startTime #= 0
      dut.clockDomain.waitSampling(2)

      val timeToPulse = dut.pg.timeToPulse
      val totalCycles = 320
      // capture (valid, payload) per cycle while io.time free-runs as a counter (io.time(c) = c).
      val capV  = Array.ofDim[Boolean](totalCycles)
      val capRe = Array.ofDim[BigInt](totalCycles, N)
      val capIm = Array.ofDim[BigInt](totalCycles, N)

      // scheduled pushes per cycle: cycle -> list of (port, value); ports identified by a small tag.
      val pushes = mutable.Map[Int, mutable.ArrayBuffer[(String, BigInt, Int)]]()
      def schedule(cycle: Int, port: String, value: BigInt, st: Int): Unit =
        pushes.getOrElseUpdate(cycle, mutable.ArrayBuffer()).append((port, value, st))

      // pulse A (full param set), pulse B back-to-back, pulse C phase-only (virtual-Z) reusing A's amp/freq.
      val pA = Pulse(startTime = 90, amp = 10000, freq = 2000, phase = 3000, base = 10, dur = 6)
      val pB = Pulse(startTime = 150, amp = 7000, freq = -1500, phase = 1000, base = 20, dur = 5)
      // C is phase-only (virtual-Z): it pushes no amp/freq, so the held parameters are B's.
      val pC = Pulse(startTime = 210, amp = pB.amp, freq = pB.freq, phase = -8000, base = 30, dur = 4)
      // push full sets for A and B well before their starts; C pushes only phase/addr/dur (phase-only).
      for (pu <- Seq(pA, pB)) {
        val c = pu.startTime - 60
        schedule(c, "amp", pu.amp, pu.startTime); schedule(c, "freq", pu.freq, pu.startTime)
        schedule(c, "phase", pu.phase, pu.startTime); schedule(c, "addr", BigInt(pu.base), pu.startTime)
        schedule(c, "dur", BigInt(pu.dur), pu.startTime)
      }
      val cC = pC.startTime - 60
      schedule(cC, "phase", pC.phase, pC.startTime)
      schedule(cC, "addr", BigInt(pC.base), pC.startTime)
      schedule(cC, "dur", BigInt(pC.dur), pC.startTime)

      for (c <- 0 until totalCycles) {
        dut.io.time #= c
        // default all pushes off, then apply scheduled ones for this cycle.
        dut.io.amp.valid #= false; dut.io.freq.valid #= false; dut.io.phase.valid #= false
        dut.io.addr.valid #= false; dut.io.dur.valid #= false
        pushes.get(c).foreach(_.foreach { case (port, value, st) =>
          dut.io.startTime #= st
          port match {
            case "amp"   => dut.io.amp.valid #= true; dut.io.amp.payload #= value
            case "freq"  => dut.io.freq.valid #= true; dut.io.freq.payload #= value
            case "phase" => dut.io.phase.valid #= true; dut.io.phase.payload #= value
            case "addr"  => dut.io.addr.valid #= true; dut.io.addr.payload #= value
            case "dur"   => dut.io.dur.valid #= true; dut.io.dur.payload #= value
          }
        })
        dut.clockDomain.waitSampling()
        capV(c) = dut.io.pulse.valid.toBoolean
        for (k <- 0 until N) {
          capRe(c)(k) = dut.io.pulse.payload(k).re.toBigInt
          capIm(c)(k) = dut.io.pulse.payload(k).im.toBigInt
        }
      }

      // verify one pulse: the valid window is exactly [startTime, startTime+dur) and bit-exact inside.
      def checkPulse(pu: Pulse, label: String): Unit = {
        val win = (0 until totalCycles).filter(capV)
          .filter(c => c >= pu.startTime - 5 && c < pu.startTime + pu.dur + 5)
        assert(win.nonEmpty, s"[$label] no valid window found near startTime=${pu.startTime}")
        val c0 = win.head
        // window is contiguous and exactly dur long.
        assert(win == (c0 until c0 + pu.dur), s"[$label] valid window $win != [$c0, ${c0 + pu.dur})")
        // window starts at io.time == startTime (the lead-time scheduling lands it exactly).
        assert(c0 == pu.startTime, s"[$label] window starts at io.time=$c0, expected startTime=${pu.startTime}")
        // bit-exact value over the window: τ = (startTime+i) − timeToPulse, addr = base+i.
        for (i <- 0 until pu.dur; k <- 0 until N) {
          val tau = BigInt(pu.startTime + i - timeToPulse)
          val (gre, gimFull) = goldenPulse(pu.amp, pu.freq, pu.phase, pu.base, i, tau, k)
          // realOutput drops the (unused) imag lane to 0; the REAL lane must stay bit-exact (same golden).
          val gim = if (realOutput) BigInt(0) else gimFull
          assert(capRe(c0 + i)(k) == gre && capIm(c0 + i)(k) == gim,
            s"[$label] batch $i lane $k: dut(${capRe(c0 + i)(k)},${capIm(c0 + i)(k)}) != golden($gre,$gim)")
        }
      }
      checkPulse(pA, "A")
      checkPulse(pB, "B")
      checkPulse(pC, "C-phaseOnly")

      // exactly three pulses, each dur cycles; everything else is zero (clean DAC between pulses).
      val totalValid = (0 until totalCycles).count(capV)
      assert(totalValid == pA.dur + pB.dur + pC.dur,
        s"total valid cycles $totalValid != ${pA.dur + pB.dur + pC.dur}")
      for (c <- 0 until totalCycles if !capV(c); k <- 0 until N)
        assert(capRe(c)(k) == 0 && capIm(c)(k) == 0, s"non-zero payload at idle cycle $c lane $k")

      simSuccess()
    }
    println(s"[PulseGeneratorSim] PASS  N=$N w=$w prescaleAmp=$prescaleAmp saturate=$saturate " +
      s"realOutput=$realOutput: 3 pulses " +
      s"(full / back-to-back / phase-only) — valid window exactly [startTime,+dur), bit-exact composed " +
      s"golden${if (!saturate) " + no-overflow (clamp==wrap)" else ""}, zeroed between pulses.")
  }

  run(prescaleAmp = false, saturate = true)
  run(prescaleAmp = true, saturate = true)
  run(prescaleAmp = true, saturate = false)  // simple mode: no saturation, phasor headroom
  run(prescaleAmp = false, saturate = false)
  // real-DAC mode (the SoC config: prescaleAmp + no-sat): real lane bit-exact, imag lane pruned to 0.
  run(prescaleAmp = true, saturate = false, realOutput = true)
}
