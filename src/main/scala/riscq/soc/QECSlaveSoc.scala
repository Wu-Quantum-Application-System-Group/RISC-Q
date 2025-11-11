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
import riscq.network.GtPins
import riscq.network.Tiny6466Top
import riscq.network.BasicSync
import spinal.lib.blackbox.xilinx.s7.BUFG
import riscq.network.EmmetCoreTop

case class QECSlaveSoc(
    qubitNum: Int,
    dacMap: Map[(Int, Int), Int], // (core id, channel id) -> dac id
    adcMap: Map[Int, Int], // core id -> adc id
    syndromeXMap: Map[Int, Int], // core id -> syndrome x id
    syndromeZMap: Map[Int, Int], // core id -> syndrome z id
    withWhitebox: Boolean = false,
    dacNum: Int = 16,
    adcNum: Int = 16,
    distance: Int = 3,
    withTest: Boolean = false
) extends Component {
  val io = RiscqZcu216SocPorts(gtNum = 1)
  // val io = new Bundle {
  //   val dspClk = in Bool ()
  //   val dspExtRst = in Bool ()
  //   val axi = slave(Axi4(Axi4Config(32, 32, 2)))
  //   val gt = GtPins()
  //   val dac = List.fill(dacNum)(master port Stream(Bits(16 * 16 bits)))
  //   val adc = List.fill(adcNum)(slave port Stream(Bits(4 * 16 bits)))
  //   // val syncCopper = inout(Bool())
  //   val ledB = out Bool ()
  // }
  // riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  // io.gt.mgtrefclk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_P ")
  // io.gt.mgtrefclk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_N ")
  // io.adc.zipWithIndex.foreach { case (d, id) =>
  //   riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
  //   d.ready := True
  // }
  // io.dac.zipWithIndex.foreach { case (d, id) =>
  //   riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  // }

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)

  // cd500m
  io.dspClk.setName("dspClk")
  io.dspRst.setName("dspRst")
  // val dspCdNoRst = ClockDomain(io.dspClk)
  // val dspRst = dspCdNoRst(Reg(Bool()))
  // dspRst := dspCdNoRst(Delay(io.dspExtRst, 5))
  val dspRst = io.dspRst

  // val dspCd = BUFG.onReset(ClockDomain(io.dspClk, dspRst))
  val dspCd = ClockDomain(io.dspClk, dspRst)
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

  // val riscqMemBus = Node()
  // riscqMemBus at SizeMapping(riscqCoreMemOffset, 1 << 25) of hostBus
  // riscqMemBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // val pulseMemWa = WidthAdapter()
  // pulseMemWa.up at SizeMapping(pulseMemOffset, 1 << 25) of hostBus
  val pulseMemBus = Node()
  pulseMemBus at SizeMapping(pulseMemOffset, 1 << 25) of hostBus

  val riscqBridgeNodes = List.fill(qubitNum)(Node())
  riscqBridgeNodes.foreach { node => node.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL) }
  riscqBridgeNodes.foreach { node => node.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL) }
  riscqBridgeNodes.head at SizeMapping(riscqCoreMemOffset, 1 << 25) of hostBus
  for ((head, tail) <- riscqBridgeNodes zip riscqBridgeNodes.tail) {
    tail at riscqCoreMemSpaceSize of head
  }

  val riscqReset = Bool()
  // val riscqCd = BUFG.onReset(ClockDomain(dspCd.readClockWire, riscqReset))
  val riscqCd = ClockDomain(dspCd.readClockWire, riscqReset)
  val riscqArea = new ClockingArea(dspCd) {
    val refTime = Reg(UInt(64 bit)) init 0
    refTime := refTime + 1

    val timeOffset = Reg(UInt(64 bit)) init 0

    val syncTime = RegNext(refTime + timeOffset)
    // val time = Delay(syncTime(0, 32 bits), 5)
    val time = RegNext(syncTime(0, 32 bits))
    time.addAttribute("MAX_FANOUT", 16)

    val fromHost = Reg(Bits(32 bit)) init 0

    val params = RiscqParams()

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
        memWidth = 32
      )
    )
    riscqCores.foreach { riscq =>
      riscq.riscqFiber.riscq.addAttribute("KEEP_HIERARCHY", "TRUE")
      riscq.rfArea.pgs.foreach { pg => pg.addAttribute("KEEP_HIERARCHY", "TRUE") }
    }
    for ((riscq, i) <- riscqCores.zipWithIndex) {
      // val bridgeNode = Node()
      // bridgeNode at SizeMapping(riscqCoreMemSpaceSize * i, riscqCoreMemSpaceSize) of riscqMemBus
      // riscq.iMemPortArb at 0 of bridgeNode
      riscq.iMemPortArb at SizeMapping(0, riscqCoreMemSpaceSize) of riscqBridgeNodes(i)
      // riscq.pulseMemFiber.up at SizeMapping(pulseMemSpaceSize * i, pulseMemSpaceSize) of pulseMemWa.down
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
        o.r.assignFromBits(RegNext(i)) // 1 RegNext is too little, 3 RegNexts are too much
        o.i := o.i.getZero
      }
    }
  }

  // val qecMemOffset = 0x800000
  val qecMemOffset = 0x8000
  val syndromeOffset = 0x00000
  val qecAddrWidth = 4
  val syndromeXNum = syndromeXMap.size
  val syndromeZNum = syndromeZMap.size
  val txController = dspCd(QECSlaveTxController(syndromeXNum = syndromeXNum, syndromeZNum = syndromeZNum))
  val rxController = dspCd(QECSlaveRxController(errorXNum = 9, errorZNum = 9, distance = distance))

  val hostRiscqReset = Reg(Bool()) init True
  val qecMMLogic = new ClockingArea(riscqCd) {
    val syndromeAggregator = QECSlaveSyndromeAggregator(syndromeXNum = syndromeXNum, syndromeZNum = syndromeZNum)
    for ((i, j) <- syndromeZMap) {
      val syndFlow = Reg(syndromeAggregator.io.zIn(j))
      syndFlow >> syndromeAggregator.io.zIn(j)
      // val qecFiber = MemMapDriverFiber(
      //   driveProc = { factory =>
      //     factory.driveFlow(syndFlow, syndromeOffset)
      //   },
      //   addressWidth = qecAddrWidth,
      //   dataWidth = 32
      // )
      // qecFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
      // val riscqCore = riscqArea.riscqCores(i)
      // qecFiber.up at SizeMapping(qecMemOffset, 1 << qecAddrWidth) of riscqCore.dMemPortDec
      val riscqCore = riscqArea.riscqCores(i)
      riscqCore.memMapFiber.addMapping{ factory =>
        factory.driveFlow(syndFlow, syndromeOffset)
      }
    }
    for ((i, j) <- syndromeXMap) {
      val syndFlow = Reg(syndromeAggregator.io.xIn(j))
      syndFlow >> syndromeAggregator.io.xIn(j)
      // val qecFiber = MemMapDriverFiber(
      //   driveProc = { factory =>
      //     factory.driveFlow(syndFlow, syndromeOffset)
      //   },
      //   addressWidth = qecAddrWidth,
      //   dataWidth = 32
      // )
      // qecFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
      // val riscqCore = riscqArea.riscqCores(i)
      // qecFiber.up at SizeMapping(qecMemOffset, 1 << qecAddrWidth) of riscqCore.dMemPortDec
      val riscqCore = riscqArea.riscqCores(i)
      riscqCore.memMapFiber.addMapping{ factory =>
        factory.driveFlow(syndFlow, syndromeOffset)
      }
    }

    txController.io.syndromeX := syndromeAggregator.io.xOut.stage()
    txController.io.syndromeZ := syndromeAggregator.io.zOut.stage()

    val bufferedRxRiscqReset = dspCd(BufferCC(rxController.io.riscqReset, 5))
    val bufferedHostRiscqReset = dspCd(BufferCC(hostRiscqReset, 5))


    val riscqResetDelayed = dspCd(Delay(bufferedRxRiscqReset | bufferedHostRiscqReset, 4))
    riscqResetDelayed.addAttribute("MAX_FANOUT", 16)
    val riscqResetReg = dspCd(RegNext(riscqResetDelayed))
    // val riscqResetBUFG = BUFG.on(riscqResetDelayed)
    // val riscqResetReg = RegNext(riscqResetBUFG)
    riscqResetReg.addAttribute("MAX_FANOUT", 16)
    KeepAttribute(riscqResetReg)

    // riscqResetReg.addAttribute("MAX_FANOUT", 128)
    if (withTest) {
      riscqReset := riscqResetReg | io.dspRst
    } else {
      riscqReset := riscqResetReg
    }
  }

  val netCore = EmmetCoreTop(gtId = 0)
  netCore.io.gt <> io.gts(0)
  val netRstNHostCd = Bool()
  netCore.io.reset_n := netRstNHostCd
  val reset = hostCd.readResetWire
  val rxTmpCd = ClockDomain(netCore.io.rx_userclk)
  val rxResetCd = ClockDomain(netCore.io.rx_userclk, rxTmpCd(BufferCC(reset)))
  val txTmpCd = ClockDomain(netCore.io.tx_userclk)
  val txResetCd = ClockDomain(netCore.io.tx_userclk, txTmpCd(BufferCC(reset)))

  val rxRsp = netCore.io.rxRsp.toStream.queue(2, rxResetCd, dspCd).toFlow
  val rxRspHostCd = rxRsp.toStream.queue(2, riscqCd, hostCd).toReg
  rxController.io.rxRsp.valid := rxRsp.valid
  rxController.io.rxRsp.payload.assignFromBits(rxRsp.payload)
  val errorTime = dspCd(RegNextWhen(riscqArea.refTime, rxController.io.errorX.valid.rise()))

  val txCtlToTxCmd = txController.io.txCmd.queue(2, dspCd, txResetCd)
  val txCtlCmd = Stream(Bits(64 bits))
  txCtlCmd.arbitrationFrom(txCtlToTxCmd)
  txCtlCmd.payload := txCtlToTxCmd.payload.asBits

  val hostMsg = Stream(Bits(32 bits))
  val hostCmd = hostMsg.queue(2, hostCd, txResetCd)
  val hostToTxCmd = Stream(Bits(64 bits))
  hostToTxCmd.arbitrationFrom(hostCmd)
  hostToTxCmd.payload := B(0, 32 bits) ## hostCmd.payload

  val txCmd = txResetCd(StreamArbiterFactory().lowerFirst.onArgs(hostToTxCmd, txCtlCmd))
  txCmd >> netCore.io.txCmd

  val hostReadoutOffset = (2 * (1 << 25)) // 0x5000000
  val hostReadoutDriver = MemMapDriverFiber(addressWidth = 8, dataWidth = 32, driveProc = { factory =>
    for (i <- 0 until qubitNum) {
      factory.read(BufferCC(riscqArea.riscqCores(i).rfArea.rds(0).io.real), i * 4 * 2)
      factory.read(BufferCC(riscqArea.riscqCores(i).rfArea.rds(0).io.imag), i * 4 * 2 + 4)
    }
    factory.read(rxRspHostCd(0, 32 bits), 132)
    factory.read(rxRspHostCd(32, 32 bits), 136)
    // factory.read(BufferCC(riscqReset, 5), 140)
  })
  hostReadoutDriver.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  hostReadoutDriver.up at SizeMapping(hostReadoutOffset, 1 << 24) of hostBus

  val measureTime = dspCd(RegNextWhen(riscqArea.refTime, qecMMLogic.syndromeAggregator.io.xIn(0).valid.rise()))
  val txTime = dspCd(RegNextWhen(riscqArea.refTime, BufferCC(netCore.io.txCmd.valid).rise()))
  val rxTime = dspCd(RegNextWhen(riscqArea.refTime, BufferCC(netCore.io.rxRsp.valid).rise()))

  val fromHost = Reg(Bits(32 bit)) init 0
  riscqArea.fromHost := dspCd(BufferCC(fromHost))

  val timeOffset = Reg(UInt(64 bit)) init 0
  riscqArea.timeOffset := dspCd(BufferCC(timeOffset))

  val hostCtrlDriver = MemMapDriverFiber { factory =>
    factory.drive(netRstNHostCd, 0)
    factory.drive(hostRiscqReset, 4)
    factory.driveStream(hostMsg, 8)
    factory.drive(fromHost, 16)
    factory.read(BufferCC(netCore.io.txCmd.ready), 20)
    val rxData = BufferCC(rxResetCd(netCore.io.rxRsp.toReg))
    factory.read(rxData(0, 32 bits), 24)
    factory.read(BufferCC(txTime(0, 32 bits)), 32)
    factory.read(BufferCC(txTime(32, 32 bits)), 36)
    factory.read(BufferCC(rxTime(0, 32 bits)), 40)
    factory.read(BufferCC(rxTime(32, 32 bits)), 44)
    factory.read(BufferCC(measureTime(0, 32 bits)), 48)
    factory.read(BufferCC(measureTime(32, 32 bits)), 52)
    factory.read(BufferCC(errorTime(0, 32 bits)), 56)
    factory.read(BufferCC(errorTime(32, 32 bits)), 60)
    factory.write(timeOffset(0, 32 bits), 64)
    factory.write(timeOffset(32, 32 bits), 68)
  }

  hostCtrlDriver.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  hostCtrlDriver.up at SizeMapping(hostCtrlOffset, 1 << 24) of hostBus
}

