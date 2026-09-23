package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.DebugId
import spinal.lib.bus.tilelink.fabric.MasterBus
import spinal.lib.bus.tilelink.sim.{IdAllocator, IdCallback, MasterAgent}
import spinal.lib.bus.misc.SizeMapping
import riscq.soc.fabric.MemMapFiber
import riscq.soc.link.{EventFifoSink, EventLink, EventSource, ReadoutResultSink, RfLink, SinkSpec}

/**
 * Sign-off for the generic up-link (specs/universal-control/01 §2.4 / P4): two reporters — a
 * `result`-kind source (settled/cleared beats, as the readout decoder emits) and a `fifo`-kind source
 * (edge-like events with a cause time) — are serialised into puts and share one `Flow(RfCmd)` through
 * [[EventLink.merge]] and `linkPipe` stages into their two core-side sinks. Asserts:
 *
 *   - the FIFO sink's `pop` read HALTS until an event is queued, returns the event's first data word
 *     and consumes it; the popped event's `time` and `seq` read back exactly; events arrive in order
 *     with consecutive sequence numbers; `count` reports the occupancy;
 *   - a beat of each tag landing on the SAME cycle reaches its own sink (the arbiter never drops or
 *     misroutes);
 *   - the result sink still rebuilds the level: `res` halts until the settled beat, is idempotent,
 *     and reads the value the beat carried.
 *
 * Run with `mill runMain riscq.soc.sim.EventSinkSim`.
 */
object EventSinkSim extends App {
  val accWidth = 32
  val linkPipe = 4
  // two sinks at the inbox's first two windows (CPU 0x4200 / 0x4220 ⇒ local map 0x00 / 0x20)
  val resSpec  = SinkSpec("res", EventLink.resultKind, 0, EventLink.sinkBase, EventLink.resultDataWidth(accWidth))
  val fifoSpec = SinkSpec("ev", EventLink.fifoKind, 1, EventLink.sinkBase + 0x20, 32)

  case class Dut() extends Component {
    val tlBus = new MasterBus(tilelink.M2sParameters(addressWidth = 16, dataWidth = 32,
      masters = List(tilelink.M2sAgent(name = this, mapping = List(tilelink.M2sSource(
        id = SizeMapping(0, 4), emits = tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(0x40), putFull = tilelink.SizeRange.upTo(0x40),
          putPartial = tilelink.SizeRange.upTo(0x40))))))))
    // reporter 0: the readout-result kind (a level in, settled/cleared beats out)
    val resValid = in port Bool()
    val resSign  = in port Bool()
    val resReal  = in port SInt(accWidth bits)
    val resImag  = in port SInt(accWidth bits)
    // reporter 1: an edge-like event with a 32-bit payload and a cause time
    val evValid = in port Bool()
    val evData  = in port Bits(32 bits)
    val evTime  = in port UInt(32 bits)

    val resSrc = EventLink.resultSource(resValid, resSign, resReal, resImag, accWidth)
    val evFlow = Flow(Bits(32 bits)); evFlow.valid := evValid; evFlow.payload := evData
    val evSrc  = EventSource(EventLink.fifoKind, evFlow, Some(evTime))
    val up     = RfLink.pipe(EventLink.merge(Seq(resSrc, evSrc), Seq(resSpec, fifoSpec)), linkPipe)

    val resultSink = ReadoutResultSink(accWidth, base = resSpec.base)
    val fifoSink   = EventFifoSink(fifoSpec.dataWidth, base = fifoSpec.base, depth = 8)
    resultSink.resultIn << up
    fifoSink.resultIn << up

