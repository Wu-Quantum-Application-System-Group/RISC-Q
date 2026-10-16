package riscq.dsp.sim

import spinal.core.sim._
import riscq.dsp._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[SinCos]]. Mirrors the core's lock-step philosophy with a
 * **two-tier golden model**:
 *
 *   1. a bit-exact Scala mirror (same table, same fold, same rounding) the DUT must match every
 *      cycle with zero tolerance — catches addressing / sign / width bugs;
 *   2. a math bound `|dut − Amax·f(P)| ≤ Amax·π·2^-(t+2) + ½` against the real function — catches
 *      errors in the mirror itself and validates the accuracy claim. No fudge factor (only a
 *      1e-6 FP guard).
 *
 * Plus: fixed-latency / II=1 throughput (a delay-line model of `rsp.valid` vs `cmd.valid`, including
 * randomized valid gaps), in-order FIFO payload matching, and a range assertion
 * (`−2^(na-1) < out ≤ 2^(na-1)-1`) on every output. Exhaustive over all 2^phaseWidth phases when
 * phaseWidth ≤ 20. Run with `mill runMain riscq.dsp.sim.SinCosSim`.
 */
object SinCosSim extends App {

  /** Pure-Scala bit-exact mirror + math bound for one parameter set (any method). */
  class Golden(p: SinCosParams) {
    val np    = p.phaseWidth
    val na    = p.ampWidth
    val t     = p.tableIndexBits
    val m     = np - 2
    val amax  = p.ampMax
    val tMask = (1 << t) - 1

    // v1 uses an n_a-bit table; v2 (Taylor) an n_a+gw-bit table read at the coarse midpoint.
    val gw       = p.tableGuardBits
    val isTay    = p.method == SinCosMethod.Taylor
    val isCordic = p.method == SinCosMethod.Cordic
    val cp       = SinCos.cordicParams(p) // CORDIC config (used only when isCordic)
    val table   = SinCos.quarterTable(p, if (isTay) gw else 0) // index 0 .. 2^t-1
    val f       = p.fineBits
    val cmul    = SinCos.taylorPiConst(p)
    val sh      = m + p.piScaleBits          // S/C left-shift before the round
    val rnd     = m + p.piScaleBits + gw     // R: fractional bits rounded off
    val roundC  = BigInt(1) << (rnd - 1)

    /** signed value the SInt phase port carries for the unsigned bit pattern `ph`. */
    def signedPhase(ph: Int): Int = if ((ph & (1 << (np - 1))) != 0) ph - (1 << np) else ph

    /** mirror the hardware fold: coarse table addresses + per-output sign. */
    def decode(ph: Int): (Int, Int, Boolean, Boolean) = {
      val u      = ph & ((1 << np) - 1)
      val q1     = (u >> (np - 1)) & 1
      val q0     = (u >> (np - 2)) & 1
      val idx    = u & ((1 << m) - 1)
      val idxTop = idx >> (m - t)
      val addrSin = if (q0 == 1) (~idxTop) & tMask else idxTop
      val addrCos = if (q0 == 1) idxTop else (~idxTop) & tMask
      (addrSin, addrCos, q1 == 1, (q1 ^ q0) == 1)
    }

    /** bit-exact (sin, cos) the DUT must produce — mirrors the selected datapath exactly. */
    def exact(ph: Int): (BigInt, BigInt) = {
      if (isCordic) {
        // rotate (Amax, 0) by π·value(phase): re = Amax·cos, im = Amax·sin → (sin, cos) = (im, re).
        val (re, im) = Cordic.rotate(cp, amax, BigInt(0), BigInt(signedPhase(ph)))
        return (im, re)
      }
      val (as, ac, ns, nc) = decode(ph)
      val s = if (ns) -table(as) else table(as) // coarse S (·2^gw for Taylor)
      val c = if (nc) -table(ac) else table(ac) // coarse C
      if (!isTay) (s, c)
      else {
        val u        = ph & ((1 << np) - 1)
        val fine     = u & ((1 << f) - 1)
        val deltaInt = BigInt(fine) - (BigInt(1) << (f - 1)) // centered residual
        val deltaRad = deltaInt * cmul                       // δ·2^(m+pb)
        def sat(x: BigInt): BigInt = if (x > amax) amax else if (x < -amax) -amax else x
        val sinOut   = sat(((s << sh) + deltaRad * c + roundC) >> rnd) // round( S + δ·C )
        val cosOut   = sat(((c << sh) - deltaRad * s + roundC) >> rnd) // round( C − δ·S )
        (sinOut, cosOut)
      }
    }

