package scratch

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.core.fiber.Retainer
import spinal.lib.misc.plugin._
import spinal.core.SpinalVerilog
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.sim._
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.misc.Clint.addressWidth
import spinal.lib.bus.misc.SizeMapping
import spinal.core.fiber.Fiber
import riscq.misc.TileLinkMemReadWriteFiber
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber
import spinal.lib.bus.amba4.axi.Axi4

object TileLinkCdDemo extends App {
  case class TLCD() extends Component {
    val cd500m = ClockDomain.current
    val clk100m = in Bool ()
    val rst100m = in Bool ()
    val cd100m = ClockDomain(clk100m, rst100m)

    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 2
    )
    val axi = slave(Axi4(axiConfig))
    val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
    bridge.up load axi
    val hostBus = cd100m(Node())
    hostBus at 0 of bridge.down
    val mem = cd100m(Mem.fill(128)(Bits(32 bit)))
    val memFiber = TileLinkMemReadWriteFiber(mem.readWriteSyncPort(32 / 8), false)
    memFiber.up at 0 of hostBus
  }

  SpinalVerilog(TLCD())
}

object TileLinkSizeMappingDemo extends App {
  case class TLSM() extends Component {
    val m1 = new MasterBus(
      tilelink.M2sParameters(
        addressWidth = 32,
        dataWidth = 32,
        masters = List(
          tilelink.M2sAgent(
            name = this,
            mapping = List(
              tilelink.M2sSource(
                id = SizeMapping(0, 4),
                emits = tilelink.M2sTransfers(
                  get = tilelink.SizeRange.upTo(0x100),
                  putFull = tilelink.SizeRange.upTo(0x100),
                  putPartial = tilelink.SizeRange.upTo(0x100)
                )
              )
            )
          )
        )
      )
    )

    val mem = Mem.fill(1024)(Bits(32 bit))
    val memFiber1 = TileLinkMemReadWriteFiber(mem.readWriteSyncPort(4), withOutReg = false)
    val memFiber2 = TileLinkMemReadWriteFiber(mem.readWriteSyncPort(4), withOutReg = false)

    memFiber1.up at SizeMapping(0, 0x10000000) of m1.node
    memFiber2.up at 0x00000200 of m1.node
  }

  SimConfig.compile { TLSM() }.doSim { dut =>
    implicit val idAllocator = new tilelink.sim.IdAllocator(DebugId.width)
    implicit val idCallback = new tilelink.sim.IdCallback
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    val tlDriver = new tilelink.sim.MasterAgent(dut.m1.node.bus, cd)
    val monitor1 = new tilelink.sim.Monitor(dut.memFiber1.up.bus, cd)
    val monitor2 = new tilelink.sim.Monitor(dut.memFiber2.up.bus, cd)
    monitor1.add(new tilelink.sim.MonitorSubscriber {
      override def onA(a: TransactionA) = { println(s"a1:${simTime()}"); println(a) }
      override def onD(d: TransactionD) = { println(s"d1:${simTime()}"); println(d) }
    })
    monitor2.add(new MonitorSubscriber {
      override def onA(a: TransactionA) = { println(s"a2:${simTime()}"); println(a) }
      override def onD(d: TransactionD) = { println(s"d2:${simTime()}"); println(d) }
    })

    tlDriver.putFullData(0, 512, List(0.toByte))
    cd.waitRisingEdge(100)
  }
}

object TileLinkDemo extends App {
  case class MasterFiber() extends Area {
    this.setName("masterrrrr")
    val down = Node.down()
    val logic = Fiber build new Area {
      // down.m2s.proposed load M2sSupport(M2sParameters(
      //     addressWidth = 64,
      //     dataWidth = 64,
      //     masters = List(
      //       M2sAgent(
      //         name = MasterFiber.this,
      //         mapping = List(
      //           M2sSource(
      //             id = SizeMapping(0, 4),
      //             emits = M2sTransfers(
      //               get = SizeRange(1, 64),
      //               putFull = SizeRange(1, 64)
      //             )
      //           )
      //         )
      //       )
      //     )
      //   )
      // )

      // println(s"there: ${down.m2s.supported.dataWidth}")
      // down.m2s.parameters load M2sParameters(down.m2s.supported, 4)
      down.m2s.proposed load M2sSupport(
        addressWidth = 32,
        dataWidth = 64,
        transfers = M2sTransfers(
          get = SizeRange(1, 64)
        )
      )
      down.m2s.parameters load M2sParameters(down.m2s.supported, 4, MasterFiber.this).copy(addressWidth = 32)
      down.s2m.supported load S2mSupport.none()
      println(s"master: ${down.m2s.parameters}")
      // down.s2m.none()
    }
  }

  case class SlaveFiber() extends Area {
    val up = Node.up()
    val logic = Fiber build new Area {
      up.m2s.supported load up.m2s.proposed.copy(addressWidth = 64)
      up.s2m.none()
    }
  }
  SpinalVerilog {
    new Component {
      val myClk = in Bool ()
      val myRst = in Bool ()
      val myCd = ClockDomain(myClk, myRst)
      val master = myCd(MasterFiber())
      val slave = SlaveFiber()
      slave.up at 0x0000 of master.down
      val logic = Fiber check new Area {
        println(s"slave: ${slave.up.m2s.parameters}")
      }
    }
  }
}

object RetainerDemo extends App {
  case class TopLevel(plugins: Seq[FiberPlugin]) extends Component {
    val host = new PluginHost
    host.asHostOf(plugins)
  }

  case class PA() extends FiberPlugin {
    val lock = Retainer()

    val logic = during setup {
      awaitBuild()
      lock.await()
      println("unlocked")
    }
  }

  case class PB() extends FiberPlugin {
    val logic = during setup {
      val pa = host[PA]
      val retainer = retains(pa.lock)
      awaitBuild()
      retainer.release()
    }
  }

  case class PC() extends FiberPlugin {
    val logic = during setup {
      val retainer = retains(host.list[PA].map(_.lock))
      awaitBuild()
      retainer.release()
      retainer.release()
    }
  }

  SpinalVerilog {
    // val plugins = List(PA(), PB())
    val plugins = List(PA(), PB(), PC())
    TopLevel(plugins)
  }

}

object TestTileLinkParameters extends App {
  SpinalVerilog {
    new Component {
      val tlM2sParameter = tilelink.M2sParameters(
        addressWidth = 32,
        dataWidth = 32,
        masters = List(
          tilelink.M2sAgent(
            name = this,
            mapping = List(
              tilelink.M2sSource(
                id = SizeMapping(0, 4),
                emits = tilelink.M2sTransfers(
                  get = tilelink.SizeRange.upTo(0x4),
                  putFull = tilelink.SizeRange.upTo(0x4),
                  putPartial = tilelink.SizeRange.upTo(0x4)
                )
              )
            )
          )
        )
      )
      println(s"${tilelink.SizeRange.upTo(0x100).max}")
      val param = NodeParameters(tlM2sParameter, tilelink.S2mParameters.none()).toBusParameter()
      println(param)
    }
  }
}
