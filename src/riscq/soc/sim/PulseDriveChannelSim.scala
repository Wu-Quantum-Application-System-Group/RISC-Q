package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp.SinCosMethod
import riscq.soc.link.Put
import riscq.soc.rf.PulseDriveChannel

/**
 * Functional sign-off for the memory-mapped **`phaseOffset`** (virtual Z) in [[PulseDriveChannel]]:
 * the offset is added to the generator's phase input, so firing a pulse with `table.phase = P` and
 * `phaseOffset = Δ` must produce **the bit-exact same** output as firing with `table.phase = (P+Δ) mod
 * 2^w` and `phaseOffset = 0`. This is checked directly against the DUT itself (no CORDIC golden needed):
 * fire the same pulse three ways on one channel and compare the captured output windows.
 *
 *   - baseline : `phase = P`,     `phaseOffset = 0`
 *   - reference: `phase = P + Δ`, `phaseOffset = 0`   (the rotation folded into the table phase)
 *   - offset   : `phase = P`,     `phaseOffset = Δ`   (the rotation applied via the virtual-Z register)
 *
 * Assert `offset == reference` (the offset is added exactly to the phase input, wrapping mod 2^w) and
 * `offset != baseline` (the Δ rotation is not a no-op). Δ is chosen to wrap the 16-bit phase, so the
 * modular add is exercised too.
 *
 * Run with `mill runMain riscq.soc.sim.PulseDriveChannelSim`.
 */
object PulseDriveChannelSim extends App {

  val batchSize  = 4
  val w          = 16
  val timeWidth  = 32
  val envAddrW   = 6
  val durWidth   = 8
  val pulseNum   = 2
  val memLatency = 2
  val putAddrWidth = 16
  val prescaleAmp = true
  val saturate    = false

  val N      = batchSize
  val maskW  = BigInt(1) << w
  val nA     = 1 << envAddrW

