package riscq.tester.mmsoc

import spinal.core.sim._
import spinal.core._
import spinal.lib.bus.tilelink.sim._
import spinal.lib.bus.tilelink._
import spinal.lib.misc.Elf
import java.io.File
import riscq._
import riscq.soc.MemoryMapSoc
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.tester.RvAssembler
import riscq.tester.QubicAssembler
import riscq.tester.ByteHelper
import riscq.soc.MemoryMapPlugins
import net.fornwall.jelf.ElfSectionHeader

class Driver(dut: MemoryMapSoc) {
  implicit val idAllocator = new IdAllocator(DebugId.width)
  implicit val idCallback = new IdCallback
  val cd = dut.clockDomain
  val cd100m = dut.cd100m

  val wb = dut.core.host[test.WhiteboxerPlugin].logic
  val puop = MemoryMapPlugins.puop

  var axi4Driver: Axi4Master = null
  var tlDriver: MasterAgent = null

  def init() = {
    axi4Driver = Axi4Master(dut.axi, cd100m)
    axi4Driver.reset()
    tlDriver = new MasterAgent(dut.tlBus.node.bus, cd100m)
    cd.forkStimulus(10)
    cd100m.forkStimulus(50)

    cd.assertReset()
    cd100m.assertReset()
    cd100m.waitRisingEdge(10)
    cd.deassertReset()
    cd100m.deassertReset()
  }

  def rstUp() = {
    cd.assertReset()
  }

