package riscq.wr.time.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.wr.time.RefTimeTsu

import scala.collection.mutable
import scala.util.Random

/**
 * W2 gate, spec 04 §4. dspClk period = 2000 sim units (1 unit ≙ 1 ps, 500 MHz); the trigger is
 * an async pulse of width 16000 (a 62.5 MHz PCS single-cycle pulse) placed on a ps grid strictly
 * between clock edges.
 *
 *   - Phase sweep (two slide rates ≙ trigger-clock ppm offsets): every pulse captures exactly
 *     once (`seq` +1), `ts == refTime-in-force-at-the-rise + 2` (the documented sync-chain
 *     constant), and against the continuous-time golden the quantization residue spans (0,1]
 *     — the dither statistic the exchange averaging relies on (README §3).
 *   - Matched-chain: a second identical TSU on the same trigger yields identical `ts` always.
 *   - Overrun/seq: unacked back-to-back captures set sticky `overrun`, `ack` clears, next
 *     capture is clean.
 *   - Round-trip golden (feeds W3): four TSUs (master/slave TX+RX pairs) on two same-domain
 *     counters with an injected offset; scripted SYNC/DELAY_REQ exchanges with a symmetric link
 *     delay; `offset_MS = ((t1−t2)+(t4−t3))/2` recovers the injected offset: each exchange
 *     within ±2 cycles, the N-exchange mean within ±0.5 (README §3 arithmetic as the golden).
 *
 * Run: `mill runMain riscq.wr.time.sim.RefTimeTsuSim`.
 */
object RefTimeTsuSim extends App {
  val PERIOD = 2000L // sim units per dspClk cycle

  case class Tb() extends Component {
    val io = new Bundle {
      val trigger = in Bool ()
      val ack     = in Bool ()
      val refTime = out UInt (64 bits)
      val ts      = out UInt (64 bits)
      val seq     = out UInt (4 bits)
      val valid   = out Bool ()
      val overrun = out Bool ()
      val tsB     = out UInt (64 bits)
      val validB  = out Bool ()
    }
    val refTime = Reg(UInt(64 bits)) init 0
    refTime := refTime + 1
    val a, b = RefTimeTsu()
    for (t <- Seq(a, b)) {
      t.io.trigger := io.trigger
      t.io.refTime := refTime
      t.io.ack := io.ack
    }
    io.refTime := refTime
    io.ts := a.io.ts
    io.seq := a.io.seq
    io.valid := a.io.valid
    io.overrun := a.io.overrun
    io.tsB := b.io.ts
    io.validB := b.io.valid
  }

  // Master/slave exchange harness: two syntonized counters with an injected offset, four TSUs.
  case class ExTb() extends Component {
    val io = new Bundle {
      val load  = in Bool ()
      val sInit = in UInt (64 bits)
      val mTxTrig, mRxTrig, sTxTrig, sRxTrig = in Bool ()
      val ack = in Bool ()
      val refM, refS     = out UInt (64 bits)
      val t1, t2, t3, t4 = out UInt (64 bits)
      val allValid       = out Bool ()
    }
    val refM = Reg(UInt(64 bits)) init 0
    val refS = Reg(UInt(64 bits)) init 0
    refM := refM + 1
    refS := refS + 1
    when(io.load) { refS := io.sInit }

    val mTx, mRx, sTx, sRx = RefTimeTsu()
    for ((t, trig, ref) <- Seq(
        (mTx, io.mTxTrig, refM), (mRx, io.mRxTrig, refM),
        (sTx, io.sTxTrig, refS), (sRx, io.sRxTrig, refS))) {
      t.io.trigger := trig
      t.io.refTime := ref
      t.io.ack := io.ack
    }
    io.refM := refM
    io.refS := refS
    io.t1 := mTx.io.ts // master sends SYNC
    io.t2 := sRx.io.ts // slave receives it
    io.t3 := sTx.io.ts // slave sends DELAY_REQ
    io.t4 := mRx.io.ts // master receives it
    io.allValid := mTx.io.valid && mRx.io.valid && sTx.io.valid && sRx.io.valid
  }

