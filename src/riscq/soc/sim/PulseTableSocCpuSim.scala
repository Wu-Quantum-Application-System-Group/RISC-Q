package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.Elf
import spinal.lib.sim.SparseMemory
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.soc.PulseTableSoc

import java.io.File

/**
 * '''CPU-in-the-loop''' sign-off: the same gate pulse that `PulseTableSocSim` schedules with a
 * test-master harness is now scheduled by the RISC-V control core executing a real program
 * (`sw/pulse_sched.S` → `sw/pulse_sched.elf`) fetched from its instruction RAM over the iBus fabric,
 * with the RF-register writes issued over the CPU's own dBus through the tilelink decode tree.
 *
 * Flow: backdoor-load the program into core 0 (and a benign `jal x0,0` self-loop into core 1) plus the
 * gate envelope; release the cores from reset over AXI (the host control block); then let the program
 * run — it reads `time`@0xbff8, writes `startTime = time + 1024`@0x4100, programs the gate-drive
 * table[0] and fires `outId` 0. We wait for the CPU to publish `startTime`, then assert the pulse
 * reaches physical DAC 8 (through the `dacMap` `AdderTree` reduction) for ≥ `dur` cycles — the
 * pulse-reaches-DAC property, now driven end-to-end by software.
 *
 * No test-master master (`withTest = false`): the CPU is the sole `dBus` master, exactly as the real
 * SoC. Run with `./.metals/mill runMain riscq.soc.sim.PulseTableSocCpuSim`.
 */
object PulseTableSocCpuSim extends App {
  val qubitNum = 2
  val dacMap   = Map((0, 0) -> 8, (0, 1) -> 8, (1, 0) -> 1, (1, 1) -> 1)
  val adcMap   = Map(0 -> 12, 1 -> 13)
  val N = 16; val w = 16; val maskW = BigInt(1) << w
  val gateInterp = 4               // gate-drive envelope stored interp×4 narrower (matches DUT default)
  val envAddrW = 10; val nA = 1 << envAddrW
  val memOffset = 0x80000000L

  // the program's gate parameters (must match sw/pulse_sched.S so the expected pulse is non-trivial).
  val progDur = 6

