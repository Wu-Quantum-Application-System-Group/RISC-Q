package riscq.wr.time

import spinal.core._
import spinal.lib._

/**
 * Scope-visible sync verification marker (spec 04 §2): the host arms a 64-bit `markerTime`;
 * when `syncTime` reaches it, the marker pin pulses for `width` dspClk cycles. Both boards get
 * the same `markerTime` — after the `timeOffset` correction their pins' rising edges must
 * coincide within one dspClk cycle on the scope.
 *
 * Compares against `syncTime` (not `refTime`) deliberately: the marker verifies the *corrected*
 * alignment end-to-end, including the `timeOffset` path.
 *
 *   - Fire is the first `syncTime >= target` hit, not strict equality — robust to the moment
 *     being skipped (e.g. a `timeOffset` rewrite mid-flight); after the arm-time future check
 *     the two coincide.
 *   - Marker rise = one cycle after `syncTime === markerTime` (the stretch register), identical
 *     on both boards, cancels on the scope.
 *   - `period =/= 0`: auto re-arm at `target + period` on every fire, for continuous scope
 *     triggering; `period === 0` is one-shot (disarm on fire).
 *   - Arming with `markerTime <= syncTime` (already past) does not arm and raises `missed`,
 *     cleared by the next successful arm.
 */
case class SyncMarker() extends Component {
  val io = new Bundle {
    val syncTime   = in UInt (64 bits)
    val arm        = in Bool ()
    val markerTime = in UInt (64 bits)
    val width      = in UInt (8 bits)
    val period     = in UInt (32 bits)
    val marker     = out Bool ()
    val armed      = out Bool ()
    val missed     = out Bool ()
  }

  val target  = Reg(UInt(64 bits)) init 0
  val armed   = Reg(Bool()) init False
  val missed  = Reg(Bool()) init False
  val stretch = Reg(UInt(8 bits)) init 0

  val fire = armed && io.syncTime >= target

  when(stretch =/= 0) { stretch := stretch - 1 }
  when(fire) {
    stretch := io.width
    when(io.period =/= 0) {
      target := target + io.period
    } otherwise {
      armed := False
    }
  }
  when(io.arm) { // host action wins over a same-cycle fire
    when(io.markerTime > io.syncTime) {
      target := io.markerTime
      armed := True
      missed := False
    } otherwise {
      armed := False
      missed := True
    }
  }

  io.marker := stretch =/= 0
  io.armed := armed
  io.missed := missed
}
