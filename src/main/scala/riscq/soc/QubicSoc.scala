// package riscq.soc

// import spinal.core._
// import spinal.core.sim._
// import spinal.lib._
// import spinal.lib.bus.tilelink.fabric._
// import spinal.lib.bus.amba4.axi.Axi4Config
// import spinal.lib.bus.amba4.axi.Axi4
// import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber
// import scala.collection.mutable.ArrayBuffer
// import riscq._
// import spinal.lib.misc.plugin.FiberPlugin
// import spinal.core.fiber.Fiber
// import spinal.lib.bus.tilelink
// import spinal.lib.bus.misc.SizeMapping
// import spinal.lib.eda.bench.Rtl
// import riscq.memory.DualClockRam
// import riscq.misc.TileLinkMemReadWriteFiber
// import spinal.lib.eda.bench.Bench
// import riscq.misc.XilinxRfsocTarget
// import riscq.execute.TimerPlugin

// object QubicPlugins {
//   val puop = execute.PulseOpParam(
//     addrWidth = 12,
//     startWidth = 32,
//     durationWidth = 12,
//     phaseWidth = 16,
//     freqWidth = 16,
//     ampWidth = 16
//   )
//   // val qubitNum = 2
//   def qubitDrivePgSpec = pulse.PulseGeneratorSpec(
//     dataWidth = 16,
//     batchSize = 16,
//     bufferDepth = 4096,
//     clockWidth = 12,
//     phaseWidth = 16,
//     freqWidth = 16,
//     ampWidth = 16
//   )
//   def readoutDrivePgSpec = pulse.PulseGeneratorSpec(
//     dataWidth = 16,
//     batchSize = 16,
//     bufferDepth = 4096,
//     clockWidth = 12,
//     phaseWidth = 16,
//     freqWidth = 16,
//     ampWidth = 16
//   )

//   def testAdcPgSpec = pulse.PulseGeneratorSpec(
//     dataWidth = 16,
//     batchSize = 4,
//     bufferDepth = 4096,
//     clockWidth = 12,
//     phaseWidth = 16,
//     freqWidth = 16,
//     ampWidth = 16
//   )

//   def qubitDriveCgSpec = pulse.CarrierGeneratorSpec(
//     batchSize = 16,
//     carrierWidth = 16,
//     freqWidth = 16,
//     clockWidth = 32
//   )
//   def readoutDriveCgSpec = pulse.CarrierGeneratorSpec(
//     batchSize = 16,
//     carrierWidth = 16,
//     freqWidth = 16,
//     clockWidth = 32
//   )
//   def readoutDemodCgSpec = pulse.CarrierGeneratorSpec(
//     batchSize = 4,
//     carrierWidth = 16,
//     freqWidth = 16,
//     clockWidth = 32
//   )
//   def getPlugins(qubitNum: Int) = new Area {
//     val rfReadSync = false
//     val pgSpecs = List.fill(qubitNum)(qubitDrivePgSpec) ++ List.fill(qubitNum)(readoutDrivePgSpec)
//     val cgSpecs = List.fill(qubitNum)(qubitDriveCgSpec) ++
//       List.fill(qubitNum)(readoutDriveCgSpec) ++
//       List.fill(qubitNum)(readoutDemodCgSpec)

//     val pcReset = 0x80000000L
//     val plugins = ArrayBuffer[FiberPlugin]()
//     val pp = new schedule.PipelinePlugin()
//     plugins += pp
//     plugins += new riscv.RiscvPlugin(xlen = 32)
//     plugins += new schedule.ReschedulePlugin()
//     plugins += new fetch.PcPlugin()
//     plugins += new fetch.FetchCachelessPlugin(
//       wordWidth = 128,
//       forkAt = 0,
//       joinAt = 1
//     )
//     // plugins += new decode.DecoderSimplePlugin(decodeAt = 0)
//     plugins += new decode.DecoderPlugin(decodeAt = 0)
//     plugins += new regfile.RegFilePlugin(
//       spec = riscv.IntRegFile,
//       physicalDepth = 32,
//       preferedWritePortForInit = "",
//       syncRead = rfReadSync,
//       dualPortRam = false,
//       maskReadDuringWrite = false
//     )
//     val rfReadAt = -1 - rfReadSync.toInt
//     val enableBypass = true
//     plugins += new execute.RegReadPlugin(syncRead = true, rfReadAt = rfReadAt, enableBypass = enableBypass)
//     plugins += new execute.SrcPlugin(executeAt = 0, relaxedRs = true)
//     plugins += new schedule.HazardPlugin(rfReadAt = rfReadAt, hazardAt = rfReadAt, enableBypass = enableBypass)
//     plugins += new execute.WriteBackPlugin(riscv.IntRegFile, writeAt = 2)
//     plugins += new execute.IntFormatPlugin()
//     plugins += new execute.IntAluPlugin(executeAt = 0, formatAt = 0)
//     plugins += new execute.BranchPlugin()
//     plugins += new execute.TimerPlugin(32)
//     plugins += new execute.CarrierPlugin(cgSpecs)
//     val pgp = new execute.PulseGeneratorPlugin(puop, pgSpecs)
//     plugins += pgp
//     plugins += new execute.ReadoutPlugin(batchSize = 4, carrierWidth = 16, num = qubitNum)
//     plugins += new execute.lsu.LsuCachelessPlugin()
//     // plugins += new test.WhiteboxerPlugin()
//   }
// }