  // gate-drive envelope content — same generator as PulseTableSocSim, so the preloaded line is clearly
  // non-zero across the programmed window [env=10, env+dur).
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a + 1) else BigInt(a * 5 + k * 11 + 17)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 9) & (maskW - 1)
  def envWord(a: Int): BigInt = {
    var word = BigInt(0)
    for (k <- 0 until N / gateInterp) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }

  val JAL_SELF = BigInt("6f", 16) // jal x0, 0 — 1-instruction infinite loop (no dBus traffic)
  // default is the hand-written golden; RISCQ_ELF points the sim at any compiled program (e.g. the
  // software/firmware build of pulse.c) for the firmware sign-off in docs/software/02 §6.
  val elfFile  = new File(sys.env.getOrElse("RISCQ_ELF", "src/riscq/soc/sim/sw/pulse_sched.elf"))
  require(elfFile.exists(), s"missing ${elfFile.getPath} — rebuild it per the header of sw/pulse_sched.S")

  SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN") // the clock-crossing Bram blackbox arrays are written from clka+clkb
    .addSimulatorFlag("--x-initial 0")           // 0-init pre-reset X state (the host→dsp CDC FIFO, X until the first
                                                 // host write) so it can't trip a spurious Tilelink decoder-miss —
                                                 // mirrors PulseTableSocSim; on hardware this state powers up defined.
    .compile {
      val dut = PulseTableSoc(qubitNum, dacMap, adcMap, withTest = false)
      dut.riscqArea.time.simPublic()
      dut.riscqArea.riscqCores(0).startTime.simPublic()
      dut.riscqArea.riscqCores(0).gatePulse.valid.simPublic()
      dut
    }.doSim("pulseTableSocCpu", seed = 42) { dut =>
    val hostCd = dut.clockDomain
    val dspCd  = dut.dspCd

    // suppress X-valued phantom requests on the (initially undriven) AXI master during reset.
    dut.io.axi.ar.valid #= false; dut.io.axi.aw.valid #= false; dut.io.axi.w.valid #= false
    dut.io.axi.r.ready #= false;  dut.io.axi.b.ready #= false
    for (i <- 0 until dut.io.adc.length) { dut.io.adc(i).valid #= true; dut.io.adc(i).payload #= 0 }

    hostCd.forkStimulus(10)
    dspCd.forkStimulus(10)

    val image = SparseMemory(seed = 0)
    new Elf(elfFile, 32).load(image, 0)

    hostCd.waitSampling(40) // io.dspRst (forkStimulus) deasserts

    val axi = Axi4Master(dut.io.axi, hostCd)

    // host bus-load: the program + gate envelope arrive over io.axi → the iMem / pulseMem fabric, exactly
    // as the real SoC loads them — a blackbox `Bram` has no SpinalHDL `Mem` to backdoor-poke. The instruction
    // and pulse-RAM host ports stay live while the cores are held in riscqReset, so the program is in place
    // before the reset release below; each 32-bit lane is one 4-byte AXI write (the write-only envelope
    // fiber steers it to the addressed sub-word lane of the wider line).
    def leBytes(v: BigInt, n: Int): List[Byte] = List.tabulate(n)(i => ((v >> (8 * i)) & 0xFF).toByte)
    def loadInstr(core: Int, word: Int, v: BigInt): Unit =
      axi.write(BigInt(dut.map.coreMemOffset(core)) + word.toLong * 4, leBytes(v, 4))
    val gateEnvBytes = (N / gateInterp) * 2 * w / 8                 // bytes per interpolated gate line (16)
    def loadEnv(core: Int, a: Int, word: BigInt): Unit = {
      val wordAddr = BigInt(dut.map.envOffset(core, 0)) + a.toLong * gateEnvBytes
      for (lane <- 0 until gateEnvBytes / 4) axi.write(wordAddr + lane * 4, leBytes(word >> (lane * 32), 4))
    }
    // core-0 runs the pulse-scheduling program; core-1 self-loops; core-0 gate envelope over [base,base+dur) ⊂ [0,64).
    for (i <- 0 until 256) // covers the ~0x124-byte program (incl. the j-self loop); rest is unreached
      loadInstr(0, i, BigInt(image.readInt(memOffset + 4L * i).toLong & 0xFFFFFFFFL))
    loadInstr(1, 0, JAL_SELF)
    for (a <- 0 until 64) loadEnv(0, a, envWord(a))

    // ── release the cores from reset via the host control block (hostCtrlBase, riscqReset @ offset 0) ──
    // assert then deassert for a clean release edge, exactly as the reference testbench does
    // (0x01 = reset up/hold, 0x00 = reset down/release).
    val hostCtrlAddr = BigInt(dut.map.hostCtrlBase)
    axi.write(hostCtrlAddr, List(0x01, 0x00, 0x00, 0x00).map(_.toByte))
    hostCd.waitSampling(20)
    axi.write(hostCtrlAddr, List(0x00, 0x00, 0x00, 0x00).map(_.toByte))
    hostCd.waitSampling(60) // BufferCC(5)+Delay(5) CDC + settle ⇒ refTime/time advance, the CPU runs

    val tA = dut.riscqArea.time.toBigInt.toInt
    dspCd.waitSampling(50)
    val tB = dut.riscqArea.time.toBigInt.toInt
    assert(tB != tA, s"[M4 time] batch time not advancing after reset release ($tA -> $tB)")
    println(s"[PulseTableSocCpuSim] reset released; batch time advancing ($tA -> $tB) — CPU running")

    // ── wait for the program to publish startTime (proves the CPU's dBus reached the control block) ──
    var guard = 0
    while (dut.riscqArea.riscqCores(0).startTime.toBigInt == 0 && guard < 6000) { dspCd.waitSampling(); guard += 1 }
    val startTime = dut.riscqArea.riscqCores(0).startTime.toBigInt.toInt
    assert(startTime != 0, "[M4 startTime] CPU never wrote startTime — program/dBus path broken")
    println(s"[PulseTableSocCpuSim] CPU programmed startTime=$startTime (after $guard cycles)")

    // ── capture the mapped DAC (dacMap (0,0)->8 ⇒ DAC 8 carries core-0's gate drive) ──
    val dac8      = dut.io.dac(8)
    val gateValid = dut.riscqArea.riscqCores(0).gatePulse.valid
    val stopTime  = startTime + 60
    var dacNonZeroRun = 0; var maxRun = 0; var gateValids = 0; guard = 0
    while (dut.riscqArea.time.toBigInt.toInt < stopTime && guard < 8000) {
      dspCd.waitSampling()
      guard += 1
      if (gateValid.toBoolean) gateValids += 1
      if (dac8.payload.toBigInt != 0) { dacNonZeroRun += 1; maxRun = scala.math.max(maxRun, dacNonZeroRun) }
      else dacNonZeroRun = 0
    }
    println(s"[PulseTableSocCpuSim] gate scheduled@$startTime: gateValids=$gateValids maxDacRun=$maxRun")

    assert(gateValids >= progDur, s"[M4 gate] gate pulse-generator valid run $gateValids < expected dur $progDur")
    assert(maxRun >= progDur, s"[M4 DAC] mapped DAC 8 non-zero run $maxRun < expected pulse dur $progDur")
    println(s"[PulseTableSocCpuSim] PASS: the RISC-V program scheduled a gate pulse that drove DAC 8 " +
      s"(non-zero run $maxRun ≥ dur=$progDur) — CPU-in-the-loop functional sign-off.")
    simSuccess()
  }
}
