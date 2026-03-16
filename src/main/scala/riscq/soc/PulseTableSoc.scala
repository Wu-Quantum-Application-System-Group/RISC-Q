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
import riscq.pulse.AddTree
import riscq.pulse.ComplexBatch
import riscq.pulse.PulseGeneratorWithTableFiber

case class ReadoutDemodFiber(
  startTime: UInt,
  time: UInt,
) extends Area {
  val up = Node.up()

  val dcg = pulse.DemodCarrierGenerator(
    batchSize = 4,
    dataWidth = 16,
    timeWidth = 32
  )
  dcg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = 5,
      dataWidth = 32,
      allowBurst = false,
      proposed = up.m2s.proposed
    )
    up.s2m.none()
    val factory = new tilelink.SlaveFactory(up.bus, false)
    factory.driveFlow(dcg.io.freq, 0, bitOffset = 16)
    factory.driveFlow(dcg.io.phase, 4, bitOffset = 16)
  }
}

case class ReadoutDecoderFiber(
  startTime: UInt,
  time: UInt,
  carrier: Vec[pulse.Complex],
) extends Area {
  val up = Node.up()

  val readAccWidth = 32
  val rd = pulse.ReadoutDecoder(
      batchSize = 4,
      inWidth = 16,
      accWidth = readAccWidth,
      durWidth = 16,
      timeWidth = 32
    )
  rd.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
  rd.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
  rd.io.carrier := carrier

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = 5,
      dataWidth = 32,
      allowBurst = false,
      proposed = up.m2s.proposed
    )
    up.s2m.none()
    val factory = new tilelink.SlaveFactory(up.bus, false)
    factory.driveFlow(rd.io.dur, 0, bitOffset = 16)
    factory.read(rd.io.res.payload, 4)
    factory.onReadPrimitive(SingleMapping(4), haltSensitive = false, null) {
      when(!rd.io.res.valid) {
        factory.writeHalt() // and readHalt
      }
    }
    factory.read(rd.io.real, 8)
    factory.read(rd.io.imag, 12)
  }
}

case class RiscqRfWithPulseTableFiber(
    plugins: ArrayBuffer[FiberPlugin],
    dspCd: ClockDomain,
    hostCd: ClockDomain,
    riscqCd: ClockDomain,
    time: UInt,
    fromHost: Bits,
    memDepth: Int = 1024,
    memWidth: Int = 32,
    memOutReg: Boolean = true,
    fifoDepth: Int = 2,
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
  mem.addAttribute("KEEP_HIERARCHY", "TRUE")

  val iMemPortArb = Node()
  val dMemPortDec = riscqCd(Node())

  val memOffset = 0x80000000L

  dMemPortDec at 0 of riscqFiber.dBus
  val dBusFiber = riscqCd(TileLinkMemReadWriteFiber(mem.fastPort, withOutReg = memOutReg))
  dBusFiber.up at memOffset of dMemPortDec

  iMemPortArb at memOffset of riscqFiber.iBus
  val iBusFiber = TileLinkMemReadWriteFiber(mem.slowPort, withOutReg = memOutReg)
  iBusFiber.up at 0 of iMemPortArb


  val memMapFiber = riscqCd(MemMapFiber(addressWidth = 22, dataWidth = 32))
  val timeMemMap = TimeMemMap(time)
  memMapFiber.addMapping(timeMemMap.mapping)
  val hostMemMap = HostMemMap(fromHost)
  memMapFiber.addMapping(hostMemMap.mapping)
  val startTime = Reg(UInt(32 bit)) init 0
  memMapFiber.addMapping{ factory =>
    factory.write(startTime, 0x4100)
  }
  memMapFiber.up at SizeMapping(0, 1 << 16) of dMemPortDec
  memMapFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val rfFiber = Node()
  rfFiber at SizeMapping(0x10000, 4 << 16) of dMemPortDec
  rfFiber.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val gateDriveFiber = riscqCd(PulseGeneratorWithTableFiber(
    startTime = startTime,
    time = time,
    batchSize = 16,
    dataWidth = 16,
    envAddrWidth = 10,
    timeWidth = 32,
    pulseNum = 4,
    fifoDepth = 2,
    durWidth = 16,
    memLatency = 1 + 1,
    timeInOffset = 1,
  ))
  gateDriveFiber.up at SizeMapping(0, 1 << 16) of rfFiber
  gateDriveFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val readoutDriverFiber = riscqCd(PulseGeneratorWithTableFiber(
    startTime = startTime,
    time = time,
    batchSize = 16,
    dataWidth = 16,
    envAddrWidth = 10,
    timeWidth = 32,
    pulseNum = 1,
    fifoDepth = 2,
    durWidth = 16,
    memLatency = 1 + 1,
    timeInOffset = 1,
  ))
  readoutDriverFiber.up at SizeMapping(0x10000, 1 << 16) of rfFiber
  readoutDriverFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val readoutDemodFiber = riscqCd(ReadoutDemodFiber(
    startTime = startTime,
    time = time,
  ))
  readoutDemodFiber.up at SizeMapping(0x20000, 1 << 4) of rfFiber
  readoutDemodFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val readoutDecoderFiber = riscqCd(ReadoutDecoderFiber(
    startTime = startTime,
    time = time,
    carrier = readoutDemodFiber.dcg.io.carrier,
  ))
  readoutDecoderFiber.up at SizeMapping(0x30000, 1 << 4) of rfFiber
  readoutDecoderFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // val readoutFiber = riscqCd(ReadoutFiber(
  //   startTime = startTime,
  //   time = time,
  // ))
  // readoutFiber.up at SizeMapping(0x30000, 1 << 16) of dMemPortDec
  // readoutFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val pulseMemFiber = hostCd(DualClockRamFiber(2, 256, 1024, hostCd, dspCd, true))
  for(i <- 0 until 2) {
    pulseMemFiber.rams(i).fastPort.enable := True
    pulseMemFiber.rams(i).fastPort.write := False
    pulseMemFiber.rams(i).fastPort.mask.setAllTo(False)
    pulseMemFiber.rams(i).fastPort.wdata.setAllTo(False)
  }
  pulseMemFiber.rams(0).fastPort.address := gateDriveFiber.pg.io.memPort.cmd.payload
  gateDriveFiber.pg.io.memPort.rsp := pulseMemFiber.rams(0).fastPort.rdata
  pulseMemFiber.rams(1).fastPort.address := readoutDriverFiber.pg.io.memPort.cmd.payload
  readoutDriverFiber.pg.io.memPort.rsp := pulseMemFiber.rams(1).fastPort.rdata

  val dac = List.fill(2)(ComplexBatch(batchSize = 16, dataWidth = 16))
  val adc = ComplexBatch(batchSize = 4, dataWidth = 16)
  dac(0) := gateDriveFiber.pg.io.pulse.payload
  dac(1) := readoutDriverFiber.pg.io.pulse.payload
  readoutDecoderFiber.rd.io.adc := adc
}

