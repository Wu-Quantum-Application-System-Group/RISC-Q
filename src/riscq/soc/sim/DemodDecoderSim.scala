package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp._
import riscq.dsp.pulse.{ReadoutDecoder, ReadoutDecoderParams}
import riscq.soc.link.Put
import riscq.soc.rf.DemodChannel

import scala.collection.mutable

/**
 * End-to-end self-checking testbench for the **carrier-triggered** demod path — a [[DemodChannel]]
 * (`PulseParamBuffer` + `PulseGenerator`) feeding the [[ReadoutDecoder]] as its carrier `Flow`. The
 * demod carrier is a scheduled, envelope-weighted complex pulse whose `valid` **is** the integration
 * window, so the readout integral is
 *
 * {{{
 *   real + i·imag = Σ_{i ∈ [0, dur)} Σ_k  adc[St+i][k] · (env[base+i][k] · carrier[St+i][k])
 * }}}
 *
 * The golden is the composition of the per-block bit-exact models: the demod carrier is
 * `PulseGenerator`'s composed golden (`env · Cordic-carrier`, mirroring `PulseGeneratorSim`, valid
 * exactly `[St, St+dur)` with `τ = (St+i) − timeToPulse`) and the integral is `ReadoutDecoder`'s window
 * sum. Because each batch's envelope+carrier differs, matching the integral bit-exactly simultaneously
 * validates the **carrier-triggered window alignment**: the decoder integrates exactly the carrier's
 * in-window batches, pairing `adc[St+i]` with `carrier[St+i]` at the same cycle (no arm, no decoder
 * `io.time`). A +1-shifted-window cross-check confirms the alignment is sharp.
 *
 * The demod channel is driven exactly as software drives it: posted `Put`s program the buffer
 * (startTime / freq / table[slot] / fire); firing the demod at `St` for `dur` batches **is** the
 * readout — there is no separate decoder arm, and the window is the demod pulse (no +2 padding, since
 * outside the window the generator zeroes its output and the carrier `valid` is low, so the decoder
 * integrates nothing). Run with `mill runMain riscq.soc.sim.DemodDecoderSim`.
 */
object DemodDecoderSim extends App {

  val N          = 4      // adcBatch (demod/decoder lanes)
  val w          = 16
  val timeWidth  = 16
  val envAddrW   = 6
  val durWidth   = 8      // demod-generator dur field (= the readout window length)
  val accWidth   = 32
  val pulseNum   = 2
  val memLatency = 2
  val putAddrWidth = 16

  val amax  = (BigInt(1) << (w - 1)) - 1
  val maskW = BigInt(1) << w
  val nA    = 1 << envAddrW

  def signedW(pat: BigInt): BigInt = if (pat.testBit(w - 1)) pat - maskW else pat
  def uMod(x: BigInt, m: BigInt): BigInt = ((x % m) + m) % m

