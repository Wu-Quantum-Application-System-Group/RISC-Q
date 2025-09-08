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
import riscq.misc.VivadoClkHelper

case class MemoryMapSoc(
    qubitNum: Int,
    withWhitebox: Boolean = false,
    withVivado: Boolean = false,
    withCocotb: Boolean = false,
    withTest: Boolean = true
) extends Component {
  // default cd
  val dspCd = ClockDomain.current
  dspCd.renamePulledWires("dspClk", "dspRst")
  VivadoClkHelper.addInference(dspCd.readClockWire, dspCd.readResetWire, 500000000)

  // cd100m
  val hostClk = in Bool ()
  val hostRst = in Bool ()
  val hostCd = ClockDomain(hostClk, hostRst)
  VivadoClkHelper.addInference(hostClk, hostRst, 100000000)

  val params = RiscqParams()
  params.withTest = withTest
  val plugins = params.getPlugins().plugins

  val hostBusArea = hostCd(HostBusArea(withTest))
  def tlBus = hostBusArea.tlBus
  def axi = hostBusArea.axi
  if (withVivado) { riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS") }
  if (withCocotb) { riscq.misc.Axi4CocotbHelper.setName(axi) }

  val riscq_rst = in Bool()
  val riscqRst = BufferCC(riscq_rst, 5)
  riscqRst.addAttribute("MAX_FANOUT", "128")

  // pulse mem
  val envAddrWidth = 12
  val pulseMemFiber = hostCd(PulseMemFiber(
    num = 2 * qubitNum,
    width = 16 * 16,
    depth = 1 << envAddrWidth,
    withOutReg = true,
    hostCd = hostCd,
    dspCd = dspCd,
  ))
  pulseMemFiber.up at 0 of hostBusArea.pulseMemBus

  // cpu memory
  val memOutReg = true
  val mem = DualClockRam(
    width = 32,
    depth = 4096,
    slowCd = dspCd,
    fastCd = dspCd,
    withOutRegFast = memOutReg,
    withOutRegSlow = memOutReg
  )

  val memOffset = 0x80000000L
  val mmioOffset = 0x0L
  val mmioSize = 0x01000000L

  val iBus= Node.down()
  val iBusDec = Node()
  iBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val dBus = Node.down()
  val dBusArb = Node()
  dBusArb.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  iBusDec at 0 of hostBusArea.memBus
  iBusDec at memOffset of iBus
  dBusArb at 0 of dBus
  val iBusFiber = TileLinkMemReadWriteFiber(mem.slowPort, withOutReg = memOutReg)
  iBusFiber.up at 0 of iBusDec
  plugins += new fetch.FetchCachelessTileLinkPlugin(iBus)
  val dBusFiber = TileLinkMemReadWriteFiber(mem.fastPort, withOutReg = memOutReg)
  dBusFiber.up at memOffset of dBusArb
  plugins += new execute.lsu.LsuCachelessTileLinkPlugin(dBus)

  val riscqArea = new ResetArea(riscqRst, false) {
    val riscq = RiscQ(plugins)//.addAttribute("KEEP_HIERARCHY", "TRUE")
  }

  val core = riscqArea.riscq

  // pulse generator and carrier generator

  val clintFiber = ClintFiber()
  clintFiber.up at SizeMapping(0, 1 << 16) of dBusArb
  clintFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val rfArea = RFArea(dacChannels = qubitNum * 2, adcChannels = qubitNum)
  val rfFiber = RFFiber(rfArea)
  rfFiber.up at SizeMapping(MemMapReg.rfBase, 1 << 22) of dBusArb
  rfFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  rfArea.time := clintFiber.time
  rfArea.rfRst := riscqRst

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

  // readout buffer
  val readoutBufFiber = hostCd(ReadoutBufFiber(qubitNum, 128, 1024, hostCd, dspCd))
  readoutBufFiber.up at 0 of hostBusArea.readoutBufBus
  val rbArea = for (rbId <- 0 until qubitNum) yield new ResetArea(riscqRst, false) {
    val readoutBuffer = readoutBufFiber.readoutBufs(rbId)
    val addr = Reg(readoutBuffer.fastPort.address) init 0
    val valid = rfArea.rds(rbId).io.demodData.valid
    valid.simPublic()
    when(valid) {
      addr := addr + 1
    }

    readoutBuffer.fastPort.enable := True
    readoutBuffer.fastPort.mask.setAllTo(True)
    readoutBuffer.fastPort.address := addr
    readoutBuffer.fastPort.write := valid
    readoutBuffer.fastPort.wdata := rfArea.rds(rbId).io.demodData.payload.asBits
  }

  // dac and adc ports
  val dac = List.fill(qubitNum * 2)(master port Stream(Bits(16 * 16 bits)))
  dac.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }
  val adc = List.fill(qubitNum * 2)(slave port Stream(Bits(4 * 16 bits)))
  adc.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
    d.ready := True
  }


  // pulse generator output
  for ((o, pg) <- (dac zip rfArea.pgs)) {
    o.payload := pg.io.pulse.payload.map(_.r).asBits()
    o.valid := True
  }

  // readout input

  val test_adc = withTest generate (in port Vec.fill(qubitNum * 2)(pulse.ComplexBatch(batchSize = 4, dataWidth = 16)))
  if (!withTest) {
    for ((rd, inAdc) <- (rfArea.rds zip adc)) {
      (rd.io.adc zip inAdc.payload.subdivideIn(16 bits)).foreach { case (o, i) =>
        o.r.assignFromBits(i)
        o.i := o.i.getZero
      }
    }
  } else {
    (rfArea.rds zip test_adc).foreach { case (outs, ins) =>
      outs.io.adc := ins
    }
  }

  Fiber build new Area {
    dBus.bus.get.simPublic()
    dBusArb.bus.get.simPublic()
    rfFiber.up.bus.get.simPublic()
    hostBusArea.hostBus.bus.get.simPublic()
    // hostBusArea.tlBus.node.bus.get.simPublic()
    Fiber.awaitCheck()

    // val path = PathTracer.impl(shareBus.bus.get.d.valid, tlBus.node.bus.get.d.valid)
    // println(path.report())
    // println(path.reportPaths())
    // println(path.reportNodes())

  }
}

