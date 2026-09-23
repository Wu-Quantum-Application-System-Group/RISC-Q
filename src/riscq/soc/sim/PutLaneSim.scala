package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.soc.link.{EventLink, PutFrame, PutHub, PutLane, RfCmd}
import riscq.soc.spec.SocSpecMap

import scala.collection.mutable.ArrayBuffer

/**
 * Sign-off for the two-board put network (specs/cross-core/02 §5, R4): two [[PutHub]]s — board 0 the
 * barrier root, board 1 — joined by two [[PutLane]]s (the WR TX/RX PCS pairs) cross-wired at the raw
 * 20-bit word level, with the host marker path idle. Asserts:
 *
 *   - a bit published on board 1 reaches board 0's broadcast (and vice versa) with the same group word;
 *   - a barrier with members on both boards releases exactly once per board, to exactly the arrivers,
 *     with the SAME time value on both boards (the root's stamp);
 *   - a unicast to a core on the other board lands there with mask `1 << core`, untouched;
 *   - ordering across the lane: a bit published on board 1 before its arrival is re-put on board 0
 *     before board 0's release beat, and board 0's bit precedes the release frame on board 1.
 *
 * Run with `mill runMain riscq.soc.sim.PutLaneSim`.
 */
object PutLaneSim extends App {
  val cores = 3

  case class Dut() extends Component {
    val refCd = ClockDomain.external("ref", config = ClockDomainConfig(resetKind = BOOT))
    val rxCd  = ClockDomain.external("rx", config = ClockDomainConfig(resetKind = BOOT))
    val hubs  = Seq(PutHub(cores = cores, board = 0, boards = 2), PutHub(cores = cores, board = 1, boards = 2))
    val lanes = Seq.fill(2)(PutLane(refCd, rxCd, clockDomain, calThreshLog2 = 8))
    val puts  = Vec(Vec(slave port Flow(RfCmd(SocSpecMap.putAddrWidth)), cores), 2)
    val time  = Vec(in port UInt(32 bits), 2)
    val bcast = Vec(master port cloneOf(hubs(0).out), 2)
    for (i <- 0 until 2) {
      for (c <- 0 until cores) hubs(i).in(c) << puts(i)(c)
      hubs(i).time := time(i)
      bcast(i) << hubs(i).out
      lanes(i).putTx << hubs(i).laneOut
      hubs(i).laneIn << lanes(i).putRx
      lanes(i).hostTx.valid := False
      lanes(i).hostTx.payload.assignDontCare()
      lanes(i).hostRx.ready := True
      lanes(i).tx.pcs.io.calMode := False
    }
    // the raw words cross directly (bit offset 0 by construction), a few cycles of lane delay each way
    val wire = rxCd { new Area {
      def cross(w: Bits): Bits = { val r = RegNext(w); r.addTag(crossClockDomain); Delay(r, 2) }  // ref → rx, sim only
      lanes(1).rx.pcs.io.rxDataRaw := cross(lanes(0).tx.pcs.io.txDataRaw)
      lanes(0).rx.pcs.io.rxDataRaw := cross(lanes(1).tx.pcs.io.txDataRaw)
    }}
  }

