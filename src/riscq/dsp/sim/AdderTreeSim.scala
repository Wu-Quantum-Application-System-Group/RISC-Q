package riscq.dsp.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[AdderTree]].
 *
 *   - '''Pass A — latency:''' a single held input vector into a zeroed tree must reach the expected sum
 *     after exactly `AdderTree.latency(n)` (= `log2Up(n)`) rising edges — this validates the exported
 *     latency against the hardware (the ReadoutDecoder lead-time math depends on it).
 *   - '''Pass B — bit-exact + II = 1:''' streaming random vectors, the output every cycle must equal the
 *     two's-complement-wrapped integer sum of the inputs driven `latency` edges earlier (a depth-`L`
 *     scoreboard), so the tree produces one correct result per cycle.
 *
 * Covers power-of-two and non-power-of-two `n` (the balanced tree carries the odd element through a
 * register at each level, so latency stays uniform), and a single-input degenerate case (latency 0,
 * `reduceBalancedTree` returns the resized input un-registered). Run with
 * `mill runMain riscq.dsp.sim.AdderTreeSim`.
 */
object AdderTreeSim extends App {

  /** Wrap a Component around the `AdderTree` reduction so it can be simulated. */
  case class Dut(n: Int, inW: Int, accW: Int) extends Component {
    val io = new Bundle {
      val inputs = in port Vec.fill(n)(SInt(inW bits))
      val sum    = out port SInt(accW bits)
    }
    io.sum := AdderTree(io.inputs, accW)
  }

  /** Two's-complement fit of `x` into `w` bits (the non-growing `_ + _` inside the tree wraps; the
   *  caller sizes `accW` so the true sum never actually wraps — Pass B asserts the equality). */
  def wrap(x: BigInt, w: Int): BigInt = {
    val m = BigInt(1) << w
    val r = ((x % m) + m) % m
    if (r >= (BigInt(1) << (w - 1))) r - m else r
  }

  def run(n: Int, inW: Int, accW: Int): Long = {
    val L      = AdderTree.latency(n)
    val amaxIn = (BigInt(1) << (inW - 1)) - 1
    val aminIn = -(BigInt(1) << (inW - 1))
    var verified = 0L

    SimConfig.compile(Dut(n, inW, accW)).doSim(s"adderTree-n${n}_in${inW}_acc$accW", seed = 42) { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      def drive(v: Seq[BigInt]): Unit = for (i <- 0 until n) dut.io.inputs(i) #= v(i)
      val zero = Seq.fill(n)(BigInt(0))

      // settle to a zeroed tree (always read the output AFTER a rising edge).
      drive(zero); cd.waitSampling(L + 3)
      assert(dut.io.sum.toBigInt == 0, s"[n=$n] idle tree sum != 0")

      // --- Pass A: latency — a one-step pulse appears at the output exactly L edges later (and only
      //     there). Input driven at relative step s lands at output sample s+L (samples read post-edge). ---
      val pulse    = (0 until n).map(i => BigInt(i + 1)) // distinct, nonzero
      val pulseSum = wrap(pulse.sum, accW)
      val rec = mutable.ArrayBuffer[BigInt]()
      for (s <- 0 until L + 4) {
        drive(if (s == 0) pulse else zero)
        cd.waitSampling()
        rec += dut.io.sum.toBigInt
      }
      assert(rec(L) == pulseSum, s"[n=$n] pulse appeared at sample ${rec.indexOf(pulseSum)} != declared latency $L")
      for (s <- rec.indices if s != L)
        assert(rec(s) == 0, s"[n=$n] spurious nonzero ${rec(s)} at sample $s (pulse should be a single spike at $L)")

      // --- Pass B: streaming random — bit-exact vs the sum driven L edges earlier, every cycle (II = 1).
      //     Same scoreboard convention as ComplexMulSim: a depth-L delay queue, pre-filled (the pipeline
      //     is all-zero now), enqueue-then-dequeue before the edge so `due` is the value from L steps ago. ---
      val rng = new Random(1)
      def rcode(): BigInt = aminIn + BigInt(rng.nextInt((amaxIn - aminIn + 1).toInt))
      val dl = mutable.Queue.fill(L)(BigInt(0))
      for (_ <- 0 until 5000) {
        val v = (0 until n).map(_ => rcode())
        drive(v)
        dl.enqueue(wrap(v.sum, accW))
        val due = dl.dequeue()
        cd.waitSampling()
        assert(dut.io.sum.toBigInt == due,
          s"[n=$n] sum mismatch: dut=${dut.io.sum.toBigInt} expected=$due (inputs driven $L edges ago)")
        verified += 1
      }
      simSuccess()
    }
    verified
  }

  // power-of-two, non-power-of-two, and the degenerate single-input case.
  val configs = Seq((1, 16, 20), (2, 16, 20), (4, 16, 27), (5, 12, 20), (8, 16, 24), (16, 16, 32))
  for ((n, inW, accW) <- configs) {
    val v = run(n, inW, accW)
    println(s"[AdderTreeSim] PASS  n=$n inW=$inW accW=$accW: latency=${AdderTree.latency(n)} verified, " +
      s"$v streamed sums bit-exact (II=1).")
  }
  println("[AdderTreeSim] all configs PASS")
}
