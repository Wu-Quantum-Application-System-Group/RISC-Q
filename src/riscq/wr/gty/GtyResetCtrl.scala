package riscq.wr.gty

import spinal.core._
import spinal.lib._

/**
 * GT reset sequencing (spec 01 §3) — the wizard's `gtwiz_reset` reduced to one TX/RX pair on
 * QPLL0, running on `clk_free` (100 MHz). Sequence:
 *
 *   release QPLL reset → wait `qpllLock` → release the TX resets → wait `txPmaResetDone` →
 *   assert `txUserRdy` (the userclk-active delay) → wait `txResetDone` (= `txDone`) →
 *   hold the RX resets for the CDR-stable wait (a fixed count — CDR lock is not observable,
 *   like the wizard) → release → wait `rxPmaResetDone` → `rxUserRdy` → `rxResetDone`
 *   (= `rxDone`).
 *
 * `resetRxDatapath` (the dice-throw knob) redoes only the RX leg. Any wait timing out restarts
 * the full sequence and bumps `retryCount`. GT status inputs are treated as async and 2-FF
 * synchronized here; the reset outputs are plain `clk_free` levels (the GT takes them async).
 */
case class GtyResetCtrl(
    cdrStableCycles: Int = 5000, // ~50 µs at 100 MHz, the wizard-style fixed CDR wait
    userRdyCycles: Int = 64, // usrclk-active delay before TX/RXUSERRDY
    timeoutCycles: Int = 1 << 20 // per-wait watchdog → full retry
) extends Component {
  val io = new Bundle {
    val resetAll        = in Bool () // level; sequence (re)starts while high, runs on release
    val resetRxDatapath = in Bool () // pulse/level: redo the RX leg only
    // GT side
    val qpllReset = out Bool ()
    val qpllLock  = in Bool ()
    val gtTxReset, txProgDivReset, txUserRdy = out Bool ()
    val txPmaResetDone, txResetDone          = in Bool ()
    val gtRxReset, rxProgDivReset, rxUserRdy = out Bool ()
    val rxPmaResetDone, rxResetDone          = in Bool ()
    // status
    val txDone, rxDone = out Bool ()
    val retryCount     = out UInt (8 bits)
  }

  val lock    = BufferCC(io.qpllLock, init = False)
  val txPma   = BufferCC(io.txPmaResetDone, init = False)
  val txRstOk = BufferCC(io.txResetDone, init = False)
  val rxPma   = BufferCC(io.rxPmaResetDone, init = False)
  val rxRstOk = BufferCC(io.rxResetDone, init = False)

  val fsm = new Area {
    object State extends SpinalEnum {
      val ALL_RESET, WAIT_QPLL, WAIT_TX_PMA, TX_USERRDY, WAIT_TX_DONE,
          RX_CDR_HOLD, WAIT_RX_PMA, RX_USERRDY, WAIT_RX_DONE, DONE = newElement()
    }
    import State._

    val state = RegInit(ALL_RESET)
    val cnt   = Reg(UInt(log2Up(timeoutCycles) + 1 bits)) init 0
    val retry = Reg(UInt(8 bits)) init 0

    val qpllReset = Reg(Bool()) init True
    val txReset   = Reg(Bool()) init True // drives gtTxReset + txProgDivReset
    val rxReset   = Reg(Bool()) init True // drives gtRxReset + rxProgDivReset
    val txUserRdy = Reg(Bool()) init False
    val rxUserRdy = Reg(Bool()) init False
    val txDone    = Reg(Bool()) init False
    val rxDone    = Reg(Bool()) init False

    val waiting = state =/= ALL_RESET && state =/= RX_CDR_HOLD && state =/= DONE
    cnt := cnt + 1
    when(waiting && cnt === timeoutCycles) { // hung wait → full retry (counted)
      state := ALL_RESET
      cnt := 0
      retry := retry + 1
    }

    switch(state) {
      is(ALL_RESET) { // hold everything (incl. the QPLL) in reset for a settle window
        qpllReset := True
        txReset := True
        rxReset := True
        txUserRdy := False
        rxUserRdy := False
        txDone := False
        rxDone := False
        when(io.resetAll) { cnt := 0 }
        when(!io.resetAll && cnt >= 16) {
          qpllReset := False
          cnt := 0
          state := WAIT_QPLL
        }
      }
      is(WAIT_QPLL) {
        when(lock) {
          txReset := False
          cnt := 0
          state := WAIT_TX_PMA
        }
      }
      is(WAIT_TX_PMA) {
        when(txPma) { cnt := 0; state := TX_USERRDY }
      }
      is(TX_USERRDY) { // model the userclk-active delay, then declare the user side ready
        when(cnt === userRdyCycles) {
          txUserRdy := True
          cnt := 0
          state := WAIT_TX_DONE
        }
      }
      is(WAIT_TX_DONE) {
        when(txRstOk) {
          txDone := True
          cnt := 0
          state := RX_CDR_HOLD
        }
      }
      is(RX_CDR_HOLD) { // hold the RX reset for the fixed CDR-stabilization wait
        rxReset := True
        rxUserRdy := False
        rxDone := False
        when(cnt === cdrStableCycles) {
          rxReset := False
          cnt := 0
          state := WAIT_RX_PMA
        }
      }
      is(WAIT_RX_PMA) {
        when(rxPma) { cnt := 0; state := RX_USERRDY }
      }
      is(RX_USERRDY) {
        when(cnt === userRdyCycles) {
          rxUserRdy := True
          cnt := 0
          state := WAIT_RX_DONE
        }
      }
      is(WAIT_RX_DONE) {
        when(rxRstOk) {
          rxDone := True
          state := DONE
        }
      }
      is(DONE) {}
    }

    when(io.resetAll) { state := ALL_RESET; cnt := 0 } // host-held level: dominates, not a counted retry
    when(io.resetRxDatapath && (state === DONE || state === WAIT_RX_PMA ||
        state === RX_USERRDY || state === WAIT_RX_DONE)) {
      cnt := 0
      state := RX_CDR_HOLD // the dice-throw: RX leg only
    }
  }
  io.qpllReset := fsm.qpllReset
  io.gtTxReset := fsm.txReset
  io.txProgDivReset := fsm.txReset
  io.gtRxReset := fsm.rxReset
  io.rxProgDivReset := fsm.rxReset
  io.txUserRdy := fsm.txUserRdy
  io.rxUserRdy := fsm.rxUserRdy
  io.txDone := fsm.txDone
  io.rxDone := fsm.rxDone
  io.retryCount := fsm.retry
}

