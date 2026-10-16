package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp.pulse._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for the active RISC-Q-style [[TimedQueue]]. A '''cycle-accurate mirror'''
 * of the hardware FSM runs in lock-step:
 * the registered compare `timeUp = RegNext(head.valid && (time + lead + 1 ≥ startTime))` and the
 * fire-once `blank = RegNext(pop)` are modelled exactly, so `pop = head.valid && timeUp && !blank` is
 * asserted equal to the DUT every cycle. This verifies the precise pop cycle (`= startTime − leadTime`
 * for well-spaced schedules, the `+1` cancelling the compare register), the wrap-safe geq, time
 * wrap-around, full-queue backpressure, and in-order completeness. Back-to-back already-due entries
 * drain one every two cycles (the edge-detect blank — II=1 is the variants' job).
 *
 * Every scenario runs over both `regHead` flag values. The model always evaluates
 * the ORIGINAL pop expression `head.valid && timeUp && !blank`; the DUT uses the baked-in lean form
 * `timeUp && !blank` and must still match the model every cycle — that is the standing bit-exactness
 * proof that dropping the `head.valid` term is sound. `regHead` adds the m2sPipe head stage to the mirror (+1 cycle push→head visibility,
 * capture-on-pop refill), and the well-spaced scenarios assert the pop cycle is still exactly
 * `startTime − leadTime − timeOffset`. Run with `mill runMain riscq.dsp.pulse.sim.TimedQueueSim`.
 */
object TimedQueueSim extends App {

  val timeWidth = 8
  val depth     = 4
  val mask      = (BigInt(1) << timeWidth) - 1
  val msb       = BigInt(1) << (timeWidth - 1)

  /** wrap-safe geq: (time + lead + offset + 1) ≥ startTime, signed-difference msb test. */
  def geqPlus1(time: BigInt, startTime: BigInt, leadTime: Int, timeOffset: Int): Boolean = {
    val a = (time + leadTime + timeOffset + 1) & mask
    (((a - startTime) & mask) & msb) == 0
  }

  /** One scripted/random run over `cycles`, time starting at `time0` (mod 2^tw).
   *  `exactPop = true` asserts each pop lands at exactly `startTime − leadTime − timeOffset`
   *  (valid for well-spaced schedules pushed ≥ leadTime+3 cycles before their startTime). */
  def run(label: String, leadTime: Int, timeOffset: Int, time0: BigInt,
          schedule: Int => Option[(BigInt, BigInt)], cycles: Int,
          regHead: Boolean,
          backpressureFrom: Int = -1, exactPop: Boolean = false): Unit = {
    val flags = s"regHead=$regHead"
    SimConfig.compile(TimedQueue(UInt(timeWidth bits), timeWidth, depth, leadTime, timeOffset,
        regHead = regHead))
      .doSim(s"$label-$flags", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.push.valid #= false
      dut.clockDomain.waitSampling(2)

      val fifoQ  = mutable.Queue[(BigInt, BigInt)]() // (data, startTime) inside the FIFO proper
      var stage  = Option.empty[(BigInt, BigInt)]    // the m2sPipe head stage (regHead only)
      val pushed = mutable.ArrayBuffer[BigInt]()
      val popped = mutable.ArrayBuffer[BigInt]()
      val startOf = mutable.Map[BigInt, BigInt]()    // data -> startTime (for the exact-pop check)
      var time   = time0 & mask
      var timeUp = false // model of the registered compare
      var blank  = false // model of RegNext(pop) — the fire-once blank

      // the head the due test reads this cycle: the stage register (regHead) or the FIFO's async head.
      def headOpt: Option[(BigInt, BigInt)] = if (regHead) stage else fifoQ.headOption

      for (c <- 0 until cycles) {
        val pushReq = schedule(c)
        dut.io.time #= time
        dut.io.push.valid #= pushReq.isDefined
        pushReq.foreach { case (d, s) =>
          dut.io.push.payload.data #= d
          dut.io.push.payload.startTime #= s
        }
        sleep(1) // settle the combinational pop / push.ready

        val head      = headOpt
        val headValid = head.isDefined
        // ALWAYS the original expression — the baked-in lean DUT must still match it (bit-exactness proof).
        val expPop = headValid && timeUp && !blank
        val popV   = dut.io.pop.valid.toBoolean
        val pReady = dut.io.push.ready.toBoolean
        assert(popV == expPop,
          s"[$label $flags] cycle $c time=$time: pop.valid=$popV but model expects $expPop (head=$head)")
        if (popV) {
          assert(dut.io.pop.payload.toBigInt == head.get._1,
            s"[$label $flags] cycle $c: pop data ${dut.io.pop.payload.toBigInt} != head ${head.get._1}")
          if (exactPop) {
            val expT = (head.get._2 - leadTime - timeOffset) & mask
            assert(time == expT,
              s"[$label $flags] cycle $c: pop of ${head.get._1} at time=$time, expected startTime−lead−off=$expT")
          }
        }
        if (backpressureFrom >= 0 && c >= backpressureFrom && !expPop)
          assert(pReady == (fifoQ.size < depth),
            s"[$label $flags] cycle $c: push.ready=$pReady but model fifo size=${fifoQ.size}, depth=$depth")

        // next-cycle registers, computed from the head this cycle reads (pre-dequeue).
        val nextTimeUp = headValid && geqPlus1(time, head.get._2, leadTime, timeOffset)
        val nextBlank  = expPop

        dut.clockDomain.waitSampling()
        // post-edge state update. Order matters: pop, then the stage capture (reads the FIFO as it was
        // DURING the cycle), then the push append (visible to the model only from the next cycle).
        if (regHead) {
          val inputReady = stage.isEmpty || expPop        // m2sPipe: capture when empty or being popped
          if (expPop) popped += stage.get._1
          if (inputReady) stage = if (fifoQ.nonEmpty) Some(fifoQ.dequeue()) else None
        } else {
          if (expPop) popped += fifoQ.dequeue()._1
        }
        pushReq.foreach { case (d, s) =>
          if (pReady) { fifoQ.enqueue((d, s)); pushed += d; startOf(d) = s }
        }
        timeUp = nextTimeUp; blank = nextBlank
        time = (time + 1) & mask
      }
      assert(popped.toSeq == pushed.toSeq,
        s"[$label $flags] pop order ${popped.toSeq} != push order ${pushed.toSeq}")
      println(f"[TimedQueueSim] PASS  $label%-22s $flags%-28s lead=$leadTime off=$timeOffset: " +
        f"${pushed.size} pushed / ${popped.size} popped, cycle-exact FSM + in-order + wrap-safe.")
      simSuccess()
    }
  }

  for (regHead <- List(false, true)) {
    def go(label: String, leadTime: Int, timeOffset: Int, time0: BigInt,
           schedule: Int => Option[(BigInt, BigInt)], cycles: Int,
           backpressureFrom: Int = -1, exactPop: Boolean = false): Unit =
      run(label, leadTime, timeOffset, time0, schedule, cycles, regHead,
        backpressureFrom, exactPop)

    // --- A: clean timing — 4 monotonic, well-spaced future startTimes; each pops at startTime−lead
    //     and drains the queue to empty (pop-to-empty corner) ---
    go("clean-timing", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(0 -> (BigInt(10), BigInt(20)), 1 -> (BigInt(11), BigInt(40)),
                          2 -> (BigInt(12), BigInt(60)), 3 -> (BigInt(13), BigInt(80))).get(c),
      cycles = 100, exactPop = true)

    // --- B: past startTime — pushed when already due ⇒ each drains as it reaches the head (II=2) ---
    go("past-due", leadTime = 3, timeOffset = 0, time0 = 50,
      schedule = c => Map(0 -> (BigInt(20), BigInt(5)),
                          1 -> (BigInt(21), BigInt(6)),
                          2 -> (BigInt(22), BigInt(7))).get(c),
      cycles = 30)

    // --- C: backpressure + refill — fill a queue that can't pop yet (start 15 > time+lead), let it
    //     drain to empty, then push a second wave (full/refill corner) ---
    go("backpressure", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => if (c < 8) Some((BigInt(100 + c), BigInt(15)))
                      else if (c >= 30 && c < 33) Some((BigInt(200 + c), BigInt(45)))
                      else None,
      cycles = 60, backpressureFrom = 0)

    // --- D: time wrap — counter starts near 2^tw and wraps; startTimes straddle the wrap ---
    go("wrap", leadTime = 3, timeOffset = 0, time0 = mask - 4,
      schedule = c => Map(0 -> (BigInt(30), BigInt(2)),
                          1 -> (BigInt(31), BigInt(10)),
                          2 -> (BigInt(32), BigInt(20))).get(c),
      cycles = 60)

    // --- E: timeOffset — a calibration offset that shifts every due time earlier ---
    go("offset", leadTime = 2, timeOffset = 5, time0 = 0,
      schedule = c => Map(0 -> (BigInt(7), BigInt(30)), 1 -> (BigInt(8), BigInt(55))).get(c),
      cycles = 80, exactPop = true)

    // --- G: tightest exact-pop margin — pushed exactly leadTime+3 cycles before startTime, the
    //     documented bound that still pops at startTime−lead even through the regHead stage ---
    go("tight-margin", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(2 -> (BigInt(40), BigInt(2 + 3 + 3)),
                          20 -> (BigInt(41), BigInt(20 + 3 + 3))).get(c),
      cycles = 40, exactPop = true)

    // --- H: push-when-due — a due-now entry pushed the same cycle an earlier entry pops ---
    go("push-when-due", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(0 -> (BigInt(50), BigInt(8)),
                          5 -> (BigInt(51), BigInt(5))).get(c),   // pushed at c=5, already due
      cycles = 40)

    // --- F: randomized near-future schedule (within the ±2^(tw-1) wrap window), honouring backpressure ---
    val rng = new Random(1)
    go("random", leadTime = 3, timeOffset = 0, time0 = 100,
      schedule = c => if (c < 3700 && rng.nextInt(4) == 0) { // sparser pushes + early stop so it drains
        val now = (100 + c) & mask.toInt
        Some((BigInt(c & 0xff), BigInt((now + 6 + rng.nextInt(40)) & mask.toInt))) // ≥ a few cycles out
      } else None,
      cycles = 4000)
  }

  println("[TimedQueueSim] all scenarios × both regHead values PASS (baked-in lean pop bit-exact vs the original expr)")
}
