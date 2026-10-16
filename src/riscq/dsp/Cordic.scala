package riscq.dsp

import spinal.core._
import spinal.lib._

/**
 * Rotation-mode CORDIC rotator: rotates the complex `xy` by the angle `π·value(z)` and gain-corrects,
 * so `rsp ≈ xy · exp(iπ·value(z))`. Amplitude rides the `xy` input for free (rotating `(amp, 0)` gives
 * `amp·exp(iπz)` — why CORDIC fits the pulse generator and a LUT would not). Number format SF(n):
 * `xy.re/im` are SF(xyWidth), `z` is SF(zWidth) with angle `= value(z)·π ∈ [-π, π)` (plain
 * two's-complement wrap = phase wrap).
 *
 * Algorithm (rotation mode):
 *
 *  1. '''π pre-rotation.''' CORDIC converges only for `|angle| ≲ 99.9°`. When the top two bits of
 *     `z` differ (`|value| > ½`, i.e. `|angle| > π/2`): negate `xy` (rotate by π) and flip `z`'s MSB
 *     (`= ∓1.0 = ∓π`). Exact (no adder), leaving `|angle| ≤ π/2`.
 *  2. '''Iterations.''' `iters` stages `i = 0..iters-1`, one register each. With `d = sign(z)`
 *     (`+1` when `z ≥ 0`): `x' = x − d·(y>>i)`, `y' = y + d·(x>>i)`, `z' = z − d·α(i)`, where
 *     `α(i) = round(atan(2^-i)/π · 2^(zInternal-1))`.
 *  3. '''Gain correction.''' Multiply x, y by `1/K` (`K = Π√(1+2^-2i) ≈ 1.6467602`) as a constant
 *     multiplier in fabric (`use_dsp=no`, 0 DSP), folding that round into the output round + saturate.
 *
 * Precision: the xy datapath carries `xyIntGuard` integer guard bits (`K·√2 < 4 ⇒ 2`) + `g` fractional
 * guard bits and rounds once; the z datapath carries `zg` guard bits so the α-rounding errors stay
 * sub-ulp. The analytic worst-case bound ([[Cordic.errorBound]], ≤ 2 ulp target) is asserted exactly
 * in sim.
 *
 * `Flow` in/out, II = 1, `latency = 1 (load+pre-rot) + iters + gainStage + outRound`.
 */
case class CordicParams(
    xyWidth: Int = 16,        // input/output Complex SF width
    zWidth: Int = 16,         // input angle SF width (angle = value·π)
    nIter: Int = -1,          // CORDIC iterations; <0 ⇒ xyWidth + 1 (general-rotation bound < 2 ulp)
    xyGuardBits: Int = -1,    // g: fractional guard bits in the xy datapath; <0 ⇒ log2Up(iters) + 3
    zGuardBits: Int = -1,     // fractional guard bits in the z datapath; <0 ⇒ log2Up(iters) + 3
    xyIntGuard: Int = 2,      // integer guard bits (gain growth K·√2 ≈ 2.33 < 2^2)
    gainScaleBits: Int = 20,  // kf: fractional bits of the 1/K KCM constant
    correctGain: Boolean = true,
    saturate: Boolean = true, // clamp the output to ±Amax. false ⇒ bare truncate (caller must keep the
                              // input magnitude below full-scale so the result fits — simpler HW)
    resetValid: Boolean = true // reset the `latency`-deep rsp.valid shift register. true ⇒ valid is a
                              // well-defined `false` from reset (the standalone Flow contract). false ⇒
                              // reset-free chain (off the global reset net, SRL-inferable) — only for a
                              // parent whose rsp.valid consumer is itself reset-gated
) {
  require(xyWidth >= 2, "xy needs a sign bit + a magnitude bit")
  require(zWidth >= 3, "z needs a sign bit + two quadrant bits for the π pre-rotation")
  require(xyIntGuard >= 1, "need ≥1 integer guard bit for the CORDIC gain growth")
  require(gainScaleBits >= 1, "1/K needs a positive scale")

  def iters: Int = if (nIter > 0) nIter else xyWidth + 1
  def g: Int     = if (xyGuardBits >= 0) xyGuardBits else log2Up(iters) + 3
  def zg: Int    = if (zGuardBits >= 0) zGuardBits else log2Up(iters) + 3

  /** internal xy width: sign + `xyIntGuard` integer guard + `(xyWidth-1)+g` fractional. */
  def xyInternal: Int = xyWidth + xyIntGuard + g
  /** internal z width: `value = code·2^-(zInternal-1)`, i.e. `zg` extra fractional bits. */
  def zInternal: Int  = zWidth + zg
  /** largest output magnitude, `Amax = 2^(xyWidth-1) − 1`. */
  def ampMax: BigInt  = (BigInt(1) << (xyWidth - 1)) - 1
}