  SimConfig.compile(Dut()).doSim("putLane", seed = 5) { dut =>
    val cd = dut.clockDomain
    for (i <- 0 until 2; c <- 0 until cores) dut.puts(i)(c).valid #= false
    dut.time(0) #= 0; dut.time(1) #= 0
    cd.forkStimulus(10)
    dut.refCd.forkStimulus(16)
    fork { sleep(5); dut.rxCd.forkStimulus(16) }
    var t = 5000L
    fork { while (true) { cd.waitSampling(); t += 1; dut.time(0) #= t; dut.time(1) #= t + 7 } }   // board 1's clock differs: the stamp must be the root's

    val beats = Seq.fill(2)(ArrayBuffer[(Int, Int, Long)]())
    cd.onSamplings {
      for (i <- 0 until 2) if (dut.bcast(i).valid.toBoolean)
        beats(i) += ((dut.bcast(i).payload.mask.toInt, dut.bcast(i).payload.put.address.toInt, dut.bcast(i).payload.put.data.toLong))
    }
    def put(board: Int, core: Int, node: Int, offset: Int, data: Long): Unit = {
      dut.puts(board)(core).valid #= true
      dut.puts(board)(core).payload.address #= (BigInt(node) << 16) + offset
      dut.puts(board)(core).payload.data #= data
      cd.waitSampling()
      dut.puts(board)(core).valid #= false
    }
    def settle(): Unit = cd.waitSampling(400)   // PCS bring-up / lane latency at the slow ref clock
    val all = (1 << cores) - 1
    cd.waitSampling(300)                        // the RX PCS needs commas before it delivers frames

    // ── 1. a publish on each board reaches both boards' broadcasts ──
    put(1, 2, SocSpecMap.groupNode(0), 0, (5 << 1) | 1); settle()
    put(0, 0, SocSpecMap.groupNode(0), 0, (1 << 1) | 1); settle()
    val word = (1L << 5) | (1L << 1)
    for (i <- 0 until 2) {
      val g0 = beats(i).filter(_._2 == EventLink.boardOffset)
      assert(g0.nonEmpty && g0.last == ((all, EventLink.boardOffset, word)), s"board $i group word: ${g0.toSeq}")
    }
    beats.foreach(_.clear())

    // ── 2. a barrier across the boards: members {0:1, 1:0, 1:2}, count 3 ──
    put(1, 0, SocSpecMap.barrierNode(2), 0, 3); cd.waitSampling(20)
    put(0, 1, SocSpecMap.barrierNode(2), 0, 3); cd.waitSampling(20)
    put(1, 2, SocSpecMap.barrierNode(2), 0, 3); settle()
    val rel0 = beats(0).filter(_._2 == EventLink.releaseOffset).toSeq
    val rel1 = beats(1).filter(_._2 == EventLink.releaseOffset).toSeq
    assert(rel0.size == 1 && rel0.head._1 == (1 << 1), s"board 0 release: $rel0")
    assert(rel1.size == 1 && rel1.head._1 == ((1 << 0) | (1 << 2)), s"board 1 release: $rel1")
    assert(rel0.head._3 == rel1.head._3, s"release stamps differ: ${rel0.head._3} vs ${rel1.head._3}")
    beats.foreach(_.clear())

    // ── 3. unicast across the lane ──
    put(0, 2, SocSpecMap.inboxNode(1, 1), EventLink.mailboxOffset(1), 0x55L); settle()
    assert(beats(0).isEmpty, s"board 0 must not see the unicast: ${beats(0).toSeq}")
    assert(beats(1).toSeq == Seq((1 << 1, EventLink.mailboxOffset(1), 0x55L)), s"board 1 unicast: ${beats(1).toSeq}")
    beats.foreach(_.clear())

    // ── 4. ordering: board 1 publishes then arrives; board 0 arrives last → on both boards the bit
    //      beat precedes the release beat ──
    put(1, 1, SocSpecMap.groupNode(1), 0, (9 << 1) | 1)
    put(1, 1, SocSpecMap.barrierNode(4), 0, 2)
    cd.waitSampling(5)
    put(0, 0, SocSpecMap.groupNode(1), 0, (3 << 1) | 1)
    put(0, 0, SocSpecMap.barrierNode(4), 0, 2); settle()
    for (i <- 0 until 2) {
      val seq = beats(i).map(_._2).toSeq
      val bit = seq.indexOf(EventLink.boardOffset + 4); val rel = seq.indexOf(EventLink.releaseOffset)
      assert(bit >= 0 && rel > bit, s"board $i ordering: $seq")
      assert(beats(i).filter(_._2 == EventLink.boardOffset + 4).last._3 == ((1L << 9) | (1L << 3)), s"board $i group 1 word")
    }
    println(s"[PutLaneSim] PASS  two boards over the WR lane: publishes converge on both boards; a cross-board barrier " +
      "releases once per board to exactly the arrivers with the root's stamp; unicast lands on the far board; " +
      "a bit published before an arrival precedes the release on both boards.")
  }
}
