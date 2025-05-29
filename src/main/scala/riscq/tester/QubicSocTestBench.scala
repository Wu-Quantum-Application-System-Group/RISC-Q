package riscq.tester.qubic

import spinal.core.sim._
import spinal.core._
import spinal.lib.bus.tilelink.sim._
import spinal.lib.bus.tilelink._
import spinal.lib.misc.Elf
import java.io.File
import riscq._
import riscq.soc.QubicSoc
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.tester.{RvAssembler, QubicAssembler}
import riscq.tester.ByteHelper
import net.fornwall.jelf.ElfSectionHeader

class Driver(dut: QubicSoc) {
  implicit val idAllocator = new IdAllocator(DebugId.width)
  implicit val idCallback = new IdCallback
  val dspCd = dut.clockDomain
  val hostCd = dut.hostCd

  val wb = dut.core.host[test.WhiteboxerPlugin].logic

  var axi4Driver: Axi4Master = null
  var tlDriver: MasterAgent = null

  def init() = {
    axi4Driver = Axi4Master(dut.axi, hostCd)
    axi4Driver.reset()
    tlDriver = new MasterAgent(dut.tlBus.node.bus, hostCd)
    dspCd.forkStimulus(10)
    hostCd.forkStimulus(50)

    rstUp()
    hostCd.waitRisingEdge(10)
    rstDown()
  }

  def rstUp() = {
    dspCd.assertReset()
    hostCd.assertReset()
    dut.riscq_rst #= true
  }

  def rstDown() = {
    dspCd.deassertReset()
    hostCd.deassertReset()
    dut.riscq_rst #= false
  }

  def loadMem(addr: Long, insts: Seq[String]) = {
    var writeAddr = addr
    for (inst <- insts) {
      val instInt = BigInt(inst, 2)
      dut.mem.mem.setBigInt(writeAddr, instInt)
      writeAddr += 1
    }
  }

  def getDMem(addr: Long): BigInt = {
    return dut.mem.mem.getBigInt(addr)
  }

  def loadInsts(addr: Long, insts: Seq[String]) = {
    var writeAddr = addr
    for (inst <- insts) {
      val instInt = BigInt(inst, 2)
      dut.mem.mem.setBigInt(writeAddr, instInt)
      writeAddr += 1
    }
  }

  def tick(t: Int = 1) = {
    dspCd.waitRisingEdge(t)
  }

  def waitUntil(t: Int) = {
    while (dutTime < t) {
      tick()
    }
  }

  def logRf() = {
    val n = wb.rf.mem.wordCount
    val res = (0 until n).map { x => wb.rf.mem.getBigInt(x).toString() }.toList
    println(s"rf: ${res}")
  }

  def logPc() = {
    val pc = wb.pc.output.toBigInt.toString(16)
    val valid = wb.pc.valid.toBoolean
    println(s"pc: ${pc}, v: ${valid}")
  }

  def logBranch() = {
    val doit = wb.branch.doIt.toBoolean
    println(s"br doit: $doit")
  }

  def logPcs() = {
    for (data <- wb.pcs.data) {
      println(
        s"pc ${data.pc.toBigInt.toString(16)}, v: ${data.valid.toBoolean}, r: ${data.ready.toBoolean}, f: ${data.forgetOne.toBoolean}, ${data.ctrlName}"
      )
    }
  }
  
  def getRf(i: Int): BigInt = {
    return wb.rf.mem.getBigInt(i)
  }

  def logExInsts() = {
    for (data <- wb.exInsts.data) {
      println(s"pc ${data.pc.toBigInt.toString(16)}, ${data.inst.toBigInt
          .toString(16)}, v: ${data.valid.toBoolean}, r: ${data.ready.toBoolean}, ${data.ctrlName}")
    }
  }

  def logSrc() = {
    val src1 = wb.src.src1.toBigInt
    val src1k = wb.src.src1k.toBigInt
    val src2 = wb.src.src2.toBigInt
    val src2k = wb.src.src2k.toBigInt
    val addsub = wb.src.addsub.toBigInt
    println(s"src1: $src1, src1k: $src1k, src2: $src2, src2k: $src2k, addsub: $addsub")
  }

  def logHazard() = {
    val rs = wb.hazard.rs.toBoolean
    val fl = wb.hazard.flush.toBoolean
    println(s"rshazard: $rs, flush hazard: $fl")
  }

  def logDac(n: Int) = {
    val dac = dut.rfArea.pgs(n).io.pulse
    val pulse = dac.payload.map { _.r.toDouble }.toList
    println(s"pulse: ${pulse}")
  }

  def dutTime = dut.clintFiber.time.toBigInt

  def logTime() = {
    val time = dutTime
    println(s"time: $time")
  }

  def FREQ_GHZ(f: Double) = (f * (1 << 13)).toInt
}

object QubicTestConfig {
  val simConfig = SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN") // .withFstWave.withTimeSpec(1 ns, 1 ps)
}

object TestPulse extends App {
  import QubicTestConfig._
  simConfig.compile {
    val dut = QubicSoc(
      qubitNum = 2,
      withVivado = false,
      withCocotb = false,
      withWhitebox = true,
      withTest = true
    )
    dut.rfArea.pgs.map { _.io.simPublic() }
    dut.rfArea.startTime.simPublic()
    dut
  }
  .doSim { dut =>
    val driver = new Driver(dut)
    import driver._
    val rvAsm = new RvAssembler(128)
    import rvAsm._
    val qbAsm = new QubicAssembler()
    import qbAsm._

    val insts1 = List(
      // time addr = 0xbff8
      lui(1, 0xc),
      // time #= 0
      sw(0, -8, 1),
      // timecmp addr = 0x4000
      lui(5, 0x4),
      // timecmp #= 0
      sw(0, 0, 5)
    )
    def loopbody(i : Int) = {
      val start = (i + 1) * 100
      val addr = 0
      val dur = 4
      val phase = 0
      val freq = (i + 1) * (0.1 * (1 << 13)).toInt
      val amp = 0x7fff
      val id = 2
      val insts = List(
        // timecmp = start - 70
        addi(2, 0, start - 70),
        // MTIMECMP #= timecmp
        sw(2, 0, 5),
        nop, nop, nop, nop,
        // regs(0) := MTIMEWAIT
        lw(0, 8, 5),
        nop, nop, nop, nop,
        pulse(start, addr, dur, phase, freq, amp, id),
      )
      insts
    }
    val loop = List.tabulate(5)(loopbody).flatten
    val insts = insts1 ++ loop ++ List(
      // set done flag, and then loop forever
      addi(5, 0, 1),
      beq(0, 0, 0)
    )

    init()
    rstUp()

    val batchSize = 16
    val dataWidth = 16
    val id = 2
    for (i <- 0 until 100) {
      val dt = if (i == 0) BigInt(1 << 12) else BigInt((1 << 15) - 1)
      val batch = List.fill(batchSize)(dt)
      val dataStr = batch.map { x => ByteHelper.intToBinStr(x, dataWidth) }.reduce { _ ++ _ }
      dut.pulseMemFiber.pulseMems(id).mem.setBigInt(i, BigInt(dataStr, 2))
    }
    hostCd.waitRisingEdge()

    loadInsts(0, insts)
    tick(10)
    rstDown()

    for (t <- 100 until 501 by 100) {
      waitUntil(t - 1)
      for (i <- 0 until 6) {
        logTime()
        logDac(id)
        println("")
        tick()
      }
    }

    tick(20)
    println(f"done flag = ${getRf(5)}")
  }
}