// case class QubicSoc(
//     qubitNum: Int = 2,
//     withWhitebox: Boolean = false,
//     withVivado: Boolean = false,
//     withCocotb: Boolean = false,
//     withTest: Boolean = true
// ) extends Component {

//   // default cd
//   val cd500m = ClockDomain.current
//   cd500m.renamePulledWires("clk500m", "rst500m")
//   cd500m.readResetWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst500m rst")
//   cd500m.readResetWire.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
//   cd500m.readClockWire.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk500m clk")
//   cd500m.readClockWire.addAttribute(
//     "X_INTERFACE_PARAMETERS", "FREQ_HZ 500000000, ASSOCIATED_RESET rst500m"
//   )

//   val pluginsArea = QubicPlugins.getPlugins(qubitNum)
//   val plugins = pluginsArea.plugins
//   if (withWhitebox) {
//     plugins += new test.WhiteboxerPlugin()
//   }


//   // cd100m
//   val clk100m = in Bool ()
//   val rst100m = in Bool ()
//   val cd100m = ClockDomain(clk100m, rst100m)
//   rst100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:reset:1.0 rst100m rst")
//   rst100m.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
//   clk100m.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 clk100m clk")
//   clk100m.addAttribute("X_INTERFACE_PARAMETERS", "FREQ_HZ 100000000, ASSOCIATED_RESET rst100m")

//   val riscq_rst = in Bool ()
//   // val riscqRst = RegNext(RegNext(riscq_rst))
//   val riscqRst = BufferCC(riscq_rst)

//   val axiConfig = Axi4Config(
//     addressWidth = 32,
//     dataWidth = 32,
//     idWidth = 1,
//   )

//   val axi = cd100m(slave(Axi4(axiConfig)))
//   val tlBus = withTest generate cd100m(tlBusNode)

//   val shareBus = cd100m(Node())

//   // pulse mem
//   val pulseOffset = 0
//   val pulseMems = pluginsArea.pgSpecs.map(spec =>
//     DualClockRam(
//       width = spec.batchSize * spec.dataWidth,
//       depth = spec.bufferDepth,
//       slowCd = cd100m,
//       fastCd = ClockDomain.current
//     )
//   )

//   val rbp = execute.ReadoutBufferPlugin(0)
//   plugins += rbp
//   val readoutBuffer = DualClockRam(width = 128, depth = 1024, slowCd = cd100m, fastCd = cd500m)
//   val rbLogic = Fiber build new ResetArea(riscqRst, false) {
//     val addr = Reg(readoutBuffer.fastPort.address) init 0
//     val valid = rbp.logic.outData.valid
//     when(valid) {
//       addr := addr + 1
//     }
//     readoutBuffer.fastPort.enable := True
//     readoutBuffer.fastPort.mask.setAllTo(True)
//     readoutBuffer.fastPort.address := addr
//     readoutBuffer.fastPort.write := valid
//     readoutBuffer.fastPort.wdata := rbp.logic.outData.payload
//   }
//   val rbBusLogic = new ClockingArea(cd100m) {
//     val rbTlFiber = TileLinkMemReadWriteFiber(readoutBuffer.slowPort)
//     val rbTlWa = WidthAdapter()
//     rbTlWa.up at 0x0f000000L of shareBus
//     rbTlFiber.up at 0 of rbTlWa.down
//   }

//   // cpu instruction bus

//   // instruction memory for qubic
//   val pcOffset = 0x80000000L
//   val iMemBusOffset = 0x01000000L
//   val dMemOffset = 0x08000000L
//   val memBits = 128
//   val iMem = DualClockRam(width = 128, depth = 1024, slowCd = cd100m, fastCd = cd500m)

