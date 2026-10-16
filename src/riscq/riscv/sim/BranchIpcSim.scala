package riscq.riscv.sim

import spinal.core.sim._
import spinal.lib.misc.plugin.Hostable
import riscq.riscv.{Riscq, RiscqParam}
import riscq.riscv.execute.{BranchPlugin, LsuPlugin}
import riscq.riscv.fetch.FetchPlugin
import riscq.riscv.regfile.RegFilePlugin

import scala.collection.mutable

/**
 * Branch-prediction IPC testbench — two scenarios over a fixed-latency (L = 1),
 * always-accepting Tilelink instruction memory (so the *only* bubbles are pipeline redirects;
 * straight-line fetch is already IPC = 1). Each program is run on several cores built from
 * `RiscqParam.plugins()` (BTB + GShare), `pluginsBtbOnly()` (BTB, always-taken) and
 * `pluginsNoPredict()` (no predictor), and we read the execute/fetch whiteboxes for committed
 * PCs, cycles, and branch mispredictions (`BranchPlugin.dbgMispredict`).
 *
 * 1. **BTB win (loop with a backward branch + an in-loop jump).** `plugins()` vs the
 *    no-predict baseline: identical committed-PC stream + register file (prediction is
 *    architecturally invisible) and a large IPC improvement.
 * 2. **GShare win (a conditional branch taken only 1/8 of the time).** `plugins()` vs
 *    `pluginsBtbOnly()`: an always-taken BTB mispredicts the 7/8 fall-throughs, while GShare's
 *    counters learn the bias — far fewer mispredictions, same architectural result.
 *
 * Run with `mill runMain riscq.riscv.sim.BranchIpcSim`.
 */
object BranchIpcSim extends App {
  // RISCQ_GSHARE_MEM=1 runs the IPC scenarios on the Mem-based GShare.
  val gshareMem = sys.env.get("RISCQ_GSHARE_MEM")
    .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(RiscqParam().gshareMem)
  // RISCQ_ALU_FAST_ADDONLY=1 measures the IPC cost of forwarding only the ADD class (SLT/bitwise
  // producers interlock instead of forwarding).
  val aluFastAddOnly = sys.env.get("RISCQ_ALU_FAST_ADDONLY")
    .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(RiscqParam().aluFastAddOnly)
  // RISCQ_BTB_LATE=1 measures the IPC cost of re-steering a predicted-taken transfer one cycle late
  // (0 → 1 bubble).
  val btbPredictLate = sys.env.get("RISCQ_BTB_LATE")
    .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(RiscqParam().btbPredictLate)
  // RISCQ_LATE_BADTARGET=1 prices the jumpAt-side target compare (expected 0%: the decision still
  // acts at jumpAt in the same cycle — cycle-identical by design).
  val lateBadTarget = sys.env.get("RISCQ_LATE_BADTARGET")
    .map(s => s == "1" || s.equalsIgnoreCase("true")).getOrElse(RiscqParam().lateBadTarget)
  // RISCQ_SKID_AFTER=1,3 sets the skid-buffer boundaries (transparent ⇒ IPC-neutral; here to confirm it).
  val skidAfter = sys.env.get("RISCQ_SKID_AFTER")
    .map(_.split(",").map(_.trim).filter(_.nonEmpty).map(_.toInt).toSeq).getOrElse(RiscqParam().skidAfter)
  // RISCQ_FETCH_PC_WIDTH=N narrows the carried PC (IPC-neutral: same control logic, BTB tag keeps its
  // in-region discriminating power). The 4KB region (memWords 1<<10) fits N=12.
  val fetchPcWidth = sys.env.get("RISCQ_FETCH_PC_WIDTH").map(_.toInt).orElse(RiscqParam().fetchPcWidth)
  val param = RiscqParam(memWords = 1 << 10, gshareMem = gshareMem,
    aluFastAddOnly = aluFastAddOnly, btbPredictLate = btbPredictLate, lateBadTarget = lateBadTarget,
    skidAfterOverride = Some(skidAfter), fetchPcWidth = fetchPcWidth)
  val base  = param.resetVector.toLong
  val L     = 1 // fixed instruction-memory latency (hidden by the multi-outstanding fetch)