// runs in the riscq cd
case class QECSlaveRxController(errorXNum: Int, errorZNum: Int, distance: Int) extends Component {
  val io = new Bundle {
    val rxRsp = slave Flow (QECMessage())
    val riscqReset = out port Bool()
    val errorX = master Flow(Vec.fill(errorXNum)(Bool()))
    val errorZ = master Flow(Vec.fill(errorZNum)(Bool()))
  }
  val riscqReset = Reg(Bool()) init False
  io.riscqReset := riscqReset

  val errorX = Reg(Flow(Vec.fill(errorXNum)(Bool())))
  val errorZ = Reg(Flow(Vec.fill(errorZNum)(Bool())))
  errorX.valid := False
  errorZ.valid := False
  io.errorX := errorX
  io.errorZ := errorZ

  when(io.rxRsp.valid) {
    when(io.rxRsp.payload.header === QECMessage.HEADER_RESET) {
      riscqReset := io.rxRsp.payload.data(0)
    }
    when(io.rxRsp.payload.header === QECMessage.HEADER_ERROR) {
      errorZ.valid := True
      errorX.valid := True
      errorZ.payload.assignFromBits(io.rxRsp.payload.data(0, errorZNum bits))
      errorX.payload.assignFromBits(io.rxRsp.payload.data(distance * distance, errorXNum bits))
    }
  }
}

