package riscq.dsp.pulse

import spinal.core._
import spinal.lib._
import riscq.dsp._

case class EnvelopeReaderParams(
    batchSize: Int = 16, // N lanes (one DAC sample each)
    dataWidth: Int = 16, // w: SF width of each re/im
    addrWidth: Int = 12, // envelope memory address width
    memLatency: Int = 2  // external mem cmd → rsp cycles
) {
  require(batchSize >= 1 && dataWidth >= 2 && addrWidth >= 1)
  require(memLatency >= 1, "the external memory takes at least one cycle (sync read)")
  /** envelope memory word width: `N` complex lanes × 2 × `w`. */
  def wordWidth: Int = batchSize * 2 * dataWidth
}

/**
 * Complex-envelope memory front-end, the complex analogue of
 * the reference `PulseMemReader`. An address register loads from `io.addr` and otherwise
 * '''free-runs''' (auto-increments every cycle, as the reference — the junk between pulses is masked
 * by the toplevel's duration gate). It drives an external `MemReadPort` (cmd always valid; the RAM +
 * its Tilelink write path are phase-2) and splits the returned word into `N` complex lanes.
 *
 * '''Word layout''' (shared with the phase-2 Tilelink writer): lane `k` occupies bits
 * `[(2k+2)·w−1 : 2k·w]`, with `re` in the lower half and `im` in the upper half — i.e.
 * `subdivideIn(2N slices)` gives `[re0, im0, re1, im1, …]`.
 *
 * `latency = 1 (addr reg) + memLatency + 1 (output reg)` (= `memLatency + 2`), exported.
 */
case class EnvelopeReader(p: EnvelopeReaderParams) extends Component {
  val io = new Bundle {
    val addr    = slave port Flow(UInt(p.addrWidth bits))
    val memPort = master port MemReadPort(Bits(p.wordWidth bits), p.addrWidth)
    val env     = out port ComplexBatch(p.batchSize, p.dataWidth)
  }

  // address register: load on a Flow fire, else auto-increment (free-running).
  val addrReg = Reg(UInt(p.addrWidth bits)) init (0)
  when(io.addr.valid)(addrReg := io.addr.payload).otherwise(addrReg := addrReg + 1)

  io.memPort.cmd.valid   := True       // always read; the duration gate masks the result
  io.memPort.cmd.payload := addrReg

  // split the returned word into N complex lanes per the documented layout.
  val lanes   = io.memPort.rsp.subdivideIn(2 * p.batchSize slices) // [re0, im0, re1, im1, …]
  val envComb = ComplexBatch(p.batchSize, p.dataWidth)
  for (k <- 0 until p.batchSize) {
    envComb(k).re := lanes(2 * k).asSInt
    envComb(k).im := lanes(2 * k + 1).asSInt
  }
  io.env := RegNext(envComb) // output register (keeps the split off the mem-rsp critical path)

  /** addr-load → env latency. */
  def latency: Int = 1 + p.memLatency + 1
}