    val map = MemMapFiber(addressWidth = 16, dataWidth = 32)
    map.addMapping(resultSink.mapping)
    map.addMapping(fifoSink.mapping)
    map.up at 0 of tlBus.node
  }

  SimConfig.compile(Dut()).doSim("eventSink", seed = 7) { dut =>
    val cd = dut.clockDomain
    dut.tlBus.node.bus.a.valid #= false
    dut.resValid #= false; dut.resSign #= false; dut.resReal #= 0; dut.resImag #= 0
    dut.evValid #= false; dut.evData #= 0; dut.evTime #= 0
    cd.forkStimulus(10)
    implicit val idAllocator = new IdAllocator(DebugId.width)
    implicit val idCallback  = new IdCallback
    val agent = new MasterAgent(dut.tlBus.node.bus, cd)
    cd.waitSampling(5)

    // ── events queued before the CPU looks: 3 beats with distinct data/time, the last on the same
    // cycle as the result's settled beat (tag collision on the link) ──
    def event(data: Long, time: Long): Unit = {
      dut.evValid #= true; dut.evData #= data; dut.evTime #= time; cd.waitSampling(); dut.evValid #= false
    }
    event(0x11, 1000); cd.waitSampling(3); event(0x22, 1010); cd.waitSampling(3)
    dut.evValid #= true; dut.evData #= 0x33; dut.evTime #= 1020
    dut.resValid #= true; dut.resSign #= true; dut.resReal #= 12345; dut.resImag #= -678   // settled beat
    cd.waitSampling(); dut.evValid #= false
    cd.waitSampling(linkPipe + 16)   // + the puts' serialisation

    assert(agent.getInt(0, 0x4238) == 3, s"count: expected 3 queued, got ${agent.getInt(0, 0x4238)}")
    for ((d, t, seq) <- Seq((0x11L, 1000L), (0x22L, 1010L), (0x33L, 1020L)).zipWithIndex.map { case ((d, t), i) => (d, t, i) }) {
      val w0 = agent.getInt(0, 0x4220)        // pop: halts until queued, returns data word 0, consumes
      assert(w0 == d, s"pop: expected data 0x${d.toHexString}, got $w0")
      assert(agent.getInt(0, 0x4230) == t, s"time of event $seq: expected $t, got ${agent.getInt(0, 0x4230)}")
      assert(agent.getInt(0, 0x4234) == seq, s"seq: expected $seq, got ${agent.getInt(0, 0x4234)}")
    }
    assert(agent.getInt(0, 0x4238) == 0, "count after three pops must be 0")

    // ── the result sink saw its settled beat (same cycle as event 3): halting res read returns it ──
    assert((agent.getInt(0, 0x4200) & 1) == 1, "res: expected the settled beat's sign")
    assert(agent.getInt(0, 0x4204) == 12345, s"real: got ${agent.getInt(0, 0x4204)}")
    assert(agent.getInt(0, 0x4208).toInt == -678, s"imag: got ${agent.getInt(0, 0x4208)}")
    assert((agent.getInt(0, 0x4200) & 1) == 1, "res read must be idempotent")

    // ── a pop issued BEFORE its event halts until the event lands, then returns it ──
    var popped: Option[BigInt] = None
    val t0 = simTime()
    fork { popped = Some(agent.getInt(0, 0x4220)) }
    cd.waitSampling(20)
    assert(popped.isEmpty, "pop must halt while the queue is empty")
    event(0x44, 2000)
    waitUntil(popped.nonEmpty)
    assert(popped.get == 0x44 && agent.getInt(0, 0x4230) == 2000 && agent.getInt(0, 0x4234) == 3,
      s"late pop: got ${popped.get}, time ${agent.getInt(0, 0x4230)}, seq ${agent.getInt(0, 0x4234)}")

    // ── the cleared beat drops the result level: a res read then halts until the next settled beat ──
    dut.resValid #= false; cd.waitSampling(linkPipe + 10)
    var res2: Option[BigInt] = None
    fork { res2 = Some(agent.getInt(0, 0x4200)) }
    cd.waitSampling(20)
    assert(res2.isEmpty, "res must halt after the cleared beat (a new window is integrating)")
    dut.resSign #= false; dut.resReal #= 7; dut.resImag #= 8; dut.resValid #= true
    waitUntil(res2.nonEmpty)
    assert((res2.get & 1) == 0 && agent.getInt(0, 0x4204) == 7, s"fresh result after re-settle: res2=${res2.get}, real=${agent.getInt(0, 0x4204)}")
    println(s"[EventSinkSim] PASS  two reporters (result + fifo) as puts over one up-link, linkPipe=$linkPipe: " +
      "consume-on-read pop halts/returns/consumes in order with exact time+seq; the same-cycle result beat " +
      "reached its sink; the level sink halts after a cleared beat and returns the next settled value.")
  }
}