// runs in the dsp cd
case class QECSlaveSyndromeAggregator(syndromeXNum: Int, syndromeZNum: Int) extends Component {
  val io = new Bundle {
    val xIn = in port Vec.fill(syndromeXNum)(slave Flow (Bool()))
    val zIn = in port Vec.fill(syndromeZNum)(slave Flow (Bool()))
    val xOut = master Flow (Bits(syndromeXNum bits))
    val zOut = master Flow (Bits(syndromeZNum bits))
  }
  val xBuf = Vec.fill(syndromeXNum)(Reg(Flow(Bool())))
  val zBuf = Vec.fill(syndromeZNum)(Reg(Flow(Bool())))

  (xBuf zip io.xIn).foreach { case (o, i) =>
    when(i.valid) {
      o.valid := True
      o.payload := i.payload
    }
  }
  (zBuf zip io.zIn).foreach { case (o, i) =>
    when(i.valid) {
      o.valid := True
      o.payload := i.payload
    }
  }

  val xAllValid = xBuf.map(_.valid).andR
  val zAllValid = zBuf.map(_.valid).andR
  io.xOut.valid := xAllValid
  io.xOut.payload := xBuf.map(_.payload).asBits
  io.zOut.valid := zAllValid
  io.zOut.payload := zBuf.map(_.payload).asBits

  when(io.xOut.fire) {
    xBuf.foreach { b => b.valid := False }
  }
  when(io.zOut.fire) {
    zBuf.foreach { b => b.valid := False }
  }
}

