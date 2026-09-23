package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.misc.plugin.Hostable
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.fabric.{Node, MasterBus}
import spinal.lib.bus.misc.SizeMapping
import riscq.memory.{Bram, HalfUram, Uram}
import riscq.soc.fabric.{RiscqFiber, TileLinkCpuMemFiber, MemMapFiber}
import riscq.soc.rf.{TimeMemMap, DoneMemMap}
import riscq.soc.spec.SocSpecMap
import riscq.soc.link.{RfLinkBridge, EventLink, EventFifoSink, ReadoutResultSink, LatestSink, MailboxSink, SinkSpec, RfCmd, HostCmd, HostWindowBridge}

/**
 * The **timing-critical core unit** carved out of [[RiscqRfWithPulseTableFiber]] for the
 * registered-boundary floorplan: a hard SpinalHDL `Component` (Verilog module ⇒ a clean per-instance
 * pblock target) holding the RISC-V core + its real BRAM I/D RAM + the CPU-mapped control block + the
 * posted-link bridge + the readout-result sink — and nothing else. Everything past the posted link (the
 * converter-edge DSP datapath) stays OUT, in the parent.
 *
 * IO boundary — narrow and **registered** on both sides of the posted link:
 *   - `time`                     : shared batch-time broadcast (in);
 *   - `cmd : master Flow(RfCmd)`  : posted RF writes out of the [[RfLinkBridge]] → the DSP datapath;
 *   - `hostCmd : master Stream(HostCmd)` : posted result writes out of the [[HostWindowBridge]] → the
 *     clock-crossing FIFO and the shared host-window funnel (specs/software/22; the crossing itself is
 *     in the parent, outside this hard Component);
 *   - `resultIn : slave Flow(ReadoutResult)` : the readout result back from the DSP → [[ReadoutResultSink]];
 *   - `iLoad`  (a [[MasterBus]] slave-IO, implicit dsp clock) : the program/data image load into the
 *     BRAM's slow port; the parent drives it *across* the host→dsp CDC, so this slave-IO is already in
 *     the dsp domain and the crossing lives outside this hard Component (see
 *     `RiscqRfWithPulseTableFiber.iMemLoad`);
 *   - `dTap`   (a [[MasterBus]] slave-IO, riscqCd; `withTestTap` only) : a second master into the CPU
 *     data-bus decode so a sim can schedule RF writes without a CPU program (the test harness).
 *
 * Because the fabric cannot cross a hard Component boundary, the only crossings are concrete
 * `tilelink.Bus`/`Flow` IO; the host-load + test taps enter as `MasterBus` slave-IO ports. The far side
 * of `cmd`/`resultIn`/`iLoad` is the *only* thing the dummy floorplan top vs the real [[PulseTableSoc]]
 * differ on — `RiscvSoc` itself is byte-for-byte identical (the floorplan-transfer claim).
 *
 * Faithfulness: this reproduces the exact internal wiring [[RiscqRfWithPulseTableFiber]] had before the
 * carve-out; the readout sink still carries `{res, real, imag}` on three read addresses (narrowing it to
 * 32 bits is deferred so `PulseTableSocSim`, which reads real/imag, stays bit-exact).
 */
