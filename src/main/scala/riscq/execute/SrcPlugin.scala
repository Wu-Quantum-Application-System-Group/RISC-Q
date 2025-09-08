package riscq.execute

import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.misc.pipeline._
import spinal.lib.misc.plugin.FiberPlugin
import riscq.Global
import riscq.fetch.Fetch
import riscq.Global._
import riscq.decode.Decode
import riscq.riscv.{IMM, IntRegFile, MicroOp, RS1, RS2, RfRead, Riscv}
import riscq.schedule.PipelinePlugin

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import riscq.decode.DecoderService

object SrcStageables extends AreaObject {
  val REVERT, ZERO, UNSIGNED = Payload(Bool())
}

class SrcKeys
class Src1Keys extends SrcKeys
class Src2Keys extends SrcKeys
class OpKeys   extends SrcKeys
object SrcKeys extends AreaObject {
  val Op = new Area{
    val ADD = new OpKeys
    val SUB = new OpKeys
    val SRC1 = new OpKeys
    val LESS = new OpKeys
    val LESS_U = new OpKeys
  }
  val SRC1 = new Area{
    val RF = new Src1Keys
    val U  = new Src1Keys
  }
  val SRC2 = new Area{
    val RF = new Src2Keys
    val I  = new Src2Keys
    val S  = new Src2Keys
    val PC = new Src2Keys
  }
}

class SrcPlugin(var executeAt : Int,
                var relaxedRs: Boolean,
                ) extends FiberPlugin{
  val elaborationLock = Retainer()

  val spec = mutable.LinkedHashMap[UopSpec, mutable.LinkedHashSet[SrcKeys]]()
  def specify(impl : UopSpec, keys: Seq[SrcKeys]) = {
    val e = spec.getOrElseUpdate(impl, mutable.LinkedHashSet[SrcKeys]())
    for(k <- keys){
      assert(!e.contains(k))
      k match {
        case SrcKeys.SRC1.RF => impl.addRsSpec(RS1, executeAt)
        case SrcKeys.SRC2.RF => impl.addRsSpec(RS2, executeAt)
        case _ =>
      }
      e += k
    }
  }
  def specify(impl : UopSpec, head: SrcKeys, tail : SrcKeys*) : Unit = specify(impl, head +: tail)

  val SRC1, SRC2 = Payload(SInt(Riscv.XLEN bits))
  val ADD_SUB = Payload(SInt(Riscv.XLEN bits))
  val LESS = Payload(Bool())

  val logic = during setup new Area{
    val pp = host[PipelinePlugin]
    val dp = host[DecoderService]
    val buildBefore = retains(pp.elaborationLock, dp.elaborationLock)

    awaitBuild()
    elaborationLock.await()
    
    val ss = SrcStageables
    val sk = SrcKeys

    assert(executeAt >= 0)

    val keys = spec.flatMap(_._2).toSeq.distinctLinked
    val opKeys   = keys.filter(_.isInstanceOf[OpKeys]).toSeq
    val src1Keys = keys.filter(_.isInstanceOf[Src1Keys]).toSeq
    val src2Keys = keys.filter(_.isInstanceOf[Src2Keys]).toSeq

    val SRC1_CTRL = Payload(Bits(log2Up(src1Keys.size) bits))
    val SRC2_CTRL = Payload(Bits(log2Up(src2Keys.size) bits))

    val src1ToEnum = src1Keys.zipWithIndex.map{case(k,i) => k -> B(i, widthOf(SRC1_CTRL) bits)}.toMap
    val src2ToEnum = src2Keys.zipWithIndex.map{case(k,i) => k -> B(i, widthOf(SRC2_CTRL) bits)}.toMap

    def has(keys : SrcKeys*) = keys.exists(keys.contains)

    for((impl, keys) <- spec){
      val decodes = keys.toSeq.flatMap{
          case sk.Op.SRC1     => List(ss.REVERT -> False, ss.ZERO   -> True)
          case sk.Op.ADD      => List(ss.REVERT -> False, ss.ZERO   -> False)
          case sk.Op.SUB      => List(ss.REVERT -> True,  ss.ZERO   -> False)
          case sk.Op.LESS     => List(ss.REVERT -> True,  ss.ZERO   -> False, ss.UNSIGNED -> False)
          case sk.Op.LESS_U   => List(ss.REVERT -> True,  ss.ZERO   -> False, ss.UNSIGNED -> True)
          case key : Src1Keys => List(SRC1_CTRL -> src1ToEnum(key))
          case key : Src2Keys => List(SRC2_CTRL -> src2ToEnum(key))
      }
      impl.decode(
        keys.toSeq.flatMap{
          case sk.Op.SRC1     => List(ss.REVERT -> False, ss.ZERO   -> True)
          case sk.Op.ADD      => List(ss.REVERT -> False, ss.ZERO   -> False)
          case sk.Op.SUB      => List(ss.REVERT -> True,  ss.ZERO   -> False)
          case sk.Op.LESS     => List(ss.REVERT -> True,  ss.ZERO   -> False, ss.UNSIGNED -> False)
          case sk.Op.LESS_U   => List(ss.REVERT -> True,  ss.ZERO   -> False, ss.UNSIGNED -> True)
          case key : Src1Keys => List(SRC1_CTRL -> src1ToEnum(key))
          case key : Src2Keys => List(SRC2_CTRL -> src2ToEnum(key))
        }
      )
    }

    val src = new pp.Execute(executeAt - relaxedRs.toInt){
      this(Global.PC) := this(Fetch.WORD_PC)
      val rrp = host[RegReadPlugin]
      def getPayload(rs : RfRead) = down(rrp(IntRegFile, rs))
      val imm = new IMM(Decode.INSTRUCTION)
      if(src1Keys.nonEmpty) SRC1 := SRC1_CTRL.muxListDc[SInt](src1Keys.map {
        case sk.SRC1.RF => src1ToEnum(sk.SRC1.RF) -> S(getPayload(RS1))
        case sk.SRC1.U  => src1ToEnum(sk.SRC1.U ) -> S(imm.u).resize(Riscv.XLEN)
      })

      val pcExtended = false
      if(src2Keys.nonEmpty) SRC2 := SRC2_CTRL.muxListDc[SInt](src2Keys.map {
        case sk.SRC2.RF => src2ToEnum(sk.SRC2.RF) -> S(getPayload(RS2))
        case sk.SRC2.I  => src2ToEnum(sk.SRC2.I ) -> imm.i_sext
        case sk.SRC2.S  => src2ToEnum(sk.SRC2.S ) -> imm.s_sext
        case sk.SRC2.PC => src2ToEnum(sk.SRC2.PC) -> pcExtended.mux(S(this(Global.PC)).resize(Riscv.XLEN), S(this(Global.PC).resize(Riscv.XLEN)))
      })
    }


    val addsub = opKeys.nonEmpty generate new pp.Execute(executeAt){
      def carryIn(that: SInt) = that + S(U(ss.REVERT, Riscv.XLEN bits))
      val rs2Patched = CombInit(this(SRC2))
      when(ss.REVERT){ rs2Patched :=  ~SRC2  }
      when(ss.ZERO){ rs2Patched := 0 }
      ADD_SUB := carryIn(SRC1 + rs2Patched)

      // SLT, SLTU, branches
      LESS := (SRC1.msb === SRC2.msb) ? ADD_SUB.msb | Mux(ss.UNSIGNED, SRC2.msb, SRC1.msb)
    }
    buildBefore.release()
  }
}
