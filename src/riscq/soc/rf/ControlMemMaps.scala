package riscq.soc.rf

import spinal.core._
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SingleMapping

/**
 * CPU-mapped control-block register fragments contributed to a `MemMapFiber` (`addMapping`) — ported
 * from the RISC-Q reference (`riscq.soc.Misc`).
 *
 * [[TimeMemMap]] exposes the SoC batch-time and a wait-compare the control software spins on:
 *   - `time`@0xbff8        — current batch time (registered copy of the external time);
 *   - `timeCmp`@0x4000     — software-written compare value;
 *   - `waitTimeCmp`@0x4008 — a read that **halts** until `time + delay ≥ timeCmp`, i.e. the CPU blocks
 *                            until the wall clock catches up to the scheduled instant. The test is the
 *                            SIGNED difference `timeCmp − (time + delay)`, so it stays correct across
 *                            the free-running counter's 2^32 wrap (every 8.6 s at 500 MHz) as long as
 *                            the schedule is within ±2^31 of now — the same rule as [[TimedQueue]]. An
 *                            unsigned `<` released every wait for up to one grid period whenever
 *                            `timeCmp` had wrapped and `time` had not (the run-free shots then read
 *                            one stale readout result over and over).
 *
 * [[DoneMemMap]] is the run-completion flag the host reads instead of the RAM's `__rq_status`.
 */
case class TimeMemMap(externalTime: UInt) extends Area {
  val timeCmp = Reg(UInt(32 bit)) init 0
  val time    = RegNext(externalTime)

  def mapping(factory: tilelink.SlaveFactory): Unit = {
    val timeAddr = 0xbff8
    factory.read(time, timeAddr)

    val timeCmpAddr = 0x4000
    factory.readAndWrite(timeCmp, timeCmpAddr)
    val delay = 3

    val waitTimeAddr = timeCmpAddr + 8
    val waitTimeCmp  = RegNext((timeCmp - (time + delay)).asSInt > 0)   // wrap-safe: signed distance
    factory.read(waitTimeCmp, waitTimeAddr)
    factory.onReadPrimitive(SingleMapping(waitTimeAddr), haltSensitive = false, null) {
      when(waitTimeCmp) {
        factory.readHalt()
      }
    }
  }
}

/**
 * [[DoneMemMap]] is the program's completion flag (specs/software/23-done-register.md): one sticky
 * bit the firmware sets as its last store, which the host then reads in the SoC's *host* control block
 * instead of polling `__rq_status` in the core's RAM. Polling the RAM word works, but every poll is a
 * Get on the RAM port that instruction fetch shares with the host image-load master — this keeps
 * completion detection off that port entirely, and lets one host read cover every core at once.
 *
 *   - `done`@0x4010 — write bit 0 to set; a read returns it.
 *
 * There is deliberately **no software clear**: the bit is cleared only by `riscqReset`, which is
 * exactly the run boundary (the host asserts reset between runs), so a stale DONE from the previous
 * run cannot race the next poll and the driver needs no clearing write.
 */
case class DoneMemMap() extends Area {
  val done = RegInit(False)

  def mapping(factory: tilelink.SlaveFactory): Unit = {
    val doneAddr = 0x4010
    factory.read(done, doneAddr)
    factory.setOnSet(done, doneAddr, 0)
  }
}
