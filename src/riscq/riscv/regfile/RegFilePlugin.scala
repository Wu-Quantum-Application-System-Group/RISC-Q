package riscq.riscv.regfile

import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.Decode
import riscq.riscv.execute.{Execute, IntAluPlugin}
import riscq.riscv.fetch.Fetch
import riscq.riscv.misc.PipelinePlugin

// Defined at file scope (not inside the anonymous Area) so it gets a stable class identity that
// survives Area renumbering; otherwise Zinc can report RiscqProbe's reference as broken.
case class FwdSrc(rd: UInt, data: Bits, valid: Bool)

/**
 * The integer register file: 32 × XLEN, with `x0` hard-wired to 0.
 *
 * Built as a small **asynchronous-read** memory (distributed RAM): two combinational read
 * ports for rs1/rs2 and one synchronous write port for rd.
 *
 * The read is split one stage ahead of execute and the write one stage behind it:
 *  - **Read at `regReadAt = executeAt-1`**, driving the raw values as `Execute.RS1_RAW`/`RS2_RAW`.
 *    The regReadAt→executeAt StageLink *registers* them (so the `RAMD32` async read leaves the
 *    executeAt critical cone) and *holds* them across a stall (so a stalled load/CSR keeps its
 *    operands).
 *  - **Write at `regWriteAt = executeAt+1`** (= `jumpAt`, so no added depth). RD_DATA is registered
 *    across the StageLink, breaking the ALU/JALR-add → RAMD32-write cone at a register.
 *
 * The source-select + add are relocated to regReadAt too (operands are registered into executeAt),
 * so the forward is evaluated one stage early at regReadAt, driving `Execute.RS1_DATA`/`RS2_DATA`
 * across a read→write gap of 2. A *selective* srcA forward keeps the barrel shifter + RD_DATA
 * CSR/load/jump mux out of the 1-ahead forward loop:
 *   srcA = the 1-ahead producer's shallow `Execute.ALU_FAST` tap, forwarded only for cheap (non-shift
 *          ALU) producers, `isValid`-gated;
 *   srcB = the 2-ahead producer's registered regWriteAt commit (any class), `isValid`-gated;
 *   priority srcA > srcB > raw read; x0 never forwards.
 * An *expensive* 1-ahead producer (shift/load/CSR/jump) commits too late for srcB and isn't covered
 * by srcA, so a class-gated 1-cycle interlock holds regReadAt until it reaches regWriteAt — back-to-
 * back ALU pairs stay bubble-free.
 */
