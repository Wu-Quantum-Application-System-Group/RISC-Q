package riscq.dsp

import spinal.core._
import spinal.lib._

/**
 * Phase-to-amplitude converter: one `(cos, sin)` pair per clock, initiation interval 1.
 * Number format is signed fixed-point **SF(n)**: an `SInt(n bits)` value `x` denotes the real
 * `x · 2^-(n-1) ∈ [-1, +1)`.
 *
 *  - phase  `SInt(phaseWidth bits)` encodes the angle `P = value(x) · π ∈ [-π, +π)`;
 *    plain two's-complement overflow is an exact phase wrap (`+π-ε → -π`).
 *  - sin/cos `SInt(ampWidth bits)` carry the *scaled* waveform `A · sin(P)`, with
 *    `A = (2^(na-1) − 1) · 2^-(na-1) = 1 − 2^-(na-1)` (since +1.0 is not representable).
 *    The amplitude factor `A` is a pure gain — the golden model must include it.
 *
 * One [[SinCos]] `Component`, one [[SinCosParams]], the algorithm picked by `method` so the
 * testbench and generate targets are shared. v1 ([[SinCosMethod.Lut]]) lives in `SinCosLut.scala`.
 */

/** Algorithm selector (elaboration-time, not hardware). */
sealed trait SinCosMethod
object SinCosMethod {
  case object Lut          extends SinCosMethod // v1: quarter-wave folded LUT
  case object Taylor       extends SinCosMethod // v2: v1 + 1st-order Taylor correction
  case object Cordic       extends SinCosMethod // rotation-mode CORDIC ([[Cordic]]) driven by (Amax,0)
  case object Multipartite extends SinCosMethod // v3: multipartite tables (multiplierless)
}

case class SinCosParams(
    phaseWidth: Int = 16,                        // n_p
    ampWidth: Int = 16,                          // n_a
    tableIndexBits: Int = 11,                    // t: quarter-table address bits (≤ phaseWidth-2)
    method: SinCosMethod = SinCosMethod.Lut,
    tableGuardBits: Int = 2,                     // gw: extra fractional bits stored per table entry (v2)
    piScaleBits: Int = 10                        // Pb: fractional bits of the π/2 KCM constant (v2)
) {
  require(phaseWidth >= 3, "need at least a sign bit + a quadrant bit + one index bit")
  require(ampWidth >= 2, "amplitude needs a sign bit + a magnitude bit")
  require(tableIndexBits >= 1, "the quarter-wave table needs at least one index bit")
  require(
    tableIndexBits <= phaseWidth - 2,
    s"tableIndexBits ($tableIndexBits) must fit in the quarter index (phaseWidth-2 = ${phaseWidth - 2})"
  )
  // v2 (Taylor) splits the quarter index into coarse `t` + fine `f`; it needs ≥1 fine bit to
  // correct, plus table guard bits and a π/2 constant for the KCM.
  if (method == SinCosMethod.Taylor) {
    require(fineBits >= 1,
      s"Taylor needs ≥1 fine bit: tableIndexBits ($tableIndexBits) must be ≤ phaseWidth-3 = ${phaseWidth - 3}")
    require(tableGuardBits >= 1, "Taylor needs ≥1 table guard bit to stay under 1 ulp")
    require(piScaleBits >= 1, "Taylor needs a positive π/2 scale")
  }

  /** bits of the quarter-wave index `idx` carried by the phase (`m = n_p − 2`). */
  def quarterIndexBits: Int = phaseWidth - 2

  /** fine residual bits below the coarse table index (`f = m − t`); v2 only. */
  def fineBits: Int = quarterIndexBits - tableIndexBits

  /** largest table magnitude, `Amax = 2^(n_a-1) − 1` — the scaled amplitude in integer codes. */
  def ampMax: BigInt = (BigInt(1) << (ampWidth - 1)) - 1
}

case class SinCosCmd(p: SinCosParams) extends Bundle {
  val phase = SInt(p.phaseWidth bits)
}
case class SinCosRsp(p: SinCosParams) extends Bundle {
  val sin = SInt(p.ampWidth bits)
  val cos = SInt(p.ampWidth bits)
}

/** Quarter-wave table addressing + per-output sign, derived from the phase (combinational). */
case class QuadrantDecode(p: SinCosParams) extends Bundle {
  val addrSin = UInt(p.tableIndexBits bits)
  val addrCos = UInt(p.tableIndexBits bits)
  val negSin  = Bool()
  val negCos  = Bool()
}

case class SinCos(p: SinCosParams) extends Component {
  val io = new Bundle {
    val cmd = slave(Flow(SinCosCmd(p)))  // no backpressure — II = 1 by construction
    val rsp = master(Flow(SinCosRsp(p))) // rsp.valid is cmd.valid delayed by `latency`
  }

  p.method match {
    case SinCosMethod.Lut    => SinCosLut.build(p, io.cmd, io.rsp)
    case SinCosMethod.Taylor => SinCosTaylor.build(p, io.cmd, io.rsp)
    case SinCosMethod.Cordic => SinCosCordic.build(p, io.cmd, io.rsp)
    case other               => SpinalError(s"SinCos method $other not implemented yet")
  }

  /** compile-time pipeline latency for this (method, params). */
  def latency: Int = SinCos.latency(p)
}

object SinCos {