  // ==========================================================================================
  SimConfig.compile(Tb()).doSim("tsu", seed = 42) { dut =>
    dut.io.trigger #= false
    dut.io.ack #= false
    dut.clockDomain.forkStimulus(PERIOD)
    dut.clockDomain.waitSampling(4)
    val tEdge = simTime() // a sampling edge (trust the wake TIME; a read here would be pre-edge stale)
    sleep(3) // strictly inside the cycle: reads are now settled in-force values
    val r0 = dut.io.refTime.toBigInt // in force during (tEdge, tEdge+P)

    def sleepUntil(t: Long): Unit = { assert(t > simTime(), "schedule in the past"); sleep(t - simTime()) }
    // edges lie at tEdge + 2000n; an odd offset from tEdge can never coincide with one
    def oddRel(t: Long): Long = if ((t - tEdge) % 2 == 0) t + 1 else t

    var lastSeq = dut.io.seq.toInt
    val residues = mutable.ArrayBuffer[Double]()

    /** One pulse with its rising edge at absolute time T (must lie strictly between edges). */
    def pulseAndCheck(tRise: Long): Unit = {
      sleepUntil(tRise)
      val rInForce = dut.io.refTime.toBigInt
      dut.io.trigger #= true
      sleep(16000) // the 62.5 MHz pulse width — ~8 dspClk cycles, no extender needed (spec 04 §1)
      dut.io.trigger #= false
      sleep(8 * PERIOD) // sync chain + capture settle

      assert(dut.io.valid.toBoolean, s"no capture for pulse at $tRise")
      val ts = dut.io.ts.toBigInt
      val sq = dut.io.seq.toInt
      assert(sq == ((lastSeq + 1) & 0xf), s"seq $sq != ${(lastSeq + 1) & 0xf} — not exactly one capture")
      lastSeq = sq
      assert(ts == rInForce + 2,
        s"ts=$ts != refTime-at-rise+2=${rInForce + 2} at t=$tRise — sync-chain constant moved, update docs+spec 04")
      assert(dut.io.validB.toBoolean && dut.io.tsB.toBigInt == ts, "matched-chain: TSU B diverged from A")

      // continuous-time golden: ts − (R0 + (tRise−tEdge)/P) − 1 = ceil(c)−c ∈ (0,1], dithering
      val c = (tRise - tEdge).toDouble / PERIOD
      val q = (ts - r0).toDouble - c - 1.0
      assert(q > 0.0 && q <= 1.0, s"quantization residue $q outside (0,1] at t=$tRise")
      residues += q

      dut.io.ack #= true
      sleep(PERIOD + 1) // covers ≥ 1 sampling edge
      dut.io.ack #= false
      assert(!dut.io.overrun.toBoolean, s"spurious overrun at $tRise")
    }

    // === phase sweep at two slide rates (≙ ppm-offset trigger clocks) ========================
    for (slide <- Seq(73, 3)) {
      val n0 = residues.size
      for (k <- 0 until 300) {
        val phase = (k.toLong * slide) % PERIOD
        pulseAndCheck(oddRel(simTime() + 30000 + phase))
      }
      val qs = residues.drop(n0)
      assert(qs.min < 0.3 && qs.max > 0.7,
        s"slide=$slide: residues [${qs.min}, ${qs.max}] do not span the cycle — no dither, averaging contract broken")
    }

    // === overrun / seq semantics =============================================================
    def bareTrigger(): Unit = {
      dut.io.trigger #= true; sleep(16000); dut.io.trigger #= false; sleep(8 * PERIOD)
    }
    sleepUntil(oddRel(simTime() + 30000)) // keep off-edge placement
    bareTrigger()
    assert(dut.io.valid.toBoolean && !dut.io.overrun.toBoolean, "first unacked capture: valid without overrun")
    val seqAfterFirst = dut.io.seq.toInt
    bareTrigger() // second capture with the first still unacked
    assert(dut.io.valid.toBoolean && dut.io.overrun.toBoolean, "second unacked capture must set overrun")
    assert(dut.io.seq.toInt == ((seqAfterFirst + 1) & 0xf), "overrun capture must still bump seq")
    dut.io.ack #= true; sleep(PERIOD + 1); dut.io.ack #= false
    assert(!dut.io.valid.toBoolean && !dut.io.overrun.toBoolean, "ack must clear valid and overrun")
    lastSeq = dut.io.seq.toInt
    pulseAndCheck(oddRel(simTime() + 30000)) // clean capture after the overrun episode
    println(f"[RefTimeTsuSim] tsu PASS  ${residues.size} pulses, residue span [${residues.min}%.3f, ${residues.max}%.3f]")
  }

