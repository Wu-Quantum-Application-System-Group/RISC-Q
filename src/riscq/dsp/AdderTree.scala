package riscq.dsp

import spinal.core._
import spinal.lib._

/**
 * Balanced, fully-registered integer reduction of a lane vector to one sum — a thin wrapper over
 * SpinalHDL's [[spinal.lib.reduceBalancedTree]]:
 *
 * {{{
 *   inputs.map(_.resize(accW)).reduceBalancedTree(_ + _, (s, _) => RegNext(s))
 * }}}
 *
 *  - '''Pre-resize each lane to `accW`''' (sign-extend) so the non-growing `_ + _` can never overflow
 *    inside the tree: the batch sum of `n` lanes of `inWidth` needs only `inWidth + log2Up(n)` bits,
 *    and `accW` is sized well above that by the caller. Resizing once up front lets every adder add
 *    without per-level growth, dropping all index arithmetic.
 *  - '''`levelBridge = RegNext`''' registers '''every''' tree level — including the odd element carried
 *    over at a level with an odd count — so all paths see the same depth and the latency is a uniform
 *    `log2Up(n)`. Vivado can then retime/pack the adders.
 *
 * The resize is combinational (no latency); the first register stage is the first `op` level.
 */
object AdderTree {
  /** Sum `inputs` into one `accW`-bit `SInt`, registered, latency [[latency]]`(inputs.length)`. */
  def apply(inputs: Seq[SInt], accW: Int): SInt = {
    require(inputs.nonEmpty, "AdderTree needs at least one input")
    inputs.map(_.resize(accW bits)).reduceBalancedTree(_ + _, (s, _) => RegNext(s))
  }

  /** Register-stage latency of [[apply]] for `n` inputs: the balanced tree depth `log2Up(n)`
   *  (`0` for a single input, which `reduceBalancedTree` returns un-registered). */
  def latency(n: Int): Int = log2Up(n)
}