case class RiscvSoc(
    plugins: Seq[Hostable],
    riscqCd: ClockDomain,
    timeWidth: Int = 32,
    readoutAccWidth: Int = 32,
    memDepth: Int = 1024,
    memWidth: Int = 32,
    memOutReg: Boolean = true,
    useUram: Boolean = true,
    rfAddrWidth: Int = SocSpecMap.putAddrWidth,   // the put window (specs/cross-core/02 §3.2)
    hostWinAddrWidth: Int = 24,   // per-core host window (24 ⇒ 16 MB) at `RiscvSoc.hostWinBase`
    // the up-link's core-local sinks (specs/universal-control/01 §2.4), from the core's EventPlan;
    // the default is the qubit build's single readout-result sink at 0x4200 plus the cross-core inbox
    // registers (board / release / signal mailboxes, specs/cross-core/02 §3.3).
    sinks: Seq[SinkSpec] = RiscvSoc.defaultSinks,
    // test harness: add a second master into the CPU data-bus decode (a sim drives it directly).
    withTestTap: Boolean = false,
    // host-load master params (the BRAM slow-port image load). Must match what the parent's host fabric
    // presents to `iLoad` (source-id width included), so keep it a single shared definition.
    hostLoadM2s: tilelink.M2sParameters = RiscvSoc.defaultHostLoadM2s,
    testTapM2s: tilelink.M2sParameters = RiscvSoc.defaultTestTapM2s
) extends Component {
  val memOffset = 0x80000000L

  // ── IO boundary ──
  val time     = in  port UInt(timeWidth bits)        // shared batch-time broadcast
  val cmd      = master port Flow(RfCmd(rfAddrWidth)) // posted RF writes (RfLinkBridge) → DSP
  val resultIn = slave  port Flow(RfCmd(EventLink.inboxAddrWidth)) // the up-link: puts into the inbox (sinks)
  val hostCmd  = master port Stream(HostCmd(hostWinAddrWidth))    // posted result writes → host window
  val done     = out port Bool()                     // run-completion flag (specs/software/23) → host

  def getPipe[T <: Data](data: T, cycles: Int): T = {
    var res = data
    for (_ <- 0 until cycles) {
      res = RegNext(res)
      res.addAttribute("DONT_TOUCH")
    }
    res
  }

  // ── CPU + its private instruction/data RAM ──
  // RiscvSoc elaborates in its parent's clock domain (the dsp clock) — that implicit `ClockDomain.current`
  // is the single clock for the BRAM, the instruction arbiter and the `iLoad` slave-IO. `riscqCd` (same
  // clock, core reset) is passed in and wraps only the CPU + control fibers below.
  val riscqFiber = riscqCd(RiscqFiber(plugins))
  // CPU instruction/data RAM: block RAM by default, UltraRAM with `useUram`. Both expose the same two
  // true-dual-port read/write ports (port0/port1, here single-clock in the implicit dsp domain).
  // Only DEEP memories (memDepth > 4096) use a HalfUram: it packs two 32-bit words per 64-bit UltraRAM
  // row, so the array costs half the URAM primitives of a 1-word-per-row Uram (32-bit only — hence the
  // memWidth guard). At or below one URAM's 4096-deep primitive a plain Uram already fits in a single
  // URAM, so HalfUram's combinational read-half mux would only add datapath delay for no primitive
  // saving — use a plain Uram there. Both URAM forms share the same read latency (uramPipeNum + 2).
  // Read latency differs and the fibers must be told exactly: Bram = 1 + outReg (2); the URAM template
  // chains memreg + NBPIPE pipes + the dout register, so pipeNum = 1 is 3 — one MORE than Bram+outReg
  // (HalfUram's half-mux is combinational, so it inherits that exact latency — Uram and HalfUram match).
  // (Feeding the fibers latency 2 with the URAM made every read — CPU load, fetch, host readback —
  // return the *previous* read's data; caught by the M0 co-sim contract test + PulseTableSocCpuSim.)
  val uramPipeNum = 1
  val (mem, memPort0, memPort1, memReadLatency) = if (useUram) {
    if (memDepth > 4096) {
      require(memWidth == 32, s"HalfUram-backed CPU RAM is 32-bit only, got memWidth=$memWidth")
      val uram = HalfUram(addressWidth = log2Up(memDepth), pipeNum = uramPipeNum)
      (uram, uram.io.port0, uram.io.port1, uramPipeNum + 2)
    } else {
      val uram = Uram(Bits(memWidth bits), addressWidth = log2Up(memDepth), pipeNum = uramPipeNum)
      (uram, uram.io.port0, uram.io.port1, uramPipeNum + 2)
    }
  } else {
    val bram = Bram(Bits(memWidth bits), depth = memDepth,
      fastCd = ClockDomain.current, slowCd = ClockDomain.current, outReg = memOutReg)
    (bram, bram.io.port0, bram.io.port1, 1 + memOutReg.toInt)
  }
  mem.setName("mem").addAttribute("KEEP_HIERARCHY", "TRUE")

  // The data bus already carries the posted-store adapter inside RiscqFiber (on the DataMemBus, ahead of
  // its Tilelink bridge), so the fabric just decodes the resulting Tilelink master.
  val dMemPortDec = riscqCd(Node())
  dMemPortDec at 0 of riscqFiber.dBus
  val dBusFiber = riscqCd(TileLinkCpuMemFiber(memPort0, latency = memReadLatency))
  dBusFiber.up at memOffset of dMemPortDec

  // optional test tap: a second master into the data-bus decode (the sim drives dTap.node.bus directly).
  val dTap = withTestTap generate riscqCd(new MasterBus(testTapM2s))
  if (withTestTap) dMemPortDec at 0 of dTap.node

  // instruction fetch + host image-load share the BRAM slow port through an arbiter; the host enters on
  // the `iLoad` MasterBus slave-IO. iLoad lives in the implicit (dsp) clock domain — same as the arbiter
  // / BRAM slow port — so there is NO clock crossing inside this hard Component: the parent puts the
  // host→dsp CDC on the fabric arc that drives iLoad (see `RiscqRfWithPulseTableFiber.iMemLoad`),
  // keeping it out of the per-core pblock.
  val iLoad = new MasterBus(hostLoadM2s)
  val iMemPortArb = Node()
  iMemPortArb at memOffset of riscqFiber.iBus
  iMemPortArb at 0 of iLoad.node
  val iBusFiber = TileLinkCpuMemFiber(memPort1, latency = memReadLatency)
  iBusFiber.up at 0 of iMemPortArb

  // ── control block: time (+ the core-local readout-result sink, added after the bridge) ──
  val memMapFiber = riscqCd(MemMapFiber(addressWidth = 22, dataWidth = 32))
  val ctrlTime    = riscqCd(getPipe(time, 1))
  val timeMemMap  = TimeMemMap(ctrlTime); memMapFiber.addMapping(timeMemMap.mapping)
  // Completion flag (specs/software/23): the firmware's last store sets it, the host reads it in the
  // SoC's host control block — so a completion poll never touches the RAM port that instruction fetch
  // shares with the host image-load master. It lives in `riscqCd`, so the per-run reset is its only
  // clear. Two `riscqCd` stages before the port keep the hard-Component IO boundary a short arc; the
  // consumer is a `hostCd` BufferCC across an asynchronous clock group, so nothing here is timed
  // against the 500 MHz path — the level just has to arrive within a poll interval.
  val doneMemMap  = riscqCd(DoneMemMap()); memMapFiber.addMapping(doneMemMap.mapping)
  done := riscqCd(RegNext(RegNext(doneMemMap.done, False), False))

  // ── posted-link bridge + core-local readout-result sink (riscqCd) ──
  val posted = riscqCd { new Composite(this, "posted") {
    val bridge = RfLinkBridge(rfAddrWidth)
    // the put window at 0x10000: node · 0x10000 + offset — nodes 0..15 are this core's own channels
    // (one 0x10000 sub-window each, universal-control/01 §2.2), nodes ≥ 16 are system units the parent
    // routes to the hub (specs/cross-core/02 §3.2). The host window at 0x40000000 bounds it at 29 bits.
    require(rfAddrWidth <= 29, s"rfAddrWidth $rfAddrWidth: the put window would reach the host window")
    bridge.up at SizeMapping(0x10000, BigInt(1) << rfAddrWidth) of dMemPortDec
    bridge.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

    // write-only host window (specs/software/22): results leave the core here instead of piling up in
    // the 16 KB I+D RAM. Unlike the RF bridge this one is a Stream — the far side (a CC FIFO, then DDR)
    // can stall, and the bridge then withholds the AccessAck so the store back-pressures the CPU.
    val hostWindow = HostWindowBridge(hostWinAddrWidth)
    hostWindow.up at SizeMapping(RiscvSoc.hostWinBase, BigInt(1) << hostWinAddrWidth) of dMemPortDec
    hostWindow.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

    // one sink per reporting channel, by kind: the readout result keeps its latched-level contract,
    // edge-like reports get a consume-on-read FIFO with a sequence number and the cause time.
    val sinkAreas = sinks.map { sk =>
      val a: Area = sk.kind match {
        case EventLink.resultKind  => ReadoutResultSink(readoutAccWidth, sk.base)
        case EventLink.fifoKind    => EventFifoSink(sk.dataWidth, sk.base)
        case EventLink.latestKind  => LatestSink(sk.dataWidth / 32, sk.base)
        case EventLink.mailboxKind => MailboxSink(sk.base)
      }
      a.setCompositeName(this, s"${sk.name}Sink")
      a
    }
    sinkAreas.foreach {
      case r: ReadoutResultSink => r.resultIn << resultIn
      case f: EventFifoSink     => f.resultIn << resultIn
      case l: LatestSink        => l.resultIn << resultIn
      case m: MailboxSink       => m.resultIn << resultIn
    }
  } }

  cmd     << posted.bridge.cmd                      // posted RF writes leave for the DSP datapath
  hostCmd << posted.hostWindow.cmd                  // posted result writes leave for the host funnel

  // finish the control block: add the local result-sink read map, then connect the bus.
  posted.sinkAreas.foreach {                          // sink k at 0x4200 + 0x20·k (local reads)
    case r: ReadoutResultSink => memMapFiber.addMapping(r.mapping)
    case f: EventFifoSink     => memMapFiber.addMapping(f.mapping)
    case l: LatestSink        => memMapFiber.addMapping(l.mapping)
    case m: MailboxSink       => memMapFiber.addMapping(m.mapping)
  }
  memMapFiber.up at SizeMapping(0, 1 << 16) of dMemPortDec
  memMapFiber.up.setUpConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
}

