package riscq.wr.pcs.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.wr.pcs.{Dec8b10b, Enc8b10b}

import scala.util.Random

/**
 * Independent 8b/10b golden, transcribed from wr-cores `gc_enc_8b10b.vhd` (tables *and* the
 * selection-logic structure follow that file, including its literal disparity strings), so a
 * shared systematic error with `Enc8b10b`'s algorithmic implementation is unlikely. Codes are
 * LSB-first ints (bit 0 = 'a', first on the wire), matching the `riscq.wr` convention.
 */
object Wr8b10bGolden {
  // c_enc_5b_6b_table / c_enc_3b_4b_table, `abcdei` / `fghj` reading order.
  private val t6 = Seq(
    "100111", "011101", "101101", "110001", "110101", "101001", "011001", "111000",
    "111001", "100101", "010101", "110100", "001101", "101100", "011100", "010111",
    "011011", "100011", "010011", "110010", "001011", "101010", "011010", "111010",
    "110011", "100110", "010110", "110110", "001110", "101110", "011110", "101011"
  )
  private val t4    = Seq("1011", "1001", "0101", "1100", "1101", "1010", "0110", "1110")
  private val disp6 = "11101000100000011000000110010111" // c_disPar_6b, literal
  private val disp4 = "10001001"                         // c_disPar_4b, literal

  // the control-code case list of p_encoding, `abcdeifghj` reading order
  private val kTable = Map(
    0x1c -> "0011110100", 0x3c -> "0011111001", 0x5c -> "0011110101", 0x7c -> "0011110011",
    0x9c -> "0011110010", 0xbc -> "0011111010", 0xdc -> "0011110110", 0xfc -> "0011111000",
    0xf7 -> "1110101000", 0xfb -> "1101101000", 0xfd -> "1011101000", 0xfe -> "0111101000"
  )

  private def lsb(s: String): Int = s.zipWithIndex.map { case (c, i) => (c - '0') << i }.sum
  private def inv(s: String): String = s.map(c => if (c == '1') '0' else '1')

  /** None for an invalid K byte (the VHDL's `err_o`), else (code LSB-first, rdOut). */
  def encode(byte: Int, isK: Boolean, rdPlus: Boolean): Option[(Int, Boolean)] = {
    val x   = byte & 0x1f
    val y   = (byte >> 5) & 0x7
    val d6b = disp6(x) == '1'
    val d4b = disp4(y) == '1'
    if (isK) {
      kTable.get(byte).map { str =>
        val t      = lsb(str)
        val code   = if (rdPlus) ~t & 0x3ff else t
        val toggle = if ((byte & 3) != 0) false else !(d6b ^ d4b) // '1' xor d6 xor d4, low-bits guard
        (code, rdPlus ^ toggle)
      }
    } else {
      val six0  = t6(x)
      val four0 = t4(y)
      var six   = six0
      var four  = four0
      if (!rdPlus) {
        if (d4b == d6b) {
          if (d6b) four = inv(four0)
        } else if (d4b) {
          if (six0.drop(3) == "011" && four0.take(3) == "111") four = "0111" // A7, RD−
        } else {
          if (four0 == "1100") four = inv(four0)
        }
      } else {
        if (d6b) six = inv(six0)
        else {
          if (six0 == "111000") six = inv(six0)
          if (d4b) {
            four = if (six0.drop(3) == "100" && four0.take(3) == "111") "1000" else inv(four0) // A7, RD+
          } else {
            if (four0 == "1100") four = inv(four0)
          }
        }
      }
      Some((lsb(six + four), rdPlus ^ (d6b ^ d4b)))
    }
  }

  val allSymbols: Seq[(Int, Boolean)] =
    (0 until 256).map((_, false)) ++ kTable.keys.toSeq.sorted.map((_, true))

  /** code -> (byte, isK); and the per-disparity valid sets — the decode golden. */
  val (codeToSymbol, validMinus, validPlus) = {
    var m  = Map[Int, (Int, Boolean)]()
    var vm = Set[Int]()
    var vp = Set[Int]()
    for ((b, k) <- allSymbols; rdPlus <- Seq(false, true)) {
      val (code, _) = encode(b, k, rdPlus).get
      m.get(code).foreach(prev => assert(prev == ((b, k)), f"golden decode collision $code%03x"))
      m += code -> ((b, k))
      if (rdPlus) vp += code else vm += code
    }
    (m, vm, vp)
  }

  /** the decoder's disparity-tracking rule (popcount) — shared definition */
  def rdNext(code: Int, rdPlus: Boolean): Boolean = {
    val ones = Integer.bitCount(code)
    if (ones == 5) rdPlus else ones > 5
  }
}

/**
 * W1 gate, spec 02 §1: exhaustive encode vs the independent golden (all 256 data + all 12 K, both
 * disparities), exhaustive decode classification over all 1024 codes × both disparities
 * (data/isK/codeErr/dispErr/rdOut), encode→decode round trip, and stream properties on a chained
 * two-symbol encoder (run length ≤ 5, bounded running DC sum, golden lock-step disparity).
 * Run: `mill runMain riscq.wr.pcs.sim.Enc8b10bSim`.
 */
object Enc8b10bSim extends App {

