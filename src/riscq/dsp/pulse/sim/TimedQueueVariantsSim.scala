package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp.pulse._

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for the II=1 / latency-0 [[TimedQueue]] variants
 * ([[TimedQueueRegArray]], [[TimedQueueDeadline]]) in `TimedQueueVariants.scala`. Cycle-exact lockstep
 * vs a '''combinational''' model of the wrap-safe due compare: `pop.valid == (time + lead ≥ startTime)`
 * for the current head every cycle, so back-to-back already-due entries drain '''one-per-cycle (II=1)'''.
 * Both variants share [[TimedQueueIo]] (via [[TimedQueueLike]]), so one body drives either. Checks the
 * exact pop cycle, past-`startTime` immediate pop, full-queue backpressure, time
 * wrap-around, and in-order completeness. Run with
 * `mill runMain riscq.dsp.pulse.sim.TimedQueueVariantsSim`.
 */
object TimedQueueVariantsSim extends App {

  val timeWidth = 8
  val depth     = 4
  val mask      = (BigInt(1) << timeWidth) - 1
  val msb       = BigInt(1) << (timeWidth - 1)

  def mk(impl: String, leadTime: Int, timeOffset: Int): TimedQueueLike[UInt] = impl match {
    case "RegArray" => TimedQueueRegArray(UInt(timeWidth bits), timeWidth, depth, leadTime, timeOffset)
    case "Deadline" => TimedQueueDeadline(UInt(timeWidth bits), timeWidth, depth, leadTime, timeOffset)
  }

  def run(impl: String, label: String, leadTime: Int, timeOffset: Int, time0: BigInt,
          schedule: Int => Option[(BigInt, BigInt)], cycles: Int,
          backpressureFrom: Int = -1): Unit = {
    SimConfig.compile(mk(impl, leadTime, timeOffset)).doSim(s"${impl}_$label", seed = 42) { dut =>
      val io = dut.io
      dut.clockDomain.forkStimulus(10)
      io.push.valid #= false
      dut.clockDomain.waitSampling(2)

      val mq     = mutable.Queue[(BigInt, BigInt)]()
      val pushed = mutable.ArrayBuffer[BigInt]()
      val popped = mutable.ArrayBuffer[BigInt]()
      var time   = time0 & mask

      def due(): Boolean = mq.nonEmpty && {
        val a = (time + leadTime + timeOffset) & mask
        (((a - mq.head._2) & mask) & msb) == 0
      }

      for (c <- 0 until cycles) {
        val pushReq = schedule(c)
        io.time #= time
        io.push.valid #= pushReq.isDefined
        pushReq.foreach { case (d, s) => io.push.payload.data #= d; io.push.payload.startTime #= s }
        sleep(1)

        val popV   = io.pop.valid.toBoolean
        val pReady = io.push.ready.toBoolean
        val expPop = due()
        assert(popV == expPop,
          s"[$impl/$label] cycle $c time=$time: pop.valid=$popV but model expects $expPop (head=${mq.headOption})")
        if (popV) assert(io.pop.payload.toBigInt == mq.head._1,
          s"[$impl/$label] cycle $c: pop data ${io.pop.payload.toBigInt} != head ${mq.head._1}")
        if (backpressureFrom >= 0 && c >= backpressureFrom && !expPop)
          assert(pReady == (mq.size < depth),
            s"[$impl/$label] cycle $c: push.ready=$pReady but model size=${mq.size}, depth=$depth")

        dut.clockDomain.waitSampling()
        if (expPop) popped += mq.dequeue()._1
        pushReq.foreach { case (d, s) => if (pReady) { mq.enqueue((d, s)); pushed += d } }
        time = (time + 1) & mask
      }
      assert(popped.toSeq == pushed.toSeq,
        s"[$impl/$label] pop order ${popped.toSeq} != push order ${pushed.toSeq}")
      println(f"[TimedQueueVariantsSim] PASS  $impl%-9s $label%-13s lead=$leadTime off=$timeOffset: " +
        f"${pushed.size} pushed / ${popped.size} popped, exact-cycle (II=1) + in-order + wrap-safe.")
      simSuccess()
    }
  }

  for (impl <- Seq("RegArray", "Deadline")) {
    run(impl, "clean-timing", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => Map(0 -> (BigInt(10), BigInt(20)), 1 -> (BigInt(11), BigInt(40)),
                          2 -> (BigInt(12), BigInt(60)), 3 -> (BigInt(13), BigInt(80))).get(c),
      cycles = 100)
    run(impl, "past-due", leadTime = 3, timeOffset = 0, time0 = 50,
      schedule = c => Map(0 -> (BigInt(20), BigInt(5)), 1 -> (BigInt(21), BigInt(6)),
                          2 -> (BigInt(22), BigInt(7))).get(c),
      cycles = 30)
    run(impl, "backpressure", leadTime = 3, timeOffset = 0, time0 = 0,
      schedule = c => if (c < 8) Some((BigInt(100 + c), BigInt(15))) else None,
      cycles = 25, backpressureFrom = 0)
    run(impl, "wrap", leadTime = 3, timeOffset = 0, time0 = mask - 4,
      schedule = c => Map(0 -> (BigInt(30), BigInt(2)), 1 -> (BigInt(31), BigInt(10)),
                          2 -> (BigInt(32), BigInt(20))).get(c),
      cycles = 60)
    run(impl, "offset", leadTime = 2, timeOffset = 5, time0 = 0,
      schedule = c => Map(0 -> (BigInt(7), BigInt(30)), 1 -> (BigInt(8), BigInt(55))).get(c),
      cycles = 80)
    val rng = new Random(1)
    run(impl, "random", leadTime = 3, timeOffset = 0, time0 = 100,
      schedule = c => if (c < 3800 && rng.nextInt(3) == 0) {
        val now = (100 + c) & mask.toInt
        Some((BigInt(c & 0xff), BigInt((now + rng.nextInt(40)) & mask.toInt)))
      } else None,
      cycles = 4000)
  }

  println("[TimedQueueVariantsSim] all scenarios PASS")
}
