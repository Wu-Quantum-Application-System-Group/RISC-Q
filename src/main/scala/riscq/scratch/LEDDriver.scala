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

case class AxiLEDDriver() extends Component {
  val axi = slave(Axi4(Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 4,
  )))
  riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS")

  val io = new Bundle {
    val ledR = out Bool()
    val ledB = out Bool()
  }

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
    factory.drive(io.ledR, 0)
    factory.drive(io.ledB, 4)
  }
}

object AxiLEDDriver extends App {
  SpinalVerilog(new AxiLEDDriver)
}

object TestAxiLEDDriver extends App {
  SimConfig.compile(AxiLEDDriver()).doSim { dut =>
    val cd = dut.clockDomain
    val axi4Driver = Axi4Master(dut.axi, cd)
    cd.forkStimulus(10)
    cd.assertReset()
    axi4Driver.reset()
    cd.waitSampling(10)
    cd.deassertReset()

    val mon = fork {
      for(i <- 0 until 10) {
        println(s"${dut.io.ledR.toBigInt.toString(16)}")
        cd.waitSampling()
      }
    }
    axi4Driver.write(0x0, List(0x7f, 0x7f, 0x7f, 0x7f))
    cd.waitSampling(5)
    axi4Driver.write(0x0, List(0x00, 0x00, 0x00, 0x00))
    mon.join()
  }
}

