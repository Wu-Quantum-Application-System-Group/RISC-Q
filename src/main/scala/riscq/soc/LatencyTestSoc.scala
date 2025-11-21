package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.fabric._
import scala.collection.mutable.ArrayBuffer
import riscq._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import riscq.memory.DualClockRam
import scala.collection.mutable.LinkedHashMap
import spinal.lib.bus.misc.SingleMapping
import riscq.misc.VivadoClkHelper
import riscq.pulse.AddTree
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber

case class LatencyTestSoc() extends Component {
  val io = RiscqZcu216SocPorts(gtNum = 0)

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)

  // cd500m
  io.dspClk.setName("dspClk")
  io.dspRst.setName("dspRst")
  val dspCd = ClockDomain(io.dspClk, io.dspRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspRst, 500000000)

  // memory map for axi host bus
  // 0 - 1 << 20: riscq core memory
  // 1 << 25 - 2 * 1 << 25: pulse memory
  // 2 * 1 << 25 - 3 * 1 << 25: readout map
  // 3 * 1 << 25 - 4 * 1 << 25: reset/control signal

  // memory space for each riscq core: 1 << 16
  val riscqCoreMemOffset = 0x0
  val riscqCoreMemSpaceSize = 1 << 16

  // memory space for pulse memory of each core: 1 << 18 -> at most 128 channels for 1 << 24
  val pulseMemOffset = 1 << 25
  val pulseMemSpaceSize = 1 << 18

  val readoutBufOffset = 2 * (1 << 25)

  // control space
  val hostCtrlOffset = 3 * (1 << 25)

  // blockSize is the maximal bytes that can be transfered in a transaction
  // slotsCount is the number of sources
  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load io.axi
  val hostBus = Node()
  hostBus at 0 of bridge.down

  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  for(i <- 1 until 16) {
    io.dac(i).payload := 0
  }

  val hostEnable = Bool()
  val dacEnable = dspCd(Reg(Bool()) init False)
  val dacData = Vec.fill(16)(UInt(16 bits))
  for(i <- 0 until 8) {
    dacData(i) := U(0x8000)
  }
  for(i <- 8 until 16) {
    dacData(i) := U(0x7fff)
  }
  io.dac(0).payload := dacEnable.mux(dacData.asBits, B(0, 16 * 16 bits))

  val counter = dspCd(Reg(UInt(10 bits)) init 0)
  when(dacEnable) {
    counter := counter + 1
  }
  when(counter === 1023) {
    dacEnable := False
    counter := 0
  }
  when(dspCd(BufferCC(hostEnable).rise())) {
    dacEnable := True
  }

  val readoutBufFiber = hostCd(ReadoutBufFiber(1, 64, 1024, hostCd, dspCd))
  readoutBufFiber.up at SizeMapping(readoutBufOffset, 1 << 24) of hostBus
  val rbArea =  new ClockingArea(dspCd) {
    val readoutBuffer = readoutBufFiber.readoutBufs(0)
    val addr = Reg(readoutBuffer.fastPort.address) init 0
    val valid = dacEnable
    // valid.simPublic()
    when(valid) {
      addr := addr + 1
    }
    when(dacEnable.rise()) {
      addr := 0
    }

    readoutBuffer.fastPort.enable := True
    readoutBuffer.fastPort.mask.setAllTo(True)
    readoutBuffer.fastPort.address := addr
    readoutBuffer.fastPort.write := valid
    readoutBuffer.fastPort.wdata := io.adc(12).payload.asBits
  }


  val hostCtrlDriver = MemMapDriverFiber(addressWidth = 10, dataWidth = 32, driveProc = { factory =>
    factory.drive(hostEnable, 0)
  })
  hostCtrlDriver.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  hostCtrlDriver.up at SizeMapping(hostCtrlOffset, 1 << 24) of hostBus
}

object GenLatencyTestSoc extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    LatencyTestSoc()
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    riscq.misc.ClockInterface()
  )
}