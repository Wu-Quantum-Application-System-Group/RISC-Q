package riscq.soc.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.Elf
import spinal.lib.misc.plugin.Hostable
import spinal.lib.sim.SparseMemory
import rvls.spinal.FileBackend
import riscq.riscv.test.konata
import riscq.memory.DualClockRam
import riscq.soc.fabric.{RiscqFiber, TileLinkCpuMemFiber}
import riscq.riscv.RiscqParam
import riscq.riscv.regfile.RegFilePlugin
import riscq.riscv.test.RiscqProbe

import java.io.File
import scala.collection.mutable.ArrayBuffer

/**
 * Testbench DUT: a riscq core on the `tilelink.fabric`, its instruction/data busses decoding to a
 * single shared [[DualClockRam]] (slow port ← `iBus`, fast port ← `dBus`) mapped at `0x80000000`.
 * Everything sits in one clock domain (no CDC) — this sim only proves the bridge + fiber + decode.
 */
case class RamOnFabricDut(plugins: Seq[Hostable], memDepth: Int, memOutReg: Boolean) extends Component {
  val memOffset = 0x80000000L

  val riscqFiber = RiscqFiber(plugins)
  val ram = DualClockRam(
    width          = 32,
    depth          = memDepth,
    slowCd         = ClockDomain.current,
    fastCd         = ClockDomain.current,
    withOutRegFast = memOutReg,
    withOutRegSlow = memOutReg
  )

  val iBusFiber = TileLinkCpuMemFiber(ram.slowPort, latency = 1 + memOutReg.toInt)
  iBusFiber.up at memOffset of riscqFiber.iBus

  // The posted-store adapter (writes acked locally, reads pass through) rides inside RiscqFiber on the
  // DataMemBus, exercising the full rv32ui-p suite (incl. store→load RAW) through the shim under RVLS
  // lock-step, with the verified LsuPlugin untouched.
  val dBusFiber = TileLinkCpuMemFiber(ram.fastPort, latency = 1 + memOutReg.toInt)
  dBusFiber.up at memOffset of riscqFiber.dBus
}

/**
 * Sign-off: run the `rv32ui-p-*` ELF tests through the riscq core **wired onto the `tilelink.fabric`** —
 * `RiscqFiber.iBus`/`dBus` decode to a shared [[DualClockRam]] via [[TileLinkCpuMemFiber]] — and
 * check each one exactly as `RvTestSim` does. This proves the core→fabric bridge, the ported memory
 * fiber, and the fabric address decode all together, end to end, with the real RTL memory in the loop
 * (preloaded by backdoor).
 *
 * Usage:
 *   mill runMain riscq.soc.sim.RamOnFabricSim            # the default subset
 *   mill runMain riscq.soc.sim.RamOnFabricSim add lw sw  # just these
 */
object RamOnFabricSim extends App {
  val testDir   = "ext/NaxSoftware/riscv-tests"
  val memBase   = 0x80000000L
  val memDepth  = 4096        // 16 KiB window: covers .text.init @0x80000000 and .tohost @0x80001000
  val memOutReg = true

  // RISCQ_ALU_NO_FASTFWD / RISCQ_ALU_RESULT_ONEHOT exercise the drop-srcA-forward / one-hot-ALU-result
  // fmax levers through the fabric-wired core under RVLS lock-step. Default = the landed recipe.
  def env(name: String, d: Boolean) = sys.env.get(name).map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(d)
  val param = RiscqParam(
    aluNoFastForward = env("RISCQ_ALU_NO_FASTFWD", RiscqParam().aluNoFastForward),
    aluResultOneHot  = env("RISCQ_ALU_RESULT_ONEHOT", RiscqParam().aluResultOneHot))

  // A representative subset exercising fetch / ALU / branch / jump / load / store over the fabric;
  // pass test names as args to run any others.
  val defaultTests = Seq(
    "simple", "add", "addi", "and", "or", "sll", "sra", "slt",
    "lb", "lbu", "lh", "lhu", "lw", "sb", "sh", "sw",
    "beq", "bne", "blt", "bgeu", "jal", "jalr", "auipc", "lui", "fence_i"
  )
  val tests = if (args.nonEmpty) args.toSeq.map(_.stripPrefix("rv32ui-p-")) else defaultTests

