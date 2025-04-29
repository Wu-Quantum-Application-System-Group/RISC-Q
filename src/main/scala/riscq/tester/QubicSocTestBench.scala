package riscq.tester.qubic

import spinal.core.sim._
import spinal.core._
import spinal.lib.bus.tilelink.sim._
import spinal.lib.bus.tilelink._
import riscq._
import riscq.soc.QubicSoc
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.tester.RvAssembler
import riscq.tester.QubicAssembler
import riscq.tester.ByteHelper
import riscq.soc.QubicSocParams._

class Driver(dut: QubicSoc) {
  implicit val idAllocator = new IdAllocator(DebugId.width)
  implicit val idCallback = new IdCallback
  val cd = dut.clockDomain
  val cd100m = dut.cd100m

  val wb = dut.core.host[test.WhiteboxerPlugin].logic

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

  def loadIMem(addr: Long, insts: Seq[String]) = {
    var writeAddr = addr
    for (inst <- insts) {
      val instInt = BigInt(inst, 2)
      dut.mem.mem.setBigInt(writeAddr, instInt)
      writeAddr += 1
    }
  }

  def tick(t: Int = 1) = {
    cd.waitRisingEdge(t)
  }

  /*
  def logDac(n: Int) = {
    val dac = dut.pgs(n).io.pulse
    val pulse = dac.payload.map { _.r.toDouble }.toList
    println(s"pulse: ${pulse}")
  }*/
}

object QubicTestConfig {
  val simConfig = SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN") // .withFstWave.withTimeSpec(1 ns, 1 ps)
}

// // println(s"${dut.cd100mLogic.iMemTlFiber.up.bus.a.valid.toBoolean}")
object TestQubicPulse extends App {
  import QubicTestConfig._
  simConfig
    .compile{
      val dut = QubicSoc(
        qubitNum = 8,
        withVivado = false,
        withCocotb = false,
        withWhitebox = true,
        withTest = true
      )
      //dut.pgs.map{_.io.simPublic()}
      //dut.cgs.map{_.io.simPublic()}
      dut
    }
    .doSim { dut =>
      val driver = new Driver(dut)
      import driver._
      val rvAsm = new RvAssembler(128)
      import rvAsm._
      val qbAsm = new QubicAssembler()
      import qbAsm._

      init()
      dut.riscq_rst #= true

      val batchSize = 16
      val dataWidth = 16
      val id = 2
      for (i <- 0 until 100) {
        val dt = if (i == 0) BigInt(1 << 12) else BigInt((1 << 15) - 1)
        val batch = List.fill(batchSize)(dt)
        val dataStr = batch.map { x => ByteHelper.intToBinStr(x, dataWidth) }.reduce { _ ++ _ }
        tlDriver.putFullData(
          0,
          dut.pmAxiOffset + (1 << 20) * id + i * batchSize * dataWidth / 8,
          ByteHelper.fromBinStr(dataStr).reverse
        )
      }
      cd100m.waitRisingEdge()

      val startTime = 50
      val insts = List(
        setTime(0), // 0
        carrier(1 << (16 - 5), 0), // 1
        pulse(
          start = startTime,
          addr = 0,
          duration = 4,
          phase = (1 << (pulsePhaseWidth - 7)),
          freq = 0,
          amp = (1 << (pulseAmpWidth - 1)) - 1
        ), // 2
        beq(0, 0, 0) // 3
      )
      loadIMem(0, insts)

      dut.riscq_rst #= true
      tick()
      dut.riscq_rst #= false

      tick(startTime+8)
      for (i <- 0 until 6) {
        //logDac(0)
        println("")
        tick()
      }
    }
}
// object QubicTest extends App {
//   implicit val idAllocator = new IdAllocator(DebugId.width)
//   implicit val idCallback = new IdCallback
//   val simConfig = SpinalSimConfig()
//   simConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")
//   val puop = execute.PulseOpParam(
//     addrWidth = 12,
//     startWidth = 32,
//     durationWidth = 12,
//     phaseWidth = 16,
//     freqWidth = 16,
//     ampWidth = 16,
//     idWidth = 5
//   )

//   def doTest() = {
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut
//     }
//     compiled.doSim{ dut =>
//       val cd = dut.clockDomain
//       cd.forkStimulus(10)

//       cd.waitSampling()
//       cd.assertReset()
//       cd.waitRisingEdge()
//       cd.deassertReset()

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic
//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd)
//       val monitor = new Monitor(dut.tlBus.node.bus, cd)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       monitor.add(new MonitorSubscriber {
//         override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
//         override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
//       })
//       // val memMonitor = new Monitor(dut.memFiber.up.bus, cd)
//       // memMonitor.add(new MonitorSubscriber {
//       //   override def onA(a: TransactionA) = {println(s"membus: ${simTime()}"); println(a)}
//       //   override def onD(d: TransactionD) = {println(s"membus: ${simTime()}"); println(d)}
//       // })

//       println(s"inst0: ${dut.iMem.mem.getBigInt(0l).toString(2)}")
//       val tInst = Qubic.timerInst(123)
//       println(tInst)
//       driver.putFullData(1, pcReset, ByteHelper.fromBinStr(tInst).reverse)
//       cd.waitSampling(10)
//       println(s"inst0: ${dut.iMem.mem.getBigInt(0l).toString(2)}")
//       pc.simSetPc(pcReset - 16)

