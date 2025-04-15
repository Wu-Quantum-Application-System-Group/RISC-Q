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

case class DspConnectionArea(
    qubitNum: Int,
    pgs: List[pulse.PulseGenerator],
    pms: List[DualClockRam],
    dcgs: List[pulse.DemodCarrierGenerator],
    rds: List[pulse.ReadoutDecoder]
) extends Area {

  (pgs zip pms).foreach {
    case (x, y) => {
      y.fastPort.enable := True
      y.fastPort.write := False
      y.fastPort.mask.setAllTo(False)
      y.fastPort.wdata.setAllTo(False)
      y.fastPort.address := x.io.memPort.cmd.payload
      x.io.memPort.rsp := RegNext(y.fastPort.rdata)
    }
  }

  (rds zip dcgs).foreach {
    case (rd, dcg) => {
      rd.io.carrier := dcg.io.carrier
    }
  }
}

object MemMapReg {
  val pgTlBase = 0x10000
  val dcgTlBase = 0x20000
  val rdTlBase = 0x30000

  val pgMemBase = 0x10000
  val pgStep = 0x100
  val dcgMemBase = 0x20000
  val dcgStep = 0x100
  val rdMemBase = 0x30000
  val rdStep = 0x100

  def pgIdOffset(i: Int) = pgStep * i
  def pgAddrOffset(i: Int) = pgIdOffset(i) + 0
  def pgAmpOffset(i: Int) = pgIdOffset(i) + 4
  def pgDurOffset(i: Int) = pgIdOffset(i) + 8
  def pgFreqOffset(i: Int) = pgIdOffset(i) + 12
  def pgPhaseOffset(i: Int) = pgIdOffset(i) + 16
  // def pgStart(i: Int) = pgBase(i) + 20

  def dcgIdOffset(i: Int) = dcgStep * i
  def dcgFreqOffset(i: Int) = dcgIdOffset(i) + 0
  def dcgPhaseOffset(i: Int) = dcgIdOffset(i) + 4

  def rdIdOffset(i: Int) = rdStep * i
  def rdDurOffset(i: Int) = rdIdOffset(i) + 0
  def rdRefROffset(i: Int) = rdIdOffset(i) + 4
  def rdRefIOffset(i: Int) = rdIdOffset(i) + 8
  def rdResOffset(i: Int) = rdIdOffset(i) + 12

  def getCHeader(qubitNum: Int) = {
    var res = ""
    for (i <- 0 until qubitNum * 2) {
      res += s"#define PG_${i}_ADDR 0x${(pgMemBase + pgAddrOffset(i)).toHexString} \n"
      res += s"#define PG_${i}_AMP 0x${(pgMemBase + pgAmpOffset(i)).toHexString} \n"
      res += s"#define PG_${i}_DUR 0x${(pgMemBase + pgDurOffset(i)).toHexString} \n"
      res += s"#define PG_${i}_FREQ 0x${(pgMemBase + pgFreqOffset(i)).toHexString} \n"
      res += s"#define PG_${i}_PHASE 0x${(pgMemBase + pgPhaseOffset(i)).toHexString} \n"
      // res += s"#define PG_${i}_START 0x${pgStart(i).toHexString} \n"
    }

    for (i <- 0 until qubitNum * 3) {
      res += s"#define DCG_${i}_FREQ 0x${(dcgMemBase + dcgFreqOffset(i)).toHexString} \n"
      res += s"#define DCG_${i}_PHASE 0x${(dcgMemBase + dcgPhaseOffset(i)).toHexString} \n"
    }

    for (i <- 0 until qubitNum) {
      res += s"#define RD_${i}_DUR 0x${(rdMemBase + rdDurOffset(i)).toHexString} \n"
      res += s"#define RD_${i}_REFR 0x${(rdMemBase + rdRefROffset(i)).toHexString} \n"
      res += s"#define RD_${i}_REFI 0x${(rdMemBase + rdRefIOffset(i)).toHexString} \n"
      res += s"#define RD_${i}_RES 0x${(rdMemBase + rdResOffset(i)).toHexString} \n"
    }
    res
  }
}

object GenMemMapRegHeader extends App {
  println(MemMapReg.getCHeader(8))
}

