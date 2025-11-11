package riscq.soc

import spinal.core._
import spinal.lib._
import helios.{HeliosCore, HeliosParams}
import riscq.network.Tiny6466Top
import riscq.network.EmmetCoreTop
import riscq.network.GtPins
import riscq.misc.Axi4VivadoHelper
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber
import riscq.network.AxiToTileLinkDriver
import riscq.misc.VivadoClkHelper
import riscq.network.BasicSync
import spinal.lib.bus.misc.SizeMapping

case class HeliosCoreWrapper(params: HeliosParams) extends Component {
  val io = new Bundle {
    val meas_in = slave Stream (Vec.fill(params.grid_width_x, params.grid_width_z)(Bool()))
    val output = out port Flow(Vec.fill(params.code_distance, params.code_distance)(Bool()))
  }
  val helios = new HeliosCore(params)
  val counter = Reg(UInt(log2Up(params.code_distance + 1) bits)) init 0
  val prev_in = RegNextWhen(io.meas_in.payload, io.meas_in.fire)
  prev_in.foreach { row => row.foreach { b => b init False } }
  when(io.meas_in.fire) {
    counter := counter + 1
  }
  when(counter === params.code_distance) {
    counter := 0
    prev_in.foreach { row => row.foreach { b => b := False } }
  }
  val xor_in = cloneOf(io.meas_in.payload)
  for (i <- 0 until params.grid_width_x; j <- 0 until params.grid_width_z) {
    xor_in(i)(j) := io.meas_in.payload(i)(j) ^ prev_in(i)(j)
  }
  val xorStream = cloneOf(io.meas_in)
  xorStream.valid := io.meas_in.valid
  xorStream.payload := xor_in
  io.meas_in.ready := xorStream.ready
  helios.meas_in << xorStream
  io.output << helios.output
}

