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

object QubicSocParams {
  /* from old `PulseOpParam` */
  val pulseAddrWidth = 12
  val pulseStartWidth = 32
  val pulseDurWidth = 12
  val pulsePhaseWidth = 16
  val pulseFreqWidth = 16
  val pulseAmpWidth = 16
  val pulseIdWidth = 5

  /* from MMSocParams */
  val pulseMemOutReg = true

  val rfReadSync = false
  val rfReadAt = -1 - rfReadSync.toInt
  val enableBypass = true

  def getPlugins(qubitNum: Int) = new Area {
    val pcReset = 0x80000000L
    val plugins = ArrayBuffer[FiberPlugin]()
    val pp = new schedule.PipelinePlugin()
    plugins += pp
    plugins += new riscv.RiscvPlugin(xlen = 32)
    plugins += new schedule.ReschedulePlugin()
    plugins += new fetch.PcPlugin()
    plugins += new fetch.FetchCachelessPlugin(
      wordWidth = 128,
      forkAt = 0,
      joinAt = 4
    )
    plugins += new decode.DecoderPlugin(decodeAt = 0)
    plugins += new regfile.RegFilePlugin(
      spec = riscv.IntRegFile,
      physicalDepth = 32,
      preferedWritePortForInit = "",
      syncRead = rfReadSync,
      dualPortRam = false,
      maskReadDuringWrite = false
    )
    plugins += new execute.RegReadPlugin(rfReadAt = rfReadAt, enableBypass = enableBypass)
    plugins += new execute.SrcPlugin(executeAt = 0, relaxedRs = true)
    plugins += new schedule.HazardPlugin(rfReadAt = rfReadAt, hazardAt = rfReadAt, enableBypass = enableBypass)
    plugins += new execute.WriteBackPlugin(riscv.IntRegFile, writeAt = 2, allowBypassFrom = 1)
    plugins += new execute.IntFormatPlugin()
    plugins += new execute.IntAluPlugin(executeAt = 0, formatAt = 0)
    plugins += new execute.BarrelShifterPlugin(shiftAt = 0, formatAt = 0)
    plugins += new execute.BranchPlugin(aluAt = 0, jumpAt = 1, wbAt = 0)
    plugins += new execute.lsu.LsuCachelessNoRspStorePlugin(addressAt = 0, forkAt = 0, joinAt = 1, wbAt = 2)
  }
}

case class QubicSoc(
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

  val pluginsArea = QubicSocParams.getPlugins(qubitNum)
  val plugins = pluginsArea.plugins
  val pulsePlugin = new execute.PulsePlugin()
  plugins += pulsePlugin
  if (withWhitebox) {
    plugins += new test.WhiteboxerPlugin()
  }

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
    width = 128,
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

  val rfArea = RFArea(qubitNum)
  new PulsePluginConnections(rfArea, pulsePlugin)
  val rfFiber = QubicRfFiber(rfArea)
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
}

class PulsePluginConnections(rfArea : RFArea, pulsePlugin : execute.PulsePlugin) {
  import QubicSocParams._
  val logic = Fiber build new Area {
    // call `getDriveReg` on everything
    val pgs = rfArea.pgs
    import MemMapReg.getDriveReg
    val pg_addrs = Vec(pgs.map(pg => getDriveReg(pg.io.addr, depth=2)))
    val pg_amps = Vec(pgs.map(pg => getDriveReg(pg.io.amp, depth=2)))
    val pg_durs = Vec(pgs.map(pg => getDriveReg(pg.io.dur, depth=2)))
    val pg_freqs = Vec(pgs.map(pg => getDriveReg(pg.io.freq, depth=2)))
    val pg_phs = Vec(pgs.map(pg => getDriveReg(pg.io.phase, depth=2)))
    // idle by default.
    // otherwise, "latch detected".
    pg_addrs.map(_.setIdle())
    pg_amps.map(_.setIdle())
    pg_durs.map(_.setIdle())
    pg_freqs.map(_.setIdle())
    pg_phs.map(_.setIdle())
    // actual logic
    when(pulsePlugin.logic.sel) {
      rfArea.startTime.addAttribute("MAX_FANOUT", 16)
      rfArea.startTime := pulsePlugin.logic.start.asUInt
      val id = pulsePlugin.logic.id.asUInt.resized
      val addr = pulsePlugin.logic.addr.asUInt.resized
      pg_addrs(id).push(addr)
      val dur = pulsePlugin.logic.duration.asUInt.resized
      pg_durs(id).push(dur)
      val phase = AFix.S(0 exp, pulsePhaseWidth bits)
      phase.assignFromBits(pulsePlugin.logic.phase)
      pg_phs(id).push(phase)
      val freq = AFix.S(0 exp, pulseFreqWidth bits)
      freq.assignFromBits(pulsePlugin.logic.freq)
      pg_freqs(id).push(freq)
      val amp = AFix.S(0 exp, pulseFreqWidth bits)
      amp.assignFromBits(pulsePlugin.logic.amp)
      pg_amps(id).push(amp)
    }
  }
}

case class QubicRfFiber(rfArea: RFArea) extends Area {
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

    val rdFactory = factory
    val dcgFactory = factory

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


object GenQubicSocVivado extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    QubicSoc(
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