//       for(i <- 0 until 20) {
//         sleep(1)
//         println(s"pc: state: ${wbp.pc.state.toBigInt.toString(16)}, output: ${wbp.pc.output.toBigInt.toString(16)}, r: ${wbp.pc.ready.toBoolean}, v: ${wbp.pc.valid.toBoolean}")
//         println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         println(s"inst: ${wbp.decode.inst.toBigInt.toString(2)}")
//         println(s"time: ${simTime()}, timer: ${wbp.timer.time.toLong}, SEL: ${wbp.timer.SEL.toBoolean}")
//         // // println(s"pulse data: ${wbp.pg.get.pgdata.payload.map(_.toLong)}")
//         // val ev = wbp.pg.get.pgevent
//         // // println(s"pulse event: v:${ev.valid.toBoolean}, start:${ev.start.toBigInt}, addr:${ev.cmd.addr.toBigInt}, dur:${ev.cmd.duration.toBigInt}, phase:${ev.cmd.phase.toBigInt}, freq:${ev.cmd.freq.toBigInt}, amp:${ev.cmd.amp.toBigInt}")
//         // // println(s"pulse mem: ${pg.pgmem.getBigInt(1).toString(2)}")
//         // // println(s"pulse timer: ${pg.pgtimer.toBigInt}")
//         // // println(s"pulse valid: ${pg.pgdata.valid.toBoolean}")
//         println("")
//         cd.waitSampling()
//       }
//     }
//   }

//   def doTestAlu() = {
//     val simConfig = SpinalSimConfig()
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut
//     }
//     compiled.doSim{ dut =>
//       val cd = dut.clockDomain
//       cd.forkStimulus(10)

//       cd.waitSampling()
//       cd.assertReset()
//       cd.waitRisingEdge()
//       cd.deassertReset()

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic
//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd)
//       val monitor = new Monitor(dut.tlBus.node.bus, cd)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       monitor.add(new MonitorSubscriber {
//         override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
//         override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
//       })
//       // val memMonitor = new Monitor(dut.memFiber.up.bus, cd)
//       // memMonitor.add(new MonitorSubscriber {
//       //   override def onA(a: TransactionA) = {println(s"membus: ${simTime()}"); println(a)}
//       //   override def onD(d: TransactionD) = {println(s"membus: ${simTime()}"); println(d)}
//       // })

//       val addi123_0_7 = RvInst.addiInst(123, rs1 = 0, rd = 7)
//       val addi321_0_6 = RvInst.addiInst(321, rs1 = 0, rd = 6)
//       val add6_7_7 = RvInst.addInst(rs1 = 6, rs2 = 7, rd = 7)
//       val sub6_7_7 = RvInst.subInst(rs1 = 6, rs2 = 7, rd = 7)
//       val add7123 = RvInst.addiInst(123, rs1 = 7, rd = 7)
//       driver.putFullData(1, pcReset, ByteHelper.fromBinStr(addi123_0_7).reverse)
//       driver.putFullData(1, pcReset+16, ByteHelper.fromBinStr(addi321_0_6).reverse)
//       driver.putFullData(1, pcReset+32, ByteHelper.fromBinStr(sub6_7_7).reverse)
//       cd.waitSampling(10)
//       println(s"inst0: ${dut.iMem.mem.getBigInt(0l).toString(2)}")
//       pc.simSetPc(pcReset - 16)

//       for(i <- 0 until 10) {
//         sleep(1)
//         println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         println(s"rs1: ${wbp.rs.rs1.toBigInt}, ${wbp.rs.rs1e.toBoolean}, ${wbp.rs.rs1p.toBigInt}, ${wbp.rs.rs1i.toBigInt}")
//         println(s"rs2: ${wbp.rs.rs2.toBigInt}, ${wbp.rs.rs2e.toBoolean}, ${wbp.rs.rs2p.toBigInt}, ${wbp.rs.rs2i.toBigInt}")
//         // println(s"rsp: ${wbp.rs.rsp(0).address.toBigInt},${wbp.rs.rsp(0).data.toBigInt},${wbp.rs.rsp(0).valid.toBoolean}, ")
//         println(s"src: ${wbp.src.src1.toBigInt},${wbp.src.src2.toBigInt},${wbp.src.addsub.toBigInt},${wbp.src.src1k.toBigInt},${wbp.src.src2k.toBigInt},")
//         println(s"hazard: rs:${wbp.hazard.rs.toBoolean},flush:${wbp.hazard.flush.toBoolean}")
//         println(s"inst: ${wbp.decode.inst.toBigInt.toString(2)}")
//         println(s"wb0: ${wbp.wb(0).data.toInt}, ${wbp.wb(0).valid.toBoolean}")
//         // println(s"wb1: ${wbp.wb(1).data.toInt}, ${wbp.wb(1).valid.toBoolean}")
//         // println(s"bye: ${wbp.rs.bpse.map(_.toBigInt.toString(2).reverse)}")
//         println("")
//         cd.waitSampling()
//       }
//     }
//   }

