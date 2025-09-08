package riscq.pulse

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.lib.fsm._
import scala.math

import scala.collection.mutable
import riscq.misc.GpioFiber
import spinal.lib.bus.tilelink.fabric.RamFiber

import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4
import riscq.misc.Axi4VivadoHelper

import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber

import riscq.misc.Axi4StreamVivadoHelper
import riscq.misc.TileLinkPipeFiber
import riscq.misc.TileLinkMemReadWriteFiber

import riscq.fetch.{CachelessBus, CachelessBusParam}
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber
import riscq.fetch.CachelessBusToTilelink
import riscq.misc.Axi4VivadoHelper
import riscq.memory.DualClockRam
import riscq.network.GtPins
import riscq.misc.VivadoClkHelper
import riscq.network.AxiToTileLinkDriver
import spinal.lib.bus.amba4.axi.sim.Axi4Master

case class DacSquare() extends Component {
  val dacNum = 16
  val adcNum = 16
  val io = new Bundle {
    val dspClk = in Bool ()
    val dspExtRst = in Bool ()
    val axi = slave(Axi4(Axi4Config(32, 32, 2)))
    val dac = List.fill(dacNum)(master port Stream(Bits(16 * 16 bits)))
    val adc = List.fill(adcNum)(slave port Stream(Bits(4 * 16 bits)))
  }
  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  io.adc.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := True
  }
  io.dac.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)

  // cd500m
  io.dspClk.setName("dspClk")
  io.dspExtRst.setName("dspExtRst")
  val dspRst = io.dspExtRst

  val dspCd = ClockDomain(io.dspClk, dspRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspExtRst, 500000000)

  val sqGen = dspCd(SquareGenerator(4))
  val enable = Reg(Bool())
  val enableBuffered = dspCd(BufferCC(enable))
  val dacSel = Reg(UInt(4 bits)) init 0

  val axiDriver = AxiToTileLinkDriver(driveProc = factory => {
    factory.drive(enable, 0)
    factory.write(dacSel, 4)
  })
  axiDriver.axi <> io.axi

  for (i <- 0 until dacNum) {
    io.dac(i).valid := True
    val selected = dspCd(BufferCC(dacSel === i))
    val valid = dspCd(RegNext(selected && enableBuffered))
    io.dac(i).payload := valid.mux(sqGen.io.data.asBits, B(0, 256 bits))
  }
}

object GenDacSquare extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(DacSquare())
}

object TestDacSquare extends App {
  SimConfig.compile{
    val dut = DacSquare()
    dut
  }.doSim{ dut =>
    val dspCd = dut.clockDomain
    val hostCd = dut.hostCd
    val axi4Driver = Axi4Master(dut.io.axi, hostCd)
    axi4Driver.reset()
    dspCd.forkStimulus(10)
    hostCd.forkStimulus(50)
  }
}


case class PulseReader(addrWidth: Int) extends Component {
  val io = new Bundle {
    val en = in port Bool()
    val data = master port Flow(Bits(256 bits))
    val memPort = master port MemReadPort(Bits(256 bits), addressWidth = addrWidth)
  }

  val addr = Reg(UInt(addrWidth bits))
  addr := addr + U(1)

  io.memPort.cmd.payload := addr
  io.memPort.cmd.valid := True

  io.data.valid := io.en
  io.data.payload := io.memPort.rsp
}

