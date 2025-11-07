package riscq.execute

import spinal.core._
import spinal.lib._
import spinal.lib.misc.pipeline._
import riscq.riscv.IMM
// import riscq.decode.Decode._
import riscq.riscv.Riscv
import riscq.riscv.IntRegFile
import riscq.schedule.ReschedulePlugin
import riscq.fetch.PcPlugin
import riscq.riscv.Rvi
import riscq.schedule.PipelinePlugin
import riscq.Global.PC
import riscq.Global.PC_WIDTH
import riscq.decode.Decode
import riscq.fetch.Fetch
import riscq.riscv.Const
import riscq.Global
import riscq.fetch.BtbFetchPlugin
import riscq.fetch.BtbParams
import riscq.fetch.BtbEntry
import _root_.spinal.core.sim.SimDataPimper

class BtbBranchPlugin(
    var aluAt: Int = 0,
    var jumpAt: Int = 1,
    var wbAt: Int = 0
) extends ExecutionUnit { // with LearnSource
  import BranchPlugin._

  def catchMissaligned = !Riscv.RVC

  val logic = during setup new Area {
    val wbp = host.find[WriteBackPlugin](p => p.rf == IntRegFile)
    val sp = host[ReschedulePlugin]
    val pcp = host[PcPlugin]
    val pp = host[PipelinePlugin]
    val srcp = host[SrcPlugin]
    val bfp = host[BtbFetchPlugin]
    val ioRetainer = retains(wbp.elaborationLock, sp.elaborationLock, pcp.elaborationLock)
    val buildBefore = retains(pp.elaborationLock)

    awaitBuild()

    import SrcKeys._

    addUop(Rvi.JAL).decode(BRANCH_CTRL -> BranchCtrlEnum.JAL)
    addUop(Rvi.JALR).decode(BRANCH_CTRL -> BranchCtrlEnum.JALR).srcs(SRC1.RF)
    addUop(Rvi.BEQ).decode(BRANCH_CTRL -> BranchCtrlEnum.B).srcs(SRC1.RF, SRC2.RF)
    addUop(Rvi.BNE).decode(BRANCH_CTRL -> BranchCtrlEnum.B).srcs(SRC1.RF, SRC2.RF)
    addUop(Rvi.BLT).decode(BRANCH_CTRL -> BranchCtrlEnum.B).srcs(SRC1.RF, SRC2.RF, Op.LESS)
    addUop(Rvi.BGE).decode(BRANCH_CTRL -> BranchCtrlEnum.B).srcs(SRC1.RF, SRC2.RF, Op.LESS)
    addUop(Rvi.BLTU).decode(BRANCH_CTRL -> BranchCtrlEnum.B).srcs(SRC1.RF, SRC2.RF, Op.LESS_U)
    addUop(Rvi.BGEU).decode(BRANCH_CTRL -> BranchCtrlEnum.B).srcs(SRC1.RF, SRC2.RF, Op.LESS_U)
    SEL.setName("branch_SEL")

    val jList = List(Rvi.JAL, Rvi.JALR)
    val bList = List(Rvi.BEQ, Rvi.BNE, Rvi.BLT, Rvi.BGE, Rvi.BLTU, Rvi.BGEU)

    val wb = wbp.createPort(wbAt)
    for (j <- jList; spec = uopSpecs(j)) {
      wbp.addMicroOp(wb, spec)
      spec.mayFlushUpTo(jumpAt)
    }
    for (j <- bList; spec = uopSpecs(j)) {
      spec.mayFlushUpTo(jumpAt)
    }

    val age = pp.exGetAge(jumpAt)
    val pcPort = pcp.newJumpInterface(age)
    val flushPort = sp.newFlushPort(age)
    // val trapPort = catchMissaligned generate ts.newTrap(age, Execute.LANE_AGE_WIDTH)

    uopRetainer.release()
    ioRetainer.release()

    val pcCalc = new pp.Execute(aluAt) {
      val imm = IMM(Decode.INSTRUCTION)
      val target_a = BRANCH_CTRL.mux(
        default -> S(PC),
        BranchCtrlEnum.JALR -> srcp.SRC1.resize(PC_WIDTH)
      )

      val target_b = BRANCH_CTRL.mux(
        default -> imm.b_sext,
        BranchCtrlEnum.JAL -> imm.j_sext,
        BranchCtrlEnum.JALR -> imm.i_sext
      )

      val PC_TRUE = insert(U(target_a + target_b).resize(PC_WIDTH));
      PC_TRUE(0) := False // PC RESIZED
      val PC_FALSE = insert(PC + U(Fetch.WORD_BYTES))

      // Without those keepattribute, Vivado will transform the logic in a way which will serialize the 32 bits of the COND comparator,
      // with the 32 bits of the TRUE/FALSE adders, ending up in a quite long combinatorial path (21 lut XD)
      KeepAttribute(apply(PC_TRUE))
      KeepAttribute(apply(PC_FALSE))
    }

    val PC_TRUE = pcCalc.PC_TRUE
    val PC_FALSE = pcCalc.PC_FALSE

    // TODO different parameter for this stage?
    val writeBtb = new pp.Execute(aluAt + 1) {
      val sel = Bool()
      sel := isValid && SEL
      sel.simPublic()
      val pctrue = UInt()
      pctrue := PC_TRUE
      pctrue.simPublic()
      val pc = UInt()
      pc := Fetch.WORD_PC
      pc.simPublic()
      val btb_table = bfp.logic.branch_targets
      val addr = Fetch.WORD_PC(2 until (2 + BtbParams.addr_size))
      val tag = Fetch.WORD_PC((2 + BtbParams.addr_size) until PC_WIDTH)
      tag.simPublic()
      val entry = BtbEntry()
      entry.valid := True
      entry.targetPc := pctrue
      entry.tag := tag
      when(isValid && SEL) {
        btb_table.write(addr, entry)
      }
    }

    val alu = new pp.Execute(aluAt) {
      val ss = SrcStageables
      val EQ = insert(srcp.SRC1 === srcp.SRC2)

      val COND = insert(
        BRANCH_CTRL.mux(
          BranchCtrlEnum.JALR -> True,
          BranchCtrlEnum.JAL -> True,
          BranchCtrlEnum.B -> Decode
            .INSTRUCTION(14 downto 12)
            .mux[Bool](
              B"000" -> EQ,
              B"001" -> !EQ,
              M"1-1" -> !srcp.LESS,
              default -> srcp.LESS
            )
        )
      )

    }

    val jumpLogic = new pp.Execute(jumpAt) {
      val bfp = host[BtbFetchPlugin]
      // Maybe the `a ? b | c` operator doesn't like payloads?
      pcPort.pc := alu.COND.mux(this(PC_TRUE), this(PC_FALSE))
      flushPort.self := False
      val predicted = bfp.logic.jump_predicted
      when(isValid && SEL && (alu.COND =/= predicted)) {
        pcPort.valid := True
        flushPort.valid := True
      } otherwise {
        pcPort.valid := False
        flushPort.valid := False
      }

      val MISSALIGNED = insert(PC_TRUE(0, Fetch.PC_LOW bits) =/= 0 && alu.COND)

      val IS_JAL = insert(BRANCH_CTRL === BranchCtrlEnum.JAL)
      val IS_JALR = insert(BRANCH_CTRL === BranchCtrlEnum.JALR)
      val rdLink = List[Bits](1, 5).map(Decode.INSTRUCTION(Const.rdRange) === _).orR
      val rs1Link = List[Bits](1, 5).map(Decode.INSTRUCTION(Const.rs1Range) === _).orR
      val rdEquRs1 = Decode.INSTRUCTION(Const.rdRange) === Decode.INSTRUCTION(Const.rs1Range)
    }

    val wbLogic = new pp.Execute(wbAt) {
      wb.valid := SEL
      wb.payload := Global.expendPc(PC_FALSE, Riscv.XLEN).asBits
    }
    buildBefore.release()
  }
}