//   def doTestBranch() = {
//     val simConfig = SpinalSimConfig()
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut
//     }
//     compiled.doSim{ dut =>
//       val cd = dut.clockDomain
//       cd.forkStimulus(10)

//       cd.waitSampling()
//       cd.assertReset()
//       cd.waitRisingEdge()
//       cd.deassertReset()

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic
//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd)
//       val monitor = new Monitor(dut.tlBus.node.bus, cd)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       monitor.add(new MonitorSubscriber {
//         override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
//         override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
//       })
//       // val memMonitor = new Monitor(dut.memFiber.up.bus, cd)
//       // memMonitor.add(new MonitorSubscriber {
//       //   override def onA(a: TransactionA) = {println(s"membus: ${simTime()}"); println(a)}
//       //   override def onD(d: TransactionD) = {println(s"membus: ${simTime()}"); println(d)}
//       // })

//       val addi123_0_7 = RvInst.addiInst(123, rs1 = 0, rd = 7)
//       val addi321_0_6 = RvInst.addiInst(321, rs1 = 0, rd = 6)
//       val add6_7_7 = RvInst.addInst(rs1 = 6, rs2 = 7, rd = 7)
//       val sub6_7_7 = RvInst.subInst(rs1 = 6, rs2 = 7, rd = 7)
//       val add7123 = RvInst.addiInst(123, rs1 = 7, rd = 7)
//       val beq00 = RvInst.beqInst(16, rs1 = 0, rs2 = 0)
//       driver.putFullData(1, pcReset, ByteHelper.fromBinStr(addi123_0_7).reverse)
//       driver.putFullData(1, pcReset+16, ByteHelper.fromBinStr(addi321_0_6).reverse)
//       driver.putFullData(1, pcReset+32, ByteHelper.fromBinStr(beq00).reverse)
//       driver.putFullData(1, pcReset+48, ByteHelper.fromBinStr(add7123).reverse)
//       cd.waitSampling(10)
//       pc.simSetPc(pcReset - 16)

//       for(i <- 0 until 10) {
//         sleep(1)
//         println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         println(s"rs1: ${wbp.rs.rs1.toBigInt}, ${wbp.rs.rs1e.toBoolean}, ${wbp.rs.rs1p.toBigInt}, ${wbp.rs.rs1i.toBigInt}")
//         println(s"rs2: ${wbp.rs.rs2.toBigInt}, ${wbp.rs.rs2e.toBoolean}, ${wbp.rs.rs2p.toBigInt}, ${wbp.rs.rs2i.toBigInt}")
//         // println(s"rsp: ${wbp.rs.rsp(0).address.toBigInt},${wbp.rs.rsp(0).data.toBigInt},${wbp.rs.rsp(0).valid.toBoolean}, ")
//         println(s"src: ${wbp.src.src1.toBigInt},${wbp.src.src2.toBigInt},${wbp.src.addsub.toBigInt},${wbp.src.src1k.toBigInt},${wbp.src.src2k.toBigInt},")
//         println(s"hazard: rs:${wbp.hazard.rs.toBoolean},flush:${wbp.hazard.flush.toBoolean},valid0:${wbp.hazard.valid0.toBoolean}")
//         println(s"inst: ${wbp.decode.inst.toBigInt.toString(2)}")
//         println(s"wb0: ${wbp.wb(0).data.toInt}, ${wbp.wb(0).valid.toBoolean}")
//         // println(s"wb1: ${wbp.wb(1).data.toInt}, ${wbp.wb(1).valid.toBoolean}")
//         println(s"time: ${simTime()}, timer: ${wbp.timer.time.toLong}, SEL: ${wbp.timer.SEL.toBoolean}")
//         println("")
//         cd.waitSampling()
//       }
//     }
//   }

//   def doTestLsu() = {
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut
//     }
//     compiled.doSim(seed = 705040262){ dut =>
//     // compiled.doSim{ dut =>
//       val cd = dut.clockDomain
//       val cd100m = dut.cd100m
//       cd.forkStimulus(10)
//       cd100m.forkStimulus(50)

//       cd100m.waitSampling()
//       cd.assertReset()
//       dut.riscq_rst #= true
//       cd100m.assertReset()
//       cd100m.waitRisingEdge()
//       cd.deassertReset()
//       dut.riscq_rst #= false
//       cd100m.deassertReset()
//       cd100m.waitRisingEdge()
//       dut.riscq_rst #= true

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic
//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd100m)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       val pcAxiOffset = 0x01000000l

//       val monitor = new Monitor(dut.cd100mLogic.dMemTlFiber.up.bus, cd100m)
//       // val monitor = new Monitor(dut.tlBus.node.bus, cd100m)
//       monitor.add(new MonitorSubscriber {
//         override def onA(a: TransactionA) = {println(s"!!!!!!!!!! a${simTime()}"); println(a)}
//         override def onD(d: TransactionD) = {println(s"!!!!!!!!!! d${simTime()}"); println(d)}
//       })