case class MemMapRegFiber(
    pgs: List[pulse.PulseGenerator],
    dcgs: List[pulse.DemodCarrierGenerator],
    rds: List[pulse.ReadoutDecoder]
) extends Area {
  val up = Node.up()
  val allowBurst = false

  // val timeNode = Node.up()
  // timeNode at 0 of up
  // // timeNode.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // val pgNode = Node.up()
  // pgNode at 0x10000 of up
  // pgNode.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // val rdNode = Node.up()
  // rdNode at 0x30000 of up
  // // rdNode.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  import MemMapReg._

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = 24,
      dataWidth = 32,
      allowBurst = allowBurst,
      proposed = up.m2s.proposed
    )
    up.s2m.none()
    // timeNode.m2s.supported load tilelink.SlaveFactory.getSupported(
    //   addressWidth = 16,
    //   dataWidth = 32,
    //   allowBurst = allowBurst,
    //   proposed = timeNode.m2s.proposed
    // )
    // timeNode.s2m.none()

    // pgNode.m2s.supported load tilelink.SlaveFactory.getSupported(
    //   addressWidth = 17,
    //   dataWidth = 32,
    //   allowBurst = allowBurst,
    //   proposed = pgNode.m2s.proposed
    // )
    // pgNode.s2m.none()

    // rdNode.m2s.supported load tilelink.SlaveFactory.getSupported(
    //   addressWidth = 16,
    //   dataWidth = 32,
    //   allowBurst = allowBurst,
    //   proposed = rdNode.m2s.proposed
    // )
    // rdNode.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, allowBurst)
    val timeFactory = factory
    val pgFactory = factory
    val rdFactory = factory
    // val timeFactory = new tilelink.SlaveFactory(timeNode.bus, allowBurst)
    // val pgFactory = new tilelink.SlaveFactory(pgNode.bus, allowBurst)
    // val rdFactory = new tilelink.SlaveFactory(rdNode.bus, allowBurst)
    val dcgFactory = pgFactory

    val timeAddr = 0xbff8
    val time = Reg(UInt(32 bit)) init 0
    time.addAttribute("MAX_FANOUT", 16)
    time := time + U(1)
    time.simPublic()

    timeFactory.readAndWrite(time, timeAddr)

    val timeCmpAddr = 0x4000
    val timeCmp = Reg(UInt(32 bit)) init 0
    timeFactory.readAndWrite(timeCmp, timeCmpAddr)
    val delay = 3
    val waitTimeCmp = RegNext(time + delay < timeCmp)
    timeFactory.onReadPrimitive(SingleMapping(timeCmpAddr + 8), haltSensitive = false, null) {
      when(waitTimeCmp) {
        timeFactory.readHalt()
        // factory.writeHalt()
      }
    }

    val startTimeAddr = 0x8000
    val startTime = Reg(UInt(32 bit)) init 0
    startTime.addAttribute("MAX_FANOUT", 16)
    timeFactory.write(startTime, startTimeAddr)

    def getDriveReg[T <: Data](data: T): T = {
      val ret = Reg(data)
      data := ret
      ret
    }

    for ((pg, id) <- pgs.zipWithIndex) {
      pg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      pg.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      pgFactory.driveFlow(getDriveReg(pg.io.addr), pgTlBase + pgAddrOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.amp), pgTlBase + pgAmpOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.dur), pgTlBase + pgDurOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.freq), pgTlBase + pgFreqOffset(id), bitOffset = 16)
      pgFactory.driveFlow(getDriveReg(pg.io.phase), pgTlBase + pgPhaseOffset(id), bitOffset = 16)
    }

    for ((dcg, id) <- dcgs.zipWithIndex) yield new Area {
      dcg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      dcgFactory.driveFlow(getDriveReg(dcg.io.freq), dcgTlBase + dcgFreqOffset(id), bitOffset = 16)
      dcgFactory.driveFlow(getDriveReg(dcg.io.phase), dcgTlBase + dcgPhaseOffset(id), bitOffset = 16)
    }

    for ((rd, id) <- rds.zipWithIndex) {
      rd.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      rd.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      rdFactory.driveFlow(getDriveReg(rd.io.dur), rdTlBase + rdDurOffset(id), bitOffset = 16)
      rdFactory.driveFlow(getDriveReg(rd.io.refR), rdTlBase + rdRefROffset(id), bitOffset = 16)
      rdFactory.driveFlow(getDriveReg(rd.io.refI), rdTlBase + rdRefIOffset(id), bitOffset = 16)

      rdFactory.read(rd.io.res.payload, rdTlBase + rdResOffset(id))
      rdFactory.onReadPrimitive(SingleMapping(rdTlBase + rdResOffset(id)), haltSensitive = false, null) {
        when(!rd.io.res.valid) {
          rdFactory.writeHalt() // and readHalt
        }
      }
    }

  }
}

