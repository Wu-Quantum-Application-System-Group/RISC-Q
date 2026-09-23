package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.fabric.{Node, MasterBus, WidthAdapter}
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber
import spinal.lib.bus.misc.SizeMapping
import riscq.dsp.{AdderTree, ComplexBatch}
import riscq.riscv.RiscqParam
import riscq.soc.fabric.{BramFiber, MemMapDriverFiber}
import riscq.soc.link.{HostWindowFunnel, PutHub, EventLink, RfCmd}
import riscq.soc.spec.{CoreSpec, SocSpec, SocSpecMap}
import riscq.misc.BUFG
import riscq.wr.WrNode
import riscq.wr.gty.{WrGtyPhy, WrGtyPhyParams, WrPhyIo}
import scala.collection.mutable.LinkedHashMap
import scala.collection.mutable

/**
 * Multi-qubit control SoC — the agentic reproduction of the RISC-Q reference `PulseTableSoc`. One
 * shared 32-bit batch-time counter drives `qubitNum` identical [[RiscqRfWithPulseTableFiber]] qubit
 * cores; the host AXI bus loads programs / pulse tables / control while the faster dsp domain runs the
 * real-time datapath.
 *
 * The toplevel: bridges `io.axi` → Tilelink and fans it to per-core instruction memory, per-core
 * pulse memory, the readout buffers (`robs`) and a host control block (`riscqReset` /
 * 64-bit `timeOffset`); builds the shared `time` from a free-running `refTime + timeOffset`; maps
 * logical DAC/ADC **channels** to physical converters (`dacMap`/`adcMap`), summing channels that share
 * a DAC with [[AdderTree]]; and streams a readout trace into `robs` on pulse fire.
 *
 * Per-core results leave over the **host window** (specs/software/22): each core's write-only 16 MB
 * window funnels through a [[HostWindowFunnel]] onto `io.hostMem` → the PS DDR4, addressed
 * `base + (core << 24) + offset` from the `HOSTWIN_BASE_LO/HI` registers of the host control block.
 *
 * `withTest` exposes each core's CPU data-bus decode (`dMemPortDec`) to a second Tilelink master so a
 * sim can configure the RF (schedule pulses) without a CPU program — in the real SoC the CPU is the
 * sole `dBus` master.
 *
 * @param dacMap (core, channel) → physical DAC id.   @param adcMap core → physical ADC id.
 */
