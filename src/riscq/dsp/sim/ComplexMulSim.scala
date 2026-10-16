package riscq.dsp.sim

import spinal.core.sim._
import riscq.dsp._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[ComplexMul]]. Same two-tier philosophy as `SinCosSim`:
 *
 *   1. a bit-exact Scala mirror (same 3-mult Karatsuba, same `+½ ulp` round, same saturation) the
 *      DUT must match every cycle with zero tolerance — catches sign / width / addressing bugs;
 *   2. a math bound: when the exact product is in range it must be round-to-nearest (`≤ ½ ulp`)
 *      of the true value — validates the mirror's rounding constant and shift.
 *
 * Plus a range assertion (`−Amax ≤ out ≤ Amax`, so the most-negative code never appears and no
 * overflow wraps) and the throughput/latency scoreboard from `SinCosSim`: `rsp.valid` is exactly
 * `cmd.valid` delayed by `latency`, payloads emerge in order, II = 1 under full load and under
 * randomized valid gaps. Run with `mill runMain riscq.dsp.sim.ComplexMulSim`.
 */
object ComplexMulSim extends App {

  type In = (BigInt, BigInt, BigInt, BigInt) // (ar, ai, br, bi)

  /** Pure-Scala bit-exact mirror + ideal product for one width. */
  class Golden(w: Int, saturate: Boolean = true) {
    val amax  = (BigInt(1) << (w - 1)) - 1
    val scale = math.pow(2, -(w - 1)) // SF(w) code → real value

    /** bit-exact (re, im) the DUT must produce — the shared [[ComplexMul.model]]. */
    def mul(in: In): (BigInt, BigInt) = ComplexMul.model(w, in._1, in._2, in._3, in._4, saturate)

    /** exact product in output code units (no rounding / saturation) — the tier-2 reference. */
    def ideal(in: In): (Double, Double) = {
      val (ar, ai, br, bi) = in
      // value(a)·value(b) = (a·b codes)·2^-2(w-1); times 2^(w-1) back to output codes = codes·scale
      ((ar * br - ai * bi).toDouble * scale, (ar * bi + ai * br).toDouble * scale)
    }
  }

  /** Compile once for width `w`, drive `stim`, run all checks. Returns #responses verified. With
   *  `saturate = false` the DUT/model truncate instead of clamp; the stimulus must then be unit-disk
   *  (|a|,|b| ≤ Amax) and the check asserts the product did NOT overflow (clamp == truncate). */
  def run(w: Int, stim: IndexedSeq[In], label: String, saturate: Boolean = true): Long = {
    val g = new Golden(w, saturate)
    val L = ComplexMul.latency(saturate)
    var verified = 0L

    SimConfig.compile(ComplexMul(w, saturate)).doSim(s"$label-w$w-sat$saturate", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(2)

      val q       = mutable.Queue[In]()          // payloads in flight (FIFO — pipeline keeps order)
      val validDl = mutable.Queue.fill(L)(false)  // model of the L-deep valid pipeline

      def check(in: In, gotRe: BigInt, gotIm: BigInt): Unit = {
        val (exRe, exIm) = g.mul(in)
        assert(gotRe == exRe && gotIm == exIm,
          s"[$label w=$w] bit-exact mismatch in=$in: dut=($gotRe,$gotIm) golden=($exRe,$exIm)")
        if (!saturate) { // unit-disk contract ⇒ the truncating product must equal the clamping one
          val (cRe, cIm) = ComplexMul.model(w, in._1, in._2, in._3, in._4, saturate = true)
          assert(exRe == cRe && exIm == cIm,
            s"[$label w=$w] no-sat overflow in=$in: truncate=($exRe,$exIm) != clamp=($cRe,$cIm)")
        }
        assert(gotRe >= -g.amax && gotRe <= g.amax && gotIm >= -g.amax && gotIm <= g.amax,
          s"[$label w=$w] range in=$in: ($gotRe,$gotIm) outside [${-g.amax}, ${g.amax}]")
        val (idRe, idIm) = g.ideal(in)
        if (math.abs(idRe) <= g.amax.toDouble)
          assert(math.abs(gotRe.toDouble - idRe) <= 0.5 + 1e-6,
            s"[$label w=$w] re bound in=$in: |$gotRe - $idRe%.3f| > 0.5")
        if (math.abs(idIm) <= g.amax.toDouble)
          assert(math.abs(gotIm.toDouble - idIm) <= 0.5 + 1e-6,
            s"[$label w=$w] im bound in=$in: |$gotIm - $idIm%.3f| > 0.5")
        verified += 1
      }

      def drive(in: In): Unit = {
        dut.io.cmd.payload.a.re #= in._1; dut.io.cmd.payload.a.im #= in._2
        dut.io.cmd.payload.b.re #= in._3; dut.io.cmd.payload.b.im #= in._4
      }

      def step(v: Boolean, in: In): Unit = {
        dut.io.cmd.valid #= v
        drive(in)
        if (v) q.enqueue(in)
        validDl.enqueue(v)
        val expV = validDl.dequeue()
        dut.clockDomain.waitSampling()
        assert(dut.io.rsp.valid.toBoolean == expV,
          s"[$label w=$w] rsp.valid delay mismatch: expected $expV (cmd.valid delayed $L)")
        if (expV) check(q.dequeue(), dut.io.rsp.payload.re.toBigInt, dut.io.rsp.payload.im.toBigInt)
      }

      val zero: In = (BigInt(0), BigInt(0), BigInt(0), BigInt(0))
      def flush(): Unit = for (_ <- 0 until L) step(false, zero)

      // --- Pass A: single-pulse latency into an idle pipeline (data-independent valid delay) ---
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(L + 2)
      dut.io.cmd.valid #= true
      drive(stim.head)
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false
      var d = 0
      while (!dut.io.rsp.valid.toBoolean) { dut.clockDomain.waitSampling(); d += 1 }
      assert(d == L, s"[$label w=$w] measured latency $d != declared $L")
      dut.clockDomain.waitSampling(L + 2) // drain the pulse

      // --- Pass B: II = 1, valid every cycle ---
      stim.foreach(step(true, _))
      flush()
      assert(q.isEmpty, s"[$label w=$w] responses left after full-load pass")

      // --- Pass C: randomized valid gaps — pipeline integrity under bubbles ---
      val rng = new Random(1)
      for (_ <- 0 until 4000) step(rng.nextBoolean(), stim(rng.nextInt(stim.length)))
      flush()
      assert(q.isEmpty, s"[$label w=$w] responses left after gap pass")

      simSuccess()
    }
    verified
  }

