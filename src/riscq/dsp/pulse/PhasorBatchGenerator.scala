package riscq.dsp.pulse

import spinal.core._
import spinal.lib._
import riscq.dsp._

/**
 * Per-frequency phasor batch. Holds
 * `phasors[k] = Amax·exp(iπ·f·k)`, `k = 0..N−1`, in registers and regenerates them on each
 * `io.freq` update by driving '''one shared computation block''' over `N` cycles (intra-batch
 * factorization: only the phasors change per freq, so serial regen costs one block instead of N).
 *
 *  1. on a freq fire: latch `f`, clear the SF phase accumulator `zAcc` (truncating wrap = exact
 *     phase wrap);
 *  2. issue `N` commands on consecutive cycles — phase `zAcc`, `zAcc += f`;
 *  3. '''write index = count of accepted responses''' since regen start — responses arrive in
 *     order at II = 1. Acceptance is gated until `Lc` cycles after the restart, which
 *     '''also discards any in-flight responses''' from an aborted regen;
 *  4. `io.phasors` is invalid during the regen window and valid (holding the batch) otherwise.
 *
 * `method` selects the phasor computation block (elaboration-time):
 *  - [[SinCosMethod.Cordic]] (default): rotation-mode CORDIC, 0 DSP, ~17-stage pipeline.
 *    `correctGain`/`saturate` apply as in the standalone [[Cordic]] component.
 *  - [[SinCosMethod.Taylor]]: LUT + first-order Taylor correction ([[SinCosTaylor]]), 2 DSP,
 *    8-stage pipeline, 1 BRAM36. `correctGain`/`saturate` are ignored — Taylor has no gain
 *    factor and always saturates at ±Amax (clamp is silent for in-range inputs). Shorter
 *    `regenCycles` (N+10 vs N+21/22).
 *
 * The phasor registers carry '''no reset init''': the batch is undefined until the first
 * `freq` write regenerates it, which is safe because the pulse output is duration-gated to zero and every
 * consumer programs `freq` before its window — see the register declaration below. `regenCycles` (freq
 * fire → phasors valid) is exported so the toplevel's freq-queue lead time covers the regen window.
 */
