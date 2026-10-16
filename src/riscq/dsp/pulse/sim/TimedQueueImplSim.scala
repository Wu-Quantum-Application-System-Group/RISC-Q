package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp.pulse._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for the non-default [[TimedQueue]] implementations
 * ([[TimedQueueImpl.Srl]] / [[TimedQueueImpl.Shadow]] / [[TimedQueueImpl.SrlShadow]] — the default
 * [[TimedQueueImpl.RegHead]] is covered by [[TimedQueueSim]]). As there, a '''cycle-accurate
 * mirror''' of each impl's FSM runs in lock-step and `pop.valid`/`pop.payload` are asserted equal
 * to the DUT every cycle; the mirrors always evaluate the FSM's ORIGINAL pop expression (with the
 * redundant valid terms), so the DUTs' baked-in lean forms matching every cycle is the standing
 * bit-exactness proof. The well-spaced scenarios also assert each pop lands at exactly
 * `startTime − leadTime − timeOffset` (all impls share the `lead + 3` push margin); the rest cover
 * past-due drain (Srl II=2, Shadow/SrlShadow II=3), full-queue backpressure + drain + refill, time
 * wrap-around, a calibration `timeOffset`, the tightest exact-pop margin, push-when-due, and a long
 * randomized schedule. `RISCQ_TQ_DEPTH` overrides the queue depth (e.g. 32 re-proves the deep
 * config; the overfill scenario adapts). Run with
 * `mill runMain riscq.dsp.pulse.sim.TimedQueueImplSim`.
 */
object TimedQueueImplSim extends App {

  val timeWidth = 8
  val depth     = sys.env.getOrElse("RISCQ_TQ_DEPTH", "4").toInt
  val mask      = (BigInt(1) << timeWidth) - 1
  val msb       = BigInt(1) << (timeWidth - 1)

  /** wrap-safe geq with the +1 register-cancel, on the precomputed deadline. */
  def due(time: BigInt, startTime: BigInt, leadTime: Int, timeOffset: Int): Boolean = {
    val dl = (startTime - leadTime - timeOffset - 1) & mask
    (((time - dl) & mask) & msb) == 0
  }

  /** Cycle-accurate mirror of one impl's FSM. Called once per cycle *before* the clock edge with
   *  the current `time` and this cycle's accepted push; returns `(expPop, popData)` for the assert,
   *  then commits its own state. `fifoSize` exposes the FIFO-proper occupancy for the backpressure
   *  check. */
  trait Mirror {
    def cycle(time: BigInt, pushAccepted: Option[(BigInt, BigInt)]): (Boolean, Option[BigInt])
    def commit(): Unit
    def fifoSize: Int
    def pushedAll: Seq[BigInt]
    def poppedAll: Seq[BigInt]
  }

  /** Mirror for [[TimedQueueImpl.Srl]] — the RegHead FSM (m2sPipe head stage + registered compare
   *  + 1-cycle blank), identical to [[TimedQueueSim]]'s regHead model. Original pop expression:
   *  `stageValid && timeUp && !blank`. */
  class RegHeadMirror(leadTime: Int, timeOffset: Int) extends Mirror {
    val fifoQ  = mutable.Queue[(BigInt, BigInt)]()
    var stage  = Option.empty[(BigInt, BigInt)]
    val pushed = mutable.ArrayBuffer[BigInt]()
    val popped = mutable.ArrayBuffer[BigInt]()
    var timeUp = false
    var blank  = false

    var expPop                         = false
    var push: Option[(BigInt, BigInt)] = None
    var nextTimeUp                     = false

    def cycle(time: BigInt, pushAccepted: Option[(BigInt, BigInt)]): (Boolean, Option[BigInt]) = {
      expPop     = stage.isDefined && timeUp && !blank
      push       = pushAccepted
      nextTimeUp = stage.isDefined && due(time, stage.get._2, leadTime, timeOffset)
      (expPop, stage.map(_._1))
    }
    def commit(): Unit = {
      val inputReady = stage.isEmpty || expPop
      if (expPop) popped += stage.get._1
      if (inputReady) stage = if (fifoQ.nonEmpty) Some(fifoQ.dequeue()) else None
      push.foreach { case (d, s) => fifoQ.enqueue((d, s)); pushed += d }
      blank  = expPop
      timeUp = nextTimeUp
    }
    def fifoSize  = fifoQ.size
    def pushedAll = pushed.toSeq
    def poppedAll = popped.toSeq
  }

