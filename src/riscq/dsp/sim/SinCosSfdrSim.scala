package riscq.dsp.sim

import spinal.core.sim._
import riscq.dsp._

import scala.math.{Pi, log10}

/**
 * Spectral (SFDR) bench for [[SinCos]]. A software phase accumulator sweeps a single tone through
 * the converter; we capture `N = 2^n_p` samples of the analytic signal `cos + j·sin = e^{jP}`, FFT
 * them in Scala, and report the worst spur relative to the carrier (Spurious-Free Dynamic Range, dBc).
 *
 * With tuning word `TW` odd (coprime to `2^n_p`) and `N = 2^n_p`, `phase[n] = n·TW mod 2^n_p`
 * visits every phase code exactly once and the ideal signal is a *pure* tone at FFT bin `TW`
 * (coherent sampling, no leakage). All energy off that bin is converter error, so the spectrum is
 * the exact error transfer function — the truncation/quantization spurs the analog side cares about.
 *
 * Rule of thumb: truncation-limited v1 ≈ −6.02·(phase bits used) dBc; v2 is limited by amplitude
 * quantization instead (≈ 6.02·n_a). Run with
 * `mill runMain riscq.dsp.sim.SinCosSfdrSim`.
 */
object SinCosSfdrSim extends App {

  /** In-place iterative radix-2 DIT FFT (`N` a power of two), `re`/`im` overwritten with the DFT. */
  def fft(re: Array[Double], im: Array[Double]): Unit = {
    val n = re.length
    // bit-reversal permutation
    var j = 0
    for (i <- 1 until n) {
      var bit = n >> 1
      while ((j & bit) != 0) { j ^= bit; bit >>= 1 }
      j |= bit
      if (i < j) {
        val tr = re(i); re(i) = re(j); re(j) = tr
        val ti = im(i); im(i) = im(j); im(j) = ti
      }
    }
    var len = 2
    while (len <= n) {
      val ang = -2 * Pi / len
      val wr  = scala.math.cos(ang)
      val wi  = scala.math.sin(ang)
      var i = 0
      while (i < n) {
        var cr = 1.0
        var ci = 0.0
        var k  = 0
        val half = len / 2
        while (k < half) {
          val a  = i + k
          val b  = a + half
          val tr = cr * re(b) - ci * im(b)
          val ti = cr * im(b) + ci * re(b)
          re(b) = re(a) - tr; im(b) = im(a) - ti
          re(a) = re(a) + tr; im(a) = im(a) + ti
          val ncr = cr * wr - ci * wi
          ci = cr * wi + ci * wr
          cr = ncr
          k += 1
        }
        i += len
      }
      len <<= 1
    }
  }

  /** signed value the SInt phase port carries for an unsigned phase code `ph`. */
  def signedPhase(ph: Int, np: Int): Int = if ((ph & (1 << (np - 1))) != 0) ph - (1 << np) else ph

  /** Drive a full coherent sweep through `p`, FFT, and return (SFDR dBc, carrierBin, worstBin). */
  def measure(p: SinCosParams): (Double, Int, Int) = {
    val np = p.phaseWidth
    val n  = 1 << np
    val tw = ((n / 8) - 1) | 1 // odd ⇒ coprime to 2^n_p; carrier ≈ N/8 (clear of DC and Nyquist)
    val L  = SinCos.latency(p)
    val re = new Array[Double](n) // cos[n]
    val im = new Array[Double](n) // sin[n]

    SimConfig.compile(SinCos(p)).doSim(s"sfdr_np${np}_t${p.tableIndexBits}_${p.method}", seed = 1) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(2)
      dut.io.cmd.valid #= true
      for (i <- 0 until n + L) {
        if (i < n) {
          val ph = ((i.toLong * tw) % n).toInt
          dut.io.cmd.payload.phase #= signedPhase(ph, np)
        } else dut.io.cmd.valid #= false
        dut.clockDomain.waitSampling()
        if (i >= L) {
          val k = i - L
          re(k) = dut.io.rsp.payload.cos.toBigInt.toDouble
          im(k) = dut.io.rsp.payload.sin.toBigInt.toDouble
        }
      }
    }

    fft(re, im)
    val carrier = tw
    val peak    = scala.math.hypot(re(carrier), im(carrier))
    var worst   = 0.0
    var worstK  = -1
    var k = 0
    while (k < n) {
      if (k != carrier) {
        val mg = scala.math.hypot(re(k), im(k))
        if (mg > worst) { worst = mg; worstK = k }
      }
      k += 1
    }
    (20 * log10(peak / worst), carrier, worstK)
  }

  // (config, floor dBc). The v1 floor is a conservative bound set by the phase-truncation rule
  // (−6.02·(t+2)). The v2/CORDIC floor is the DDS-compiler acceptance spec, 96 dBc; those datapaths
  // are amplitude/Taylor-limited. Note the full-permutation coherent sweep adds processing gain a
  // real single-tone DDS run does not see, so 96 dBc is the threshold that actually matters.
  val cases = Seq(
    (SinCosParams(), 60.0),                                                          // v1 16/16/11
    (SinCosParams(tableIndexBits = 8, method = SinCosMethod.Taylor), 96.0),          // v2 16/16/8
    (SinCosParams(phaseWidth = 18, ampWidth = 16, tableIndexBits = 8,                // v2 18/16/8
      method = SinCosMethod.Taylor), 96.0),
    (SinCosParams(method = SinCosMethod.Cordic), 96.0)                               // CORDIC 16/16
  )

  for ((p, floor) <- cases) {
    val (sfdr, carrier, worstK) = measure(p)
    val tag = f"np=${p.phaseWidth} na=${p.ampWidth} t=${p.tableIndexBits} ${p.method}"
    println(f"[SinCosSfdrSim] $tag%-34s SFDR = $sfdr%6.2f dBc  (carrier bin $carrier, worst spur bin $worstK; floor $floor%.0f)")
    assert(sfdr >= floor, f"SFDR $sfdr%.2f dBc below floor $floor%.2f for $tag")
  }
  println("[SinCosSfdrSim] all configs PASS")
}
