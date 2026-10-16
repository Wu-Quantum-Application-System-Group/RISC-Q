package riscq.dsp.sim

import spinal.core.sim._
import riscq.dsp._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[Cordic]]. Two-tier, like `SinCosSim` / `ComplexMulSim`:
 *
 *   1. bit-exact vs [[Cordic.rotate]] (the integer mirror — same pre-rotation, shifts, α table,
 *      1/K constant, rounding, saturation) — zero tolerance, every cycle;
 *   2. analytic bound vs the ideal real rotation `xy·exp(iπ·value(z))`: `|dut − ideal| ≤
 *      Cordic.errorBound(p, |xy|)` (codes), checked whenever the ideal stays in range (otherwise the
 *      DUT saturates and the comparison is moot). The bound is asserted exactly — no fudge factor.
 *
 * Plus a range assertion (`−Amax ≤ out ≤ Amax`) and the throughput/latency scoreboard: `rsp.valid`
 * is `cmd.valid` delayed by `latency`, payloads emerge in order, II = 1 under full load and gaps.
 * Stimulus is random `(xy, z)` plus named pre-rotation corner angles (`0, ±π/4, ±π/2±lsb, ±π`).
 * Run with `mill runMain riscq.dsp.sim.CordicSim`.
 */
object CordicSim extends App {

  type In = (BigInt, BigInt, BigInt) // (x0, y0, z0)

  def run(p: CordicParams, stim: IndexedSeq[In], label: String): Long = {
    val L    = Cordic.latency(p)
    val amax = p.ampMax
    var verified = 0L
    var maxErr   = 0.0

    def ideal(in: In): (Double, Double) = {
      val (x0, y0, z0) = in
      val theta = z0.toDouble / (1 << (p.zWidth - 1)) * math.Pi
      (x0.toDouble * math.cos(theta) - y0.toDouble * math.sin(theta),
       x0.toDouble * math.sin(theta) + y0.toDouble * math.cos(theta))
    }
    def vecMag(in: In): Double = math.hypot(in._1.toDouble, in._2.toDouble)

    SimConfig.compile(Cordic(p)).doSim(s"$label-xy${p.xyWidth}z${p.zWidth}", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(2)

      val q       = mutable.Queue[In]()
      val validDl = mutable.Queue.fill(L)(false)

      def check(in: In, gotRe: BigInt, gotIm: BigInt): Unit = {
        val (exRe, exIm) = Cordic.rotate(p, in._1, in._2, in._3)
        assert(gotRe == exRe && gotIm == exIm,
          s"[$label] bit-exact mismatch in=$in: dut=($gotRe,$gotIm) golden=($exRe,$exIm)")
        assert(gotRe >= -amax && gotRe <= amax && gotIm >= -amax && gotIm <= amax,
          s"[$label] range in=$in: ($gotRe,$gotIm) outside [${-amax}, $amax]")
        val (idRe, idIm) = ideal(in)
        val bound = Cordic.errorBound(p, vecMag(in))
        if (math.abs(idRe) <= amax.toDouble) {
          val e = math.abs(gotRe.toDouble - idRe)
          assert(e <= bound + 1e-6, f"[$label] re bound in=$in: |$gotRe - $idRe%.3f| = $e%.3f > $bound%.3f")
          maxErr = math.max(maxErr, e)
        }
        if (math.abs(idIm) <= amax.toDouble) {
          val e = math.abs(gotIm.toDouble - idIm)
          assert(e <= bound + 1e-6, f"[$label] im bound in=$in: |$gotIm - $idIm%.3f| = $e%.3f > $bound%.3f")
          maxErr = math.max(maxErr, e)
        }
        verified += 1
      }

      def drive(in: In): Unit = {
        dut.io.cmd.payload.xy.re #= in._1
        dut.io.cmd.payload.xy.im #= in._2
        dut.io.cmd.payload.z     #= in._3
      }
      def step(v: Boolean, in: In): Unit = {
        dut.io.cmd.valid #= v
        drive(in)
        if (v) q.enqueue(in)
        validDl.enqueue(v)
        val expV = validDl.dequeue()
        dut.clockDomain.waitSampling()
        assert(dut.io.rsp.valid.toBoolean == expV,
          s"[$label] rsp.valid delay mismatch: expected $expV (cmd.valid delayed $L)")
        if (expV) check(q.dequeue(), dut.io.rsp.payload.re.toBigInt, dut.io.rsp.payload.im.toBigInt)
      }
      val zero: In = (BigInt(0), BigInt(0), BigInt(0))
      def flush(): Unit = for (_ <- 0 until L) step(false, zero)

      // Pass A — single-pulse latency into an idle pipeline (data-independent valid delay).
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(L + 2)
      dut.io.cmd.valid #= true
      drive(stim.head)
      dut.clockDomain.waitSampling()
      dut.io.cmd.valid #= false
      var d = 0
      while (!dut.io.rsp.valid.toBoolean) { dut.clockDomain.waitSampling(); d += 1 }
      assert(d == L, s"[$label] measured latency $d != declared $L")
      dut.clockDomain.waitSampling(L + 2)

      // Pass B — II = 1, valid every cycle.
      stim.foreach(step(true, _))
      flush()
      assert(q.isEmpty, s"[$label] responses left after full-load pass")

      // Pass C — randomized valid gaps.
      val rng = new Random(1)
      for (_ <- 0 until 4000) step(rng.nextBoolean(), stim(rng.nextInt(stim.length)))
      flush()
      assert(q.isEmpty, s"[$label] responses left after gap pass")

      simSuccess()
    }
    val worstBound = Cordic.errorBound(p, math.sqrt(2.0) * amax.toDouble)
    println(f"[CordicSim] PASS  $label (xy=${p.xyWidth} z=${p.zWidth} iters=${p.iters}): " +
      f"$verified verified, latency=$L/II=1; measured max |err| = $maxErr%.3f codes, " +
      f"bound ≤ $worstBound%.3f codes.")
    verified
  }

