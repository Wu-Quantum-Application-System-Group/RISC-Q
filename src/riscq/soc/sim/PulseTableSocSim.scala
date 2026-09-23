package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.DebugId
import spinal.lib.bus.tilelink.sim.{IdAllocator, IdCallback, MasterAgent}
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.soc.PulseTableSoc

/**
 * A **bus-driven** integration test of the assembled [[PulseTableSoc]] — no software ELFs. Each core's
 * CPU runs a 1-instruction self-loop (so its `dBus` stays idle and the batch clock `refTime` advances),
 * and the test drives the SoC two ways:
 *
 *   - an `Axi4Master` on `io.axi` writes a word into the readout-buffer region and reads it back —
 *     proving the host AXI → Tilelink bridge + fabric fan-out + on-chip RAM round-trips;
 *   - a `MasterAgent` on the (test-only) per-core data-bus master schedules a gate pulse through the
 *     SoC's RF decode tree (the writes the control software would issue), and we assert the pulse
 *     propagates through the `dacMap` reduction to the mapped physical DAC;
 *   - the same `MasterAgent` drives the readout path (item (b)): a free-running real ADC tone is fed
 *     into the mapped physical ADC (`io.adc`), the LO carrier is tuned via the demod fiber, and two
 *     integration windows are armed — one with the LO **matched** to the tone, one **detuned**. We
 *     assert the read-back `real/imag` magnitude is large when matched and collapses when detuned (the
 *     VNA principle). The magnitude is phase-invariant for a matched tone, so this is robust to the
 *     SoC's bulk pipeline latency between `io.adc`/`io.time` and the integrator.
 *
 * (Per-pulse *shape* and the bit-exact readout integral are verified in the per-block sims; this checks
 * the SoC-level wiring — AXI access, channel→DAC mapping, and the full
 * ADC→demod→integrate→read-back path.) Run with `./.metals/mill runMain riscq.soc.sim.PulseTableSocSim`.
 */
object PulseTableSocSim extends App {
  val qubitNum = 2
  val dacMap   = Map((0, 0) -> 8, (0, 1) -> 8, (1, 0) -> 1, (1, 1) -> 1)
  val adcMap   = Map(0 -> 12, 1 -> 13)
  val N = 16; val w = 16; val maskW = BigInt(1) << w
  val gateInterp = 4               // gate-drive envelope stored interp×4 narrower (matches DUT default)
  val envAddrW = 10; val nA = 1 << envAddrW