class RegFilePlugin(p: RiscqParam) extends FiberPlugin {
  val logic = during setup new Area {
    val pp   = host[PipelinePlugin]
    val lock = retains(pp.elaborationLock)
    awaitBuild()

    val cr = pp.ctrl(p.regReadAt)  // register-read stage (executeAt-1): operands registered into execute
    val ce = pp.ctrl(p.executeAt)  // execute stage (the 1-ahead producer commits combinationally here)
    val cw = pp.ctrl(p.regWriteAt) // register-write stage (executeAt+1): the registered commit + write

    val regs = Mem.fill(Decode.ARCH_REGS)(Bits(Global.XLEN bits))
      .init(Seq.fill(Decode.ARCH_REGS)(B(0, Global.XLEN bits))) // deterministic reset state
    regs.simPublic()

    // ---- Read ports at regReadAt: two combinational reads (x0 ⇒ 0), registered into executeAt by the
    //      StageLink as Execute.RS1_RAW/RS2_RAW. ----
    val read = new cr.Area {
      def rd(addr: UInt): Bits = (addr === 0) ? B(0, Global.XLEN bits) | regs.readAsync(addr)
      Execute.RS1_RAW := rd(Decode.RS1)
      Execute.RS2_RAW := rd(Decode.RS2)
    }

    // ---- Selective 2-deep forward, evaluated at regReadAt (one stage early, so the source-select +
    //      add leave the operand-select path) and driving RS1_DATA/RS2_DATA. `FwdSrc` (rd, data, valid)
    //      is defined at file scope. Priority srcA > srcB > raw read; x0 never forwards. ----
    val forward = new cr.Area {
      // srcA: the 1-ahead producer (at executeAt this cycle), its SHALLOW ALU_FAST tap, only for CHEAP
      //   producers (an ALU op that isn't a shift — its result is ALU_FAST, available without the
      //   5-level barrel shifter), gated on `isValid` (not isFiring, to keep the LSU-halt/cancel/ready
      //   spine out of the valid). The read is reg→combinational→mux with no loop: regReadAt's StageLink
      //   registers feed executeAt, whose ALU_FAST is consumed back at regReadAt one stage upstream —
      //   different instructions, no combinational cycle.
      // srcA: the 1-ahead fast tap. p.aluNoFastForward DROPS it entirely (None) and interlocks every
      //   1-ahead RAW hazard below instead, taking the ALU-result-mux→RD_DATA→forward loop off the path.
      val srcA = (!p.aluNoFastForward) generate new ce.Area {
        val rd    = CombInit(apply(Decode.RD))
        // p.aluFastAddOnly: forward only the ADD class (ALU_FAST = addSub, mux-free); else any non-shift
        // ALU op. MUST stay in lockstep with `prod.cheap` below — srcA forwards iff cheap, prod interlocks
        // iff a producer is NOT cheap, so a divergence would drop a real hazard.
        val cheap = apply(IntAluPlugin.ALU_WRITES_RD) &&
          (if (p.aluFastAddOnly) apply(IntAluPlugin.ALU_SEL) === IntAluPlugin.AluSel.ADD
           else apply(IntAluPlugin.ALU_SEL) =/= IntAluPlugin.AluSel.SHIFT)
        val data  = CombInit(apply(Execute.ALU_FAST))
        val valid = isValid && cheap && (rd =/= 0)
      }
      // srcB: the 2-ahead producer (at regWriteAt this cycle), its registered regWriteAt commit (any
      //   class), also `isValid`-gated.
      val srcB = new cw.Area {
        val rd    = CombInit(apply(Decode.RD))
        val data  = CombInit(apply(Execute.RD_DATA))
        val valid = isValid && apply(Execute.RD_WRITE) && (rd =/= 0)
      }
      val fwdSrcs = Option(srcA).map(a => FwdSrc(a.rd, a.data, a.valid)).toSeq :+
        FwdSrc(srcB.rd, srcB.data, srcB.valid)
      def fwd(addr: UInt, raw: Bits): Bits =
        fwdSrcs.foldRight(raw) { (s, fallback) =>
          (s.valid && addr =/= 0 && addr === s.rd) ? s.data | fallback
        }
      Execute.RS1_DATA := fwd(Decode.RS1, Execute.RS1_RAW)
      Execute.RS2_DATA := fwd(Decode.RS2, Execute.RS2_RAW)

      // Interlock. Baseline = CLASS-GATED: an EXPENSIVE 1-ahead producer (writes rd≠0 but isn't a cheap
      //   ALU op — shift/load/CSR/jump, at executeAt) commits too late (regWriteAt) for srcB this cycle and
      //   isn't forwarded by srcA, so hold regReadAt one cycle (after the bubble srcB covers it).
      //   aluNoFastForward drops srcA, so it must interlock EVERY 1-ahead writer (rd≠0), not just the
      //   expensive ones. `isValid` (not isFiring): a halted producer (e.g. a load awaiting memory) still
      //   owns rd and must still block.
      val prod = new ce.Area {
        val rd        = apply(Decode.RD)
        // Same `cheap` predicate as srcA above (aluFastAddOnly-aware) — they MUST match.
        val cheap     = apply(IntAluPlugin.ALU_WRITES_RD) &&
          (if (p.aluFastAddOnly) apply(IntAluPlugin.ALU_SEL) === IntAluPlugin.AluSel.ADD
           else apply(IntAluPlugin.ALU_SEL) =/= IntAluPlugin.AluSel.SHIFT)
        val blocks    = isValid && apply(Execute.RD_WRITE) && (rd =/= 0) &&
          (if (p.aluNoFastForward) True else !cheap)
      }
      def deps(addr: UInt): Bool = addr =/= 0 && addr === prod.rd
      cr.haltWhen(prod.blocks && (deps(Decode.RS1) || deps(Decode.RS2)))
    }

    // ---- executeAt: the commit decision, whiteboxed for the lock-step testbench (the write port lives
    //      at regWriteAt below). ----
    val exec = new ce.Area {
      // A real instruction leaves execute, the op writes a register, and the destination isn't x0.
      val doWrite = down.isFiring && Execute.RD_WRITE && Decode.RD =/= 0

      // Whitebox the committed write (and PC/instruction) for the lock-step testbench. Kept at
      // executeAt: RD_DATA is computed here, and an instruction firing at executeAt is past any flush
      // and will reach regWriteAt, so the RVLS retire point/value is consistent here (executeAt=4 /
      // regWriteAt=5).
      val dbgFiring = CombInit(down.isFiring)
      val dbgPc     = CombInit(Global.fullPc(apply(Global.PC))) // full committed PC for RVLS lock-step + pass/fail
      val dbgInstr  = CombInit(apply(Fetch.WORD))
      val dbgRd     = CombInit(apply(Decode.RD))
      // RD_DATA_DBG is the executeAt rd value (== RD_DATA when lateWriteback is off; the sim-only
      // executeAt copy when on) — keeps the RVLS retire point at executeAt regardless of where the
      // real RD_DATA mux lives.
      val dbgRdData = CombInit(apply(Execute.RD_DATA_DBG))
      val dbgWrite  = CombInit(doWrite)
      dbgFiring.simPublic()
      dbgPc.simPublic()
      dbgInstr.simPublic()
      dbgRd.simPublic()
      dbgRdData.simPublic()
      dbgWrite.simPublic()
    }

    // ---- Synchronous write port at regWriteAt. RD/RD_DATA/RD_WRITE are the values registered from
    //      executeAt by the StageLink; `down.isFiring` gates out a flushed/bubble instruction (a
    //      wrong-path instr cancelled before regWriteAt never writes). ----
    val write = new cw.Area {
      val doWrite = down.isFiring && Execute.RD_WRITE && (Decode.RD =/= 0)
      regs.write(Decode.RD, Execute.RD_DATA, doWrite)
    }

    lock.release()
  }
}
