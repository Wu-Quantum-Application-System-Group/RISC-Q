package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.misc.TileLinkMemReadWriteFiber
import riscq.misc.VivadoClkHelper
import spinal.lib.bus.amba4.axi._
import riscq.network.GtPins

case class RiscqZcu216SocPorts(gtNum: Int = 0) extends Bundle {
  val dacNum = 16
  val adcNum = 16

  val dspClk = in Bool ()
  val dspRst = in Bool ()
  val axi = slave(Axi4(Axi4Config(32, 32, 2)))
  val dac = List.fill(dacNum)(master port Stream(Bits(16 * 16 bits)))
  val adc = List.fill(adcNum)(slave port Stream(Bits(4 * 16 bits)))
  val gts = List.fill(gtNum)(GtPins())

  riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS")
  adc.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := True
  }
  dac.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
    d.valid := True
  }
  gts.zipWithIndex.foreach { case (gt, id) =>
    gt.mgtrefclk_p.addAttribute(
      "X_INTERFACE_INFO",
      s"xilinx.com:interface:diff_clock:1.0 mgtrefclk_${id}_diff CLK_P "
    )
    gt.mgtrefclk_n.addAttribute(
      "X_INTERFACE_INFO",
      s"xilinx.com:interface:diff_clock:1.0 mgtrefclk_${id}_diff CLK_N "
    )
  }

  def noDac() = {
    dac.foreach { d =>
      d.payload := 0
    }
  }
}

object RiscqZcu216MemoryMap {

  // memory map for axi host bus
  // 0 - 1 << 20: riscq core memory
  // 1 << 25 - 2 * 1 << 25: pulse memory
  // 2 * 1 << 25 - 3 * 1 << 25: readout map
  // 3 * 1 << 25 - 4 * 1 << 25: reset/control signal
  val riscqCoreMemOffset = 0x0
  val riscqCoreMemSpaceSize = 1 << 16

  val pulseMemOffset = 1 << 25
  val pulseMemSpaceSize = 1 << 18

  val readoutMemOffset = 2 * (1 << 25)
  val readoutMemSpaceSize = 1 << 18

  val hostCtrlOffset = 3 * (1 << 25)

  def coreOffset(coreId: Int) = riscqCoreMemOffset + coreId * riscqCoreMemSpaceSize
  def pulseOffset(coreId: Int) = pulseMemOffset + coreId * pulseMemSpaceSize
  def readoutOffset(coreId: Int) = readoutMemOffset + coreId * readoutMemSpaceSize
}


abstract class Zcu216Top(gtNum: Int) extends Component {
  val io = RiscqZcu216SocPorts(gtNum = gtNum)

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)

  // cd500m
  io.dspClk.setName("dspClk")
  io.dspRst.setName("dspRst")
  val dspCd = ClockDomain(io.dspClk, io.dspRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspRst, 500000000)

}