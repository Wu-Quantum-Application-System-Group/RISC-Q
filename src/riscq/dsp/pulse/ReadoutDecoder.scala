package riscq.dsp.pulse

import spinal.core._
import spinal.lib._
import riscq.dsp._

case class ReadoutDecoderParams(
    batchSize: Int = 4,     // N, power of two (one ADC batch per tick) — the ADC batch, not the DAC batch
    dataWidth: Int = 16,    // w: SF width of adc/carrier/demod
    accWidth:  Int = 32,    // integrated-I/Q width — read back by software as one word
    maxWinLog2: Int = 14,   // no-overflow contract: longest carrier window ≤ 2^maxWinLog2 batches
    saturate:  Boolean = false  // → ComplexMul saturation; default matches PulseGeneratorParams.saturate
) {
  require(isPow2(batchSize), "batchSize must be a power of two")
  require(dataWidth >= 2)
  // worst-case integral: N·Amax over up to 2^maxWinLog2 batches. maxWinLog2 elaborates NO hardware —
  // it exists only to state this bound; the window length is the demod carrier's `dur`.
  require(accWidth >= dataWidth + log2Up(batchSize) + maxWinLog2,
    s"accWidth ($accWidth) must hold the worst-case window integral " +
      s"dataWidth + log2Up(batchSize) + maxWinLog2 = ${dataWidth + log2Up(batchSize) + maxWinLog2} bits")
}

/**
 * Demodulating readout integrator — the inverse of the [[PulseGenerator]], '''triggered by the demod
 * carrier's window''' instead of a software-armed one. For every incoming ADC batch it
 * '''demodulates''' against the reference carrier (the demod LO), '''integrates''' the product across
 * the lanes and across the window, and emits the integrated I/Q point plus a one-bit discrimination:
 *
 * {{{
 *   demod[t][k] = adc[t][k] · carrier[t][k]            // complex × complex, per lane  (down-conversion)
 *   real/imag   = Σ_{t ∈ window} Σ_k demod[t][k]        // integrated I / Q
 *   res         = sign(real)                            // threshold 0 on the real axis
 * }}}
 *
 * '''The carrier defines the window.''' `io.carrier` is a `Flow`: its `valid` is exactly the scheduled
 * demod-pulse window (the [[PulseGenerator]]'s `activeReg`, high for `[startTime, startTime + dur)`),
 * and its payload is forced to zero outside that window. So the decoder needs no `io.time`, no
 * `startTime`, no `dur`, and no window FSM — it watches the carrier valid:
 *   - '''rising edge''' → restart the accumulator (load the first in-window product);
 *   - '''falling edge''' → the integral is complete → raise `res.valid`;
 *   - in between it just accumulates — and it accumulates '''every cycle''', because an idle carrier
 *     contributes an exactly-zero product, so the free-running `sum` simply holds its settled value
 *     between windows.
 *
 * '''Reused, verified blocks''': the per-lane demod is `riscq.dsp.ComplexMul` (the same DSP48-packed,
 * II = 1 product the pulse side uses); the lane sum is the balanced [[AdderTree]]
 * (`reduceBalancedTree(_ + _, RegNext)`).
 *
 * '''Derived window alignment (no magic numbers)''' — the carrier `valid` is delayed by
 * `Ldemod = ComplexMul.latency + 1 + log2Up(N)` cycles (adc/carrier input → tree output) so its edges
 * arrive at the accumulator aligned with the corresponding tree output. The integrated batches are then
 * '''exactly''' the carrier's in-window batches, `dur` of them; ADC/analog-path delay is absorbed into
 * when software fires the demod pulse (no decoder-side `timeOffset`).
 *
 * `res.valid` rises once the integral has settled (the SoC halts the readback bus until then) and stays
 * high until the next window's `winStart` clears it, i.e. it is low exactly during the next window's
 * integration; the SoC forwards it up the link as a level. `demodData` streams the raw per-batch demod
 * (for trace/averaging), valid aligned with its own payload.
 */