class PulseTableSoc(
    val spec: SocSpec,
    val withTest: Boolean = false,
    val vivado: Boolean = false,
    // RISC-V core plugin config, replicated across all cores (each core's `withMul` comes from its
    // CoreSpec). Defaults to the verified timing-closure stack for the packed multi-core floorplan,
    // every flag RVLS-bit-exact:
    //   - `gshareMem` moves the GShare 2-bit counter table from a flip-flop array + one-hot write decode
    //     into a synchronous-read LUTRAM (cuts per-core control sets and reset FFs);
    //   - `csrWarl` applies the CSR WARL latitude (trims the reset group);
    //   - `aluNoFastForward` drops the srcA fast-forward → interlocks every 1-ahead RAW (small IPC cost),
    //     taking the ALU-result-mux→RD_DATA→forward loop off the path;
    //   - `aluResultOneHot` builds the ALU result mux as a balanced one-hot masked-OR cone (zero IPC);
    //   - `pcRegMaxFanout = 16` replicates the route-dominated fetch predicted-PC register.
    // Pass `RiscqParam()` to A/B the pre-opt core.
    val coreParam: RiscqParam = RiscqParam(gshareMem = true, csrWarl = true,
      aluNoFastForward = true, aluResultOneHot = true, pcRegMaxFanout = 16),
    // host window (specs/software/22): the PS physical address width.
    val hostMemAddrWidth: Int = 40,
) extends Zcu216Top(dacNum = spec.dacNum, adcNum = spec.adcNum, dacBatch = 16, adcBatch = 4, dataWidth = 16, vivado = vivado,
                    dio = PulseTableSoc.dioNames(spec)) {
  // Every per-SoC parameter comes from the spec (universal-control/01 P1): the cores' channel lists,
  // converter ids and memory sizes from their CoreSpecs; the link depth, host-window width, readout
  // trace depth and the RFDC-edge ADC pipe (specs/dsp-fmax.md C2) from the SoC fields.
  val qubitNum         = spec.qubitNum
  val dacNum           = spec.dacNum
  val adcNum           = spec.adcNum
  val linkPipe         = spec.linkPipe          // narrow posted-link per-direction RegNext depth
  val robDepth         = spec.robDepth
  val hostWinAddrWidth = spec.hostwinBits
  val adcPipe          = spec.adcPipe
  val withWhiteRabbit  = spec.withWhiteRabbit   // White Rabbit node window + phy ports (specs/white-rabbit/06)
  val wrMarkerDac      = spec.wrMarkerDac       // spare DAC carrying the sync marker (white-rabbit/09)
  val N        = 16    // DAC drive batch
  val adcBatch = 4
  val w        = 16
  val robWidth = adcBatch * 32       // 4 readout lanes × 32-bit (no overflow summing ≤16 ADCs)

  // ── host AXI → Tilelink ── blockSize ≥ the widest full-word transfer (the robs WidthAdapter's
  // 128-bit / 16-byte line); each fiber's decoder restricts the size down to what it supports. The
  // write-only envelope banks load 32-bit sub-word (no WidthAdapter), so they never need a wide burst.
  // `slotsCount` also caps how many host reads can be in flight toward a core's instruction/data RAM,
  // and that cap is what makes `TileLinkCpuMemFiber` legal on that port: the stripped slave never
  // back-pressures its d channel, which holds only while the d path back to this bridge can absorb
  // every outstanding response. That path buffers 14 beats — 3 × StreamPipe.FULL (2 each) on
  // hostBus → riscqMemBus → iMemPortArb, plus the dsp→host FifoCc's dDepth of 8 — so keep
  // slotsCount well under it (see docs/soc/TileLinkMemFiber.md and specs/software/23 §1.3).
  val hostSlots = 4
  require(hostSlots <= 14, s"host slots ($hostSlots) exceed the d-channel buffering to the CPU-mem slave")
  val bridge = new Axi4ToTilelinkFiber(blockSize = 64, slotsCount = hostSlots)
  bridge.up load io.axi
  val hostBus = Node()
  hostBus at 0 of bridge.down
  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val map = SocSpecMap(spec)
  require(map.robBytes == robWidth * robDepth / 8)

  // The envelope banks are write-only (BramWriteFiber): each core's fiber steers a 32-bit host beat
  // straight into the addressed sub-word lane of its wide line, so NO WidthAdapter is needed. The
  // per-bank regions are decoded off hostBus as NARROW 32-bit fan-out buses, so only 32-bit nets cross
  // the die and every wide envelope net stays local to its core (saving routing). The 32-bit
  // instruction memory (riscqMemBus) likewise wires direct. One region per channel slot j (every
  // core's j-th channel bank, SocSpecMap.slotBases), named after the qubit builds' banks where the
  // slot is one.
  val envBuses: Seq[Option[Node]] = (0 until map.nSlots).map { j =>
    // a slot no core has a bank in (a dio-only slot) gets no region bus: a fabric node needs a slave
    if (!spec.cores.exists(c => c.channels.length > j && c.channels(j).envBytes > 0)) None else {
      val bus = Node()
      bus at SizeMapping(map.slotBases(j), map.regionSize) of hostBus
      bus.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
      bus.setName(Seq("pulseMemBus", "readoutEnvBus", "demodEnvBus").lift(j).getOrElse(s"envBus$j"))
      Some(bus)
    }
  }
  val riscqMemBus = Node()
  riscqMemBus   at SizeMapping(map.coreMemBase,    map.regionSize) of hostBus
  riscqMemBus.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val robs = BramFiber(1, robWidth, robDepth, hostCd, dspCd, withOutReg = true)
  val robAdapter = WidthAdapter()
  robAdapter.up at SizeMapping(map.robBase, map.regionSize) of hostBus
  robs.up at SizeMapping(0, map.regionSize) of robAdapter.down
  robs.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // ── reset/control crossing into the dsp/riscq domain ──
  val riscqReset = Bool()
  val riscqCd    = ClockDomain(dspCd.readClockWire, riscqReset)

  // WR sync-marker drive for the spare DAC: pre-declared so the dspCd DAC combine (inside
  // riscqArea) can consume it — the WrNode that drives it is created after riscqArea (it needs
  // riscqArea.refTime). Assigned in the White Rabbit section below.
  require(wrMarkerDac.forall(d => withWhiteRabbit && d >= 0 && d < dacNum),
    s"wrMarkerDac=$wrMarkerDac needs withWhiteRabbit and a valid DAC id")
  val wrMarkerPulse = (withWhiteRabbit && wrMarkerDac.isDefined) generate Bool()

  /** Converter-boundary pipeline: `converterPipe` extra register stages on the long DAC/ADC nets
   *  into/out of the RFDC edge. converterPipe = 0 ⇒ identity (no behavioural change). */
  def pipe[T <: Data](x: T, converterPipe: Int): T = (0 until converterPipe).foldLeft(x)((s, _) => RegNext(s))

  val riscqArea = new ClockingArea(dspCd) {
    // Free-running batch-time counter in dspCd: it is NOT reset by riscqReset, so batch time is monotonic
    // across runs (only the external dspRst zeroes it). This keeps a leftover TimedQueue entry from a prior
    // run in the *past* rather than the future, so it drains on its own and a run needs no per-run flush.
    // Software works in now()-relative time, so the non-zero base is transparent.
    val refTime    = Reg(UInt(64 bit)) init 0
    refTime := refTime + 1
    val timeOffset = Reg(UInt(64 bit)) init 0
    val syncTime   = RegNext(refTime + timeOffset)
    val time       = RegNext(syncTime(0, 32 bits))
    time.addAttribute("MAX_FANOUT", 16)

    // per-core batch-clock replica: each core gets an independent register fed from the same `syncTime`,
    // so it is value-identical to `time` every cycle (no skew); EQUIVALENT_REGISTER_REMOVAL=NO stops
    // Vivado folding the identical replicas back into one shared high-fanout net. The floorplan bench
    // pins each `coreTime_i` register to its core's region so the replicated time stays local.
    val coreTimes = List.tabulate(qubitNum) { i =>
      val t = RegNext(syncTime(0, 32 bits))
      t.addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
      t.addAttribute("MAX_FANOUT", 16)
      t.setName(s"coreTime_$i")
      t
    }

    def cp(core: CoreSpec) = coreParam.copy(
      fetchPcWidth = Some(log2Up(core.memDepth) + 2),
      fetchLatency = 4,
      withMul = core.withMul)
    val riscqCores = spec.cores.toList.zipWithIndex.map { case (core, i) =>
      RiscqRfWithPulseTableFiber(
        spec = core, plugins = cp(core).plugins(), dspCd = dspCd, hostCd = hostCd, riscqCd = riscqCd,
        time = coreTimes(i), batchSize = N, dataWidth = w, adcBatch = adcBatch,
        linkPipe = linkPipe, hostWinAddrWidth = hostWinAddrWidth,
        withTestTap = withTest) }

    // floorplan: keep each core's RiscvSoc a hard synth boundary so opt can't merge logic across the
    // identical cores into a MUXF7/F8 macro that straddles two per-core pblocks. The shared host AXI fans
    // instruction-load to every core, so opt_design otherwise shares equivalent iLoad-response logic
    // between cores. Synthesis-only attribute — zero behavioural change, sims ignore it.
    riscqCores.foreach(_.riscvSoc.addAttribute("KEEP_HIERARCHY", "TRUE"))

    // ── the board hub (specs/cross-core/02 §4): every core's system puts in, one ordered broadcast
    // out, replicated per core (valid gated by that core's mask bit) and piped like the RF link. It
    // lives in riscqCd so a run boundary (riscqReset) clears its boards, barrier counts and flags.
    val hub = riscqCd(PutHub(cores = qubitNum, board = spec.board, boards = spec.boards))
    for ((core, i) <- riscqCores.zipWithIndex) hub.in(i) << core.xput
    hub.time := RegNext(syncTime(0, 32 bits))
    for ((core, i) <- riscqCores.zipWithIndex) {
      val mine = Flow(RfCmd(EventLink.inboxAddrWidth))
      mine.valid   := hub.out.valid && hub.out.payload.mask(i)
      mine.payload := hub.out.payload.put
      core.hubIn << core.getPipe(mine, linkPipe)
    }

    // host fan-out: per-core instruction memory + every write-only envelope bank wire DIRECT to their
    // narrow 32-bit region bus — each envelope fiber bridges a 32-bit host beat into its wide line
    // locally (no WidthAdapter). Offsets are relative to each region bus (rebased 0); the core's j-th
    // bank sits in slot region j at the slot's stride.
    for ((core, i) <- riscqCores.zipWithIndex) {
      core.iMemPortArb        at SizeMapping(i * map.coreStride,       map.coreStride)       of riscqMemBus
      core.iMemPortArb.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
      for ((Some(mem), j) <- core.envMems.zipWithIndex) {   // bank-less slots (dio) stay holes
        mem.up at SizeMapping(i * map.slotStrides(j), map.slotStrides(j)) of envBuses(j).get
        mem.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
      }
    }

    // optional test masters so a sim can drive each core's RF/control (CPU is held in reset). Each core
    // re-exposes its data-bus decode as a `dMemPortDec` tap node (bridged into RiscvSoc's `dTap`
    // slave-IO, withTestTap = withTest); the sim's MasterAgent drives this master.
    val testMasters = withTest generate riscqCores.map { core =>
      val mb = riscqCd(new MasterBus(tilelink.M2sParameters(
        addressWidth = 32, dataWidth = 32,
        masters = List(tilelink.M2sAgent(name = null, mapping = List(tilelink.M2sSource(
          id = SizeMapping(0, 4),
          emits = tilelink.M2sTransfers(
            get = tilelink.SizeRange.upTo(0x40), putFull = tilelink.SizeRange.upTo(0x40),
            putPartial = tilelink.SizeRange.upTo(0x40)))))))))
      core.dMemPortDec at 0 of mb.node
      mb
    }

    // ── DAC: per physical DAC, sum the real lanes of every logical channel mapped to it, then pad
    // every DRIVEN DAC to one shared pipeline depth from a core's `dac(ch)` to `io.dac`, so every pulse
    // generator sees the same register count to the converter edge — gate and readout drives (whether on
    // their own DAC or summed with others) all leave time-aligned. The natural "combine" latency differs
    // per DAC — a single channel is a pass-through (0), an n-channel sum costs `AdderTree.latency(n)+1` —
    // so pad each path up to the deepest one.
    //
    // Channels are addressed by (core, k) with k the channel's index among the core's DAC-bound
    // channels (the order of `RiscqRfWithPulseTableFiber.dac`), straight from the spec.
    val dacChannels = (0 until dacNum).map { dacId =>
      spec.cores.toList.zipWithIndex.flatMap { case (core, c) =>
        core.channels.flatMap(_.dac).zipWithIndex.collect { case (d, k) if d == dacId => riscqCores(c).dac(k) } }
    }
    def combineLatency(n: Int) = if (n <= 1) 0 else AdderTree.latency(n) + 1
    val dacAlignStages = dacChannels.map(p => combineLatency(p.size)).max

    val dacPayloads = dacChannels.zipWithIndex.map { case (pulses, dacId) =>
      val payload = cloneOf(io.dac(dacId).payload)
      io.dac(dacId).payload := pipe(payload, 1)   // one shared stage into the RFDC edge
      if (withWhiteRabbit && wrMarkerDac.contains(dacId)) {
        require(pulses.isEmpty, s"wrMarkerDac=$dacId collides with a dacMap channel")
        // scope-visible sync marker on a spare converter: full-scale while the marker is high.
        // One RegNext + the shared RFDC stage = a constant, board-identical delay from the
        // syncTime compare (any residual mismatch lands in the role-swap ε).
        payload := RegNext(Mux(wrMarkerPulse, Vec.fill(N)(B(0x7FFF, w bits)).asBits, B(0, N * w bits)))
      } else if (pulses.isEmpty) {
        payload := 0                              // no generator maps here — nothing to align
      } else {
        val combined =
          if (pulses.size == 1) Vec(pulses.head.map(_.re)).asBits    // single channel: pass-through
          else {
            // No saturation on the co-mapped channel sum: it wraps modulo 2^w (keep the low w bits),
            // matching the QubiC reference (elementsum "not checking overflow, depends on user"). Software
            // keeps the summed channels within full-scale; dropping the saturating clamp also takes its
            // comparators/muxes off the DAC converter-boundary path.
            val accW = w + log2Up(pulses.size)
            Vec.tabulate(N)(k => RegNext(AdderTree(pulses.map(_(k).re), accW).resize(w))).asBits
          }
        // pad this DAC's combine path up to the deepest driven DAC so every generator sees one depth.
        payload := pipe(combined, dacAlignStages - combineLatency(pulses.size))
      }
      payload
    }

    // ── ADC: buffer the real lanes of only the MAPPED converters, fan to the mapped cores (im = 0).
    // Unmapped physical ADCs are left untouched — no buffer registers, no contribution to the trace. ──
    val adcMap: Map[Int, Int] = spec.adcMap            // core → the adc of its demod channel
    val mappedAdcIds = adcMap.values.toList.distinct.sorted
    val adcBufs = mappedAdcIds.map { adcId =>
      val a = Vec.fill(adcBatch)(SInt(w bits))
      a.assignFromBits(io.adc(adcId).payload)
      adcId -> pipe(a, adcPipe).addAttribute("max_fanout", 4)   // extra register stages off the RFDC edge
    }.toMap
    for ((coreId, adcId) <- adcMap) {
      (riscqCores(coreId).adc zip adcBufs(adcId)).foreach { case (o, i) => o.re := i; o.im := 0 }
    }

    // ── timed-DIO banks straight to the board ports (no converter, no map) ──
    for ((core, c) <- riscqCores.zipWithIndex; (ch, d) <- core.dios) {
      val k = PulseTableSoc.dioNames(spec).indexOf(s"${spec.cores(c).name}_${ch.name}")
      io.dioOut(k) := d.io.dout
      d.io.din := io.dioIn(k)
    }

    // ── readout trace into robs on any traced (readout-drive) pulse fire ──
    val anyPulseValid = riscqCores.flatMap(_.tracePulses.map(_.valid)).reduceBalancedTree(_ | _, (s, _) => RegNext(s))
    val fire   = RegNext(anyPulseValid)
    val rbAddr = Reg(UInt(log2Up(robDepth) bits))
    when(fire)(rbAddr := rbAddr + 1).otherwise(rbAddr := 0)

    // robs(0): per-lane sum of the MAPPED ADC inputs (a 32-bit-per-lane integrated trace).
    val adcSum = Vec.tabulate(adcBatch)(k => RegNext(AdderTree(mappedAdcIds.map(id => adcBufs(id)(k)), 32)))
    val rb0 = robs.rams(0).fastPort
    rb0.enable := True; rb0.mask.setAllTo(True)
    rb0.address := RegNext(rbAddr); rb0.write := fire
    rb0.wdata   := Vec(adcSum).asBits
  }

  // ── host window: every core's posted result stream → one write-only AXI master → PS DDR4 ──
  // hostCd logic, outside every core's hard band; `io.hostMem` goes to `S_AXI_HP0_FPD`.
  val hostWindow = HostWindowFunnel(qubitNum, hostWinAddrWidth, hostMemAddrWidth)
  for ((core, i) <- riscqArea.riscqCores.zipWithIndex) hostWindow.io.cmd(i) << core.hostCmd
  io.hostMem << hostWindow.io.axi

  // ── host control block (host clock domain) ──
  val riscqResetHostCd = Bool()
  val bufferedReset    = dspCd(BufferCC(riscqResetHostCd, 3))
  // Power up ASSERTED (init True): the cores — and any stateful data-bus adapter like the posted-store
  // shim's FIFO — must come up held in reset until the host releases them, otherwise they run with
  // uninitialised state before the first host reset pulse (a posted store would drain a garbage entry).
  val riscqResetBuf = dspCd(RegNext(bufferedReset | io.dspRst) init True)
  riscqReset := dspCd(RegNext(riscqResetBuf) init True)

  val timeOffset = Reg(UInt(64 bit)) init 0
  riscqArea.timeOffset := dspCd(BufferCC(timeOffset))

  // Host-window base: the PS *physical* address of the result buffer, split LO/HI like `timeOffset`.
  // `enable` powers up LOW so a core storing to the window before the host has programmed `base` stalls
  // visibly instead of writing DDR address 0 (the kernel's memory). `setup` writes both words while
  // `riscqReset` is asserted, so the funnel is idle when the 40-bit address changes — no torn base.
  val hostWinBaseLo = Reg(UInt(32 bits)) init 0
  val hostWinBaseHi = Reg(UInt(hostMemAddrWidth - 32 bits)) init 0
  val hostWinEnable = Reg(Bool()) init False
  hostWindow.io.base   := hostWinBaseHi @@ hostWinBaseLo
  hostWindow.io.enable := hostWinEnable

  // Run-completion flags (specs/software/23), one bit per core: each core's sticky `done` level is
  // crossed into `hostCd` and packed into one read-only word, so `poll_done` reads ONE address for the
  // whole SoC instead of a `__rq_status` word out of every core's RAM. `riscqReset` clears the source
  // registers, so the word self-clears at the run boundary — the driver never writes it. The crossing
  // is a per-bit BufferCC: the bits are independent levels, so no coherence between them is needed,
  // and dspClk/hostClk are an asynchronous clock group (constraints-zcu216.xdc), so this arc is not
  // timed against the 500 MHz path.
  require(qubitNum <= 32, s"the host control block's DONE word carries one bit per core, got qubitNum=$qubitNum")
  val doneHostCd = Bits(qubitNum bits)
  for ((core, i) <- riscqArea.riscqCores.zipWithIndex) doneHostCd(i) := BufferCC(core.done, False)
  val hubMismatchHostCd = BufferCC(riscqArea.hub.countMismatch, False)   // sticky until riscqReset

  val hostCtrlDriver = MemMapDriverFiber(addressWidth = 10, dataWidth = 32, driveProc = { factory =>
    factory.drive(riscqResetHostCd, 0)
    factory.read(doneHostCd, 0x50)                     // DONE: bit i = core i has finished
    factory.read(hubMismatchHostCd, 0x54)              // HUB_STATUS: bit 0 = a barrier's counts disagreed
    factory.write(timeOffset(0, 32 bits), 64)
    factory.write(timeOffset(32, 32 bits), 68)
    factory.write(hostWinBaseLo, 72)                        // HOSTWIN_BASE_LO = base[31:0]
    factory.write(hostWinBaseHi, 76, 0)                     // HOSTWIN_BASE_HI[7:0]  = base[39:32]
    factory.write(hostWinEnable, 76, 31)                    // HOSTWIN_BASE_HI[31]   = enable
  })
  hostCtrlDriver.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  hostCtrlDriver.up at SizeMapping(map.hostCtrlBase, map.regionSize) of hostBus

  // ── White Rabbit node (specs/white-rabbit/06): the syncTime-alignment peripheral as a register
  // window at map.wrBase — the hostCtrlDriver idiom. On sim builds (`vivado = false`) the phy
  // contract is a toplevel port driven by the GtySimPhy model (WrNodeSim, the cocotb bench); the
  // vivado build instantiates the real WrGtyPhy (W4) and exposes the GTY board pins instead. ──
  val wrPhy    = (withWhiteRabbit && !vivado) generate slave(WrPhyIo())
  val wrMarker = withWhiteRabbit generate (out Bool ())
  val wrRefClkP, wrRefClkN, wrRxP, wrRxN = (withWhiteRabbit && vivado) generate (in Bool ())
  val wrTxP, wrTxN                       = (withWhiteRabbit && vivado) generate (out Bool ())
  val wrGtyPhy = (withWhiteRabbit && vivado) generate {
    // clk_free for the GT bring-up FSMs: hostClk/2 through a BUFG (PG182: ≤ 62.5 MHz with both
    // buffers bypassed, W4 harvest note). The FF divider is constrained by name via the
    // create_generated_clock in vivado-scripts/riscvsoc-bd/constraints-wr.xdc.
    val div = hostCd { val r = Reg(Bool()) init False; r := !r; r }
    div.setName("wrClkFreeDiv")
    val bufg = BUFG()
    bufg.I := div
    val freeCd = ClockDomain(bufg.O, config = ClockDomainConfig(resetKind = BOOT),
      frequency = FixedFrequency(50 MHz))
    val gty = freeCd(WrGtyPhy(WrGtyPhyParams()))
    gty.io.refClkP := wrRefClkP
    gty.io.refClkN := wrRefClkN
    gty.io.rxP := wrRxP
    gty.io.rxN := wrRxN
    wrTxP := gty.io.txP
    wrTxN := gty.io.txN
    gty
  }
  val wrNode = withWhiteRabbit generate WrNode(if (vivado) wrGtyPhy.io.phy else wrPhy,
    riscqArea.refTime, riscqArea.syncTime, hostCd, dspCd)
  // the hub's lane: the put frames ride the WR link (specs/cross-core/02 §5); no link ⇒ tied off
  if (withWhiteRabbit) {
    wrNode.putTx << riscqArea.hub.laneOut
    riscqArea.hub.laneIn << wrNode.putRx
  } else {
    riscqArea.hub.laneOut.ready := True
    riscqArea.hub.laneIn.valid := False
    riscqArea.hub.laneIn.payload.assignDontCare()
  }
  if (withWhiteRabbit) {
    wrMarker := wrNode.marker
    if (wrMarkerDac.isDefined) wrMarkerPulse := wrNode.marker
    // near-end PMA loopback (CTRL[4]) for the W6 single-board self-test: a quasi-static host
    // level — software sets it in the same CTRL write that releases resetAll, so it is stable
    // before the sequenced GT resets deassert (UG578: change LOOPBACK, then reset).
    if (vivado) wrGtyPhy.io.loopback := wrNode.ctrl.pmaLoopback ? B"010" | B"000"
    wrNode.regs.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
    wrNode.regs.up at SizeMapping(map.wrBase, map.regionSize) of hostBus
  }

  Fiber build new Area {
    riscqArea.refTime.simPublic() // sims force/observe the free-running counter (e.g. WrNodeSim's 2^32 crossing)
    riscqArea.riscqCores(0).riscvSoc.dMemPortDec.bus.get.simPublic()
    if (withTest) {
      riscqArea.time.simPublic()
      riscqArea.riscqCores(0).gatePulse.valid.simPublic()
      riscqArea.testMasters.foreach(_.node.bus.get.simPublic())
    }
  }
}

