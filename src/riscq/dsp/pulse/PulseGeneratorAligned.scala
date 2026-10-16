package riscq.dsp.pulse

import spinal.core._
import spinal.lib._
import riscq.dsp._

/** The four co-fired pulse parameters carried as one queue entry (one shared valid). `freq` is NOT
 *  here — it rides its own separate queues (it fires on a different cycle than the table-entry fire,
 *  and drives two consumers at very different latencies). */
case class AlignedPulseParams(dataWidth: Int, addrWidth: Int, durWidth: Int) extends Bundle {
  val amp   = SInt(dataWidth bits)
  val phase = SInt(dataWidth bits)
  val addr  = UInt(addrWidth bits)
  val dur   = UInt(durWidth bits)
}

/**
 * QubiC-style pulse generator — same datapath and bit-exact output as [[PulseGenerator]], but a
 * different parameter-alignment scheme borrowed from the QubiC `element`/`ammod`:
 *
 *  - [[PulseGenerator]] (lead-time scheduling): '''one TimedQueue per parameter''', each popped
 *    `startTime − leadₓ` so a parameter enters its consumer exactly when its own pipeline will carry it
 *    to the DAC at `startTime`. Six queues, six 32-bit registered time compares.
 *
 *  - This component (QubiC alignment): the four co-fired parameters {amp, phase, addr, dur} share '''one
 *    TimedQueue''' (one entry, one `startTime`, one shared pop valid), popped once at the '''latest'''
 *    of their lead times, `maxLead`. Each parameter is then realigned to its own consumer with a static
 *    SRL delay of `maxLead − leadₓ` (the QubiC `reg_delay1` shift line), and a '''single''' shared
 *    valid shift register (the QubiC `busy_sr`) is tapped at those same depths. `freq` keeps its own
 *    separate queues (QubiC routes the frequency word on a separate, longer-latency path too).
 *
 * Because every consumer still sees its parameter at exactly `startTime − leadₓ` (queue pops at
 * `startTime − maxLead`, the SRL adds back `maxLead − leadₓ`), the carrier / envelope / gate inputs are
 * timing-identical to [[PulseGenerator]] — so the output pulse is '''bit-exact''' the same and the
 * [[riscq.dsp.pulse.sim.PulseGeneratorSim]] golden applies unchanged. Only the alignment hardware
 * differs (3 queues + SRLs here vs 6 queues there) — which is the resource/fmax question this trades.
 *
 * The narrow vs wide delay choice mirrors QubiC: `addr`/`dur` are delayed as narrow SRLs (`addrWidth` /
 * `durWidth` bits) the same way QubiC delays the env '''address''' (12 bit) rather than the 512-bit env
 * data — the cheap side of each branch.
 */