object MMSocParams {
  val dataWidth = 16

  val pulseBatchSize = 16
  val pulseAddrWidth = 12
  val pulseDurWidth = 12
  val pulseMemOutReg = true

  val demodBatchSize = 4
  val demodDurWidth = 12

  val pulseMemWidth = dataWidth * pulseBatchSize

  val rfReadSync = false
  val rfReadAt = -1 - rfReadSync.toInt
  val enableBypass = true

  def getPlugins(qubitNum: Int) = new Area {
    // val pgSpecs = List.fill(qubitNum)(qubitDrivePgSpec) ++ List.fill(qubitNum)(readoutDrivePgSpec)
    // val cgSpecs = List.fill(qubitNum)(qubitDriveCgSpec) ++
    //   List.fill(qubitNum)(readoutDriveCgSpec) ++
    //   List.fill(qubitNum)(readoutDemodCgSpec)

    val pcReset = 0x80000000L
    val plugins = ArrayBuffer[FiberPlugin]()
    val pp = new schedule.PipelinePlugin()
    plugins += pp
    plugins += new riscv.RiscvPlugin(xlen = 32)
    plugins += new schedule.ReschedulePlugin()
    plugins += new fetch.PcPlugin()
    plugins += new fetch.FetchCachelessPlugin(
      wordWidth = 32,
      forkAt = 0,
      joinAt = 4
    )
    // plugins += new decode.DecoderSimplePlugin(decodeAt = 0)
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
    plugins += new execute.MulPlugin(splitAt = 0, partialMulAt = 0, add1At = 1, add2At = 2, formatAt = 2)
    plugins += new execute.BarrelShifterPlugin(shiftAt = 0, formatAt = 0)
    plugins += new execute.BranchPlugin(aluAt = 0, jumpAt = 1, wbAt = 0)
    plugins += new execute.lsu.LsuCachelessPlugin(addressAt = 0, forkAt = 0, joinAt = 1, wbAt = 2)
  }
}