case class QECMasterSoc(
    distance: Int = 3,
    nodeNum: Int = 1,
    syndromeXMap: Map[(Int, Int), (Int, Int)], // syndromeX (x, y) -> (node id, msg pos)
    syndromeZMap: Map[(Int, Int), (Int, Int)], // syndromeZ (x, y) -> (node id, msg pos)
    errorMap: Map[(Int, Int), (Int, Int)] // error grid (x, y) -> (node id, msg pos)
) extends Component {
  val io = RiscqZcu216SocPorts(gtNum = 2)
  io.noDac()

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  val reset = hostCd.readResetWire
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)
  // cd500m
  io.dspClk.setName("dspClk")
  io.dspRst.setName("dspRst")
  val dspCd = ClockDomain(io.dspClk, io.dspRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspRst, 500000000)

  val decoderReset = Bool()
  val decoderCd = ClockDomain(dspCd.readClockWire, decoderReset)

  val netResetN = Reg(Bool()) init False
  val netCores = List.tabulate(nodeNum)(i => EmmetCoreTop(gtId = i))
  for (i <- 0 until nodeNum) {
    netCores(i).io.gt <> io.gts(i)
    netCores(i).io.reset_n := netResetN
  }
  val txCdTmp = List.tabulate(nodeNum)(i => ClockDomain(netCores(i).io.tx_userclk))
  val txCds = List.tabulate(nodeNum)(i => ClockDomain(netCores(i).io.tx_userclk, txCdTmp(i)(BufferCC(reset))))
  val rxCdTmp = List.tabulate(nodeNum)(i => ClockDomain(netCores(i).io.rx_userclk))
  val rxCds = List.tabulate(nodeNum)(i => ClockDomain(netCores(i).io.rx_userclk, rxCdTmp(i)(BufferCC(reset))))

  val txController = decoderCd(QECMasterTxController(nodeNum, distance, errorMap))
  for (i <- 0 until nodeNum) {
    val txCmd = txController.io.txCmdOuts(i).queue(2, decoderCd, txCds(i))
    netCores(i).io.txCmd.arbitrationFrom(txCmd)
    netCores(i).io.txCmd.payload.assignFromBits(txCmd.payload.asBits)
  }

  val rxController = decoderCd(QECMasterRxController(nodeNum, distance, syndromeXMap, syndromeZMap))
  for (i <- 0 until nodeNum) {
    val rxRsp = netCores(i).io.rxRsp.toStream.queue(2, rxCds(i), decoderCd).toFlow
    rxController.io.rxRsps(i).valid := rxRsp.valid
    rxController.io.rxRsps(i).payload.assignFromBits(rxRsp.payload.asBits)
  }

  val heliosParams = HeliosParams(
    code_distance = distance,
    max_delay = 3,
    neighbor_count = 6
  )
  // decoderReset.addAttribute("MAX_FANOUT", 128)
  
  val xDecoder = decoderCd(new HeliosCoreWrapper(heliosParams))
  xDecoder.helios.addAttribute("KEEP_HIERARCHY", "TRUE")
  val zDecoder = decoderCd(new HeliosCoreWrapper(heliosParams))
  zDecoder.helios.addAttribute("KEEP_HIERARCHY", "TRUE")

  xDecoder.io.meas_in << rxController.io.syndromeX
  zDecoder.io.meas_in << rxController.io.syndromeZ

  val hostXErrors = xDecoder.io.output.toStream.queue(2, decoderCd, hostCd).toFlow
  val hostZErrors = zDecoder.io.output.toStream.queue(2, decoderCd, hostCd).toFlow
  txController.io.xErrors << xDecoder.io.output
  txController.io.zErrors << zDecoder.io.output

  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load io.axi
  val hostBus = Node()
  hostBus at 0 of bridge.down

  val hostCmd = Stream(QECMessage())
  val hostCmdBin = hostCmd.payload.asBits
  val hostCmdHigh = Reg(Bits(32 bit)) init 0
  val hostCmdLow = Flow(Reg(Bits(32 bit)))
  hostCmd.valid := hostCmdLow.valid
  hostCmd.payload.assignFromBits(hostCmdHigh ## hostCmdLow.payload)
  hostCmd.queue(2, hostCd, decoderCd) >> txController.io.hostCmd

  val doReadTime = Flow(False)
  val dspArea = new ClockingArea(dspCd) {
    val time = Reg(UInt(64 bit)) init 0
    time := time + 1
    val readTime = RegNextWhen(time, BufferCC(doReadTime.valid))
    val decodeTime = RegNextWhen(time, xDecoder.io.output.valid.rise())
  }

  // val sync = dspCd(BasicSync())
  // sync.io.time := dspArea.time
  // sync.io.syncCopper := io.syncCopper
  // val syncDoSync = Bool()
  // val syncSlave = Bool()
  // sync.io.doSync := dspCd(BufferCC(syncDoSync, 5))
  // sync.io.slave := dspCd(BufferCC(syncSlave, 5))


  val txValids = netCores.zipWithIndex.map{case (core, i) => dspCd(BufferCC(core.io.txCmd.valid).rise())}
  // val txValidCCs = List.tabulate(nodeNum)(i => dspCd(netCores(i).io.txCmd.valid))
  val txValid = txValids.orR
  val txTime = dspCd(RegNextWhen(dspArea.time, txValid))

  val xRxValid = dspCd(BufferCC(netCores(1).io.rxRsp.valid).rise())
  val xRxTime = dspCd(RegNextWhen(dspArea.time, xRxValid))

  val rxValids = netCores.zipWithIndex.map{case (core, i) => dspCd(BufferCC(core.io.rxRsp.valid).rise())}
  val rxValid = rxValids.orR
  val rxTime = dspCd(RegNextWhen(dspArea.time, rxValid))

  // val sampleSyndTime = dspCd(BufferCC(rxController.io.syndromeX.valid))
  // val sampleSyndFlow = Flow(Bits(0 bit))
  // sampleSyndFlow.valid := rxController.io.syndromeX.valid
  // val sampleSyndTime = sampleSyndFlow.toStream.queue(4, hostCd, dspCd).toFlow.fire
  // val syndTime = dspCd(RegNextWhen(dspArea.time, sampleSyndTime))
  val syndTime = dspCd(RegNextWhen(dspArea.time, xDecoder.io.meas_in.valid.rise()))

  val hostDecoderReset = Bool()
  decoderReset := decoderCd(BufferCC(hostDecoderReset))
  val driver = MemMapDriverFiber { factory =>
    factory.drive(netResetN, 0)
    factory.drive(hostDecoderReset, 4)
    factory.driveFlow(hostCmdLow, 8)
    factory.write(hostCmdHigh, 12)
    factory.read(BufferCC(txTime(0, 32 bits)), 32)
    factory.read(BufferCC(txTime(32, 32 bits)), 36)
    factory.read(BufferCC(rxTime(0, 32 bits)), 40)
    factory.read(BufferCC(rxTime(32, 32 bits)), 44)
    val xErrors = hostXErrors.toReg.asBits
    val zErrors = hostZErrors.toReg.asBits
    factory.read(xErrors, 64)
    factory.read(zErrors, 68)
    factory.read(BufferCC(xDecoder.io.output.valid), 72)
    factory.read(BufferCC(xDecoder.io.output.valid), 76)
    factory.read(BufferCC(dspArea.decodeTime(0, 32 bits), 5), 80)
    factory.read(BufferCC(syndTime(0, 32 bits), 5), 84)
    factory.read(BufferCC(xRxTime(0, 32 bits), 5), 88)
    factory.driveFlow(doReadTime, 100)
    factory.read(BufferCC(dspArea.readTime(0, 32 bits)), 104)
    factory.read(BufferCC(dspArea.readTime(32, 32 bits)), 108)
  }
  val hostCtrlOffset = 3 * (1 << 25)
  driver.up at SizeMapping(hostCtrlOffset, 1 << 24) of hostBus
}