  /** Fixed pipeline latency (cmd→rsp cycles) per method. */
  def latency(p: SinCosParams): Int = p.method match {
    case SinCosMethod.Lut    => SinCosLut.latency
    case SinCosMethod.Taylor => SinCosTaylor.latency
    case SinCosMethod.Cordic => SinCosCordic.latency(p)
    case other               => SpinalError(s"latency: method $other not implemented yet")
  }

  /**
   * [[CordicParams]] for the [[SinCosMethod.Cordic]] wrapper: the angle is the phase (`zWidth =
   * phaseWidth`) and the rotated vector is `(Amax, 0)` (`xyWidth = ampWidth`), so the gain-corrected
   * output is `(Amax·cos(P), Amax·sin(P))`. CORDIC defaults (iters, guard bits, 1/K KCM) apply.
   */
  def cordicParams(p: SinCosParams): CordicParams =
    CordicParams(xyWidth = p.ampWidth, zWidth = p.phaseWidth)

  /**
   * Shared front-end: fold the full circle to one quarter-wave table over `[0, π/2)`.
   * With `u = phase bits as UInt`, `quad = {q1,q0} = u[n_p-1:n_p-2]`, and `idx` the remaining
   * `m` bits, the table address is the **top `t` bits** of `idx` (or its complement). Folding
   * the truncated address with a bitwise NOT is exact — `~(idx[hi:lo]) == (~idx)[hi:lo]` — so no
   * `+1` adder and no quadrant-edge special case (midpoint sampling).
   */
  def decode(p: SinCosParams, phase: SInt): QuadrantDecode = {
    val r  = QuadrantDecode(p)
    val u  = phase.asBits.asUInt
    val q1 = u(p.phaseWidth - 1)
    val q0 = u(p.phaseWidth - 2)
    // top t bits of the m-bit quarter index idx = u[n_p-3 : 0]
    val idxTop = u(p.phaseWidth - 3 downto p.phaseWidth - 2 - p.tableIndexBits)
    r.addrSin := q0 ? ~idxTop | idxTop
    r.addrCos := q0 ? idxTop | ~idxTop
    r.negSin  := q1
    r.negCos  := q1 ^ q0
    r
  }

  /**
   * Quarter-wave sine table, magnitudes only, sampled at each bin **midpoint**:
   * `T[k] = round(Amax · 2^guardBits · sin((k + ½) · 2^-t · π/2))`, `k ∈ [0, 2^t)`. With
   * `guardBits = 0` (v1) the entries are in `[0, Amax]` — the endpoints `sin 0` and `sin π/2 = 1.0`
   * are never stored, so they fit unsigned in `ampWidth-1` magnitude bits and the signed result
   * never reaches the most-negative code. `guardBits > 0` (v2) keeps `guardBits` extra fractional
   * bits per entry so the coarse value carries below an output ulp; the magnitude then needs
   * `ampWidth-1+guardBits` bits. Computed once in `Double` (exact to ~52 bits), rounded once.
   */
  def quarterTable(p: SinCosParams, guardBits: Int = 0): Seq[BigInt] = {
    val n     = 1 << p.tableIndexBits
    val scale = p.ampMax.toDouble * (BigInt(1) << guardBits).toDouble
    (0 until n).map { k =>
      val theta = (k + 0.5) * (scala.math.Pi / 2) / n
      BigInt(scala.math.round(scale * scala.math.sin(theta)))
    }
  }

  /**
   * The π/2 KCM constant: `round((π/2) · 2^piScaleBits)`. The Taylor residual is
   * `δ = δ_int · 2^-(t+f) · (π/2)`; the `2^-(t+f)` is a pure binary-point placement (free), so
   * the only irrational factor is `π/2`, captured once here as a fixed-point constant. Rounding
   * it to `Pb` fractional bits is the sole source of the `δ`-quantization error term.
   */
  def taylorPiConst(p: SinCosParams): BigInt =
    BigInt(scala.math.round((scala.math.Pi / 2) * (BigInt(1) << p.piScaleBits).toDouble))
}

/**
 * CORDIC method: a thin wrapper rotating `(Amax, 0)` by the phase angle. `exp(iπ·P) = cos(πP) +
 * i·sin(πP)`, so the rotated vector's real part is `Amax·cos(P)` (→ `cos`) and the imaginary part
 * `Amax·sin(P)` (→ `sin`). Amplitude rides the CORDIC `x` input for free; no separate amplitude
 * multiply. The exhaustive `SinCosSim` and the `SinCosSfdrSim` exercise [[Cordic]] as a third
 * converter method, directly comparable to v1/v2.
 */
object SinCosCordic {
  def latency(p: SinCosParams): Int = Cordic.latency(SinCos.cordicParams(p))

  def build(p: SinCosParams, cmd: Flow[SinCosCmd], rsp: Flow[SinCosRsp]): Unit = {
    val cordic = Cordic(SinCos.cordicParams(p))
    cordic.io.cmd.valid         := cmd.valid
    cordic.io.cmd.payload.xy.re := S(p.ampMax, p.ampWidth bits) // x = Amax
    cordic.io.cmd.payload.xy.im := S(0, p.ampWidth bits)        // y = 0
    cordic.io.cmd.payload.z     := cmd.payload.phase            // angle = value(phase)·π
    rsp.valid       := cordic.io.rsp.valid
    rsp.payload.cos := cordic.io.rsp.payload.re // Amax·cos(P)
    rsp.payload.sin := cordic.io.rsp.payload.im // Amax·sin(P)
  }
}