// runs in the riscq cd
case class QECSlaveTxController(
    syndromeXNum: Int,
    syndromeZNum: Int,
) extends Component {
  val io = new Bundle {
    val txCmd = master Stream (QECMessage())
    val syndromeX = slave Flow (Bits(syndromeXNum bits))
    val syndromeZ = slave Flow (Bits(syndromeZNum bits))
  }

  val syndromeX = Reg(Flow(Bits(syndromeXNum bits)))
  syndromeX.valid init False
  val syndromeZ = Reg(Flow(Bits(syndromeXNum bits)))
  syndromeZ.valid init False

  when(io.syndromeX.fire) {
    syndromeX.valid := True
    syndromeX.payload := io.syndromeX.payload
  }
  when(io.syndromeZ.fire) {
    syndromeZ.valid := True
    syndromeZ.payload := io.syndromeZ.payload
  }

  val message = Reg(Flow(QECMessage()))
  message.data := 0
  message.header := QECMessage.HEADER_SYNDROME
  message.valid init False
  when(syndromeX.valid && syndromeZ.valid) {
    message.valid := True
    message.data(0, syndromeXNum + syndromeZNum bits) := syndromeX.payload ## syndromeZ.payload
    syndromeX.valid := False
    syndromeZ.valid := False
  }

  io.txCmd.valid := message.valid
  io.txCmd.payload := message.payload
  when(io.txCmd.fire) {
    message.valid := False
  }
}

object GenQECSoc extends App {
  // val qubitNum = 8
  // val dacMap = (0 until qubitNum).flatMap { i => List(((i, 0), 0), ((i, 1), i + 1)) }.toMap
  val qubitNum = 10
  val dacMap = (0 until qubitNum).flatMap { i => List(((i, 0), 0), ((i, 1), i + 1 )) }.toMap
  val adcMap = (0 until qubitNum).map { i => (i, 12) }.toMap
  // val adcMap = (0 until qubitNum).map { i => (i, i / 7 + 12) }.toMap
  val syndromeXMap = (4 until 8).map { i => (i, i - 4) }.toMap
  val syndromeZMap = (0 until 4).map { i => (i, i) }.toMap
  // val dacMap = (0 until qubitNum).flatMap{i => List(((i, 0), i), ((i, 1), i))}.toMap
  // val adcMap = (0 until qubitNum).map{i => (i, i)}.toMap
  println(s"dacMap: $dacMap")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    QECSlaveSoc(
      qubitNum = qubitNum,
      dacMap = dacMap,
      adcMap = adcMap,
      syndromeXMap = syndromeXMap,
      syndromeZMap = syndromeZMap
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
