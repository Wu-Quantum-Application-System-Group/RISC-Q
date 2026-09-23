package riscq.soc.rf

import spinal.core._
import spinal.lib._
import riscq.soc.link.Put

/** One pulse's parameters as stored in the CPU-writable table: raw `Bits` (the PulseGenerator io
 *  flows `assignFromBits` them). `env` is the envelope-memory base address. Each field's width matches
 *  its destination generator port (so `assignFromBits` is a no-resize copy). */
case class PulseTableTerm(dataWidth: Int, envWidth: Int, durWidth: Int) extends Bundle {
  val phase = Bits(dataWidth bit)
  val amp   = Bits(dataWidth bit)
  val env   = Bits(envWidth bit)
  val dur   = Bits(durWidth bit)
}

/**
 * Address map + widths for a [[PulseParamBuffer]]. The offsets follow the RF register layout the
 * control software writes, with `startTime` folded in per-buffer:
 *
 *   - `fire`@0x0        — write the table index `outId` ⇒ enqueue that entry at the current `startTime`
 *   - `freq`@0x4        — shared carrier frequency (16-bit field at bit 16)
 *   - `dcOffset`@0x8    — DC bias added to the real output lanes downstream (16-bit field at bit 16)
 *   - `phaseOffset`@0xC — virtual-Z phase added to the generator's phase input downstream (16-bit field at bit 16)
 *   - `table[i]`        — entry `i` at `(i+1)*0x10`: `+0` phase, `+4` amp, `+8` env, `+12` dur
 *   - `startTime`@0x4100 — this buffer's own `startTime` register (per-buffer, posted)
 */
case class PulseParamBufferParams(
    pulseNum: Int,
    dataWidth: Int = 16,
    envAddrWidth: Int = 10,
    durWidth: Int = 16,
    timeWidth: Int = 32,
    addrWidth: Int = 16,         // Put address width (the buffer's RF sub-window)
    fireAddr: Int = 0x0,
    freqAddr: Int = 0x4,
    dcOffsetAddr: Int = 0x8,     // per-buffer DC bias for the real output lanes (16-bit field at bit 16)
    phaseOffsetAddr: Int = 0xC,  // per-buffer virtual-Z phase added to the generator's phase input (16-bit field at bit 16)
    startTimeAddr: Int = 0x4100,
    pulseOffset: Int = 0x10,     // 4 words per table entry; entry i at (i+1)*pulseOffset
    bitOffset: Int = 16,         // 16-bit fields packed in data[31:16]
    useMem: Boolean = true       // table storage: true (default) = distributed-RAM Mem; false = FF Vec
                                 // register file. Clamped to a register file when pulseNum = 1 (a depth-1
                                 // table has no address, e.g. ro/demod) — see `memTable` in the body.
) {
  require(pulseNum >= 1)
  require(addrWidth >= log2Up(startTimeAddr + 1), "addrWidth too small for startTimeAddr")
  // the parallel cmd decode splits the address at the 16-byte slot boundary (slot = address >> 4,
  // field = address[3:2]), so the layout must respect it:
  require(pulseOffset == 16, "table decode assumes 4-word (16-byte) entry slots")
  require(Seq(fireAddr, freqAddr, dcOffsetAddr, phaseOffsetAddr).forall(_ < pulseOffset),
    "scalar registers must sit in slot 0, below the table")
  require(startTimeAddr >= (pulseNum + 1) * pulseOffset, "startTimeAddr must sit past the table slots")
}

/**
 * DSP-side register file for one pulse generator — the posted-link register file. It holds the pulse
 * table, `freq`, `startTime` and a local `time` copy, driven by the demuxed **posted** `Flow(Put)`
 * (no TileLink, no D channel), and emits the parameter `Flow`s + `time`/`startTime` to a sibling
 * [[riscq.dsp.pulse.PulseGenerator]].
 *
 * The fire path (`outParamFlow` `Reg(Flow)` + `Delay` staging) keeps the pulse bit-exact vs the
 * [[riscq.dsp.pulse.PulseGenerator]] golden, and the local `time` copy is a single `RegNext` with
 * `EQUIVALENT_REGISTER_REMOVAL=NO`, so two buffers given the **same** `startTime` value rise on the
 * same output cycle — a software contract backed by equal `time`-broadcast delay to every buffer.
 */