object PulseTableSoc {
  /** the board-port names of every timed-DIO channel, `<core>_<channel>`, in spec order */
  def dioNames(spec: SocSpec): Seq[String] =
    spec.cores.flatMap(c => c.channels.filter(_.kind == "dio").map(ch => s"${c.name}_${ch.name}"))

  /** The qubit-build constructor the sims, benches and generators call: `qubitNum` identical gate /
    * ro / demod cores with the given converter maps — `SocSpec.qubits` under the hood. */
  def apply(
      qubitNum: Int,
      dacMap: Map[(Int, Int), Int],
      adcMap: Map[Int, Int],
      dacNum: Int = 16,
      adcNum: Int = 16,
      withTest: Boolean = false,
      vivado: Boolean = false,
      coreParam: RiscqParam = RiscqParam(gshareMem = true, csrWarl = true,
        aluNoFastForward = true, aluResultOneHot = true, pcRegMaxFanout = 16),
      readoutInterp: Int = 16,
      gateInterp: Int = 4,
      demodInterp: Int = 4,
      linkPipe: Int = 4,
      memDepth: Int = 4096,
      envDepth: Int = 1024,
      robDepth: Int = 1024,
      gatePulseNum: Int = 8,
      queueDepth: Int = 4,
      adcPipe: Int = 3,
      hostWinAddrWidth: Int = 24,
      hostMemAddrWidth: Int = 40,
      withWhiteRabbit: Boolean = false,
      wrMarkerDac: Option[Int] = None): PulseTableSoc =
    new PulseTableSoc(
      SocSpec.qubits(qubitNum, dacMap, adcMap, dacNum = dacNum, adcNum = adcNum,
        gatePulseNum = gatePulseNum, envDepth = envDepth, gateInterp = gateInterp,
        readoutInterp = readoutInterp, demodInterp = demodInterp, memDepth = memDepth,
        withMul = coreParam.withMul, queueDepth = queueDepth, linkPipe = linkPipe,
        hostwinBits = hostWinAddrWidth, robDepth = robDepth, adcPipe = adcPipe)
        .copy(withWhiteRabbit = withWhiteRabbit, wrMarkerDac = wrMarkerDac),
      withTest, vivado, coreParam, hostMemAddrWidth)
}

