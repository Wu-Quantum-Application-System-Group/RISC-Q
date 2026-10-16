package riscq.riscv.sim

import spinal.core.sim._
import spinal.lib.misc.Elf
import spinal.lib.sim.SparseMemory
import rvls.spinal.FileBackend
import riscq.riscv.test.konata
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.regfile.RegFilePlugin
import riscq.riscv.test.RiscqProbe

import java.io.File
import scala.collection.mutable.ArrayBuffer

/**
 * Runs the standard `rv32ui-p-*` RISC-V ELF tests through riscq over Tilelink and checks each
 * one. The `-p` programs boot through a machine-mode startup (handled by
 * [[riscq.riscv.csr.CsrPlugin]]) and signal their result by reaching a global `pass` / `fail` symbol,
 * so [[RiscqProbe]] watches the committed PC against those addresses — no Spike needed.
 *
 * Each run also emits a **Konata** pipeline trace and a pure-Scala **text trace**
 * (`rvls.spinal.FileBackend`) under `simWorkspace/rvtest/<test>/`. Full per-instruction
 * **RVLS/Spike lock-step** runs automatically once the native `ext/rvls/build/apps/rvls.so`
 * has been built (see `ext/rvls`), additionally checking every commit, register and
 * memory access against Spike. Force it with `RISCQ_RVLS=1` / disable with `RISCQ_RVLS=0`.
 *
 * Usage:
 *   mill runMain riscq.riscv.sim.RvTestSim            # the whole rv32ui-p suite
 *   mill runMain riscq.riscv.sim.RvTestSim add sub    # just these
 */
