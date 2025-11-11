package riscq.network

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber
import riscq.misc.VivadoClkHelper
import riscq.soc.RiscqZcu216SocPorts

case class GtPins() extends Bundle {
  val gtyrxp_in = in Bool()
  val gtyrxn_in = in Bool()
  val gtytxp_out = out Bool()
  val gtytxn_out = out Bool()
  val mgtrefclk_p = in Bool()
  val mgtrefclk_n = in Bool()
}

case class AxiToTileLinkDriver(driveProc: tilelink.SlaveFactory => Unit) extends Area {
  val axi = Axi4(Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  ))

  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load axi
  val hostBus = Node()
  hostBus at 0 of bridge.down
  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val driverNode = Node.up()
  val dataWidth = 32
  driverNode at 0 of hostBus

  val driverLogic = Fiber build new Area {
    driverNode.m2s.supported load driverNode.m2s.proposed.copy(
    addressWidth = 16,
    dataWidth = dataWidth,
    transfers = driverNode.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8),
        )
      )
    )
    driverNode.s2m.none()

    val factory = new tilelink.SlaveFactory(driverNode.bus, false)
    driveProc(factory)
  }
}

case class GtTxIO() extends Bundle {
  val resetdone = in Bool ()
  val userdata_out = out Bits (64 bits)
  val header_out = out Bits (2 bits)
  val sequence_out = out UInt (7 bits)
  val userclk_active_in = in Bool ()
}

case class GtRxIO(withPolarity: Boolean = false) extends Bundle { 
  val resetdone = in Bool ()
  val userdata_in = in Bits (64 bits)
  val datavalid_in = in Bool ()
  val header_in = in Bits (2 bits)
  val headervalid_in = in Bool ()
  val gearboxslip = out Bool ()
  val userclk_active_in = in Bool ()
  val polarity = withPolarity generate (out Bool())
}

case class GtCoreIO(withPolarity: Boolean = false) extends Bundle {
  val reset_n = in Bool ()
  val gtpowergood = in Bool ()
  val tx = GtTxIO()
  val rx = GtRxIO(withPolarity)
  val txCmd = slave Stream(Bits(64 bits))
  val rxRsp = master Flow(Bits(64 bits))
  val txClk = in Bool()
  val rxClk = in Bool()
}

abstract class GtCore(val withPolarity: Boolean = false) extends Component {
  val io = new GtCoreIO(withPolarity)
}

case class GtCoreTopIO() extends Bundle {
  val reset_n = in Bool()
  val gt = GtPins()
  val txCmd = slave Stream(Bits(64 bits))
  val rxRsp = master Flow(Bits(64 bits))
  val tx_userclk = out Bool()
  val rx_userclk = out Bool()
}

abstract class GtCoreTop[T <: GtCore](gen: =>T, gtId: Int = 0) extends Component {
  val io = new GtCoreTopIO()

  val mgtrefclk_IBUFDSGTE4 = IBUFDS_GTE4()
  mgtrefclk_IBUFDSGTE4.I := io.gt.mgtrefclk_p
  mgtrefclk_IBUFDSGTE4.IB := io.gt.mgtrefclk_n
  mgtrefclk_IBUFDSGTE4.CEB := False
  val mgtrefclk = mgtrefclk_IBUFDSGTE4.O

  val core = gen
  val withPolarity = core.withPolarity
  core.io.txCmd <> io.txCmd
  io.rxRsp <> core.io.rxRsp

  val gt = GtwizardWrapper(gtId)
  gt.io.gtyrxp_in := io.gt.gtyrxp_in
  gt.io.gtyrxn_in := io.gt.gtyrxn_in
  io.gt.gtytxp_out := gt.io.gtytxp_out
  io.gt.gtytxn_out := gt.io.gtytxn_out
  gt.io.gtrefclk00_in := mgtrefclk

  val tx_userclk = gt.io.gtwiz_userclk_tx_usrclk_out
  val rx_userclk = gt.io.gtwiz_userclk_rx_usrclk_out
  io.tx_userclk := tx_userclk
  io.rx_userclk := rx_userclk

