package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp._
import riscq.dsp.pulse._

/**
 * End-to-end self-checking testbench for the '''carrier-triggered''' [[ReadoutDecoder]]. The demod
 * carrier is driven directly as a `Flow`: `valid` high for exactly the window's batches (payload the
 * carrier codes), `valid` low + payload zero outside — mirroring the real generator, whose output is
 * exactly zero when idle. The golden is the composition of the per-block bit-exact models
 * (`ComplexMul.model` per lane, summed across lanes and across the window).
 *
 * The stream is a tone whose `adc` is `±conj(carrier)`, so the demod is `±|carrier|²` — clean +/-
 * integrals exercising both `res` signs. Checks:
 *
 *   1. `real`/`imag` '''bit-exact''' vs `Σ_{in-window} Σ_k ComplexMul.model(adc, carrier)` (window =
 *      exactly the `dur` valid batches). A +1-shifted-window cross-check confirms the derived alignment
 *      is sharp (an off-by-one would be caught).
 *   2. `res.valid` low during the window, rises after the settle, and clears at the next window's start;
 *      `res.payload == sign(real)`.
 *   3. '''Multiple windows re-integrate''' — three windows, two of them separated by the minimum
 *      1-batch gap — give three distinct correct results (captured at the `res.valid` rising edges).
 *   4. The load-bearing idle-zero invariant: `treeR/treeI === 0` whenever the delayed carrier valid (`inWin`)
 *      is low — checked every idle cycle, so a future rounding change that dirties ±0 fails here.
 *   5. `demodData` valid exactly `dur` cycles per window, payload bit-exact and self-aligned with valid.
 *
 * Run with `mill runMain riscq.dsp.pulse.sim.ReadoutDecoderSim`.
 */
object ReadoutDecoderSim extends App {