  case class Tb() extends Component {
    val io = new Bundle {
      val encData = in Bits (8 bits)
      val encIsK  = in Bool ()
      val encRdIn = in Bool ()
      val encCode  = out Bits (10 bits)
      val encRdOut = out Bool ()

      val decCode = in Bits (10 bits)
      val decRdIn = in Bool ()
      val decData    = out Bits (8 bits)
      val decIsK     = out Bool ()
      val decCodeErr = out Bool ()
      val decDispErr = out Bool ()
      val decRdOut   = out Bool ()

      // chained pair with a registered running disparity — the WrTxPcs usage pattern
      val pData0, pData1 = in Bits (8 bits)
      val pK0, pK1       = in Bool ()
      val pCode0, pCode1 = out Bits (10 bits)
      val pRd            = out Bool ()
    }
    val enc = Enc8b10b()
    enc.io.data := io.encData
    enc.io.isK := io.encIsK
    enc.io.rdIn := io.encRdIn
    io.encCode := enc.io.code
    io.encRdOut := enc.io.rdOut

    val dec = Dec8b10b()
    dec.io.code := io.decCode
    dec.io.rdIn := io.decRdIn
    io.decData := dec.io.data
    io.decIsK := dec.io.isK
    io.decCodeErr := dec.io.codeErr
    io.decDispErr := dec.io.dispErr
    io.decRdOut := dec.io.rdOut

    val pair = new Area {
      val rd = Reg(Bool()) init False
      val e0 = Enc8b10b()
      val e1 = Enc8b10b()
      e0.io.data := io.pData0
      e0.io.isK := io.pK0
      e0.io.rdIn := rd
      e1.io.data := io.pData1
      e1.io.isK := io.pK1
      e1.io.rdIn := e0.io.rdOut
      rd := e1.io.rdOut
      io.pCode0 := e0.io.code
      io.pCode1 := e1.io.code
      io.pRd := rd
    }
  }

  SimConfig.compile(Tb()).doSim("enc8b10b", seed = 42) { dut =>
    import Wr8b10bGolden._
    dut.clockDomain.forkStimulus(10)
    dut.io.pData0 #= 0; dut.io.pData1 #= 0; dut.io.pK0 #= false; dut.io.pK1 #= false
    dut.clockDomain.waitSampling(2)
    var checks = 0L

    def settle(): Unit = sleep(1)

    // === exhaustive encode: all data + all K, both disparities, vs the independent golden ====
    for ((byte, isK) <- allSymbols; rdPlus <- Seq(false, true)) {
      dut.io.encData #= byte
      dut.io.encIsK #= isK
      dut.io.encRdIn #= rdPlus
      settle()
      val (gCode, gRd) = encode(byte, isK, rdPlus).get
      assert(
        dut.io.encCode.toInt == gCode && dut.io.encRdOut.toBoolean == gRd,
        f"enc mismatch byte=$byte%02x isK=$isK rd+=$rdPlus: hw=(${dut.io.encCode.toInt}%03x," +
          f"${dut.io.encRdOut.toBoolean}) golden=($gCode%03x,$gRd)"
      )
      checks += 1
    }

    // === exhaustive decode classification: all 1024 codes × both disparities ================
    for (code <- 0 until 1024; rdPlus <- Seq(false, true)) {
      dut.io.decCode #= code
      dut.io.decRdIn #= rdPlus
      settle()
      val valid   = codeToSymbol.contains(code)
      val validAt = if (rdPlus) validPlus(code) else validMinus(code)
      assert(dut.io.decCodeErr.toBoolean == !valid, f"codeErr wrong at $code%03x")
      assert(dut.io.decDispErr.toBoolean == (valid && !validAt), f"dispErr wrong at $code%03x rd+=$rdPlus")
      assert(dut.io.decRdOut.toBoolean == rdNext(code, rdPlus), f"rdOut wrong at $code%03x")
      if (valid) {
        val (b, k) = codeToSymbol(code)
        assert(dut.io.decData.toInt == b && dut.io.decIsK.toBoolean == k, f"decode wrong at $code%03x")
      }
      checks += 3
    }

    // === stream properties on the chained pair: run length, DC balance, golden lock-step ====
    val rng  = new Random(7)
    val bits = scala.collection.mutable.ArrayBuffer[Int]()
    var gRd  = false // golden running disparity (pair starts at RD−)
    for (cyc <- 0 until 20000) {
      // symbol soup shaped like the real line: idle pairs, frames' data bytes, SOF/EOF
      val (d0, k0, d1, k1) = rng.nextInt(4) match {
        case 0 => (0xbc, true, if (rng.nextBoolean()) 0xc5 else 0x50, false) // /I1/ or /I2/
        case 1 => (0xfb, true, rng.nextInt(256), false)                      // SOF + byte
        case 2 => (rng.nextInt(256), false, 0xfd, true)                      // byte + EOF
        case _ => (rng.nextInt(256), false, rng.nextInt(256), false)
      }
      dut.io.pData0 #= d0; dut.io.pK0 #= k0
      dut.io.pData1 #= d1; dut.io.pK1 #= k1
      settle()
      assert(dut.io.pRd.toBoolean == gRd, s"pair rd diverged at cycle $cyc")
      val (c0, r0) = encode(d0, k0, gRd).get
      val (c1, r1) = encode(d1, k1, r0).get
      assert(dut.io.pCode0.toInt == c0 && dut.io.pCode1.toInt == c1, s"pair code diverged at cycle $cyc")
      gRd = r1
      for (c <- Seq(c0, c1); i <- 0 until 10) bits += ((c >> i) & 1)
      dut.clockDomain.waitSampling()
      checks += 2
    }
    var run = 0; var last = -1; var maxRun = 0; var sum = 0
    var minSum = 0; var maxSum = 0
    for (b <- bits) {
      run = if (b == last) run + 1 else 1
      last = b; maxRun = maxRun max run
      sum += 2 * b - 1; minSum = minSum min sum; maxSum = maxSum max sum
    }
    assert(maxRun <= 5, s"run length $maxRun > 5")
    assert(maxSum - minSum <= 6, s"DC wander ${maxSum - minSum} > 6")

    println(f"[Enc8b10bSim] PASS  $checks checks (exhaustive enc/dec + ${bits.size} stream bits, maxRun=$maxRun)")
  }
}
