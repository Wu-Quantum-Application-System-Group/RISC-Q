package riscq.dsp.pulse.sim

import spinal.core.sim._
import riscq.dsp._
import riscq.dsp.pulse._

import scala.math.{Pi, abs, cos, pow, sin}

/**
 * Self-checking testbench for [[CarrierBatchGenerator]]. Static amp/phase/freq/phasors are driven
 * once and held; for each test time the pipeline settles and `io.carrier[k]` is checked two ways:
 * '''bit-exact''' vs the composed model `ComplexMul(Cordic(amp,0,gPhase), phasor[k])`, and against
 * the '''Double''' reference `amp·exp(iπ·gPhase)·phasor[k]` with a composed bound (CORDIC error
 * through the ComplexMul + ½ ulp).
 *
 * '''Phase coherence''' is exercised by including very large and wrap-straddling time values (and the
 * `t·N` batch-time wrap at `t = 2^w/N`): the truncated time-product phase is exact mod 2π, so the
 * carrier must still match the model after an arbitrary time jump. Run with
 * `mill runMain riscq.dsp.pulse.sim.CarrierBatchGeneratorSim`.
 */
object CarrierBatchGeneratorSim extends App {

  val N         = 8
  val w         = 16
  val timeWidth = 32
  val amax      = (BigInt(1) << (w - 1)) - 1
  val maskW     = BigInt(1) << w
  def signedW(pat: BigInt): BigInt = if (pat.testBit(w - 1)) pat - maskW else pat
  def uMod(x: BigInt, m: BigInt): BigInt = ((x % m) + m) % m

  // static stimulus: amp < Amax, an arbitrary phase/freq, and the phasor batch's frequency.
  val amp: BigInt   = amax / 2 + 1234
  val phase: BigInt = 5000
  val freq: BigInt  = 1234
  val f0: BigInt    = 4096

  /** gPhase(t) = (freq·((t·N) mod 2^w) + phase) mod 2^w, signed — mirrors the hardware exactly. */
  def gPhase(t: BigInt): BigInt = {
    val bt    = signedW((t * N) & (maskW - 1)) // (t·N) mod 2^w
    val tpLow = signedW(uMod(freq * bt, maskW)) // low w bits of freq·batchTime
    signedW(uMod(tpLow + phase, maskW))
  }

  /** Run both gain modes: correctGain = true (default) and false (prescaleAmp). When uncorrected the
   *  carrier CORDIC output carries the K gain (output ≈ K·amp), and the phasor batch is built from the
   *  K-prescaled phasor constant (magnitude ≈ Amax) — both mirrored by `cp` below. */
  def run(correctGain: Boolean): Unit = {
    val cp = CordicParams(xyWidth = w, zWidth = w, correctGain = correctGain)
    val g  = if (correctGain) 1.0 else Cordic.gain(cp.iters) // uncorrected ⇒ output carries the K gain
    val phasorMag: BigInt = if (correctGain) amax else BigInt((amax.toDouble / Cordic.gain(cp.iters)).round)
    val phasor: Array[(BigInt, BigInt)] =
      (0 until N).map(k => Cordic.rotate(cp, phasorMag, BigInt(0), signedW((BigInt(k) * f0) & (maskW - 1)))).toArray

    /** bit-exact carrier[k](t): ComplexMul(Cordic(amp,0,gPhase), phasor[k]). */
    def modelCarrier(t: BigInt, k: Int): (BigInt, BigInt) = {
      val (cr, ci) = Cordic.rotate(cp, amp, BigInt(0), gPhase(t))
      ComplexMul.model(w, cr, ci, phasor(k)._1, phasor(k)._2)
    }

    /** Double reference (g·amp)·exp(iπ·gPhase)·phasor[k] (codes) + composed error bound. */
    def idealCarrier(t: BigInt, k: Int): (Double, Double, Double) = {
      val ang = gPhase(t).toDouble / (1 << (w - 1)) * Pi
      val cr  = g * amp.toDouble * cos(ang)
      val ci  = g * amp.toDouble * sin(ang)
      val pr  = phasor(k)._1.toDouble
      val pi  = phasor(k)._2.toDouble
      val sc  = pow(2, -(w - 1))
      val bound = Cordic.errorBound(cp, abs(g * amp.toDouble)) * (abs(pr) + abs(pi)) * sc + 0.5
      ((cr * pr - ci * pi) * sc, (cr * pi + ci * pr) * sc, bound)
    }

    var maxErr = 0.0
    SimConfig.compile(CarrierBatchGenerator(N, w, timeWidth, correctGain)).doSim(s"carrier_cg$correctGain", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.amp.valid #= false; dut.io.phase.valid #= false
      dut.io.freq.valid #= false; dut.io.phasors.valid #= false
      dut.io.time #= 0
      dut.clockDomain.waitSampling(2)

      // drive the static parameters once (held thereafter).
      dut.io.amp.valid #= true; dut.io.amp.payload #= amp
      dut.io.phase.valid #= true; dut.io.phase.payload #= phase
      dut.io.freq.valid #= true; dut.io.freq.payload #= freq
      dut.io.phasors.valid #= true
      for (k <- 0 until N) {
        dut.io.phasors.payload(k).re #= phasor(k)._1
        dut.io.phasors.payload(k).im #= phasor(k)._2
      }
      dut.clockDomain.waitSampling()
      dut.io.amp.valid #= false; dut.io.phase.valid #= false
      dut.io.freq.valid #= false; dut.io.phasors.valid #= false
      dut.clockDomain.waitSampling(40) // settle the parameter registers

      def checkAtTime(t: BigInt): Unit = {
        dut.io.time #= t
        dut.clockDomain.waitSampling(45) // settle this time through the whole pipeline
        for (k <- 0 until N) {
          val (mre, mim) = modelCarrier(t, k)
          val cre = dut.io.carrier(k).re.toBigInt
          val cim = dut.io.carrier(k).im.toBigInt
          assert(cre == mre && cim == mim,
            s"bit-exact t=$t lane $k: dut($cre,$cim) != model($mre,$mim)")
          val (ire, iim, bound) = idealCarrier(t, k)
          val er = abs(cre.toDouble - ire); val ei = abs(cim.toDouble - iim)
          assert(er <= bound + 1e-6, f"re bound t=$t lane $k: $er%.3f > $bound%.3f")
          assert(ei <= bound + 1e-6, f"im bound t=$t lane $k: $ei%.3f > $bound%.3f")
          maxErr = math.max(maxErr, math.max(er, ei))
        }
      }

      // small steps, the t·N batch-time wrap (t = 2^w/N = 8192), large jumps, and the time wrap.
      val times = Seq[BigInt](0, 1, 2, 3, 17, 255, 256, 1000, 8191, 8192, 8193, 16384,
        65535, 65536, 100000, 1234567,
        (BigInt(1) << 31), (BigInt(1) << timeWidth) - 3, (BigInt(1) << timeWidth) - 1)
      times.foreach(checkAtTime)
      simSuccess()
    }
    println(f"[CarrierBatchGeneratorSim] PASS  N=$N w=$w correctGain=$correctGain: bit-exact + composed " +
      f"bound (max |err| $maxErr%.3f codes) + phase coherence across time jumps/wraps.")
  }

  run(correctGain = true)
  run(correctGain = false)
}