  // complex envelope memory content (same scheme as PulseParamBufferSim) — just needs to be non-zero.
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a) else BigInt(a * 5 + k * 11 + 3)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 1) & (maskW - 1)
  val content: Seq[BigInt] = (0 until nA).map { a =>
    var word = BigInt(0)
    for (k <- 0 until N) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }

  /** Test top: one [[PulseDriveChannel]] driven by a poke-able posted `Put` Flow + `timeBcast`, its
   *  envelope read port wired to the complex envelope Mem, its DAC `pulse` re-exported. */
  case class Dut() extends Component {
    val cmd       = slave port Flow(Put(putAddrWidth))
    val timeBcast = in port UInt(timeWidth bits)

    val ch = PulseDriveChannel(
      pulseNum = pulseNum, batchSize = batchSize, dataWidth = w, envAddrWidth = envAddrW,
      durWidth = durWidth, timeWidth = timeWidth, memLatency = memLatency, prescaleAmp = prescaleAmp,
      saturate = saturate, phasorMethod = SinCosMethod.Cordic, realOutput = false, putAddrWidth = putAddrWidth)
    ch.io.cmd << cmd
    ch.io.timeBcast := timeBcast

    val mem = Mem.fill(content.length)(Bits(N * 2 * w bits)) init (content.map(c => B(c, N * 2 * w bits)))
    ch.io.memPort.rsp := Delay(mem.readSync(ch.io.memPort.cmd.payload), memLatency - 1)

    val pulse = master port cloneOf(ch.io.pulse)
    pulse << ch.io.pulse
  }

  def w16(v: BigInt): Int = (((v & 0xFFFF) << 16) & 0xFFFFFFFFL).toInt // 16-bit field in data[31:16]

  // one pulse: same table entry / amp / freq / envelope / dur across all three shots; only startTime,
  // the table phase, and the phaseOffset register vary. freq = 0 keeps the carrier time-invariant (the
  // per-batch angle depends on the absolute time `startTime + i`), so the three shots — fired at
  // different startTimes — are directly comparable and the test isolates the phase rotation alone.
  val idx = 0; val amp = 10000; val freq = 0; val base = 10; val dur = 6
  val P = 20000; val D = 16384                     // Δ = quarter turn (2^14); P+Δ = 36384 wraps SInt16.
  case class Shot(startTime: Int, phase: BigInt, phaseOffset: BigInt)
  val baseline  = Shot(startTime = 300, phase = P,     phaseOffset = 0)
  val reference = Shot(startTime = 450, phase = P + D, phaseOffset = 0)  // rotation folded into phase
  val offset    = Shot(startTime = 600, phase = P,     phaseOffset = D)  // rotation via virtual-Z reg
  val totalCycles = 720

  SimConfig.compile(Dut()).doSim("pulseDriveChannel", seed = 42) { dut =>
    val cd = dut.clockDomain
    dut.cmd.valid #= false
    dut.cmd.payload.address #= 0
    dut.cmd.payload.data #= 0
    dut.timeBcast #= 0
    cd.forkStimulus(10)
    cd.waitSampling(20)

    def post(addr: Int, data: Int): Unit = {
      dut.cmd.valid #= true
      dut.cmd.payload.address #= addr
      dut.cmd.payload.data #= data & 0xFFFFFFFFL
      cd.waitSampling()
      dut.cmd.valid #= false
      cd.waitSampling(2)
    }
    def fire(s: Shot): Unit = {
      post(0x4100, s.startTime)             // per-buffer startTime
      post(0x4,  w16(freq))                 // shared freq @4
      post(0xC,  w16(s.phaseOffset))        // phaseOffset register @0xC (persists; written explicitly)
      val b = (idx + 1) * 0x10
      post(b + 0,  w16(s.phase))            // table[idx].phase
      post(b + 4,  w16(amp))                // table[idx].amp
      post(b + 8,  w16(BigInt(base)))       // table[idx].env
      post(b + 12, w16(BigInt(dur)))        // table[idx].dur
      post(0x0, idx)                        // fire
    }
    fire(baseline); fire(reference); fire(offset)

    // ── ramp timeBcast and capture the pulse per cycle ──
    val capV  = Array.ofDim[Boolean](totalCycles)
    val capRe = Array.ofDim[BigInt](totalCycles, N)
    val capIm = Array.ofDim[BigInt](totalCycles, N)
    for (c <- 0 until totalCycles) {
      dut.timeBcast #= c
      cd.waitSampling()
      capV(c) = dut.pulse.valid.toBoolean
      for (k <- 0 until N) { capRe(c)(k) = dut.pulse.payload(k).re.toBigInt; capIm(c)(k) = dut.pulse.payload(k).im.toBigInt }
    }

    // extract a shot's contiguous `dur`-long valid window as a dur×N complex array + its start offset.
    def windowOf(s: Shot): (Int, Array[Array[(BigInt, BigInt)]]) = {
      val win = (0 until totalCycles).filter(capV).filter(c => c >= s.startTime - 10 && c < s.startTime + dur + 20)
      assert(win.nonEmpty, s"[shot@${s.startTime}] no valid window")
      val c0 = win.head
      assert(win == (c0 until c0 + dur), s"[shot@${s.startTime}] window $win != [$c0, ${c0 + dur})")
      (c0 - s.startTime, Array.tabulate(dur, N)((i, k) => (capRe(c0 + i)(k), capIm(c0 + i)(k))))
    }
    val (offB, wBase) = windowOf(baseline)
    val (offR, wRef)  = windowOf(reference)
    val (offO, wOff)  = windowOf(offset)
    assert(offB == offR && offR == offO, s"channel bulk latency differs across shots: $offB/$offR/$offO")

    // sanity: the reference pulse is non-zero, so the comparisons below are not vacuous.
    val nonZero = (0 until dur).exists(i => (0 until N).exists(k => wRef(i)(k) != ((BigInt(0), BigInt(0)))))
    assert(nonZero, "reference pulse is all-zero — test would be vacuous")

    // equivalence: phaseOffset Δ is added exactly to the phase input ⇒ bit-identical to folding Δ into
    // the table phase (both wrap mod 2^w; Δ here wraps SInt16, so the modular add is exercised).
    for (i <- 0 until dur; k <- 0 until N)
      assert(wOff(i)(k) == wRef(i)(k),
        s"[equiv] batch $i lane $k: phaseOffset ${wOff(i)(k)} != phase-add ${wRef(i)(k)}")

    // non-trivial: the Δ rotation actually changes the pulse vs the Δ=0 baseline.
    val changed = (0 until dur).exists(i => (0 until N).exists(k => wOff(i)(k) != wBase(i)(k)))
    assert(changed, "[non-trivial] phaseOffset had no effect on the pulse (expected a virtual-Z rotation)")

    println(s"[PulseDriveChannelSim] PASS  N=$N w=$w: phaseOffset Δ=$D bit-exactly equals folding Δ into the " +
      s"table phase (offset≡phase-add, wrapping mod 2^$w) and rotates the pulse vs the Δ=0 baseline; bulk latency $offO.")
    simSuccess()
  }

  // ── spec 09 B0: fire-train equivalence (bit-exact at the DAC window) ──
  // A pulse train scheduled as `set_start(t)` + N bare `fire`s — startTime auto-advancing by the
  // entry's `dur` on each fire — must equal N explicit plays `set_start(t + k*dur) + fire`, pulse
  // for pulse. freq=0 keeps the carrier time-invariant, so the two schedules land the same N
  // windows iff the auto-advance schedules and captures each pulse correctly. queueDepth caps
  // pending pulses at 4, so the two schedules run as two passes on one compiled DUT; each contiguous
  // run of N·dur valid cycles is sliced into N dur-long windows (windowOf generalized to a
  // back-to-back run) and compared lane-by-lane.
  {
    val NT      = 3
    val trainAt = 100
    val compiledT = SimConfig.compile(Dut())

    // run one timeBcast ramp after `program` posts its Put beats; return the per-cycle capture.
    def captureRun(program: (((Int, Int) => Unit), (Seq[(Int, Int)] => Unit)) => Unit)
        : (Array[Boolean], Array[Array[BigInt]], Array[Array[BigInt]]) = {
      val V  = Array.ofDim[Boolean](totalCycles)
      val Re = Array.ofDim[BigInt](totalCycles, N)
      val Im = Array.ofDim[BigInt](totalCycles, N)
      compiledT.doSim("trainEquiv") { dut =>
        val cd = dut.clockDomain
        dut.cmd.valid #= false; dut.cmd.payload.address #= 0; dut.cmd.payload.data #= 0; dut.timeBcast #= 0
        cd.forkStimulus(10); cd.waitSampling(20)
        def post(addr: Int, data: Int): Unit = {
          dut.cmd.valid #= true; dut.cmd.payload.address #= addr; dut.cmd.payload.data #= data & 0xFFFFFFFFL
          cd.waitSampling(); dut.cmd.valid #= false; cd.waitSampling(2)
        }
        // tight variant: cmd.valid on ADJACENT cycles (the back-to-back fire train).
        def postTight(beats: Seq[(Int, Int)]): Unit = {
          for ((a, d) <- beats) {
            dut.cmd.valid #= true; dut.cmd.payload.address #= a; dut.cmd.payload.data #= d & 0xFFFFFFFFL; cd.waitSampling()
          }
          dut.cmd.valid #= false; cd.waitSampling(3)
        }
        program(post, postTight)
        for (c <- 0 until totalCycles) {
          dut.timeBcast #= c; cd.waitSampling()
          V(c) = dut.pulse.valid.toBoolean
          for (k <- 0 until N) { Re(c)(k) = dut.pulse.payload(k).re.toBigInt; Im(c)(k) = dut.pulse.payload(k).im.toBigInt }
        }
        simSuccess()
      }
      (V, Re, Im)
    }

    def programTable(post: (Int, Int) => Unit): Unit = {
      post(0x4, w16(freq))
      val b = (idx + 1) * 0x10
      post(b + 0, w16(P)); post(b + 4, w16(amp)); post(b + 8, w16(BigInt(base))); post(b + 12, w16(BigInt(dur)))
    }
    // pass 1: the train — one set_start then N bare fires on adjacent beats.
    val (vT, reT, imT) = captureRun { (post, postTight) =>
      programTable(post); post(0x4100, trainAt); postTight(Seq.fill(NT)((0x0, idx)))
    }
    // pass 2: N explicit plays — set_start(trainAt + k*dur) + fire (the same contiguous schedule).
    val (vR, reR, imR) = captureRun { (post, _) =>
      programTable(post); for (k <- 0 until NT) { post(0x4100, trainAt + k * dur); post(0x0, idx) }
    }

    // slice a single back-to-back run of NT·dur valid cycles into NT dur-long windows.
    def contigWindows(V: Array[Boolean], Re: Array[Array[BigInt]], Im: Array[Array[BigInt]]) = {
      val win = (0 until totalCycles).filter(V).filter(c => c >= trainAt - 10 && c < trainAt + NT * dur + 20)
      assert(win.nonEmpty, s"[fire-train] no valid window near $trainAt")
      val c0 = win.head
      assert(win == (c0 until c0 + NT * dur), s"[fire-train] window $win != [$c0, ${c0 + NT * dur})")
      Array.tabulate(NT, dur, N)((k, i, j) => (Re(c0 + k * dur + i)(j), Im(c0 + k * dur + i)(j)))
    }
    val wT = contigWindows(vT, reT, imT)
    val wR = contigWindows(vR, reR, imR)
    assert((0 until NT).exists(k => (0 until dur).exists(i => (0 until N).exists(j => wR(k)(i)(j) != ((BigInt(0), BigInt(0)))))),
      "[fire-train] reference windows all-zero — test would be vacuous")
    for (k <- 0 until NT; i <- 0 until dur; j <- 0 until N)
      assert(wT(k)(i)(j) == wR(k)(i)(j),
        s"[fire-train] pulse $k batch $i lane $j: train ${wT(k)(i)(j)} != explicit play ${wR(k)(i)(j)}")
    println(s"[PulseDriveChannelSim] PASS  fire-train: set_start + $NT bare fires (startTime auto-advance) " +
      s"bit-exact vs $NT explicit set_start+fire plays at the DAC window.")
  }
}
