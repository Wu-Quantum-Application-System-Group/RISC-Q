package riscq.dsp.pulse

import spinal.core._
import spinal.lib._
import riscq.dsp._

case class PulseGeneratorParams(
    batchSize: Int = 16,  // N, power of 2
    dataWidth: Int = 16,  // w: SF width of amp/freq/phase/envelope/output
    timeWidth: Int = 32,  // global batch-time counter width
    addrWidth: Int = 12,  // envelope memory address width
    durWidth: Int = 16,   // duration counter width (in batches)
    queueDepth: Int = 4,  // TimedQueue depth (entries per parameter)
    queueUseVec: Boolean = false,  // TimedQueue FIFO storage: distributed-RAM Mem (default — fewest
                                   // control sets for replicated instances; the congestion-best choice)
                                   // vs a register array (true = recover the higher-fmax FIFO).
    queueForFMax: Boolean = false, // TimedQueue FIFO pointers: plain binary (default — fewer control
                                   // sets) vs fmax-tuned gray/empty-tracker (true).
    queueImpl: TimedQueueImpl = TimedQueueImpl.RegHead, // TimedQueue microarchitecture — see
                                   // [[TimedQueueImpl]] for the congestion/II trade of each option.
    memLatency: Int = 2,  // envelope mem: cmd → rsp cycles
    timeOffset: Int = 0,  // external calibration: adds to every lead time
    prescaleAmp: Boolean = true, // software prescales amp by 1/K ⇒ both CORDICs drop the gain stage
    saturate: Boolean = false,   // clamp every product/rotation to ±Amax. false ⇒ no saturation anywhere
                                 // (simpler/faster HW): the phasor is pulled just under full-scale so the
                                 // un-clamped datapath can't overflow — software must then keep amp and the
                                 // envelope magnitude ≤ Amax (within the unit disk).
    phasorMethod: SinCosMethod = SinCosMethod.Cordic
                                 // algorithm for PhasorBatchGenerator. Cordic (default): rotation-mode
                                 // CORDIC, 0 DSP, ~17 pipeline stages, no BRAM. Taylor: LUT + first-order
                                 // correction, 2 DSP, 8 pipeline stages, 1 BRAM — shorter regenCycles
                                 // (N+10 vs N+21/22) and fewer LUTs at the cost of 1 BRAM36 + 2 DSP48E2.
    ,
    realOutput: Boolean = false  // when only the real pulse lane is consumed (e.g. a real DAC), drive
                                 // io.pulse.im = 0 so synthesis prunes each envelope ComplexMul's
                                 // imag-output multiply (3→2 DSP/lane) — even inside a KEEP_HIERARCHY
                                 // fence, since the dead output is then intra-block.
) {
  require(isPow2(batchSize), "batchSize must be a power of two")
  require(dataWidth >= 2 && timeWidth >= 3 && addrWidth >= 1 && durWidth >= 1)
}

/**
 * Timed complex-envelope pulse generator — one flat component (no inner/outer split). Plays
 * `pulse[k](t) = env[N·t+k]·amp·exp(iπ(freq·(N·t+k)+phase))` for a scheduled window, every parameter
 * queued against the global `io.time` and popped '''early''' by its own pipeline latency so all
 * parameters of a pulse land on the same output sample (lead-time scheduling).
 *
 * '''Lead-time derivation — the central design rule''' (no numeric literals): each queue's lead time
 * is the sum of the exported latencies of every block on that parameter's path to the DAC, so a
 * future pipeline edit shifts the lead times automatically (and the end-to-end TB asserts the
 * pulse timing). Datapath: `carrier[k]` × complex `env[k]` through `N` more `ComplexMul` →
 * duration gate → `pulse`.
 */