/**
 * TX/RX buffer-bypass phase alignment, single-lane **auto mode** (spec 01 §4, the wizard's
 * `gtwiz_buffbypass_*` FSM): after the datapath reset completes, pulse `DLYSRESET`, wait
 * `DLYSRESETDONE`, then wait `SYNCDONE` → `done`. The auto-mode statics
 * (`SYNCMODE = 1`, `SYNCALLIN = PHALIGNDONE`, everything else 0) are wired in `WrGtyPhy`.
 * Runs in the respective user-clock domain; a timeout raises `error` and re-arms (the reset
 * controller's retry then re-runs the whole leg).
 */
case class BufferBypassCtrl(timeoutCycles: Int = 1 << 17) extends Component {
  val io = new Bundle {
    val start         = in Bool () // level: the leg's resetDone
    val dlySReset     = out Bool ()
    val dlySResetDone = in Bool ()
    val syncDone      = in Bool ()
    val done          = out Bool ()
    val error         = out Bool ()
  }
  val startCc = BufferCC(io.start, init = False)
  val doneCc  = BufferCC(io.dlySResetDone, init = False)
  val syncCc  = BufferCC(io.syncDone, init = False)

  object State extends SpinalEnum { val IDLE, DLY_RESET, WAIT_SYNC, DONE = newElement() }
  import State._
  val state = RegInit(IDLE)
  val cnt   = Reg(UInt(log2Up(timeoutCycles) + 1 bits)) init 0
  val error = Reg(Bool()) init False

  io.dlySReset := state === DLY_RESET
  cnt := cnt + 1
  switch(state) {
    is(IDLE) {
      when(startCc) { cnt := 0; state := DLY_RESET }
    }
    is(DLY_RESET) {
      when(doneCc) { cnt := 0; state := WAIT_SYNC }
    }
    is(WAIT_SYNC) {
      when(syncCc) { state := DONE }
    }
    is(DONE) {}
  }
  when(state =/= IDLE && state =/= DONE && cnt === timeoutCycles) {
    error := True
    state := IDLE
  }
  when(!startCc) { state := IDLE } // the leg went back into reset
  io.done := state === DONE
  io.error := error
}