//   val dMemBits = 32
//   val dMem = DualClockRam(width = 32, depth = 1024, slowCd = cd100m, fastCd = cd500m)

//   val memConnects = plugins.map {
//     case p: fetch.FetchCachelessPlugin => {
//       // val iBus = Node.down()
//       // val iBusFiber = TileLinkMemReadWriteFiber(iMem.fastPort)
//       // iBusFiber.up at 0 of iBus
//       // iBusFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
//       // plugins += new fetch.FetchCachelessTileLinkPlugin(iBus)
//       new fetch.FetchCachelessBramConnectArea(p, iMem.fastPort)
//     }
//     case p: execute.lsu.LsuCachelessPlugin => {
//       val dBus = Node.down()
//       val dBusFiber = TileLinkMemReadWriteFiber(dMem.fastPort)
//       dBusFiber.up at 0 of dBus
//       dBusFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
//       plugins += new execute.lsu.LsuCachelessTileLinkPlugin(dBus)
//       // new execute.lsu.LsuCachelessBramConnectArea(p, dMem.fastPort)
//     }
//     case _ =>
//   }

//   val cd100mLogic = new ClockingArea(cd100m) {
//     // blockSize is the maximal bytes that can be transfered in a transaction, which could takes multiple bits
//     // slotsCount is the number of sources
//     val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 2)
//     bridge.up load axi
//     shareBus at 0 of bridge.down
//     if (withVivado) { riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS") }
//     if (withCocotb) { riscq.misc.Axi4CocotbHelper.setName(axi) }
//     // axi.addAttribute("X_INTERFACE_PARAMETER", "CLK_DOMAIN clk100m_clk_p")

//     if (withTest) {
//       shareBus at 0 of tlBus.node
//     }

//     // envelope memory
//     val pulseMemWa = WidthAdapter()
//     pulseMemWa.up at 0 of shareBus
//     val pulseMemBus = Node()
//     pulseMemBus at 0 of pulseMemWa.down
//     pulseMemBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

//     val pulseMemFibers = for (i <- 0 until qubitNum * 2) yield new Area {
//       val pulseMemFiber = TileLinkMemReadWriteFiber(pulseMems(i).slowPort)
//       val offset = pulseOffset + (1 << 20) * i
//       // println(s"!!!!!! pulseoffset: ${BigInt(offset).toString(16)}")
//       pulseMemFiber.up at offset of pulseMemBus
//       pulseMemFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
//     }

//     val iMemTlFiber = TileLinkMemReadWriteFiber(iMem.slowPort)
//     val iMemWa = WidthAdapter()
//     iMemWa.up at iMemBusOffset of shareBus
//     iMemTlFiber.up at 0 of iMemWa.down
//     iMemTlFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

//     val dMemTlFiber = TileLinkMemReadWriteFiber(dMem.slowPort)
//     dMemTlFiber.up at dMemOffset of shareBus
//     dMemTlFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
//   }
//   val riscqArea = new ResetArea(riscqRst, false) {
//     val riscq = RiscQ(plugins)
//   }

//   // riscv core
//   val core = riscqArea.riscq

//   // pulse generator and carrier generator
//   val pgCgArea = new ResetArea(riscqRst, false) {
//     val pgs = pluginsArea.pgSpecs.map(spec => pulse.PulseGeneratorWithCarrierTop(QubicPlugins.puop, spec))
//     pgs.foreach{_.addAttribute("KEEP_HIERARCHY", "TRUE")}
//     val cgs = pluginsArea.cgSpecs.map(spec => pulse.CarrierGenerator(spec))
//   }
//   val pgs = pgCgArea.pgs
//   val cgs = pgCgArea.cgs

//   (pgs zip cgs).foreach{case (pg, cg) => pg.io.carrier := cg.io.carrier}

//   (pgs zip pulseMems).foreach {
//     case (x, y) => {
//       y.fastPort.enable := True
//       y.fastPort.write := False
//       y.fastPort.mask.setAllTo(False)
//       y.fastPort.wdata.setAllTo(False)
//       y.fastPort.address := x.memPort.cmd.payload
//       x.memPort.rsp := RegNext(y.fastPort.rdata)
//     }
//   }

