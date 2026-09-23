package riscq.wr.pcs

import spinal.core._
import spinal.lib._

/**
 * TX PCS (spec 02 §2): turns host messages into 8b/10b frames on the 20-bit raw GTY pipe, one
 * word (2 symbols) every `clk_ref` cycle. Wire format (spec 02 §4):
 *
 *   /I/../I/ SOF(K27.7) msg[0..N) CRC16(hi,lo) EOF(K29.7) [fill] /I/..
 *
 * `io.frame` carries the message bytes (TYPE SEQ PAYLOAD, ≤ 62 B); the CRC is appended here.
 * The byte stream delivers at most 1 B/cycle but the line needs 2, so the message is slurped
 * into a small buffer first (idle keeps running) and blasted back-to-back once complete — this
 * implements the "FIFO holds a full message before send" contract structurally. Oversize
 * messages (> 62 B) are consumed and discarded (`io.oversize` pulses).
 *
 * Framing details:
 *   - idle is 1000Base-X /I1/(K28.5,D5.6) //I2/(K28.5,D16.2), chosen to leave RD− after every
 *     idle word; comma always on symbol 0. A new frame starts only after ≥ 2 idle cycles.
 *   - SOF always sits at symbol 0; EOF lands at symbol 1 (odd N) or at symbol 0 with a D16.2
 *     fill at symbol 1 (even N) — never a comma at an odd position.
 *   - `io.txTrigger` pulses exactly in the cycle the SOF word is on `io.txDataRaw` (both are
 *     registered once): the t1/t3 timestamp event. PCS trigger→port latency constant = 0 cycles
 *     (asserted by `WrPcsLoopSim`, spec 02 §5).
 *   - `io.calMode` (entered/left from idle): continuous K28.7, the calibration square wave.
 */
case class WrTxPcs() extends Component {
  val maxMsgBytes = 62

  val io = new Bundle {
    val frame     = slave Stream (Fragment(Bits(8 bits)))
    val txDataRaw = out Bits (20 bits)
    val txTrigger = out Bool ()
    val calMode   = in Bool ()
    val oversize  = out Bool ()
  }
  import WrSymbols._

  val buf     = Mem(Bits(8 bits), 64)
  val loadCnt = Reg(UInt(6 bits)) init 0 // message length once loaded
  val loaded  = Reg(Bool()) init False

  val load = new Area {
    val dropping = Reg(Bool()) init False
    io.frame.ready := !loaded
    io.oversize := False
    when(io.frame.fire) {
      when(!dropping) {
        buf.write(loadCnt, io.frame.fragment)
        loadCnt := loadCnt + 1
        when(loadCnt === maxMsgBytes - 1 && !io.frame.last) { dropping := True }
      }
      when(io.frame.last) {
        when(dropping) {
          loadCnt := 0
          dropping := False
          io.oversize := True
        } otherwise {
          loaded := True
        }
      }
    }
  }

  val fsm = new Area {
    object State extends SpinalEnum {
      val IDLE, SOF, DATA, CRC_PAIR, LO_EOF, EOF_FILL, CAL = newElement()
    }
    import State._

    val state   = RegInit(IDLE)
    val idx     = Reg(UInt(6 bits)) init 0
    val crc     = Reg(Bits(16 bits)) init 0
    val idleCnt = Reg(UInt(2 bits)) init 0
    val rd      = Reg(Bool()) init False // False = RD−

    val byte0 = buf.readAsync(idx)
    val byte1 = buf.readAsync((state === SOF) ? U(0, 6 bits) | (idx + 1))

    // symbol pair for this cycle (sym0 first on the wire)
    val symData0, symData1 = Bits(8 bits)
    val symK0, symK1       = Bool()
    // idle defaults: /I1/ when RD+ (flip to −), /I2/ when RD− (preserve −)
    symData0 := K28_5
    symK0 := True
    symData1 := rd ? B(D5_6, 8 bits) | B(D16_2, 8 bits)
    symK1 := False

    val remaining = loadCnt - idx
    val finish    = False
    when(finish) { loaded := False; loadCnt := 0 }

    switch(state) {
      is(IDLE) {
        idleCnt := (idleCnt === 3) ? idleCnt | (idleCnt + 1)
        when(io.calMode) {
          state := CAL
          idleCnt := 0
        } elsewhen (loaded && idleCnt >= 2) {
          state := SOF
          idleCnt := 0
        }
      }
      is(SOF) {
        symData0 := K27_7
        symK0 := True
        symData1 := byte1
        symK1 := False
        crc := Crc16.step(B(Crc16.init, 16 bits), byte1)
        idx := 1
        state := (loadCnt === 1) ? CRC_PAIR | DATA
      }
      is(DATA) {
        symData0 := byte0
        symK0 := False
        when(remaining === 1) { // last byte + CRC hi
          val crcFin = Crc16.step(crc, byte0)
          symData1 := crcFin(15 downto 8)
          crc := crcFin
          state := LO_EOF
        } otherwise {
          symData1 := byte1
          crc := Crc16.step(Crc16.step(crc, byte0), byte1)
          idx := idx + 2
          when(remaining === 2) { state := CRC_PAIR }
        }
      }
      is(CRC_PAIR) { // N even: CRC hi + lo, EOF next cycle
        symData0 := crc(15 downto 8)
        symData1 := crc(7 downto 0)
        symK0 := False
        symK1 := False
        state := EOF_FILL
      }
      is(LO_EOF) { // N odd: CRC lo + EOF
        symData0 := crc(7 downto 0)
        symK0 := False
        symData1 := K29_7
        symK1 := True
        state := IDLE
        finish := True
      }
      is(EOF_FILL) { // N even: EOF + fill (never a comma at symbol 1)
        symData0 := K29_7
        symK0 := True
        symData1 := D16_2
        symK1 := False
        state := IDLE
        finish := True
      }
      is(CAL) {
        symData0 := K28_7
        symK0 := True
        symData1 := K28_7
        symK1 := True
        when(!io.calMode) { state := IDLE }
      }
    }

    val enc0 = Enc8b10b()
    val enc1 = Enc8b10b()
    enc0.io.data := symData0
    enc0.io.isK := symK0
    enc0.io.rdIn := rd
    enc1.io.data := symData1
    enc1.io.isK := symK1
    enc1.io.rdIn := enc0.io.rdOut
    rd := enc1.io.rdOut

    io.txDataRaw := RegNext(enc1.io.code ## enc0.io.code) init 0
    io.txTrigger := RegNext(state === SOF) init False
  }
}
