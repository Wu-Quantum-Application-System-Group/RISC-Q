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
import riscq.soc.rf.{TimeMemMap, DoneMemMap}

/**
 * Sign-off for the qubit-core control block ([[MemMapFiber]] + [[TimeMemMap]] +
 * `startTime`), driven over Tilelink with a `MasterAgent`. Pins down the CPU-visible control register
 * map and the `waitTimeCmp` halt — the spin-wait the scheduling software blocks on until the batch clock
 * reaches a scheduled instant:
 *
 *   - `time`@0xbff8 reads the (registered) external batch time;
 *   - `timeCmp`@0x4000 read/writes the compare value; `startTime`@0x4100 is write-only;
 *   - `waitTimeCmp`@0x4008 is a read that '''halts''' the bus until `time + 3 ≥ timeCmp`;
 *   - `done`@0x4010 is the run-completion flag (specs/software/23): set by writing bit 0 and sticky
 *     against further writes (its clearing by the per-run `riscqReset` is checked in HostWindowCpuSim).
 *
 * Run with `./.metals/mill runMain riscq.soc.sim.ControlMapFiberSim`.
 */
object ControlMapFiberSim extends App {

  case class Dut() extends Component {
    val tlBus = new MasterBus(tilelink.M2sParameters(addressWidth = 24, dataWidth = 32,
      masters = List(tilelink.M2sAgent(name = this, mapping = List(tilelink.M2sSource(
        id = SizeMapping(0, 4), emits = tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(0x40), putFull = tilelink.SizeRange.upTo(0x40),
          putPartial = tilelink.SizeRange.upTo(0x40))))))))
    val externalTime = in port UInt(32 bits)

    val mm = MemMapFiber(addressWidth = 22, dataWidth = 32)
    val timeMemMap = TimeMemMap(externalTime); mm.addMapping(timeMemMap.mapping)
    val doneMemMap = DoneMemMap(); mm.addMapping(doneMemMap.mapping)
    val startTime = Reg(UInt(32 bit)) init 0
    mm.addMapping { factory => factory.write(startTime, 0x4100) }
    mm.up at 0 of tlBus.node

    val startTimeOut = out(UInt(32 bits)); startTimeOut := startTime
    val doneOut = out(Bool()); doneOut := doneMemMap.done
  }

  SimConfig.compile(Dut()).doSim("controlMap", seed = 42) { dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    dut.externalTime #= 0
    implicit val idAllocator = new IdAllocator(DebugId.width)
    implicit val idCallback  = new IdCallback
    val agent = new MasterAgent(dut.tlBus.node.bus, cd)
    cd.waitSampling(10)

    // ── time read (registered copy of externalTime) ──
    dut.externalTime #= 12345
    cd.waitSampling(3)
    assert(agent.getInt(0, 0xbff8) == 12345, s"time read != 12345")

    // ── timeCmp read/write ──
    agent.putInt(0, 0x4000, 0x64); cd.waitSampling(2) // timeCmp = 100
    assert(agent.getInt(0, 0x4000) == 0x64, "timeCmp readback")

    // ── startTime write-only ──
    agent.putInt(0, 0x4100, 777); cd.waitSampling(3)
    assert(dut.startTimeOut.toBigInt == 777, "startTime write")

    // ── waitTimeCmp halt: timeCmp far in the future; the read must block until externalTime catches up.
    agent.putInt(0, 0x4000, 200); cd.waitSampling(2)        // timeCmp = 200
    dut.externalTime #= 50; cd.waitSampling(3)              // settle waitTimeCmp true (50 + 3 < 200) before the halting read
    var ticking = true
    fork { var t = 50; while (ticking) { dut.externalTime #= t; cd.waitSampling(); t += 1 } }
    val wv = agent.getInt(0, 0x4008)                         // HALTS until externalTime + 3 ≥ 200
    val tAfter = dut.externalTime.toBigInt
    ticking = false; cd.waitSampling(2)
    assert(tAfter >= 197, s"waitTimeCmp released too early at externalTime=$tAfter (want ≥197)")
    assert((wv & 1) == 0, s"waitTimeCmp value should be false (released) but was $wv")

    // ── waitTimeCmp across the 2^32 wrap: time in the upper half, timeCmp just past the wrap. The
    // unsigned compare released this immediately (the bug behind the repeated-IQ tails on hardware).
    val wrapCmp = BigInt(150)                                 // = 2^32 + 150
    agent.putInt(0, 0x4000, wrapCmp.toInt); cd.waitSampling(2)
    val tStart = (BigInt(1) << 32) - 50
    dut.externalTime #= tStart; cd.waitSampling(3)
    fork { var t = tStart; while (true) { dut.externalTime #= t; cd.waitSampling(); t = (t + 1) & 0xFFFFFFFFL } }
    val wv2 = agent.getInt(0, 0x4008)                        // HALTS through the wrap until time + 3 ≥ 150
    val tWrap = dut.externalTime.toBigInt
    assert(tWrap >= 147 && tWrap < 1000, s"waitTimeCmp across the wrap released at externalTime=$tWrap (want 147..)")
    assert((wv2 & 1) == 0, s"waitTimeCmp value should be false (released) but was $wv2")

    // ── done@0x4010: reads 0 out of reset, set by a write of bit 0, sticky, cleared only by reset ──
    assert(agent.getInt(0, 0x4010) == 0, "done should read 0 before the program sets it")
    assert(!dut.doneOut.toBoolean, "done register should be clear out of reset")
    agent.putInt(0, 0x4010, 0); cd.waitSampling(2)          // a write of 0 must NOT set it
    assert(agent.getInt(0, 0x4010) == 0, "done set by a write of bit 0 = 0")
    agent.putInt(0, 0x4010, 1); cd.waitSampling(2)
    assert(agent.getInt(0, 0x4010) == 1, "done not set by a write of bit 0 = 1")
    assert(dut.doneOut.toBoolean, "done output not raised")
    agent.putInt(0, 0x4010, 0); cd.waitSampling(2)          // sticky: no software clear
    assert(agent.getInt(0, 0x4010) == 1, "done cleared by a write — it must be sticky")
    // The other half of the contract — the bit clearing at the run boundary — is a `riscqReset`
    // property, so it is checked in HostWindowCpuSim against the real reset network. Toggling this
    // DUT's own domain reset here would reset the bus under the MasterAgent too.

    println(s"[ControlMapFiberSim] PASS: time/timeCmp/startTime register map + waitTimeCmp halt " +
      s"(released at externalTime=$tAfter, across the wrap at $tWrap) + done@0x4010 set/sticky.")
    simSuccess()
  }
}
