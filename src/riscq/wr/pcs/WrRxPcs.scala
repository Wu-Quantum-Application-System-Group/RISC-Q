package riscq.wr.pcs

import spinal.core._
import spinal.lib._

/**
 * RX PCS (spec 02 §3): decodes the aligned 20-bit raw words (comma at symbol 0 by construction,
 * spec 01 §5), reassembles frames, and raises the RX timestamp trigger at SOF.
 *
 *   - Sync monitor: saturating counter — +1 per clean comma, −4 per code/disparity error, hold
 *     otherwise (frames are comma-less but clean); `synced` at ≥ 4. Loss-of-sync counted.
 *   - Frames: on SOF (symbol 0, while synced) collect bytes until EOF; CRC16 residue checked
 *     over the whole body. Any mid-frame code/disparity error, unexpected K, overlength, missing
 *     minimum length, busy buffers, or bad CRC drops the frame and counts — only complete
 *     CRC-good messages are delivered on `io.frame` (TYPE..PAYLOAD, CRC stripped), so delivery
 *     itself is the `crcOk` flag.
 *   - `io.rxTrigger` (t2/t4) pulses on every SOF seen while synced, delivered or not, at a fixed
 *     pipeline offset: input register + trigger register = SOF word on `io.rxDataRaw` + 2 cycles
 *     (the latency constant asserted by `WrPcsLoopSim`, spec 02 §5).
 *   - Reassembly buffer is a 2-slot ping-pong (16-bit-wide, single write port) so a frame can be
 *     collected while the previous one drains onto the 1 B/cycle output stream.
 *   - Cal detector: consecutive-K28.7 counter, status at ≥ 2^calThreshLog2 symbols.
 *
 * Everything runs in `clk_rx`; the message stream and counters are CDC'd by the node (06 §1).
 */
