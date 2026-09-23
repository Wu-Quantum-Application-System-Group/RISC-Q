package riscq.soc.link

import spinal.core._
import spinal.lib._
import riscq.wr.pcs.{WrRxPcs, WrTxPcs}

/**
 * One put on the board-to-board lane (specs/cross-core/02 §5, D5): a 72-bit message the WR PCS
 * carries as a `PUT` frame. `board` is the source board of an arrival / the destination board of a
 * release; `aux` the source core of an arrival; `node`/`offset`/`data` the put itself. For a barrier
 * node, `offset == 0` is an arrival (data = the member count) and `offset != 0` a release from the
 * root (offset = the destination board's arriver mask, data = the released time).
 */
case class PutFrame() extends Bundle {
  val board  = UInt(4 bits)
  val aux    = UInt(8 bits)
  val node   = UInt(12 bits)
  val offset = UInt(16 bits)
  val data   = Bits(32 bits)
}

object PutFrame {
  val TYPE   = 0x50           // the WR frame TYPE byte of a put frame ('P'; markers are 1)
  val bytes  = 9              // the message payload: 72 bits
  val length = 2 + bytes      // TYPE, SEQ, payload
}

/** `Stream(PutFrame)` → the WR TX PCS's byte fragments: TYPE, a running SEQ, the 9 payload bytes. */
case class PutFramePacker() extends Area {
  val in  = Stream(PutFrame())
  val out = Stream(Fragment(Bits(8 bits)))
  val seq = Reg(UInt(8 bits)) init 0
  val idx = Reg(UInt(4 bits)) init 0
  val payload = in.payload.asBits
  val byteOf  = Vec((0 until PutFrame.bytes).map(i => payload(8 * i, 8 bits)))
  val last    = idx === PutFrame.length - 1
  out.valid := in.valid
  out.last  := last
  out.fragment := idx.mux(
    0 -> B(PutFrame.TYPE, 8 bits),
    1 -> seq.asBits,
    default -> byteOf((idx - 2).resize(4)))
  in.ready := out.fire && last
  when(out.fire) {
    idx := last ? U(0) | idx + 1
    when(last)(seq := seq + 1)
  }
}

/** The WR RX PCS's byte fragments of one `PUT` frame → `Flow(PutFrame)`; a frame of the wrong length
  * is dropped (the PCS already dropped anything with a bad CRC). Always ready. */
case class PutFrameUnpacker() extends Area {
  val in  = Stream(Fragment(Bits(8 bits)))
  val out = Flow(PutFrame())
  val idx = Reg(UInt(5 bits)) init 0
  val buf = Reg(Bits(8 * PutFrame.bytes bits)) init 0
  in.ready := True
  out.valid := False
  out.payload.assignFromBits(buf)
  when(in.fire) {
    when(idx >= 2 && idx < PutFrame.length) {
      val i = (idx - 2).resize(4)
      buf((i << 3).resize(log2Up(8 * PutFrame.bytes)), 8 bits) := in.fragment
    }
    idx := idx + 1
    when(in.last) {
      idx := 0
      out.valid := idx === PutFrame.length - 1
    }
  }
}

/** Routes one received frame whole by its first byte: `put` for a `PUT` frame, `host` for anything
  * else (the WR markers software reads). */
case class FrameRouter() extends Area {
  val in   = Stream(Fragment(Bits(8 bits)))
  val first  = Reg(Bool()) init True
  val selReg = Reg(Bool()) init False
  val isPut  = in.fragment === PutFrame.TYPE
  val sel    = first ? isPut | selReg
  when(in.fire) {
    first := in.last
    when(first)(selReg := isPut)
  }
  val outs = StreamDemux(in, sel.asUInt, 2)
  val host = outs(0)
  val put  = outs(1)
}

/**
 * The lane: the WR TX/RX PCS pair with the put path beside the host's marker frames
 * (specs/cross-core/02 D5). TX: put frames (dspCd) cross into `refCd`, are packed, and share the PCS
 * with the host's frames through a fragment-locked arbiter that gives the host (the markers)
 * priority. RX: every CRC-good frame is routed whole — `PUT` to the unpacker and a crossing into
 * `dspCd`, everything else to the host. Timestamps are unaffected: the PCS raises its triggers at the
 * physical SOF, so a put frame ahead of a marker delays it without biasing the offset.
 */
case class PutLane(refCd: ClockDomain, rxCd: ClockDomain, dspCd: ClockDomain, calThreshLog2: Int = 17) extends Area {
  val putTx  = Stream(PutFrame())                    // dspCd: puts for the other board
  val putRx  = Flow(PutFrame())                      // dspCd: puts from the other board
  val hostTx = Stream(Fragment(Bits(8 bits)))        // refCd: the host's frames (markers)
  val hostRx = Stream(Fragment(Bits(8 bits)))        // rxCd: frames for the host

  val txFifo = new StreamFifoCC(PutFrame(), depth = 16, pushClock = dspCd, popClock = refCd, withPopBufferedReset = false)
  txFifo.io.push << putTx
  val tx = refCd { new Area {
    val packer = PutFramePacker()
    packer.in << txFifo.io.pop
    val arb = StreamArbiterFactory().lowerFirst.fragmentLock.on(Seq(hostTx, packer.out))
    val pcs = WrTxPcs()
    pcs.io.frame << arb
  }}

  val rxFifo = new StreamFifoCC(PutFrame(), depth = 16, pushClock = rxCd, popClock = dspCd, withPopBufferedReset = false)
  val rx = rxCd { new Area {
    val pcs = WrRxPcs(calThreshLog2)
    val router = FrameRouter()
    router.in << pcs.io.frame
    hostRx << router.host
    val unpacker = PutFrameUnpacker()
    unpacker.in << router.put
    rxFifo.io.push << unpacker.out.toStream   // a burst beyond the FIFO is dropped (posted semantics)
  }}
  putRx << rxFifo.io.pop.toFlow
}