/**
 * DAC/ADC channel→converter maps for the ZCU216 build, generic in `qubitNum` (matches the 14-qubit
 * `GenPulseTableSoc` layout when `qubitNum == 14`): each qubit's gate-drive channel gets its own DAC
 * `0..qubitNum-1`; its readout-drive channel and its ADC share converter 14 (qubits 0–6) or 15 (7+).
 */
object SocChannelMap {
  def readoutDriverConverter(core: Int): Int = if (core < 7) 14 else 15
  def readoutConverter(core: Int): Int = if (core < 7) 0 else 4
  def gateConverter(core: Int): Int = core
  def dacMap(qubitNum: Int): Map[(Int, Int), Int] =
    (0 until qubitNum).flatMap(c => List((c, 0) -> gateConverter(c), (c, 1) -> readoutDriverConverter(c))).toMap
  def adcMap(qubitNum: Int): Map[Int, Int] =
    (0 until qubitNum).map(c => c -> readoutConverter(c)).toMap
}

/**
 * RTL generation for the Vivado ZCU216 flow: emits `PulseTableSoc.v` *with* the
 * `X_INTERFACE_INFO`/`FREQ_HZ` bus-interface attributes (host clock renamed `hostClk`/`hostRst`) plus the
 * `ClockInterface.v` clock-buffer wrapper, both into `build/rtl`. The qubit count is `args(0)` (default 14,
 * the full ZCU216 config); use a small count for quick script iteration. `romReuse` shares the per-core
 * register-file ROM init across the identical cores.
 */