  // complex envelope memory (same scheme as PulseGeneratorSim): distinct, non-trivial per addr/lane.
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a) else BigInt(a * 5 + k * 11 + 3)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 1) & (maskW - 1)
  val content: Seq[BigInt] = (0 until nA).map { a =>
    var word = BigInt(0)
    for (k <- 0 until N) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }
  def env(addr: Int, k: Int): (BigInt, BigInt) =
    (signedW(reK(addr & (nA - 1), k)), signedW(imK(addr & (nA - 1), k)))

  def gPhase(freq: BigInt, phase: BigInt, tau: BigInt): BigInt = {
    val bt    = signedW((tau * N) & (maskW - 1))
    val tpLow = signedW(uMod(freq * bt, maskW))
    signedW(uMod(tpLow + phase, maskW))
  }

  /** Test top: DemodChannel (poke-able posted Put + timeBcast) with a preloaded envelope Mem, feeding
   *  a carrier-triggered ReadoutDecoder. The carrier `Flow` (valid + payload) is connected straight to
   *  the decoder — no stage — so the decoder integrates the carrier's in-window batches at the very
   *  cycle they are emitted, pairing them with the live `adc` of the same cycle. */
  case class Tb(prescaleAmp: Boolean, saturate: Boolean) extends Component {
    val io = new Bundle {
      val cmd       = slave port Flow(Put(putAddrWidth))
      val time      = in    port UInt(timeWidth bits)
      val adc       = in    port ComplexBatch(N, w)
      val real      = out   port SInt(accWidth bits)
      val imag      = out   port SInt(accWidth bits)
      val resValid  = out   port Bool()
      val resSign   = out   port Bool()
    }
    val demod = DemodChannel(pulseNum = pulseNum, batchSize = N, dataWidth = w, envAddrWidth = envAddrW,
      durWidth = durWidth, timeWidth = timeWidth, memLatency = memLatency, prescaleAmp = prescaleAmp,
      saturate = saturate, phasorMethod = SinCosMethod.Cordic, putAddrWidth = putAddrWidth)
    demod.io.cmd << io.cmd
    demod.io.timeBcast := io.time
    val mem = Mem.fill(content.length)(Bits(N * 2 * w bits)) init (content.map(c => B(c, N * 2 * w bits)))
    demod.io.memPort.rsp := Delay(mem.readSync(demod.io.memPort.cmd.payload), memLatency - 1)

    val decoder = ReadoutDecoder(ReadoutDecoderParams(batchSize = N, dataWidth = w, accWidth = accWidth,
      maxWinLog2 = 14, saturate = saturate))
    decoder.io.adc     := io.adc
    decoder.io.carrier << demod.io.carrier
    io.real := decoder.io.real; io.imag := decoder.io.imag
    io.resValid := decoder.io.res.valid; io.resSign := decoder.io.res.payload
    val timeToPulse = demod.timeToPulse
  }

  def w16(v: BigInt): Int = (((v & 0xFFFF) << 16) & 0xFFFFFFFFL).toInt

  // a deterministic ADC tone, distinct per batch/lane so the window integral pins the alignment.
  def adcCode(t: Int, k: Int): (Int, Int) = {
    def clamp(s: Long): Int = (if (s > amax.toLong) amax.toLong else if (s < -amax.toLong) -amax.toLong else s).toInt
    val ar = clamp(scala.math.round(0.5 * amax.toDouble * scala.math.cos(0.11 * (t * N + k) + 0.3)))
    val ai = clamp(scala.math.round(0.5 * amax.toDouble * scala.math.sin(0.07 * (t * N + k) + 0.9)))
    (ar, ai)
  }

  def run(prescaleAmp: Boolean, saturate: Boolean): Unit = {
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
    def phasor(freq: BigInt, k: Int): (BigInt, BigInt) = crot(phasorMag, signedW((BigInt(k) * freq) & (maskW - 1)))
    def carrier(amp: BigInt, freq: BigInt, phase: BigInt, tau: BigInt, k: Int): (BigInt, BigInt) = {
      val (cr, ci) = crot(amp, gPhase(freq, phase, tau)); val (pr, pi) = phasor(freq, k); cmul(cr, ci, pr, pi)
    }
    // the demod carrier = env · carrier (the PulseGenerator composed golden), envelope index i, carrier
    // absolute-time τ = (St+i) − timeToPulse (the generator's carrier alignment).
    def demodCarrier(amp: BigInt, freq: BigInt, phase: BigInt, base: Int, i: Int, tau: BigInt, k: Int): (BigInt, BigInt) = {
      val (cr, ci) = carrier(amp, freq, phase, tau, k); val (er, ei) = env(base + i, k); cmul(cr, ci, er, ei)
    }

    // the readout integral golden: adc[St+i] · demodCarrier[St+i], summed over lanes and the window
    // [St, St+dur) — exactly the carrier's valid batches, paired with the live adc of the same cycle.
    def wrap(x: BigInt): BigInt = {
      val m = BigInt(1) << accWidth; val r = ((x % m) + m) % m
      if (r >= (BigInt(1) << (accWidth - 1))) r - m else r
    }
    def windowSum(St: Int, dur: Int, amp: BigInt, freq: BigInt, phase: BigInt, base: Int, tToPulse: Int): (BigInt, BigInt) = {
      var sr = BigInt(0); var si = BigInt(0)
      for (i <- 0 until dur) {
        val tau = St + i                        // generator schedule-time of window batch i (envelope idx i)
        for (k <- 0 until N) {
          val (cr, ci) = demodCarrier(amp, freq, phase, base, i, BigInt(tau - tToPulse), k)
          val (ar, ai) = adcCode(tau + 1, k)    // adc at the physical output cycle: the buffer's
                                                // RegNext(time) lands the carrier window one cycle late,
                                                // so the live adc leads the carrier batch by 1.
          val (dr, di) = cmul(ar, ai, cr, ci)
          sr += dr; si += di
        }
      }
      (wrap(sr), wrap(si))
    }

    SimConfig.compile(Tb(prescaleAmp, saturate)).doSim(s"demodDecoder_pre${prescaleAmp}_sat$saturate", seed = 42) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.cmd.valid #= false; dut.io.cmd.payload.address #= 0; dut.io.cmd.payload.data #= 0
      dut.io.time #= 0
      for (k <- 0 until N) { dut.io.adc(k).re #= 0; dut.io.adc(k).im #= 0 }
      cd.waitSampling(2)

      val tToPulse = dut.timeToPulse

      // one readout: the demod fired at St for `dur` batches IS the window (no separate decoder arm).
      val St = 90; val dur = 6
      val amp = 9000; val freq = 1800; val phase = 3000; val base = 10; val slot = 0
      val totalCycles = 220

      // posted demod-buffer writes, scheduled well before St (lead-time queues pop early). One Put per
      // cycle, mirroring the software order: startTime, freq, table[slot] (phase/amp/env/dur), fire.
      val fireC = St - 60
      val prog = mutable.Map[Int, (Int, Int)]()  // cycle -> (addr, data)
      val b = (slot + 1) * 0x10
      val seq = Seq(
        (0x4100, St), (0x4, w16(freq)),
        (b + 0, w16(phase)), (b + 4, w16(amp)), (b + 8, w16(base)), (b + 12, w16(dur)),
        (0x0, slot))  // fire
      for ((cmd, off) <- seq.zipWithIndex) prog(fireC + off * 2) = cmd

      var resValidAt = -1
      var realAt = BigInt(0); var imagAt = BigInt(0); var signAt = false
      for (c <- 0 until totalCycles) {
        dut.io.time #= c
        for (k <- 0 until N) { val (ar, ai) = adcCode(c, k); dut.io.adc(k).re #= ar; dut.io.adc(k).im #= ai }
        dut.io.cmd.valid #= false
        prog.get(c).foreach { case (addr, data) =>
          dut.io.cmd.valid #= true; dut.io.cmd.payload.address #= addr; dut.io.cmd.payload.data #= data & 0xFFFFFFFFL
        }
        cd.waitSampling()
        if (dut.io.resValid.toBoolean && resValidAt < 0) {
          resValidAt = c; realAt = dut.io.real.toBigInt; imagAt = dut.io.imag.toBigInt; signAt = dut.io.resSign.toBoolean
        }
      }

      assert(resValidAt >= 0, s"[pre$prescaleAmp/sat$saturate] res.valid never asserted")
      val (gr, gi) = windowSum(St, dur, amp, freq, phase, base, tToPulse)
      assert(realAt == gr && imagAt == gi,
        s"[pre$prescaleAmp/sat$saturate] integral dut=($realAt,$imagAt) golden=($gr,$gi)")
      assert(signAt == (gr < 0), s"[pre$prescaleAmp/sat$saturate] res sign dut=$signAt golden=${gr < 0}")
      // sharpness: a +1-shifted window differs ⇒ an off-by-one in the demod↔decoder alignment fails here.
      val (sr, _) = windowSum(St + 1, dur, amp, freq, phase, base, tToPulse)
      if (sr != gr) assert(realAt != sr, s"[pre$prescaleAmp/sat$saturate] alignment not sharp: +1-shifted window matched")
      // non-trivial: the integral is clearly non-zero (the test is not vacuous).
      assert(gr.abs + gi.abs > 0, "golden integral is zero — test would be vacuous")

      simSuccess()
    }
    println(s"[DemodDecoderSim] PASS prescaleAmp=$prescaleAmp saturate=$saturate: envelope-shaped demod carrier → " +
      s"bit-exact readout integral (Σ adc·env·carrier over [St,St+dur)) + sharp carrier-triggered window alignment.")
  }

  run(prescaleAmp = true,  saturate = false)  // the SoC config
  run(prescaleAmp = false, saturate = true)
}