  // ==========================================================================================
  SimConfig.compile(ExTb()).doSim("exchange", seed = 43) { dut =>
    val rng = new Random(43)
    for (p <- Seq(dut.io.load, dut.io.mTxTrig, dut.io.mRxTrig, dut.io.sTxTrig, dut.io.sRxTrig, dut.io.ack))
      p #= false
    dut.io.sInit #= 0
    dut.clockDomain.forkStimulus(PERIOD)
    dut.clockDomain.waitSampling(4)

    // inject a refTime offset into the slave
    dut.io.sInit #= 123456
    dut.io.load #= true
    dut.clockDomain.waitSampling()
    dut.io.load #= false
    dut.clockDomain.waitSampling(2)
    val tEdge = simTime()
    val truth = (dut.io.refM.toBigInt - dut.io.refS.toBigInt).toDouble // constant: same-domain counters

    def sleepUntil(t: Long): Unit = { assert(t > simTime(), "schedule in the past"); sleep(t - simTime()) }
    def oddRel(t: Long): Long = if ((t - tEdge) % 2 == 0) t + 1 else t
    def pulse(pin: Bool, tRise: Long): Unit = { sleepUntil(tRise); pin #= true; sleep(16000); pin #= false }

    // symmetric one-way delay: not a cycle multiple, parity-even so odd-rel launch ⇒ odd-rel arrival
    val link    = 35 * PERIOD + 138
    val offsets = mutable.ArrayBuffer[Double]()
    for (k <- 0 until 150) {
      val t0 = oddRel(simTime() + 40000 + rng.nextInt(PERIOD.toInt))
      pulse(dut.io.mTxTrig, t0) // SYNC leaves the master…
      pulse(dut.io.sRxTrig, t0 + link) // …and reaches the slave
      val t1 = oddRel(t0 + link + 100 * PERIOD + rng.nextInt(PERIOD.toInt)) // turnaround
      pulse(dut.io.sTxTrig, t1) // DELAY_REQ leaves the slave…
      pulse(dut.io.mRxTrig, t1 + link) // …and reaches the master
      sleep(8 * PERIOD)

      assert(dut.io.allValid.toBoolean, s"exchange $k: not all four TSUs captured")
      val (ts1, ts2, ts3, ts4) =
        (dut.io.t1.toBigInt, dut.io.t2.toBigInt, dut.io.t3.toBigInt, dut.io.t4.toBigInt)
      val offset = ((ts1 - ts2) + (ts4 - ts3)).toDouble / 2 // README §3, symmetric-link identity
      assert((offset - truth).abs <= 2.0, s"exchange $k: offset $offset vs truth $truth — > 2 cycles off")
      offsets += offset

      dut.io.ack #= true; sleep(PERIOD + 1); dut.io.ack #= false
    }
    val mean = offsets.sum / offsets.size
    assert((mean - truth).abs <= 0.5,
      s"mean offset $mean vs injected $truth — averaging does not recover the offset within half a cycle")
    println(f"[RefTimeTsuSim] exchange PASS  ${offsets.size} exchanges, truth=$truth%.1f mean=$mean%.3f " +
      f"spread=[${offsets.min}%.1f, ${offsets.max}%.1f]")
  }

  println("[RefTimeTsuSim] all PASS")
}
