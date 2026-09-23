package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.soc.dio.TimedDio
import riscq.soc.link.RfCmd

/**
 * Sign-off for [[TimedDio]] (universal-control/01 P5 / neutral-atom T3): posted writes program two
 * slots (`set` / `clear` of line 0, dur 10) and a train `play(set, t0)`, `fire(clear)`, `fire(set)`,
 * `fire(clear)`; the output must rise at local time t0 and toggle every 10 batches EXACTLY (the
 * `startTime` auto-advance), a back-to-back pair at the shortest hold (dur 2) lands two cycles apart,
 * and every input edge posts an event whose data is `{changed, levels}` and whose time is the batch
 * the changed sample was taken. Run with `mill runMain riscq.soc.sim.TimedDioSim`.
 */
object TimedDioSim extends App {
  case class Dut() extends Component {
    val dio = TimedDio(slots = 4)
    val cmd  = slave port Flow(RfCmd(16))
    val time = in port UInt(32 bits)
    val dout = out port Bits(16 bits)
    val din  = in port Bits(16 bits)
    val evValid = out port Bool()
    val evData  = out port Bits(32 bits)
    val evTime  = out port UInt(32 bits)
    dio.io.cmd << cmd; dio.io.timeBcast := time; dout := dio.io.dout; dio.io.din := din
    evValid := dio.io.event.valid; evData := dio.io.event.payload; evTime := dio.io.eventTime
    val localTime = out port UInt(32 bits)
    localTime := dio.io.time
  }

  SimConfig.compile(Dut()).doSim("timedDio", seed = 3) { dut =>
    val cd = dut.clockDomain
    var t = 0L
    dut.cmd.valid #= false; dut.time #= 0; dut.din #= 0
    cd.forkStimulus(10)
    fork { while (true) { dut.time #= t; cd.waitSampling(); t += 1 } }
    def write(addr: Int, data: Long): Unit = {
      dut.cmd.valid #= true; dut.cmd.payload.address #= addr; dut.cmd.payload.data #= data
      cd.waitSampling(); dut.cmd.valid #= false
    }
    def slot(i: Int, mask: Int, value: Int, dur: Int): Unit = {
      val b = (i + 1) * 0x10
      write(b + 0, mask.toLong << 16); write(b + 4, value.toLong << 16); write(b + 8, 0); write(b + 12, dur.toLong << 16)
    }
    cd.waitSampling(5)
    slot(0, 0x0001, 0x0001, 10)   // set line 0, hold 10
    slot(1, 0x0001, 0x0000, 10)   // clear line 0, hold 10
    slot(2, 0x0002, 0x0002, 2)    // set line 1, hold 2 (the shortest hold: the queue pops at most every other cycle)
    slot(3, 0x0002, 0x0000, 2)    // clear line 1, hold 2

    // record every output change with the local time it happened at
    val changes = scala.collection.mutable.ArrayBuffer[(Long, Int)]()
    var last = 0
    fork { while (true) { cd.waitSampling(); val v = dut.dout.toInt; if (v != last) { changes += ((dut.localTime.toLong, v)); last = v } } }

    val t0 = t + 60
    write(0x4100, t0); write(0x0, 0); write(0x0, 1); write(0x0, 0); write(0x0, 1)   // play(set,t0), clear, set, clear
    // the queue holds 4 scheduled entries (TRAIN_AHEAD): the next pair waits until the train has played
    waitUntil(dut.localTime.toLong >= t0 + 32)
    val t1 = t0 + 60
    write(0x4100, t1); write(0x0, 2); write(0x0, 3)                                   // the 2-batch pair
    cd.waitSampling(120)
    val expect = Seq((t0, 1), (t0 + 10, 0), (t0 + 20, 1), (t0 + 30, 0), (t1, 2), (t1 + 2, 0))
    assert(changes.toSeq == expect, s"output edges ${changes.toSeq} != expected $expect")

    // input edges: two changes at known local times
    val events = scala.collection.mutable.ArrayBuffer[(Long, Long)]()
    fork { while (true) { cd.waitSampling(); if (dut.evValid.toBoolean) events += ((dut.evData.toLong, dut.evTime.toLong)) } }
    cd.waitSampling(3)
    // an edge applied while the local time reads L is sampled on the next cycle and stamped L + 1
    val tin1 = dut.localTime.toLong + 1; dut.din #= 0x0005; cd.waitSampling(20)
    val tin2 = dut.localTime.toLong + 1; dut.din #= 0x0004; cd.waitSampling(20)
    val exp = Seq(((0x0005L << 16) | 0x0005L, tin1), ((0x0001L << 16) | 0x0004L, tin2))
    assert(events.toSeq == exp, s"input events ${events.toSeq.map { case (d, tt) => (d.toHexString, tt) }} != expected ${exp.map { case (d, tt) => (d.toHexString, tt) }}")
    println(s"[TimedDioSim] PASS  play+3 fires toggled line 0 at t0, t0+10, t0+20, t0+30 exactly (auto-advance); " +
      "a dur-2 pair landed 2 cycles apart; both input edges posted {changed, levels} with the sample's batch time.")
  }
}
