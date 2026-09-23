package riscq.wr.pcs

import spinal.core._
import spinal.lib._

/**
 * 8b/10b symbol alphabet used by the WR PCS (spec 02 §1/§4).
 *
 * Bit conventions, fixed here for the whole `riscq.wr` stack:
 *   - a 10-bit code is packed LSB-first in transmission order: bit 0 = 'a' (first on the wire),
 *     bits 5..0 = `abcdei`, bits 9..6 = `fghj`;
 *   - in a 20-bit raw word, symbol 0 (the even symbol, comma position) is bits [9:0] and is
 *     transmitted first, symbol 1 is bits [19:10] (matches GTY raw mode: TXDATA bit 0 first).
 */
object WrSymbols {
  val K28_5 = 0xbc // comma (idle first symbol)
  val K27_7 = 0xfb // SOF
  val K29_7 = 0xfd // EOF
  val K28_7 = 0xfc // calibration pattern
  val D5_6  = 0xc5 // /I1/ second symbol (disparity-neutral)
  val D16_2 = 0x50 // /I2/ second symbol (disparity-flipping); also the post-EOF fill byte
}

/**
 * 8b/10b encoder (spec 02 §1): standard Widmer–Franaszek 5b/6b + 3b/4b tables, combinational,
 * running-disparity in/out so two instances chain within a cycle. `rdIn`/`rdOut`: False = RD−,
 * True = RD+. Valid K bytes are the 12 standard ones ([[Enc8b10b.kCodesRdMinus]]); feeding any
 * other byte with `isK` emits that table's zero entry (the PCS only ever sends valid Ks).
 *
 * The elaboration-time mirror [[Enc8b10b.model]] must stay in lock-step with the hardware — it
 * generates [[Dec8b10b]]'s lookup ROM, and `Enc8b10bSim` checks both exhaustively against an
 * independent golden transcribed from wr-cores `gc_enc_8b10b.vhd`.
 */
case class Enc8b10b() extends Component {
  val io = new Bundle {
    val data  = in Bits (8 bits)
    val isK   = in Bool ()
    val rdIn  = in Bool ()
    val code  = out Bits (10 bits)
    val rdOut = out Bool ()
  }
  import Enc8b10b._

  val x = io.data(4 downto 0).asUInt // EDCBA -> 5b/6b block
  val y = io.data(7 downto 5).asUInt // HGF   -> 3b/4b block

  val dataArea = new Area {
    val t6    = Vec(enc5b6bRdMinus.map(v => B(v, 6 bits)))(x)
    val t4    = Vec(enc3b4bRdMinus.map(v => B(v, 4 bits)))(y)
    val disp6 = Vec(disp6Unbalanced.map(Bool(_)))(x)
    val disp4 = Vec(disp4Unbalanced.map(Bool(_)))(y)

    // 6b block: RD+ uses the complement when unbalanced, plus the balanced special case D7.
    val code6 = (io.rdIn && (disp6 || x === 7)) ? ~t6 | t6
    // intra-word disparity in front of the 3b/4b block.
    val intra = io.rdIn ^ disp6
    // D.x.7: substitute A7 (avoids a run of 5) for x∈{17,18,20} at intra RD− / {11,13,14} at RD+.
    val a7 = y === 7 && (
      (!intra && (x === 17 || x === 18 || x === 20)) ||
        (intra && (x === 11 || x === 13 || x === 14))
    )
    val code4 = Bits(4 bits)
    when(!intra) {
      code4 := a7 ? B(a7RdMinus, 4 bits) | t4
    } otherwise {
      when(disp4) {
        code4 := a7 ? B(a7RdPlus, 4 bits) | ~t4
      } otherwise {
        code4 := (y === 3) ? ~t4 | t4 // Dx.3's balanced alternate (1100/0011)
      }
    }
    val code  = code4 ## code6
    val rdOut = io.rdIn ^ disp6 ^ disp4
  }

  val kArea = new Area {
    val table = Vec(Bits(10 bits), 256)
    for (i <- 0 until 256) table(i) := B(kCodesRdMinus.getOrElse(i, 0), 10 bits)
    val t     = table(io.data.asUInt)
    val code  = io.rdIn ? ~t | t
    val rdOut = io.rdIn ^ Vec((0 until 256).map(i => Bool(kFlips(i))))(io.data.asUInt)
  }

  io.code  := io.isK ? kArea.code | dataArea.code
  io.rdOut := io.isK ? kArea.rdOut | dataArea.rdOut
}