case class MemoryMapSoc(
    qubitNum: Int = 2,
    withWhitebox: Boolean = false,
    withVivado: Boolean = false,
    withCocotb: Boolean = false,
    withTest: Boolean = true
) extends Component {

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

  val pluginsArea = MMSocParams.getPlugins(qubitNum)
  val plugins = pluginsArea.plugins
  if (withWhitebox) {
    plugins += new test.WhiteboxerPlugin()
  }

  // cd100m
  val clk100m = in Bool ()
  val rst100m = in Bool ()
  val cd100m = ClockDomain(clk100m, rst100m)
  rst100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst100m rst")
  rst100m.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
  clk100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk100m clk")
  clk100m.addAttribute("X_INTERFACE_PARAMETERS", "FREQ_HZ 100000000, ASSOCIATED_RESET rst100m")

  val riscq_rst = in Bool ()
  // val riscqRst = RegNext(RegNext(riscq_rst))
  val riscqRst = BufferCC(riscq_rst)
  riscqRst.addAttribute("MAX_FANOUT", "128")

  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )

  val axi = cd100m(slave(Axi4(axiConfig)))
  val tlBus = withTest generate cd100m(tlBusNode)

  val shareBus = cd100m(Node())

  // pulse mem
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

  // cpu instruction bus

  // instruction memory
  val pcOffset = 0x80000000L
  // val pmAxiOffset  = 0x08000000L
  // val memAxiOffset = 0x00000000L
  val pmAxiOffset = 0x00000000L
  val memAxiOffset = 0x01000000L

  val memOutReg = true
  val mem = DualClockRam(
    width = 32,
    depth = 1024,
    slowCd = cd500m,
    fastCd = cd500m,
    withOutRegFast = memOutReg,
    withOutRegSlow = memOutReg
  )

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
  plugins += new fetch.FetchCachelessTileLinkPlugin(iBus)

  val dBus = Node.down()
  val dBusFiber = TileLinkMemReadWriteFiber(mem.fastPort, withOutReg = memOutReg)
  dBusFiber.up at pcOffset of dBusArb
  // dBusFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  dBusArb at 0 of dBus
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
    val riscq = RiscQ(plugins).addAttribute("KEEP_HIERARCHY", "TRUE")
  }

  val core = riscqArea.riscq

  // pulse generator and carrier generator
  val dspArea = new ResetArea(riscqRst, false) {
    // val pgs = pluginsArea.pgSpecs.map(spec => pulse.PulseGeneratorWithCarrierTop(QubicPlugins.puop, spec))
    val pgs = List.fill(qubitNum * 2)(
      pulse.PulseGenerator(
        batchSize = MMSocParams.pulseBatchSize,
        dataWidth = MMSocParams.dataWidth,
        addrWidth = MMSocParams.pulseAddrWidth,
        timeWidth = 32,
        durWidth = MMSocParams.pulseDurWidth,
        memLatency =
          1 + pulseMems(0).withOutRegFast.toInt + 1, // the last 1 is from x.io.memPort.rsp := RegNext(y.fastPort.rdata)
        timeInOffset = 1
      )
    )
    pgs.foreach { _.addAttribute("KEEP_HIERARCHY", "TRUE") }
    // val cgs = pluginsArea.cgSpecs.map(spec => pulse.CarrierGenerator(spec))
    val dcgs = List.fill(qubitNum) {
      pulse.DemodCarrierGenerator(
        batchSize = MMSocParams.demodBatchSize,
        dataWidth = MMSocParams.dataWidth,
        timeWidth = 32
      )
    }

    val readAccWidth = 27
    val rds = List.fill(qubitNum)(
      pulse.ReadoutDecoder(
        batchSize = MMSocParams.demodBatchSize,
        inWidth = MMSocParams.dataWidth,
        accWidth = readAccWidth,
        durWidth = MMSocParams.demodDurWidth,
        timeWidth = 32
      )
    )
  }
  val pgs = dspArea.pgs
  val dcgs = dspArea.dcgs
  val rds = dspArea.rds

  val dspConnectionArea = DspConnectionArea(qubitNum, pgs, pulseMems, dcgs, rds)

  val mmFiber = MemMapRegFiber(pgs, dcgs, rds)
  mmFiber.up at 0 of dBusArb
  mmFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

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

  val rbOffset = 0x0f000000
  val rbStep = 0x00100000
  val rbTlWaLogic = new ClockingArea(cd100m) {
    val rbTlWa = WidthAdapter()
    rbTlWa.up at rbOffset of shareBus
    rbTlWa.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  }
  val rbArea = for (rbId <- 0 until qubitNum) yield new Area {
    val readoutBuffer = DualClockRam(
      width = 128,
      depth = 1024,
      slowCd = cd100m,
      fastCd = cd500m,
      withOutRegFast = true,
      withOutRegSlow = true
    )
    val rbLogic = Fiber build new ResetArea(riscqRst, false) {
      val addr = Reg(readoutBuffer.fastPort.address) init 0
      val valid = rds(rbId).io.demodData.valid
      valid.simPublic()
      when(valid) {
        addr := addr + 1
      }

      readoutBuffer.fastPort.enable := True
      readoutBuffer.fastPort.mask.setAllTo(True)
      readoutBuffer.fastPort.address := addr
      readoutBuffer.fastPort.write := valid
      readoutBuffer.fastPort.wdata := rds(rbId).io.demodData.payload.asBits
    }

    val rbBusLogic = new ClockingArea(cd100m) {
      val rbTlFiber = TileLinkMemReadWriteFiber(readoutBuffer.slowPort, withOutReg = true)
      rbTlFiber.up at rbId * rbStep of rbTlWaLogic.rbTlWa.down
    }
  }

  val test_adc = withTest generate (in port Vec.fill(qubitNum * 2)(pulse.ComplexBatch(batchSize = 4, dataWidth = 16)))

  // pulse generator output
  for ((o, pg) <- (dac zip pgs)) {
    o.payload := pg.io.pulse.payload.map(_.r).asBits()
    o.valid := True
  }

  // readout input

  if (!withTest) {
    for ((rd, inAdc) <- (rds zip adc)) {
      (rd.io.adc zip inAdc.payload.subdivideIn(16 bits)).foreach { case (o, i) =>
        o.r.assignFromBits(i)
        o.i := o.i.getZero
      }
    }
  } else {
    (rds zip test_adc).foreach { case (outs, ins) =>
      outs.io.adc := ins
    }
  }

  Fiber build new Area {
    withTest generate tlBus.node.bus.get.simPublic()
    iBus.bus.get.simPublic()
    dBus.bus.get.simPublic()
    dBusArb.bus.get.simPublic()
    shareBus.bus.get.simPublic()

    Fiber.awaitCheck()

    // val path = PathTracer.impl(shareBus.bus.get.d.valid, tlBus.node.bus.get.d.valid)
    // println(path.report())
    // println(path.reportPaths())
    // println(path.reportNodes())

  }

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

object GenMMSocVivado extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    MemoryMapSoc(
      qubitNum = 4,
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
