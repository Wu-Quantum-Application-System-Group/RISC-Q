package riscq.network

import spinal.core._
import spinal.lib._

case class IOBUF() extends BlackBox {
  val io = new Bundle {
    val IO = inout(Bool())
    val I = in Bool()
    val O = out Bool()
    val T = in Bool() // 0 for I, 1 for O
  }
  noIoPrefix()
}

case class BasicSync() extends Component {
  val io = new Bundle {
    val time = in UInt(32 bit)
    val txTime = out UInt(32 bit)
    val rxTime = out UInt(32 bit)
    val doSync = in Bool()
    val slave = in Bool()
    val syncCopper = inout(Bool())
  }

  val txTime = Reg(UInt(32 bit)) init 0
  io.txTime := txTime
  val rxTime = Reg(UInt(32 bit)) init 0
  io.rxTime := rxTime

  val txData = Bool()
  val rxData = Bool()
  val ioBuf = IOBUF()
  ioBuf.io.I := txData
  rxData := ioBuf.io.O
  ioBuf.io.T := io.slave
  ioBuf.io.IO := io.syncCopper

  val doSyncHist = History(io.doSync, 4)
  val txTrigger = RegNext(doSyncHist(1) && !doSyncHist(2))

  val rxHist = History(rxData, 4)
  val rxTrigger = RegNext(rxHist(1) && !rxHist(2))

  txData := txTrigger
  when(txTrigger) {
    txTime := io.time
  }
  when(rxTrigger) {
    rxTime := io.time
  }
}