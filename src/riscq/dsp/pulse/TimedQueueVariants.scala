package riscq.dsp.pulse

import spinal.core._
import spinal.lib._

/**
 * Alternative [[TimedQueue]] implementations, kept for reference / swap-in. The active toplevel uses
 * the RISC-Q-style [[TimedQueue]] (registered compare, leanest LUT count, but no back-to-back II=1).
 * These two both keep '''II=1''' (drain consecutive due entries one-per-cycle) and '''latency 0'''
 * (combinational pop cycle), at a higher LUT cost. All three share the same `io` and the same external
 * contract (pop fires at `time = startTime − leadTime`), so any one can be dropped into the toplevel by
 * renaming:
 *
 * {{{
 *   queue                 II      latency   notes
 *   RISC-Q TimedQueue     ≥2        0       registered compare, StreamFifo (active)
 *   TimedQueueRegArray     1        0       per-slot registered due bits
 *   TimedQueueDeadline     1        0       StreamFifo, combinational compare
 * }}}
 */

/**
 * '''Register-array''' variant. A depth-`depth` register buffer where each slot keeps its data,
 * precomputed `deadline = startTime − lead`, and a '''registered''' due bit maintained every cycle via
 * the identity `RegNext(time+1 ≥ d) ≡ (time ≥ d)` (valid because `io.time` is a +1/cycle counter). All
 * slots are maintained (not just the head), so the next head's due bit is already correct when the
 * current entry pops ⇒ '''II=1''' back-to-back, with the 32-bit compares register-to-register (off the
 * `pop.valid → consumer` path). Costs one per-slot comparator vs the RISC-Q queue's single one.
 */
case class TimedQueueRegArray[T <: Data](
    dataType: HardType[T],
    timeWidth: Int,
    depth: Int,
    leadTime: Int,
    timeOffset: Int = 0
) extends Component with TimedQueueLike[T] {
  require(depth >= 1, "queue needs at least one entry")
  require(leadTime + timeOffset >= 0, "lead time must be non-negative")
  require((leadTime + timeOffset) < (BigInt(1) << timeWidth), "lead time must fit in timeWidth bits")

  val io = TimedQueueIo(dataType, timeWidth)

  val leadC = U(leadTime + timeOffset, timeWidth bits)

  val data     = Vec.fill(depth)(Reg(dataType()))
  val deadline = Vec.fill(depth)(Reg(UInt(timeWidth bits)))
  val dueBit   = Vec.fill(depth)(RegInit(False))
  val ptrW     = log2Up(depth)
  val rd       = Reg(UInt(ptrW bits)) init 0
  val wr       = Reg(UInt(ptrW bits)) init 0
  val count    = Reg(UInt(log2Up(depth + 1) bits)) init 0

  val full  = count === depth
  val empty = count === 0

  def dueOf(d: UInt): Bool = !(io.time + U(1, timeWidth bits) - d).msb
  for (s <- 0 until depth) dueBit(s) := dueOf(deadline(s)) // maintain ALL slots ⇒ next head ready ⇒ II=1

  io.pop.valid   := !empty && dueBit(rd)
  io.pop.payload := data(rd)
  io.push.ready  := !full

  val doPop  = io.pop.valid
  val doPush = io.push.valid && !full

  def inc(p: UInt): UInt =
    if (depth == 1) p else ((p === U(depth - 1, ptrW bits)) ? U(0, ptrW bits) | (p + 1).resize(ptrW bits))

  when(doPush) {
    val dl = io.push.payload.startTime - leadC
    data(wr)     := io.push.payload.data
    deadline(wr) := dl
    dueBit(wr)   := dueOf(dl)
    wr           := inc(wr)
  }
  when(doPop)(rd := inc(rd))
  count := count + U(doPush) - U(doPop)

  def latency: Int = 0
}

/**
 * '''Deadline-precompute''' variant. Keeps the standard `StreamFifo`, but stores the precomputed
 * `deadline = startTime − lead` (a constant subtract, registered into the FIFO at push) instead of
 * `startTime`, so the per-cycle pop compare is a single subtract `time − deadline` rather than an
 * add-then-subtract — bit-identical pop to the original, '''II=1''', latency 0. Leanest of the
 * II=1 options (reuses `StreamFifo`) but the compare is still combinational on the `pop.valid` path.
 */
case class TimedQueueDeadline[T <: Data](
    dataType: HardType[T],
    timeWidth: Int,
    depth: Int,
    leadTime: Int,
    timeOffset: Int = 0
) extends Component with TimedQueueLike[T] {
  require(depth >= 1, "queue needs at least one entry")
  require(leadTime + timeOffset >= 0, "lead time must be non-negative")
  require((leadTime + timeOffset) < (BigInt(1) << timeWidth), "lead time must fit in timeWidth bits")

  val io = TimedQueueIo(dataType, timeWidth)

  val fifo  = StreamFifo(TimedStored(dataType, timeWidth), depth, latency = 1)
  val leadC = U(leadTime + timeOffset, timeWidth bits)

  fifo.io.push.valid            := io.push.valid
  fifo.io.push.payload.data     := io.push.payload.data
  fifo.io.push.payload.deadline := io.push.payload.startTime - leadC // constant subtract, registered
  io.push.ready                 := fifo.io.push.ready

  val head = fifo.io.pop
  val due  = !(io.time - head.payload.deadline).msb // wrap-safe geq, single subtract

  io.pop.valid   := head.valid && due
  io.pop.payload := head.payload.data
  head.ready     := io.pop.valid

  def latency: Int = 0
}