//       val loadX1 = RvInst.addiInst(123, 0, 1)
//       val swX1 = RvInst.swInst(offset = 0, rs1 = 0, 1)
//       val loadX2 = RvInst.addiInst(234, 0, 2)
//       val swX2 = RvInst.swInst(offset = 4, rs1 = 0, 2)
//       val lwX3 = RvInst.lwInst(offset = 4, rs1 = 0, rd = 3)
//       val lwX4 = RvInst.lwInst(offset = 0, rs1 = 0, rd = 4)
//       val add345 = RvInst.addInst(3, 4, 5)
//       val swX5 = RvInst.swInst(12, rs1 = 0, rs2 = 5)
//       val setTime = Qubic.timerInst(0)
//       val wait = Qubic.waitInst(20)
//       val stallI = RvInst.beqInst(0, 0, 0)
//       // writeInsts(driver, pcAxiOffset + 16, List(setTime, loadX1, swX1, loadX2, swX2, stallI))
//       writeInsts(driver, pcAxiOffset, List(setTime, loadX1, swX1, loadX2, swX2, wait, lwX3, lwX4, add345, swX5, stallI))
//       println(s"!!!!!!!! load inst ${simTime()}")
//       cd100m.waitRisingEdge(1000)
//       println(s"${dut.iMem.mem.getBigInt(0).toString(16)}")
//       println(s"!!!!!!!! load inst ${simTime()}")
//       cd.assertReset()
//       cd.waitRisingEdge()
//       cd.deassertReset()
//       // pc.simSetPc(pcReset)
//       dut.riscq_rst #= false
//       // cd.waitRisingEdge(7)

//       println(s"${dut.iMem.mem.getBigInt(0).toString(16)}")
//       for(i <- 0 until 40) {
//         sleep(2)
//         println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         println(s"inst-2: ${wbp.insts.ex_2.toBigInt.toString(2)}, ${wbp.insts.v_2.toBoolean}, ${wbp.insts.r_2.toBoolean}")
//         println(s"inst-1: ${wbp.insts.ex_1.toBigInt.toString(2)}, ${wbp.insts.v_1.toBoolean}, ${wbp.insts.r_1.toBoolean}")
//         println(s"inst0: ${wbp.insts.ex0.toBigInt.toString(2)}, ${wbp.insts.v0.toBoolean}, ${wbp.insts.r0.toBoolean}")
//         println(s"inst1: ${wbp.insts.ex1.toBigInt.toString(2)}, ${wbp.insts.v1.toBoolean}, ${wbp.insts.r1.toBoolean}, b: ${wbp.wbp.DATA1.toBigInt}, l: ${wbp.lsu.READ_DATA1.toBigInt}")
//         println(s"inst2: ${wbp.insts.ex2.toBigInt.toString(2)}, ${wbp.insts.v2.toBoolean}, ${wbp.insts.r2.toBoolean}, b: ${wbp.wbp.DATA2.toBigInt}, l: ${wbp.lsu.READ_DATA2.toBigInt}")
//         println(s"inst3: ${wbp.insts.ex3.toBigInt.toString(2)}, ${wbp.insts.v3.toBoolean}, ${wbp.insts.r3.toBoolean}")
//         // println(s"hazard: rs: ${wbp.hazard.rs.toBoolean}, flush: ${wbp.hazard.flush.toBoolean}")
//         // println(s"pc: ${wbp.pc.ready.toBoolean}, ${wbp.pc.valid.toBoolean}")
//         // println(s"branch: ${wbp.branch.doIt.toBoolean}")
//         println(s"dmem: ${dut.dMem.mem.getBigInt(0)}, ${dut.dMem.mem.getBigInt(1)}, ${dut.dMem.mem.getBigInt(2)}, ${dut.dMem.mem.getBigInt(3)}")
//         println(s"bypass: ${wbp.hazard.bypass_1.map{case (id, data) => (id, data.toBoolean)}}")
//         // println(s"wbdata: ${wbp.wbp.DATA.map{case (id, data) => (id, data.toBigInt)}}")
//         println(s"rf0: ${wbp.rf.mem.getBigInt(3)}, ${wbp.rf.mem.getBigInt(4)}")
//         println(s"bv: ${wbp.lsu.bv.toBoolean}, sel: ${wbp.lsu.SEL0.toBoolean}, raddr: ${wbp.lsu.RAW_ADDRESS0.toBigInt}, skip: ${wbp.lsu.skip.toBoolean}")
//         println(s"timer: ${wbp.timer.time.toLong}, SEL: ${wbp.timer.SEL.toBoolean}, halt: ${wbp.timer.haltCond.toBoolean}, SEL1: ${wbp.timer.SEL1.toBoolean}, ctrl1: ${wbp.timer.ctrl1.toBigInt}, target: ${wbp.timer.target.toBigInt}, timelt: ${wbp.timer.timeLt.toBoolean}")
//         println(s"src1: ${wbp.src.src1.toBigInt}, src2: ${wbp.src.src2.toBigInt}, src1k: ${wbp.src.src1k.toBigInt}, addsub: ${wbp.src.addsub.toBigInt}, rs1: ${wbp.rs.rs1.toBigInt}")
//         println("")
//         cd.waitRisingEdge()
//       }
//       driver.get(1, 0x08000000L, 4)
//       cd100m.waitRisingEdge(100)
//     }

//   }

//   def doTestTimer() = {
//     val simConfig = SpinalSimConfig()
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut
//     }
//     compiled.doSim{ dut =>
//       val cd = dut.clockDomain
//       cd.forkStimulus(10)

