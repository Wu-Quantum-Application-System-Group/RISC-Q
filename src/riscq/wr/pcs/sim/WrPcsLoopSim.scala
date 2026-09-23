package riscq.wr.pcs.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim.{StreamMonitor, StreamReadyRandomizer}
import riscq.wr.pcs.{WrRxPcs, WrTxPcs}

import scala.collection.mutable
import scala.util.Random

/**
 * W1 loop gate (spec 02 §6): `WrTxPcs` → minimal serial bit model → `WrRxPcs`, TX and RX in
 * separate clock domains. The serial model is the pre-`GtySimPhy` stand-in: it serializes each
 * 20-bit TX word LSB-first into a bit queue primed with `delayBits` of garbage (the line delay +
 * a random word-alignment phase) and regroups words on the RX side after a one-time
 * comma-boundary alignment (the job W4's dice-throw `CommaAligner` does on hardware), so the RX
 * latency is a run-constant.
 *
 * Gates:
 *   - random message soup (lengths 1..62, random gaps, random RX back-pressure): every
 *     uncorrupted message delivered exactly once, in order, bit-exact;
 *   - injected single-bit line errors: the hit frame is not delivered, an error counter moves,
 *     sync recovers, later frames flow;
 *   - exact trigger timing: TX trigger coincides with the SOF word on `txDataRaw` (Δ_tx,PCS = 0);
 *     for every frame `rxTrigIter − txTrigIter == D + 2` where `D = delayBits/20` is the model's
 *     word delay and the `+2` is the documented RX PCS constant (input reg + trigger reg, the
 *     `WrRxPcs` rxDataRaw→rxTrigger 2-cycle pipeline; the TB's TX-read and RX-drive thread
 *     phases cancel) — the spec 02 §5 latency-constant regression;
 *   - cal mode: K28.7 pattern detected / released; oversize messages discarded without transmit.
 *
 * Run: `mill runMain riscq.wr.pcs.sim.WrPcsLoopSim`.
 */
object WrPcsLoopSim extends App {

  case class Tb() extends Component {
    val txCd = ClockDomain.external("tx")
    val rxCd = ClockDomain.external("rx")
    val tx   = txCd on WrTxPcs()
    val rx   = rxCd on WrRxPcs(calThreshLog2 = 8)

    val io = new Bundle {
      val frameIn   = slave Stream (Fragment(Bits(8 bits)))
      val txDataRaw = out Bits (20 bits)
      val txTrigger = out Bool ()
      val calMode   = in Bool ()
      val oversize  = out Bool ()

      val rxDataRaw   = in Bits (20 bits)
      val frameOut    = master Stream (Fragment(Bits(8 bits)))
      val rxTrigger   = out Bool ()
      val synced      = out Bool ()
      val calDetected = out Bool ()
      val codeErrCnt  = out UInt (16 bits)
      val dispErrCnt  = out UInt (16 bits)
      val crcErrCnt   = out UInt (16 bits)
      val droppedCnt  = out UInt (16 bits)
      val syncLossCnt = out UInt (16 bits)
    }
    io.frameIn >> tx.io.frame
    io.txDataRaw := tx.io.txDataRaw
    io.txTrigger := tx.io.txTrigger
    tx.io.calMode := io.calMode
    io.oversize := tx.io.oversize

    rx.io.rxDataRaw := io.rxDataRaw
    rx.io.frame >> io.frameOut
    io.rxTrigger := rx.io.rxTrigger
    io.synced := rx.io.synced
    io.calDetected := rx.io.calDetected
    io.codeErrCnt := rx.io.codeErrCnt
    io.dispErrCnt := rx.io.dispErrCnt
    io.crcErrCnt := rx.io.crcErrCnt
    io.droppedCnt := rx.io.droppedCnt
    io.syncLossCnt := rx.io.syncLossCnt
  }

  val compiled = SimConfig.compile(Tb())

