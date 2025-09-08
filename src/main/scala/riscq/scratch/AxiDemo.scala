package scratch

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SpecRenamer, Axi4ToTilelinkFiber}
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import spinal.lib.bus.tilelink.BusParameter
import spinal.lib.bus.tilelink.fabric.RamFiber
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber
import riscq.misc.Axi4VivadoHelper
import riscq.network.AxiToTileLinkDriver
import spinal.lib.bus.amba4.axi.Axi4CrossbarFactory
import spinal.lib.bus.misc.SizeMapping

class AxiDemoM extends Component {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
  )
  val axi = master(Axi4(axiConfig))
}

class AxiDemoS extends Component {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
  )
  val axi = slave(Axi4(axiConfig))
}

class AxiDemoT extends Component {
  val m = new AxiDemoM()
  val s = new AxiDemoS()
  s.axi << m.axi
}

object AxiDemo extends App {
  SpinalVerilog(new AxiDemoT)
}

case class AxiToTileLinkFactory() extends Component {
  val axi = slave(Axi4(Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
  )))

  val outPort = out port Bits(32 bits)

  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load axi

  val driverNode = tilelink.fabric.Node.up()
  driverNode.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val dataWidth = 32
  driverNode at 0 of bridge.down
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
    factory.drive(outPort, 0)
  }
}

object TestAxiToTileLinkFactory extends App {
  SimConfig.compile(AxiToTileLinkFactory()).doSim { dut =>
    val cd = dut.clockDomain
    val axi4Driver = Axi4Master(dut.axi, cd)
    cd.forkStimulus(10)
    cd.assertReset()
    axi4Driver.reset()
    cd.waitSampling(10)
    cd.deassertReset()

    val mon = fork {
      for(i <- 0 until 10) {
        println(s"${dut.outPort.toBigInt.toString(16)}")
        cd.waitSampling()
      }
    }
    axi4Driver.write(0x0, List(0x12, 0x34, 0x56, 0x78))
    mon.join()
  }
}

case class AxiToTileLinkTestNode() extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
    )))
  }

  val data = Reg(Bits(32 bits)) init 0x0

  val driver = AxiToTileLinkDriver{factory =>
    factory.readAndWrite(data, 0)
  }
  driver.axi << io.axi
}

case class AxiCrossBarToTileLinkTest() extends Component {
  val io = new Bundle {
    val axi = slave(Axi4(Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
    )))
    val ledB = out port Bool()
    val ledR = out port Bool()
  }
  riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")

  val slaveNode = AxiToTileLinkTestNode()
  val masterNode = AxiToTileLinkTestNode()

  val axiCrossbar = Axi4CrossbarFactory()
  axiCrossbar.addSlaves(
    slaveNode.io.axi -> SizeMapping(0x00000000L, 1 << 8),
    masterNode.io.axi -> SizeMapping(0x08000000L, 1 << 8)
  )
  axiCrossbar.addConnections(
    io.axi -> List(slaveNode.io.axi, masterNode.io.axi)
  )
  axiCrossbar.addPipelining(slaveNode.io.axi)((crossbar, slaveNode) => {
    crossbar.readCmd >/-> slaveNode.readCmd
    crossbar.readRsp <-/< slaveNode.readRsp
  })((crossbar, slaveNode) => {
    crossbar.writeCmd >/-> slaveNode.writeCmd
    crossbar.writeData >/-> slaveNode.writeData
    crossbar.writeRsp <-/< slaveNode.writeRsp
  })
  axiCrossbar.addPipelining(masterNode.io.axi)((crossbar, masterNode) => {
    crossbar.readCmd >/-> masterNode.readCmd
    crossbar.readRsp <-/< masterNode.readRsp
  })((crossbar, masterNode) => {
    crossbar.writeCmd >/-> masterNode.writeCmd
    crossbar.writeData >/-> masterNode.writeData
    crossbar.writeRsp <-/< masterNode.writeRsp
  })
  axiCrossbar.build()

  io.ledR := (masterNode.data.pull())(0)
  io.ledB := (slaveNode.data.pull())(0)
}

object GenAxiCrossBarToTileLinkTest extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    AxiCrossBarToTileLinkTest()
  )
}

object TestAxiCrossBarToTileLinkTest extends App {
  SimConfig.compile(AxiCrossBarToTileLinkTest()).doSim { dut =>
    val cd = dut.clockDomain
    val axi4Driver = Axi4Master(dut.io.axi, cd)
    cd.forkStimulus(10)
    cd.assertReset()
    axi4Driver.reset()
    cd.waitSampling(10)
    cd.deassertReset()

    val addr = 0x08000000L
    // val addr = 0x00000000L

    axi4Driver.write(addr, List(3, 0, 0, 0))
    println(s"${dut.io.ledR.toBoolean} ${dut.io.ledB.toBoolean}")
    cd.waitSampling(1)
    axi4Driver.write(addr, List(0, 0, 0, 0))
    println(s"${dut.io.ledR.toBoolean} ${dut.io.ledB.toBoolean}")
  }
}