case class PulseGenerator(p: PulseGeneratorParams) extends Component {
  // Fence this whole DSP datapath as one synthesis unit so each instance packs its ComplexMuls into
  // DSP48E2s independently (3 DSP/lane) instead of the flattened multi-qubit netlist's global DSP
  // resource-balancer over-spilling multiplies into LUTs at scale.
  this.addAttribute("KEEP_HIERARCHY", "TRUE")
  val N = p.batchSize
  val w = p.dataWidth

  val io = new Bundle {
    val time      = in port UInt(p.timeWidth bits)            // external SoC time, batch units
    val startTime = in port UInt(p.timeWidth bits)            // sampled at each param push
    val amp       = slave port Flow(SInt(w bits))
    val freq      = slave port Flow(SInt(w bits))
    val phase     = slave port Flow(SInt(w bits))
    val addr      = slave port Flow(UInt(p.addrWidth bits))
    val dur       = slave port Flow(UInt(p.durWidth bits))    // batches; pulse window length
    val memPort   = master port MemReadPort(Bits(N * 2 * w bits), p.addrWidth)
    val pulse     = master port Flow(ComplexBatch(N, w))
  }

  // ── sub-blocks (instantiated first so their exported latencies drive the lead times) ──
  // prescaleAmp ⇒ run both CORDICs uncorrected (software prescales amp by 1/K): the gain
  // stage drops, cordic.latency shrinks by 1, and the lead times below re-derive automatically.
  val correctGain = !p.prescaleAmp
  val phasorGen = PhasorBatchGenerator(N, w, correctGain, p.saturate, p.phasorMethod)
  val carrierGen = CarrierBatchGenerator(N, w, p.timeWidth, correctGain, p.saturate)
  val envReader = EnvelopeReader(EnvelopeReaderParams(N, w, p.addrWidth, p.memLatency))
  // resetValid = false: the envMul rsp.valid is unused — the output is gated by the
  // reset-bearing duration counter `active`, so the reset-free valid chain sheds the global reset.
  val envMuls = Array.fill(N)(ComplexMul(w, p.saturate, resetValid = false))
  val Lm = ComplexMul.latency(p.saturate)

  // ── lead-time derivation: sums of exported sub-module latencies, zero literals ──
  val gateLatency = 1 // payload path: envMul output → pulse (one output register)
  val leadAmp   = carrierGen.ampLatency + Lm + gateLatency
  val leadPhase = carrierGen.phaseLatency + Lm + gateLatency
  val leadFreqC = carrierGen.freqLatency + Lm + gateLatency
  val leadFreqP = phasorGen.regenCycles + carrierGen.phasorLatency + Lm + gateLatency
  val leadAddr  = envReader.latency + Lm + gateLatency
  val leadDur   = gateLatency + 1 // + the down-counter load register
  /** time → pulse output latency (for the golden model's carrier time alignment). */
  def timeToPulse: Int = carrierGen.timeLatency + Lm + gateLatency

  // ── six TimedQueues, each pushed from its io Flow with io.startTime ──
  def mkQueue[T <: Data](dt: HardType[T], lead: Int): TimedQueue[T] = {
    val q = TimedQueue(dt, p.timeWidth, p.queueDepth, lead, p.timeOffset, p.queueUseVec, p.queueForFMax,
      impl = p.queueImpl)
    q.io.time := io.time
    q
  }
  val ampQ   = mkQueue(SInt(w bits), leadAmp)
  val phaseQ = mkQueue(SInt(w bits), leadPhase)
  val freqCQ = mkQueue(SInt(w bits), leadFreqC) // freq → carrier
  val freqPQ = mkQueue(SInt(w bits), leadFreqP) // freq → phasor regen
  val addrQ  = mkQueue(UInt(p.addrWidth bits), leadAddr)
  val durQ   = mkQueue(UInt(p.durWidth bits), leadDur)

  def push[T <: Data](q: TimedQueue[T], v: Bool, payload: T): Unit = {
    q.io.push.valid           := v
    q.io.push.payload.data    := payload
    q.io.push.payload.startTime := io.startTime
  }
  push(ampQ, io.amp.valid, io.amp.payload)
  push(phaseQ, io.phase.valid, io.phase.payload)
  push(freqCQ, io.freq.valid, io.freq.payload) // io.freq feeds both freq queues
  push(freqPQ, io.freq.valid, io.freq.payload)
  push(addrQ, io.addr.valid, io.addr.payload)
  push(durQ, io.dur.valid, io.dur.payload)

  // ── wire the popped parameters to the generators / reader ──
  carrierGen.io.time := io.time
  carrierGen.io.amp << ampQ.io.pop
  carrierGen.io.phase << phaseQ.io.pop
  carrierGen.io.freq << freqCQ.io.pop
  phasorGen.io.freq << freqPQ.io.pop
  carrierGen.io.phasors << phasorGen.io.phasors
  envReader.io.addr << addrQ.io.pop

  // envelope memory port pass-through to the external RAM (phase-2).
  io.memPort.cmd.valid     := envReader.io.memPort.cmd.valid
  io.memPort.cmd.payload   := envReader.io.memPort.cmd.payload
  envReader.io.memPort.rsp := io.memPort.rsp

  // ── envelope multiply: carrier[k] × env[k] (complex × complex) ──
  for (k <- 0 until N) {
    envMuls(k).io.cmd.valid     := True
    envMuls(k).io.cmd.payload.a := carrierGen.io.carrier(k)
    envMuls(k).io.cmd.payload.b := envReader.io.env(k)
  }

  // ── duration gate: down-counter loaded from the dur queue; valid window = [startTime, +dur) ──
  val cnt    = Reg(UInt(p.durWidth bits)) init (0)
  when(durQ.io.pop.valid)(cnt := durQ.io.pop.payload).elsewhen(cnt =/= 0)(cnt := cnt - 1)
  val active = cnt =/= 0
  // Buffer `active` (the cnt comparator output) in its own register rather than muxing io.pulse
  // straight off the live comparator: this keeps the high-fanout counter→compare path off the output
  // mux's select, so it ends at a register and the mux is driven by a fast, duplicable reg output.
  // The data lanes register the same cycle, so the gate stays aligned (latency 1, bit-exact).
  val activeReg = RegNext(active) init (False)
  activeReg.addAttribute("max_fanout", 16)
  io.pulse.valid := activeReg
  for (k <- 0 until N) {
    io.pulse.payload(k).re := activeReg ? RegNext(envMuls(k).io.rsp.payload.re) | S(0, w bits)
    // realOutput: tie imag to 0 so the dead envMul imag-output multiply is pruned; else carry it.
    io.pulse.payload(k).im :=
      (if (p.realOutput) S(0, w bits) else activeReg ? RegNext(envMuls(k).io.rsp.payload.im) | S(0, w bits))
  }
}