  core.io.reset_n := io.reset_n
  core.io.txClk := tx_userclk
  core.io.rxClk := rx_userclk
  core.io.gtpowergood := gt.io.gtpowergood_out
  core.io.tx.resetdone := gt.io.txresetdone_out
  gt.io.gtwiz_userdata_tx_in := core.io.tx.userdata_out
  gt.io.txheader_in := core.io.tx.header_out.resized
  gt.io.txsequence_in := core.io.tx.sequence_out.asBits
  core.io.tx.userclk_active_in := gt.io.gtwiz_userclk_tx_active_out
  core.io.rx.resetdone := gt.io.rxresetdone_out
  core.io.rx.userdata_in := gt.io.gtwiz_userdata_rx_out
  core.io.rx.datavalid_in := gt.io.rxdatavalid_out(0)
  core.io.rx.header_in := gt.io.rxheader_out.resized
  core.io.rx.headervalid_in := gt.io.rxheadervalid_out(0)
  core.io.rx.userclk_active_in := gt.io.gtwiz_userclk_rx_active_out
  gt.io.rxgearboxslip_in := core.io.rx.gearboxslip

  gt.io.gtwiz_reset_all_in := ClockDomain.current.readResetWire
  gt.io.gtwiz_reset_clk_freerun_in := ClockDomain.current.readClockWire
  gt.io.gtwiz_userclk_tx_reset_in := !gt.io.txpmaresetdone_out
  gt.io.gtwiz_userclk_rx_reset_in := !gt.io.rxpmaresetdone_out
  if(withPolarity) {
    gt.io.rxpolarity_in := core.io.rx.polarity
  } else {
    gt.io.rxpolarity_in := False
  }

  // may be useless
  gt.io.gtwiz_reset_tx_pll_and_datapath_in := False
  gt.io.gtwiz_reset_tx_datapath_in := False
  gt.io.gtwiz_reset_rx_pll_and_datapath_in := False
  gt.io.gtwiz_reset_rx_datapath_in := False
}

abstract class GtCoreAxiTop[T <: GtCore](gen: =>GtCoreTop[T]) extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 2
    )))
    val gt = GtPins()
    val ledR = out Bool()
  }

  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  io.gt.mgtrefclk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_P ")
  io.gt.mgtrefclk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_N ")
  val reset_n = Reg(Bool()) init False
  io.ledR := reset_n

  val core = gen
  core.io.gt <> io.gt
  core.io.reset_n := reset_n

  val rxCd = ClockDomain(core.io.rx_userclk)
  val rxReset = rxCd(BufferCC(ClockDomain.current.readResetWire))
  val rxResetCd = ClockDomain(core.io.rx_userclk, rxReset)
  val rxRsp = core.io.rxRsp.toStream.queue(2, rxResetCd, ClockDomain.current).toReg

  val txCd = ClockDomain(core.io.tx_userclk)
  val txBufferLow = Stream(Bits(32 bits))
  val txBufferHigh = Reg(Bits(32 bits)) init 0
  val txBuffer = Stream(Bits(64 bits))
  txBuffer.valid := txBufferLow.valid
  txBuffer.payload := txBufferHigh ## txBufferLow.payload
  txBufferLow.ready := txBuffer.ready
  txBuffer.queue(2, ClockDomain.current, txCd) >> core.io.txCmd

  val driver = AxiToTileLinkDriver(factory => {
    factory.drive(reset_n, 0)
    factory.read(reset_n, 0)
    factory.driveStream(txBufferLow, 4)
    factory.drive(txBufferHigh, 8)
    factory.read(rxRsp(0, 32 bits), 12)
    factory.read(rxRsp(32, 32 bits), 16)
    factory.read(BufferCC(core.gt.io.rxresetdone_out.pull()), 32)
    factory.read(BufferCC(core.gt.io.txresetdone_out.pull()), 36)
    factory.read(BufferCC(core.gt.io.gtpowergood_out.pull()), 40)
    factory.read(BufferCC(core.io.txCmd.ready), 44)
  })
  driver.axi <> io.axi
}

