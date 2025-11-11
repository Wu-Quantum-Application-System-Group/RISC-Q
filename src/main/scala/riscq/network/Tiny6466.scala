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
  val askResetMsg = B("64'h0deadbeef")

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
        // header := controlHeader // default
        userdata := lfsrArea.lfsr.io.random
      }
    } otherwise {
      val askReset = False
      when(retryCounter < initCyclesBeforeRetry) {
        retryCounter := retryCounter + 1
      } otherwise {
        retryCounter := 0
        gearboxMatch := False
        askReset := True
      }

      when(askReset) {
        userdata := askResetMsg
      } elsewhen(gearboxMatch) {
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
            } elsewhen(io.rx.userdata_in === askResetMsg) {
              rxGearboxMatch := False
              rxPartnerGearboxMatch := False
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

case class Tiny6466SyncTester(id: Int = 0) extends SyncTester(Tiny6466Top(id))

object GenTiny6466SyncTester extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate{
    val dut = Tiny6466SyncTester()
    dut
  }
}
