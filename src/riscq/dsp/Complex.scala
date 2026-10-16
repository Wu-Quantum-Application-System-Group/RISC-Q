package riscq.dsp

import spinal.core._
import spinal.lib._

/**
 * Complex fixed-point value in the SF(width) convention: each of `re`/`im` is an `SInt(width bits)`
 * code `x` denoting the real `value(x) = x · 2^-(width-1) ∈ [-1, +1)`. `+1.0` is not representable,
 * so the largest magnitude is `Amax = 2^(width-1) − 1` codes (`= 1 − 2^-(width-1)` in value). This is
 * a plain `Bundle` of two `SInt`s — no AFix anywhere in the pulse datapath.
 */
case class Complex(width: Int) extends Bundle {
  val re = SInt(width bits) // SF(width)
  val im = SInt(width bits) // SF(width)
}

object ComplexBatch {
  /**
   * A length-`n` batch of `Complex(w)` lanes — one DAC sample per lane. A plain `Vec` (no `valid`):
   * batch validity is the duration gate's job in the pulse toplevel, not a per-lane concern.
   */
  def apply(n: Int, w: Int): Vec[Complex] = Vec.fill(n)(Complex(w))
}
