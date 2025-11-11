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
import scala.collection.mutable
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.misc.PathTracer
import riscq.riscv.IntRegFile.width
import riscq.misc.TileLinkMemWriteFiber
import riscq.network.GtPins

case class HostBusArea(withTest: Boolean) extends Area {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )

  val pulseMemOffset = 0x10000000L
  val pulseMemSize = 0x01000000L
  val readoutBufOffset = 0x18000000L
  val readoutBufSize = 0x01000000L
  val memOffset = 0x00000000L
  val memSize = 0x01000000L

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

case class RFArea(dacChannels: Int, adcChannels: Int, envAddrWidth: Int = 12, fifoDepth: Int = 2) extends Area {
  val time = UInt(32 bit)
  val timeBuf = RegNext(time)
  timeBuf.addAttribute("MAX_FANOUT", "32")

  val startTime = Reg(UInt(32 bit))
  val startTimeBuf = RegNext(startTime)
  startTimeBuf.addAttribute("MAX_FANOUT", "32")

  val rfRst = Bool()

  val dspArea = new ResetArea(rfRst, false) {
    val pgs = List.fill(dacChannels)(
      pulse.PulseGenerator(
        batchSize = 16,
        dataWidth = 16,
        addrWidth = envAddrWidth,
        timeWidth = 32,
        durWidth = 16,
        memLatency = 1 + 1, // sync read latency + out reg
        timeInOffset = 1,
        fifoDepth = fifoDepth
      )
    )
    pgs.foreach { pg =>
      // pg.addAttribute("KEEP_HIERARCHY", "TRUE")
      pg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      pg.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
    }

    val dcgs = List.fill(adcChannels) {
      pulse.DemodCarrierGenerator(
        batchSize = 4,
        dataWidth = 16,
        timeWidth = 32
      )
    }
    dcgs.foreach { dcg =>
      dcg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
    }

    val readAccWidth = 28
    val rds = List.fill(adcChannels)(
      pulse.ReadoutDecoder(
        batchSize = 4,
        inWidth = 16,
        accWidth = readAccWidth,
        durWidth = 16,
        timeWidth = 32
      )
    )
    rds.foreach { rd =>
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

case class RFFiber(rfArea: RFArea) extends Area {
  import MemMapReg._
  val up = Node.up()

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = 22,
      dataWidth = 32,
      allowBurst = false,
      proposed = up.m2s.proposed
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)

    val startTimeAddr = 0x0000
    rfArea.startTime.addAttribute("MAX_FANOUT", 16)
    factory.write(rfArea.startTime, startTimeAddr)

    val pgFactory = factory
    val rdFactory = factory
    val dcgFactory = factory

    val pgs = rfArea.pgs
    for ((pg, id) <- pgs.zipWithIndex) {
      pgFactory.driveFlow(getDriveReg(pg.io.addr), pgTlOffset + pgAddrOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.amp), pgTlOffset + pgAmpOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.dur), pgTlOffset + pgDurOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.freq), pgTlOffset + pgFreqOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.phase), pgTlOffset + pgPhaseOffset(id), bitOffset = 16)
    }

    val dcgs = rfArea.dcgs
    for ((dcg, id) <- dcgs.zipWithIndex) yield new Area {
      dcgFactory.driveFlow(getDriveReg(dcg.io.freq), dcgTlOffset + dcgFreqOffset(id), bitOffset = 16)
      dcgFactory.driveFlow(getDriveReg(dcg.io.phase), dcgTlOffset + dcgPhaseOffset(id), bitOffset = 16)
    }

    val rds = rfArea.rds
    for ((rd, id) <- rds.zipWithIndex) {
      rdFactory.driveFlow(getDriveReg(rd.io.dur), rdTlOffset + rdDurOffset(id), bitOffset = 16)

      rdFactory.read(rd.io.res.payload, rdTlOffset + rdResOffset(id))
      rdFactory.read(rd.io.real, rdTlOffset + rdRealOffset(id))
      rdFactory.read(rd.io.imag, rdTlOffset + rdImagOffset(id))
      rdFactory.onReadPrimitive(SingleMapping(rdTlOffset + rdResOffset(id)), haltSensitive = false, null) {
        when(!rd.io.res.valid) {
          rdFactory.writeHalt() // and readHalt
        }
      }
    }
  }
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
    val memPort = pulseMems(i).slowPort
    val writePort = Flow(MemWriteCmd(memPort.dataType, memPort.addressWidth, memPort.maskWidth))
    memPort.enable := True
    memPort.write := writePort.valid
    memPort.address := writePort.address
    memPort.mask := writePort.mask
    memPort.wdata := writePort.data
    val pulseMemFiber = TileLinkMemWriteFiber(writePort)
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