case class PulseParamBuffer(p: PulseParamBufferParams) extends Component {
  import p._
  val w = dataWidth

  val io = new Bundle {
    val cmd       = slave  port Flow(Put(addrWidth))     // demuxed posted writes for THIS generator
    val timeBcast = in     port UInt(timeWidth bits)     // shared time broadcast (equal delay to all)
    val phase     = master port Flow(SInt(w bits))
    val amp       = master port Flow(SInt(w bits))
    val addr      = master port Flow(UInt(envAddrWidth bits))
    val dur       = master port Flow(UInt(durWidth bits))
    val freq      = master port Flow(SInt(w bits))
    val time      = out    port UInt(timeWidth bits)     // local copy → pg.io.time
    val startTime = out    port UInt(timeWidth bits)     // per-buffer, cmd-written → pg.io.startTime
    val dcOffset  = out    port SInt(w bits)             // per-buffer, cmd-written → real-lane DC bias
    val phaseOffset = out  port SInt(w bits)             // per-buffer, cmd-written → generator phase-input bias (virtual Z)
  }

  // local low-fanout time copy: equal pipeline delay across buffers ⇒ same-startTime same-cycle rise.
  io.time := RegNext(io.timeBcast).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")

  // ── posted register file ──
  val cmd = io.cmd
  def field(width: Int): Bits = cmd.payload.data(bitOffset, width bits)

  val startTime = Reg(UInt(timeWidth bits)) init 0
  // One export stage (spec 09 B0): a fired pulse reaches the timed-queue push 2 cycles after its
  // fire beat and the queues sample io.startTime at push, so this RegNext makes each fired pulse
  // capture the register as of its own fire beat (pre-increment) and back-to-back fires each
  // capture the running sum. See the auto-advance block after `outParam` below.
  io.startTime := RegNext(startTime)

  val dcOffset = Reg(SInt(w bits)) init 0
  // MAX_FANOUT cap (baked in, specs/dsp-fmax.md B2): Vivado replicates this quasi-static bias register
  // next to the consuming output-lane adders instead of routing one net across the channel's DSP
  // columns. Zero semantic change. On buffers whose dcOffset is unused (demod) it rides a pruned reg.
  dcOffset.addAttribute("MAX_FANOUT", 4)
  io.dcOffset := dcOffset

  val phaseOffset = Reg(SInt(w bits)) init 0
  io.phaseOffset := phaseOffset

  val outId = Reg(UInt(log2Up(pulseNum) bit)) init 0

  // ── cmd decode (combinational on address; cmd is the posted Flow, already a registered handoff).
  // The target address windows are disjoint, so every register decodes the Flow in parallel — the
  // scalar registers (fire / startTime / dcOffset / phaseOffset, and freq below) each cost one
  // exact-match compare, and the table write is split by address: entry i occupies the 16-byte slot
  // at (i+1)*pulseOffset, so the slot index is address>>4 (one range compare) and the written field
  // is address[3:2] (word offsets +0/+4/+8/+12) — not 4*pulseNum full-address comparators. Upstream
  // traffic is word-aligned 4-byte Puts (PutBridge), so address[1:0] is always 0. ──
  val addr = cmd.payload.address
  def hit(a: Int): Bool = cmd.valid && addr === a

  val outParamValid = hit(fireAddr)
  if (pulseNum > 1) when(outParamValid) { outId := cmd.payload.data(0, log2Up(pulseNum) bits).asUInt }

  val explicitStartWrite = hit(startTimeAddr)   // also gates the fire auto-advance below (explicit wins)
  when(explicitStartWrite)   { startTime   := cmd.payload.data(0, timeWidth bits).asUInt }
  when(hit(dcOffsetAddr))    { dcOffset    := field(w).asSInt }
  when(hit(phaseOffsetAddr)) { phaseOffset := field(w).asSInt }

  // table write request, shared by both storage styles; only one field of one entry per beat.
  val slot   = addr >> log2Up(pulseOffset)                  // table slot: entry i lives in slot i+1
  val tWrEn  = cmd.valid && slot =/= 0 && slot <= pulseNum  // table write enable
  val tWrIdx = UInt(log2Up(pulseNum) bit)                   // table write index
  tWrIdx := (slot - 1).resized  // don't-care outside tWrEn (the rmw read of a garbage index is discarded)
  val fieldSel = addr(3 downto 2)                           // word within the slot
  val wrPhase = fieldSel === 0
  val wrAmp   = fieldSel === 1
  val wrEnv   = fieldSel === 2
  val wrDur   = fieldSel === 3

  // ── pulse table: `pulseNum` PulseTableTerm entries, reset/init to zero so an un-programmed (or
  // spurious reset-window) fire reads a benign dur=0. Two bit-identical storage styles:
  //   - Vec : a flip-flop register file — combinational `table(outId)` read, direct per-field write.
  //   - Mem : a distributed-RAM (async-read) memory — drops the table out of FFs (fewer control sets /
  //           reset FFs) at the cost of a read-modify-write for the per-field write (only one field is
  //           written per beat, so the other fields are read back and re-stored). ──
  val zeroTerm = PulseTableTerm(w, envAddrWidth, durWidth).getZero
  val outParam = PulseTableTerm(w, envAddrWidth, durWidth)    // the fired entry, read by outId
  // Mem storage only where the table is addressable; a depth-1 table (pulseNum = 1) stays a register
  // file regardless of the requested `useMem`, since a depth-1 Mem has no address.
  val memTable = useMem && pulseNum >= 2
  if (!memTable) {
    val table = Vec.fill(pulseNum)(Reg(PulseTableTerm(w, envAddrWidth, durWidth)) init zeroTerm)
    outParam := table(outId)
    when(tWrEn) {
      val e = table(tWrIdx)
      when(wrPhase) { e.phase := field(w) }
      when(wrAmp)   { e.amp   := field(w) }
      when(wrEnv)   { e.env   := field(envAddrWidth) }
      when(wrDur)   { e.dur   := field(durWidth) }
    }
  } else {
    val table = Mem(PulseTableTerm(w, envAddrWidth, durWidth), pulseNum).init(Seq.fill(pulseNum)(zeroTerm))
    outParam := table.readAsync(outId)
    val rmw = CombInit(table.readAsync(tWrIdx))              // keep the untouched fields
    when(wrPhase) { rmw.phase := field(w) }
    when(wrAmp)   { rmw.amp   := field(w) }
    when(wrEnv)   { rmw.env   := field(envAddrWidth) }
    when(wrDur)   { rmw.dur   := field(durWidth) }
    table.write(tWrIdx, rmw, tWrEn)
  }

  // shared post-fire beat marker: outId/outParam settle the cycle after the fire beat, so this one
  // register both applies the B0 auto-advance and times the outParamFlow pulse below.
  val fired = RegNext(outParamValid) init False

  // spec 09 B0: a fire advances startTime by the fired entry's dur, applied the beat AFTER the
  // fire beat. An explicit startTime write that beat wins (the !explicitStartWrite guard).
  when(fired && !explicitStartWrite) {
    startTime := startTime + outParam.dur.asUInt
  }

  // fire path: writing outId pulses the selected entry into the param flows — Reg(Flow) + `fired`
  // staging so the pulse is bit-exact vs the PulseGenerator golden.
  val outParamFlow  = Reg(Flow(PulseTableTerm(w, envAddrWidth, durWidth)))
  outParamFlow.valid init False                       // reset-clean: no X-driven spurious fire at t=0
  outParamFlow.payload := outParam
  outParamFlow.valid   := fired
  KeepAttribute(outParamFlow)

  // freq is a separate always-driven flow (posted write; pulses valid on the freq write, like driveFlow).
  // One extra register stage on the way out cuts the critical path from the cmd address-decode /
  // field-extract into the downstream PulseGenerator freq queues. freq is queued against the per-buffer
  // startTime, which is NOT rewritten between the freq write and the fire, so the +1-cycle delay is
  // timing-invisible (the timed queue still captures the same startTime ⇒ bit-exact). valid inits False —
  // reset-clean, no X-driven spurious freq push at t=0 (mirrors outParamFlow above).
  io.freq.valid   := RegNext(hit(freqAddr)) init False
  io.freq.payload := RegNext(field(w).asSInt)

  // fire the popped table entry into the generator's queues.
  io.phase.valid := outParamFlow.valid; io.phase.payload := outParamFlow.phase.asSInt
  io.amp.valid   := outParamFlow.valid; io.amp.payload   := outParamFlow.amp.asSInt
  io.addr.valid  := outParamFlow.valid; io.addr.payload  := outParamFlow.env.asUInt
  io.dur.valid   := outParamFlow.valid; io.dur.payload   := outParamFlow.dur.asUInt
}