case class PulseGeneratorAligned(p: PulseGeneratorParams) extends Component {
  // Same single-synthesis-unit fence as PulseGenerator so each instance packs its ComplexMuls into
  // DSP48E2s independently.
  this.addAttribute("KEEP_HIERARCHY", "TRUE")
  val N = p.batchSize
  val w = p.dataWidth

  val io = new Bundle {
    val time      = in port UInt(p.timeWidth bits)                 // external SoC time, batch units
    val startTime = in port UInt(p.timeWidth bits)                 // sampled at the params push
    val params    = slave port Flow(AlignedPulseParams(w, p.addrWidth, p.durWidth)) // amp/phase/addr/dur, ONE valid
    val freq      = slave port Flow(SInt(w bits))                  // separate signal (own queues)
    val memPort   = master port MemReadPort(Bits(N * 2 * w bits), p.addrWidth)
    val pulse     = master port Flow(ComplexBatch(N, w))
  }

  // ── sub-blocks (identical to PulseGenerator; their exported latencies drive the lead times) ──
  val correctGain = !p.prescaleAmp
  val phasorGen = PhasorBatchGenerator(N, w, correctGain, p.saturate, p.phasorMethod)
  val carrierGen = CarrierBatchGenerator(N, w, p.timeWidth, correctGain, p.saturate)
  val envReader = EnvelopeReader(EnvelopeReaderParams(N, w, p.addrWidth, p.memLatency))
  val envMuls = Array.fill(N)(ComplexMul(w, p.saturate, resetValid = false))
  val Lm = ComplexMul.latency(p.saturate)

  // ── lead-time derivation (identical to PulseGenerator): zero literals ──
  val gateLatency = 1 // payload path: envMul output → pulse (one output register)
  val leadAmp   = carrierGen.ampLatency + Lm + gateLatency
  val leadPhase = carrierGen.phaseLatency + Lm + gateLatency
  val leadFreqC = carrierGen.freqLatency + Lm + gateLatency
  val leadFreqP = phasorGen.regenCycles + carrierGen.phasorLatency + Lm + gateLatency
  val leadAddr  = envReader.latency + Lm + gateLatency
  val leadDur   = gateLatency + 1 // + the down-counter load register
  /** time → pulse output latency (for the golden model's carrier time alignment). */
  def timeToPulse: Int = carrierGen.timeLatency + Lm + gateLatency

  // ── one combined TimedQueue for {amp, phase, addr, dur}, popped at the LATEST of their leads ──
  val maxLead = Seq(leadAmp, leadPhase, leadAddr, leadDur).max
  val paramQ = TimedQueue(AlignedPulseParams(w, p.addrWidth, p.durWidth), p.timeWidth, p.queueDepth,
    maxLead, p.timeOffset, p.queueUseVec, p.queueForFMax, impl = p.queueImpl)
  paramQ.io.time := io.time
  paramQ.io.push.valid             := io.params.valid
  paramQ.io.push.payload.data      := io.params.payload
  paramQ.io.push.payload.startTime := io.startTime

  // ── frequency: own separate queues (left as a separate signal, QubiC-style) ──
  def mkFreqQueue(lead: Int): TimedQueue[SInt] = {
    val q = TimedQueue(SInt(w bits), p.timeWidth, p.queueDepth, lead, p.timeOffset, p.queueUseVec, p.queueForFMax,
      impl = p.queueImpl)
    q.io.time := io.time
    q.io.push.valid             := io.freq.valid
    q.io.push.payload.data      := io.freq.payload
    q.io.push.payload.startTime := io.startTime
    q
  }
  val freqCQ = mkFreqQueue(leadFreqC) // freq → carrier
  val freqPQ = mkFreqQueue(leadFreqP) // freq → phasor regen

  // ── SRL alignment (the QubiC reg_delay1 / busy_sr) ──
  // Per-parameter SRL depth = how much LATER than the common pop each consumer needs its value.
  val dAmp   = maxLead - leadAmp
  val dPhase = maxLead - leadPhase
  val dAddr  = maxLead - leadAddr
  val dDur   = maxLead - leadDur
  // One shared valid shift register (busy_sr), reset-clean (init False) so it never spurious-fires;
  // tapped at each consumer's depth. validSr(d) = pop.valid delayed by d cycles.
  val validSr = History(paramQ.io.pop.valid, Seq(dAmp, dPhase, dAddr, dDur).max + 1, init = False)
  // Payload SRLs carry no reset (Delay without init ⇒ Xilinx SRL): write-before-read, gated by the
  // reset-clean valid taps above, exactly like the carrier's no-init held registers.
  def aligned[T <: Data](field: T, depth: Int): T = Delay(field, depth)

  carrierGen.io.time := io.time
  carrierGen.io.amp.valid     := validSr(dAmp)
  carrierGen.io.amp.payload   := aligned(paramQ.io.pop.payload.amp, dAmp)
  carrierGen.io.phase.valid   := validSr(dPhase)
  carrierGen.io.phase.payload := aligned(paramQ.io.pop.payload.phase, dPhase)
  carrierGen.io.freq << freqCQ.io.pop
  phasorGen.io.freq << freqPQ.io.pop
  carrierGen.io.phasors << phasorGen.io.phasors

  envReader.io.addr.valid   := validSr(dAddr)
  envReader.io.addr.payload := aligned(paramQ.io.pop.payload.addr, dAddr)

  // envelope memory port pass-through to the external RAM (phase-2).
  io.memPort.cmd.valid     := envReader.io.memPort.cmd.valid
  io.memPort.cmd.payload   := envReader.io.memPort.cmd.payload
  envReader.io.memPort.rsp := io.memPort.rsp

  // ── envelope multiply: carrier[k] × env[k] ──
  for (k <- 0 until N) {
    envMuls(k).io.cmd.valid     := True
    envMuls(k).io.cmd.payload.a := carrierGen.io.carrier(k)
    envMuls(k).io.cmd.payload.b := envReader.io.env(k)
  }

  // ── duration gate: down-counter loaded from the SRL-aligned dur tap; window = [startTime, +dur) ──
  val durValid = validSr(dDur)
  val durData  = aligned(paramQ.io.pop.payload.dur, dDur)
  val cnt    = Reg(UInt(p.durWidth bits)) init (0)
  when(durValid)(cnt := durData).elsewhen(cnt =/= 0)(cnt := cnt - 1)
  val active = cnt =/= 0
  val activeReg = RegNext(active) init (False)
  io.pulse.valid := activeReg
  for (k <- 0 until N) {
    io.pulse.payload(k).re := activeReg ? RegNext(envMuls(k).io.rsp.payload.re) | S(0, w bits)
    io.pulse.payload(k).im :=
      (if (p.realOutput) S(0, w bits) else activeReg ? RegNext(envMuls(k).io.rsp.payload.im) | S(0, w bits))
  }
}