case class RiscqFiber(plugins: ArrayBuffer[FiberPlugin]) extends Area {
  val iBus = Node.down()
  val dBus = Node.down()
  iBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  dBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  plugins += new fetch.FetchCachelessTileLinkPlugin(iBus)
  plugins += new execute.lsu.LsuCachelessTileLinkPlugin(dBus)

  Fiber build new Area {
    dBus.bus.get.simPublic()
  }

  val riscq = RiscQ(plugins) // .addAttribute("KEEP_HIERARCHY", "TRUE")
}

case class RiscqRfFiber(
    plugins: ArrayBuffer[FiberPlugin],
    dspCd: ClockDomain,
    hostCd: ClockDomain,
    riscqCd: ClockDomain,
    time: UInt,
    fromHost: Bits,
    dacChannels: Int,
    adcChannels: Int,
    memDepth: Int = 1024,
    memWidth: Int = 32,
    memOutReg: Boolean = true,
    fifoDepth: Int = 2
) extends Area {

  val riscqFiber = riscqCd(RiscqFiber(plugins))

  val mem = DualClockRam(
    width = memWidth,
    depth = memDepth,
    slowCd = dspCd,
    fastCd = dspCd,
    withOutRegFast = memOutReg,
    withOutRegSlow = memOutReg
  )

  val iMemPortArb = Node()
  val dMemPortDec = riscqCd(Node())
  // iMemPortArb.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  // dMemPortDec.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val memOffset = 0x80000000L

  dMemPortDec at 0 of riscqFiber.dBus
  val dBusFiber = riscqCd(TileLinkMemReadWriteFiber(mem.fastPort, withOutReg = memOutReg))
  dBusFiber.up at memOffset of dMemPortDec

  iMemPortArb at memOffset of riscqFiber.iBus
  val iBusFiber = TileLinkMemReadWriteFiber(mem.slowPort, withOutReg = memOutReg)
  iBusFiber.up at 0 of iMemPortArb

  val rfArea = riscqCd(
    RFArea(dacChannels = dacChannels, adcChannels = adcChannels, envAddrWidth = 10, fifoDepth = fifoDepth)
  )
  val pulseMemFiber = hostCd(PulseMemFiber(dacChannels, 256, 1024, true, hostCd, dspCd))

  val memMapFiber = riscqCd(MemMapFiber(addressWidth = 22, dataWidth = 32))
  // val rfMemMap = RfMemMap(rfArea)
  // memMapFiber.addMapping(rfMemMap.mapping)
  val timeMemMap = TimeMemMap(time)
  memMapFiber.addMapping(timeMemMap.mapping)
  val hostMemMap = HostMemMap(fromHost)
  memMapFiber.addMapping(hostMemMap.mapping)
  memMapFiber.up at SizeMapping(0, 1 << 16) of dMemPortDec
  memMapFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // val clintFiber = riscqCd(ClintFiber(externalTime = time, hostTime = hostTime))
  // clintFiber.up at SizeMapping(0, 1 << 16) of dMemPortDec
  // clintFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val rfFiber = riscqCd(RFFiber(rfArea))
  rfFiber.up at SizeMapping(MemMapReg.rfBase, 1 << 22) of dMemPortDec
  rfFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  rfArea.time := time
  rfArea.rfRst := riscqCd.readResetWire
  // rfArea.time := clintFiber.time
  // rfArea.rfRst := riscqCd.readResetWire

  // val gateTableFiber = GateTableFiber(8)

  (rfArea.pgs zip pulseMemFiber.pulseMems).foreach {
    case (x, y) => {
      y.fastPort.enable := True
      y.fastPort.write := False
      y.fastPort.mask.setAllTo(False)
      y.fastPort.wdata.setAllTo(False)
      y.fastPort.address := x.io.memPort.cmd.payload
      x.io.memPort.rsp := y.fastPort.rdata
    }
  }
}

case class MemMapDriverFiber(driveProc: tilelink.SlaveFactory => Unit, addressWidth: Int = 10, dataWidth: Int = 32)
    extends Area {
  val up = Node.up()

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = addressWidth,
      dataWidth = dataWidth,
      transfers = up.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8)
        )
      )
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)
    driveProc(factory)
  }
}

case class MemMapFiber(addressWidth: Int = 10, dataWidth: Int = 32) extends Area {
  val up = Node.up()
  val mappings = mutable.LinkedHashSet[tilelink.SlaveFactory => Unit]()

  def addMapping(mapping: tilelink.SlaveFactory => Unit) = {
    mappings += mapping
  }

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = addressWidth,
      dataWidth = dataWidth,
      transfers = up.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8)
        )
      )
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)
    mappings.foreach(mapping => mapping(factory))
  }
}