//   // dac and adc ports
//   val dac = List.fill(qubitNum * 2)(master port Stream(Bits(16 * 16 bits)))
//   dac.zipWithIndex.foreach { case (d, id) =>
//     riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"DAC${id}_AXIS")
//   }
//   val adc = List.fill(qubitNum * 2)(slave port Stream(Bits(4 * 16 bits)))
//   adc.zipWithIndex.foreach { case (d, id) =>
//     riscq.misc.Axi4StreamVivadoHelper.addStreamInference(d, s"ADC${id}_AXIS")
//     d.ready := True
//   }

//   val test_adc = withTest generate (in port Vec.fill(qubitNum * 2)(pulse.ComplexBatch(batchSize = 4, dataWidth = 16)))

//   // pulse generator output
//   for((o, pg) <- (dac zip pgs)) {
//     o.payload := pg.io.data.payload.map(_.r).asBits()
//     o.valid := True
//   }

//   // readout input
//   val readoutInputLogic = Fiber build new Area {
//     plugins.foreach {
//       case p: execute.ReadoutPlugin => {
//         for(i <- 0 until p.num) {
//           p.logic.carriers(i) := cgs(2 * p.num + i).io.carrier.payload
//         }
//         if(!withTest) {
//           for((carrierAdc, inAdc) <- (p.logic.adcs zip adc)) {
//             (carrierAdc zip inAdc.payload.subdivideIn(16 bits)).foreach { case(o, i) =>
//               o.r.assignFromBits(i)
//               o.i := o.i.getZero
//             }
//           }
//         } else {
//           (p.logic.adcs zip test_adc).foreach { case (outs, ins) =>
//             outs := ins
//           }
//         }
//       }
//       case _ =>
//     }
//   }

//   val ioLogic = Fiber build new Area {
//     val time = core.host[TimerPlugin].logic.time
//     plugins.foreach {
//       case p: execute.PulseGeneratorPlugin => {
//         (pgs zip p.logic.pgPorts).foreach { case (x, y) => 
//           x.io.event.payload := y.payload
//           x.io.event.valid := y.valid
//           x.io.time := time
//         }
//       }
//       case p: execute.CarrierPlugin => {
//         (cgs zip p.logic.cgPorts).foreach { case (x, y) => 
//           x.io.freq.payload := y.payload.freq
//           x.io.freq.valid := y.valid
//           x.io.phase.payload := y.payload.phase
//           x.io.phase.valid := y.valid
//           x.io.time := time
//         }
//       }
//       case _ =>
//     }
//   }

//   def tlBusNode = {
//     new MasterBus(
//       tilelink.M2sParameters(
//         addressWidth = 32,
//         dataWidth = 32,
//         masters = List(
//           tilelink.M2sAgent(
//             name = this,
//             mapping = List(
//               tilelink.M2sSource(
//                 id = SizeMapping(0, 4),
//                 emits = tilelink.M2sTransfers(
//                   get = tilelink.SizeRange.upTo(0x100),
//                   putFull = tilelink.SizeRange.upTo(0x100),
//                   putPartial = tilelink.SizeRange.upTo(0x100)
//                 )
//               )
//             )
//           )
//         )
//       )
//     )
//   }
// }

// object GenQubicCocotb extends App {
//   SpinalConfig(
//     mode = Verilog
//     // targetDirectory="./riscq-vivado/rtl"
//   ).generate(
//     QubicSoc(
//       qubitNum = 8,
//       withVivado = false,
//       withCocotb = true,
//       withWhitebox = false
//     )
//   )
// }

// object GenQubicVivado extends App {
//   SpinalConfig(
//     mode = Verilog,
//     targetDirectory = "./build/rtl",
//     romReuse = true
//   ).generate(
//     QubicSoc(
//       qubitNum = 8,
//       withVivado = true,
//       withCocotb = false,
//       withWhitebox = false,
//       withTest = false
//     )
//   )
//   SpinalConfig(
//     mode = Verilog,
//     targetDirectory = "./build/rtl",
//     romReuse = true
//   ).generate(
//     riscq.misc.ClockInterface()
//   )
// }

// object QubicTileLinkSoc {
//   def apply() = QubicSoc(qubitNum = 2, withWhitebox = true, withVivado = false, withCocotb = false)
// }

// object BenchQubicSoc extends App {
//   val rtl = Rtl(SpinalVerilog(
//     QubicSoc(
//       qubitNum = 8,
//       withVivado = true,
//       withCocotb = false,
//       withWhitebox = false,
//       withTest = false
//     )
//   ))
//   Bench(List(rtl), XilinxRfsocTarget(), "./build/bench")
// }