case class QECMasterErrorAggregator(
  nodeNum: Int, 
  distance: Int, 
  errorMap: Map[(Int, Int), (Int, Int)]// error grid (x, y) -> (node id, msg pos)
  ) extends Component {
  // assert(nodeNum == 1)
  assert(distance == 3)
  val io = new Bundle {
    val xErrors = in port Vec.fill(distance, distance)(Bool())
    val zErrors = in port Vec.fill(distance, distance)(Bool())
    val txPayloads = out port Vec.fill(nodeNum)(QECMessage.DATA_TYPE())
  }
  for (i <- 0 until nodeNum) {
    io.txPayloads(i) := io.txPayloads(i).getZero
    io.txPayloads(i).allowOverride()
  }
  for (i <- 0 until distance; j <- 0 until distance) {
    val (ix, jx) = errorMap(i, j)
    io.txPayloads(ix)(jx + (distance * distance)) := io.xErrors(i)(j)
    val (iz, jz) = errorMap(i, j)
    io.txPayloads(iz)(jz) := io.zErrors(i)(j)
  }
}

case class QECMasterSyndromeAggregator(
    nodeNum: Int,
    distance: Int,
    syndromeXMap: Map[(Int, Int), (Int, Int)], // (syndrome grid x, syndrome grid z) -> (node id, msg pos)
    syndromeZMap: Map[(Int, Int), (Int, Int)]
) extends Component {
  // assert(nodeNum == 2)
  assert(distance == 3)
  val syndromeNum = distance * distance - 1
  val gridWidthX = distance + 1
  val gridWidthZ = distance / 2
  val io = new Bundle {
    val msgDataIn = in port Vec.fill(nodeNum)(QECMessage.DATA_TYPE())
    val syndromeX = out port Vec.fill(distance + 1, distance / 2)(Bool())
    val syndromeZ = out port Vec.fill(distance + 1, distance / 2)(Bool())
  }

  for (i <- 0 until gridWidthX; j <- 0 until gridWidthZ) {
    val (jx, kx) = syndromeXMap(i, j)
    io.syndromeX(i)(j) := io.msgDataIn(jx)(kx)
    val (jz, kz) = syndromeZMap(i, j)
    io.syndromeZ(i)(j) := io.msgDataIn(jz)(kz)
  }
}

case class QECMasterTxController(nodeNum: Int = 2, distance: Int = 3, errorMap: Map[(Int, Int), (Int, Int)]) extends Component {
  // assert(nodeNum == 2)
  assert(distance == 3)
  val io = new Bundle {
    val xErrors = in port Flow(Vec.fill(distance, distance)(Bool()))
    val zErrors = in port Flow(Vec.fill(distance, distance)(Bool()))
    val txCmdOuts = List.fill(nodeNum)(master Stream (QECMessage()))
    val hostCmd = slave Stream (QECMessage())
  }

  val hostCmd = Stream(QECMessage())
  hostCmd.valid := io.hostCmd.valid
  hostCmd.payload.header := io.hostCmd.payload.header
  hostCmd.payload.data := io.hostCmd.payload.data
  io.hostCmd.ready := hostCmd.ready
  val xErrorBuf = Reg(io.xErrors)
  xErrorBuf.valid init False
  val zErrorBuf = Reg(io.zErrors)
  zErrorBuf.valid init False
  val allValid = xErrorBuf.valid && zErrorBuf.valid

  when(io.xErrors.valid) {
    xErrorBuf.valid := True
    xErrorBuf.payload := io.xErrors.payload
  }
  when(io.zErrors.valid) {
    zErrorBuf.valid := True
    zErrorBuf.payload := io.zErrors.payload
  }
  when(allValid) {
    xErrorBuf.valid := False
    zErrorBuf.valid := False
  }

  val errorCmds = List.fill(nodeNum)(Stream(QECMessage()))
  val errorFired = List.fill(nodeNum)(Reg(Bool()))
  val errorAggregator = QECMasterErrorAggregator(nodeNum, distance, errorMap)
  errorAggregator.io.xErrors := xErrorBuf.payload
  errorAggregator.io.zErrors := zErrorBuf.payload

  for (i <- 0 until nodeNum) {
    errorFired(i) setWhen (errorCmds(i).fire) clearWhen (!allValid)
    errorCmds(i).payload.data := errorAggregator.io.txPayloads(i)
    errorCmds(i).payload.header := QECMessage.HEADER_ERROR
    errorCmds(i).valid := allValid && !errorFired(i)
  }

  val hostCmdFork = StreamFork(hostCmd, nodeNum)
  for (i <- 0 until nodeNum) {
    val arbiter = StreamArbiterFactory().lowerFirst.onArgs(errorCmds(i), hostCmdFork(i))
    io.txCmdOuts(i) << arbiter
  }
}

