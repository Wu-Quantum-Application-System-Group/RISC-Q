package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber
import scala.collection.mutable.ArrayBuffer
import riscq._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.core.fiber.Fiber
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.eda.bench.Rtl
import riscq.memory.DualClockRam
import riscq.misc.TileLinkMemReadWriteFiber
import spinal.lib.eda.bench.Bench
import riscq.misc.XilinxRfsocTarget
import scala.collection.mutable.LinkedHashMap
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.misc.PathTracer

case class HostBusArea(withTest: Boolean) extends Area {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )

  val pulseMemOffset =   0x10000000L
  val pulseMemSize =     0x01000000L
  val readoutBufOffset = 0x18000000L
  val readoutBufSize =   0x01000000L
  val memOffset =        0x00000000L
  val memSize =          0x01000000L

  val axi = slave(Axi4(axiConfig))

  // blockSize is the maximal bytes that can be transfered in a transaction, which could takes multiple bits
  // slotsCount is the number of sources
  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load axi
  val hostBus = Node()

  val tlBus = withTest generate tlBusNode
  if (withTest) {
    hostBus at 0 of tlBus.node
  }

  hostBus at 0 of bridge.down
  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val pulseMemWa = WidthAdapter()
  pulseMemWa.up at SizeMapping(pulseMemOffset, pulseMemSize) of hostBus

  val readoutBufWa = WidthAdapter()
  readoutBufWa.up at SizeMapping(readoutBufOffset, readoutBufSize) of hostBus
  def readoutBufBus = readoutBufWa.down

  val memBus = Node()
  memBus at SizeMapping(memOffset, memSize) of hostBus

  def pulseMemBus = pulseMemWa.down

  def tlBusNode = {
    new MasterBus(
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
  }
}

case class RFArea(qubitNum: Int) extends Area {
  val time = UInt(32 bit)
  val timeBuf = RegNext(time)
  timeBuf.addAttribute("MAX_FANOUT", "32")

  val startTime = Reg(UInt(32 bit))
  val startTimeBuf = RegNext(startTime)
  startTimeBuf.addAttribute("MAX_FANOUT", "32")

  val rfRst = Bool()

  val envAddrWidth = 12
  val dspArea = new ResetArea(rfRst, false) {
    val pgs = List.fill(qubitNum * 2)(
      pulse.PulseGenerator(
        batchSize = 16,
        dataWidth = 16,
        addrWidth = envAddrWidth,
        timeWidth = 32,
        durWidth = 16,
        memLatency =
          1 + 1, // sync read latency + out reg
        timeInOffset = 1,
        fifoDepth = 4
      )
    )
    pgs.foreach { pg => 
      pg.addAttribute("KEEP_HIERARCHY", "TRUE") 
      pg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      pg.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
    }

    val dcgs = List.fill(qubitNum) {
      pulse.DemodCarrierGenerator(
        batchSize = 4,
        dataWidth = 16,
        timeWidth = 32
      )
    }
    dcgs.foreach{ dcg =>
      dcg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
    }

    val readAccWidth = 27
    val rds = List.fill(qubitNum)(
      pulse.ReadoutDecoder(
        batchSize = 4,
        inWidth = 16,
        accWidth = readAccWidth,
        durWidth = 16,
        timeWidth = 32
      )
    )
    rds.foreach{ rd =>
      rd.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      rd.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
    }
    
    (rds zip dcgs).foreach {
      case (rd, dcg) => {
        rd.io.carrier := dcg.io.carrier
      }
    }
  }

  def pgs = dspArea.pgs
  def dcgs = dspArea.dcgs
  def rds = dspArea.rds
}

case class PulseMemFiber(num: Int, width: Int, depth: Int, withOutReg: Boolean, hostCd: ClockDomain, dspCd: ClockDomain)
    extends Area {
  val up = Node()
  val pulseMems = List.fill(num)(
    DualClockRam(
      width = width,
      depth = depth,
      slowCd = hostCd,
      fastCd = dspCd,
      withOutRegFast = withOutReg,
      withOutRegSlow = true
    )
  )

  val step = 1 << log2Up(depth * width / 8)
  val pulseMemFibers = for (i <- 0 until num) yield new ClockingArea(hostCd) {
    val pulseMemFiber = TileLinkMemReadWriteFiber(pulseMems(i).slowPort, withOutReg = true)
    val offset = step * i
    pulseMemFiber.up at offset of up
  }
}

case class ReadoutBufFiber(num: Int, width: Int, depth: Int, hostCd: ClockDomain, dspCd: ClockDomain) extends Area {
  val up = Node()
  val readoutBufs = List.fill(num)(
    DualClockRam(
      width = width,
      depth = depth,
      slowCd = hostCd,
      fastCd = dspCd,
      withOutRegFast = true,
      withOutRegSlow = true
    )
  )

  val step = 1 << log2Up(width * depth / 8)
  val readoutBufFibers = for (i <- 0 until num) yield new Area {
    val rbTlFiber = TileLinkMemReadWriteFiber(readoutBufs(i).slowPort, withOutReg = true)
    rbTlFiber.up at i * step of up
  }
}