object MemMapReg {
  val rfBase = 0x10000

  val pgTlOffset = 0x10000
  val dcgTlOffset = 0x20000
  val rdTlOffset = 0x30000

  val pgStep = 0x100
  val dcgStep = 0x100
  val rdStep = 0x100

  def pgIdOffset(i: Int) = pgStep * i
  def pgAddrOffset(i: Int) = pgIdOffset(i) + 0
  def pgAmpOffset(i: Int) = pgIdOffset(i) + 4
  def pgDurOffset(i: Int) = pgIdOffset(i) + 8
  def pgFreqOffset(i: Int) = pgIdOffset(i) + 12
  def pgPhaseOffset(i: Int) = pgIdOffset(i) + 16

  def dcgIdOffset(i: Int) = dcgStep * i
  def dcgFreqOffset(i: Int) = dcgIdOffset(i) + 0
  def dcgPhaseOffset(i: Int) = dcgIdOffset(i) + 4

  def rdIdOffset(i: Int) = rdStep * i
  def rdDurOffset(i: Int) = rdIdOffset(i) + 0
  def rdResOffset(i: Int) = rdIdOffset(i) + 4
  def rdRealOffset(i: Int) = rdIdOffset(i) + 8
  def rdImagOffset(i: Int) = rdIdOffset(i) + 12

  def getCHeader(): String = {
    var res = ""
    res += s"#define PULSE_BASE_ADDR 0x${(rfBase + pgTlOffset).toHexString}\n"
    res += s"#define DEMOD_BASE_ADDR 0x${(rfBase + dcgTlOffset).toHexString}\n"
    res += s"#define RD_BASE_ADDR 0x${(rfBase + rdTlOffset).toHexString}\n"
    res += s"#define PULSE_PHASE_OFFSET 0x${(pgPhaseOffset((0))).toHexString}\n"
    res += s"#define PULSE_AMP_OFFSET 0x${(pgAmpOffset((0))).toHexString}\n"
    res += s"#define PULSE_ADDR_OFFSET 0x${(pgAddrOffset((0))).toHexString}\n"
    res += s"#define PULSE_DUR_OFFSET 0x${(pgDurOffset((0))).toHexString}\n"
    res += s"#define PULSE_FREQ_OFFSET 0x${(pgFreqOffset((0))).toHexString}\n"
    res += s"#define DEMOD_FREQ_OFFSET 0x${(dcgFreqOffset((0))).toHexString}\n"
    res += s"#define DEMOD_PHASE_OFFSET 0x${(dcgPhaseOffset((0))).toHexString}\n"
    res += s"#define RD_DUR_OFFSET 0x${(rdDurOffset((0))).toHexString}\n"
    res += s"#define RD_RES_OFFSET 0x${(rdResOffset((0))).toHexString}\n"
    res += s"#define RD_REAL_OFFSET 0x${(rdRealOffset((0))).toHexString}\n"
    res += s"#define RD_IMAG_OFFSET 0x${(rdImagOffset((0))).toHexString}\n"

    return res
  }

  def getDriveReg[T <: Data](data: T, depth: Int = 1): T = {
    val bufs = Vec.fill(depth)(Reg(cloneOf(data)))
    data := bufs.last
    bufs.last.addAttribute("equivalent_register_removal", "no")
    bufs.last.setCompositeName(data, s"buf")
    for(i <- 0 until depth - 1) {
      bufs(i + 1) := bufs(i)
      bufs(i).addAttribute("equivalent_register_removal", "no")
      bufs(i).setCompositeName(data, s"buf$i")
    }
    bufs.head
  }
}

object GenMemMapRegHeader extends App {
  println(MemMapReg.getCHeader())
}

case class ClintFiber(externalTime: Option[UInt] = None, hostTime: Option[UInt] = None) extends Area {
  val up = Node.up()

  var time: UInt = null
  if(externalTime.isDefined) {
    time = externalTime.get
  } else {
    time = Reg(UInt(32 bit)) init 0
    time.addAttribute("MAX_FANOUT", 16)
    time := time + 1
  }
  time.simPublic()

  val timeCmp = Reg(UInt(32 bit)) init 0

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = 16,
      dataWidth = 32,
      allowBurst = false,
      proposed = up.m2s.proposed
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)

    val timeAddr = 0xbff8

    if (externalTime.isDefined) {
      factory.read(time, timeAddr)
    } else {
      factory.readAndWrite(time, timeAddr)
    }

    val hostTimeAddr = 0x4010
    if (hostTime.isDefined) {
      factory.read(hostTime.get, hostTimeAddr)
    }

    val timeCmpAddr = 0x4000
    factory.readAndWrite(timeCmp, timeCmpAddr)
    val delay = 3
    val waitTimeCmp = RegNext(time + delay < timeCmp)
    factory.read(waitTimeCmp, timeCmpAddr + 8)
    factory.onReadPrimitive(SingleMapping(timeCmpAddr + 8), haltSensitive = false, null) {
      when(waitTimeCmp) {
        factory.readHalt()
      }
    }

  }
}

object GenMMSocVivado extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    MemoryMapSoc(
      qubitNum = 8,
      withVivado = true,
      withCocotb = false,
      withWhitebox = false,
      withTest = false
    )
  )
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    riscq.misc.ClockInterface()
  )
}