case class DacTester() extends Component {
  val addrWidth = 8

  ClockDomain.current.renamePulledWires("clk500m", "rst500m")
  ClockDomain.current.readResetWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst500m rst")
  ClockDomain.current.readResetWire.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  ClockDomain.current.readClockWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk500m clk")
  ClockDomain.current.readClockWire.addAttribute(
    "X_INTERFACE_PARAMETERS",
    "FREQ_HZ 500000000, ASSOCIATED_BUSIF DAC0_AXIS:DAC1_AXIS:DAC2_AXIS:DAC3_AXIS:ADC0_AXIS:ADC1_AXIS:ADC2_AXIS:ADC3_AXIS, ASSOCIATED_RESET rst500m"
  )

  // cd100m
  val clk100m = in Bool ()
  val rst100m = in Bool ()
  val cd100m = ClockDomain(clk100m, rst100m)
  rst100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst100m rst")
  rst100m.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  clk100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk100m clk")
  clk100m.addAttribute("X_INTERFACE_PARAMETERS", "FREQ_HZ 100000000, ASSOCIATED_BUSIF S_AXIS, ASSOCIATED_RESET rst100m")

  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )

  val axi = cd100m(slave(Axi4(axiConfig)))

  val pulseMem = DualClockRam(width = 256, depth = 1 << addrWidth, fastCd = ClockDomain.current, slowCd = cd100m)

  val cd100mLogic = new ClockingArea(cd100m) {
    val shareBus = Node()
    val bridge = new Axi4ToTilelinkFiber(32, 4)
    bridge.up load axi
    shareBus at 0 of bridge.down
    Axi4VivadoHelper.addInference(axi, "S_AXIS")

    val pulseMemWa = WidthAdapter()
    pulseMemWa.up at 0 of shareBus
    // val pulseMemFifo = TileLinkFifoFiber() // to improve timing
    // pulseMemFifo.up at 0 of pulseMemWa.down
    val pulseMemPipe = TileLinkPipeFiber(StreamPipe.FULL_KEEP) // to improve timing
    pulseMemPipe.up at 0 of pulseMemWa.down

    val pulseMemFibers = new Area {
      // println(s"${pulseMems(i).axiPort}")
      val pulseMemFiber = TileLinkMemReadWriteFiber(pulseMem.slowPort, withOutReg = false)
      pulseMemFiber.up at 0 of pulseMemPipe.down
    }
  }

  val pulseReader = PulseReader(addrWidth)
    pulseMem.fastPort.enable := True
    pulseMem.fastPort.mask.setAllTo(False)
    pulseMem.fastPort.address := pulseReader.io.memPort.cmd.payload
    pulseMem.fastPort.write := False
    pulseMem.fastPort.wdata.setAllTo(False)
    pulseReader.io.memPort.rsp := pulseMem.fastPort.rdata

  val dac = List.fill(4)(master port Stream(Bits(16 * 16 bits)))
  dac.zipWithIndex.foreach { case (d, id) =>
    Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }
  val adc = List.fill(4)(slave port Stream(Bits(4 * 16 bits)))
  adc.zipWithIndex.foreach { case (d, id) =>
    Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := False
  }

  dac(0).valid := pulseReader.io.data.valid
  val pulseMemOutReg = RegNext(pulseReader.io.data.payload)
  val dacIn = pulseMemOutReg.subdivideIn(16 bits)
  val dacInvertMsb =
    for (d <- dacIn) yield {
      val invertMsb = d ^ B(1 << (d.getBitsWidth - 1), d.getBitsWidth bits)
      invertMsb
    }
  val dac0InReg = RegNext(dacInvertMsb.asBits)
  dac(0).payload := dac0InReg

  for (i <- 1 until 4) {
    dac(i).valid := False
    dac(i).payload := B(0, 256 bits)
  }

  val en = in port Bool()
  pulseReader.io.en := en
}

case class SquareGenerator(timerWidth: Int) extends Component {
  val io = new Bundle {
    val data = out port Vec.fill(16)(Reg(SInt(16 bits)))
  }

  val timer = Reg(UInt(timerWidth bits)) init 0
  timer := timer + U(1)

  val amp = S(1 << 14, 16 bits)
  val namp = S(-(1 << 14), 16 bits)

  io.data.foreach { _ := timer.msb.mux(amp, namp) }
}

case class SquareGeneratorHighFreq() extends Component {
  val io = new Bundle {
    val data = out port Vec.fill(16)(Reg(SInt(16 bits)))
  }

  val amp = S(1 << 14, 16 bits)
  val namp = S(-(1 << 14), 16 bits)

  io.data.zipWithIndex.foreach { case (data, id) =>
    if (id % 2 == 0) {
      data := amp
    } else {
      data := namp
    }
  }
  // io.data.tail.foreach{ _ := timer.msb.mux(amp, namp) }
  // io.data(0) := S(0)
}

object SquareTest extends App {
  SimConfig
    .compile {
      val dut = SquareGenerator(3)
      dut
    }
    .doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)

      cd.assertReset()
      cd.waitSampling()
      cd.deassertReset()
      cd.waitSampling

      for (i <- 1 to 10) {
        println(s"${dut.io.data.map { _.toBigInt }}")
        cd.waitSampling()
      }
    }
}

case class DacSimple() extends Component {
  ClockDomain.current.renamePulledWires("clk500m", "rst500m")
  ClockDomain.current.readResetWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst500m rst")
  ClockDomain.current.readResetWire.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  ClockDomain.current.readClockWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk500m clk")
  ClockDomain.current.readClockWire.addAttribute(
    "X_INTERFACE_PARAMETERS",
    "FREQ_HZ 500000000, ASSOCIATED_BUSIF DAC0_AXIS:DAC1_AXIS:DAC2_AXIS:DAC3_AXIS:ADC0_AXIS:ADC1_AXIS:ADC2_AXIS:ADC3_AXIS, ASSOCIATED_RESET rst500m"
  )

  val dac = List.fill(4)(master port Stream(Bits(16 * 16 bits)))
  dac.zipWithIndex.foreach { case (d, id) =>
    Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }
  val adc = List.fill(4)(slave port Stream(Bits(4 * 16 bits)))
  adc.zipWithIndex.foreach { case (d, id) =>
    Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := False
  }

  val en = in port Bool()
  val enReg = RegNext(en)
  dac(0).valid := enReg

  val sqGen = SquareGenerator(4)
  // val sqGen = SquareGeneratorHighFreq()
  val dacInvertMsb =
    for (d <- sqGen.io.data) yield {
      val invertMsb = d.asBits ^ B(1 << (d.getBitsWidth - 1), d.getBitsWidth bits)
      invertMsb
    }
  val dac0InReg = RegNext(dacInvertMsb.asBits)
  dac(0).payload := dac0InReg

  for (i <- 1 until 4) {
    dac(i).valid := False
    dac(i).payload := B(0, 256 bits)
  }

  val ledR = out Bool ()
  val ledB = out Bool ()

  ledB := en

  val timer = Reg(UInt(30 bits))
  timer := timer + U(1)
  ledR := timer.msb
}