  def rstDown() = {
    cd.deassertReset()
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

  def tick(t: Int = 1) = {
    cd.waitRisingEdge(t)
  }

  def waitUntil(t: Int) = {
    while(dutTime < t) {
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
    val dac = dut.pgs(n).io.data
    val dacCarrier = dut.pgs(n).io.carrier
    val carrier = dacCarrier.payload.map {_.r.toDouble}.toList
    val event = dut.pgs(n).io.event.payload
    val pulse = dac.payload.map { _.r.toDouble * (1 << 14) }.toList
    println(s"pulse ev: v: ${dut.pgs(n).io.event.valid.toBoolean}, amp: ${event.cmd.amp.toBigInt}, start: ${event.start.toBigInt}")
    println(s"pulse: ${pulse}")
  }

  def logCarrier(n: Int) = {
    val carrier = dut.cgs(n).io.carrier
    val data = carrier.payload.map { _.r.toDouble * (1 << 14) }.toList
    println(s"carrier ev: v: ${dut.cgs(n).io.cmd.valid.toBoolean}, freq: ${dut.cgs(n).io.cmd.payload.freq.toDouble}, phase: ${dut.cgs(n).io.cmd.payload.phase.toDouble}")
    println(s"${data}")
  }

  def dutTime = dut.mmFiber.logic.time.toBigInt

  def logTime() = {
    val time = dutTime
    println(s"time: $time")
  }

  def FREQ_GHZ(f: Double) = (f * (1 << 13)).toInt
}

object MMSocTestConfig {
  val simConfig = SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN") // .withFstWave.withTimeSpec(1 ns, 1 ps)
}


// println(s"${dut.cd100mLogic.iMemTlFiber.up.bus.a.valid.toBoolean}")
object TestMMSocPulse extends App {
  import MMSocTestConfig._
  simConfig
    .compile {
      val dut = MemoryMapSoc(
        qubitNum = 4,
        withVivado = false,
        withCocotb = false,
        withWhitebox = true,
        withTest = true
      ) 
      dut.pgs.map { _.io.simPublic() }
      dut.cgs.map { _.io.simPublic() }
      dut
    }
    .doSim { dut =>
      val driver = new Driver(dut)
      import driver._

      init()


      val batchSize = 16
      val dataWidth = 16
      for (i <- 0 until 100) {
        val dt = if (i == 0) BigInt(10) else BigInt(1000)
        val batch = List.fill(batchSize)(dt)
        val batchData = riscq.pulse.PGTestPulse.concat(batch, dataWidth)
        val dataStr = batch.map { x => ByteHelper.intToBinStr(x, dataWidth) }.reduce { _ ++ _ }
        tlDriver.putFullData(0, dut.pmAxiOffset + (1 << 21) + i * batchSize * dataWidth / 8, ByteHelper.fromBinStr(dataStr).reverse)
        // axi4Driver.write(dut.pmAxiOffset + i * batchSize * dataWidth / 8, ByteHelper.fromBinStr(dataStr).reverse.toList)
      }
      cd100m.waitRisingEdge()


      val elfFile = new File("compiler-scripts/pg_test.elf")
      val elf = new Elf(elfFile, addressWidth = 32)
      elf.load(dut.mem.mem, -0x80000000)

      val startTime = 50

      dut.riscq_rst #= true
      tick(10)
      dut.riscq_rst #= false

      val monitor = new Monitor(dut.dBus.bus, cd)
      val pcReset = 0x80000000l
      monitor.add(new MonitorSubscriber {
        override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
        override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
      })

      tick(30)
      for(t <- 100 until 500 by 100) {
        waitUntil(t - 1)
        for(i <- 0 until 10) {
          logTime()
          logDac(1)
          logCarrier(1)
          tick()
        }
      }
        
      // waitUntil(99)
      for (i <- 0 until 10) {
        // println(s"${dut.mem.mem.getBigInt(0).toString(16)}")
        // println(s"${dut.mem.mem.getBigInt(0x1d).toString(16)}")
        // println(s"${dut.mmFiber.logic.cgParams(0).cgIo.freq.toDouble}")
        // println(s"bypass: ${wb.hazard.bypass_1.map{case (id, data) => (id, data.toBoolean)}}")
        logTime()
        // logBranch()
        // logHazard()
        // logPcs()
        // logSrc()
        // logExInsts()
        logDac(2)
        println("")
        tick()
      }
    }
}

object TestMMSocReadout extends App {
  import MMSocTestConfig._
  simConfig
    .compile {
      val dut = MemoryMapSoc(
        qubitNum = 4,
        withVivado = false,
        withCocotb = false,
        withWhitebox = true,
        withTest = true
      ) 
      dut.pgs.map { _.io.simPublic() }
      dut.cgs.map { _.io.simPublic() }
      dut.rds.map { _.io.simPublic() }
      dut
    }
    .doSim { dut =>
      val driver = new Driver(dut)
      import driver._

      init()

      val adc_id = 2
      val adcLogic = fork {
        def freq_ghz(f: Double) = f * math.Pi
        val freq = freq_ghz(0.1)
        // f ghz
        // t + 1 -> time + 2ns -> phase + 2 * f * 2 pi = f * 4 pi

        // t+1 -> phase + 4 * freq * pi
        // 0.1ghz
        // 1ns -> phase + 0.1 * 2pi
        // 2ns -> phase + 0.2 * 2pi = 4 point
        // 1point -> phase + 0.1 * pi
        val amp = 1.0
        val batchSize = 4
        val phaseAdc = 0.0
        while(true) {
          for(i <- 0 until batchSize) {
            val time = dutTime - 19
            val ar = math.cos((time * batchSize + i).toDouble * freq + phaseAdc)
            val ai = -math.sin((time * batchSize + i).toDouble * freq + phaseAdc)
            dut.test_adc(adc_id)(i).r #= ar * amp
            dut.test_adc(adc_id)(i).i #= ai * amp
          }
          cd.waitSampling()
        }
      }

      val elfFile = new File("compiler-scripts/rd_test.elf")
      val elf = new Elf(elfFile, addressWidth = 32)
      elf.load(dut.mem.mem, -0x80000000)

      val startTime = 50

      dut.riscq_rst #= true
      tick(10)
      dut.riscq_rst #= false

      val monitor = new Monitor(dut.dBus.bus, cd)
      val pcReset = 0x80000000l
      monitor.add(new MonitorSubscriber {
        override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
        override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
      })

      tick(30)
      waitUntil(60)
      for (i <- 0 until 60) {
        // println(s"${dut.mem.mem.getBigInt(0).toString(16)}")
        // println(s"${dut.mem.mem.getBigInt(0x1d).toString(16)}")
        // println(s"${dut.mmFiber.logic.cgParams(0).cgIo.freq.toDouble}")
        // println(s"bypass: ${wb.hazard.bypass_1.map{case (id, data) => (id, data.toBoolean)}}")
        logTime()
        // logBranch()
        // logHazard()
        // logPcs()
        // logSrc()
        // logExInsts()
        // logDac(2)
        // logCarrier(10)
        println(s"cmdv: ${dut.rds(2).io.cmd.valid.toBoolean}, cmdp: ${dut.rds(2).io.cmd.payload.toBigInt}, rspv: ${dut.rds(2).io.res.valid.toBoolean}, rspp: ${dut.rds(2).io.res.payload.toBoolean}")
        println(s"carrier: ${dut.rds(2).io.carrier.map{c => c.r.toDouble}}")
        println(s"adc: ${dut.rds(2).io.adc.map{c => c.r.toDouble}}")
        println(s"demod: ${dut.rds(2).io.demodData.payload.map{c => c.r.toDouble}}")
        println("")
        tick()
      }

      for(i <- 0 until dut.qubitNum * 3) {
        println(s"$i: ${dut.cgs(i).spec.batchSize}")
      }
    }
}