    /** real reference Amax·sin(P), Amax·cos(P). */
    def realRef(ph: Int): (Double, Double) = {
      val pr = signedPhase(ph).toDouble / (1 << (np - 1)) * scala.math.Pi
      (amax.toDouble * scala.math.sin(pr), amax.toDouble * scala.math.cos(pr))
    }

    // Analytic worst-case error bound vs Amax·f(P), in output codes.
    import scala.math.{Pi, pow}
    val boundCodes: Double = p.method match {
      case SinCosMethod.Lut =>
        amax.toDouble * Pi * pow(2, -(t + 2)) + 0.5
      case SinCosMethod.Taylor =>
        val eTaylor = amax.toDouble * Pi * Pi * pow(2, -(2 * t + 5)) // 2nd-order remainder
        val eTableS = pow(2, -(gw + 1))                              // S table quant (½ ulp @ gw)
        val eTableC = Pi * pow(2, -(t + gw + 3))                     // C quant through δ (δmax·½ulp)
        val eDelta  = amax.toDouble * pow(2, -(t + p.piScaleBits + 2)) // π/2 KCM rounding
        eTaylor + eTableS + eTableC + eDelta + 0.5                   // + final round
      case SinCosMethod.Cordic =>
        Cordic.errorBound(cp, amax.toDouble)                         // |xy| = Amax (xy = (Amax, 0))
      case other => sys.error(s"no bound for method $other")
    }
    val ampHi      = amax            // 2^(na-1) - 1
    val ampLo      = -amax           // most-negative code 2^(na-1) must never appear
  }

  /** Compile once, then run every check pass against the DUT. Returns #responses verified. */
  def run(p: SinCosParams): Long = {
    val g    = new Golden(p)
    val L    = SinCos.latency(p)
    val name = s"np=${p.phaseWidth} na=${p.ampWidth} t=${p.tableIndexBits} ${p.method}"
    var verified = 0L

    SimConfig.compile(SinCos(p)).doSim(name, seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(2)

      // expected payloads in flight (FIFO — the pipeline preserves order) and a model of the
      // L-deep valid pipeline, so we can assert rsp.valid is exactly cmd.valid delayed by L.
      val payloadQ = mutable.Queue[(Int, BigInt, BigInt)]()
      val validDl  = mutable.Queue.fill(L)(false)

      def checkResp(ph: Int, gotSin: BigInt, gotCos: BigInt): Unit = {
        val (exSin, exCos) = g.exact(ph)
        assert(gotSin == exSin && gotCos == exCos,
          f"bit-exact mismatch [$name] phase=0x${ph.toHexString}: " +
            f"dut(sin=$gotSin,cos=$gotCos) != golden(sin=$exSin,cos=$exCos)")
        // range: most-negative code never appears, no overflow wrap.
        assert(gotSin >= g.ampLo && gotSin <= g.ampHi && gotCos >= g.ampLo && gotCos <= g.ampHi,
          f"range violation [$name] phase=0x${ph.toHexString}: sin=$gotSin cos=$gotCos not in [${g.ampLo}, ${g.ampHi}]")
        // math bound (tier 2): the mirror itself must track the real function.
        val (rSin, rCos) = g.realRef(ph)
        assert(scala.math.abs(gotSin.toDouble - rSin) <= g.boundCodes + 1e-6,
          f"sin bound exceeded [$name] phase=0x${ph.toHexString}: |$gotSin - $rSin%.3f| > ${g.boundCodes}%.4f")
        assert(scala.math.abs(gotCos.toDouble - rCos) <= g.boundCodes + 1e-6,
          f"cos bound exceeded [$name] phase=0x${ph.toHexString}: |$gotCos - $rCos%.3f| > ${g.boundCodes}%.4f")
        verified += 1
      }

      // Drive a sequence of (valid, phase). Each cycle: model the valid delay line, assert
      // rsp.valid matches, and FIFO-match the payload of every emerging response.
      def driveSteps(steps: Seq[(Boolean, Int)]): Unit = {
        for ((v, ph) <- steps) {
          dut.io.cmd.valid #= v
          dut.io.cmd.payload.phase #= g.signedPhase(ph)
          if (v) payloadQ.enqueue((ph, g.exact(ph)._1, g.exact(ph)._2))
          validDl.enqueue(v)
          val expValid = validDl.dequeue()
          dut.clockDomain.waitSampling()
          assert(dut.io.rsp.valid.toBoolean == expValid,
            s"rsp.valid delay mismatch [$name]: expected $expValid (cmd.valid delayed $L)")
          if (expValid) {
            val (ph0, _, _) = payloadQ.dequeue()
            checkResp(ph0, dut.io.rsp.payload.sin.toBigInt, dut.io.rsp.payload.cos.toBigInt)
          }
        }
      }
      def flush(): Unit = driveSteps(Seq.fill(L)((false, 0)))

      // --- Pass A: explicit latency of a single pulse into an idle pipeline ---
      dut.io.cmd.valid #= false
      dut.clockDomain.waitSampling(8) // drain any startup, empty the pipeline
      dut.io.cmd.valid #= true
      dut.io.cmd.payload.phase #= g.signedPhase(0x1234 & ((1 << p.phaseWidth) - 1))
      dut.clockDomain.waitSampling() // drive cycle
      dut.io.cmd.valid #= false
      var d = 0
      while (!dut.io.rsp.valid.toBoolean) { dut.clockDomain.waitSampling(); d += 1 }
      assert(d == L, s"measured latency $d != declared $L [$name]")
      dut.clockDomain.waitSampling(L + 2) // let the pulse fully drain

      // --- Pass B: exhaustive, II=1 (valid every cycle) over all 2^np phases ---
      val nPhases = 1 << p.phaseWidth
      driveSteps((0 until nPhases).map(ph => (true, ph)))
      flush()
      assert(payloadQ.isEmpty, s"pending responses left after exhaustive sweep [$name]")

      // --- Pass C: randomized valid gaps — pipeline integrity under bubbles ---
      val rng = new Random(1)
      driveSteps((0 until 4000).map(_ => (rng.nextBoolean(), rng.nextInt(nPhases))))
      flush()
      assert(payloadQ.isEmpty, s"pending responses left after gap sweep [$name]")

      // --- Pass D: named corners (also in the exhaustive sweep; asserted for readable failures) ---
      val half = 1 << (p.phaseWidth - 2)                  // +π/2
      val mask = (1 << p.phaseWidth) - 1
      val corners = Seq(
        "0" -> 0, "+1lsb" -> 1, "-1lsb" -> mask,
        "+pi/2" -> half, "+pi/2+1" -> ((half + 1) & mask), "+pi/2-1" -> ((half - 1) & mask),
        "-pi/2" -> ((-half) & mask), "-pi" -> (1 << (p.phaseWidth - 1)), "+pi-1lsb" -> ((1 << (p.phaseWidth - 1)) - 1)
      )
      driveSteps(corners.map { case (_, ph) => (true, ph) })
      flush()

      simSuccess()
    }
    verified
  }

