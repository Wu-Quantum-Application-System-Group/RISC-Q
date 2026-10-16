package riscq.soc.rf

import spinal.core._
import spinal.lib._
import riscq.dsp.{Complex, ComplexBatch, SinCosMethod}
import riscq.dsp.pulse.{PulseGenerator, PulseGeneratorAligned, PulseGeneratorParams}
import riscq.soc.link.Put

/**
 * Converter-edge **pulse-drive channel** — the self-contained box the floorplan pins: a
 * [[PulseParamBuffer]] (DSP-side register file, driven by the demuxed posted `Flow(Put)`) + a
 * [[PulseGenerator]] + its envelope-RAM read port. Its only CPU-facing input is the `Put` Flow (plus
 * the shared `time` broadcast); it emits the DAC `pulse` and an external envelope-memory `MemReadPort`.
 * The buffer's memory-mapped `phaseOffset` (virtual Z) is added combinationally to the generator's
 * phase input — the phase Flow stays cycle-aligned with amp/addr/dur, and the add wraps modulo 2^w,
 * which is exactly a phase rotation (a full turn = 2^w), so no saturation is needed. On the way out a
 * single `RegNext` stage adds the buffer's memory-mapped `dcOffset` to every real output lane (the
 * imaginary lanes and `valid` are registered alongside to keep the batch aligned).
 */
case class PulseDriveChannel(
    pulseNum: Int,
    batchSize: Int,
    dataWidth: Int,
    envAddrWidth: Int,
    durWidth: Int,
    timeWidth: Int,
    memLatency: Int,
    prescaleAmp: Boolean,
    saturate: Boolean,
    phasorMethod: SinCosMethod,
    realOutput: Boolean,
    queueDepth: Int = 4,          // per-parameter TimedQueue depth (scheduled-ahead pulses per param)
    putAddrWidth: Int = 16,
    useAligned: Boolean = false   // false = per-parameter lead-time TimedQueues (PulseGenerator);
                                  // true = QubiC-style single combined params FIFO + SRL alignment
                                  // (PulseGeneratorAligned). Bit-identical pulse; trades alignment HW.
) extends Component with Channel {
  val N = batchSize; val w = dataWidth
  val io = new Bundle {
    val cmd       = slave  port Flow(Put(putAddrWidth))
    val timeBcast = in     port UInt(timeWidth bits)
    val memPort   = master port MemReadPort(Bits(N * 2 * w bits), envAddrWidth)
    val pulse     = master port Flow(ComplexBatch(N, w))
  }
  def cmd = io.cmd; def timeBcast = io.timeBcast; def memPort = Some(io.memPort)
  def envLanes = N; def dacOut = Some(io.pulse); def carrier = None

  val buf = PulseParamBuffer(PulseParamBufferParams(
    pulseNum = pulseNum, dataWidth = w, envAddrWidth = envAddrWidth, durWidth = durWidth,
    timeWidth = timeWidth, addrWidth = putAddrWidth))
  buf.io.cmd << io.cmd
  buf.io.timeBcast := io.timeBcast

  val pgParams = PulseGeneratorParams(
    batchSize = N, dataWidth = w, timeWidth = timeWidth, addrWidth = envAddrWidth, durWidth = durWidth,
    memLatency = memLatency, prescaleAmp = prescaleAmp, saturate = saturate, phasorMethod = phasorMethod,
    realOutput = realOutput, queueDepth = queueDepth)

  // The generator's raw pulse (pre-dcOffset), captured here so the dcOffset stage below is shared
  // across both generator variants.
  val rawPulse = Flow(ComplexBatch(N, w))

  // The two implementations differ only on the parameter-input side; the envelope read port is
  // forwarded to the external host-writable RAM (no extra register stage) and the raw pulse captured
  // identically in each branch.
  if (!useAligned) {
    val pg = PulseGenerator(pgParams)
    pg.io.time := buf.io.time; pg.io.startTime := buf.io.startTime
    pg.io.phase.valid := buf.io.phase.valid; pg.io.phase.payload := buf.io.phase.payload + buf.io.phaseOffset
    pg.io.amp << buf.io.amp; pg.io.addr << buf.io.addr
    pg.io.dur << buf.io.dur; pg.io.freq << buf.io.freq
    io.memPort.cmd.valid   := pg.io.memPort.cmd.valid
    io.memPort.cmd.payload := pg.io.memPort.cmd.payload
    pg.io.memPort.rsp      := io.memPort.rsp
    rawPulse << pg.io.pulse
  } else {
    val pg = PulseGeneratorAligned(pgParams)
    pg.io.time := buf.io.time; pg.io.startTime := buf.io.startTime
    // amp/phase/addr/dur all fire on buf's shared outParamFlow.valid ⇒ one combined params Flow.
    pg.io.params.valid         := buf.io.amp.valid
    pg.io.params.payload.amp   := buf.io.amp.payload
    pg.io.params.payload.phase := buf.io.phase.payload + buf.io.phaseOffset
    pg.io.params.payload.addr  := buf.io.addr.payload
    pg.io.params.payload.dur   := buf.io.dur.payload
    pg.io.freq << buf.io.freq
    io.memPort.cmd.valid   := pg.io.memPort.cmd.valid
    io.memPort.cmd.payload := pg.io.memPort.cmd.payload
    pg.io.memPort.rsp      := io.memPort.rsp
    rawPulse << pg.io.pulse
  }

  // dcOffset bias: add the buffer's memory-mapped `dcOffset` to every real output lane, in one RegNext
  // pipeline stage (`valid`, and the imag lane, registered alongside to keep the batch aligned). The add
  // wraps modulo 2^w — software keeps the biased sample within range. With `realOutput` the imag lane is
  // tied to 0 (only the real lane feeds the DAC), matching the generator's own imag pruning.
  io.pulse.valid := RegNext(rawPulse.valid) init False
  for (k <- 0 until N) {
    io.pulse.payload(k).re := RegNext(rawPulse.payload(k).re + buf.io.dcOffset)
    io.pulse.payload(k).im := (if (realOutput) S(0, w bits) else RegNext(rawPulse.payload(k).im))
  }

  /** per-buffer startTime, for the SoC/sim to observe the software-written schedule value. */
  def startTime: UInt = buf.startTime
}

