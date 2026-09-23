package riscq.wr.gty

import spinal.core._
import spinal.lib._

/**
 * Fixed-position comma alignment by RX-datapath dice-throw (spec 01 §5) — a port of wr-cores
 * `gtx_comma_detect_lp.vhd`, `clk_rx` domain.
 *
 * Scans a sliding 40-bit window (two consecutive raw words) at all 20 bit offsets for the K28.5
 * code (either disparity, LSB-first `riscq.wr` convention — the same `Enc8b10b` tables as the
 * PCS). The wr-cores hysteresis FSM declares `linkUp` after a consistent run of commas
 * (+4 per hit / −1 per miss, up at `upCount`) and drops sync after `lossCount` comma-less cycles.
 *
 * `aligned` when the locked position equals `targetPos`. When the link is up at the WRONG
 * position, one `rxResetReq` pulse is emitted (`diceCount` +1) and the FSM re-arms — the reset
 * controller then resets the RX datapath, re-randomizing the word boundary (geometric with
 * p = 1/20). There is **no barrel shifter**: with `targetPos = 0` the data path is the identity,
 * so RX bitslide is 0 by construction and no mux sits in the datapath (spec D1).
 */
case class CommaAligner(
    targetPos: Int = 0,
    upCount: Int = 500,
    lossCount: Int = 1000
) extends Component {
  val io = new Bundle {
    val rxDataRaw  = in Bits (20 bits)
    val commaPos   = out UInt (5 bits)
    val posValid   = out Bool ()
    val linkUp     = out Bool ()
    val aligned    = out Bool ()
    val rxResetReq = out Bool () // single-cycle pulse: throw the dice again
    val diceCount  = out UInt (8 bits)
  }

  // K28.5 RD− in the LSB-first wire convention (bit 0 = 'a', first on the wire)
  val K28_5 = B(riscq.wr.pcs.Enc8b10b.kCodesRdMinus(0xbc), 10 bits)

  val window = new Area {
    val d0     = RegNext(io.rxDataRaw) init 0
    val merged = io.rxDataRaw ## d0 // [39:0], bit i..i+9 = a candidate symbol
    val found  = Bits(20 bits)
    for (i <- 0 until 20) {
      val sym = merged(i + 9 downto i)
      found(i) := RegNext(sym === K28_5 || sym === ~K28_5) init False
    }
    val pos   = OHToUInt(OHMasking.first(found)).resize(5)
    val valid = RegNext(found.orR) init False
    val posR  = RegNextWhen(pos, found.orR) init 0
  }
  io.commaPos := window.posR
  io.posValid := window.valid

  val fsm = new Area {
    object State extends SpinalEnum { val LOST, CHECK, ACQUIRED = newElement() }
    import State._

    val state      = RegInit(LOST)
    val firstComma = Reg(UInt(5 bits)) init 0
    val cnt        = Reg(UInt(16 bits)) init 0
    val linkUp     = Reg(Bool()) init False
    val aligned    = Reg(Bool()) init False
    val dice       = Reg(UInt(8 bits)) init 0
    val reqPulse   = False

    val hit = window.valid && window.posR === firstComma

    switch(state) {
      is(LOST) {
        linkUp := False
        aligned := False
        when(window.valid) {
          firstComma := window.posR
          cnt := 4
          state := CHECK
        }
      }
      is(CHECK) {
        when(hit) {
          cnt := cnt + 4
        } elsewhen (cnt =/= 0) {
          cnt := cnt - 1
          when(cnt === 1) { state := LOST }
        }
        when(cnt >= upCount) {
          state := ACQUIRED
          cnt := 0
        }
      }
      is(ACQUIRED) {
        linkUp := True
        when(hit) {
          when(firstComma === targetPos) {
            aligned := True
          } otherwise { // consistent comma at the wrong position: throw the dice again
            reqPulse := True
            dice := dice + 1
            state := LOST
          }
          cnt := 0
        } otherwise {
          cnt := cnt + 1
          when(cnt === lossCount) { state := LOST }
        }
      }
    }
  }
  io.linkUp := fsm.linkUp
  io.aligned := fsm.aligned
  io.rxResetReq := fsm.reqPulse
  io.diceCount := fsm.dice
}