case class IBUFDS_GTE4() extends BlackBox {
  val I = in Bool()
  val IB = in Bool()
  val CEB = in Bool()
  val O = out Bool()
  val ODIV2 = out Bool()
}

abstract class SyncTester[T <: GtCore](gen: =>GtCoreTop[T]) extends Component {
  val io = RiscqZcu216SocPorts(gtNum = 1)
  io.noDac()

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)
  io.dspClk.setName("dspClk")
  io.dspRst.setName("dspRst")
  val dspCd = ClockDomain(io.dspClk, io.dspRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspRst, 500000000)

  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  // io.gts(0).mgtrefclk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_P ")
  // io.gts(0).mgtrefclk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_N ")

  val core = gen
  core.io.gt <> io.gts(0)
  val reset_n = Reg(Bool()) init False
  core.io.reset_n := reset_n

  val txCd = ClockDomain(core.io.tx_userclk)
  val rxCd = ClockDomain(core.io.rx_userclk)

  val reset = ClockDomain.current.readResetWire
  val rxReset = rxCd(BufferCC(reset))
  val txReset = txCd(BufferCC(reset))
  val rxResetCd = ClockDomain(core.io.rx_userclk, rxReset)
  val txResetCd = ClockDomain(core.io.tx_userclk, txReset)

  val rxRsp = core.io.rxRsp.toStream.queue(2, rxResetCd, hostCd).toReg


  val dspArea = new ClockingArea(dspCd) {
    val time = Reg(UInt(64 bits))
    time := time + 1

    val txTime = Reg(UInt(64 bits))
    val txValidCC = BufferCC(core.io.txCmd.valid, 10)
    when(txValidCC.rise()) {
      txTime := time
    }

    val rxTime = Reg(UInt(64 bits))
    val rxValidCC = BufferCC(core.io.rxRsp.valid, 10)
    when(rxValidCC.rise()) {
      rxTime := time
    }

  }

  val hostCmd = Stream(Bits(32 bits))
  val hostToTxCmd = hostCmd.queue(8, ClockDomain.current, txResetCd)

  val txArea = new ClockingArea(txResetCd) {
    val hostCmd = cloneOf(core.io.txCmd)
    hostCmd.arbitrationFrom(hostToTxCmd)
    hostCmd.payload := B(0, 32 bits) ## hostToTxCmd.payload
  }

  val powergood = core.gt.io.gtpowergood_out.pull()
  val txResetdone = core.gt.io.txresetdone_out.pull()
  val rxResetdone = core.gt.io.rxresetdone_out.pull()
  val txUserclkActive = core.gt.io.gtwiz_userclk_tx_active_out.pull()
  val rxUserclkActive = core.gt.io.gtwiz_userclk_rx_active_out.pull()
  val rxData = core.core.io.rx.userdata_in.pull()
  val rxDataValid = core.core.io.rx.datavalid_in.pull()
  val rxDataFlow = Flow(rxData)
  rxDataFlow.payload := rxData
  rxDataFlow.valid := rxDataValid
  val rxDataBuf = rxDataFlow.toStream.queue(2, rxResetCd, hostCd).toReg
  // val rxDataNonZero = RegNextWhen(rxDataBuf, rxDataBuf.orR)
  val rxHeader = core.core.io.rx.header_in.pull()
  val rxHeaderValid = core.core.io.rx.headervalid_in.pull()
  val rxHeaderFlow = Flow(rxHeader)
  rxHeaderFlow.payload := rxHeader
  rxHeaderFlow.valid := rxHeaderValid
  val rxHeaderBuf = rxHeaderFlow.toStream.queue(2, rxResetCd, hostCd).toReg
  // val rxHeaderNonZero = RegNextWhen(rxHeaderBuf, rxHeaderBuf.orR)
  val testReg = Reg(UInt(32 bits))
  testReg := 321

  val driver = AxiToTileLinkDriver(factory => {
    factory.drive(reset_n, 0)
    factory.read(reset_n, 0)
    factory.driveStream(hostCmd, 8)
    factory.read(rxRsp(0, 32 bits), 16)
    factory.read(BufferCC(core.io.txCmd.ready), 20)
    factory.read(BufferCC(dspArea.txTime(0, 32 bits)), 24)
    factory.read(BufferCC(dspArea.txTime(32, 32 bits)), 28)
    factory.read(BufferCC(dspArea.rxTime(0, 32 bits)), 32)
    factory.read(BufferCC(dspArea.rxTime(32, 32 bits)), 36)
    factory.read(BufferCC(powergood), 64)
    factory.read(BufferCC(txResetdone), 68)
    factory.read(BufferCC(rxResetdone), 72)
    factory.read(BufferCC(txUserclkActive), 76)
    factory.read(BufferCC(rxUserclkActive), 80)
    factory.read(rxDataBuf(0, 32 bits), 84)
    factory.read(rxDataBuf(32, 32 bits), 88)
    // factory.read(rxDataNonZero(0, 32 bits), 92)
    // factory.read(rxDataNonZero(32, 32 bits), 96)
    factory.read(rxHeaderBuf, 100)
    factory.read(testReg, 104)
    // factory.read(rxHeaderNonZero, 104)
  })
  driver.axi <> io.axi
}