  // ---- Minimal instruction encoders (mirror ExecuteSim) ----
  def i(imm: Int, rs1: Int, f3: Int, rd: Int, op: Int): BigInt =
    ((BigInt(imm) & 0xfff) << 20) | (BigInt(rs1) << 15) | (BigInt(f3) << 12) | (BigInt(rd) << 7) | BigInt(op)
  def b(imm: Int, rs2: Int, rs1: Int, f3: Int): BigInt = {
    val im = BigInt(imm) & 0x1fff
    (((im >> 12) & 1) << 31) | (((im >> 5) & 0x3f) << 25) | (BigInt(rs2) << 20) |
      (BigInt(rs1) << 15) | (BigInt(f3) << 12) | (((im >> 1) & 0xf) << 8) | (((im >> 11) & 1) << 7) | BigInt(0x63)
  }
  def jal(imm: Int, rd: Int): BigInt = {
    val im = BigInt(imm) & 0x1fffff
    (((im >> 20) & 1) << 31) | (((im >> 1) & 0x3ff) << 21) | (((im >> 11) & 1) << 20) |
      (((im >> 12) & 0xff) << 12) | (BigInt(rd) << 7) | BigInt(0x6f)
  }
  def ADDI(rd: Int, rs1: Int, imm: Int) = i(imm, rs1, 0x0, rd, 0x13)
  def ANDI(rd: Int, rs1: Int, imm: Int) = i(imm, rs1, 0x7, rd, 0x13)
  def BEQ(rs1: Int, rs2: Int, imm: Int) = b(imm, rs2, rs1, 0x0)
  def BNE(rs1: Int, rs2: Int, imm: Int) = b(imm, rs2, rs1, 0x1)
  def JAL(rd: Int, imm: Int)            = jal(imm, rd)
  val ECALL                             = BigInt(0x00000073L)

  def buildImage(program: Seq[BigInt]): Array[BigInt] =
    Array.tabulate(param.memWords)(idx => if (idx < program.length) program(idx) else ADDI(0, 0, 0))

  // Scenario 1: a backward conditional branch + an in-loop unconditional jump (idx 4 is poison
  // the jump must always skip). Loop body that retires is idx {2,3,5,6}.
  val loopN = 50
  val loopImage = buildImage(Seq(
    /*0*/ ADDI(1, 0, loopN), /*1*/ ADDI(2, 0, 0),
    /*2*/ ADDI(2, 2, 1),     /*3*/ JAL(0, 8),       /*4*/ ADDI(9, 9, 100),
    /*5*/ ADDI(1, 1, -1),    /*6*/ BNE(1, 0, -16),  /*7*/ ECALL
  ))

  // Scenario 2: BEQ@2 is taken only when (i & 7) == 0, i.e. 1/8 of iterations (not-taken-biased).
  val biasN = 64
  val biasImage = buildImage(Seq(
    /*0*/ ADDI(1, 0, biasN),
    /*1*/ ANDI(3, 1, 7),     /*2*/ BEQ(3, 0, 8),    /*3*/ ADDI(4, 4, 1),
    /*4*/ ADDI(1, 1, -1),    /*5*/ BNE(1, 0, -16),  /*6*/ ECALL
  ))

  case class Run(commits: Int, cycles: Int, mispredicts: Int, pcs: Seq[Long], regs: IndexedSeq[BigInt])