  val withRvls = sys.env.get("RISCQ_RVLS") match {
    case Some(v) => v == "1"
    case None    => new File("ext/rvls/build/apps/rvls.so").exists()
  }

  // The clock-crossing Mem is one array with two clocked ports (iBus slowPort + dBus fastPort) — a
  // true dual-port RAM, which Verilator flags MULTIDRIVEN; intentional here, so silence that check.
  val compiled = SimConfig.withFstWave
    .addSimulatorFlag("-Wno-MULTIDRIVEN")
    .compile(RamOnFabricDut(param.plugins(), memDepth, memOutReg))
  println(s"[RamOnFabricSim] RVLS/Spike per-instruction lock-step: ${if (withRvls) "ON" else "off"}")

  var passed = 0
  val failures = ArrayBuffer[String]()
  for (name <- tests) {
    val uiElf   = new File(s"$testDir/rv32ui-p-$name")
    val elfFile = if (uiElf.exists()) uiElf else new File(s"$testDir/rv32um-p-$name")
    if (!elfFile.exists()) {
      failures += s"$name (ELF not found at ${elfFile.getPath})"
      println(s"[RamOnFabricSim] SKIP $name (missing)")
    } else {
      try {
        runOne(name, elfFile)
        passed += 1
        println(s"[RamOnFabricSim] PASS ${elfFile.getName}")
      } catch {
        case e: Throwable =>
          failures += s"$name (${e.getMessage})"
          println(s"[RamOnFabricSim] FAIL ${elfFile.getName} : ${e.getMessage}")
      }
    }
  }

  println(s"\n[RamOnFabricSim] ${passed}/${tests.size} passed" +
    (if (failures.isEmpty) " — ALL GOOD" else s"; failures:\n  - ${failures.mkString("\n  - ")}"))
  if (failures.nonEmpty) sys.exit(1)

  def runOne(name: String, elfFile: File): Unit = {
    compiled.doSim(name, seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)

      // Load the ELF into a software image, then backdoor-preload the RTL RAM word-for-word over the
      // [memBase, memBase + memDepth*4) window. (The probe's pass/fail + tohost detection reads the
      // core's whitebox signals, so it is independent of the memory backend.)
      val image = SparseMemory(seed = 0)
      val elf   = new Elf(elfFile, param.xlen)
      elf.load(image, 0)
      for (i <- 0 until memDepth) {
        val word = image.readInt(memBase + 4L * i).toLong & 0xFFFFFFFFL
        dut.ram.mem.setBigInt(i, BigInt(word))
      }

      val workspace = new File(s"simWorkspace/ramOnFabric/$name")
      val kb        = new konata.Backend(new File(workspace, "konata.log"))
      val probe     = new RiscqProbe(dut.riscqFiber.riscq, param, kb = Some(kb))
      probe.add(new FileBackend(new File(workspace, "trace.log")))
      if (withRvls) {
        val rvlsBackend = new rvls.spinal.RvlsBackend(workspace)
        rvlsBackend.loadElf(0, elf.f)
        probe.add(rvlsBackend)
      }

      val mask = 0xFFFFFFFFL
      Option(elf.getELFSymbol("tohost")).map(_.st_value & mask).foreach { tohost =>
        probe.storeCallbacks += { (addr, data) =>
          if (addr == tohost) {
            if (data == 1) delayed(1)(simSuccess())
            else simFailure(s"tohost = $data (test #${data >> 1})")
          }
        }
      }

      val passSym = Option(elf.getELFSymbol("pass")).map(_.st_value & mask)
      val failSym = Option(elf.getELFSymbol("fail")).map(_.st_value & mask)
      probe.commitsCallbacks += { (_, pc) =>
        if (passSym.contains(pc)) delayed(1)(simSuccess())
        if (failSym.contains(pc)) {
          val testNum = dut.riscqFiber.riscq.host[RegFilePlugin].logic.regs.getBigInt(3) >> 1
          simFailure(s"reached 'fail' (test #$testNum)")
        }
      }

      dut.clockDomain.waitSampling(200000)
      simFailure("timeout: never reached pass/fail")
    }
  }
}
