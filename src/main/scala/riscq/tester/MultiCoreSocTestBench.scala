package riscq.tester.multicore

import spinal.core.sim._
import spinal.core._
import spinal.lib.bus.tilelink.sim._
import spinal.lib.bus.tilelink._
import spinal.lib.misc.Elf
import java.io.File
import riscq._
import riscq.soc.MultiCoreSoc
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.tester.RvAssembler
import riscq.tester.ByteHelper
import net.fornwall.jelf.ElfSectionHeader

object TwosComplementToSigned {
  def apply(x: BigInt, width: Int): BigInt = {
    if(x >= (1 << (width - 1))) x - (1 << width) else x
  }
}

class Driver(dut: MultiCoreSoc) {
  implicit val idAllocator = new IdAllocator(DebugId.width)
  implicit val idCallback = new IdCallback
  val dspCd = dut.dspCd
  val hostCd = dut.hostCd

  var axi4Driver: Axi4Master = null
  var tlDriver: MasterAgent = null
  val wb = dut.riscqArea.riscqCores(0).riscqFiber.riscq.host[test.WhiteboxerPlugin].logic

  def init() = {
    axi4Driver = Axi4Master(dut.io.axi, hostCd)
    axi4Driver.reset()
    dspCd.forkStimulus(10)
    hostCd.forkStimulus(50)

    rstUp()
    hostCd.waitRisingEdge(10)
    rstDown()
  }

  def rstUp() = {
    dspCd.assertReset()
    hostCd.assertReset()
  }

  def rstDown() = {
    dspCd.deassertReset()
    hostCd.deassertReset()
  }

  def loadMem(id: Int, addr: Long, insts: Seq[String]) = {
    var writeAddr = addr
    for (inst <- insts) {
      val instInt = BigInt(inst, 2)
      dut.riscqArea.riscqCores(id).mem.mem.setBigInt(writeAddr, instInt)
      writeAddr += 1
    }
  }

  def readMem(id: Int, addr: Long): BigInt = {
    return dut.riscqArea.riscqCores(id).mem.mem.getBigInt(addr)
  }

  def tick(t: Int = 1) = {
    dspCd.waitRisingEdge(t)
  }

  def waitUntil(t: Int) = {
    while (dutTime < t) {
      tick()
    }
  }

  def logPcs() = {
    for (data <- wb.pcs.data) {
      println(
        s"pc ${data.pc.toBigInt.toString(16)}, v: ${data.valid.toBoolean}, r: ${data.ready.toBoolean}, f: ${data.forgetOne.toBoolean}, ${data.ctrlName}"
      )
    }
  }

  def logDac(n: Int) = {
    val dac = dut.io.dac(n)
    val bin = dac.payload.toBooleans.grouped(16).toArray
    val ampInt = bin.map { x => BigInt(x.toList.reverse.map { y => if(y) "1" else "0" }.mkString(""), 2) }.toList
    val pulse = ampInt.map { x => 1.0 * TwosComplementToSigned(x, 16).toInt / (1 << 15) }.toList
    println(s"pulse: ${pulse}")
  }

  def dutTime = dut.riscqArea.time.toBigInt

  def logTime() = {
    val time = dutTime
    println(s"time: $time")
  }

  def FREQ_GHZ(f: Double) = (f * (1 << 13)).toInt
}

object TestMultiCoreSocPulse extends App {
  val qubitNum = 4
  val dacMap = (0 until qubitNum).flatMap { i => List(((i, 0), 2 * i), ((i, 1), 2 * i + 1)) }.toMap
  val adcMap = (0 until qubitNum).map { i => (i, i) }.toMap
  val simConfig = SimConfig
  simConfig.addSimulatorFlag("--x-initial 0")
  simConfig
    .compile {
      val dut = new MultiCoreSoc(qubitNum = qubitNum, dacMap = dacMap, adcMap = adcMap, withTest = true)
      dut.riscqResetHostCd.simPublic()
      dut.riscqArea.time.simPublic()
      dut.riscqArea.riscqCores(0).timeMemMap.timeCmp.simPublic()
      dut
    }
    .doSim { dut =>
      val driver = new Driver(dut)
      import driver._

      init()

      axi4Driver.write(dut.hostCtrlOffset, List(0x01, 0x00, 0x00, 0x00)) // riscq reset up

      val batchSize = 16
      val dataWidth = 16
      for (coreId <- 0 until qubitNum) {
        for (channelId <- 0 until 2) {
          for (i <- 0 until 1024) {
            val dt = if (i == 0) BigInt(1 << 12) else BigInt((1 << 15) - 1)
            val batch = List.fill(batchSize)(dt)
            val dataStr = batch.map { x => ByteHelper.intToBinStr(x, dataWidth) }.reduce { _ ++ _ }
            dut.riscqArea.riscqCores(coreId).pulseMemFiber.pulseMems(channelId).mem.setBigInt(i, BigInt(dataStr, 2))
          }
        }
      }
      hostCd.waitRisingEdge()

      val elfFile = new File("software-example/build/repeat_pulse.elf")
      val elf = new Elf(elfFile, addressWidth = 32)
      for(coreId <- 0 until qubitNum) {
        elf.load(dut.riscqArea.riscqCores(coreId).mem.mem, -0x80000000)
      }

      dspCd.assertReset()
      dspCd.waitRisingEdge()
      dspCd.deassertReset()

      axi4Driver.write(dut.hostCtrlOffset, List(0x00, 0x00, 0x00, 0x00)) // riscq reset down

      // waitUntil(400 - 5)
      // val monitor = new Monitor(dut.riscqArea.riscqCores(0).riscqFiber.dBus.bus, dspCd)
      // monitor.add(new MonitorSubscriber {
      //   override def onA(a: TransactionA) = { logTime(); println(a); println(s"timecmp: ${dut.riscqArea.riscqCores(0).timeMemMap.timeCmp.toBigInt}") }
      //   override def onD(d: TransactionD) = { logTime(); println(d) }
      // })
      for(j <- 1 until 11) {
        waitUntil(j * 100)
        for(i <- 0 until 6) {
          logTime()
          logDac(0)
          // logPcs()
          println("")
          tick()
        }
      }
    }
}
