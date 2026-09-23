package riscq.wr.time

import spinal.core._
import spinal.lib._

/**
 * `refTime` timestamping unit (spec 04 §1): captures the free-running 64-bit dspClk `refTime`
 * counter on a PCS timestamp trigger. One instance per direction — TX (t1/t3) and RX (t2/t4).
 *
 * Runs entirely in dspClk. `io.trigger` is the raw single-cycle pulse from the 62.5 MHz PCS
 * domain: at 16 ns it is ~8 dspClk cycles wide, so a 2-FF synchronizer + rising-edge detect
 * needs no pulse extender (`RefTimeTsuSim` asserts exactly one capture per pulse).
 *
 * Capture constant: `ts = refTime-in-force-at-the-trigger-rise + 2` (the two sync FFs), exact
 * and asserted in sim; on hardware the trigger phase quantizes this to ±1 dspClk cycle — the
 * dither the exchange averaging relies on (spec README §3). TX and RX use this identical chain,
 * so the constant is matched and cancels in `delay_MM` (paper §3.5.3 discipline).
 *
 * `valid` holds `ts`/`seq` until the host acknowledges (`ack`, from the register file read,
 * 06 §2). A capture landing while `valid` is still set raises the sticky `overrun` — a protocol
 * violation (one outstanding exchange at a time), the host restarts the exchange. A capture
 * coincident with `ack` is a fresh capture, not an overrun.
 */
case class RefTimeTsu() extends Component {
  val io = new Bundle {
    val trigger = in Bool ()
    val refTime = in UInt (64 bits)
    val ack     = in Bool ()
    val ts      = out UInt (64 bits)
    val seq     = out UInt (4 bits)
    val valid   = out Bool ()
    val overrun = out Bool ()
  }

  val sync = BufferCC(io.trigger, init = False)
  val last = RegNext(sync) init False
  val rise = sync && !last

  val ts      = Reg(UInt(64 bits)) init 0
  val seq     = Reg(UInt(4 bits)) init 0
  val valid   = Reg(Bool()) init False
  val overrun = Reg(Bool()) init False

  when(io.ack) {
    valid := False
    overrun := False
  }
  when(rise) {
    ts := io.refTime
    seq := seq + 1
    valid := True
    when(valid && !io.ack) { overrun := True }
  }

  io.ts := ts
  io.seq := seq
  io.valid := valid
  io.overrun := overrun
}