case class WrRxPcs(calThreshLog2: Int = 17) extends Component {
  val io = new Bundle {
    val rxDataRaw   = in Bits (20 bits)
    val frame       = master Stream (Fragment(Bits(8 bits)))
    val rxTrigger   = out Bool ()
    val synced      = out Bool ()
    val calDetected = out Bool ()
    val codeErrCnt  = out UInt (16 bits)
    val dispErrCnt  = out UInt (16 bits)
    val crcErrCnt   = out UInt (16 bits)
    val droppedCnt  = out UInt (16 bits)
    val syncLossCnt = out UInt (16 bits)
  }
  import WrSymbols._

  def satInc(c: UInt, cond: Bool): Unit = when(cond && c =/= c.maxValue) { c := c + 1 }

  val decode = new Area {
    val raw  = RegNext(io.rxDataRaw) init 0
    val rd   = Reg(Bool()) init False
    val dec0 = Dec8b10b()
    val dec1 = Dec8b10b()
    dec0.io.code := raw(9 downto 0)
    dec0.io.rdIn := rd
    dec1.io.code := raw(19 downto 10)
    dec1.io.rdIn := dec0.io.rdOut
    rd := dec1.io.rdOut
    val err0   = dec0.io.codeErr || dec0.io.dispErr
    val err1   = dec1.io.codeErr || dec1.io.dispErr
    val anyErr = err0 || err1
  }
  import decode._

  val sync = new Area {
    val cnt   = Reg(UInt(3 bits)) init 0
    val comma = dec0.io.isK && dec0.io.data === K28_5 && !anyErr
    when(anyErr) {
      cnt := (cnt < 4) ? U(0) | (cnt - 4)
    } elsewhen (comma && cnt =/= 7) {
      cnt := cnt + 1
    }
    val synced  = cnt >= 4
    val lossCnt = Reg(UInt(16 bits)) init 0
    satInc(lossCnt, RegNext(synced, False) && !synced)
  }
  io.synced := sync.synced

  val counters = new Area {
    val codeErr = Reg(UInt(16 bits)) init 0
    val dispErr = Reg(UInt(16 bits)) init 0
    val crcErr  = Reg(UInt(16 bits)) init 0
    val dropped = Reg(UInt(16 bits)) init 0
    satInc(codeErr, sync.synced && (dec0.io.codeErr || dec1.io.codeErr))
    satInc(dispErr, sync.synced && (dec0.io.dispErr || dec1.io.dispErr))
  }
  io.codeErrCnt := counters.codeErr
  io.dispErrCnt := counters.dispErr
  io.crcErrCnt := counters.crcErr
  io.droppedCnt := counters.dropped
  io.syncLossCnt := sync.lossCnt

  val cal = new Area {
    val max   = (BigInt(1) << (calThreshLog2 + 1)) - 1
    val cnt   = Reg(UInt(calThreshLog2 + 1 bits)) init 0
    val isCal0 = dec0.io.isK && dec0.io.data === K28_7 && !err0
    val isCal1 = dec1.io.isK && dec1.io.data === K28_7 && !err1
    when(isCal0 && isCal1) {
      when(cnt < max - 1) { cnt := cnt + 2 } otherwise { cnt := max }
    } otherwise { cnt := 0 }
    io.calDetected := cnt >= (BigInt(1) << calThreshLog2)
  }

  // 2-slot ping-pong reassembly buffer; byte i of a slot = half (15:8) of word i/2 when i even.
  val slots = new Area {
    val buf   = Mem(Bits(16 bits), 64)
    val valid = Vec(Reg(Bool()) init False, 2)
    val len   = Vec(Reg(UInt(6 bits)) init 0, 2)
  }

  val frame = new Area {
    object St extends SpinalEnum { val HUNT, COLLECT = newElement() }
    import St._

    val state     = RegInit(HUNT)
    val slot      = Reg(Bool()) init False
    val wordIdx   = Reg(UInt(5 bits)) init 0
    val pend      = Reg(Bits(8 bits)) init 0
    val pendValid = Reg(Bool()) init False
    val byteCnt   = Reg(UInt(7 bits)) init 0
    val crc       = Reg(Bits(16 bits)) init 0

    val sof0 = dec0.io.isK && dec0.io.data === K27_7 && !err0
    val eof0 = dec0.io.isK && dec0.io.data === K29_7 && !err0
    val eof1 = dec1.io.isK && dec1.io.data === K29_7 && !err1

    // ONE write port, enable-gated: the FSM branches calling bufWrite are mutually exclusive
    // per cycle (last assignment would win regardless, matching multi-port ordering), and a
    // single port is what lets Vivado infer a distributed RAM — one `Mem.write` per call site
    // emits one write port EACH, which synthesis un-infers into LUT feedback loops
    // (fatal DRC LUTLP-1 at implementation; docs/soc/SOC_TIPS.md §8.9).
    val bufWrEn   = False
    val bufWrData = B(0, 16 bits)
    slots.buf.write(slot.asUInt @@ wordIdx, bufWrData, enable = bufWrEn)
    def bufWrite(word: Bits): Unit = { bufWrEn := True; bufWrData := word }

    val drop = False // handled after the switch: drop wins over any branch's state assignment

    // finalize with `crcFin` = residue over all body bytes incl. CRC, `cnt` = total body bytes
    def finalize(crcFin: Bits, cnt: UInt): Unit = {
      state := HUNT
      when(crcFin =/= 0) {
        satInc(counters.crcErr, True)
      } elsewhen (cnt < 3) {
        drop := True
      } otherwise {
        slots.valid(slot.asUInt) := True
        slots.len(slot.asUInt) := (cnt - 2).resized
        slot := !slot
      }
    }

    io.rxTrigger := RegNext(state === HUNT && sync.synced && sof0) init False

    switch(state) {
      is(HUNT) {
        when(sync.synced && sof0) {
          when(slots.valid(slot.asUInt)) {
            drop := True // both buffers busy — drop the whole incoming frame
          } elsewhen (err1 || dec1.io.isK) {
            drop := True // TYPE byte already broken
          } otherwise {
            state := COLLECT
            pend := dec1.io.data
            pendValid := True
            byteCnt := 1
            crc := Crc16.step(B(Crc16.init, 16 bits), dec1.io.data)
            wordIdx := 0
          }
        }
      }
      is(COLLECT) {
        val b0 = dec0.io.data
        val b1 = dec1.io.data
        when(eof0) { // frame ended on the previous cycle's bytes; sym1 is fill
          when(pendValid) { bufWrite(pend ## B(0, 8 bits)) }
          finalize(crc, byteCnt)
        } elsewhen (err0 || dec0.io.isK) {
          drop := True
        } elsewhen (eof1) { // one final byte + EOF
          when(pendValid) { bufWrite(pend ## b0) } otherwise { bufWrite(b0 ## B(0, 8 bits)) }
          finalize(Crc16.step(crc, b0), byteCnt + 1)
        } elsewhen (err1 || dec1.io.isK) {
          drop := True
        } elsewhen (byteCnt > 64) { // no EOF in any legal frame length
          drop := True
        } otherwise { // two payload bytes
          when(pendValid) {
            bufWrite(pend ## b0)
            wordIdx := wordIdx + 1
            pend := b1
          } otherwise {
            bufWrite(b0 ## b1)
            wordIdx := wordIdx + 1
          }
          crc := Crc16.step(Crc16.step(crc, b0), b1)
          byteCnt := byteCnt + 2
        }
      }
    }

    when(drop) { state := HUNT }
    satInc(counters.dropped, drop)
  }

  val drain = new Area {
    val active = Reg(Bool()) init False
    val dSlot  = Reg(Bool()) init False
    val idx    = Reg(UInt(6 bits)) init 0
    when(!active && slots.valid(dSlot.asUInt)) {
      active := True
      idx := 0
    }
    val len  = slots.len(dSlot.asUInt)
    val word = slots.buf.readAsync(dSlot.asUInt @@ idx(5 downto 1))
    io.frame.valid := active
    io.frame.fragment := idx(0) ? word(7 downto 0) | word(15 downto 8)
    io.frame.last := idx === len - 1
    when(io.frame.fire) {
      idx := idx + 1
      when(io.frame.last) {
        active := False
        slots.valid(dSlot.asUInt) := False
        dSlot := !dSlot
      }
    }
  }
}