object GenDacTester extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./riscq-vivado/dac-tester",
    romReuse = true
  ).generate(DacTester())
}

object GenDacSimple extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./riscq-vivado/dac-simple",
    romReuse = true
  ).generate(DacSimple())
}

case class LedOn() extends Component {
  val led = out Bool ()

  led := True
}

object LedOnTest extends App {
  SpinalVerilog(LedOn())
}

case class LedFlash() extends Component {
  val led = out Bool ()
  val timer = Reg(UInt(23 bits)) init 0
  timer := timer + 1

  led := timer.msb
}

case class FifoCcTester() extends Component {
  val qubitNum = 2
  ClockDomain.current.renamePulledWires("clk500m", "rst500m")
  ClockDomain.current.readResetWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst500m rst")
  ClockDomain.current.readResetWire.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  ClockDomain.current.readClockWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk500m clk")
  ClockDomain.current.readClockWire.addAttribute(
    "X_INTERFACE_PARAMETERS",
    "FREQ_HZ 500000000, ASSOCIATED_BUSIF DAC0_AXIS:DAC1_AXIS:DAC2_AXIS:DAC3_AXIS:ADC0_AXIS:ADC1_AXIS:ADC2_AXIS:ADC3_AXIS, ASSOCIATED_RESET rst500m"
  )

  // cd100m
  val clk100m = in Bool ()
  val rst100m = in Bool ()
  val cd100m = ClockDomain(clk100m, rst100m)
  rst100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst100m rst")
  rst100m.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  clk100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk100m clk")
  clk100m.addAttribute("X_INTERFACE_PARAMETERS", "FREQ_HZ 100000000, ASSOCIATED_BUSIF S_AXIS, ASSOCIATED_RESET rst100m")

  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )

  val axi = cd100m(slave(Axi4(axiConfig)))

  // cpu instruction bus

  // instruction memory for qubic
  val pcOffset = 0x80000000L
  val pcAxiOffset = 0x01000000L
  val memBits = 128

  val mem = Mem(Bits(memBits bits), wordCount = 1024)
  val memTlPort = mem.readWriteSyncPort(maskWidth = memBits / 8)
  val memTlFiber = TileLinkMemReadWriteFiber(memTlPort, withOutReg = false)
  // val iBus = Node.down()
  // memTlFiber.up at pcOffset of iBus
  // val p = CachelessBusParam(32, 128, 2, false)
  // val ibus = slave(CachelessBus(p))
  // val ibusLogic = Fiber build new Area {
  //   val node = iBus
  //   val bridge = new CachelessBusToTilelink(ibus)
  //   // master(bridge.down)
  //   node.m2s.forceParameters(bridge.m2sParam)
  //   node.s2m.supported.load(tilelink.S2mSupport.none())
  //   node.bus.component.rework(node.bus << bridge.down)
  // }

  val cd100mLogic = new ClockingArea(cd100m) {
    val shareBus = Node()
    val bridge = new Axi4ToTilelinkFiber(32, 4)
    bridge.up load axi
    shareBus at 0 of bridge.down
    Axi4VivadoHelper.addInference(axi, "S_AXIS")

    val iMemWa = WidthAdapter()
    val iMemPipe = TileLinkPipeFiber(StreamPipe.FULL_KEEP)
    iMemWa.up at pcAxiOffset of shareBus
    iMemPipe.up at 0 of iMemWa.down
    memTlFiber.up at 0 of iMemPipe.down
  }

  val dac = List.fill(qubitNum * 2)(master port Stream(Bits(16 * 16 bits)))
  dac.zipWithIndex.foreach { case (d, id) =>
    Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }
  val adc = List.fill(qubitNum * 2)(slave port Stream(Bits(4 * 16 bits)))
  adc.zipWithIndex.foreach { case (d, id) =>
    Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := True
  }

  val addr = Reg(UInt(12 bit)) init 0
  addr := addr + U(1)
  val ioLogic = Fiber build new Area {
    for (i <- 0 until qubitNum * 2) {
      dac(i).payload := B(0, 256 bit)
      dac(i).valid := False
    }
  }
}



object LedFlashTest extends App {
  SpinalVerilog(LedFlash())
}


object GenFifoCcTester extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./riscq-vivado/fifocc-tester/rtl",
    romReuse = true
  ).generate(FifoCcTester())
}

object TestDacSimple extends App {
  SimConfig.compile{
    val dut = DacSimple()
    dut
  }.doSim{ dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)

    for(i <- 0 until 20) {
      // println(s"${dut.dac(0).payload.toBigInt.toString(2).reverse.padTo('0', 256).reverse.grouped(16).map(Integer.parseInt(_, 2)).toList}")
      println(s"${dut.dac(0).payload.toBigInt.toString(2).reverse.padTo(256, '0').reverse.grouped(16).map(BigInt(_, 2)).toList}")
      cd.waitRisingEdge()
    }
  }
}