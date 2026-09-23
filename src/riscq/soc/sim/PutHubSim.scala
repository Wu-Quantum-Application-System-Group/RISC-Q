package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.soc.link.{EventLink, PutHub, Put}
import riscq.soc.spec.SocSpecMap

import scala.collection.mutable.ArrayBuffer
import scala.util.Random

/**
 * Sign-off for the board hub (specs/cross-core/02 §4, R2): four cores' system puts through one
 * [[PutHub]], checked beat by beat against a software model of the same rules:
 *
 *   - **groups**: random `{slot, bit}` publishes, several sources on the same cycle — every beat
 *     broadcasts (mask = all) the group's whole word, and the word equals the model's after each;
 *   - **barriers**: random subsets arrive in random order with the right count — exactly one release
 *     per epoch, to exactly the arrivers, carrying the hub's time; re-entering the same id works; two
 *     ids in flight at once do not mix; a disagreeing count raises the sticky `countMismatch`;
 *   - **unicast**: a put to core i's inbox node leaves with mask `1 << i`, offset and data untouched;
 *   - **ordering**: a publish followed by an arrival from the same source is re-put before the release
 *     that arrival completes (the freshness contract, §3.4).
 *
 * Run with `mill runMain riscq.soc.sim.PutHubSim`.
 */
object PutHubSim extends App {
  val cores = 4
  val groups = SocSpecMap.groupNodes
  val ids    = SocSpecMap.barrierIds

  case class Dut() extends Component {
    val hub  = PutHub(cores = cores)
    val puts = Vec(slave port Flow(Put(SocSpecMap.putAddrWidth)), cores)
    val time = in port UInt(32 bits)
    val bcast = master port cloneOf(hub.out)
    for (i <- 0 until cores) hub.in(i) << puts(i)
    hub.laneIn.valid := False; hub.laneIn.payload.assignDontCare(); hub.laneOut.ready := True   // one board: no lane
    hub.time := time
    bcast << hub.out
    val mismatch = out port Bool()
    mismatch := hub.countMismatch
  }