  /** all SF(w) signed codes, [−2^(w-1), 2^(w-1)−1]. */
  def codes(w: Int): IndexedSeq[Int] = (-(1 << (w - 1)) until (1 << (w - 1)))

  // --- w = 4: exhaustive over all (a, b) pairs (2^16 = 65536) — full corner/saturation coverage ---
  val w4 = 4
  val exhaustive: IndexedSeq[In] = for {
    ar <- codes(w4); ai <- codes(w4); br <- codes(w4); bi <- codes(w4)
  } yield (BigInt(ar), BigInt(ai), BigInt(br), BigInt(bi))

  // --- w = 16: named corners (incl. the saturation paths) + random ---
  val w16    = 16
  val amax16 = (1 << (w16 - 1)) - 1
  val amin16 = -(1 << (w16 - 1)) // = −1.0 code
  def C(ar: Int, ai: Int, br: Int, bi: Int): In = (BigInt(ar), BigInt(ai), BigInt(br), BigInt(bi))
  val corners16: IndexedSeq[In] = IndexedSeq(
    C(0, 0, 0, 0),
    C(amax16, amax16, amax16, amax16),     // (≈1+i)² ≈ 2i → im near +Amax (no saturation: |re| < 1)
    C(amin16, amin16, amin16, amin16),     // (−1−i)² = 2i → im saturates to +Amax
    C(amin16, amin16, amax16, amax16),     // (−1−i)(≈1+i) ≈ −2i → im saturates to −Amax
    C(amax16, 0, 0, amax16), C(amin16, 0, 0, amax16),
    C(amax16, 0, amax16, 0), C(amin16, 0, amin16, 0),
    C(0, 0, amax16, amin16), C(1, 0, 0, 1), C(0, 1, 0, 1), C(-1, 0, -1, 0),
    C(amin16, 0, amin16, 0)                // (−1)² = +1 → re saturates to +Amax
  )
  val rnd = new Random(7)
  def rcode(): Int = amin16 + rnd.nextInt(amax16 - amin16 + 1)
  val random16: IndexedSeq[In] = IndexedSeq.fill(50000)(C(rcode(), rcode(), rcode(), rcode()))
  val stim16: IndexedSeq[In] = corners16 ++ random16

  // --- w = 16, saturate = false: unit-disk operands (|a|,|b| ≤ Amax) so the truncating product never
  // overflows — validates the simple (no-saturation) ComplexMul at full scale. ---
  def disk(rng: Random): (Int, Int) = {
    val mag = rng.nextDouble() * amax16             // magnitude ∈ [0, Amax]
    val ang = rng.nextDouble() * 2 * math.Pi
    (math.round(mag * math.cos(ang)).toInt, math.round(mag * math.sin(ang)).toInt)
  }
  val half = math.round(amax16 / math.sqrt(2.0)).toInt // |(half,half)| = Amax (on the unit circle)
  val diskCorners: IndexedSeq[In] = IndexedSeq(
    C(0, 0, 0, 0), C(amax16, 0, amax16, 0), C(amax16, 0, 0, amax16), C(0, amax16, 0, amax16),
    C(half, half, half, half), C(half, -half, half, half), C(-half, half, amax16, 0))
  val rnd2 = new Random(11)
  val diskStim: IndexedSeq[In] =
    diskCorners ++ IndexedSeq.fill(50000) { val (ar, ai) = disk(rnd2); val (br, bi) = disk(rnd2); C(ar, ai, br, bi) }

  val n4  = run(w4, exhaustive, "exhaustive")
  val n16 = run(w16, stim16, "random+corners")
  val n16ns = run(w16, diskStim, "nosat-disk", saturate = false)
  println(s"[ComplexMulSim] PASS  w=4 exhaustive: $n4 verified; w=16 corners+random: $n16 verified; " +
    s"w=16 no-sat unit-disk: $n16ns verified (no overflow) " +
    s"(bit-exact + range + ½ulp bound, latency=${ComplexMul.latency(true)}sat/${ComplexMul.latency(false)}nosat, II=1).")
  println("[ComplexMulSim] all configs PASS")
}
