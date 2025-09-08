package riscq.network

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber

case class Lfsr64() extends Component {
  val io = new Bundle {
    val random = out Bits (64 bits)
  }
  val seed = B("64'hdeadbeeffeedcafe")
  val lfsr = Reg(Bits(64 bits)) init seed
  val feedback = lfsr(63) ^ lfsr(62) ^ lfsr(60) ^ lfsr(59)
  lfsr := lfsr(62 downto 0) ## feedback
  io.random := lfsr
}

case class Tiny6466() extends GtCore {
  val txCd = ClockDomain(io.txClk)
  val rxCd = ClockDomain(io.rxClk)

  val resetWire = !io.reset_n
  val rxresetdoneSync = BufferCC(io.rx.resetdone)
  val txresetdoneSync = BufferCC(io.tx.resetdone)
  val doReset = resetWire || !rxresetdoneSync || !txresetdoneSync

  val rxGearboxMatch = rxCd(Reg(Bool()))
  val rxPartnerGearboxMatch = rxCd(Reg(Bool()))

  val dataHeader = 1
  val controlHeader = 2
  val syncSuccessMsg = B("64'hfeedcafe")
  // val syncingMsg = B("64'h0feedbaddeadbeef")

  val txArea = new ClockingArea(txCd) {
    val txReady = Reg(Bool())
    io.txCmd.ready := txReady
    val userdata = Reg(Bits(64 bits))
    io.tx.userdata_out := userdata
    val header = Reg(Bits(2 bits))
    header := controlHeader
    io.tx.header_out := header
    val sequence = Reg(UInt(7 bits))
    io.tx.sequence_out := sequence
    val increaseSequence = Reg(Bool())
    increaseSequence := !increaseSequence
    when(increaseSequence) {
      when(sequence >= U(0x20)) {
        sequence := 0
      } otherwise {
        sequence := sequence + 1
      }
    }
    val doTx = Reg(Bool())

    val doResetSync = BufferCC(doReset)
    val gearboxMatch = BufferCC(rxGearboxMatch)
    val partnerGearboxMatch = BufferCC(rxPartnerGearboxMatch)
    doTx := !doResetSync && gearboxMatch && partnerGearboxMatch && io.gtpowergood

    val txActive = BufferCC(io.tx.userclk_active_in)
    val txReset = RegNext(!txActive || doResetSync)
    val initCyclesBeforeRetry = 1024
    val retryCounter = Reg(UInt(log2Up(initCyclesBeforeRetry + 1) bits))
    when(txReset) {
      doTx := False
      userdata := 0
      header := controlHeader
      sequence := 0
      increaseSequence := False
      retryCounter := 0
    }

    val sequencePauseNextNext = sequence === U(0x1f)
    // s 30 30 31 31 32 32
    // p 00 00 11 11 00 00
    // r 11 11 11 00 00 11

    val lfsrArea = new ResetArea(txReset, false) {
      val lfsr = Lfsr64()
    }

    txReady := False
    when(doTx) {
      when(!sequencePauseNextNext) {
        txReady := True
      }
      when(io.txCmd.fire) {
        header := dataHeader
        userdata := io.txCmd.payload
      } otherwise {
        header := controlHeader
        userdata := lfsrArea.lfsr.io.random
      }
    } otherwise {
      when(retryCounter < initCyclesBeforeRetry) {
        retryCounter := retryCounter + 1
      } otherwise {
        retryCounter := 0
        gearboxMatch := False
        partnerGearboxMatch := False
      }

      when(gearboxMatch) {
        userdata := syncSuccessMsg
      } otherwise {
        userdata := lfsrArea.lfsr.io.random
      }
    }
  }

  val rxArea = new ClockingArea(rxCd) {
    val doRx = Reg(Bool())
    val doResetSync = BufferCC(doReset)
    doRx := !doResetSync && io.gtpowergood
    val rxRsp = Reg(Flow(Bits(64 bits)))
    rxRsp.valid := False
    io.rxRsp := rxRsp
    io.rx.gearboxslip := False

    val gearBoxMatchCycles = 64
    val gearBoxMatchCounter = Reg(UInt(log2Up(gearBoxMatchCycles + 1) bits))

    val rxActive = BufferCC(io.rx.userclk_active_in)
    val rxReset = RegNext(!rxActive || doResetSync)
    when(rxReset) {
      doRx := False
      rxRsp.valid := False
      rxRsp.payload := 0
      rxGearboxMatch := False
      rxPartnerGearboxMatch := False
      gearBoxMatchCounter := 0
    }

    when(doRx) {
      val msgValid = io.rx.datavalid_in && io.rx.headervalid_in
      when(msgValid) {
        when(!rxGearboxMatch) {
          gearBoxMatchCounter := gearBoxMatchCounter + 1
          when(gearBoxMatchCounter >= gearBoxMatchCycles) {
            rxGearboxMatch := True
          }
        }

        when(io.rx.header_in === dataHeader) {
          when(rxGearboxMatch) {
            rxRsp.valid := True
            rxRsp.payload := io.rx.userdata_in
          } otherwise {
            gearBoxMatchCounter := 0
          }
        } elsewhen (io.rx.header_in === controlHeader) {
          when(rxGearboxMatch) {
            when(io.rx.userdata_in === syncSuccessMsg) {
              rxPartnerGearboxMatch := True
            }
          }
        } otherwise {
          // reset when gearbox slip happens
          gearBoxMatchCounter := 0
          rxGearboxMatch := False
          rxPartnerGearboxMatch := False
          io.rx.gearboxslip := True
        }
      }
    }
  }
}