  SimConfig.compile(Dut()).doSim("putHub", seed = 11) { dut =>
    val cd = dut.clockDomain
    val rnd = new Random(11)
    for (i <- 0 until cores) dut.puts(i).valid #= false
    dut.time #= 0
    cd.forkStimulus(10)
    var t = 1000L
    fork { while (true) { cd.waitSampling(); t += 1; dut.time #= t } }

    // capture every broadcast beat: (mask, offset, data)
    val beats = ArrayBuffer[(Int, Int, Long)]()
    cd.onSamplings {
      if (dut.bcast.valid.toBoolean)
        beats += ((dut.bcast.payload.mask.toInt, dut.bcast.payload.put.address.toInt, dut.bcast.payload.put.data.toLong))
    }
    def put(core: Int, node: Int, offset: Int, data: Long): Unit = {
      dut.puts(core).valid #= true
      dut.puts(core).payload.address #= (BigInt(node) << 16) + offset
      dut.puts(core).payload.data #= data
    }
    def clear(): Unit = for (i <- 0 until cores) dut.puts(i).valid #= false
    def drain(): Unit = cd.waitSampling(cores + 30)   // queue + register + grant + mux + pop + decode + pre-read + compute + write-back + beat
    val all = (1 << cores) - 1
    cd.waitSampling(5)

    // ── 1. groups: random publishes, bursts of several sources on one cycle ──
    val model = Array.fill(groups)(0L)
    var total = 0
    for (round <- 0 until 40) {
      // same-cycle publishers (the arbiter's order among them is its own); distinct slots per round so
      // the round's final word per group is order-independent
      val pubs  = (0 until cores).filter(_ => rnd.nextBoolean())
      val slots = rnd.shuffle((0 until 32).toList).take(pubs.size)
      val plan  = pubs.zip(slots).map { case (c, slot) => (c, rnd.nextInt(groups), slot, rnd.nextInt(2)) }
      for ((c, g, slot, bit) <- plan) put(c, SocSpecMap.groupNode(g), 0, (slot << 1) | bit)
      cd.waitSampling(); clear()
      for ((_, g, slot, bit) <- plan) model(g) = (model(g) & ~(1L << slot)) | (bit.toLong << slot)
      drain()
      assert(beats.size == plan.size, s"round $round: ${beats.size} board beats for ${plan.size} publishes")
      assert(beats.forall(_._1 == all), "board beats must broadcast to every core")
      for (g <- plan.map(_._2).distinct) {
        val last = beats.filter(_._2 == EventLink.boardOffset + 4 * g).last._3
        assert(last == model(g), s"round $round group $g: last word $last, model ${model(g)}")
      }
      total += plan.size
      beats.clear()
    }
    println(s"[PutHubSim] $total publishes over 40 rounds tracked the model")

    // ── 2. barriers: random subsets, random order, two ids in flight, re-entry ──
    for (round <- 0 until 30) {
      val id  = rnd.nextInt(ids)
      val id2 = (id + 1 + rnd.nextInt(ids - 1)) % ids
      val members  = rnd.shuffle((0 until cores).toList).take(1 + rnd.nextInt(cores))
      val members2 = rnd.shuffle((0 until cores).toList).take(1 + rnd.nextInt(cores))
      // interleave the two barriers' arrivals; each source arrives at most once per cycle
      val arrivals = rnd.shuffle(members.map(c => (c, id, members.size)) ++ members2.map(c => (c, id2, members2.size)))
      var pending = arrivals
      while (pending.nonEmpty) {
        val (now, later) = pending.foldLeft((List.empty[(Int, Int, Int)], List.empty[(Int, Int, Int)])) {
          case ((n, l), a) => if (n.exists(_._1 == a._1)) (n, l :+ a) else (n :+ a, l) }
        for ((c, b, cnt) <- now) put(c, SocSpecMap.barrierNode(b), 0, cnt)
        cd.waitSampling(); clear()
        pending = later
      }
      drain()
      val tNow = t
      val rel  = beats.filter(_._2 == EventLink.releaseOffset).toSeq
      assert(rel.size == 2, s"round $round: expected 2 releases (ids $id, $id2), got $rel")
      val masks = rel.map(_._1).toSet
      val m1 = members.map(1 << _).sum; val m2 = members2.map(1 << _).sum
      assert(masks == Set(m1, m2), s"round $round: release masks $masks, want ${Set(m1, m2)}")
      for ((_, _, d) <- rel) assert(d <= tNow && d >= tNow - 40, s"release time $d not near hub time $tNow")
      assert(!dut.mismatch.toBoolean, "no mismatch expected")
      beats.clear()
    }

    // ── 3. ordering: publish then arrive from the same source; the board beat precedes the release ──
    put(0, SocSpecMap.groupNode(1), 0, (7 << 1) | 1); cd.waitSampling(); clear()
    put(0, SocSpecMap.barrierNode(3), 0, 2); cd.waitSampling(); clear()
    cd.waitSampling(3)
    put(2, SocSpecMap.barrierNode(3), 0, 2); cd.waitSampling(); clear()
    drain()
    assert(beats.size == 2 && beats(0)._2 == EventLink.boardOffset + 4 && beats(1)._2 == EventLink.releaseOffset,
      s"ordering: got $beats")
    assert(beats(1)._1 == ((1 << 0) | (1 << 2)), s"release mask: got ${beats(1)._1}")
    beats.clear()

    // ── 4. unicast to a core's inbox ──
    put(3, SocSpecMap.inboxNode(1), EventLink.mailboxOffset(0), 0x77L); cd.waitSampling(); clear()
    drain()
    assert(beats.toSeq == Seq((1 << 1, EventLink.mailboxOffset(0), 0x77L)), s"unicast: got $beats")
    beats.clear()

    // ── 5. a disagreeing count is flagged, sticky ──
    put(0, SocSpecMap.barrierNode(5), 0, 2); cd.waitSampling(); clear(); cd.waitSampling(2)
    put(1, SocSpecMap.barrierNode(5), 0, 3); cd.waitSampling(); clear()
    drain()
    assert(dut.mismatch.toBoolean, "countMismatch must be raised by a disagreeing count")
    assert(beats.size == 1 && beats(0)._1 == 3, s"the barrier still released on the first count: got $beats")

    println(s"[PutHubSim] PASS  $cores cores: group words track the model through same-cycle bursts; barriers release " +
      "once per epoch to exactly the arrivers (two ids in flight, re-entry), stamped with the hub time; publish-then-arrive " +
      "orders the board beat before the release; unicast lands with mask 1<<i; a disagreeing count is flagged.")
  }
}
