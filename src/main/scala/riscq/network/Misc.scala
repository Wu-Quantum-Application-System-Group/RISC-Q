package riscq.network

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber
import riscq.misc.VivadoClkHelper

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

abstract class GtCore(withPolarity: Boolean = false) extends Component {
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

abstract class GtCoreTop[T <: GtCore](gen: =>T, withPolarity: Boolean = false, gtId: Int = 0) extends Component {
  val io = new GtCoreTopIO()

  val mgtrefclk_IBUFDSGTE4 = IBUFDS_GTE4()
  mgtrefclk_IBUFDSGTE4.I := io.gt.mgtrefclk_p
  mgtrefclk_IBUFDSGTE4.IB := io.gt.mgtrefclk_n
  mgtrefclk_IBUFDSGTE4.CEB := False
  val mgtrefclk = mgtrefclk_IBUFDSGTE4.O

  val core = gen
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

// runs in txCd
case class LatencyTestMaster() extends Component {
  val io = new Bundle {
    val txCmd = master Stream (Bits(64 bits))
    val rxRsp = slave Flow (Bits(64 bits))
    val latency = out UInt (32 bits)
    val en = in Bool ()
  }

  val time = Reg(UInt(32 bits))
  time := time + 1
  val txTime = Reg(UInt(32 bits))
  val rxTime = Reg(UInt(32 bits))


  val notFired = Reg(Bool()) setWhen(io.en) clearWhen(io.rxRsp.fire)
  when(io.txCmd.fire) {
    txTime := time
  }
  io.txCmd.payload := B("64'hfeedcafe")
  io.txCmd.valid := io.en || notFired
  
  when(io.rxRsp.fire && io.rxRsp.payload === B("64'hdeadbeef")) {
    rxTime := time
  }
  io.latency := RegNext(rxTime - txTime)
}

// runs in rxCd
case class LatencyTestSlave() extends Component {
  val io = new Bundle {
    val txCmd = master Stream (Bits(64 bits))
    val rxRsp = slave Flow (Bits(64 bits))
  }

  val doEcho = io.rxRsp.fire && io.rxRsp.payload === B("64'hfeedcafe")
  val notFired = Reg(Bool()) setWhen(doEcho) clearWhen(io.txCmd.fire)
  io.txCmd.valid := doEcho || notFired
  io.txCmd.payload := B("64'hdeadbeef")
}


abstract class LatencyTester[T <: GtCore](gen: =>GtCoreTop[T]) extends Component {
  val io = new Bundle {
    val axi = slave(
      Axi4(
        Axi4Config(
          addressWidth = 32,
          dataWidth = 32,
          idWidth = 2
        )
      )
    )
    val gt = GtPins()
    val ledR = out Bool ()
  }
  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  io.gt.mgtrefclk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_P ")
  io.gt.mgtrefclk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_N ")

  val core = gen
  core.io.gt <> io.gt
  val reset_n = Reg(Bool()) init False
  core.io.reset_n := reset_n
  io.ledR := reset_n

  val hostCd = ClockDomain.current
  val txCd = ClockDomain(core.io.tx_userclk)
  val rxCd = ClockDomain(core.io.rx_userclk)

  val reset = ClockDomain.current.readResetWire
  val rxReset = rxCd(BufferCC(reset))
  val txReset = txCd(BufferCC(reset))
  val rxResetCd = ClockDomain(core.io.rx_userclk, rxReset)
  val txResetCd = ClockDomain(core.io.tx_userclk, txReset)
  val rxToTxBuffer = core.io.rxRsp.toStream.queue(2, rxResetCd, txCd).toFlow

  val rxRsp = core.io.rxRsp.toStream.queue(2, rxResetCd, hostCd).toReg


  val fifoLatencyTestEn = Flow(Bool())
  val fifoCCLatencyArea = new ClockingArea(rxResetCd) {
    val time = Reg(UInt(32 bits))
    time := time + 1
    val start = Reg(UInt(32 bits))
    val end = Reg(UInt(32 bits))
    val latency = RegNext(end - start)

    val testEnFlow = fifoLatencyTestEn.toStream.queue(2, hostCd, rxResetCd).toFlow
    val fifo = testEnFlow.toStream.queue(2, rxResetCd, txResetCd).queue(2, txResetCd, rxResetCd).toFlow
    when(testEnFlow.valid) {
      start := time
    }
    when(fifo.valid) {
      end := time
    }
  }