  // pulse-envelope content (non-trivial so the pulse is clearly non-zero). The gate bank is interpolated,
  // so a word holds N/gateInterp stored complex samples (block-replicated back to N lanes at read).
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a + 1) else BigInt(a * 5 + k * 11 + 17)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 9) & (maskW - 1)
  def envWord(a: Int): BigInt = {
    var word = BigInt(0)
    for (k <- 0 until N / gateInterp) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }

  def w16(v: BigInt): Int = (((v & 0xFFFF) << 16) & 0xFFFFFFFFL).toInt
  val JAL_SELF = BigInt("6f", 16) // jal x0, 0 — 1-instruction infinite loop (no dBus traffic)

  // Narrow posted-link RF architecture: `startTime` is per-buffer (gate @0x14100, demod @0x34100) and
  // the readout result is read from the core-local ReadoutResultSink (res@0x4200/real@0x4204/imag@0x4208)
  // — the schedule, the dacMap reduction and the VNA readout are otherwise the same writes the control
  // software issues. The readout is carrier-triggered: firing the demod pulse IS the readout (no arm).
  def runSoc(linkPipe: Int): Unit = {
  val gateStAddr  = 0x14100  // gate buffer's per-buffer startTime (put window @0x10000 + 0x4100)
  val demodStAddr = 0x34100  // demod buffer's per-buffer startTime (put window @0x30000 + 0x4100)
  val resAddr    = 0x4200    // core-local ReadoutResultSink (res@0x4200 / real@0x4204 / imag@0x4208)
  val realAddr   = 0x4204
  val imagAddr   = 0x4208
  val tag        = s"posted_pipe$linkPipe"
  SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")  // the clock-crossing Bram blackbox arrays are written from clka+clkb
    .addSimulatorFlag("--x-initial 0")            // 0-init the host→dsp CDC FIFO state so its pre-reset X address can't trip a spurious Tilelink decoder-miss assertion
    .compile(PulseTableSoc(qubitNum, dacMap, adcMap, withTest = true, linkPipe = linkPipe))
    .doSim(s"pulseTableSoc_$tag", seed = 42) { dut =>
    val hostCd = dut.clockDomain
    val dspCd  = dut.dspCd

    // suppress X-valued phantom requests on the (initially undriven) bus masters during reset.
    dut.io.axi.ar.valid #= false; dut.io.axi.aw.valid #= false; dut.io.axi.w.valid #= false
    dut.io.axi.r.ready #= false;  dut.io.axi.b.ready #= false
    dut.riscqArea.testMasters(0).node.bus.a.valid #= false
    // free-running converters: hold every ADC payload at 0 (the SoC reads `io.adc` combinationally and
    // ignores `valid`, but driving both keeps the demod/robs reductions out of X-land before Part 3).
    for (i <- 0 until dut.io.adc.length) { dut.io.adc(i).valid #= true; dut.io.adc(i).payload #= 0 }

    hostCd.forkStimulus(10)
    dspCd.forkStimulus(10)

    hostCd.waitSampling(40) // io.dspRst (forkStimulus) deasserts

    val axi = Axi4Master(dut.io.axi, hostCd)

    // host bus-load: the program + gate envelope arrive over io.axi → the iMem / pulseMem fabric, exactly
    // as the real SoC loads them — a blackbox `Bram` has no SpinalHDL `Mem` to backdoor-poke. The CPU-mem
    // and pulse-RAM host ports stay live while the cores are held in riscqReset, so this lands before the
    // reset release below. Each 32-bit lane is one 4-byte AXI write; the write-only envelope fiber steers
    // it to the addressed sub-word of the wider pulse-envelope line.
    def leBytes(v: BigInt, n: Int): List[Byte] = List.tabulate(n)(i => ((v >> (8 * i)) & 0xFF).toByte)
    def loadInstr(core: Int, word: Int, v: BigInt): Unit =
      axi.write(BigInt(dut.map.coreMemOffset(core)) + word.toLong * 4, leBytes(v, 4))
    val gateEnvBytes = (N / gateInterp) * 2 * w / 8                 // bytes per interpolated gate line (16)
    def loadEnv(core: Int, a: Int, word: BigInt): Unit = {
      val wordAddr = BigInt(dut.map.envOffset(core, 0)) + a.toLong * gateEnvBytes
      for (lane <- 0 until gateEnvBytes / 4) axi.write(wordAddr + lane * 4, leBytes(word >> (lane * 32), 4))
    }
    // both cores loop on JAL-self; core-0 gate-drive envelope over the read window [base, base+dur) ⊂ [0,64).
    for (c <- 0 until qubitNum) loadInstr(c, 0, JAL_SELF)
    for (a <- 0 until 64) loadEnv(0, a, envWord(a))

    // ── release the cores from reset via the host control block (region 3, riscqReset @ offset 0) ──
    // The drive register is otherwise uninitialized (as in the reference), so the cores boot held in
    // reset (the CPUs + riscqCd RF/control fabric are gated by riscqReset). `refTime`/`time` free-run in
    // dspCd, so batch time is already advancing while reset is held — the release just lets the cores act
    // on it. Assert then deassert for a clean release edge, exactly as the reference testbench does
    // (0x01 = reset up, 0x00 = reset down).
    val hostCtrlAddr = BigInt(dut.map.hostCtrlBase)
    axi.write(hostCtrlAddr, List(0x01, 0x00, 0x00, 0x00).map(_.toByte)) // riscqReset up (hold cores)
    hostCd.waitSampling(20)
    axi.write(hostCtrlAddr, List(0x00, 0x00, 0x00, 0x00).map(_.toByte)) // riscqReset down (release)
    hostCd.waitSampling(60) // BufferCC(5)+Delay(5) CDC + settle ⇒ refTime/time advance, CPUs run

    // confirm the batch time is now advancing before scheduling anything through the riscqCd fabric.
    val tA = dut.riscqArea.time.toBigInt.toInt
    dspCd.waitSampling(50)
    val tB = dut.riscqArea.time.toBigInt.toInt
    assert(tB != tA, s"[M3 time] batch time not advancing after reset release ($tA -> $tB)")
    println(s"[PulseTableSocSim] reset released; batch time advancing ($tA -> $tB)")

    // ── Part 1: AXI host bridge round-trip into the readout-buffer region ──
    val robByteAddr = BigInt(dut.map.robBase)  // the readout-trace region
    val testWord = List(0x11, 0x22, 0x33, 0x44).map(_.toByte)
    axi.write(robByteAddr, testWord)
    hostCd.waitSampling(5)
    val readBack = axi.read(robByteAddr, 4)
    assert(readBack == testWord, s"[M3 AXI] robs round-trip ${readBack.map(_ & 0xff)} != ${testWord.map(_ & 0xff)}")
    println(s"[PulseTableSocSim] AXI host bridge round-trip OK (wrote/read ${testWord.map(_ & 0xff).mkString(",")} at readout buffers)")

    // ── Part 2: schedule a gate pulse on core 0 via the test data-bus master ──
    implicit val idAllocator = new IdAllocator(DebugId.width)
    implicit val idCallback  = new IdCallback
    val agent = new MasterAgent(dut.riscqArea.testMasters(0).node.bus, dspCd)
    val src = 0
    def wr(addr: Int, word: Int): Unit = { agent.putInt(src, addr, word); dspCd.waitSampling(2) }

    val dur = 6; val base = 10; val freq = 1800; val amp = 9000; val phase = 2000
    val startTime = dut.riscqArea.time.toBigInt.toInt + 400   // schedule well ahead of the live batch time
    // control block: startTime ; RF tree gate @0x10000 (outId@0, freq@4, table[0]@0x10..0x1C)
    wr(gateStAddr, startTime)
    wr(0x10004, w16(freq))
    wr(0x10010, w16(phase)); wr(0x10014, w16(amp)); wr(0x10018, w16(base)); wr(0x1001C, w16(dur))
    wr(0x10000, 0) // fire outId 0

    // ── capture the mapped DAC (dacMap (0,0)->8, (0,1)->8 ⇒ DAC 8 sums core-0 gate + readout drive) ──
    // The gate pulse (channel (0,0)) must reach physical DAC 8 through the dacMap AdderTree reduction.
    // We track the longest non-zero run of DAC 8, sweeping well past the scheduled pulse.
    val dac8      = dut.io.dac(8)
    val gateValid = dut.riscqArea.riscqCores(0).gatePulse.valid
    val stopTime  = startTime + 60
    var dacNonZeroRun = 0; var maxRun = 0; var gateValids = 0; var guard = 0
    while (dut.riscqArea.time.toBigInt.toInt < stopTime && guard < 8000) {
      dspCd.waitSampling()
      guard += 1
      if (gateValid.toBoolean) gateValids += 1
      if (dac8.payload.toBigInt != 0) { dacNonZeroRun += 1; maxRun = scala.math.max(maxRun, dacNonZeroRun) }
      else dacNonZeroRun = 0
    }
    println(s"[PulseTableSocSim] gate scheduled@$startTime: gateValids=$gateValids maxDacRun=$maxRun")

    assert(gateValids >= dur, s"[M3 gate] gate pulse-generator valid run $gateValids < expected dur $dur")
    assert(maxRun >= dur, s"[M3 DAC] mapped DAC 8 non-zero run $maxRun < expected pulse dur $dur")
    println(s"[PulseTableSocSim] PASS: scheduled gate pulse drove DAC 8 (non-zero run $maxRun ≥ dur=$dur) — " +
      s"AXI bridge + dacMap reduction functional.")

    // ── Part 3: readout — drive a real ADC tone and assert the demod magnitude tracks it (item (b)) ──
    // The full path: io.adc → adcBufs → core 0 ADC → ReadoutDecoder demod (× LO carrier) → integrate →
    // halting TL read-back. We tune the LO carrier (readoutDemod fiber) and feed a free-running real
    // cosine into the mapped physical ADC. The CORDIC LO is `exp(iπ·(F·s + φ)/2^(w−1))` at sample index
    // s = adcN·t + k (`F` the 16-bit freq code), so a cosine at the same `F` is frequency-matched and the
    // demod integral magnitude `≈ (Aadc·Alo/2^(w−1))·(samples/2)` is large; a detuned tone averages to
    // ~0. Magnitude is phase-invariant for a matched tone ⇒ robust to the bulk io.adc→integrator latency
    // (the reason absolute sample alignment is not checked here).
    val adcN     = 4                               // SoC adcBatch (readout demod/decoder lanes)
    val adcId    = adcMap(0)                        // physical ADC feeding core 0 (12)
    val Fcarrier = 2048                             // LO freq code: π·F/2^15 ≈ π/16 rad per sample
    val Fdetuned = 12288                            // a clearly different ADC tone (strong beat ⇒ ~0)
    val Aadc     = 28000                            // ADC tone amplitude, < full scale (2^15−1)
    def adcWord(t: Long, f: Int): BigInt = {
      var word = BigInt(0)
      for (k <- 0 until adcN) {
        val s    = t * adcN + k
        val ang  = scala.math.Pi * (f.toDouble * s) / (1 << (w - 1))
        val code = BigInt(scala.math.round(Aadc * scala.math.cos(ang)).toInt & 0xFFFF)
        word |= code << (k * w)
      }
      word
    }
    def signed32(u: BigInt): BigInt = { val m = BigInt(1) << 32; val r = ((u % m) + m) % m; if (r >= (BigInt(1) << 31)) r - m else r }

    // the demod carrier is now a scheduled, envelope-shaped complex pulse (readoutDemod @0x30000 is a
    // full drive channel: fire@0, freq@4, table[0]@0x10.., startTime@0x4100). Load a SQUARE demod
    // envelope (constant, ~full-scale real) so the carrier behaves like the old always-on LO within the
    // window — the host env RAM (demod bank, 32-bit interpolated line: re@[15:0], im@[31:16]).
    val demodEnvE = 0x7FFF                              // ~unity square envelope (real, im = 0)
    def loadDemodEnv(core: Int, a: Int, word: BigInt): Unit =
      axi.write(BigInt(dut.map.envOffset(core, 2)) + a.toLong * 4, leBytes(word, 4))
    // prescaleAmp ⇒ the CORDIC runs uncorrected (×K≈1.65); with saturate=false amp must stay well below
    // full scale so Cordic(amp)·phasor·env doesn't overflow/wrap (matches PulseGeneratorSim's amp≈10000).
    val demodBase = 0; val demodAmp = 12000
    for (a <- 0 until 64) loadDemodEnv(0, a, BigInt(demodEnvE))

    // free-running ADC stimulus, phase-locked to the SoC batch time (so its frequency exactly matches
    // the LO's); `adcFreq` is switched between windows. Forks are cooperative ⇒ a plain var suffices.
    var adcFreq = Fcarrier
    fork { while (true) { dut.io.adc(adcId).payload #= adcWord(dut.riscqArea.time.toBigInt.toLong, adcFreq); dspCd.waitSampling() } }

    val roDur = 20     // demod window = integration window (batches); integrates roDur batches × adcN lanes
    def runWindow(label: String): Double = {
      val st = dut.riscqArea.time.toBigInt.toInt + 200 // schedule the demod window well ahead of live time
      // program + fire the demod carrier pulse (envelope-shaped LO). Firing it IS the readout: the
      // carrier's valid window triggers the decoder — there is no separate arm. startTime-first order.
      wr(demodStAddr, st)                              // demod startTime (= integration window start)
      wr(0x30004, w16(Fcarrier))                       // demod freq
      wr(0x30010, w16(0)); wr(0x30014, w16(demodAmp))  // table[0]: phase, amp
      wr(0x30018, w16(demodBase)); wr(0x3001C, w16(roDur)) // table[0]: env base, dur = window
      wr(0x30000, 0)                                   // fire demod (slot 0) — the readout window
      // software freshness contract: wait past the window (here past its close) before reading, so the
      // halting res read returns THIS window's result, not the previous shot's still-held level.
      waitUntil(dut.riscqArea.time.toBigInt.toInt >= st + roDur + 60)
      agent.getInt(src, resAddr)                       // res — HALTS until the integral settles
      val re = signed32(agent.getInt(src, realAddr))   // real
      val im = signed32(agent.getInt(src, imagAddr))   // imag
      val mag = scala.math.sqrt((re * re + im * im).toDouble)
      println(s"[PulseTableSocSim] readout $label: real=$re imag=$im |z|=${mag.toLong}")
      mag
    }

    adcFreq = Fcarrier; val magMatched = runWindow("matched")
    adcFreq = Fdetuned; dspCd.waitSampling(60) // flush the matched tone out of the pipeline before arming
    val magDetuned = runWindow("detuned")

    assert(magMatched > 200000, s"[M3 readout] matched-LO magnitude ${magMatched.toLong} too small — demod/integrate path not tracking the tone")
    assert(magMatched > 4 * magDetuned, s"[M3 readout] no frequency selectivity: matched ${magMatched.toLong} not ≫ detuned ${magDetuned.toLong}")
    println(s"[PulseTableSocSim] PASS [$tag]: ADC tone → readout |z| matched=${magMatched.toLong} ≫ detuned=${magDetuned.toLong} " +
      s"(ratio ${(magMatched / scala.math.max(magDetuned, 1.0)).toInt}×) — full ADC→demod→integrate→read-back path tracks the tone.")
    simSuccess()
  }
  }

  runSoc(linkPipe = 8)
}