object Enc8b10b {
  // 5b/6b RD− column, `abcdei` reading order (a first); packed LSB-first via lsbFirst().
  private val enc5b6bStrings = Seq(
    "100111", "011101", "101101", "110001", "110101", "101001", "011001", "111000",
    "111001", "100101", "010101", "110100", "001101", "101100", "011100", "010111",
    "011011", "100011", "010011", "110010", "001011", "101010", "011010", "111010",
    "110011", "100110", "010110", "110110", "001110", "101110", "011110", "101011"
  )
  // 3b/4b RD− column, `fghj` reading order; y = 7 entry is P7 (A7 is substituted separately).
  private val enc3b4bStrings = Seq("1011", "1001", "0101", "1100", "1101", "1010", "0110", "1110")

  /** Parse a transmission-order bit string (first char = first bit on the wire) LSB-first. */
  def lsbFirst(s: String): Int = s.zipWithIndex.map { case (c, i) => (c - '0') << i }.sum

  val enc5b6bRdMinus = enc5b6bStrings.map(lsbFirst)
  val enc3b4bRdMinus = enc3b4bStrings.map(lsbFirst)
  val disp6Unbalanced = enc5b6bStrings.map(_.count(_ == '1') != 3)
  val disp4Unbalanced = enc3b4bStrings.map(_.count(_ == '1') != 2)
  val a7RdMinus = lsbFirst("0111")
  val a7RdPlus  = lsbFirst("1000")

  // The 12 valid K codes, RD− column in `abcdeifghj` reading order (wr-cores gc_enc_8b10b list).
  val kCodesRdMinus: Map[Int, Int] = Map(
    0x1c -> "0011110100", // K28.0
    0x3c -> "0011111001", // K28.1
    0x5c -> "0011110101", // K28.2
    0x7c -> "0011110011", // K28.3
    0x9c -> "0011110010", // K28.4
    0xbc -> "0011111010", // K28.5
    0xdc -> "0011110110", // K28.6
    0xfc -> "0011111000", // K28.7
    0xf7 -> "1110101000", // K23.7
    0xfb -> "1101101000", // K27.7
    0xfd -> "1011101000", // K29.7
    0xfe -> "0111101000"  // K30.7
  ).map { case (k, v) => k -> lsbFirst(v) }

  private def kFlips(byte: Int): Boolean =
    kCodesRdMinus.get(byte).exists(c => Integer.bitCount(c) != 5)

  def isValidK(byte: Int): Boolean = kCodesRdMinus.contains(byte)

  /**
   * Elaboration-time mirror of the hardware (same tables, same selection rules). `rdPlus`:
   * false = RD−. Returns (code LSB-first, rdOut). Generates [[Dec8b10b]]'s ROM.
   */
  def model(byte: Int, isK: Boolean, rdPlus: Boolean): (Int, Boolean) = {
    if (isK) {
      val t = kCodesRdMinus.getOrElse(byte, 0)
      (if (rdPlus) ~t & 0x3ff else t, rdPlus ^ kFlips(byte))
    } else {
      val x  = byte & 0x1f
      val y  = (byte >> 5) & 0x7
      val t6 = enc5b6bRdMinus(x)
      val d6 = disp6Unbalanced(x)
      val d4 = disp4Unbalanced(y)

      val code6 = if (rdPlus && (d6 || x == 7)) ~t6 & 0x3f else t6
      val intra = rdPlus ^ d6
      val a7 = y == 7 && (
        (!intra && Set(17, 18, 20)(x)) || (intra && Set(11, 13, 14)(x))
      )
      val t4 = enc3b4bRdMinus(y)
      val code4 =
        if (!intra) { if (a7) a7RdMinus else t4 }
        else if (d4) { if (a7) a7RdPlus else ~t4 & 0xf }
        else { if (y == 3) ~t4 & 0xf else t4 }
      ((code4 << 6) | code6, rdPlus ^ d6 ^ d4)
    }
  }
}