  def run(saturate: Boolean): Unit = {
    val p = ReadoutDecoderParams(batchSize = 4, dataWidth = 16, accWidth = 32, maxWinLog2 = 14,
      saturate = saturate)
    val N = p.batchSize
    val w = p.dataWidth
    val amax = (1 << (w - 1)) - 1

    // ── stimulus codes (deterministic tone; adc = ±conj(carrier)) ──
    def clampCode(s: Long): Int = (if (s > amax) amax.toLong else if (s < -amax) -amax.toLong else s).toInt
    def toCode(v: Double): Int  = clampCode(scala.math.round(v * (1 << (w - 1))))
    val freq = 1.0 / 16
    def signFor(c: Int): Int    = if (c < 70) 1 else if (c < 96) -1 else 1  // A:+, B:-, C:+
    def carrierCode(c: Int, k: Int): (Int, Int) = {
      val th = (c.toLong * N + k) * freq
      (toCode(0.5 * scala.math.cos(th)), toCode(0.5 * scala.math.sin(th)))
    }
    def adcCode(c: Int, k: Int): (Int, Int) = {
      val (cr, ci) = carrierCode(c, k); val s = signFor(c)
      (s * cr, -s * ci)                                  // s · conj(carrier), |adc| = |carrier| < 1
    }

    // ── windows (start cycle, dur in batches). B and C share a single idle batch (cycle 96) — the
    //    minimum gap — with opposite signs, so they must produce two distinct results. ──
    val windows = Seq((40, 6), (90, 6), (97, 5))
    def inWindow(c: Int): Boolean = windows.exists { case (st, dur) => c >= st && c < st + dur }
    val totalCycles = 140

    // ── golden model ──
    def demodModel(c: Int, k: Int): (BigInt, BigInt) = {
      val (ar, ai) = adcCode(c, k); val (br, bi) = carrierCode(c, k)
      val v = ComplexMul.model(w, ar, ai, br, bi, saturate)
      if (!saturate) assert(v == ComplexMul.model(w, ar, ai, br, bi, saturate = true),
        s"[no-sat] demod overflowed at c=$c k=$k — headroom too small")
      v
    }
    def add(a: (BigInt, BigInt), b: (BigInt, BigInt)) = (a._1 + b._1, a._2 + b._2)
    def batchSum(c: Int): (BigInt, BigInt) = (0 until N).map(demodModel(c, _)).foldLeft((BigInt(0), BigInt(0)))(add)
    def wrap(x: BigInt): BigInt = {
      val m = BigInt(1) << p.accWidth; val r = ((x % m) + m) % m
      if (r >= (BigInt(1) << (p.accWidth - 1))) r - m else r
    }
    // window = exactly the `dur` valid batches: [st, st+dur).
    def windowSum(st: Int, dur: Int): (BigInt, BigInt) = {
      val s = (st until st + dur).map(batchSum).foldLeft((BigInt(0), BigInt(0)))(add)
      (wrap(s._1), wrap(s._2))
    }

    val compiled = SimConfig.compile {
      val dut = ReadoutDecoder(p)
      dut.treeR.simPublic(); dut.treeI.simPublic(); dut.inWin.simPublic()
      dut
    }
    compiled.doSim(s"readout_sat$saturate", seed = 42) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      dut.io.carrier.valid #= false
      for (k <- 0 until N) {
        dut.io.adc(k).re #= 0; dut.io.adc(k).im #= 0
        dut.io.carrier.payload(k).re #= 0; dut.io.carrier.payload(k).im #= 0
      }
      cd.waitSampling(2)

      val Lmul = dut.mulLatency
      val Ld   = dut.demodLatency
      val resV  = Array.ofDim[Boolean](totalCycles)
      val resP  = Array.ofDim[Boolean](totalCycles)
      val realC = Array.ofDim[BigInt](totalCycles)
      val imagC = Array.ofDim[BigInt](totalCycles)
      val inWinA = Array.ofDim[Boolean](totalCycles)
      val treeRA = Array.ofDim[BigInt](totalCycles)
      val treeIA = Array.ofDim[BigInt](totalCycles)
      val dV    = Array.ofDim[Boolean](totalCycles)
      val dRe   = Array.ofDim[BigInt](totalCycles, N)
      val dIm   = Array.ofDim[BigInt](totalCycles, N)

      for (c <- 0 until totalCycles) {
        val on = inWindow(c)
        dut.io.carrier.valid #= on
        for (k <- 0 until N) {
          val (ar, ai) = adcCode(c, k)                   // ADC is always live
          dut.io.adc(k).re #= ar; dut.io.adc(k).im #= ai
          val (cr, ci) = if (on) carrierCode(c, k) else (0, 0)  // carrier zero when idle
          dut.io.carrier.payload(k).re #= cr; dut.io.carrier.payload(k).im #= ci
        }
        cd.waitSampling()
        resV(c)   = dut.io.res.valid.toBoolean
        resP(c)   = dut.io.res.payload.toBoolean
        realC(c)  = dut.io.real.toBigInt
        imagC(c)  = dut.io.imag.toBigInt
        inWinA(c) = dut.inWin.toBoolean
        treeRA(c) = dut.treeR.toBigInt
        treeIA(c) = dut.treeI.toBigInt
        dV(c)     = dut.io.demodData.valid.toBoolean
        for (k <- 0 until N) {
          dRe(c)(k) = dut.io.demodData.payload(k).re.toBigInt
          dIm(c)(k) = dut.io.demodData.payload(k).im.toBigInt
        }
      }

      // ── (1)+(2)+(3): capture each settled result at the res.valid rising edge, compare bit-exact ──
      val settled = (1 until totalCycles).filter(c => resV(c) && !resV(c - 1))
      assert(settled.length == windows.length,
        s"expected ${windows.length} settled windows, saw ${settled.length} (at ${settled.mkString(",")})")
      for (((st, dur), i) <- windows.zipWithIndex) {
        val c = settled(i)
        val (gr, gi) = windowSum(st, dur)
        assert(realC(c) == gr && imagC(c) == gi,
          s"[sat$saturate win$i @($st,$dur) c=$c] real/imag dut=(${realC(c)},${imagC(c)}) golden=($gr,$gi)")
        assert(resP(c) == (gr < 0), s"[sat$saturate win$i] res.payload dut=${resP(c)} golden=${gr < 0}")
        // sharpness: a +1-shifted window differs ⇒ an off-by-one in the alignment would be caught here.
        val (sr, _) = windowSum(st + 1, dur)
        if (sr != gr) assert(realC(c) != sr, s"[sat$saturate win$i] alignment not sharp: +1-shifted window matched")
      }
      // the three windows are genuinely distinct (+, -, +).
      assert(windowSum(40, 6)._1 > 0 && windowSum(90, 6)._1 < 0 && windowSum(97, 5)._1 > 0,
        "windows should integrate +, -, + (distinct results)")

      // res.valid low mid-integration (window A), and cleared at the next window's start (B opens at 90).
      assert(!resV(45 + Ld), "res.valid should be low mid-integration (A)")
      assert(resV(85 + Ld),  "res.valid should hold A's result before window B opens")
      assert(!resV(93 + Ld), "res.valid should clear at the next window's start (B integrating)")

      // ── (4): idle-zero invariant — treeR/treeI are exactly 0 on every idle (inWin low) cycle, once the
      //    pipeline has flushed its boot state (the first Ldemod cycles reflect pre-stimulus registers). ──
      for (c <- Ld until totalCycles if !inWinA(c)) {
        assert(treeRA(c) == 0 && treeIA(c) == 0,
          s"[sat$saturate] idle-zero invariant broken at idle cycle $c: treeR=${treeRA(c)} treeI=${treeIA(c)} (expected 0)")
      }

      // ── (5): demodData — exactly `dur` valids per window, payload bit-exact and self-aligned ──
      val demodCycles = (0 until totalCycles).filter(dV)
      val expectedDemod = windows.map(_._2).sum
      assert(demodCycles.length == expectedDemod,
        s"demodData valid count ${demodCycles.length} != $expectedDemod")
      for (s <- demodCycles; k <- 0 until N) {
        val c = s - Lmul                                 // demodData payload = demod of batch (cycle − Lmul)
        val (er, ei) = demodModel(c, k)
        assert(dRe(s)(k) == er && dIm(s)(k) == ei,
          s"demodData cycle $s lane $k dut=(${dRe(s)(k)},${dIm(s)(k)}) golden=($er,$ei) (batch c=$c)")
      }
      simSuccess()
    }
    println(s"[ReadoutDecoderSim] PASS saturate=$saturate: 3 carrier-triggered windows (+,-,+; min-gap pair) — " +
      s"bit-exact integral + sign + derived window alignment ([st,+dur)) + idle-zero invariant + demodData trace.")
  }

  run(saturate = false)
  run(saturate = true)
}