//       cd.waitSampling()
//       cd.assertReset()
//       cd.waitRisingEdge()
//       cd.deassertReset()

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic
//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd)
//       val monitor = new Monitor(dut.tlBus.node.bus, cd)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       monitor.add(new MonitorSubscriber {
//         override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
//         override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
//       })
//       // val memMonitor = new Monitor(dut.memFiber.up.bus, cd)
//       // memMonitor.add(new MonitorSubscriber {
//       //   override def onA(a: TransactionA) = {println(s"membus: ${simTime()}"); println(a)}
//       //   override def onD(d: TransactionD) = {println(s"membus: ${simTime()}"); println(d)}
//       // })

//       val setTime123 = Qubic.timerInst(123)
//       val waitUntil130 = Qubic.waitInst(130)
//       cd.waitSampling(10)
//       driver.putFullData(1, pcReset, ByteHelper.fromBinStr(setTime123).reverse)
//       driver.putFullData(1, pcReset+16, ByteHelper.fromBinStr(waitUntil130).reverse)
//       cd.waitSampling(10)
//       pc.simSetPc(pcReset - 16)

//       for(i <- 0 until 20) {
//         sleep(1)
//         println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         println(s"rs1: ${wbp.rs.rs1.toBigInt}, ${wbp.rs.rs1e.toBoolean}, ${wbp.rs.rs1p.toBigInt}, ${wbp.rs.rs1i.toBigInt}")
//         println(s"rs2: ${wbp.rs.rs2.toBigInt}, ${wbp.rs.rs2e.toBoolean}, ${wbp.rs.rs2p.toBigInt}, ${wbp.rs.rs2i.toBigInt}")
//         // println(s"rsp: ${wbp.rs.rsp(0).address.toBigInt},${wbp.rs.rsp(0).data.toBigInt},${wbp.rs.rsp(0).valid.toBoolean}, ")
//         println(s"src: ${wbp.src.src1.toBigInt},${wbp.src.src2.toBigInt},${wbp.src.addsub.toBigInt},${wbp.src.src1k.toBigInt},${wbp.src.src2k.toBigInt},")
//         println(s"hazard: rs:${wbp.hazard.rs.toBoolean},flush:${wbp.hazard.flush.toBoolean},valid0:${wbp.hazard.valid0.toBoolean}")
//         println(s"inst: ${wbp.decode.inst.toBigInt.toString(2)}")
//         println(s"wb0: ${wbp.wb(0).data.toInt}, ${wbp.wb(0).valid.toBoolean}")
//         // println(s"wb1: ${wbp.wb(1).data.toInt}, ${wbp.wb(1).valid.toBoolean}")
//         println(s"time: ${simTime()}, timer: ${wbp.timer.time.toLong}, SEL: ${wbp.timer.SEL.toBoolean}")
//         println("")
//         cd.waitSampling()
//       }
//     }
//   }

//   def doTestPulse() = {
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut.pgs(0).io.simPublic()
//       dut.pgs(0).memPort.simPublic()
//       dut
//     }
//     // compiled.doSim(seed = 686289100){ dut => // branch
//     // compiled.doSim(seed = 1628712070){ dut => // tilelink
//     compiled.doSim{ dut => // tilelink
//       val cd = dut.clockDomain
//       val cd100m = dut.cd100m
//       cd.forkStimulus(10)
//       cd100m.forkStimulus(50)

//       cd100m.waitSampling()
//       cd.assertReset()
//       cd100m.assertReset()
//       cd100m.waitRisingEdge()
//       cd.deassertReset()
//       cd100m.deassertReset()
//       cd100m.waitRisingEdge()

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic
//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd100m)
//       val monitor = new Monitor(dut.tlBus.node.bus, cd100m)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       val pcAxiOffset = 0x01000000l
//       println(s"123")
//       // monitor.add(new MonitorSubscriber {
//       //   override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
//       //   override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
//       // })

//       val batchSize = 16
//       val dataWidth = 16
//       for(i <- 0 until 100) {
//         val dt = if(i == 0) BigInt(10) else BigInt(1000)
//         val batch = List.fill(batchSize)(dt)
//         val batchData = pulse.PGTestPulse.concat(batch, dataWidth)
//         val dataStr = batch.map{ x => ByteHelper.intToBinStr(x, dataWidth)}.reduce{ _ ++ _}
//         // println(s"${dataStr}")
//         driver.putFullData(0, dut.pulseOffset + i * batchSize * dataWidth / 8, ByteHelper.fromBinStr(dataStr).reverse)
//       }
//       cd100m.waitRisingEdge(1000)