abstract class MultiPortTester[T <: GtCore](gen: =>GtCoreTop[T], gtNum: Int) extends Component {
  val io = RiscqZcu216SocPorts(gtNum = gtNum)
  io.noDac()

  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)
  io.dspClk.setName("dspClk")
  io.dspRst.setName("dspRst")
  val dspCd = ClockDomain(io.dspClk, io.dspRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspRst, 500000000)

  val cores = List.fill(gtNum)(gen)
  (cores zip io.gts).foreach { case (core, gt) =>
    core.io.gt <> gt
  }

  val reset_ns = List.fill(gtNum)(Reg(Bool()) init False)
  cores.zip(reset_ns).foreach { case (core, reset_n) =>
    core.io.reset_n := reset_n
  }

  val txCds = cores.map(c => ClockDomain(c.io.tx_userclk))
  val rxCds = cores.map(c => ClockDomain(c.io.rx_userclk))

  val reset = ClockDomain.current.readResetWire
  val rxResets = rxCds.map(c => c(BufferCC(reset)))
  val txResets = txCds.map(c => c(BufferCC(reset)))
  val rxResetCds = cores.zip(rxResets).map { case (c, r) => ClockDomain(c.io.rx_userclk, r) }
  val txResetCds = cores.zip(txResets).map { case (c, r) => ClockDomain(c.io.tx_userclk, r) }

  val driver = AxiToTileLinkDriver(factory => {
    val coreOffset = 0x100
    val coreDriver = for((core, i) <- cores.zipWithIndex) yield new Area {
      val coreBase = i * 0x100

      factory.write(reset_ns(i), coreBase + 0)

      val hostCmd = Stream(Bits(32 bits))
      val hostToTxCmd = hostCmd.queue(2, hostCd, txResetCds(i))
      val hostCmdResized = Stream(Bits(64 bits))
      hostCmdResized.arbitrationFrom(hostToTxCmd)
      hostCmdResized.payload := B(0, 32 bits) ## hostToTxCmd.payload

      core.io.txCmd << hostCmdResized

      factory.driveStream(hostCmd, coreBase + 4)

      val rxRsp = core.io.rxRsp.toStream.queue(2, rxResetCds(i), hostCd).toReg
      factory.read(rxRsp(0, 32 bits), coreBase + 8)
      factory.read(rxRsp(32, 32 bits), coreBase + 12)

      factory.read(BufferCC(core.io.txCmd.ready), coreBase + 16)
    }
  })
  driver.axi <> io.axi
}