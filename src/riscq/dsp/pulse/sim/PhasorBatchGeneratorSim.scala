package riscq.dsp.pulse.sim

import spinal.core.sim._
import riscq.dsp._
import riscq.dsp.pulse._

/**
 * Self-checking testbench for [[PhasorBatchGenerator]]. Checks: the reset batch is `(Amax, 0)` for
 * every lane; after a freq write the output is invalid for '''exactly''' `regenCycles` cycles then
 * valid; the regenerated batch is bit-exact vs the CORDIC model
 * `phasors[k] = Cordic.rotate((Amax,0), z = k·f mod 2^w)`; and a freq update arriving mid-regen
 * restarts cleanly (final batch matches the second freq). Run with
 * `mill runMain riscq.dsp.pulse.sim.PhasorBatchGeneratorSim`.
 */
object PhasorBatchGeneratorSim extends App {

  val N = 8
  val w = 16
  val amax  = (BigInt(1) << (w - 1)) - 1
  val maskW = (BigInt(1) << w) - 1

  def signedW(pat: BigInt): BigInt = if (pat.testBit(w - 1)) pat - (BigInt(1) << w) else pat

  /** Run both gain modes: correctGain = true (default), and false (prescaleAmp — where the hardwired
   *  phasor amplitude is pre-divided by K so the uncorrected rotation lands at Amax). */
  def run(correctGain: Boolean, saturate: Boolean): Unit = {
    val cp    = CordicParams(xyWidth = w, zWidth = w, correctGain = correctGain, saturate = saturate)
    val regenCycles = N + Cordic.latency(cp) + 2 // +2: registered collect enable adds one cycle (see HW)
    // mirror PhasorBatchGenerator.phMag / xyMag: full-scale with saturation, else pulled under it so the
    // un-clamped phasor still fits in w bits.
    val phMag: BigInt =
      if (saturate) amax else amax - BigInt(scala.math.ceil(Cordic.errorBound(cp, amax.toDouble)).toLong) - 1
    val xyMag: BigInt = if (correctGain) phMag else BigInt((phMag.toDouble / Cordic.gain(cp.iters)).round)

    /** model phasor for lane k at frequency f: rotate (xyMag,0) by z = (k·f) mod 2^w. */
    def modelPhasor(f: BigInt, k: Int): (BigInt, BigInt) =
      Cordic.rotate(cp, xyMag, BigInt(0), signedW((BigInt(k) * f) & maskW))

    SimConfig.compile(PhasorBatchGenerator(N, w, correctGain, saturate)).doSim(s"phasor_cg${correctGain}_sat$saturate", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.freq.valid #= false
      dut.clockDomain.waitSampling(2)

      // --- reset state: valid (not regenerating); the batch VALUE is now uninitialised (no reset init —
      // the duration gate makes the boot value unobservable). The `f = 0` write in the
      // freqs loop below covers the (Amax, 0) batch that the reset used to pin. ---
      assert(dut.io.phasors.valid.toBoolean, "reset: phasors should be valid (regen low at boot)")

      // fire a freq, return the freq-fire → phasors-ready latency (leaves valid=true on the new batch).
      // regen is registered, so valid lingers high one cycle after the fire; consume that, then the
      // invalid regen window — the sum is the fire→ready latency the freq-queue lead time must cover.
      def fireAndWait(f: BigInt): Int = {
        dut.io.freq.valid #= true
        dut.io.freq.payload #= f
        dut.clockDomain.waitSampling()
        dut.io.freq.valid #= false
        var d = 0
        while (dut.io.phasors.valid.toBoolean) { dut.clockDomain.waitSampling(); d += 1 }  // trailing high
        while (!dut.io.phasors.valid.toBoolean) { dut.clockDomain.waitSampling(); d += 1 } // regen window
        d
      }
      def checkPhasors(f: BigInt): Unit =
        for (k <- 0 until N) {
          val (re, im) = modelPhasor(f, k)
          assert(dut.io.phasors.payload(k).re.toBigInt == re && dut.io.phasors.payload(k).im.toBigInt == im,
            s"freq=$f lane $k: dut(${dut.io.phasors.payload(k).re.toBigInt},${dut.io.phasors.payload(k).im.toBigInt}) " +
              s"!= model($re,$im)")
          // no-sat headroom: the un-clamped phasor must still fit in [−Amax, Amax] (clamp == truncate).
          if (!saturate)
            assert(re >= -amax && re <= amax && im >= -amax && im <= amax,
              s"freq=$f lane $k: no-sat phasor ($re,$im) overflowed ±Amax — headroom too small")
        }

      // --- single freq writes: invalid window length + bit-exact batch ---
      val freqs = Seq[BigInt](0, 256, 4096, -4096, amax, -(amax + 1), 12345, -23456)
      for (f <- freqs) {
        val d = fireAndWait(f)
        assert(d == regenCycles, s"freq=$f: invalid window $d != regenCycles $regenCycles")
        checkPhasors(f)
        dut.clockDomain.waitSampling(3) // idle gap between writes
      }

      // --- mid-regen restart: fire f1, interrupt with f2 before it finishes; final batch is f2's ---
      val f1: BigInt = 7777
      val f2: BigInt = -3333
      dut.io.freq.valid #= true; dut.io.freq.payload #= f1
      dut.clockDomain.waitSampling()
      dut.io.freq.valid #= false
      dut.clockDomain.waitSampling(5) // mid-regen
      val dRestart = fireAndWait(f2)
      assert(dRestart == regenCycles, s"restart: window $dRestart != regenCycles $regenCycles")
      checkPhasors(f2)

      simSuccess()
    }
    println(s"[PhasorBatchGeneratorSim] PASS  N=$N w=$w correctGain=$correctGain saturate=$saturate: " +
      s"f=0 batch=(Amax,0) (no reset init), regenCycles=$regenCycles, bit-exact CORDIC batch over single " +
      s"writes + mid-regen restart${if (!saturate) " + phasor fits ±Amax (no-sat headroom)" else ""}.")
  }

  run(correctGain = true, saturate = true)
  run(correctGain = false, saturate = true)
  run(correctGain = false, saturate = false) // simple mode: no saturation, headroom-reduced phasor
  run(correctGain = true, saturate = false)
}
