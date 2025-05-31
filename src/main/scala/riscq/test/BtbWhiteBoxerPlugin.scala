package riscq.test

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import riscq.Global
// import riscq.Global.{COMMIT, HART_COUNT, TRAP}
import riscq.schedule.PipelinePlugin
import riscq.decode.{Decode, DecoderSimplePlugin}
import riscq.fetch.FetchCachelessPlugin
import riscq.execute._
import riscq.execute.lsu._
import riscq.fetch.{Fetch}
// import riscq.prediction.{BtbPlugin, LearnCmd, LearnPlugin}
import riscq.regfile.{RegFileWrite, RegFileWriter, RegFileWriterService, RegFilePlugin}
import riscq.riscv.{Const, Riscv}
import riscq.schedule.{FlushCmd, ReschedulePlugin}

import scala.collection.mutable.ArrayBuffer
import riscq.fetch.PcService
import riscq.fetch.PcPlugin
import riscq.riscv.IntRegFile
import riscq.riscv.RS1
import riscq.schedule.HazardPlugin
import riscq.riscv.RS2
import scala.collection.mutable.LinkedHashMap

class BtbWhiteboxerPlugin() extends FiberPlugin {
  val logic = during setup new Area {
    val pp = host[PipelinePlugin]
    val buildBefore = retains(pp.pipelineBuildLock)

    awaitBuild()

    def wrap[T <: Data](that: T): T = {
      val buffered = CombInit(that).simPublic
      buffered
    }

    pp.pipelinePrepareLock.await()

    val rfp = host.get[RegFilePlugin]
    val rf = rfp.nonEmpty generate new Area {
      val p = rfp.get
      val mem = p.logic.regfile.fpga.asMem.ram
    }
    val pcs = new Area {
      val feCtrls = pp.feIdToCtrl.toList.sortBy(_._1).map(_._2)
      val deCtrls = pp.deIdToCtrl.toList.sortBy(_._1).map(_._2)
      val exCtrls = pp.exIdToCtrl.toList.sortBy(_._1).map(_._2)
      val ctrls = feCtrls ++ deCtrls ++ exCtrls
      val data = for (ctrl <- ctrls) yield new Area {
        val ctrlName = ctrl.name
        val forgetOne = wrap(ctrl.requests.forgetsOne.orR)
        val valid = wrap(ctrl.isValid)
        val ready = wrap(ctrl.up.isReady)
        val pc = wrap(ctrl(Fetch.WORD_PC))
      }
    }
    val exInsts = new Area {
      val exCtrls = pp.exIdToCtrl.toList.sortBy(_._1).map(_._2)
      val data = for (ctrl <- exCtrls) yield new Area {
        val ctrlName = ctrl.name
        val inst = wrap(ctrl(Decode.INSTRUCTION))
        val valid = wrap(ctrl.isValid)
        val ready = wrap(ctrl.isReady)
        val pc = wrap(ctrl(Fetch.WORD_PC))
      }
    }

    buildBefore.release()
  }
}
