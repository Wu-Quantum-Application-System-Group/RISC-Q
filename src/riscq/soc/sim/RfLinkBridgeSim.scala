package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.DebugId
import spinal.lib.bus.tilelink.fabric.MasterBus
import spinal.lib.bus.tilelink.sim.{IdAllocator, IdCallback, MasterAgent}
import spinal.lib.bus.misc.SizeMapping
import riscq.soc.link.{RfCmd, RfLinkBridge, RfLink}
import riscq.soc.spec.SocSpecMap

import scala.collection.mutable.ArrayBuffer

/**
 * Sign-off for [[RfLinkBridge]] + the far-side demux: drive the bridge's TileLink slave with a
 * `tilelink.sim.MasterAgent` issuing a stream of word stores, and assert
 *
 *   - every accepted `Put` emerges as exactly one `Flow(RfCmd)` beat, **in order**, with the right
 *     `{address, data}` (the posted-write stream the far side consumes);
 *   - the address demux routes each beat to the correct sub-window, rebased to that window;
 *   - every store **completes** (the bridge acks locally — `putFullData` blocks on the D channel, so a
 *     missing ack would hang the sim); the ack is structurally 1-cycle (`bus.d << rsp.stage()`).
 *
 * Run with `./.metals/mill runMain riscq.soc.sim.RfLinkBridgeSim`.
 */
object RfLinkBridgeSim extends App {
  val rfAddrWidth = SocSpecMap.putAddrWidth   // the put window: nodes 0..15 = own channels, ≥ 16 = system
  val hubNode     = 0x21               // a system node (≥ SocSpecMap.localNodes): routed to nonLocal
  val gateBase    = 0x00000
  val roBase      = 0x10000
  val winSize     = 0x10000

  case class Dut() extends Component {
    val tlBus = new MasterBus(tilelink.M2sParameters(
      addressWidth = rfAddrWidth, dataWidth = 32,
      masters = List(tilelink.M2sAgent(name = this, mapping = List(tilelink.M2sSource(
        id = SizeMapping(0, 4),
        emits = tilelink.M2sTransfers(
          putFull = tilelink.SizeRange(4), putPartial = tilelink.SizeRange(4))))))))

    val bridge = RfLinkBridge(rfAddrWidth)
    bridge.up at 0 of tlBus.node

    // the ordered posted stream + two demuxed sub-window streams, re-exported for the sim.
    val cmd  = master port cloneOf(bridge.cmd);          cmd  << bridge.cmd
    val gate = master port Flow(RfCmd(16)); gate << RfLink.demux(bridge.cmd, gateBase, winSize, 16)
    val ro   = master port Flow(RfCmd(16)); ro   << RfLink.demux(bridge.cmd, roBase,   winSize, 16)
    val sys  = master port cloneOf(bridge.cmd);          sys  << RfLink.nonLocal(bridge.cmd, SocSpecMap.localNodes)
  }

  def w16(v: BigInt): Int = (((v & 0xFFFF) << 16) & 0xFFFFFFFFL).toInt

  // an interleaved write stream across both sub-windows (startTime / freq / table / fire).
  val issued: Seq[(Int, Int)] = Seq(
    (gateBase + 0x4100, 0x00000123),     // gate startTime
    (gateBase + 0x0004, w16(1000)),      // gate freq
    (gateBase + 0x0010, w16(50)),        // gate table[0].phase
    (gateBase + 0x0014, w16(9000)),      // gate table[0].amp
    (gateBase + 0x0000, 0),              // gate fire (outId 0)
    (roBase   + 0x4100, 0x00000456),     // readout startTime
    (roBase   + 0x0004, w16(2000)),      // readout freq
    (roBase   + 0x0000, 0),              // readout fire
    (gateBase + 0x0020, w16(77)),        // gate table[1].phase
    ((hubNode << 16) + 0x0004, 0x00000003), // a system put (barrier arrive, count 3) → nonLocal only
    (gateBase + 0x0000, 1)               // gate fire (outId 1)
  )