object RiscvSoc {
  /** The qubit build's sinks: the demod result sink, then the cross-core inbox registers. */
  def defaultSinks: Seq[SinkSpec] = {
    val demod = SinkSpec("demod", EventLink.resultKind, 0, EventLink.sinkBase, EventLink.resultDataWidth(32))
    Seq(demod,
        SinkSpec("board", EventLink.latestKind, 1, EventLink.boardBase, 32 * SocSpecMap.groupNodes),
        SinkSpec("release", EventLink.mailboxKind, 2, EventLink.releaseBase, 32)) ++
      (0 until EventLink.mailboxNum).map(m => SinkSpec(s"mbox$m", EventLink.mailboxKind, 3 + m, EventLink.mailboxAddr(m), 32))
  }

  /** Base of the per-core write-only host window in the CPU's data address space. One address bit away
   *  from everything else (ctrl `0x0`, RF `0x10000`, I+D RAM `0x80000000`). */
  val hostWinBase = 0x40000000L

  /** Host image-load master (BRAM slow port). Deliberately **generous** (4-bit source, 32-bit address,
   *  256-byte size ⇒ 4-bit size field) so it is a superset of every parent's host fabric (e.g. the
   *  PulseTableSoc AXI window, which negotiates different narrower params);
   *  the parent's `iMemLoad` bridge resizes its narrower fields up into this fixed param, so `RiscvSoc`
   *  stays byte-for-byte identical across tops (the floorplan-transfer requirement). */
  def defaultHostLoadM2s = tilelink.M2sParameters(
    addressWidth = 32, dataWidth = 32,
    masters = List(tilelink.M2sAgent(name = null, mapping = List(tilelink.M2sSource(
      id = SizeMapping(0, 16),
      emits = tilelink.M2sTransfers(
        get = tilelink.SizeRange.upTo(0x100), putFull = tilelink.SizeRange.upTo(0x100),
        putPartial = tilelink.SizeRange.upTo(0x100)))))))

  /** Test-tap master (a sim's MasterAgent drives this, bridged in from the parent like `iLoad`).
   *  Generous param (same shape as the host load) so the parent's test master resizes up into it. */
  def defaultTestTapM2s = defaultHostLoadM2s
}
