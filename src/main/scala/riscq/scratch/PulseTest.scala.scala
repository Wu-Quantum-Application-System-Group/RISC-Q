package riscq.scratch

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SpecRenamer, Axi4ToTilelinkFiber}
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import spinal.lib.bus.tilelink.BusParameter
import spinal.lib.bus.tilelink.fabric.RamFiber
import spinal.lib.bus.tilelink
import riscq.misc.VivadoClkHelper
import riscq.pulse
import riscq.soc.PulseMemFiber

case class PulseTester(dacId: Int) extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 2)))
    val dspClk = in Bool ()
    val dspExtRst = in Bool ()
    val dac = List.fill(16)(master Stream(Bits(16 * 16 bits)))
    val adc = List.fill(16)(slave Stream(Bits(4 * 16 bits)))
    val ledR = out Bool ()
    val ledB = out Bool ()
  }
  val hostCd = ClockDomain.current
  val dspCd = ClockDomain(io.dspClk, io.dspExtRst)
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)
  io.dspClk.setName("dspClk")
  io.dspExtRst.setName("dspExtRst")
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspExtRst, 500000000)
  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  io.adc.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
  }
  io.dac.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }

  val dspArea = new ClockingArea(dspCd) {
    val pulseMemFiber = hostCd(PulseMemFiber(1, 256, 1024, true, hostCd, dspCd))
    val pg = pulse.PulseGenerator(
        batchSize = 16,
        dataWidth = 16,
        addrWidth = 10,
        timeWidth = 32,
        durWidth = 16,
        memLatency =
          1 + 1, // sync read latency + out reg
        timeInOffset = 1,
        fifoDepth = 2
      )
    
      val pulseMem = pulseMemFiber.pulseMems(0)
      pulseMem.fastPort.enable := True
      pulseMem.fastPort.write := False
      pulseMem.fastPort.mask.setAllTo(False)
      pulseMem.fastPort.wdata.setAllTo(False)
      pulseMem.fastPort.address := pg.io.memPort.cmd.payload
      pg.io.memPort.rsp := pulseMem.fastPort.rdata
      for(i <- 0 until 16) {
        io.dac(i).valid := True
        if(i == dacId) {
          io.dac(i).payload := pg.io.pulse.payload.map { _.r}.asBits()
        } else {
          io.dac(i).payload := 0
        }
      }
    }
}