case class PulseTableSoc(
    qubitNum: Int,
    dacMap: Map[(Int, Int), Int], // (core id, channel id) -> dac id
    adcMap: Map[Int, Int], // core id -> adc id
    dacNum: Int = 16,
    adcNum: Int = 16,
    withTest: Boolean = false
) extends Zcu216Top(0) {
  // blockSize is the maximal bytes that can be transfered in a transaction
  // slotsCount is the number of sources
  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load io.axi
  val hostBus = Node()
  hostBus at 0 of bridge.down
  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  import RiscqZcu216MemoryMap._
  val pulseMemWa = WidthAdapter()
  pulseMemWa.up at SizeMapping(pulseMemOffset, 1 << 25) of hostBus
  val pulseMemBus = Node()
  pulseMemBus at SizeMapping(0, 1 << 25) of pulseMemWa.down
  pulseMemBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val riscqMemBus = Node()
  riscqMemBus at SizeMapping(riscqCoreMemOffset, 1 << 25) of hostBus
  riscqMemBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val robs = DualClockRamFiber(1, 64, 1024, hostCd, dspCd, withOutReg = true)
  val robAdapter = tilelink.fabric.WidthAdapter()
  robAdapter.up at SizeMapping(2 << 25, 1 << 25) of hostBus
  robs.up at SizeMapping(0, 1 << 25) of robAdapter.down
  robs.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val riscqReset = Bool()
  val riscqCd = ClockDomain(dspCd.readClockWire, riscqReset)
  val riscqArea = new ClockingArea(dspCd) {
    val refTime = riscqCd(Reg(UInt(64 bit)) init 0)
    refTime := refTime + 1

    val timeOffset = Reg(UInt(64 bit)) init 0

    val syncTime = RegNext(refTime + timeOffset)
    val time = RegNext(syncTime(0, 32 bits))
    time.addAttribute("MAX_FANOUT", 16)

    val fromHost = Reg(Bits(32 bit)) init 0

    val params = RiscqParams()
    params.withTest = withTest

    val riscqCores = List.fill(qubitNum)(
      RiscqRfWithPulseTableFiber(
        plugins = params.getPlugins().plugins,
        dspCd = dspCd,
        hostCd = hostCd,
        riscqCd = riscqCd,
        time = time,
        fromHost = fromHost,
      )
    )
    riscqCores.foreach { riscq =>
      riscq.riscqFiber.riscq.addAttribute("KEEP_HIERARCHY", "TRUE")
      riscq.gateDriveFiber.pg.addAttribute("KEEP_HIERARCHY", "TRUE") 
      riscq.readoutDriverFiber.pg.addAttribute("KEEP_HIERARCHY", "TRUE") 
    }
    for ((riscq, i) <- riscqCores.zipWithIndex) {
      val bridgeNode = Node()
      bridgeNode at SizeMapping(riscqCoreMemSpaceSize * i, riscqCoreMemSpaceSize) of riscqMemBus
      riscq.iMemPortArb at 0 of bridgeNode
      riscq.pulseMemFiber.up at SizeMapping(pulseMemSpaceSize * i, pulseMemSpaceSize) of pulseMemBus
    }

    for (dacId <- 0 until dacNum) {
      val channels = dacMap.filter { case (_, id) => id == dacId }.toList.map { _._1 }
      val pulses = channels.map { case (coreId, channelId) =>
        riscqCores(coreId).dac(channelId)
      }

      val dacPayload = cloneOf(io.dac(dacId).payload)
      io.dac(dacId).payload := dacPayload
      if (pulses.isEmpty) {
        dacPayload := 0
      } else if (pulses.size == 1) {
        dacPayload := RegNext(pulses.head.map(_.r).asBits())
      } else {
        val addTrees = (0 until 16).map { i => AddTree(pulses.map { p => p(i).r.asSInt }.toList) }
        val pulseSums = addTrees.map { _.sum }
        dacPayload := pulseSums.asBits()
      }
    }

    val adcs = Vec.fill(16)(Vec.fill(4)(SInt(16 bits)))
    (adcs zip io.adc).foreach { case (o, i) => o.assignFromBits(i.payload) }
    val adcBufs = adcs.map { adc => RegNext(adc) }


    val adcSum = Vec.tabulate(4) { i => AddTree(adcBufs.map { adc => adc(i) }.toList).sum }
    // for ((coreId, adcId) <- adcMap) {
    //   (riscqCores(coreId).adc zip adcBufs(adcId)).foreach { case (o, i) =>
    //     o.r := i // 1 RegNext is too little, 3 RegNexts are too much
    //     o.i := o.i.getZero
    //   }
    // }

    for ((coreId, adcId) <- adcMap) {
      (riscqCores(coreId).adc zip adcSum).foreach { case (o, i) =>
        o.r := i // 1 RegNext is too little, 3 RegNexts are too much
        o.i := o.i.getZero
      }
    }

    val fire = RegNext(riscqCores(0).gateDriveFiber.pg.io.pulse.valid)
    val rbAddr = Reg(UInt(10 bits)) init 0
    val rb = robs.rams(0)
    when(fire) {
      rbAddr := rbAddr + 1
    }.otherwise {
      rbAddr := 0
    }
    rb.fastPort.enable := True
    rb.fastPort.mask.setAllTo(True)
    rb.fastPort.address := RegNext(rbAddr)
    rb.fastPort.write := fire
    rb.fastPort.wdata := adcSum.asBits
  }

  val riscqResetHostCd = Bool()
  val bufferedHostRiscqReset = dspCd(BufferCC(riscqResetHostCd, 5))
  val riscqResetReg = dspCd(Delay(bufferedHostRiscqReset, 5))
  // riscqResetReg.addAttribute("MAX_FANOUT", 128)
  if (withTest) {
    riscqReset := riscqResetReg | io.dspRst
  } else {
    riscqReset := riscqResetReg
  }

  val timeOffset = Reg(UInt(64 bit)) init 0
  riscqArea.timeOffset := dspCd(BufferCC(timeOffset))

  val fromHost = Reg(Bits(32 bit)) init 0
  riscqArea.fromHost := dspCd(BufferCC(fromHost))

  val hostCtrlDriver = MemMapDriverFiber(addressWidth = 10, dataWidth = 32, driveProc = { factory =>
    // for (i <- 0 until qubitNum) {
    //   factory.read(BufferCC(riscqArea.riscqCores(i).rfArea.rds(0).io.real, 5), i * 4 * 2)
    //   factory.read(BufferCC(riscqArea.riscqCores(i).rfArea.rds(0).io.imag, 5), i * 4 * 2 + 4)
    // }
    factory.drive(riscqResetHostCd, 0)
    factory.write(fromHost, 16)
    factory.write(timeOffset(0, 32 bits), 64)
    factory.write(timeOffset(32, 32 bits), 68)
  })
  hostCtrlDriver.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  hostCtrlDriver.up at SizeMapping(hostCtrlOffset, 1 << 24) of hostBus

  Fiber build new Area {
    riscqArea.riscqCores(0).dMemPortDec.bus.get.simPublic()
    riscqArea.riscqCores(0).rfFiber.bus.get.simPublic()
  }

}

object GenPulseTableSoc extends App {
  val qubitNum = 1
  val loopbackMap = Map(
    0 -> 14,
    // 1 -> 15,
    // 2 -> 12,
    // 3 -> 13,
    // 4 -> 10,
    // 5 -> 11,
    // 6 -> 8,
    // 7 -> 9,
    // 8 -> 6,
    // 9 -> 7,
    // 10 -> 4,
    // 11 -> 5,
    // 12 -> 2,
    // 13 -> 3,
    // 14 -> 0,
    // 15 -> 1
  )
  // val dacMap = (0 until qubitNum).flatMap { i => List(((i, 0), 0), ((i, 1), i + 1 )) }.toMap
  val dacMap = (0 until qubitNum).flatMap { i => List(((i, 0), i), ((i, 1), i)) }.toMap
  val adcMap = (0 until qubitNum).map { i => (i, loopbackMap(i)) }.toMap
  println(s"dacMap: $dacMap")

  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    PulseTableSoc(
      qubitNum = qubitNum,
      dacMap = dacMap,
      adcMap = adcMap,
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