case class RfMemMap(rfArea: RFArea) extends Area {
  def mapping(factory: tilelink.SlaveFactory): Unit = {
    import MemMapReg._

    val startTimeAddr = 0x4010
    rfArea.startTime.addAttribute("MAX_FANOUT", 16)
    factory.write(rfArea.startTime, startTimeAddr)

    val pgFactory = factory
    val rdFactory = factory
    val dcgFactory = factory

    val pgs = rfArea.pgs
    for ((pg, id) <- pgs.zipWithIndex) {
      pgFactory.driveFlow(getDriveReg(pg.io.addr), pgTlOffset + pgAddrOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.amp), pgTlOffset + pgAmpOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.dur), pgTlOffset + pgDurOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.freq), pgTlOffset + pgFreqOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.phase), pgTlOffset + pgPhaseOffset(id), bitOffset = 16)
    }

    val dcgs = rfArea.dcgs
    for ((dcg, id) <- dcgs.zipWithIndex) yield new Area {
      dcgFactory.driveFlow(getDriveReg(dcg.io.freq), dcgTlOffset + dcgFreqOffset(id), bitOffset = 16)
      dcgFactory.driveFlow(getDriveReg(dcg.io.phase), dcgTlOffset + dcgPhaseOffset(id), bitOffset = 16)
    }

    val rds = rfArea.rds
    for ((rd, id) <- rds.zipWithIndex) {
      rdFactory.driveFlow(getDriveReg(rd.io.dur), rdTlOffset + rdDurOffset(id), bitOffset = 16)

      rdFactory.read(rd.io.res.payload, rdTlOffset + rdResOffset(id))
      rdFactory.read(rd.io.real, rdTlOffset + rdRealOffset(id))
      rdFactory.read(rd.io.imag, rdTlOffset + rdImagOffset(id))
      rdFactory.onReadPrimitive(SingleMapping(rdTlOffset + rdResOffset(id)), haltSensitive = false, null) {
        when(!rd.io.res.valid) {
          rdFactory.writeHalt() // and readHalt
        }
      }
    }
  }
}

case class TimeMemMap(externalTime: UInt) extends Area {
  val timeCmp = Reg(UInt(32 bit)) init 0
  val time = RegNext(externalTime)
  def mapping(factory: tilelink.SlaveFactory): Unit = {
    val timeAddr = 0xbff8
    factory.read(time, timeAddr)

    val timeCmpAddr = 0x4000
    factory.readAndWrite(timeCmp, timeCmpAddr)
    val delay = 3

    val waitTimeAddr = timeCmpAddr + 8
    val waitTimeCmp = RegNext(time + delay < timeCmp)
    factory.read(waitTimeCmp, waitTimeAddr)
    factory.onReadPrimitive(SingleMapping(waitTimeAddr), haltSensitive = false, null) {
      when(waitTimeCmp) {
        factory.readHalt()
      }
    }
  }
}

case class HostMemMap(fromHost: Bits) extends Area {
  def mapping(factory: tilelink.SlaveFactory): Unit = {
    factory.read(RegNext(fromHost), 0x2000)
  }
}

case class RiscqZcu216SocPorts(gtNum: Int = 0) extends Bundle {
  val dacNum = 16
  val adcNum = 16

  val dspClk = in Bool ()
  val dspRst = in Bool ()
  val axi = slave(Axi4(Axi4Config(32, 32, 2)))
  val dac = List.fill(dacNum)(master port Stream(Bits(16 * 16 bits)))
  val adc = List.fill(adcNum)(slave port Stream(Bits(4 * 16 bits)))
  val gts = List.fill(gtNum)(GtPins())

  riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS")
  adc.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := True
  }
  dac.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
    d.valid := True
  }
  gts.zipWithIndex.foreach { case (gt, id) =>
    gt.mgtrefclk_p.addAttribute(
      "X_INTERFACE_INFO",
      s"xilinx.com:interface:diff_clock:1.0 mgtrefclk_${id}_diff CLK_P "
    )
    gt.mgtrefclk_n.addAttribute(
      "X_INTERFACE_INFO",
      s"xilinx.com:interface:diff_clock:1.0 mgtrefclk_${id}_diff CLK_N "
    )
  }

  def noDac() = {
    dac.foreach { d =>
      d.payload := 0
    }
  }
}

case class RiscqZcu216MemoryMap() extends Area {
  val riscqCoreMemOffset = 0x0
  val riscqCoreMemSpaceSize = 1 << 16

  val pulseMemOffset = 1 << 25
  val pulseMemSpaceSize = 1 << 18

  val hostCtrlOffset = 3 * (1 << 25)

  def coreOffset(coreId: Int) = riscqCoreMemOffset + coreId * riscqCoreMemSpaceSize
  def pulseOffset(coreId: Int) = pulseMemOffset + coreId * pulseMemSpaceSize
  def riscqResetOffset = 0
}