case class ReadoutDecoder(p: ReadoutDecoderParams) extends Component {
  // Fence the datapath so its ComplexMuls pack into DSP48E2s per-instance, not via the global
  // resource-balancer that over-spills to LUTs at scale.
  this.addAttribute("KEEP_HIERARCHY", "TRUE")
  val N = p.batchSize
  val w = p.dataWidth

  val io = new Bundle {
    val adc       = in     port ComplexBatch(N, w)
    val carrier   = slave  port Flow(ComplexBatch(N, w))   // valid delimits the integration window
    val real      = out    port SInt(p.accWidth bits)      // integrated I
    val imag      = out    port SInt(p.accWidth bits)      // integrated Q
    val res       = master port Flow(Bool())               // valid once the integral settles
    val demodData = master port Flow(ComplexBatch(N, w))   // per-batch demod trace
  }

  // ── per-lane demod: adc[k] · carrier[k] ──
  // resetValid = false: the mul rsp.valid is unused — the window is delimited by the carrier valid
  // delay line below, so the reset-free valid chain sheds the global reset.
  val muls = Array.fill(N)(ComplexMul(w, p.saturate, resetValid = false))
  for (k <- 0 until N) {
    muls(k).io.cmd.valid     := True
    muls(k).io.cmd.payload.a := io.adc(k)
    muls(k).io.cmd.payload.b := io.carrier.payload(k)
  }
  val Lmul = ComplexMul.latency(p.saturate)

  // ── lane sum: one input register (fanout/retiming) + the balanced adder tree ──
  val treeR  = AdderTree(muls.map(m => RegNext(m.io.rsp.payload.re)).toSeq, p.accWidth)
  val treeI  = AdderTree(muls.map(m => RegNext(m.io.rsp.payload.im)).toSeq, p.accWidth)
  val Ltree  = 1 + AdderTree.latency(N)
  val Ldemod = Lmul + Ltree                          // adc/carrier input → tree output

  // ── window control: the carrier valid, delayed to align with the tree output, drives two edge detects ──
  val inWin    = Delay(io.carrier.valid, Ldemod, init = False)  // aligned with treeR/treeI
  val winStart = inWin && !RegNext(inWin, False)                // first in-window product
  val winEnd   = !inWin && RegNext(inWin, False)                // one cycle after the last product

  // ── free-running I/Q accumulators: no clock enable — out-of-window products are exactly 0, so
  //    `sum` holds its settled value from winEnd until the next winStart overwrites it. winStart LOADS
  //    (:= tree) rather than clearing a cycle early, so no cycle is lost between windows. ──
  val sumR = Reg(SInt(p.accWidth bits))
  val sumI = Reg(SInt(p.accWidth bits))
  sumR := winStart ? treeR | (sumR + treeR)
  sumI := winStart ? treeI | (sumI + treeI)

  // res.valid rises the cycle after winEnd (the last in-window product has settled into sum) and holds
  // until the next window's winStart clears it — one rising edge per window, low during the next window.
  val resValid = RegInit(False) clearWhen (winStart) setWhen (winEnd)

  // ── outputs ──
  io.real := sumR
  io.imag := sumI
  io.res.valid   := resValid
  io.res.payload := sumR.msb                          // sign(sumR): true ⇒ negative
  // demodData: the raw per-batch demod, valid riding with its own payload (carrier valid delayed by the
  // mul latency, not the tree latency — self-aligned, unlike the reference's tree-lagged trace).
  io.demodData.valid := Delay(io.carrier.valid, Lmul, init = False)
  for (k <- 0 until N) io.demodData.payload(k) := muls(k).io.rsp.payload

  /** ComplexMul cmd→rsp latency (demodData rides `Lmul` behind the ADC input). */
  def mulLatency: Int = Lmul
  /** ADC/carrier input → tree output: the carrier-valid delay that aligns the window with the products. */
  def demodLatency: Int = Ldemod
}