  // v1 (Lut): default + all-bits fold + heavy truncation. v2 (Taylor): faithful default + a smaller
  // faithful one + an n_p=18 headroom config + a deliberately coarse t that exercises the output
  // saturation path. All exhaustive (n_p ≤ 18).
  val configs = Seq(
    SinCosParams(),                                                   // v1 16/16/11 — default
    SinCosParams(phaseWidth = 10, ampWidth = 12, tableIndexBits = 8), // v1 t = m: fold exactness
    SinCosParams(phaseWidth = 12, ampWidth = 16, tableIndexBits = 6), // v1 heavy truncation
    SinCosParams(tableIndexBits = 8, method = SinCosMethod.Taylor),   // v2 16/16/8 — faithful default
    SinCosParams(phaseWidth = 14, ampWidth = 16, tableIndexBits = 8,  // v2 faithful, n_p=14, f=4
      method = SinCosMethod.Taylor),
    SinCosParams(phaseWidth = 18, ampWidth = 16, tableIndexBits = 8,  // v2 n_p=18 headroom
      method = SinCosMethod.Taylor),
    SinCosParams(phaseWidth = 12, ampWidth = 14, tableIndexBits = 5,  // v2 coarse t → saturation path
      method = SinCosMethod.Taylor),
    SinCosParams(method = SinCosMethod.Cordic),                       // CORDIC 16/16
    SinCosParams(phaseWidth = 14, method = SinCosMethod.Cordic)       // CORDIC 14/16
  )
  for (p <- configs) {
    val n = run(p)
    println(s"[SinCosSim] PASS  np=${p.phaseWidth} na=${p.ampWidth} t=${p.tableIndexBits} " +
      s"${p.method}: $n responses verified (bit-exact + bound ${"%.3f".format(new Golden(p).boundCodes)} codes + range + latency=${SinCos.latency(p)}/II=1).")
  }
  println("[SinCosSim] all configs PASS")
}
