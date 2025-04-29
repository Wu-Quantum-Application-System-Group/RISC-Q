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
import riscq.scratch.BenchPulseGenerator.dataWidth
import riscq.execute.PulseGeneratorPlugin

object QubicSocParams {
  val dataWidth = 16

  val pulseBatchSize = 16
  val pulseAddrWidth = 12
  val pulseDurWidth = 12
  val pulseMemOutReg = true

  val pulseStartWidth = 32
  val pulsePhaseWidth = 16
  val pulseFreqWidth = 16
  val pulseAmpWidth = 16
  val pulseIdWidth = 5

  val demodBatchSize = 4
  val demodDurWidth = 12

  val pulseMemWidth = dataWidth * pulseBatchSize

  val rfReadSync = false
  val rfReadAt = -1 - rfReadSync.toInt
  val enableBypass = true
}

import QubicSocParams._

case class QubicSoc(
    qubitNum: Int = 2,
    withWhitebox: Boolean = false,
    withVivado: Boolean = false,
    withCocotb: Boolean = false,
    withTest: Boolean = true
) extends Component {
  /* -- Clock domains -- */
  // default cd
  val cd500m = ClockDomain.current
  cd500m.renamePulledWires("clk500m", "rst500m")
  cd500m.readResetWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst500m rst")
  cd500m.readResetWire.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  cd500m.readClockWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk500m clk")
  cd500m.readClockWire.addAttribute(
    "X_INTERFACE_PARAMETERS",
    "FREQ_HZ 500000000, ASSOCIATED_RESET rst500m"
  )
  // cd100m
  val clk100m = in Bool ()
  val rst100m = in Bool ()
  val cd100m = ClockDomain(clk100m, rst100m)
  rst100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst100m rst")
  rst100m.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  clk100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk100m clk")
  clk100m.addAttribute("X_INTERFACE_PARAMETERS", "FREQ_HZ 100000000, ASSOCIATED_RESET rst100m")
  // reset
  val riscq_rst = in Bool ()
  val riscqRst = BufferCC(riscq_rst)
  riscqRst.addAttribute("MAX_FANOUT", "128")

  /* -- rams and buses -- */
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
  val pcOffset = 0x80000000L
  val pmAxiOffset = 0x00000000L
  val memAxiOffset = 0x01000000L
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )

  val axi = cd100m(slave(Axi4(axiConfig)))
  val tlBus = withTest generate cd100m(tlBusNode)
  val shareBus = cd100m(Node())
  val pulseMems = List.fill(qubitNum * 2)(
    DualClockRam(
      width = MMSocParams.pulseMemWidth,
      depth = 1 << (MMSocParams.pulseAddrWidth),
      slowCd = cd100m,
      fastCd = ClockDomain.current,
      withOutRegFast = MMSocParams.pulseMemOutReg,
      withOutRegSlow = MMSocParams.pulseMemOutReg
    )
  )
  // instruction memory
  val memOutReg = true
  val mem = DualClockRam(
    width = 32,
    depth = 1024,
    slowCd = cd500m,
    fastCd = cd500m,
    withOutRegFast = memOutReg,
    withOutRegSlow = memOutReg
  )
  // cpu instruction bus
  val iBusDec = Node()
  iBusDec.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val dBusArb = Node()
  dBusArb.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val iBus = Node.down()
  val iBusFiber = TileLinkMemReadWriteFiber(mem.slowPort, withOutReg = memOutReg)
  iBusFiber.up at 0 of iBusDec
  // iBusFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  iBusDec at pcOffset of iBus
  iBusDec.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  val dBus = Node.down()
  val dBusFiber = TileLinkMemReadWriteFiber(mem.fastPort, withOutReg = memOutReg)
  dBusFiber.up at pcOffset of dBusArb
  // dBusFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  dBusArb at 0 of dBus

  /* -- plugins -- */
  val plugins = ArrayBuffer[FiberPlugin]()
  plugins += new schedule.PipelinePlugin()
  plugins += new riscv.RiscvPlugin(xlen = 32)
  plugins += new schedule.ReschedulePlugin()
  plugins += new fetch.PcPlugin()
  plugins += new fetch.FetchCachelessPlugin(
    wordWidth = 128,
    forkAt = 0,
    joinAt = 4
  )
  plugins += new fetch.FetchCachelessTileLinkPlugin(iBus)
  plugins += new decode.DecoderPlugin(decodeAt = 0)
  plugins += new regfile.RegFilePlugin(
    spec = riscv.IntRegFile,
    physicalDepth = 32,
    preferedWritePortForInit = "",
    syncRead = rfReadSync,
    dualPortRam = false,
    maskReadDuringWrite = false
  )
  plugins += new execute.RegReadPlugin(syncRead = true, rfReadAt = rfReadAt, enableBypass = enableBypass)
  plugins += new execute.SrcPlugin(executeAt = 0, relaxedRs = true)
  plugins += new schedule.HazardPlugin(rfReadAt = rfReadAt, hazardAt = rfReadAt, enableBypass = enableBypass)
  plugins += new execute.WriteBackPlugin(riscv.IntRegFile, writeAt = 2)
  plugins += new execute.IntFormatPlugin()
  plugins += new execute.IntAluPlugin(executeAt = 0, formatAt = 0)
  plugins += new execute.BarrelShifterPlugin(shiftAt = 0, formatAt = 0)
  plugins += new execute.BranchPlugin(aluAt = 0, jumpAt = 1, wbAt = 0)
  plugins += new execute.lsu.LsuCachelessPlugin(addressAt = 0, forkAt = 0, joinAt = 1, wbAt = 2)
  val tp = new execute.TimerPlugin()
  plugins += tp
  val pgp = new execute.PulseGeneratorPlugin(qubitNum)
  plugins += pgp
  /* Carrier plugin NYI */
  /* Readout plugin NYI */
  if (withWhitebox) {
    plugins += new test.WhiteboxerPlugin()
  }
  plugins += new execute.lsu.LsuCachelessTileLinkPlugin(dBus)

  val cd100mLogic = new ClockingArea(cd100m) {
    // blockSize is the maximal bytes that can be transfered in a transaction, which could takes multiple bits
    // slotsCount is the number of sources
    val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
    bridge.up load axi
    shareBus at 0 of bridge.down
    if (withVivado) { riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS") }
    if (withCocotb) { riscq.misc.Axi4CocotbHelper.setName(axi) }
    // axi.addAttribute("X_INTERFACE_PARAMETER", "CLK_DOMAIN clk100m_clk_p")

    if (withTest) {
      shareBus at 0 of tlBus.node
    }

    // envelope memory
    val pulseMemWa = WidthAdapter()
    pulseMemWa.up at pmAxiOffset of shareBus

    val pulseMemBus = Node()
    pulseMemBus at 0 of pulseMemWa.down
    pulseMemBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

    val pulseMemFibers = for (i <- 0 until qubitNum * 2) yield new Area {
      val pulseMemFiber = TileLinkMemReadWriteFiber(pulseMems(i).slowPort, withOutReg = MMSocParams.pulseMemOutReg)
      val step = 1 << 20
      val offset = step * i
      // println(s"!!!!!! pulseoffset: ${BigInt(offset).toString(16)}")
      pulseMemFiber.up at offset of pulseMemBus
      pulseMemFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
    }

    iBusDec at memAxiOffset of shareBus
  }

  val riscqArea = new ResetArea(riscqRst, false) {
    val core = RiscQ(plugins).addAttribute("KEEP_HIERARCHY", "TRUE")
  }
  val core = riscqArea.core

  /* -- DAC and ADC -- */
  // just DAC for now, for pulse instruction
  // adc comes later
  val dac = List.fill(qubitNum * 2)(master port Stream(Bits(16 * 16 bits)))
  dac.zipWithIndex.foreach { case (d, id) =>
    riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
  }
  // pulse generator output
  Fiber build {
    val pgp = riscqArea.core.host[PulseGeneratorPlugin]
    val pgs = pgp.logic.pgs
    val memPorts = pgp.logic.memPorts
    val pulses = pgp.logic.pulses

    // TODO `DspConnectionArea(qubitNum, pgs, pulseMems, dcgs, rds)`
    // Can't write that until dcgs and rds are re-introduced.

    (memPorts zip pulseMems).foreach {
      case (x, y) => {
        y.fastPort.enable := True
        y.fastPort.write := False
        y.fastPort.mask.setAllTo(False)
        y.fastPort.wdata.setAllTo(False)
        y.fastPort.address := x.cmd.payload
        x.rsp := RegNext(y.fastPort.rdata)
      }
    }

    for ((o, pulse) <- (dac zip pulses)) {

      o.payload := pulse.payload.map(_.r).asBits()
      o.valid := pulse.valid
    }
  }


  Fiber build new Area {
    withTest generate tlBus.node.bus.get.simPublic()
    iBus.bus.get.simPublic()
    dBus.bus.get.simPublic()
    dBusArb.bus.get.simPublic()
    shareBus.bus.get.simPublic()
    Fiber.awaitCheck()
  }
}