object RvTestSim extends App {
  val testDir = "ext/NaxSoftware/riscv-tests"
  def envFlag(name: String, default: Boolean): Boolean = sys.env.get(name)
    .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(default)
  // RISCQ_WITH_MUL=0 drops the Zmmul multiply unit and its mul tests (withMul now defaults on).
  val withMul = envFlag("RISCQ_WITH_MUL", RiscqParam().withMul)
  // RISCQ_GSHARE_MEM=1 / RISCQ_CSR_WARL=1 exercise the resource-shrink levers (else off).
  val gshareMem = envFlag("RISCQ_GSHARE_MEM", RiscqParam().gshareMem)
  val csrWarl   = envFlag("RISCQ_CSR_WARL", RiscqParam().csrWarl)
  // RISCQ_DERIVE_IMM=0 carries the 32-bit Decode.IMM (baseline); default rebuilds it at regReadAt. Bit-exact.
  val deriveImmFromWord = envFlag("RISCQ_DERIVE_IMM", RiscqParam().deriveImmFromWord)
  // RISCQ_FETCH_PC_WIDTH=N narrows the carried PC to N bits (full addresses rebuilt at the boundaries).
  // In-region programs are bit-identical (RVLS-gated); N must hold the test's code region (here ≤ memWords*4).
  val fetchPcWidth = sys.env.get("RISCQ_FETCH_PC_WIDTH").map(_.toInt).orElse(RiscqParam().fetchPcWidth)
  // RISCQ_ALU_FAST_ADDONLY=1 forwards only the ADD class; SLT/bitwise producers interlock instead.
  val aluFastAddOnly = envFlag("RISCQ_ALU_FAST_ADDONLY", RiscqParam().aluFastAddOnly)
  // RISCQ_LATE_WB=1 moves writeback to regWriteAt (2:1); RVLS retire stays at executeAt via the dbg copy.
  val lateWriteback = envFlag("RISCQ_LATE_WB", RiscqParam().lateWriteback)
  // RISCQ_PCREG_MAXFANOUT=N replicates pcReg (bit-exact attribute).
  val pcRegMaxFanout = sys.env.get("RISCQ_PCREG_MAXFANOUT").map(_.toInt).getOrElse(RiscqParam().pcRegMaxFanout)
  // RISCQ_CSRCE_MAXFANOUT: the cap on the CSR commit qualifier (bit-exact attribute) — here so the RVLS
  // gate can run with it ON.
  val csrCommitMaxFanout = sys.env.get("RISCQ_CSRCE_MAXFANOUT").map(_.toInt).getOrElse(RiscqParam().csrCommitMaxFanout)
  // The baked-in structural levers and the jumpAt / fetch MAX_FANOUT=16 caps are always on
  // — RVLS still gates them here, it just no longer needs an env toggle.
  // RISCQ_BTB_LATE=1 re-steers the BTB target a cycle late (off the PC self-loop; 1-bubble taken).
  val btbPredictLate = envFlag("RISCQ_BTB_LATE", RiscqParam().btbPredictLate)
  // RISCQ_PC_ONEHOT=1 selects the next PC one-hot (off by default = when-chain). Bit-exact.
  val pcOneHotRedirect = envFlag("RISCQ_PC_ONEHOT", RiscqParam().pcOneHotRedirect)
  // RISCQ_LATE_BADTARGET=1 moves the 32-bit BAD_TARGET compare from executeAt to jumpAt. Bit-exact.
  val lateBadTarget = envFlag("RISCQ_LATE_BADTARGET", RiscqParam().lateBadTarget)
  // RISCQ_ALU_NO_FASTFWD=1 drops the srcA fast-forward and interlocks every 1-ahead RAW instead. IPC cost,
  // architecturally identical (the widened interlock covers the dropped forward) — must stay RVLS-exact.
  val aluNoFastForward = envFlag("RISCQ_ALU_NO_FASTFWD", RiscqParam().aluNoFastForward)
  // RISCQ_ALU_RESULT_ONEHOT=1 selects the ALU result one-hot. Zero IPC, bit-exact.
  val aluResultOneHot = envFlag("RISCQ_ALU_RESULT_ONEHOT", RiscqParam().aluResultOneHot)
  // RISCQ_SKID_AFTER=1,3 sets the back-pressure-cut boundaries (skid buffers; transparent ⇒ bit-exact).
  val skidAfter = sys.env.get("RISCQ_SKID_AFTER")
    .map(_.split(",").map(_.trim).filter(_.nonEmpty).map(_.toInt).toSeq).getOrElse(RiscqParam().skidAfter)
  val param    = RiscqParam(withMul = withMul,
    gshareMem = gshareMem, csrWarl = csrWarl, aluFastAddOnly = aluFastAddOnly,
    lateWriteback = lateWriteback, pcRegMaxFanout = pcRegMaxFanout,
    csrCommitMaxFanout = csrCommitMaxFanout,
    btbPredictLate = btbPredictLate, pcOneHotRedirect = pcOneHotRedirect,
    lateBadTarget = lateBadTarget, skidAfterOverride = Some(skidAfter),
    aluNoFastForward = aluNoFastForward, aluResultOneHot = aluResultOneHot,
    deriveImmFromWord = deriveImmFromWord, fetchPcWidth = fetchPcWidth)

  // Base RV32I suite (rv32ui-p-*) plus, when the M extension is enabled, the multiply tests
  // (rv32um-p-*). DIV/REM aren't implemented yet, so they're left out.
  val uiTests = Seq(
    "simple", "add", "addi", "and", "andi", "auipc", "beq", "bge", "bgeu", "blt", "bltu", "bne",
    "jal", "jalr", "lb", "lbu", "lh", "lhu", "lui", "lw", "or", "ori", "sb", "sh", "sll", "slli",
    "slt", "slti", "sltiu", "sltu", "sra", "srai", "srl", "srli", "sub", "sw", "xor", "xori",
    "fence_i"
  )
  val mulTests = if (param.withMul) Seq("mul", "mulh", "mulhsu", "mulhu") else Nil
  val defaultTests = uiTests ++ mulTests
  val tests = if (args.nonEmpty) args.toSeq.map(_.stripPrefix("rv32ui-p-").stripPrefix("rv32um-p-"))
              else defaultTests
  // Spike lock-step needs the native librvls.so; run it whenever that's been built, unless
  // RISCQ_RVLS explicitly forces it on (=1) or off (=0).
  val withRvls = sys.env.get("RISCQ_RVLS") match {
    case Some(v) => v == "1"
    case None    => new File("ext/rvls/build/apps/rvls.so").exists()
  }

