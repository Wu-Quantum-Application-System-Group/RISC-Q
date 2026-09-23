package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.Elf
import spinal.lib.sim.SparseMemory
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.soc.PulseTableSoc
import riscq.soc.link.MailboxSink
import java.io.File

/**
 * '''Cross-core CPU-in-the-loop''' sign-off for the put network (specs/cross-core/02 §6.2, R2): two
 * RISC-V cores run the same program (`sw/xcore.S`) — each publishes a bit to group 0 (core 0 a 1, core 1
 * a 0), core 0 signals core 1's mailbox, both arrive at barrier 0, core 1 waits for the signal, and each
 * fires a gate pulse at `t0 + 1024` **only if the other core's bit is set**, `t0` being the released
 * time. Asserts:
 *
 *   - both cores' release mailboxes carry the SAME time (the value-carrying release);
 *   - core 1's mailbox 0 received 0x77 and was consumed by the halting load;
 *   - core 1's gate pulse rises exactly at its `startTime = t0 + 1024` and reaches DAC 1 (dacMap
 *     (1,0) → 1) for ≥ `dur`; core 0's DAC 8 stays silent (core 1 published 0).
 *
 * Built like [[PulseTableSocCpuSim]]: the image and both gate envelopes are host-loaded over AXI while
 * the cores are held in reset; the per-core slot is a RAM word written the same way (RQ_PARAM style).
 * Run with `mill runMain riscq.soc.sim.XcoreCpuSim`.
 */
object XcoreCpuSim extends App {
  val qubitNum = 2
  val dacMap   = Map((0, 0) -> 8, (0, 1) -> 8, (1, 0) -> 1, (1, 1) -> 1)
  val adcMap   = Map(0 -> 12, 1 -> 13)
  val N = 16; val w = 16; val maskW = BigInt(1) << w
  val gateInterp = 4
  val memOffset = 0x80000000L
  val paramWord = 4095             // the program's `PARAM` = the last word of the 4096-word RAM
  val progDur = 6; val progLead = 1024
  def reK(a: Int, k: Int): BigInt = (if (k == 0) BigInt(a + 1) else BigInt(a * 5 + k * 11 + 17)) & (maskW - 1)
  def imK(a: Int, k: Int): BigInt = BigInt(a * 7 + k * 13 + 9) & (maskW - 1)
  def envWord(a: Int): BigInt = {
    var word = BigInt(0)
    for (k <- 0 until N / gateInterp) { word |= reK(a, k) << (2 * k * w); word |= imK(a, k) << ((2 * k + 1) * w) }
    word
  }
  val elfFile = new File("src/riscq/soc/sim/sw/xcore.elf")
  require(elfFile.exists(), s"missing ${elfFile.getPath} — rebuild it per the header of sw/xcore.S")

  SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN").addSimulatorFlag("--x-initial 0")
    .compile {
      val dut = PulseTableSoc(qubitNum, dacMap, adcMap, withTest = false)
      dut.riscqArea.time.simPublic()
      for (c <- dut.riscqArea.riscqCores) {
        c.startTime.simPublic()
        c.gatePulse.valid.simPublic()
        c.riscvSoc.posted.sinkAreas.collect { case m: MailboxSink => m.data.simPublic(); m.full.simPublic() }
      }
      dut
    }.doSim("xcoreCpu", seed = 42) { dut =>
    val hostCd = dut.clockDomain
    val dspCd  = dut.dspCd
    dut.io.axi.ar.valid #= false; dut.io.axi.aw.valid #= false; dut.io.axi.w.valid #= false
    dut.io.axi.r.ready #= false;  dut.io.axi.b.ready #= false
    for (i <- 0 until dut.io.adc.length) { dut.io.adc(i).valid #= true; dut.io.adc(i).payload #= 0 }
    hostCd.forkStimulus(10)
    dspCd.forkStimulus(10)
    val image = SparseMemory(seed = 0)
    new Elf(elfFile, 32).load(image, 0)
    hostCd.waitSampling(40)
    val axi = Axi4Master(dut.io.axi, hostCd)
    def leBytes(v: BigInt, n: Int): List[Byte] = List.tabulate(n)(i => ((v >> (8 * i)) & 0xFF).toByte)
    def loadWord(core: Int, word: Int, v: BigInt): Unit =
      axi.write(BigInt(dut.map.coreMemOffset(core)) + word.toLong * 4, leBytes(v, 4))
    val gateEnvBytes = (N / gateInterp) * 2 * w / 8
    def loadEnv(core: Int, a: Int, word: BigInt): Unit = {
      val wordAddr = BigInt(dut.map.envOffset(core, 0)) + a.toLong * gateEnvBytes
      for (lane <- 0 until gateEnvBytes / 4) axi.write(wordAddr + lane * 4, leBytes(word >> (lane * 32), 4))
    }
    for (core <- 0 until qubitNum) {
      for (i <- 0 until 256) loadWord(core, i, BigInt(image.readInt(memOffset + 4L * i).toLong & 0xFFFFFFFFL))
      loadWord(core, paramWord, core)                     // my slot / core index
      for (a <- 0 until 64) loadEnv(core, a, envWord(a))
    }
    val hostCtrlAddr = BigInt(dut.map.hostCtrlBase)
    axi.write(hostCtrlAddr, List(0x01, 0x00, 0x00, 0x00).map(_.toByte))
    hostCd.waitSampling(20)
    axi.write(hostCtrlAddr, List(0x00, 0x00, 0x00, 0x00).map(_.toByte))
    hostCd.waitSampling(60)

    // ── both programs publish, rendezvous and schedule: wait for core 1's startTime ──
    val cores = dut.riscqArea.riscqCores
    var guard = 0
    while (cores(1).startTime.toBigInt == 0 && guard < 20000) { dspCd.waitSampling(); guard += 1 }
    val st1 = cores(1).startTime.toBigInt.toLong
    assert(st1 != 0, "[xcore] core 1 never scheduled — barrier release or signal never arrived")
    dspCd.waitSampling(50)
    val st0 = cores(0).startTime.toBigInt.toLong
    val mailboxes = cores.map(_.riscvSoc.posted.sinkAreas.collect { case m: MailboxSink => m })
    val rel0 = mailboxes(0)(0).data.toBigInt.toLong   // sink order: release, mbox0, mbox1
    val rel1 = mailboxes(1)(0).data.toBigInt.toLong
    println(s"[XcoreCpuSim] release t0: core0=$rel0 core1=$rel1; startTime core0=$st0 core1=$st1 (after $guard cycles)")
    assert(rel0 == rel1 && rel0 != 0, s"[xcore release] the two cores read different release times: $rel0 vs $rel1")
    assert(st1 == rel1 + progLead && st0 == rel0 + progLead, "[xcore startTime] startTime must be t0 + 1024 on both cores")
    assert(mailboxes(1)(1).data.toBigInt == 0x77 && !mailboxes(1)(1).full.toBoolean,
      s"[xcore signal] core 1 mailbox 0: data=${mailboxes(1)(1).data.toBigInt} full=${mailboxes(1)(1).full.toBoolean}")
    assert(mailboxes(0)(1).data.toBigInt == 0, "[xcore signal] core 0's mailbox must be untouched (unicast)")

    // ── the pulses: core 1 rises exactly at st1 on DAC 1; core 0 never fires (DAC 8 silent) ──
    val dac1 = dut.io.dac(1); val dac8 = dut.io.dac(8)
    var firstRise = -1L; var run1 = 0; var max1 = 0; var max8 = 0; var run8 = 0; guard = 0
    while (dut.riscqArea.time.toBigInt.toLong < st1 + 60 && guard < 20000) {
      dspCd.waitSampling(); guard += 1
      val now = dut.riscqArea.time.toBigInt.toLong
      if (dac1.payload.toBigInt != 0) { if (firstRise < 0) firstRise = now; run1 += 1; max1 = scala.math.max(max1, run1) } else run1 = 0
      if (dac8.payload.toBigInt != 0) { run8 += 1; max8 = scala.math.max(max8, run8) } else run8 = 0
    }
    println(s"[XcoreCpuSim] DAC 1 first rise at batch $firstRise (startTime $st1), max run $max1; DAC 8 max run $max8")
    assert(max1 >= progDur, s"[xcore DAC1] core 1's conditional pulse missing (run $max1 < $progDur)")
    assert(max8 == 0, s"[xcore DAC8] core 0 fired although core 1 published 0 (run $max8)")
    assert(firstRise >= st1 && firstRise <= st1 + 8, s"[xcore rise] DAC 1 rose at $firstRise, expected at startTime $st1 (+ converter pipe)")
    println(s"[XcoreCpuSim] PASS: publish → signal → barrier (same t0 on both cores) → conditional fire on the other " +
      s"core's bit, scheduled from t0: DAC 1 rose at $firstRise = t0 + $progLead, DAC 8 silent.")
    simSuccess()
  }
}
