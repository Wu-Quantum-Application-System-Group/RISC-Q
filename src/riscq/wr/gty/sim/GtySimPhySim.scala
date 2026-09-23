package riscq.wr.gty.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.wr.gty.{GtySimPhy, WrPhyIo}

import scala.collection.mutable

/**
 * Self-test of the `GtySimPhy` link model (spec 01 §8): known counter patterns round-trip
 * between two cross-wired phy bundles.
 *
 *   - after alignment, delivery is **bit-exact and unshifted** (offset 0 by construction) and
 *     the word latency is a run-constant; a config with `delay + one refClk period` shifts the
 *     constant by exactly one word;
 *   - `resetRxDatapath` restarts the dice-throw and re-converges to the same constant;
 *   - across re-inits the rolled word-boundary offsets cover **all 20 positions** and the
 *     dice-throw try count varies (the geometric statistics the hardware bring-up expects).
 *
 * Run: `mill runMain riscq.wr.gty.sim.GtySimPhySim`.
 */
object GtySimPhySim extends App {

  case class PhyTb() extends Component {
    val io = new Bundle {
      val a, b = slave(WrPhyIo())
      val rstAll          = in Bool ()
      val rstRxB          = in Bool ()
      val cntA, cntB      = out UInt (20 bits)
    }
    val boot = ClockDomainConfig(resetKind = BOOT)
    // deterministic wire pattern: a free counter in each sender's clkRef domain
    val aTx = ClockDomain(io.a.clkRef, config = boot) { new Area {
      val cnt = Reg(UInt(20 bits)) init 0
      cnt := cnt + 1
    }}
    val bTx = ClockDomain(io.b.clkRef, config = boot) { new Area {
      val cnt = Reg(UInt(20 bits)) init 0
      cnt := cnt + 1
    }}
    io.a.txDataRaw := aTx.cnt.asBits
    io.b.txDataRaw := bTx.cnt.asBits
    io.cntA := aTx.cnt
    io.cntB := bTx.cnt
    io.a.resetAll := io.rstAll
    io.b.resetAll := io.rstAll
    io.a.resetRxDatapath := False
    io.b.resetRxDatapath := io.rstRxB
  }

  val compiled = SimConfig.compile(PhyTb())

  def run(name: String, cfg: GtySimPhy.Config, resetCycles: Int)(
      after: (mutable.ArrayBuffer[Int], mutable.ArrayBuffer[Int]) => Unit): Unit =
    compiled.doSim(name, seed = 5) { dut =>
      dut.io.rstAll #= true
      dut.io.rstRxB #= false
      val rolls = mutable.ArrayBuffer[Int]()
      val dices = mutable.ArrayBuffer[Int]()
      GtySimPhy.link(dut.io.a, dut.io.b, cfg.copy(rollProbe = rolls += _))
      sleep(100000)

      def awaitUp(timeoutPs: Long = 500000000L): Unit = {
        val t0 = simTime()
        while (!(dut.io.a.ready.toBoolean && dut.io.b.ready.toBoolean)) {
          assert(simTime() - t0 < timeoutPs, "link never came up")
          sleep(10000)
        }
      }
      /** B-side word latency in words: sender counter minus delivered word, sampled mid-cycle. */
      def latencyB(): Int = {
        val deltas = mutable.Set[Int]()
        for (_ <- 0 until 60) {
          waitUntil(dut.io.b.clkRx.toBoolean); waitUntil(!dut.io.b.clkRx.toBoolean)
          val rx  = dut.io.b.rxDataRaw.toBigInt.toInt
          val cnt = dut.io.cntA.toBigInt.toInt
          deltas += ((cnt - rx) & 0xfffff)
        }
        assert(deltas.size <= 2, s"latency not constant: $deltas") // ±1 from async sampling
        deltas.min
      }

      dut.io.rstAll #= false
      awaitUp()
      val lat0 = latencyB()
      dices += rolls.size

      // bit-exact consecutive delivery on both sides
      for (side <- Seq((dut.io.b, () => true), (dut.io.a, () => true))) {
        val (phy, _) = side
        var last = -1
        for (_ <- 0 until 50) {
          waitUntil(phy.clkRx.toBoolean); waitUntil(!phy.clkRx.toBoolean)
          val w = phy.rxDataRaw.toBigInt.toInt
          if (last >= 0) assert(w == ((last + 1) & 0xfffff), f"non-consecutive word: $last%05x -> $w%05x")
          last = w
        }
      }

      // re-init cycles: dice-throw restarts, same latency constant, offsets accumulate in `rolls`
      for (k <- 0 until resetCycles) {
        val rollsBefore = rolls.size
        if (k % 2 == 0) { // full reset
          dut.io.rstAll #= true; sleep(2000000); dut.io.rstAll #= false
        } else { // RX-datapath-only reset on B
          dut.io.rstRxB #= true; sleep(2000000); dut.io.rstRxB #= false
        }
        awaitUp()
        assert(rolls.size > rollsBefore, "reset did not re-roll the alignment")
        assert(latencyB() == lat0, s"latency moved across re-init: ${latencyB()} != $lat0")
        dices += rolls.size
      }
      after(rolls, dices)
    }

  val base = GtySimPhy.Config(delayAtoBPs = 40000, delayBtoAPs = 40000, rollCycles = 10, seed = 21)

  run("d40k", base, resetCycles = 20) { (rolls, dices) =>
    val distinct = rolls.distinct.sorted
    assert(distinct == (0 until 20).toList, s"rolled offsets do not cover 0..19: $distinct")
    val tries = dices.sliding(2).map(w => w.last - w.head).toList
    assert(tries.distinct.size >= 4, s"dice-throw try counts do not vary: $tries")
    println(s"[GtySimPhySim] d40k PASS  ${rolls.size} rolls cover all 20 offsets, tries=$tries")
  }

  // one extra refClk period of line delay ⇒ exactly one more word of latency
  val measured = mutable.ArrayBuffer[Int]()
  for (d <- Seq(40000L, 56000L)) {
    compiled.doSim(s"latmeas-d$d", seed = 9) { dut =>
      dut.io.rstAll #= true; dut.io.rstRxB #= false
      GtySimPhy.link(dut.io.a, dut.io.b, base.copy(delayAtoBPs = d, delayBtoAPs = d, seed = 23))
      sleep(100000)
      dut.io.rstAll #= false
      while (!(dut.io.a.ready.toBoolean && dut.io.b.ready.toBoolean)) sleep(10000)
      val deltas = mutable.Set[Int]()
      for (_ <- 0 until 60) {
        waitUntil(dut.io.b.clkRx.toBoolean); waitUntil(!dut.io.b.clkRx.toBoolean)
        deltas += ((dut.io.cntA.toBigInt.toInt - dut.io.b.rxDataRaw.toBigInt.toInt) & 0xfffff)
      }
      assert(deltas.size <= 2, s"latency not constant: $deltas")
      measured += deltas.min
    }
  }
  assert(measured(1) == measured(0) + 1,
    s"delay+P must add exactly one word of latency: $measured")
  println(s"[GtySimPhySim] latency constants ${measured.toList} (words) for delays 40000/56000 ps")
  println("[GtySimPhySim] all PASS")
}