case class QECMasterRxController(
    nodeNum: Int = 2,
    distance: Int = 3,
    syndromeXMap: Map[(Int, Int), (Int, Int)],
    syndromeZMap: Map[(Int, Int), (Int, Int)]
) extends Component {
  // assert(nodeNum == 2)
  assert(distance == 3)
  val syndromeNum = distance * distance - 1
  val gridWidthX = distance + 1
  val gridWidthZ = distance / 2
  val io = new Bundle {
    val rxRsps = Vec.fill(nodeNum)(slave Flow (QECMessage()))
    val syndromeX = master Stream (Vec.fill(gridWidthX, gridWidthZ)(Bool()))
    val syndromeZ = master Stream (Vec.fill(gridWidthX, gridWidthZ)(Bool()))
  }
  val msgBuf = Vec.fill(nodeNum)(Reg(Flow(QECMessage())))
  msgBuf.foreach { b => b.valid init False }
  (io.rxRsps zip msgBuf) foreach { case (r, b) =>
    when(r.valid && r.payload.header === QECMessage.HEADER_SYNDROME) {
      b.valid := True
      b.payload := r.payload
    }
  }

  val xBuf = Reg(Flow(Vec.fill(gridWidthX, gridWidthZ)(Bool())))
  xBuf.valid init False
  val zBuf = Reg(Flow(Vec.fill(gridWidthX, gridWidthZ)(Bool())))
  zBuf.valid init False

  val syndromeAggregator = QECMasterSyndromeAggregator(nodeNum, distance, syndromeXMap, syndromeZMap)
  (syndromeAggregator.io.msgDataIn zip msgBuf) foreach { case (r, b) =>
    r.assignFromBits(b.payload.data.asBits)
  }
  val msgAllValid = msgBuf.map(_.valid).andR
  when(msgAllValid) {
    xBuf.valid := True
    xBuf.payload := syndromeAggregator.io.syndromeX
    zBuf.valid := True
    zBuf.payload := syndromeAggregator.io.syndromeZ
    msgBuf.foreach { b => b.valid := False }
  }

  io.syndromeX.valid := xBuf.valid
  io.syndromeX.payload := xBuf.payload
  when(io.syndromeX.fire) {
    xBuf.valid := False
  }
  io.syndromeZ.valid := zBuf.valid
  io.syndromeZ.payload := zBuf.payload
  when(io.syndromeZ.fire) {
    zBuf.valid := False
  }
}

object GenQECMasterSoc extends App {
  val syndromeZMap = Map(
    (0, 0) -> (0, 0),
    (1, 0) -> (0, 1),
    (2, 0) -> (0, 2),
    (3, 0) -> (0, 3)
  )
  val syndromeXMap = Map(
    (0, 0) -> (1, 4),
    (1, 0) -> (1, 5),
    (2, 0) -> (1, 6),
    (3, 0) -> (1, 7)
  )
  val errorMap = Map(
    (0, 0) -> (0, 0),
    (0, 1) -> (0, 1),
    (0, 2) -> (0, 2),
    (1, 0) -> (0, 3),
    (1, 1) -> (0, 4),
    (1, 2) -> (1, 0),
    (2, 0) -> (1, 1),
    (2, 1) -> (1, 2),
    (2, 2) -> (1, 3)
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    QECMasterSoc(
      distance = 3,
      nodeNum = 2,
      syndromeXMap = syndromeXMap,
      syndromeZMap = syndromeZMap,
      errorMap = errorMap
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