case class CordicCmd(p: CordicParams) extends Bundle {
  val xy = Complex(p.xyWidth)
  val z  = SInt(p.zWidth bits) // angle = value(z)·π
}

case class Cordic(p: CordicParams) extends Component {
  val io = new Bundle {
    val cmd = slave(Flow(CordicCmd(p)))      // no backpressure — II = 1
    val rsp = master(Flow(Complex(p.xyWidth))) // rsp.valid is cmd.valid delayed by `latency`
  }

  val g      = p.g
  val zg     = p.zg
  val xyW    = p.xyInternal
  val zW     = p.zInternal
  val alphaC = Cordic.alpha(p).map(a => S(a, zW bits)) // atan(2^-i)/π in z's internal SF

  /** Narrow a shifted value to SF(xyWidth): clamp to `[−Amax, +Amax]` when `p.saturate`, else a bare
   *  two's-complement truncate (`p.saturate = false` ⇒ the caller guarantees the value already fits, so
   *  no clamp logic is built — the simpler/faster path). The clamp tests both rails as parallel flags
   *  (not serial), with a `max_fanout` cap so the select replicates next to the output muxes. */
  def narrow(r: SInt): SInt =
    if (!p.saturate) r.resize(p.xyWidth bits)
    else {
      val hi    = S(p.ampMax, r.getWidth bits)
      val lo    = S(-p.ampMax, r.getWidth bits)
      val ovfHi = r > hi
      val ovfLo = r < lo
      ovfHi.addAttribute("max_fanout", 6) // replicate the clamp-select next to the xyWidth output muxes
      ovfLo.addAttribute("max_fanout", 6) // (otherwise this select net is fanout/route-bound)
      (ovfHi ? hi | (ovfLo ? lo | r)).resize(p.xyWidth bits)
    }
  /** Round-to-nearest by `sh` (a `+½ ulp` add then arithmetic `>>sh`), then [[narrow]] (gain path). */
  def satRound(v: SInt, sh: Int): SInt = narrow((v +^ S(BigInt(1) << (sh - 1), sh + 1 bits)) >> sh)
  /** Truncate by `sh` (arithmetic `>>sh`, the round already folded upstream), then [[narrow]]. */
  def satTrunc(v: SInt, sh: Int): SInt = narrow(v >> sh)

  // ── stage-in: load to internal width (g/zg extra fractional bits) + π pre-rotation ──
  val zin   = io.cmd.payload.z
  val pre   = zin(p.zWidth - 1) ^ zin(p.zWidth - 2)            // top two bits differ ⇒ |angle| > π/2
  val xLoad = (io.cmd.payload.xy.re << g).resize(xyW bits)
  val yLoad = (io.cmd.payload.xy.im << g).resize(xyW bits)
  val zLoad = (zin << zg).resize(zW bits)
  val zFlip = (zLoad.asBits ^ B(BigInt(1) << (zW - 1), zW bits)).asSInt // ∓1.0 = ∓π

  val X = Array.fill(p.iters + 1)(Reg(SInt(xyW bits)))
  val Y = Array.fill(p.iters + 1)(Reg(SInt(xyW bits)))
  val Z = Array.fill(p.iters + 1)(Reg(SInt(zW bits)))
  X(0) := pre ? -xLoad | xLoad
  Y(0) := pre ? -yLoad | yLoad
  Z(0) := pre ? zFlip | zLoad

  // ── iterations: one register stage each, II = 1 ──
  // When the gain stage is dropped (!correctGain) the output rounds by an arithmetic `>>g`. Fold that
  // `+½ ulp` into the LAST micro-rotation's add (which has ample slack) so the output stage becomes a
  // bare truncate + saturate — no adder on the CORDIC's critical output path. Bit-identical to rounding
  // at the output: `trunc(Y + ½ulp, g) = round(Y, g)`, so the integer model is unchanged.
  val roundFold = !p.correctGain
  val rBias     = if (roundFold) S(BigInt(1) << (g - 1), xyW bits) else S(0, xyW bits)
  for (i <- 0 until p.iters) {
    val dGeq = !Z(i).msb // z ≥ 0 ⇒ rotate by +atan(2^-i)
    val xs   = X(i) >> i // arithmetic (floor) shift — the truncation the guard bits absorb
    val ys   = Y(i) >> i
    val bias = if (i == p.iters - 1) rBias else S(0, xyW bits) // 0 except the folded final rotation
    when(dGeq) {
      X(i + 1) := X(i) - ys + bias
      Y(i + 1) := Y(i) + xs + bias
      Z(i + 1) := Z(i) - alphaC(i)
    } otherwise {
      X(i + 1) := X(i) + ys + bias
      Y(i + 1) := Y(i) - xs + bias
      Z(i + 1) := Z(i) + alphaC(i)
    }
  }

  // ── gain correction (1/K KCM) + output round/saturate ──
  val xn = X(p.iters)
  val yn = Y(p.iters)
  val (reOut, imOut) =
    if (p.correctGain) {
      val kinv  = S(Cordic.kInv(p), Cordic.kInvWidth(p) bits)
      val xMul  = RegNext(xn * kinv); xMul.addAttribute("use_dsp", "no") // fabric constant multiply (0 DSP)
      val yMul  = RegNext(yn * kinv); yMul.addAttribute("use_dsp", "no")
      (RegNext(satRound(xMul, p.gainScaleBits + g)), RegNext(satRound(yMul, p.gainScaleBits + g)))
    } else {
      // input pre-scaled by 1/K (the K gain restores it); the round is folded into the last rotation,
      // so the output stage is a bare truncate (drop the g guard bits) + saturate.
      (RegNext(satTrunc(xn, g)), RegNext(satTrunc(yn, g)))
    }
  io.rsp.payload.re := reOut
  io.rsp.payload.im := imOut
  // rsp.valid is cmd.valid delayed by `latency`. With `resetValid` (default) the shift register resets to
  // false — the standalone Flow contract the unit tests assert. With `resetValid = false` it is reset-free:
  // this `latency`-deep chain is the dominant No-CE async-reset chain when the core is replicated (≈19 FFs
  // × every CORDIC instance, all on one global reset), so dropping the reset (a) leaves the global reset
  // net and (b) infers a single SRL16E/SRL32E instead of `latency` discrete FFs. A parent only opts out
  // when its rsp.valid consumer is itself reset-gated (so a boot value is never acted on) — e.g.
  // PhasorBatchGenerator gates `collect` behind the reset-bearing `since`/`collectCnt` counters.
  io.rsp.valid      := (if (p.resetValid) Delay(io.cmd.valid, Cordic.latency(p), init = False)
                        else              Delay(io.cmd.valid, Cordic.latency(p)))

  /** cmd→rsp pipeline latency. */
  def latency: Int = Cordic.latency(p)
}