  val hostToTxEn = Flow(Bool())
  val hostCmd = Stream(Bits(32 bits))
  val hostToTxCmd = hostCmd.queue(2, ClockDomain.current, txCd)
  val txArea = new ClockingArea(txResetCd) {
    val master = LatencyTestMaster()
    master.io.rxRsp << rxToTxBuffer
    master.io.en := hostToTxEn.toStream.queue(2, hostCd, txCd).toFlow.valid

    val slave = LatencyTestSlave()
    slave.io.rxRsp := rxToTxBuffer

    val hostCmdResized = Stream(Bits(64 bits))
    hostCmdResized.valid := hostToTxCmd.valid
    hostCmdResized.payload := B(0, 32 bits) ## hostToTxCmd.payload
    hostToTxCmd.ready := hostCmdResized.ready

    val txCmd = cloneOf(core.io.txCmd)
    txCmd >> core.io.txCmd

    val arbiter = StreamArbiterFactory().lowerFirst.onArgs(slave.io.txCmd, master.io.txCmd, hostCmdResized)
    arbiter >> txCmd
  }

  val driver = AxiToTileLinkDriver(factory => {
    factory.drive(reset_n, 0)
    factory.read(reset_n, 0)
    factory.driveStream(hostCmd, 8)
    factory.read(rxRsp(0, 32 bits), 16)
    factory.read(BufferCC(core.io.txCmd.ready), 20)
    factory.driveFlow(hostToTxEn, 32)
    factory.driveFlow(fifoLatencyTestEn, 36)
    factory.read(ValidFlow(txArea.master.io.latency).toStream.queue(2, txResetCd, hostCd).toFlow.toReg, 64)
    factory.read(ValidFlow(fifoCCLatencyArea.latency).toStream.queue(2, rxResetCd, hostCd).toFlow.toReg, 68)
  })
  driver.axi <> io.axi
}


abstract class SyncTester[T <: GtCore](gen: =>GtCoreTop[T]) extends Component {
  val hostCd = ClockDomain.current
  hostCd.renamePulledWires("hostClk", "hostRst")
  VivadoClkHelper.addInference(hostCd.readClockWire, hostCd.readResetWire, 100000000)
  val io = new Bundle {
    val axi = slave(
      Axi4(
        Axi4Config(
          addressWidth = 32,
          dataWidth = 32,
          idWidth = 2
        )
      )
    )
    val dspClk = in Bool ()
    val dspExtRst = in Bool ()
    val gt = GtPins()
    val ledR = out Bool ()
  }
  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
  io.gt.mgtrefclk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_P ")
  io.gt.mgtrefclk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_N ")

  // cd500m
  io.dspClk.setName("dspClk")
  io.dspExtRst.setName("dspExtRst")
  val dspCd = ClockDomain(io.dspClk, io.dspExtRst)
  VivadoClkHelper.addInference(dspCd.readClockWire, io.dspExtRst, 500000000)

  val core = gen
  core.io.gt <> io.gt
  val reset_n = Reg(Bool()) init False
  core.io.reset_n := reset_n
  io.ledR := reset_n

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
    val echoer = LatencyTestSlave()
    val rxBuf = core.io.rxRsp.toStream.queue(8, rxResetCd, txResetCd).toFlow
    echoer.io.rxRsp := rxBuf

    val hostCmd = cloneOf(core.io.txCmd)
    hostCmd.arbitrationFrom(hostToTxCmd)
    hostCmd.payload := B(0, 32 bits) ## hostToTxCmd.payload
    val arbiter = StreamArbiterFactory().lowerFirst.onArgs(echoer.io.txCmd, hostCmd)
    arbiter >> core.io.txCmd
  }

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
  })
  driver.axi <> io.axi
}