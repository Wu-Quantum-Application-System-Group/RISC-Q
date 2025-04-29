package riscq.execute

import spinal.lib.misc.pipeline._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.core._
import spinal.lib.bus.tilelink.fabric
import spinal.core.sim._
import spinal.idslplugin.PostInitCallback
import riscq.riscv.{MicroOp, RD, RfResource, SingleDecoding}
import riscq.decode.DecoderSimplePlugin
import riscq.Global._
import riscq.decode.Decode
import riscq.pulse._
import riscq.pulse
import riscq.schedule.PipelinePlugin
import riscq.misc.TileLinkMemReadWriteFiber
import riscq.soc.QubicSocParams
import riscq.memory.DualClockRam
import riscq.soc.QubicSoc
import QubicSocParams._

class PulseGeneratorPlugin(qubitNum: Int) extends ExecutionUnit {
  val logic = during setup new Area {
    val uop = addUop(SingleDecoding(M"-----------------011000001111111", Nil))
    uop.dontFlushFrom(1)

    val pp = host[PipelinePlugin]
    val buildBefore = retains(pp.elaborationLock)

    val pgs = List.fill(qubitNum * 2)(
      riscq.pulse.PulseGenerator(
        batchSize = QubicSocParams.pulseBatchSize,
        dataWidth = QubicSocParams.dataWidth,
        addrWidth = QubicSocParams.pulseAddrWidth,
        timeWidth = 32,
        durWidth = QubicSocParams.pulseDurWidth,
        // the last 1 is from x.io.memPort.rsp := RegNext(y.fastPort.rdata)
        memLatency = 1 + QubicSocParams.pulseMemOutReg.toInt + 1,
        timeInOffset = 1
      )
    )
    pgs.foreach { _.addAttribute("KEEP_HIERARCHY", "TRUE") }
    val pgs_io = Vec(pgs.map(_.io))

    val memPorts = Vec(pgs_io.map(io => master(cloneOf(io.memPort))))
    (memPorts zip pgs).foreach{case (m, p) => m <> p.io.memPort}
    val pulses = Vec(pgs_io.map(io => master(cloneOf(io.pulse))))
    (pulses zip pgs).foreach{case (pulse, p) => pulse := p.io.pulse}

    awaitBuild()
    uopRetainer.release()
    new pp.Execute(0) {
      /* Get time from timer plugin */
      val timer = host[TimerPlugin]
      timer.logic.await()
      for (i <- 0 until (qubitNum * 2)) {
        // pgs_io(i).time := RegNext(timer.logic.time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
        pgs_io(i).time := timer.logic.time
      }

      /* Unpack instruction and forward to pgs[i] */
      val idS = 128 - pulseIdWidth
      val phaseS = idS - pulsePhaseWidth
      val freqS = phaseS - pulseFreqWidth
      val durationS = freqS - pulseDurWidth
      val startS = durationS - pulseStartWidth
      val addrS = startS - pulseAddrWidth
      val ampS = addrS - pulseAmpWidth
      val instruction = Decode.INSTRUCTION
      val id5 = instruction(idS, pulseIdWidth bits)
      // TODO how many? This should be parametrized.
      // val id = id5(log2Up(2 * qubitNum) downto 0).asUInt
      val id = id5.asUInt.resized
      val phase = instruction(phaseS, pulsePhaseWidth bits)
      val freq = instruction(freqS, pulseFreqWidth bits)
      val duration = instruction(durationS, pulseDurWidth bits)
      val start = instruction(startS, pulseStartWidth bits)
      val addr = instruction(addrS, pulseAddrWidth bits)
      val amp = instruction(ampS, pulseAmpWidth bits)

      // Default values to prevent "latch detected"
      for (i <- 0 until (qubitNum * 2)) {
        // pgs_io(id).startTime := RegNext(start.asUInt).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
        pgs_io(i).startTime := start.asUInt
        pgs_io(i).phase.setIdle()
        pgs_io(i).freq.setIdle()
        pgs_io(i).dur.setIdle()
        pgs_io(i).addr.setIdle()
        pgs_io(i).amp.setIdle()
      }

      def drive[T <: Data](flow: Flow[T], data: Bits) = {
        flow.payload.assignFromBits(data)
        flow.valid := True
      }

      drive(pgs_io(id).phase, phase)
      drive(pgs_io(id).freq, freq)
      drive(pgs_io(id).dur, duration)
      drive(pgs_io(id).addr, addr)
      drive(pgs_io(id).amp, amp)
    }
    buildBefore.release()
  }
}