//       val setTime10 = Qubic.timerInst(10)
//       val carrierI = Qubic.carrierInst(1 << (16 - 5), 0)
//       // val pulseI = Qubic.pulseInst(puop, start = 70, addr = 0, duration = 4, phase = (1 << (puop.phaseWidth - 7)), freq = 0, amp = (1 << (puop.ampWidth - 1)) - 1)
//       val pulseI = Qubic.pulseInst(puop, start = 70, addr = 0, duration = 4, phase = (1 << (puop.phaseWidth - 3)), freq = 0, amp = (1 << (puop.ampWidth - 1)) - 1)
//       // val pulseI = Qubic.pulseInst(puop, start = 70, addr = 0, duration = 4, phase = (1 << (puop.phaseWidth - 3)), freq = 0, amp = (1 << (puop.ampWidth - 1)) - 1)
//       val stallI = RvInst.beqInst(0, 0, 0)
//       writeInsts(driver, pcAxiOffset, List(setTime10, carrierI, pulseI, stallI))
//       println(s"!!!!!!!! load inst ${simTime()}")
//       cd100m.waitRisingEdge(100)
//       println(s"!!!!!!!! load inst ${simTime()}")
//       cd.assertReset()
//       cd.waitRisingEdge()
//       cd.deassertReset()
//       // pc.simSetPc(pcReset)
//       dut.riscq_rst #= true
//       cd.waitRisingEdge()
//       dut.riscq_rst #= false

//       cd.waitRisingEdge(67) // pulse data
//       // cd.waitRisingEdge(6) // pulse inst
//       for(i <- 0 until 20) {
//         sleep(3)
//         // println(s"br: doit:${wbp.branch.doIt.toBoolean},sel:${wbp.branch.sel.toBoolean},inst:${wbp.branch.inst.toBigInt.toString(2)},v:${wbp.branch.valid.toBoolean},pc:${wbp.branch.pc.toBigInt.toString(16)}")
//         println(s"pc: s:${wbp.pc.state.toBigInt.toString(16)}, o:${wbp.pc.output.toBigInt.toString(16)}, v:${wbp.pc.valid.toBoolean}")
//         // println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, word1:${wbp.fetch.word1.toBigInt}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         println(s"inst: ${wbp.decode.inst.toBigInt.toString(2)}")
//         // println(s"carrier data: ${wbp.carrier.data.payload.map(_.r.toDouble)}")
//         // println(s"pg carrier: ${wbp.pg.get.pgcarrier.payload.map(_.r.toDouble)}")

//         // println(s"pg carrier: ${dut.pgs(0).io.carrier.payload.map(_.r.toDouble)}")
//         // println(s"pg valid: ${dut.pgs(0).io.event.valid.toBoolean}")
//         // println(s"pg amp: ${dut.pgs(0).io.event.cmd.amp.toBigInt}")
//         // println(s"env data: ${dut.pgs(0).memPort.rsp.toBigInt.toString(2)}")
//         // println(s"pg data: ${dut.pgs(0).io.data.payload.map(_.r.toDouble)}")
//         // println(s"env data: ${dut.pgs(0).memPort.rsp..map(x => if (x) 1 else 0)}")
//         // println(s"pulse data: ${wbp.pg.get.pgdata.payload.map(_.r.toDouble)}")
//         println(s"pulse valid: ${wbp.da.dac(0).valid.toBoolean}")
//         println(s"pulse data: ${wbp.da.dac(0).payload.map(_.r.toDouble * (1 << 14))}")
//         // println(s"pulse data: ${wbp.da.dac(0).payload.map(_.r.toDouble * (1 << 14) + (1 << 15))}")
//         val dac = dut.dac(0).payload.toBigInt.toString(2).reverse.padTo(256, '0')
//         // val dac = dut.dac(0).payload.toBigInt.toString(2).grouped(16).map(s => Integer.parseInt(s, 2)).toList
//         println(s"pulse data: ${dac}")
//         println(s"pulse data: ${dac.reverse.grouped(16).map(Integer.parseInt(_, 2)).toList.reverse}")
//         // val ev = wbp.pg.get.pgevent
//         // println(s"pulse event: v:${ev.valid.toBoolean}, start:${ev.start.toBigInt}, addr:${ev.cmd.addr.toBigInt}, dur:${ev.cmd.duration.toBigInt}, phase:${ev.cmd.phase.toBigInt}, freq:${ev.cmd.freq.toBigInt}, amp:${ev.cmd.amp.toBigInt}")
//         // val pgc = wbp.pg.get.pg.pg
//         // println(s"pg phaseR:${pgc.phaseC.r.toDouble},${pgc.cosSinProbe.valid.toBoolean}")
//         // println(s"pulse mem: ${pg.pgmem.getBigInt(1).toString(2)}")
//         // println(s"mem: ${dut.mem.getBigInt(0).toString(2)}")
//         println(s"time: ${simTime()}, timer: ${wbp.timer.time.toLong}, SEL: ${wbp.timer.SEL.toBoolean}")
//         println(s"hazard: rs: ${wbp.hazard.rs.toBoolean}, flush: ${wbp.hazard.flush.toBoolean}")
//         println(s"pc: ${wbp.pc.ready.toBoolean}, ${wbp.pc.valid.toBoolean}")
//         println(s"branch: ${wbp.branch.doIt.toBoolean}")
//         println("")
//         cd.waitSampling()
//       }
//       println(s"!!!!!pulseInst: ${pulseI}")
//       println(s"!!!!!pulseInst: ${BigInt(pulseI, 2).toString(16)}")
//     }
//   }

