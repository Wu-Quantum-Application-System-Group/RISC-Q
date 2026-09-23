package riscq.wr.pcs

import spinal.core._
import spinal.lib._

/**
 * 8b/10b decoder (spec 02 §1): combinational reverse lookup over the full 10-bit code space,
 * running-disparity in/out so two instances chain within a cycle.
 *
 * The lookup ROM is generated at elaboration by sweeping [[Enc8b10b.model]] over every symbol at
 * both disparities (collisions asserted away), so encoder and decoder cannot drift apart. Flags:
 *   - `codeErr`: the word is not a valid 8b/10b codeword under either running disparity;
 *   - `dispErr`: valid codeword, but not one the current running disparity may produce.
 * `rdOut` follows the received word's actual bit balance (ones > 5 ⇒ RD+, < 5 ⇒ RD−, balanced ⇒
 * hold), so the tracker re-converges even across erroneous words.
 */
case class Dec8b10b() extends Component {
  val io = new Bundle {
    val code    = in Bits (10 bits)
    val rdIn    = in Bool ()
    val data    = out Bits (8 bits)
    val isK     = out Bool ()
    val codeErr = out Bool ()
    val dispErr = out Bool ()
    val rdOut   = out Bool ()
  }

  // Entry per 10-bit code: [11:4] data, [3] isK, [1] validPlus, [0] validMinus.
  val table = Array.fill(1024)(0)
  for {
    rdPlus <- Seq(false, true)
    (byte, isK) <- (0 until 256).map((_, false)) ++ Enc8b10b.kCodesRdMinus.keys.map((_, true))
  } {
    val (code, _) = Enc8b10b.model(byte, isK, rdPlus)
    val symbol    = (byte << 4) | (if (isK) 8 else 0)
    val old       = table(code)
    assert((old >> 3) == 0 || (old >> 3) == (symbol >> 3), f"8b/10b decode collision at $code%03x")
    table(code) = symbol | old | (if (rdPlus) 2 else 1)
  }

  val entry      = Vec(table.map(v => B(v, 12 bits)))(io.code.asUInt)
  val validMinus = entry(0)
  val validPlus  = entry(1)

  io.data := entry(11 downto 4)
  io.isK := entry(3)
  io.codeErr := !(validMinus || validPlus)
  io.dispErr := !io.codeErr && !(io.rdIn ? validPlus | validMinus)

  val ones = CountOne(io.code)
  io.rdOut := (ones === 5) ? io.rdIn | (ones > 5)
}
