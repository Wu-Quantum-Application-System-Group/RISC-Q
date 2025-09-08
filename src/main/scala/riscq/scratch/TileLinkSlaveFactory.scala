package riscq.scratch

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.fabric._
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.eda.bench.Rtl
import spinal.lib.eda.bench.Bench
import riscq.misc.XilinxRfsocTarget

case class TileLinkSlaveFactoryTest(size: Int) extends Component {
  val tlBus =     new MasterBus(
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

  val regs = List.fill(size)(Reg(Bits(32 bit)))

  val up = Node.up()
  up at 0 of tlBus.node

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = 16,
      dataWidth = 32,
      allowBurst = false,
      proposed = up.m2s.proposed
      )
      up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)

    for((reg, i) <- regs.zipWithIndex) {
      factory.readAndWrite(reg, i)
    }
  }
}

object BenchTileLinkSlaveFactoryTest extends App {
  val rtl = Rtl(SpinalVerilog(TileLinkSlaveFactoryTest(1000)))
  Bench(List(rtl), XilinxRfsocTarget(), "./bench/")
}