case class PhasorBatchGenerator(batchSize: Int, dataWidth: Int, correctGain: Boolean = true,
    saturate: Boolean = true, method: SinCosMethod = SinCosMethod.Cordic) extends Component {
  val N    = batchSize
  val w    = dataWidth
  val amax = (BigInt(1) << (w - 1)) - 1

  val io = new Bundle {
    val freq    = slave port Flow(SInt(w bits))
    val phasors = master port Flow(ComplexBatch(N, w))
  }

  // ── phasor computation block ─────────────────────────────────────────────────────────────────
  // cmdValid / cmdPhase: FSM → computation block (driven below).
  val cmdValid = Bool()
  val cmdPhase = SInt(w bits)

  // Wire up the chosen block; the three response signals are assigned here for use below.
  val Lc: Int = method match {
    case SinCosMethod.Cordic =>
      Cordic.latency(CordicParams(xyWidth = w, zWidth = w, correctGain = correctGain, saturate = saturate))
    case SinCosMethod.Taylor =>
      SinCosTaylor.latency
    case other =>
      SpinalError(s"PhasorBatchGenerator: unsupported method: $other")
  }

  val rspValid = Bool()
  val rspRe    = SInt(w bits)
  val rspIm    = SInt(w bits)

  method match {
    case SinCosMethod.Cordic =>
      // resetValid = false: the rsp.valid chain is reset-free — `collect` below gates it
      // behind the reset-bearing `since`/`collectCnt`, so the boot value is never acted on.
      val cp    = CordicParams(xyWidth = w, zWidth = w, correctGain = correctGain, saturate = saturate,
                               resetValid = false)
      // Target phasor magnitude and CORDIC input magnitude (headroom for no-sat mode).
      val phMag = if (saturate) amax
                  else amax - BigInt(scala.math.ceil(Cordic.errorBound(cp, amax.toDouble)).toLong) - 1
      val xyMag = if (correctGain) phMag
                  else BigInt((phMag.toDouble / Cordic.gain(cp.iters)).round)
      val cordic = Cordic(cp)
      cordic.io.cmd.valid         := cmdValid
      cordic.io.cmd.payload.xy.re := S(xyMag, w bits)
      cordic.io.cmd.payload.xy.im := S(0, w bits)
      cordic.io.cmd.payload.z     := cmdPhase
      rspValid := cordic.io.rsp.valid
      rspRe    := cordic.io.rsp.payload.re
      rspIm    := cordic.io.rsp.payload.im

    case SinCosMethod.Taylor =>
      // Taylor always outputs at Amax amplitude and saturates at ±Amax; correctGain/saturate unused.
      val sp     = SinCosParams(phaseWidth = w, ampWidth = w, method = SinCosMethod.Taylor)
      val sincos = SinCos(sp)
      sincos.io.cmd.valid         := cmdValid
      sincos.io.cmd.payload.phase := cmdPhase
      // phasor re = cos(π·f·k), im = sin(π·f·k) — the SinCos cos/sin output convention
      rspValid := sincos.io.rsp.valid
      rspRe    := sincos.io.rsp.payload.cos
      rspIm    := sincos.io.rsp.payload.sin

    case other => SpinalError(s"PhasorBatchGenerator: unsupported method: $other")
  }
  // ─────────────────────────────────────────────────────────────────────────────────────────────

  // Phasor registers, collected as a SHIFT register. The N responses arrive in order at II=1, so
  // we shift every lane on ONE shared enable rather than writing the lane indexed by the response count.
  // A per-lane one-hot write decode would give each lane its own clock-enable (N control sets), whereas
  // a shift uses a single shared enable (1 control set). The newest response enters lane 0 and shifts
  // toward higher lanes, so after N accepts `phRe(i) = resp(N-1-i)`; `io.phasors` reads the batch back
  // reversed below (`payload(k) = phRe(N-1-k)`), bit-identical to the indexed write.
  //
  // No reset init (this was an `(Amax, 0)` f=0 default batch). Safe because the value is never observed
  // before the first `freq` write: the pulse output is hard-gated to zero by the duration counter
  // (`cnt = 0 ⇒ payload 0`, and the pulse-table `dur=0` reset already neutralises any boot/phantom fire),
  // and every consumer programs `freq` — triggering a full regen — before its active window opens (the
  // freq-queue lead time covers `regenCycles`). So the batch the window reads is always freshly
  // regenerated, not the boot value.
  val phRe = Vec.fill(N)(Reg(SInt(w bits)))
  val phIm = Vec.fill(N)(Reg(SInt(w bits)))

  // fReg/zAcc carry no reset init: both are write-before-read — loaded/cleared on a freq fire before any
  // command issues, and the reset-time default batch (issueCnt=N ⇒ not issuing) never reads
  // them — so dropping the inits is behaviour-identical and lightens the reset group.
  val fReg       = Reg(SInt(w bits))
  val zAcc       = Reg(SInt(w bits))                   // phase accumulator k·f (truncating wrap)
  val issueCnt   = Reg(UInt(log2Up(N + 1) bits)) init (N)
  val collectCnt = Reg(UInt(log2Up(N + 1) bits)) init (N)
  val since      = Reg(UInt(log2Up(Lc + 1) bits)) init (0) // cycles since restart, saturating at Lc
  val regen      = RegInit(False)
  regen.addAttribute("max_fanout", 32)

  // issue N commands (phase = k·f) on consecutive cycles.
  val issuing = issueCnt < N
  cmdValid := issuing
  cmdPhase := zAcc
  when(issuing) {
    zAcc     := zAcc + fReg // exact phase wrap (truncating add)
    issueCnt := issueCnt + 1
  }

  when(regen && since < Lc)(since := since + 1)

  // Collect responses once past the computation latency — skips stale in-flight responses on a restart.
  // One shared shift-enable for all N lanes (no per-lane write address ⇒ 1 control set, not N).
  //
  // The accept decision is a wide comparison (`rspValid && since===Lc && collectCnt<N`). Rather than
  // drive the 2·N-lane shift register's shared clock-enable straight off that combinational net (which
  // fans it out to every phRe/phIm FF), buffer it in a register (`collect`): the wide compare now drives
  // a single FF, and the registered enable — `max_fanout`-capped so the tool can replicate it next to the
  // lanes — drives the array. The response payload is delayed one matching stage (`rspReBuf`/`rspImBuf`)
  // so the data stays aligned with the now-registered enable; without it the registered enable would
  // shift in the *next* response. Cost: the regen window is one cycle longer, folded into `regenCycles`
  // (so the toplevel freq-queue lead time tracks it automatically).
  val collectEn = rspValid && since === Lc && collectCnt < N   // wide decision: drives only the reg below
  val collect   = RegNext(collectEn) init False                // buffered shift-enable (the 2·N-FF driver)
  collect.addAttribute("max_fanout", 32)
  val rspReBuf  = RegNext(rspRe)                               // payload delayed to match the buffered enable
  val rspImBuf  = RegNext(rspIm)
  when(collect) {
    for (k <- N - 1 downto 1) { phRe(k) := phRe(k - 1); phIm(k) := phIm(k - 1) }
    phRe(0)    := rspReBuf
    phIm(0)    := rspImBuf
    collectCnt := collectCnt + 1
    when(collectCnt === N - 1)(regen := False) // last lane collected ⇒ batch ready next cycle
  }

  // restart on a freq fire — placed LAST so it takes precedence over in-flight FSM updates above.
  when(io.freq.valid) {
    fReg       := io.freq.payload
    zAcc       := 0
    issueCnt   := 0
    collectCnt := 0
    since      := 0
    regen      := True
  }

  io.phasors.valid := !regen
  for (k <- 0 until N) { // reversed read: shift puts resp(k) at lane N-1-k (see the shift-collect note)
    io.phasors.payload(k).re := phRe(N - 1 - k)
    io.phasors.payload(k).im := phIm(N - 1 - k)
  }

  /** freq fire → phasors-valid latency (the regen window). `+2`: `Lc` to the first response, `N` lane
   *  shifts, and the one extra cycle from the registered `collect` enable + matched payload buffer. */
  def regenCycles: Int = N + Lc + 2
}