/**
 * Converter-edge **demod carrier channel** — the demodulation LO for the paired [[ReadoutDecoder]],
 * now a **scheduled, envelope-shaped complex pulse** instead of a free-running NCO. It is a
 * [[PulseDriveChannel]] pointed at the decoder rather than a DAC: a [[PulseParamBuffer]] (posted RF
 * register file) drives a [[PulseGenerator]] whose complex `pulse` is exported as `io.carrier`, so the
 * demod carrier gets the same host-writable envelope, `freq`/`phase` table and `startTime` scheduling
 * as a drive channel. Software programs a (typically square) matched-filter envelope once and fires the
 * demod pulse aligned with the readout window; the envelope weights each demodulated batch.
 *
 * Two deliberate differences from [[PulseDriveChannel]]: `realOutput = false` (the decoder needs the
 * full complex carrier), and no `dcOffset` output stage (a DC bias is meaningless on a demod carrier),
 * so `io.carrier` is the raw generator output — one fewer register than the drive path, which keeps the
 * carrier↔`io.time` alignment (`timeToPulse`) equal to the generator's. The `phaseOffset` (virtual Z)
 * is kept: it is the live demod-phase tune. When idle the generator zeroes its output, so `io.carrier`
 * is 0 outside the demod window and the decoder integrates nothing there.
 *
 * `io.carrier` is a `Flow`: its `valid` is the generator's scheduled window (`activeReg`, high for
 * `[startTime, startTime + dur)`), which the carrier-triggered [[ReadoutDecoder]] uses to delimit its
 * integration window — no separate arm. The payload is zero when invalid (see above), so an idle
 * carrier contributes exactly nothing to the integral.
 */
case class DemodChannel(
    pulseNum: Int,
    batchSize: Int,
    dataWidth: Int,
    envAddrWidth: Int,
    durWidth: Int,
    timeWidth: Int,
    memLatency: Int,
    prescaleAmp: Boolean,
    saturate: Boolean,
    phasorMethod: SinCosMethod,
    queueDepth: Int = 4,          // per-parameter TimedQueue depth (scheduled-ahead pulses per param)
    putAddrWidth: Int = 16
) extends Component with Channel {
  val N = batchSize; val w = dataWidth
  val io = new Bundle {
    val cmd       = slave  port Flow(Put(putAddrWidth))
    val timeBcast = in     port UInt(timeWidth bits)
    val memPort   = master port MemReadPort(Bits(N * 2 * w bits), envAddrWidth)
    val carrier   = master port Flow(ComplexBatch(N, w))
  }
  def cmd = io.cmd; def timeBcast = io.timeBcast; def memPort = Some(io.memPort)
  def envLanes = N; def dacOut = None; def carrier = Some(io.carrier)

  val buf = PulseParamBuffer(PulseParamBufferParams(
    pulseNum = pulseNum, dataWidth = w, envAddrWidth = envAddrWidth, durWidth = durWidth,
    timeWidth = timeWidth, addrWidth = putAddrWidth))
  buf.io.cmd << io.cmd
  buf.io.timeBcast := io.timeBcast

  val pg = PulseGenerator(PulseGeneratorParams(
    batchSize = N, dataWidth = w, timeWidth = timeWidth, addrWidth = envAddrWidth, durWidth = durWidth,
    memLatency = memLatency, prescaleAmp = prescaleAmp, saturate = saturate, phasorMethod = phasorMethod,
    realOutput = false, queueDepth = queueDepth))
  pg.io.time := buf.io.time; pg.io.startTime := buf.io.startTime
  pg.io.phase.valid := buf.io.phase.valid; pg.io.phase.payload := buf.io.phase.payload + buf.io.phaseOffset
  pg.io.amp << buf.io.amp; pg.io.addr << buf.io.addr
  pg.io.dur << buf.io.dur; pg.io.freq << buf.io.freq
  io.memPort.cmd.valid   := pg.io.memPort.cmd.valid
  io.memPort.cmd.payload := pg.io.memPort.cmd.payload
  pg.io.memPort.rsp      := io.memPort.rsp

  // the carrier is the raw generator output (complex, zeroed outside the window) — no dcOffset stage.
  // valid AND payload are exported: `valid` is the scheduled window the decoder triggers on.
  io.carrier << pg.io.pulse

  /** per-buffer startTime, for the SoC/sim to observe the software-written schedule value. */
  def startTime: UInt = buf.startTime
  /** generator time→carrier latency, for the decoder/golden time-alignment derivation. */
  def timeToPulse: Int = pg.timeToPulse
}