  // `plugins` is by-name: it must be instantiated *inside* SimConfig.compile's elaboration,
  // never at the call site (else GlobalData is null).
  def run(image: Array[BigInt], plugins: => Seq[Hostable], label: String): Run = {
    def wordAt(addr: Long): BigInt = {
      val k = (addr - base) / 4
      if (k >= 0 && k < image.length) image(k.toInt) else ADDI(0, 0, 0) // NOP outside the image
    }
    var out: Run = null
    SimConfig.compile(Riscq(plugins)).doSim(label, seed = 42) { dut =>
      val iBus  = dut.host[FetchPlugin].logic.iBus
      val dBus  = dut.host[LsuPlugin].logic.dBus
      val dbg   = dut.host[RegFilePlugin].logic.exec
      val regs  = dut.host[RegFilePlugin].logic.regs
      val brDbg = dut.host[BranchPlugin].logic.act // dbgMispredict now lives where the decision acts (jumpAt)

      // The programs have no loads/stores, so keep the data bus idle.
      dBus.cmd.ready #= false
      dBus.rsp.valid #= false

      // Fixed-latency, always-accepting instruction memory (see FetchIpcSim).
      iBus.a.ready #= true
      iBus.d.valid #= false
      val pend = mutable.Queue[(Long, BigInt, BigInt)]() // (dueEdge, source, data)
      var t    = 0L
      dut.clockDomain.onSamplings {
        if (iBus.a.valid.toBoolean && iBus.a.ready.toBoolean)
          pend.enqueue((t + L, iBus.a.source.toBigInt, wordAt(iBus.a.address.toLong)))
        if (iBus.d.valid.toBoolean && iBus.d.ready.toBoolean) pend.dequeue()
        iBus.d.valid #= false
        if (pend.nonEmpty && pend.front._1 == t + 1) {
          iBus.d.valid  #= true
          iBus.d.source #= pend.front._2
          iBus.d.data   #= pend.front._3
        }
        t += 1
      }

      dut.clockDomain.forkStimulus(10)

      val pcs       = mutable.ArrayBuffer[Long]()
      var mispred   = 0
      var done      = false
      var cycle     = 0
      while (!done && cycle < 6000) {
        dut.clockDomain.waitSampling()
        cycle += 1
        if (brDbg.dbgMispredict.toBoolean) mispred += 1
        if (dbg.dbgFiring.toBoolean) {
          pcs += dbg.dbgPc.toBigInt.toLong
          if (dbg.dbgInstr.toBigInt == ECALL) done = true
        }
      }
      assert(done, s"[$label] never retired ECALL ($cycle cycles, ${pcs.length} commits)")
      out = Run(pcs.length, cycle, mispred, pcs.toSeq, IndexedSeq.tabulate(32)(idx => regs.getBigInt(idx)))
      simSuccess()
    }
    out
  }

  // ============ Scenario 1: BTB IPC win on a branch+jump-heavy loop ============
  val full1 = run(loopImage, param.plugins(), "loop-full")
  val base1 = run(loopImage, param.pluginsNoPredict(), "loop-baseline")

  assert(full1.pcs == base1.pcs, "loop: committed-PC streams differ (prediction changed behaviour)")
  assert(full1.regs == base1.regs, "loop: final register files differ")
  assert(full1.regs(9) == 0, s"loop: poison x9 executed (${full1.regs(9)})") // the JAL skipped it
  assert(full1.regs(2) == loopN && full1.regs(1) == 0, "loop: wrong accumulator/counter")
  val ipcFull = full1.commits.toDouble / full1.cycles
  val ipcBase = base1.commits.toDouble / base1.cycles
  assert(full1.cycles < base1.cycles, "loop: prediction did not reduce cycles")
  // Baseline folds the predicted target into the same cycle (0 bubbles ⇒ IPC≈1). With btbPredictLate
  // the target re-steers a cycle late, so every predicted-taken transfer in this tight loop costs 1
  // bubble — IPC is lower but still a clear win over no-prediction (it avoids the full multi-cycle
  // mispredict). This loop is the worst case (one taken transfer per tiny iteration); larger basic
  // blocks amortize the bubble.
  val ipcMin = if (param.btbPredictLate) 0.55 else 0.9
  assert(ipcFull > ipcMin, f"loop: BTB IPC $ipcFull%.3f below expected $ipcMin%.2f (btbLate=${param.btbPredictLate})")
  assert(ipcFull > ipcBase * 1.4, f"loop: BTB IPC $ipcFull%.3f not a clear win over baseline $ipcBase%.3f")