object Cordic {

  /** CORDIC gain `K = Π_{i=0}^{iters-1} √(1 + 2^-2i)`. */
  def gain(iters: Int): Double = (0 until iters).map(i => scala.math.sqrt(1 + scala.math.pow(2, -2 * i))).product

  /** atan table in z's internal SF: `α(i) = round(atan(2^-i)/π · 2^(zInternal-1))`. */
  def alpha(p: CordicParams): Seq[BigInt] = {
    val scale = (BigInt(1) << (p.zInternal - 1)).toDouble
    (0 until p.iters).map(i => BigInt(scala.math.round(scala.math.atan(scala.math.pow(2, -i)) / scala.math.Pi * scale)))
  }

  /** `1/K` as a fixed-point constant: `round((1/K)·2^gainScaleBits)`. */
  def kInv(p: CordicParams): BigInt =
    BigInt(scala.math.round((1.0 / gain(p.iters)) * (BigInt(1) << p.gainScaleBits).toDouble))

  def kInvWidth(p: CordicParams): Int = kInv(p).bitLength + 1

  /** `latency = 1 (load+pre-rot) + iters + (gain ? 1) + 1 (output round)`. */
  def latency(p: CordicParams): Int = 1 + p.iters + (if (p.correctGain) 1 else 0) + 1