//   def doTestReadout() = {
//     val genConfig = SpinalConfig()
//     simConfig.withConfig(genConfig)
//     val compiled = simConfig.compile{
//       val dut = QubicTileLinkSoc()
//       dut
//     }
//     // compiled.doSimUntilVoid{ dut =>
//     // compiled.doSimUntilVoid(seed = 1062322421){ dut =>
//     compiled.doSimUntilVoid(seed = 504547349 ){ dut =>
//       val cd = dut.clockDomain
//       val cd100m = dut.cd100m
//       cd.forkStimulus(10)
//       cd100m.forkStimulus(50)

//       cd100m.waitSampling()
//       cd.assertReset()
//       cd100m.assertReset()
//       cd100m.waitRisingEdge()
//       cd.deassertReset()
//       cd100m.deassertReset()
//       cd100m.waitRisingEdge()

//       val wbp = dut.riscqArea.riscq.host[test.WhiteboxerPlugin].logic

//       val adc_id = 1
//       val freq = 1.0 / 100
//       val amp = 1.0
//       val batchSize = 4
//       val adcLogic = fork {
//         val phaseAdc = 0.0
//         while(true) {
//           for(i <- 0 until batchSize) {
//             val time = wbp.timer.time.toBigInt - 19
//             val ar = math.cos((time * batchSize + i).toDouble * freq * math.Pi + phaseAdc)
//             val ai = -math.sin((time * batchSize + i).toDouble * freq * math.Pi + phaseAdc)
//             dut.test_adc(adc_id)(i).r #= ar * amp
//             dut.test_adc(adc_id)(i).i #= ai * amp
//           }
//           cd.waitSampling()
//         }
//       }

//       val pg = wbp.pg.get

//       DebugId.setup(16)
//       val driver = new MasterAgent(dut.tlBus.node.bus, cd100m)
//       val monitor = new Monitor(dut.tlBus.node.bus, cd100m)
//       val pc = dut.riscqArea.riscq.host[fetch.PcPlugin]
//       val pcReset = 0x80000000l
//       monitor.add(new MonitorSubscriber {
//         override def onA(a: TransactionA) = {println(s"${simTime()}"); println(a)}
//         override def onD(d: TransactionD) = {println(s"!!!!!!!${simTime()}");println(d)}
//       })

//       // val busPrint = fork {
//       //   while(true) {
//       //     println(s"!!!${simTime()} valids: ${dut.shareBus.downs.map(_.down.bus.a.valid.toBoolean)}")
//       //     cd.waitRisingEdge()
//       //   }
//       // }
//       // val memMonitor = new Monitor(dut.memFiber.up.bus, cd)
//       // memMonitor.add(new MonitorSubscriber {
//       //   override def onA(a: TransactionA) = {println(s"membus: ${simTime()}"); println(a)}
//       //   override def onD(d: TransactionD) = {println(s"membus: ${simTime()}"); println(d)}
//       // })

//       val dataWidth = 16

//       val setTime10 = Qubic.timerInst(0)
//       val carrierI = Qubic.carrierInst(((1 << 15) * freq).toInt, 0, id = 4 + adc_id) // latency 33
//       // val carrierI = Qubic.carrierInst(0, 0, 4) // latency 33
//       val wait40 = Qubic.waitInst(60) // > 34 + 20
//       val readI = Qubic.readInst(id = adc_id, time = 4) // latency of read: 16 + compmul
//       val wait10 = Qubic.waitInst(80)
//       val writeR = Qubic.writeRInst(adc_id, 7)
//       val writeI = Qubic.writeIInst(adc_id, 8)
//       val swX7 = RvInst.swInst(4, 0, 7)
//       val swX8 = RvInst.swInst(8, 0, 8)
//       val add7123 = RvInst.addInst(7, 8, 7)
//       val beq00 = RvInst.beqInst(0, 0, 0)
//       // val pulseI = Qubic.pulseInst(puop, start = 45, addr = 0, duration = 4, phase = 123, freq = 1 << (puop.freqWidth - 5), amp = (1 << puop.ampWidth) - 1)
//       cd.waitSampling(10)
//       val insts = List(setTime10, carrierI, wait40, readI, wait10, writeR, writeI, swX7, swX8, add7123, beq00)
//       // val insts = List(setTime10, carrierI, wait40, readI) //, wait10, writeR, writeI, add7123, beq00)
//       writeInsts(driver, 0x01000000L, insts)
//       // driver.putFullData(1, pcReset, ByteHelper.fromBinStr(setTime10).reverse)
//       // driver.putFullData(1, pcReset+16, ByteHelper.fromBinStr(carrierI).reverse)
//       // driver.putFullData(1, pcReset+32, ByteHelper.fromBinStr(wait30).reverse)
//       // driver.putFullData(1, pcReset+48, ByteHelper.fromBinStr(readI).reverse)
//       // driver.putFullData(1, pcReset+64, ByteHelper.fromBinStr(writeR).reverse)
//       // driver.putFullData(1, pcReset+80, ByteHelper.fromBinStr(writeI).reverse)
//       // driver.putFullData(1, pcReset+96, ByteHelper.fromBinStr(add7123).reverse)
//       cd100m.waitSampling(1000)
//       cd.assertReset()
//       dut.riscq_rst #= true
//       cd.waitRisingEdge()
//       cd.deassertReset()
//       dut.riscq_rst #= false
//       cd.waitRisingEdge()

