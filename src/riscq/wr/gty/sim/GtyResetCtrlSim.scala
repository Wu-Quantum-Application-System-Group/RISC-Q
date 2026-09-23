package riscq.wr.gty.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.wr.gty.{BufferBypassCtrl, GtyResetCtrl}

import scala.collection.mutable

/**
 * W4 gate, spec 01 §3/§4 — scripted fake GT (status inputs driven by the TB):
 *
 *   - nominal bring-up: the reset releases follow the required ORDER (QPLL reset → lock →
 *     TX resets → PMA → userRdy after the fixed delay → resetDone → the RX leg with the
 *     CDR-stable hold);
 *   - `resetRxDatapath` redoes only the RX leg (TX side untouched);
 *   - a hung wait times out, restarts the full sequence, bumps `retryCount`, then recovers;
 *   - `BufferBypassCtrl`: DLYSRESET pulse → wait DLYSRESETDONE → wait SYNCDONE → done;
 *     timeout raises `error`.
 *
 * Run: `mill runMain riscq.wr.gty.sim.GtyResetCtrlSim`.
 */
object GtyResetCtrlSim extends App {

  SimConfig.compile(GtyResetCtrl(cdrStableCycles = 50, userRdyCycles = 8, timeoutCycles = 400))
    .doSim("reset", seed = 4) { dut =>
      dut.io.resetAll #= true
      dut.io.resetRxDatapath #= false
      dut.io.qpllLock #= false
      dut.io.txPmaResetDone #= false
      dut.io.txResetDone #= false
      dut.io.rxPmaResetDone #= false
      dut.io.rxResetDone #= false
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      var rxDoneEnable = true
      // the scripted GT: responds to reset releases with plausible delays, checking order
      val gtScript = fork {
        val cd = dut.clockDomain
        while (true) {
          // QPLL: lock only while its reset is released
          if (dut.io.qpllReset.toBoolean) dut.io.qpllLock #= false
          else if (!dut.io.qpllLock.toBoolean) { cd.waitSampling(20); if (!dut.io.qpllReset.toBoolean) dut.io.qpllLock #= true }
          // TX leg
          if (dut.io.gtTxReset.toBoolean) { dut.io.txPmaResetDone #= false; dut.io.txResetDone #= false }
          else if (dut.io.qpllLock.toBoolean && !dut.io.txPmaResetDone.toBoolean) { cd.waitSampling(15); if (!dut.io.gtTxReset.toBoolean) dut.io.txPmaResetDone #= true }
          if (dut.io.txPmaResetDone.toBoolean && dut.io.txUserRdy.toBoolean && !dut.io.txResetDone.toBoolean) { cd.waitSampling(10); dut.io.txResetDone #= true }
          // RX leg
          if (dut.io.gtRxReset.toBoolean) { dut.io.rxPmaResetDone #= false; dut.io.rxResetDone #= false }
          else if (dut.io.qpllLock.toBoolean && !dut.io.rxPmaResetDone.toBoolean) { cd.waitSampling(15); if (!dut.io.gtRxReset.toBoolean) dut.io.rxPmaResetDone #= true }
          if (dut.io.rxPmaResetDone.toBoolean && dut.io.rxUserRdy.toBoolean && !dut.io.rxResetDone.toBoolean && rxDoneEnable) { cd.waitSampling(10); dut.io.rxResetDone #= true }
          cd.waitSampling()
        }
      }

      // order monitor: no userRdy before pma, no resetDone claims before userRdy, etc.
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.txUserRdy.toBoolean) assert(dut.io.txPmaResetDone.toBoolean, "txUserRdy before txPmaResetDone")
          if (dut.io.rxUserRdy.toBoolean) assert(dut.io.rxPmaResetDone.toBoolean, "rxUserRdy before rxPmaResetDone")
          if (!dut.io.qpllLock.toBoolean) assert(!dut.io.txDone.toBoolean, "txDone without qpllLock")
        }
      }

      def waitFor(cond: => Boolean, what: String, limit: Int = 5000): Int = {
        var n = 0
        while (!cond) { dut.clockDomain.waitSampling(); n += 1; assert(n < limit, s"timeout waiting for $what") }
        n
      }

      // ── nominal bring-up ──────────────────────────────────────────────────────────────────
      dut.io.resetAll #= false
      waitFor(dut.io.txDone.toBoolean, "txDone")
      // the RX leg must hold its reset for the CDR-stable count after txDone
      var held = 0
      while (dut.io.gtRxReset.toBoolean) { dut.clockDomain.waitSampling(); held += 1; assert(held < 5000, "rx held forever") }
      assert(held >= 50, s"CDR hold only $held cycles (< cdrStableCycles)")
      waitFor(dut.io.rxDone.toBoolean, "rxDone")
      assert(dut.io.retryCount.toInt == 0, "spurious retry in nominal bring-up")

      // ── RX-only datapath reset (the dice-throw) ──────────────────────────────────────────
      dut.io.resetRxDatapath #= true
      dut.clockDomain.waitSampling(2)
      dut.io.resetRxDatapath #= false
      waitFor(!dut.io.rxDone.toBoolean, "rxDone to drop")
      assert(dut.io.txDone.toBoolean && !dut.io.gtTxReset.toBoolean, "TX leg disturbed by RX datapath reset")
      assert(dut.io.gtRxReset.toBoolean, "RX reset not asserted on datapath reset")
      waitFor(dut.io.rxDone.toBoolean, "rxDone after datapath reset")
      assert(dut.io.retryCount.toInt == 0)

      // ── hang → timeout → full retry → recovery ───────────────────────────────────────────
      rxDoneEnable = false // the fake GT stops answering the RX leg
      dut.io.resetRxDatapath #= true
      dut.clockDomain.waitSampling(2)
      dut.io.resetRxDatapath #= false
      waitFor(dut.io.retryCount.toInt > 0, "retry on hung rxResetDone", limit = 20000)
      dut.clockDomain.waitSampling(2) // ALL_RESET's registered outputs commit a cycle later
      assert(dut.io.qpllReset.toBoolean && !dut.io.txDone.toBoolean, "retry did not restart the full sequence")
      rxDoneEnable = true
      waitFor(dut.io.txDone.toBoolean && dut.io.rxDone.toBoolean, "recovery after retry", limit = 20000)
      println(s"[GtyResetCtrlSim] reset PASS  retries=${dut.io.retryCount.toInt}")
    }

  SimConfig.compile(BufferBypassCtrl(timeoutCycles = 200)).doSim("buffbypass", seed = 5) { dut =>
    dut.io.start #= false
    dut.io.dlySResetDone #= false
    dut.io.syncDone #= false
    dut.clockDomain.forkStimulus(10)
    dut.clockDomain.waitSampling(5)
    assert(!dut.io.dlySReset.toBoolean && !dut.io.done.toBoolean)

    // nominal alignment
    dut.io.start #= true
    dut.clockDomain.waitSampling(10)
    assert(dut.io.dlySReset.toBoolean, "DLYSRESET not asserted after start")
    dut.io.dlySResetDone #= true
    dut.clockDomain.waitSampling(10)
    assert(!dut.io.dlySReset.toBoolean, "DLYSRESET not released after DLYSRESETDONE")
    assert(!dut.io.done.toBoolean)
    dut.io.syncDone #= true
    dut.clockDomain.waitSampling(10)
    assert(dut.io.done.toBoolean && !dut.io.error.toBoolean, "no done after SYNCDONE")

    // leg back into reset → re-arm
    dut.io.start #= false
    dut.io.dlySResetDone #= false
    dut.io.syncDone #= false
    dut.clockDomain.waitSampling(10)
    assert(!dut.io.done.toBoolean, "done stuck after start dropped")

    // timeout → error
    dut.io.start #= true
    dut.clockDomain.waitSampling(300)
    assert(dut.io.error.toBoolean && !dut.io.done.toBoolean, "no error on hung DLYSRESETDONE")
    println("[GtyResetCtrlSim] buffbypass PASS")
  }

  println("[GtyResetCtrlSim] all PASS")
}