  SimConfig.compile(Dut()).doSim("rfLinkBridge", seed = 42) { dut =>
    val cd = dut.clockDomain
    dut.tlBus.node.bus.a.valid #= false
    cd.forkStimulus(10)

    // capture every emitted beat on each stream, in order.
    val cmdBeats  = ArrayBuffer[(BigInt, BigInt)]()
    val gateBeats = ArrayBuffer[(BigInt, BigInt)]()
    val roBeats   = ArrayBuffer[(BigInt, BigInt)]()
    val sysBeats  = ArrayBuffer[(BigInt, BigInt)]()
    cd.onSamplings {
      if (dut.sys.valid.toBoolean)  sysBeats  += ((dut.sys.payload.address.toBigInt,  dut.sys.payload.data.toBigInt))
      if (dut.cmd.valid.toBoolean)  cmdBeats  += ((dut.cmd.payload.address.toBigInt,  dut.cmd.payload.data.toBigInt))
      if (dut.gate.valid.toBoolean) gateBeats += ((dut.gate.payload.address.toBigInt, dut.gate.payload.data.toBigInt))
      if (dut.ro.valid.toBoolean)   roBeats   += ((dut.ro.payload.address.toBigInt,   dut.ro.payload.data.toBigInt))
    }

    implicit val idAllocator = new IdAllocator(DebugId.width)
    implicit val idCallback  = new IdCallback
    val agent = new MasterAgent(dut.tlBus.node.bus, cd)
    cd.waitSampling(10)

    // issue the stores back-to-back; putFullData blocks on the D ack, so all completing proves the
    // local ack keeps up at one store per request (a stalled bridge would hang here).
    for ((addr, data) <- issued) agent.putInt(0, addr, data)
    cd.waitSampling(20)

    // ── ordered posted stream matches the issued writes (absolute RF-window address + data) ──
    val expectedCmd = issued.map { case (a, d) => (BigInt(a), BigInt(d) & 0xFFFFFFFFL) }
    assert(cmdBeats == expectedCmd,
      s"posted RfCmd stream mismatch:\n  got  $cmdBeats\n  want $expectedCmd")

    // ── demux routing: each beat lands in its window, rebased, in order ──
    val expectedGate = issued.filter(_._1 < winSize).map { case (a, d) => (BigInt(a - gateBase), BigInt(d) & 0xFFFFFFFFL) }
    val expectedRo   = issued.filter(p => p._1 >= roBase && p._1 < roBase + winSize)
      .map { case (a, d) => (BigInt(a - roBase), BigInt(d) & 0xFFFFFFFFL) }
    assert(gateBeats == expectedGate, s"gate demux mismatch:\n  got  $gateBeats\n  want $expectedGate")
    assert(roBeats == expectedRo,     s"readout demux mismatch:\n  got  $roBeats\n  want $expectedRo")

    // ── the system put leaves on nonLocal, untouched, and never on a channel window ──
    val expectedSys = issued.filter(_._1 >= (SocSpecMap.localNodes << 16)).map { case (a, d) => (BigInt(a), BigInt(d) & 0xFFFFFFFFL) }
    assert(sysBeats == expectedSys, s"nonLocal mismatch:\n  got  $sysBeats\n  want $expectedSys")

    // every beat is accounted for by exactly one output (no leaks, no duplicates).
    assert(gateBeats.size + roBeats.size + sysBeats.size == cmdBeats.size, "routing dropped or duplicated a beat")

    println(s"[RfLinkBridgeSim] PASS  rfAddrWidth=$rfAddrWidth: ${issued.size} TL stores → ${cmdBeats.size} ordered " +
      s"RfCmd beats (gate ${gateBeats.size}, readout ${roBeats.size}, system ${sysBeats.size}); all acked locally (no stall).")
    simSuccess()
  }
}