  /** Mirror for [[TimedQueueImpl.Shadow]] / [[TimedQueueImpl.SrlShadow]] — CE-free shadow of the
   *  FIFO head (one cycle behind), registered compare, 2-cycle blank. Original pop expression:
   *  `timeUp && !blank && !blank2` (timeUp already carries the shadowed valid). */
  class ShadowMirror(leadTime: Int, timeOffset: Int) extends Mirror {
    val fifoQ  = mutable.Queue[(BigInt, BigInt)]()
    var shadow = Option.empty[BigInt] // the head's startTime as of the previous cycle
    val pushed = mutable.ArrayBuffer[BigInt]()
    val popped = mutable.ArrayBuffer[BigInt]()
    var timeUp = false
    var blank  = false
    var blank2 = false

    var expPop                         = false
    var push: Option[(BigInt, BigInt)] = None
    var nextTimeUp                     = false
    var nextShadow                     = Option.empty[BigInt]

    def cycle(time: BigInt, pushAccepted: Option[(BigInt, BigInt)]): (Boolean, Option[BigInt]) = {
      expPop     = timeUp && !blank && !blank2
      push       = pushAccepted
      nextTimeUp = shadow.exists(s => due(time, s, leadTime, timeOffset))
      nextShadow = fifoQ.headOption.map(_._2) // shadow tracks the head this cycle reads, pre-pop
      (expPop, fifoQ.headOption.map(_._1))    // pop data is the storage head, consumed at pop
    }
    def commit(): Unit = {
      if (expPop) popped += fifoQ.dequeue()._1
      push.foreach { case (d, s) => fifoQ.enqueue((d, s)); pushed += d }
      blank2 = blank
      blank  = expPop
      timeUp = nextTimeUp
      shadow = nextShadow
    }
    def fifoSize  = fifoQ.size
    def pushedAll = pushed.toSeq
    def poppedAll = popped.toSeq
  }

  val impls: Seq[(String, TimedQueueImpl)] = Seq(
    "Srl"       -> TimedQueueImpl.Srl,
    "Shadow"    -> TimedQueueImpl.Shadow,
    "SrlShadow" -> TimedQueueImpl.SrlShadow)

  def mkMirror(impl: TimedQueueImpl, leadTime: Int, timeOffset: Int): Mirror = impl match {
    case TimedQueueImpl.Srl => new RegHeadMirror(leadTime, timeOffset)
    case _                  => new ShadowMirror(leadTime, timeOffset)
  }

  def run(name: String, impl: TimedQueueImpl, label: String, leadTime: Int, timeOffset: Int,
          time0: BigInt, schedule: Int => Option[(BigInt, BigInt)], cycles: Int,
          backpressureFrom: Int = -1, exactPop: Boolean = false): Unit = {
    SimConfig.compile(TimedQueue(UInt(timeWidth bits), timeWidth, depth, leadTime, timeOffset,
        impl = impl))
      .doSim(s"${name}_$label", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.push.valid #= false
      dut.clockDomain.waitSampling(2)

      val m       = mkMirror(impl, leadTime, timeOffset)
      val startOf = mutable.Map[BigInt, BigInt]()
      var time    = time0 & mask

      for (c <- 0 until cycles) {
        val pushReq = schedule(c)
        dut.io.time #= time
        dut.io.push.valid #= pushReq.isDefined
        pushReq.foreach { case (d, s) =>
          dut.io.push.payload.data #= d
          dut.io.push.payload.startTime #= s
          startOf(d) = s
        }
        sleep(1) // settle the combinational pop / push.ready

        val pReady = dut.io.push.ready.toBoolean
        val (expPop, expData) = m.cycle(time, pushReq.filter(_ => pReady))
        val popV = dut.io.pop.valid.toBoolean
        assert(popV == expPop,
          s"[$name/$label] cycle $c time=$time: pop.valid=$popV but mirror expects $expPop")
        if (popV) {
          assert(dut.io.pop.payload.toBigInt == expData.get,
            s"[$name/$label] cycle $c: pop data ${dut.io.pop.payload.toBigInt} != mirror ${expData.get}")
          if (exactPop) {
            val expT = (startOf(expData.get) - leadTime - timeOffset) & mask
            assert(time == expT,
              s"[$name/$label] cycle $c: pop of ${expData.get} at time=$time, expected startTime−lead−off=$expT")
          }
        }
        if (backpressureFrom >= 0 && c >= backpressureFrom && !expPop)
          assert(pReady == (m.fifoSize < depth),
            s"[$name/$label] cycle $c: push.ready=$pReady but mirror fifo size=${m.fifoSize}, depth=$depth")

        dut.clockDomain.waitSampling()
        m.commit()
        time = (time + 1) & mask
      }
      assert(m.poppedAll == m.pushedAll,
        s"[$name/$label] pop order ${m.poppedAll} != push order ${m.pushedAll}")
      println(f"[TimedQueueImplSim] PASS  $name%-10s $label%-14s lead=$leadTime off=$timeOffset depth=$depth: " +
        f"${m.pushedAll.size} pushed / ${m.poppedAll.size} popped, cycle-exact FSM + in-order + wrap-safe.")
      simSuccess()
    }
  }