  val compiled = SimConfig.withFstWave.compile(Riscq(param.plugins()))
  println(s"[RvTestSim] RVLS/Spike per-instruction lock-step: ${if (withRvls) "ON" else "off"}")

  var passed = 0
  val failures = ArrayBuffer[String]()
  for (name <- tests) {
    // Resolve the suite by trying the I prefix then the M prefix (mul names don't collide with ui).
    val uiElf   = new File(s"$testDir/rv32ui-p-$name")
    val elfFile = if (uiElf.exists()) uiElf else new File(s"$testDir/rv32um-p-$name")
    if (!elfFile.exists()) {
      failures += s"$name (ELF not found at ${elfFile.getPath})"
      println(s"[RvTestSim] SKIP $name (missing)")
    } else {
      try {
        runOne(name, elfFile)
        passed += 1
        println(s"[RvTestSim] PASS ${elfFile.getName}")
      } catch {
        case e: Throwable =>
          failures += s"$name (${e.getMessage})"
          println(s"[RvTestSim] FAIL ${elfFile.getName} : ${e.getMessage}")
      }
    }
  }

  println(s"\n[RvTestSim] ${passed}/${tests.size} passed" +
    (if (failures.isEmpty) " — ALL GOOD" else s"; failures:\n  - ${failures.mkString("\n  - ")}"))
  if (failures.nonEmpty) sys.exit(1)

  def runOne(name: String, elfFile: File): Unit = {
    compiled.doSim(name, seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)

      // Load the ELF into a shared SparseMemory and serve it over Tilelink to iBus + dBus.
      val mem = SparseMemory(seed = 0)
      val elf = new Elf(elfFile, param.xlen)
      elf.load(mem, 0)
      SimMem(dut, mem, 0)

      val workspace = new File(s"simWorkspace/rvtest/$name")
      val kb        = new konata.Backend(new File(workspace, "konata.log"))
      val probe     = new RiscqProbe(dut, param, kb = Some(kb))
      probe.add(new FileBackend(new File(workspace, "trace.log")))
      if (withRvls) {
        val rvlsBackend = new rvls.spinal.RvlsBackend(workspace)
        rvlsBackend.loadElf(0, elf.f)
        probe.add(rvlsBackend)
      }

      // Universal riscv-tests termination: a write of `gp` to the `tohost` address — 1 means
      // pass, any other (odd) value encodes `(testNum << 1) | 1` on failure.
      val mask = 0xFFFFFFFFL
      Option(elf.getELFSymbol("tohost")).map(_.st_value & mask).foreach { tohost =>
        probe.storeCallbacks += { (addr, data) =>
          if (addr == tohost) {
            if (data == 1) delayed(1)(simSuccess())
            else simFailure(s"tohost = $data (test #${data >> 1})")
          }
        }
      }

      // Most `-p` tests also expose `pass`/`fail` symbols; reaching `pass` is an earlier, equally
      // valid success signal (and a clearer `fail` diagnostic with the test number from gp).
      val passSym = Option(elf.getELFSymbol("pass")).map(_.st_value & mask)
      val failSym = Option(elf.getELFSymbol("fail")).map(_.st_value & mask)
      probe.commitsCallbacks += { (_, pc) =>
        if (passSym.contains(pc)) delayed(1)(simSuccess())
        if (failSym.contains(pc)) {
          val testNum = dut.host[RegFilePlugin].logic.regs.getBigInt(3) >> 1 // gp = (num<<1)|1
          simFailure(s"reached 'fail' (test #$testNum)")
        }
      }

      dut.clockDomain.waitSampling(200000)
      simFailure("timeout: never reached pass/fail")
    }
  }
}