  def run(delayBits: Int, seed: Int): Unit = compiled.doSim(s"loop-d$delayBits", seed = seed) { dut =>
    val rng = new Random(seed)
    dut.io.frameIn.valid #= false
    dut.io.frameOut.ready #= false
    dut.io.rxDataRaw #= 0
    dut.io.calMode #= false
    dut.txCd.forkStimulus(16)
    fork { sleep(7); dut.rxCd.forkStimulus(16) }

    // === the serial line: bit queue primed with delayBits of garbage =========================
    val bitQ = mutable.Queue[Int]()
    for (_ <- 0 until delayBits) bitQ += rng.nextInt(2)
    val D = delayBits / 20 // TX word w emerges as RX word D + w (after alignment discard)

    // SOF-at-symbol-0 codes (either disparity) to cross-check the TX trigger
    val sofCodes = Set(false, true).map(rd => Wr8b10bGolden.encode(0xfb, isK = true, rd).get._1)

    // === TX side: push words, observe triggers, inject line errors ===========================
    val txTrigs        = mutable.ArrayBuffer[Long]()
    val corruptFrames  = mutable.Set[Int]()
    var corruptIn      = 0
    var corruptedCount = 0
    fork {
      dut.txCd.waitSampling(5) // registers are random until reset applies — skip the window
      var iter = 0L
      while (true) {
        dut.txCd.waitSampling()
        var w = dut.io.txDataRaw.toBigInt.toInt
        if (dut.io.txTrigger.toBoolean) {
          assert(sofCodes.contains(w & 0x3ff), f"txTrigger without SOF at symbol 0: $w%05x")
          if (corruptFrames.contains(txTrigs.size)) corruptIn = 1 + rng.nextInt(2)
          txTrigs += iter
        } else if (corruptIn > 0) {
          corruptIn -= 1
          if (corruptIn == 0) { w ^= 1 << rng.nextInt(10); corruptedCount += 1 } // hit symbol 0
        }
        for (i <- 0 until 20) bitQ += (w >> i) & 1
        iter += 1
      }
    }

    // === RX side: align once, drive words, observe triggers ==================================
    val rxTrigs = mutable.ArrayBuffer[Long]()
    fork {
      dut.rxCd.waitSampling(5)
      for (_ <- 0 until delayBits % 20) bitQ.dequeue() // the one-time comma-boundary alignment
      var iter = 0L
      while (true) {
        assert(bitQ.size >= 20, "serial model underflow")
        var w = 0
        for (i <- 0 until 20) w |= bitQ.dequeue() << i
        dut.io.rxDataRaw #= w
        dut.rxCd.waitSampling()
        if (dut.io.rxTrigger.toBoolean) rxTrigs += iter
        iter += 1
      }
    }

    // === RX message monitor with random back-pressure ========================================
    // Callback-based (onSamplings) driver+monitor pair: a hand-rolled woken-thread loop here
    // races — thread `#=` writes land one edge later than the thread's own same-wake reads
    // assume, so every ready toggle slips the recording by one byte (byte i lost, byte i+1
    // recorded twice, phantom frames when the slip crosses `last`).
    val delivered = mutable.ArrayBuffer[Seq[Int]]()
    val current   = mutable.ArrayBuffer[Int]()
    StreamReadyRandomizer(dut.io.frameOut, dut.rxCd).factor = 0.95f
    StreamMonitor(dut.io.frameOut, dut.rxCd) { p =>
      current += p.fragment.toInt
      if (p.last.toBoolean) { delivered += current.toList; current.clear() }
    }

    def sendMsg(bytes: Seq[Int]): Unit = {
      for ((b, i) <- bytes.zipWithIndex) {
        dut.io.frameIn.valid #= true
        dut.io.frameIn.fragment #= b
        dut.io.frameIn.last #= (i == bytes.size - 1)
        while (!dut.io.frameIn.ready.toBoolean) dut.txCd.waitSampling()
        dut.txCd.waitSampling()
      }
      dut.io.frameIn.valid #= false
    }

    // === phase 0: sync acquisition on idles ==================================================
    dut.txCd.waitSampling(60)
    assert(dut.io.synced.toBoolean, "RX did not sync on idle stream")

    // === phase 1: message soup with sparse corruption ========================================
    val messages = (0 until 60).map { k =>
      Seq.fill(1 + rng.nextInt(62))(rng.nextInt(256))
    }
    for (k <- messages.indices if k % 5 == 3) corruptFrames += k
    for ((msg, k) <- messages.zipWithIndex) {
      sendMsg(msg)
      // gap covers the previous frame's 1 B/cycle RX drain so the 2-slot buffer never overflows
      // (the real exchange runs at ~Hz, spec 02 §4 / D4)
      dut.txCd.waitSampling(msg.size + 5 + rng.nextInt(30))
    }
    dut.txCd.waitSampling(400) // drain the last frame + RX buffers

    val expected = messages.zipWithIndex.filterNot { case (_, k) => corruptFrames.contains(k) }.map(_._1)
    assert(corruptedCount == corruptFrames.size, s"injector armed ${corruptFrames.size} but hit $corruptedCount")
    if (delivered.size != expected.size || delivered.zip(expected).exists { case (d, e) => d != e }) {
      println(s"delivered ${delivered.size} != expected ${expected.size} (corrupted ${corruptFrames.size}, " +
        s"dropped=${dut.io.droppedCnt.toInt} crcErr=${dut.io.crcErrCnt.toInt} " +
        s"codeErr=${dut.io.codeErrCnt.toInt} dispErr=${dut.io.dispErrCnt.toInt} " +
        s"txTrigs=${txTrigs.size} rxTrigs=${rxTrigs.size})")
      println(s"corrupt frames: ${corruptFrames.toSeq.sorted}")
      println(s"sent lengths: ${messages.map(_.size)}")
      val i = delivered.zip(expected).indexWhere { case (d, e) => d != e } match {
        case -1 => expected.size.min(delivered.size)
        case x  => x
      }
      for (j <- (i - 2).max(0) to (i + 2).min(delivered.size - 1)) {
        println(s"got [$j] len=${delivered(j).size}: ${delivered(j).take(20)}")
        if (j < expected.size) println(s"want[$j] len=${expected(j).size}: ${expected(j).take(20)}")
      }
      simFailure("message soup mismatch")
    }
    val errSum = dut.io.crcErrCnt.toInt + dut.io.droppedCnt.toInt
    assert(errSum >= corruptFrames.size, s"errors counted $errSum < corrupted ${corruptFrames.size}")
    assert(dut.io.synced.toBoolean, "sync did not recover after injected errors")

    // === phase 2: exact trigger timing / latency constant (spec 02 §5) =======================
    assert(txTrigs.size == messages.size && rxTrigs.size == messages.size,
      s"trigger counts tx=${txTrigs.size} rx=${rxTrigs.size} != ${messages.size}")
    val deltas = txTrigs.zip(rxTrigs).map { case (t, r) => r - t }
    assert(deltas.toSet.size == 1, s"RX-TX trigger delta not constant: ${deltas.toSet}")
    assert(deltas.head == D + 2, s"trigger delta ${deltas.head} != D+2=${D + 2} — PCS pipeline moved, update spec 02 §5")

    // === phase 3: cal pattern =================================================================
    dut.io.calMode #= true
    dut.txCd.waitSampling(400) // threshold 2^8 symbols = 128 cycles
    assert(dut.io.calDetected.toBoolean, "cal pattern not detected")
    dut.io.calMode #= false
    dut.txCd.waitSampling(60)
    assert(!dut.io.calDetected.toBoolean, "cal detect stuck after cal mode exit")

    // === phase 4: oversize message discarded, link still alive ===============================
    val preTrigs = txTrigs.size
    var oversizeSeen = false
    val ovMon = fork {
      while (!oversizeSeen) { dut.txCd.waitSampling(); oversizeSeen |= dut.io.oversize.toBoolean }
    }
    sendMsg(Seq.fill(70)(rng.nextInt(256)))
    dut.txCd.waitSampling(200)
    assert(oversizeSeen, "oversize pulse missing")
    assert(txTrigs.size == preTrigs, "oversize message was transmitted")
    val tail = Seq(0x01, 0x02, 0x03)
    sendMsg(tail)
    dut.txCd.waitSampling(300)
    assert(delivered.last == tail, "post-oversize message not delivered")

    println(f"[WrPcsLoopSim] PASS delay=$delayBits bits (D=$D words): ${delivered.size} delivered, " +
      f"${corruptFrames.size} corrupted+refused, delta=${deltas.head} const, cal+oversize ok")
  }

  run(delayBits = 47, seed = 100)
  run(delayBits = 140, seed = 101)
  run(delayBits = 263, seed = 102)
  println("[WrPcsLoopSim] all configs PASS")
}