//       cd.waitSampling(55)
//       for(i <- 0 until 40) {
//         sleep(1)
//         println(s"inst-2: ${wbp.insts.ex_2.toBigInt.toString(2)}, ${wbp.insts.v_2.toBoolean}, ${wbp.insts.r_2.toBoolean}, pc: ${wbp.insts.pc_2.toBigInt.toString(16)}")
//         println(s"inst-1: ${wbp.insts.ex_1.toBigInt.toString(2)}, ${wbp.insts.v_1.toBoolean}, ${wbp.insts.r_1.toBoolean}")
//         println(s"inst0: ${wbp.insts.ex0.toBigInt.toString(2)}, ${wbp.insts.v0.toBoolean}, ${wbp.insts.r0.toBoolean}")
//         println(s"inst1: ${wbp.insts.ex1.toBigInt.toString(2)}, ${wbp.insts.v1.toBoolean}, ${wbp.insts.r1.toBoolean}, b: ${wbp.wbp.DATA1.toBigInt}, l: ${wbp.lsu.READ_DATA1.toBigInt}")
//         println(s"inst2: ${wbp.insts.ex2.toBigInt.toString(2)}, ${wbp.insts.v2.toBoolean}, ${wbp.insts.r2.toBoolean}, b: ${wbp.wbp.DATA2.toBigInt}, l: ${wbp.lsu.READ_DATA2.toBigInt}")
//         println(s"inst3: ${wbp.insts.ex3.toBigInt.toString(2)}, ${wbp.insts.v3.toBoolean}, ${wbp.insts.r3.toBoolean}")
//         println(s"br : ${wbp.branch.doIt.toBoolean}")
//         println(s"fetch pc0:${wbp.fetch.pc0.toBigInt.toString(16)}, pc1:${wbp.fetch.pc1.toBigInt.toString(16)}, pc2:${wbp.fetch.pc2.toBigInt.toString(16)}, ${wbp.fetch.inflight.map(_.toBoolean)}")
//         // println(s"carrier data: ${wbp.carrier.data.payload.map(_.r.toDouble * (1 << 14))}")
//         // println(s"adc data: ${dut.test_adc(0).map(_.r.toDouble * (1 << 14))}")
//         println(s"carrier data: ${wbp.readout.carrier.map(_.r.toDouble * (1 << 14))}")
//         println(s"adc data: ${wbp.readout.adc.map(_.r.toDouble * (1 << 14))}")
//         println(s"src: ${wbp.src.src1.toBigInt},${wbp.src.src2.toBigInt},${wbp.src.addsub.toBigInt},${wbp.src.src1k.toBigInt},${wbp.src.src2k.toBigInt},")
//         println(s"hazard: rs:${wbp.hazard.rs.toBoolean},flush:${wbp.hazard.flush.toBoolean}")
//         println(s"readout readD: ${wbp.readout.readR.toBigInt}, accR: ${wbp.readout.accR.toBigInt}, accv: ${wbp.readout.accv.toBoolean}, cmdv: ${wbp.readout.cmdv.toBoolean}")
//         println(s"readout readD: ${dut.readouts(0).r.toBigInt}, ${dut.readouts(0).i.toBigInt}")
//         println(s"wb0: ${wbp.wb(0).data.toInt}, ${wbp.wb(0).valid.toBoolean}")
//         println(s"time: ${simTime()}, timer: ${wbp.timer.time.toLong}, SEL: ${wbp.timer.SEL.toBoolean}, halt: ${wbp.timer.haltCond.toBoolean}, SEL1: ${wbp.timer.SEL1.toBoolean}, ctrl1: ${wbp.timer.ctrl1.toBigInt}, target: ${wbp.timer.target.toBigInt}, timelt: ${wbp.timer.timeLt.toBoolean}")
//         println(s"rob valid: ${dut.rbLogic.valid.toBoolean}")
//         println(s"dmem1: ${dut.dMem.mem.getBigInt(1)}, dmem2: ${dut.dMem.mem.getBigInt(2)}")
//         println("")
//         cd.waitSampling()
//       }
//       def getRobRead(x: BigInt) = {
//         val binstr = x.toString(2).reverse.padTo(128, '0')
//         val nums = binstr.grouped(32).map{x => BigInt(x.reverse, 2) & 0xffff}.map{x => if(x >= 0x8000) (x - 0x10000) else x}
//         nums
//       }
//       // println(s"${(0 until 128).flatMap{x => getRobRead(dut.readoutBuffer.mem.getBigInt(x))}}")

//       // dut.riscq_rst #= true
//       // cd.waitSampling()
//       // dut.riscq_rst #= false
//       // cd.waitSampling(200)
//       // println(s"${(0 until 128).flatMap{x => getRobRead(dut.readoutBuffer.mem.getBigInt(x))}}")
//       simSuccess()
//     }
//   }

//   def writeInsts(driver: MasterAgent, addr: Long, insts: Seq[String]) = {
//     var writeAddr = addr
//     for(inst <- insts) {
//       driver.putFullData(0, writeAddr, ByteHelper.fromBinStr(inst).reverse)
//       writeAddr += 16
//     }
//   }

//   // doTestReadout()
//   doTestPulse()
//   // doTestLsu()
// }