  // ============ Scenario 2: GShare direction win on a 1/8-taken branch ============
  val full2 = run(biasImage, param.plugins(), "bias-full")
  val btb2  = run(biasImage, param.pluginsBtbOnly(), "bias-btbOnly")

  assert(full2.pcs == btb2.pcs, "bias: committed-PC streams differ between GShare and BTB-only")
  assert(full2.regs == btb2.regs, "bias: final register files differ")
  // x4 counts the 7/8 fall-throughs (i = biasN..1, taken when i&7==0).
  val notTaken = (1 to biasN).count(idx => (idx & 7) != 0)
  assert(full2.regs(4) == notTaken, s"bias: x4 = ${full2.regs(4)} != $notTaken fall-throughs")
  assert(full2.mispredicts < btb2.mispredicts,
    s"bias: GShare (${full2.mispredicts} mispred) did not beat always-taken BTB (${btb2.mispredicts})")
  assert(full2.mispredicts * 2 < btb2.mispredicts,
    f"bias: GShare ${full2.mispredicts} not a clear win over always-taken ${btb2.mispredicts}")

  // ============ Scenario 3 (baked in): a poison load in a mispredict shadow is safe ============
  // idx3 LW is the JAL@2 fall-through: on the JAL's cold BTB miss it is fetched wrong-path and, one
  // stage behind the JAL, sits at executeAt exactly when the JAL resolves at jumpAt (cancelled). The LSU
  // keeps the cancel out of its halt qualifier: it halts that one cancelled cycle, but
  // the flush clears the stage off the ready spine at the next edge, so it never issues a cmd, never
  // commits, and — crucially — never deadlocks (a wrong regression here would hang the run). The load is
  // always wrong-path (poison, JAL skips it once the BTB learns), so the idle data bus suffices. The
  // zero-IPC property was gated off-vs-on before the change was baked in.
  def LW(rd: Int, rs1: Int, imm: Int) = i(imm, rs1, 0x2, rd, 0x03)
  val shadowImage = buildImage(Seq(
    /*0*/ ADDI(1, 0, loopN), /*1*/ ADDI(1, 1, -1),
    /*2*/ JAL(0, 8),         /*3*/ LW(9, 0, 0),     // idx3 poison LOAD (JAL jumps to idx4)
    /*4*/ BNE(1, 0, -12),    /*5*/ ECALL
  ))
  val shadow = run(shadowImage, param.plugins(), "shadow")
  assert(shadow.regs(9) == 0, s"shadow: poison load committed (x9=${shadow.regs(9)})")
  assert(shadow.mispredicts > 0, "shadow: expected a JAL cold-miss mispredict (the load's shadow)")

  println(
    f"[BranchIpcSim] PASS%n" +
      f"  loop (BTB vs none):     ${full1.commits} commits; ${full1.cycles} cyc IPC $ipcFull%.3f " +
      f"vs ${base1.cycles} cyc IPC $ipcBase%.3f (${base1.cycles - full1.cycles} fewer, ${ipcFull / ipcBase}%.2fx)%n" +
      f"  bias (GShare vs always-taken): ${full2.mispredicts} vs ${btb2.mispredicts} branch mispredicts " +
      f"over ${full2.commits} commits ($notTaken/$biasN fall-throughs); identical commit streams.%n" +
      f"  shadow: poison load in a mispredict shadow never commits, no deadlock " +
      f"(${shadow.mispredicts} mispred, ${shadow.cycles} cyc)."
  )
}