  /** flip the MSB of a `width`-bit two's-complement value (= ∓2^(width-1) = the π pre-rotation). */
  private def flipMsb(v: BigInt, width: Int): BigInt = {
    val f = (v & ((BigInt(1) << width) - 1)) ^ (BigInt(1) << (width - 1))
    if (f.testBit(width - 1)) f - (BigInt(1) << width) else f
  }

  /**
   * Bit-exact integer model — mirrors the hardware exactly (same pre-rotation, shifts, α table,
   * 1/K constant, rounding, saturation). Inputs/outputs are SF code integers. The hardware never
   * wraps for in-range inputs (the integer guard bits cover `K·√2 < 4` and z stays `< Σα ≈ 0.555`),
   * so this unbounded-`BigInt` model matches it cycle-for-cycle.
   */
  def rotate(p: CordicParams, x0: BigInt, y0: BigInt, z0: BigInt): (BigInt, BigInt) = {
    val g  = p.g
    val zg = p.zg
    val zW = p.zInternal
    val zu = z0 & ((BigInt(1) << p.zWidth) - 1)
    val pre = ((zu >> (p.zWidth - 1)) & 1) != ((zu >> (p.zWidth - 2)) & 1)

    var x = x0 << g
    var y = y0 << g
    var z = z0 << zg
    if (pre) { x = -x; y = -y; z = flipMsb(z, zW) }

    val a = alpha(p)
    for (i <- 0 until p.iters) {
      val d  = if (z >= 0) BigInt(1) else BigInt(-1)
      val xs = x >> i
      val ys = y >> i
      val nx = x - d * ys
      val ny = y + d * xs
      z = z - d * a(i)
      x = nx; y = ny
    }

    val amax = p.ampMax
    // mirror [[narrow]]: clamp to ±Amax when saturating, else a bare two's-complement wrap to xyWidth
    // (matches the HW `resize`; the caller keeps inputs small enough that no wrap actually occurs).
    def wrap(v: BigInt): BigInt = {
      val m = BigInt(1) << p.xyWidth
      val r = ((v % m) + m) % m
      if (r >= (BigInt(1) << (p.xyWidth - 1))) r - m else r
    }
    def fit(v: BigInt): BigInt = if (p.saturate) (if (v > amax) amax else if (v < -amax) -amax else v) else wrap(v)
    def round(v: BigInt, sh: Int): BigInt = fit((v + (BigInt(1) << (sh - 1))) >> sh)
    if (p.correctGain) {
      val k = kInv(p); val sh = p.gainScaleBits + g
      (round(x * k, sh), round(y * k, sh))
    } else {
      (round(x, g), round(y, g))
    }
  }

  /**
   * Analytic worst-case output error (codes) vs the ideal `xy·exp(iπz)`, for a vector of magnitude
   * `vecMag` codes. Sum of: angle residual after `iters` micro-rotations, the α-quantization walk,
   * the per-iteration xy shift-truncation (×2 floor safety, ×gain), the 1/K constant rounding, and
   * the final round. Target ≤ 2 ulp; asserted exactly in sim (no fudge factor).
   */
  def errorBound(p: CordicParams, vecMag: Double): Double = {
    val k = gain(p.iters)
    val angle     = scala.math.atan(scala.math.pow(2, -(p.iters - 1))) * vecMag
    val alphaWalk = p.iters * 0.5 * scala.math.pow(2, -(p.zInternal - 1)) * scala.math.Pi * vecMag
    val trunc     = 2.0 * p.iters * scala.math.pow(2, -p.g) * k
    val gainConst = if (p.correctGain) k * vecMag * 0.5 * scala.math.pow(2, -p.gainScaleBits) else 0.0
    angle + alphaWalk + trunc + gainConst + 0.5
  }
}