  /** corner stimulus: a few `xy` crossed with the pre-rotation boundary angles. */
  def corners(p: CordicParams): IndexedSeq[In] = {
    val a    = p.ampMax
    val half = BigInt(1) << (p.zWidth - 2) // π/2
    val quar = BigInt(1) << (p.zWidth - 3) // π/4
    val maxz = (BigInt(1) << (p.zWidth - 1)) - 1 // +π − lsb
    val minz = -(BigInt(1) << (p.zWidth - 1))    // −π
    val zs   = Seq(BigInt(0), BigInt(1), quar, half - 1, half, half + 1, -half, maxz, minz)
    val xys  = Seq[(BigInt, BigInt)]((a, 0), (0, a), (a, a), (-a, 0), (a / 2, -a / 3), (-a, -a))
    for { (x, y) <- xys.toIndexedSeq; z <- zs } yield (x, y, z)
  }

  def randomStim(p: CordicParams, n: Int, seed: Int): IndexedSeq[In] = {
    val rng  = new Random(seed)
    val a    = p.ampMax.toInt
    val zlo  = -(1 << (p.zWidth - 1))
    val zhi  = (1 << (p.zWidth - 1)) - 1
    IndexedSeq.fill(n)(
      (BigInt(rng.nextInt(2 * a + 1) - a), BigInt(rng.nextInt(2 * a + 1) - a),
       BigInt(rng.nextInt(zhi - zlo + 1) + zlo)))
  }

  val configs = Seq(
    CordicParams(),                                  // default 16/16, iters 17, gain-corrected
    CordicParams(xyWidth = 12, zWidth = 12)          // smaller widths
  )
  for (p <- configs) {
    val stim = corners(p) ++ randomStim(p, 50000, 7)
    run(p, stim, s"cordic")
  }
  println("[CordicSim] all configs PASS")
}