object GenTiny6466 extends App {
  SpinalVerilog(Tiny6466())
}

case class Tiny6466Tester() extends Component {
  val io = new Bundle {
    val reset_n = in Bool ()
    val txCmd = slave Stream (Bits(64 bits))
    val rxRsp = master Flow (Bits(64 bits))
  }

  val node1 = Tiny6466()
  val node2 = Tiny6466()

  node1.io.txClk := ClockDomain.current.readClockWire
  node1.io.rxClk := ClockDomain.current.readClockWire
  node2.io.txClk := ClockDomain.current.readClockWire
  node2.io.rxClk := ClockDomain.current.readClockWire

  node1.io.txCmd <> io.txCmd
  node1.io.gtpowergood := True
  node1.io.tx.resetdone := True
  node1.io.rx.resetdone := True
  node1.io.rx.userdata_in := node2.io.tx.userdata_out
  node1.io.rx.datavalid_in := True
  node1.io.rx.header_in := node2.io.tx.header_out
  node1.io.rx.headervalid_in := True
  node1.io.rx.userclk_active_in := True

  node2.io.gtpowergood := True
  node2.io.rx.resetdone := True
  node2.io.tx.resetdone := True
  node2.io.rx.userdata_in := node1.io.tx.userdata_out
  node2.io.rx.datavalid_in := True
  node2.io.rx.header_in := node1.io.tx.header_out
  node2.io.rx.headervalid_in := True
  node2.io.rx.userclk_active_in := True

  node2.io.txCmd.payload := 0
  node2.io.txCmd.valid := False
  io.rxRsp <> node2.io.rxRsp
}

object TestTiny6466 extends App {
  SimConfig
    .compile {
      val dut = Tiny6466Tester()
      dut
    }
    .doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(100)

      var data = 123
      dut.io.reset_n #= false
      cd.waitRisingEdge(10)
      dut.io.reset_n #= true
      dut.io.txCmd.valid #= true
      for (i <- 0 until 100) {
        dut.io.txCmd.payload #= data
        data += 1
        println(s"${dut.io.rxRsp.valid.toBoolean} ${dut.io.rxRsp.payload.toBigInt}")
        cd.waitRisingEdge()
      }
    }
}

case class Tiny6466Top(gtId: Int = 0) extends GtCoreTop(Tiny6466(), gtId = gtId)

object GenTiny6466Top extends App {
  SpinalVerilog(Tiny6466Top())
}

case class Tiny6466AxiTop() extends GtCoreAxiTop(Tiny6466Top())

object GenTiny6466AxiTop extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    Tiny6466AxiTop()
  )
}

case class TinyInspector() extends Component {
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
  val reset_n = Reg(Bool()) init False
  io.ledR := reset_n

  val core = Tiny6466Top()
  core.io.gt <> io.gt
  core.io.reset_n := reset_n

  val rxBuffer = BufferCC(core.io.rxRsp)
  val rxRsp = Reg(core.io.rxRsp.payload)
  when(rxBuffer.valid) {
    rxRsp := rxBuffer.payload
  }

  val txCd = ClockDomain(core.io.tx_userclk)
  val txBuffer = StreamFifoCC(core.io.txCmd.payload, 4, ClockDomain.current, txCd)
  txBuffer.io.pop >> core.io.txCmd
  val txBufferLow = Stream(Bits(32 bits))
  val txBufferHigh = Reg(Bits(32 bits)) init 0
  txBuffer.io.push.valid := txBufferLow.valid
  txBuffer.io.push.payload := txBufferHigh ## txBufferLow.payload
  txBufferLow.ready := txBuffer.io.push.ready

  val rxCd = ClockDomain(core.io.rx_userclk)
  val rxArea = new ClockingArea(rxCd) {
    val headerBuffer = Reg(Bits(2 bits))
    val dataBuffer = Reg(Bits(64 bits))
    // when(core.core.io.rx.headervalid_in.pull() && core.core.io.rx.datavalid_in.pull()) {
    headerBuffer := core.core.io.rx.header_in.pull()
    dataBuffer := core.core.io.rx.userdata_in.pull()
    // }
  }

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
    factory.read(BufferCC(core.core.rxGearboxMatch.pull()), 64)
    factory.read(BufferCC(core.core.rxPartnerGearboxMatch.pull()), 68)
    factory.read(BufferCC(core.core.rxArea.gearBoxMatchCounter.pull()), 72)
    factory.read(BufferCC(rxArea.headerBuffer), 76)
    factory.read(BufferCC(rxArea.dataBuffer)(0, 32 bits), 80)
    factory.read(BufferCC(rxArea.dataBuffer)(32, 32 bits), 84)
  })
  driver.axi <> io.axi
}

object GenTinyInspector extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    TinyInspector()
  )
}

case class Tiny6466LatencyTester() extends LatencyTester(Tiny6466Top())

object GenTiny6466LatencyTester extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    Tiny6466LatencyTester()
  )
}

case class Tiny6466SyncTester() extends SyncTester(Tiny6466Top())

object GenTiny6466SyncTester extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    Tiny6466SyncTester()
  )
}