object GenPulseTableSocVivado extends App {
  // args: `[N]` qubit count (default 14) and `[dir]` target directory (the first non-numeric arg;
  // default `./build/rtl`), so the `.v` + `ClockInterface.v` + register-file `.bin` land in the
  // per-project build dir the Vivado flow runs from.
  // Builds the narrow posted-link RF architecture — pair with the per-core / two-region floorplan.
  // PulseTableSoc tags each core's `RiscvSoc` `(* KEEP_HIERARCHY = "TRUE" *)` so synthesis can't
  // dissolve or cross-merge the identical cores; the per-core pblocks pin each core's `RiscvSoc`, so
  // that boundary must remain a distinct macro for the floorplan to bind.
  val qubitNum = args.filter(_.forall(_.isDigit)).headOption.map(_.toInt).getOrElse(14)
  val dir      = args.find(a => a.nonEmpty && !a.forall(_.isDigit)).getOrElse("./build/rtl")
  val cfg      = SpinalConfig(mode = Verilog, targetDirectory = dir, romReuse = true).setScopeProperty(LutInputs, 6)
  cfg.generate(PulseTableSoc(
    qubitNum = qubitNum,
    dacMap   = SocChannelMap.dacMap(qubitNum),
    adcMap   = SocChannelMap.adcMap(qubitNum),
    vivado   = true))
  cfg.generate(riscq.misc.ClockInterface())
  println(s"[GenPulseTableSocVivado] emitted $dir/PulseTableSoc.v + ClockInterface.v (qubitNum=$qubitNum, vivado=true)")
}

