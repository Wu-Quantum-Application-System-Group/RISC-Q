package riscq.network

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber


case class Minimal6466(
  debugReset: Boolean = false,
) extends GtCore(withPolarity = false) {
  val txCd = ClockDomain(io.txClk)
  val rxCd = ClockDomain(io.rxClk)

  val resetWire = !io.reset_n
  val rxresetdoneSync = BufferCC(io.rx.resetdone)
  val txresetdoneSync = BufferCC(io.tx.resetdone)
  val doReset = resetWire || !rxresetdoneSync || !txresetdoneSync

  val dataHeader = 1
  val controlHeader = 2
  val syncingMsg = B("64'h0000000000000000")

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
    doTx := !doResetSync && io.gtpowergood

    val txActive = BufferCC(io.tx.userclk_active_in)
    val txReset = RegNext(!txActive || doResetSync)

    when(txReset) {
      doTx := False
      userdata := 0
      header := controlHeader
      sequence := 0
      increaseSequence := False
    }

    val sequencePauseNextNext = sequence === U(0x1f)
    // add additional pause to detect gearbox slip
    // val sequencePauseNextNext = sequence === U(0x1f) || sequence === U(0x1e)
    // s 30 30 31 31 32 32
    // p 00 00 11 11 00 00
    // r 11 11 11 00 00 11

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
        userdata := syncingMsg
      }
    } otherwise {
      header := controlHeader
      userdata := syncingMsg
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

    val rxActive = BufferCC(io.rx.userclk_active_in)
    val rxReset = RegNext(!rxActive || doResetSync)
    when(rxReset) {
      doRx := False
      rxRsp.valid := False
      rxRsp.payload := 0  
    }

    when(doRx) {
      val msgValid = io.rx.datavalid_in
      when(msgValid) {
        when(io.rx.header_in === dataHeader) {
          rxRsp.valid := True
          rxRsp.payload := io.rx.userdata_in
        } elsewhen (io.rx.header_in === controlHeader) {
          when(io.rx.userdata_in =/= syncingMsg) {
            io.rx.gearboxslip := True
          }
        } otherwise {
          io.rx.gearboxslip := True
        }
      } 
    }
  }
}

object GenMinimal6466 extends App {
  SpinalVerilog(Minimal6466())
}

case class Minimal6466Top() extends GtCoreTop(Minimal6466())

object GenMinimal6466Top extends App {
  SpinalVerilog(Minimal6466Top())
}

case class Minimal6466AxiTop() extends GtCoreAxiTop(Minimal6466Top())

object GenMinimal6466AxiTop extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    Minimal6466AxiTop()
  )
}