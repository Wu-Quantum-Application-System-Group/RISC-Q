package riscq.soc.dio

import spinal.core._
import spinal.lib._
import riscq.dsp.Complex
import riscq.dsp.pulse.TimedQueue
import riscq.soc.link.{EventLink, EventSource, RfCmd}
import riscq.soc.rf.{Channel, PulseParamBuffer, PulseParamBufferParams}

/**
 * Timed digital I/O — the first non-pulse channel kind (specs/universal-control/01 P5; the
 * neutral-atom plan §2.3). A bank of 16 output lines and 16 input lines behind the SAME posted register
 * file as a pulse channel ([[PulseParamBuffer]]): a slot is `{mask (the phase word), value (the amp
 * word), dur}`, `fire` schedules it at the channel's `startTime` and auto-advances `startTime` by `dur`,
 * so a TTL train is one `play` + N−1 bare fires exactly like a pulse train, and every existing op
 * (`set_start`, `fire`, `play`, `init_pulse_params`) drives it unchanged. Two limits it shares with the
 * pulse channels: at most `queueDepth` (4) entries scheduled ahead (a push into a full queue is
 * dropped — pace trains as `riscq.cal.base.TRAIN_AHEAD` does), and the queue pops at most every other
 * cycle, so the shortest hold (`dur`) of an entry followed by another is 2 batches. At the scheduled batch the
 * output register takes `(out & ~mask) | (value & mask)` — placed at cycle precision by a
 * [[TimedQueue]], the core only schedules by lead time.
 *
 * Inputs are sampled every cycle; any change posts an event `{changed lines, new levels}` stamped
 * with the batch time of the sample, into the core's `fifo` sink (consume-on-read, with a sequence
 * number) — "wait for exposure-out" and "when exactly did the shutter open" are one halting read.
 */
case class TimedDio(
    slots: Int,
    timeWidth: Int = 32,
    durWidth: Int = 16,
    queueDepth: Int = 4,
    rfAddrWidth: Int = 16
) extends Component with Channel {
  val lines = 16                      // the buffer's 16-bit mask/value fields
  val io = new Bundle {
    val cmd       = slave port Flow(RfCmd(rfAddrWidth))
    val timeBcast = in    port UInt(timeWidth bits)
    val dout      = out   port Bits(lines bits)
    val din       = in    port Bits(lines bits)
    val event     = master port Flow(Bits(2 * lines bits))   // {changed, levels} on any input change
    val eventTime = out   port UInt(timeWidth bits)          // the batch the changed sample was taken
    val time      = out   port UInt(timeWidth bits)          // the buffer's local time copy (sims align on it)
  }
  def cmd = io.cmd; def timeBcast = io.timeBcast
  def memPort = None; def envLanes = 0; def dacOut = None; def carrier = None

  val buf = PulseParamBuffer(PulseParamBufferParams(
    pulseNum = slots, dataWidth = lines, envAddrWidth = 1, durWidth = durWidth,
    timeWidth = timeWidth, addrWidth = rfAddrWidth))
  buf.io.cmd << io.cmd
  buf.io.timeBcast := io.timeBcast

  // ── the scheduled output update: {mask, value} due at the fired slot's startTime ──
  case class Term() extends Bundle {
    val mask  = Bits(lines bits)
    val value = Bits(lines bits)
  }
  val queue = TimedQueue(Term(), timeWidth, queueDepth, leadTime = TimedDio.leadTime)
  queue.io.time := buf.io.time
  queue.io.push.valid := buf.io.phase.valid                 // the fired slot's fields land together
  queue.io.push.payload.data.mask  := buf.io.phase.payload.asBits
  queue.io.push.payload.data.value := buf.io.amp.payload.asBits
  queue.io.push.payload.startTime  := buf.io.startTime      // a push into a full queue is dropped (posted)
  val outReg = Reg(Bits(lines bits)) init 0
  when(queue.io.pop.valid) {
    outReg := (outReg & ~queue.io.pop.payload.mask) | (queue.io.pop.payload.value & queue.io.pop.payload.mask)
  }
  io.dout := outReg

  // ── input edges → events with the sample's batch time ──
  val inReg  = RegNext(io.din) init 0
  val inPrev = RegNext(inReg) init 0
  val changed = inReg ^ inPrev
  io.event.valid   := changed =/= 0
  io.event.payload := changed ## inReg
  io.eventTime     := RegNext(buf.io.time)                   // the batch the changed sample was taken
  io.time          := buf.io.time
  override def event: Option[EventSource] = Some(EventSource(EventLink.fifoKind, io.event, Some(io.eventTime)))
}

object TimedDio {
  /** The queue pops one cycle before the scheduled batch so the registered output changes ON it. */
  val leadTime = 1
  val eventDataWidth = 32                                    // {changed[15:0], levels[15:0]}
}
