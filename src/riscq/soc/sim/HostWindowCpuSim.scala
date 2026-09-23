package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.Elf
import spinal.lib.sim.SparseMemory
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.soc.{PulseTableSoc, RiscvSoc}

import java.io.File

/**
 * W1 SoC gate for the host window (specs/software/22 §4): the **RISC-V cores** — not a test harness —
 * write results into their windows and the beats must appear in the PS DDR4 model at
 * `base + (core << 24) + offset`.
 *
 * Both cores run `sw/host_window.S` (→ `host_window.elf`). The testbench writes a per-core tag word
 * (`core << 16`) into each core's data RAM, programs `HOSTWIN_BASE_LO/HI` (with `enable`) over the host
 * control block while `riscqReset` is held, then releases the cores. Each core then stores a 1024-word
 * ramp `tag + i`, one `sb`, one `sh` and an ordering sentinel into `0x4000_0000`, then sets its
 * hardware `DONE` register (specs/software/23) as its very last store.
 *
 * Completion is taken from that register — the testbench polls the ONE host-control `DONE` word
 * (`hostCtrl + 0x50`, bit per core) over the real AXI path, exactly as `riscq.run.poll_done` does,
 * instead of counting beats or spinning on the in-window sentinel. The sentinel stays as the
 * ordering witness for the posted window stores ([22 §2.4](../../../../specs/software/22-host-window.md)).
 *
 * What this adds over [[HostWindowFunnelSim]] — which drove the bridges with TileLink `MasterAgent`s —
 * is the whole real path: `LsuPlugin` → `PostedStoreShim` → the fabric decode at `0x4000_0000` → the
 * bridge → the per-core CC FIFO (dsp → host) → the shared funnel → `io.hostMem`. In particular the
 * `sb`/`sh` beats prove the LSU's byte mask survives all the way to `wstrb`.
 *
 * Run with `mill runMain riscq.soc.sim.HostWindowCpuSim`.
 */
object HostWindowCpuSim extends App {
  val qubitNum = 2
  val dacMap   = Map((0, 0) -> 8, (0, 1) -> 8, (1, 0) -> 1, (1, 1) -> 1)
  val adcMap   = Map(0 -> 12, 1 -> 13)
  val memOffset = 0x80000000L

  val offsetWidth = 24
  val base        = BigInt("840000000", 16)   // a 40-bit PS physical address (the high DDR region)
  val rampLen     = 1024
  val tagWord     = 1023                      // sw/host_window.S reads the tag from 0x80000FFC

  // the program's non-ramp stores (window offset → expected (data, strb)); see sw/host_window.S.
  val sbBeat  = (0x1001 - 1, BigInt(0xA5) << 8, 0x2)             // sb  0xA5 into byte lane 1 of 0x1000
  val shBeat  = (0x1004, BigInt(0x1234) << 16, 0xC)              // sh  0x1234 into the upper half of 0x1004
  val doneBeat = (0x2000, BigInt(0xDA7A), 0xF)                   // the last beat of each core

  val elfFile = new File("src/riscq/soc/sim/sw/host_window.elf")
  require(elfFile.exists(), s"missing ${elfFile.getPath} — rebuild it per the header of sw/host_window.S")

  SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")  // the clock-crossing Bram blackbox arrays are written from clka+clkb
    .addSimulatorFlag("--x-initial 0")            // 0-init pre-reset X state (the host→dsp CDC FIFO) — as PulseTableSocSim
    .compile(PulseTableSoc(qubitNum, dacMap, adcMap, withTest = false))
    .doSim("hostWindowCpu", seed = 42) { dut =>

    val hostCd = dut.clockDomain
    val dspCd  = dut.dspCd

    dut.io.axi.ar.valid #= false; dut.io.axi.aw.valid #= false; dut.io.axi.w.valid #= false
    dut.io.axi.r.ready #= false;  dut.io.axi.b.ready #= false
    for (i <- 0 until dut.io.adc.length) { dut.io.adc(i).valid #= true; dut.io.adc(i).payload #= 0 }

    hostCd.forkStimulus(10)
    dspCd.forkStimulus(10)

    val host = HostMemModel(dut.io.hostMem, hostCd)

    val image = SparseMemory(seed = 0)
    new Elf(elfFile, 32).load(image, 0)

    hostCd.waitSampling(40) // io.dspRst (forkStimulus) deasserts
    val axi = Axi4Master(dut.io.axi, hostCd)
    val hostCtrl = BigInt(dut.map.hostCtrlBase)
    def leBytes(v: BigInt, n: Int): List[Byte] = List.tabulate(n)(i => ((v >> (8 * i)) & 0xFF).toByte)
    def loadWord(core: Int, word: Int, v: BigInt): Unit =
      axi.write(BigInt(dut.map.coreMemOffset(core)) + word.toLong * 4, leBytes(v, 4))

    // Hold the cores BEFORE loading the image. `riscqResetHostCd` powers up DEASSERTED, so the cores are
    // already fetching — all-zero words whose PC wraps the 16 KB RAM — and the moment the program lands
    // in RAM the wrapped PC would run it early, against `enable = 0`, parking a stale partial run in the
    // CC FIFOs. The real `setup` asserts reset first for exactly this reason.
    axi.write(hostCtrl, leBytes(1, 4))                                        // riscqReset = 1

    // program image into both cores, then each core's tag (the program is identical; only the tag differs).
    for (core <- 0 until qubitNum) {
      for (i <- 0 until 64) loadWord(core, i, BigInt(image.readInt(memOffset + 4L * i).toLong & 0xFFFFFFFFL))
      loadWord(core, tagWord, BigInt(core) << 16)
    }

    // ── window base + enable, programmed while reset is held (spec §2.3: no torn 40-bit base) ──
    axi.write(hostCtrl + 72, leBytes(base & 0xFFFFFFFFL, 4))                  // HOSTWIN_BASE_LO
    axi.write(hostCtrl + 76, leBytes((base >> 32) | (BigInt(1) << 31), 4))    // HOSTWIN_BASE_HI + enable
    hostCd.waitSampling(200)
    host.clear()   // discard anything the pre-reset power-up fetch left in the FIFOs
    axi.write(hostCtrl, leBytes(0, 4))                                        // riscqReset = 0 — cores run
    hostCd.waitSampling(60)

    // ── completion: poll the hardware DONE word, one read for every core (specs/software/23) ──
    def doneWord(): Int = axi.read(hostCtrl + 0x50, 4).zipWithIndex
      .map { case (b, i) => (b & 0xFF) << (8 * i) }.sum
    val allDone = (1 << qubitNum) - 1
    assert(doneWord() == 0, s"DONE word should be 0 while the cores run, read ${doneWord()}")

    var guard = 0
    while (doneWord() != allDone && guard < 4000) { hostCd.waitSampling(20); guard += 1 }
    assert(doneWord() == allDone, f"cores not DONE: word 0x${doneWord()}%x, want 0x$allDone%x (guard $guard)")

    // The window stores are posted BEHIND the DONE store, so let them drain — the §2.4 ordering rule
    // (the host reaches the buffer through Python, decades of cycles later). 200 host cycles here.
    hostCd.waitSampling(200)
    val perCore = rampLen + 3
    assert(host.writes.size == qubitNum * perCore,
      s"expected ${qubitNum * perCore} AXI beats, saw ${host.writes.size} (guard $guard)")

    // ── each core's slice holds its own ramp, in order, then sb / sh / DONE ──
    for (core <- 0 until qubitNum) {
      val beats = host.beatsFor(base, core, offsetWidth)
      val tag   = BigInt(core) << 16
      assert(beats.size == perCore, s"core $core: ${beats.size} beats, expected $perCore")
      for (i <- 0 until rampLen) {
        val want = (4 * i, tag + i, 0xF)
        assert(beats(i) == want, s"core $core ramp beat $i: got ${beats(i)} want $want")
      }
      assert(beats(rampLen) == sbBeat,       s"core $core sb beat: got ${beats(rampLen)} want $sbBeat")
      assert(beats(rampLen + 1) == shBeat,   s"core $core sh beat: got ${beats(rampLen + 1)} want $shBeat")
      assert(beats(rampLen + 2) == doneBeat, s"core $core DONE beat: got ${beats(rampLen + 2)} want $doneBeat")
    }

    // ── byte-exact golden memory over the whole buffer ──
    val golden = scala.collection.mutable.Map[BigInt, Int]()
    def put(core: Int, off: Int, data: BigInt, strb: Int): Unit = {
      val addr = base + (BigInt(core) << offsetWidth) + off
      for (b <- 0 until 4 if ((strb >> b) & 1) == 1) golden(addr + b) = ((data >> (8 * b)) & 0xFF).toInt
    }
    for (core <- 0 until qubitNum) {
      for (i <- 0 until rampLen) put(core, 4 * i, (BigInt(core) << 16) + i, 0xF)
      List(sbBeat, shBeat, doneBeat).foreach { case (off, d, s) => put(core, off, d, s) }
    }
    assert(host.mem == golden,
      s"golden memory mismatch: ${(host.mem.toSet diff golden.toSet).take(8)} unexpected, " +
        s"${(golden.toSet diff host.mem.toSet).take(8)} missing")

    // ── the DONE word is cleared by the run boundary, so the next run's poll cannot see a stale bit ──
    axi.write(hostCtrl, leBytes(1, 4))                                        // riscqReset = 1
    hostCd.waitSampling(50)
    assert(doneWord() == 0, f"DONE word 0x${doneWord()}%x did not clear when riscqReset was asserted")

    println(f"[HostWindowCpuSim] PASS  $qubitNum cores × $perCore CPU stores → ${host.writes.size} AXI beats " +
      f"at base 0x$base%010x (window 0x${RiscvSoc.hostWinBase}%08x, slice ${1 << (offsetWidth - 20)} MB); " +
      f"ramps, sb/sh strobes and per-core order all exact; hardware DONE word 0x$allDone%x polled then " +
      "cleared by reset.")
    simSuccess()
  }
}
