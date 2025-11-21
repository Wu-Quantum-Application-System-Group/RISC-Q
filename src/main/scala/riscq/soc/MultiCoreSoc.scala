package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber
import scala.collection.mutable.ArrayBuffer
import riscq._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.eda.bench.Rtl
import riscq.memory.DualClockRam
import riscq.misc.TileLinkMemReadWriteFiber
import spinal.lib.eda.bench.Bench
import riscq.misc.XilinxRfsocTarget
import scala.collection.mutable.LinkedHashMap
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.misc.PathTracer
import riscq.misc.VivadoClkHelper
import riscq.pulse.AddTree

case class MultiCoreSoc(
    qubitNum: Int,
    dacMap: Map[(Int, Int), Int], // (core id, channel id) -> dac id
    adcMap: Map[Int, Int], // core id -> adc id
    dacNum: Int = 16,
    adcNum: Int = 16,
    withTest: Boolean = false
) extends Component {
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

  // control space
  val hostCtrlOffset = 3 * (1 << 25)

  // blockSize is the maximal bytes that can be transfered in a transaction
  // slotsCount is the number of sources
  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load io.axi
  val hostBus = Node()
  hostBus at 0 of bridge.down

  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // val pulseMemWa = WidthAdapter()
  // pulseMemWa.up at SizeMapping(pulseMemOffset, 1 << 25) of hostBus
  val pulseMemBus = Node()
  pulseMemBus at SizeMapping(pulseMemOffset, 1 << 25) of hostBus

  val riscqMemBus = Node()
  riscqMemBus at SizeMapping(riscqCoreMemOffset, 1 << 25) of hostBus
  riscqMemBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val riscqReset = Bool()
  val riscqCd = ClockDomain(dspCd.readClockWire, riscqReset)
  val riscqArea = new ClockingArea(dspCd) {
    val refTime = Reg(UInt(64 bit)) init 0
    refTime := refTime + 1

    val timeOffset = Reg(UInt(64 bit)) init 0

    val syncTime = RegNext(refTime + timeOffset)
    val time = RegNext(syncTime(0, 32 bits))
    time.addAttribute("MAX_FANOUT", 16)

    val fromHost = Reg(Bits(32 bit)) init 0

    val params = RiscqParams()
    params.withTest = withTest

    val riscqCores = List.fill(qubitNum)(
      RiscqRfFiber(
        plugins = params.getPlugins().plugins,
        dspCd = dspCd,
        hostCd = hostCd,
        riscqCd = riscqCd,
        time = time,
        fromHost = fromHost,
        dacChannels = 2,
        adcChannels = 1,
        memDepth = 1024,
        memWidth = 32,
        fifoNum = 2
      )
    )
    riscqCores.foreach { riscq =>
      riscq.riscqFiber.riscq.addAttribute("KEEP_HIERARCHY", "TRUE")
      riscq.rfArea.pgs.foreach { pg => pg.addAttribute("KEEP_HIERARCHY", "TRUE") }
    }
    for ((riscq, i) <- riscqCores.zipWithIndex) {
      val bridgeNode = Node()
      bridgeNode at SizeMapping(riscqCoreMemSpaceSize * i, riscqCoreMemSpaceSize) of riscqMemBus
      riscq.iMemPortArb at 0 of bridgeNode
      riscq.pulseMemFiber.up at SizeMapping(pulseMemSpaceSize * i, pulseMemSpaceSize) of pulseMemBus
    }

    for (dacId <- 0 until dacNum) {
      val channels = dacMap.filter { case (_, id) => id == dacId }.toList.map { _._1 }
      val pulses = channels.map { case (coreId, channelId) =>
        riscqCores(coreId).rfArea.pgs(channelId).io.pulse.payload
      }

      val dacPayload = cloneOf(io.dac(dacId).payload)
      io.dac(dacId).payload := dacPayload
      if (pulses.isEmpty) {
        dacPayload := 0
      } else if (pulses.size == 1) {
        dacPayload := RegNext(pulses.head.map(_.r).asBits())
      } else {
        val addTrees = (0 until 16).map { i => AddTree(pulses.map { p => p(i).r.asSInt }.toList) }
        val pulseSums = addTrees.map { _.sum }
        dacPayload := pulseSums.asBits()
      }
    }

    for ((coreId, adcId) <- adcMap) {
      (riscqCores(coreId).rfArea.rds(0).io.adc zip io.adc(adcId).payload.subdivideIn(16 bits)).foreach { case (o, i) =>
        o.r.assignFromBits(RegNext(RegNext(i))) // 1 RegNext is too little, 3 RegNexts are too much
        o.i := o.i.getZero
      }
    }
  }

  val riscqResetHostCd = Bool()
  val bufferedHostRiscqReset = dspCd(BufferCC(riscqResetHostCd, 5))
  val riscqResetReg = dspCd(Delay(io.dspRst, 5))
  // riscqResetReg.addAttribute("MAX_FANOUT", 128)
  if (withTest) {
    riscqReset := riscqResetReg | io.dspRst
  } else {
    riscqReset := riscqResetReg
  }

  val timeOffset = Reg(UInt(64 bit)) init 0
  riscqArea.timeOffset := dspCd(BufferCC(timeOffset))

  val fromHost = Reg(Bits(32 bit)) init 0
  riscqArea.fromHost := dspCd(BufferCC(fromHost))

  val hostCtrlDriver = MemMapDriverFiber(addressWidth = 10, dataWidth = 32, driveProc = { factory =>
    // for (i <- 0 until qubitNum) {
    //   factory.read(BufferCC(riscqArea.riscqCores(i).rfArea.rds(0).io.real, 5), i * 4 * 2)
    //   factory.read(BufferCC(riscqArea.riscqCores(i).rfArea.rds(0).io.imag, 5), i * 4 * 2 + 4)
    // }
    factory.drive(riscqResetHostCd, 0)
    factory.write(fromHost, 16)
    factory.write(timeOffset(0, 32 bits), 64)
    factory.write(timeOffset(32, 32 bits), 68)
  })
  hostCtrlDriver.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  hostCtrlDriver.up at SizeMapping(hostCtrlOffset, 1 << 24) of hostBus

  Fiber build new Area {
    riscqArea.riscqCores(0).dMemPortDec.bus.get.simPublic()
  }

}

object GenMultiCoreSoc extends App {
  val qubitNum = 8
  val dacMap = (0 until qubitNum).flatMap { i => List(((i, 0), 2*i), ((i, 1), 2*i+1)) }.toMap
  val adcMap = (0 until qubitNum).map { i => (i, i) }.toMap
  println(s"dacMap: $dacMap")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    MultiCoreSoc(
      qubitNum = qubitNum,
      dacMap = dacMap,
      adcMap = adcMap,
    )
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    riscq.misc.ClockInterface()
  )
}