/**
 * RTL for the standalone OOC fmax/floorplan flow — the faithful per-core-pblock form.
 *
 *   - `vivado = false` — plain `dspClk` / `clk` ports, no `X_INTERFACE_INFO`/`FREQ_HZ` IP-packager attrs
 *     and no separate `ClockInterface.v` wrapper. The OOC bench constrains the raw ports directly, so it
 *     must NOT be the `vivado = true` (IP) form `GenPulseTableSocVivado` emits, whose host clock is
 *     renamed `hostClk`/`hostRst`.
 *   - PulseTableSoc tags `(* KEEP_HIERARCHY *)` on every `RiscvSoc`, so the separately-pblocked cores
 *     stay distinct macros (otherwise `opt_design` merges cross-core iLoad-response logic into a
 *     MUXF7/F8 that straddles two per-core pblocks and can't place).
 *
 * Every lever is the `PulseTableSoc` constructor default (replicated batch clock, the `aluNoFastForward`
 * + `aluResultOneHot` core levers, `linkPipe = 4`, congestion-lean `coreParam`), so this generator only
 * has to pick the OOC (`vivado = false`) form.
 *
 * args: `[N]` qubit count (default 14) and `[dir]` target directory (the first non-numeric arg; default
 * `./build/rtl-riscvsoc`), so the `.v` + register-file `.bin` land where the Tcl runs Vivado.
 */
object GenPulseTableSocOoc extends App {
  val qubitNum = args.filter(_.forall(_.isDigit)).headOption.map(_.toInt).getOrElse(14)
  val dir      = args.find(a => a.nonEmpty && !a.forall(_.isDigit)).getOrElse("./build/rtl-riscvsoc")
  SpinalConfig(mode = Verilog, targetDirectory = dir, romReuse = true)
    .generate(PulseTableSoc(
      qubitNum = qubitNum,
      dacMap   = SocChannelMap.dacMap(qubitNum),
      adcMap   = SocChannelMap.adcMap(qubitNum)))
  println(s"[GenPulseTableSocOoc] emitted $dir/PulseTableSoc.v (qubitNum=$qubitNum, vivado=false)")
}