  for ((name, impl) <- impls) {
    def go(label: String, leadTime: Int, timeOffset: Int, time0: BigInt,
           schedule: Int => Option[(BigInt, BigInt)], cycles: Int,
           backpressureFrom: Int = -1, exactPop: Boolean = false): Unit =
      run(name, impl, label, leadTime, timeOffset, time0, schedule, cycles, backpressureFrom, exactPop)

    // --- A: clean timing — well-spaced future startTimes; each pops at exactly startTime − lead ---
    go("clean-timing", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(0 -> (BigInt(10), BigInt(20)), 1 -> (BigInt(11), BigInt(40)),
                          2 -> (BigInt(12), BigInt(60)), 3 -> (BigInt(13), BigInt(80))).get(c),
      cycles = 100, exactPop = true)

    // --- B: past startTime — already-due entries drain at the impl's II ---
    go("past-due", leadTime = 3, timeOffset = 0, time0 = 50,
      schedule = c => Map(0 -> (BigInt(20), BigInt(5)), 1 -> (BigInt(21), BigInt(6)),
                          2 -> (BigInt(22), BigInt(7))).get(c),
      cycles = 40)

    // --- C: backpressure + drain + refill — overfill by 4 whatever the depth, drain, second wave ---
    val fillN = depth + 4
    val c2    = 196 + 3 * depth + 40 // past the II=3 worst-case drain of the first wave
    go("backpressure", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => if (c < fillN) Some((BigInt((100 + c) & 0xff), BigInt(200)))
                      else if (c >= c2 && c < c2 + 3) Some((BigInt((60 + c) & 0xff), BigInt((c2 + 25) & mask.toInt)))
                      else None,
      cycles = c2 + 120, backpressureFrom = 0)

    // --- D: time wrap — counter wraps 2^tw; startTimes straddle the wrap ---
    go("wrap", leadTime = 3, timeOffset = 0, time0 = mask - 4,
      schedule = c => Map(0 -> (BigInt(30), BigInt(2)), 1 -> (BigInt(31), BigInt(10)),
                          2 -> (BigInt(32), BigInt(20))).get(c),
      cycles = 60)

    // --- E: timeOffset calibration ---
    go("offset", leadTime = 2, timeOffset = 5, time0 = 0,
      schedule = c => Map(0 -> (BigInt(7), BigInt(30)), 1 -> (BigInt(8), BigInt(55))).get(c),
      cycles = 80, exactPop = true)

    // --- G: tightest exact-pop margin — pushed exactly leadTime+3 cycles before startTime ---
    go("tight-margin", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(2 -> (BigInt(40), BigInt(2 + 3 + 3)),
                          20 -> (BigInt(41), BigInt(20 + 3 + 3))).get(c),
      cycles = 40, exactPop = true)

    // --- H: push-when-due — a due-now entry pushed the same cycle an earlier entry pops ---
    go("push-when-due", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(0 -> (BigInt(50), BigInt(8)),
                          5 -> (BigInt(51), BigInt(5))).get(c),
      cycles = 40)

    // --- F: randomized near-future schedule, honouring backpressure ---
    val rng = new Random(1)
    go("random", leadTime = 3, timeOffset = 0, time0 = 100,
      schedule = c => if (c < 3700 && rng.nextInt(4) == 0) {
        val now = (100 + c) & mask.toInt
        Some((BigInt(c & 0xff), BigInt((now + 6 + rng.nextInt(40)) & mask.toInt)))
      } else None,
      cycles = 4000)
  }

  println("[TimedQueueImplSim] all impls × all scenarios PASS (lean pop bit-exact vs each original expr)")
}
