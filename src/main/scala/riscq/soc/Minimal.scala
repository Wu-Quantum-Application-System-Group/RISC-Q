package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.fabric._
import scala.collection.mutable.ArrayBuffer
import riscq._
import spinal.lib.misc.plugin.FiberPlugin
import riscq.misc.TileLinkMemReadWriteFiber
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink.M2sParameters
import spinal.lib.bus.tilelink.M2sAgent
import spinal.lib.bus.tilelink.M2sSource
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.M2sTransfers
import spinal.lib.bus.tilelink.SizeRange
import spinal.lib.eda.bench.Rtl
import riscq.misc.ClockInterface
import riscq.memory.DualClockRam
import spinal.lib.eda.bench.Bench
import riscq.misc.XilinxRfsocTarget

case class MinimalSoc(whiteboxer: Boolean = false, wordWidth: Int = 32, regFileSync: Boolean = false) extends Component {
  val params = RiscqParams()
  params.withTest = whiteboxer
  var plugins = params.getPlugins().plugins
  val iMem = Mem.fill(1024)(Bits(wordWidth bit)).simPublic()
  val dMem = Mem.fill(1024)(Bits(32 bit)).simPublic()

  val memConnects = plugins.map {
    case p: fetch.FetchCachelessPlugin => {
      new fetch.FetchCachelessBramConnectArea(p, iMem.readWriteSyncPort(maskWidth = wordWidth / 8))
    }
    case p: execute.lsu.LsuCachelessBusProvider => {
      new execute.lsu.LsuCachelessBramConnectArea(p, dMem.readWriteSyncPort(maskWidth = 32 / 8))
    }
    case _ =>
  }

  val riscq = RiscQ(plugins)

  // We need some output to avoid vivado removing everything in optimization
  val dummyPort = slave port iMem.readWriteSyncPort(maskWidth = wordWidth / 8)
}

// async rf - Virtex UltraScale+ -> 602 Mhz 713 LUT 966 FF 10 BRAM 0 URAM
// sync rf - Virtex UltraScale+ -> 536 Mhz 809 LUT 1020 FF 10 BRAM 0 URAM
object BenchMinimalSoc extends App {
  val rtl = Rtl(
    SpinalVerilog(
      MinimalSoc(regFileSync = false)
    )
  )
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./build/")
}
