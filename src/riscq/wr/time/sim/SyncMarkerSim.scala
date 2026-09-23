package riscq.wr.time.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.wr.time.SyncMarker

import scala.collection.mutable

/**
 * W2 gate, spec 04 §4. The TB mirrors the SoC's `syncTime = RegNext(refTime + timeOffset)`
 * (PulseTableSoc) so the marker is checked against the exact signal it sees on the SoC.
 *
 *   - one-shot: marker rises at the sample where `syncTime == markerTime + 1` (the one-register
 *     stretch constant — identical on both boards, cancels on the scope), stays high exactly
 *     `width` cycles, disarms;
 *   - width = 1 minimal pulse;
 *   - periodic: rises exactly `period` apart, `armed` stays set;
 *   - arming in the past: no fire, `missed` set (this is also the disarm idiom), next good arm
 *     clears it.
 *
 * Run: `mill runMain riscq.wr.time.sim.SyncMarkerSim`.
 */
object SyncMarkerSim extends App {

  case class Tb() extends Component {
    val io = new Bundle {
      val timeOffset = in UInt (64 bits)
      val arm        = in Bool ()
      val markerTime = in UInt (64 bits)
      val width      = in UInt (8 bits)
      val period     = in UInt (32 bits)
      val syncTime   = out UInt (64 bits)
      val marker     = out Bool ()
      val armed      = out Bool ()
      val missed     = out Bool ()
    }
    val refTime  = Reg(UInt(64 bits)) init 0
    refTime := refTime + 1
    val syncTime = RegNext(refTime + io.timeOffset) init 0 // the PulseTableSoc syncTime shape
    val m = SyncMarker()
    m.io.syncTime := syncTime
    m.io.arm := io.arm
    m.io.markerTime := io.markerTime
    m.io.width := io.width
    m.io.period := io.period
    io.syncTime := syncTime
    io.marker := m.io.marker
    io.armed := m.io.armed
    io.missed := m.io.missed
  }

  SimConfig.compile(Tb()).doSim("marker", seed = 44) { dut =>
    dut.io.timeOffset #= 5000 // any constant: the marker must track syncTime, not refTime
    dut.io.arm #= false
    dut.io.markerTime #= 0
    dut.io.width #= 0
    dut.io.period #= 0
    dut.clockDomain.forkStimulus(10)

    // coherent transition trace: (syncTime at rise, syncTime at fall)
    val rises = mutable.ArrayBuffer[BigInt]()
    val falls = mutable.ArrayBuffer[BigInt]()
    var lastM = false
    dut.clockDomain.onSamplings {
      val m = dut.io.marker.toBoolean
      if (m && !lastM) rises += dut.io.syncTime.toBigInt
      if (!m && lastM) falls += dut.io.syncTime.toBigInt
      lastM = m
    }

    dut.clockDomain.waitSampling(4)
    def now: BigInt = dut.io.syncTime.toBigInt
    def arm(target: BigInt, width: Int, period: Int): Unit = {
      dut.io.markerTime #= target
      dut.io.width #= width
      dut.io.period #= period
      dut.io.arm #= true
      dut.clockDomain.waitSampling()
      dut.io.arm #= false
      dut.clockDomain.waitSampling()
    }

    // === one-shot ============================================================================
    val t1 = now + 200
    arm(t1, width = 20, period = 0)
    assert(dut.io.armed.toBoolean && !dut.io.missed.toBoolean, "arm did not take")
    dut.clockDomain.waitSampling(400)
    assert(rises == Seq(t1 + 1), s"one-shot rise at ${rises.toList} != ${t1 + 1}")
    assert(falls == Seq(t1 + 1 + 20), s"one-shot fall at ${falls.toList} != ${t1 + 21} (width)")
    assert(!dut.io.armed.toBoolean, "one-shot must disarm after firing")

    // === width = 1 ===========================================================================
    rises.clear(); falls.clear()
    val t2 = now + 150
    arm(t2, width = 1, period = 0)
    dut.clockDomain.waitSampling(300)
    assert(rises == Seq(t2 + 1) && falls == Seq(t2 + 2), s"width-1 pulse at ${rises.toList}/${falls.toList}")

    // === periodic ============================================================================
    rises.clear(); falls.clear()
    val t3 = now + 100
    arm(t3, width = 10, period = 500)
    dut.clockDomain.waitSampling(2200)
    assert(dut.io.armed.toBoolean, "periodic marker must stay armed")
    assert(rises.size >= 4, s"periodic: only ${rises.size} pulses")
    for ((r, i) <- rises.zipWithIndex) assert(r == t3 + 1 + i * 500, s"periodic rise $i at $r != ${t3 + 1 + i * 500}")
    for ((f, i) <- falls.zipWithIndex) assert(f == t3 + 11 + i * 500, s"periodic fall $i at $f")

    // stop it via the past-arm disarm idiom
    arm(0, width = 10, period = 0)
    assert(!dut.io.armed.toBoolean && dut.io.missed.toBoolean, "past arm must disarm + flag missed")
    rises.clear(); falls.clear()
    dut.clockDomain.waitSampling(800)
    assert(rises.isEmpty, s"marker fired after disarm: ${rises.toList}")

    // === past target, then a good re-arm clears missed ======================================
    arm(now - 100, width = 10, period = 0)
    assert(dut.io.missed.toBoolean && !dut.io.armed.toBoolean, "past target must set missed, not arm")
    dut.clockDomain.waitSampling(300)
    assert(rises.isEmpty, "past target must never fire")
    val t4 = now + 100
    arm(t4, width = 5, period = 0)
    assert(!dut.io.missed.toBoolean, "good arm must clear missed")
    dut.clockDomain.waitSampling(300)
    assert(rises == Seq(t4 + 1), s"post-missed rise at ${rises.toList} != ${t4 + 1}")

    println(s"[SyncMarkerSim] PASS  one-shot + width-1 + periodic + missed/disarm, exact rise/fall cycles")
  }
}
