package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.Hostable
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.fabric.{Node, MasterBus}
import riscq.dsp.{Complex, ComplexBatch, SinCosMethod}
import riscq.dsp.pulse.{ReadoutDecoder, ReadoutDecoderParams}
import riscq.riscv.RiscqParam
import riscq.soc.fabric.BramWriteFiber
import riscq.soc.rf.{Channel, PulseDriveChannel, DemodChannel}
import riscq.soc.dio.TimedDio
import riscq.soc.link.{PutLink, EventLink, EventPlan, EventSource, Put, HostCmd}
import riscq.soc.spec.SocSpecMap
import riscq.soc.spec.{ChannelSpec, CoreSpec, SocSpecMap}

/**
 * One core — the shell around a hard [[RiscvSoc]] Component (the timing-critical RISC-V core + I/D RAM
 * + control block + posted-link bridge + readout-result sink) plus the converter-edge DSP datapath
 * reached over the **narrow, one-way, posted link**. The datapath is built from the core's
 * [[CoreSpec]] channel list (specs/universal-control/01 §2.3): channel `k` gets sub-window
 * `k · 0x10000` of the put window, its own host-written envelope bank, and its kind's block — a
 * [[PulseDriveChannel]] for `pulse` (DAC-bound) or a [[DemodChannel]] + [[ReadoutDecoder]] for `demod`
 * (ADC-bound; its result rides the up-link into the core-local sink). The qubit builds list
 * `gate / ro / demod`, which reproduces the previous fixed three-channel shell exactly.
 *
 * The split is at the already-registered seam (the posted link): [[RiscvSoc]] exposes `cmd`
 * (posted writes, from the `PutBridge`) and `resultIn` (the readout result, into the
 * `ReadoutResultSink`); this shell applies the `linkPipe` RegNext stages each way and demuxes `cmd` to
 * the channels. Everything past `getPipe(riscvSoc.cmd, linkPipe)` — the demux, the channels, the
 * decoder, the envelope BRAMs, dac/adc — lives here (the parent), not in [[RiscvSoc]], so the core can
 * be floorplanned far from the converters.
 *
 * CPU data-bus decode and the `0x10000` put window / `0x80000000` data-RAM maps are all inside
 * [[RiscvSoc]] now; the host program/data image load enters [[RiscvSoc]] over its (dspCd) `iLoad`
 * slave-IO, re-exposed here as the `iMemPortArb` fabric node — the node that now carries the host→dsp
 * clock crossing (moved out of [[RiscvSoc]]), so the toplevel wiring is otherwise unchanged.
 *
 * Differences from the RISC-Q reference: the pulse-envelope RAM is **512-bit** complex; the DAC carries
 * only the real lane; the readout result returns on the posted up-link. `linkPipe` is the per-direction
 * `RegNext` depth.
 */
case class RiscqRfWithPulseTableFiber(
    spec: CoreSpec,
    plugins: Seq[Hostable],
    dspCd: ClockDomain,
    hostCd: ClockDomain,
    riscqCd: ClockDomain,
    time: UInt,
    batchSize: Int = 16,
    dataWidth: Int = 16,
    timeWidth: Int = 32,
    durWidth: Int = 16,
    adcBatch: Int = 4,
    readoutMaxWinLog2: Int = 14,  // decoder no-overflow bound: longest demod window ≤ 2^this batches
    readoutAccWidth: Int = 32,
    prescaleAmp: Boolean = true,
    saturate: Boolean = false,
    phasorMethod: SinCosMethod = SinCosMethod.Cordic,
    memWidth: Int = 32,
    memOutReg: Boolean = true,
    linkPipe: Int = 4,
    hostWinAddrWidth: Int = 24,   // per-core host window (24 ⇒ 16 MB)
    hostWinFifoDepth: Int = 16,   // CC FIFO depth: a shot tail is 2-3 words, so every core can finish
                                  // a shot at once without stalling
    withTestTap: Boolean = false,
    hubQueueDepth: Int = 16       // ≥ the cores on the board: a same-cycle burst of hub beats never drops
) extends Area {
  val w        = dataWidth
  val memDepth = spec.memDepth
  val memLatency = 1 + memOutReg.toInt      // envelope RAM read latency (sync read + out reg)
  // The put window: channel k owns sub-window k · 0x10000 (PutLink.demux below), nodes ≥ 16 are system
  // puts (PutLink.nonLocal). The width is the whole put window, checked against the channel count.
  val putAddrWidth = SocSpecMap.putAddrWidth(spec)
  val demods = spec.channels.filter(_.kind == "demod")
  require(demods.length == 1, s"core '${spec.name}': exactly one demod channel per core (one decoder + ADC)")
  // the up-link layout for this channel list: which channels report, sink kinds and bases
  val eventPlan = EventPlan(spec, readoutAccWidth)

  // ════════════════════════ the timing-critical core unit (hard Component) ═════════════════════════
  val riscvSoc = RiscvSoc(
    plugins = plugins, riscqCd = riscqCd,
    timeWidth = timeWidth, readoutAccWidth = readoutAccWidth, memDepth = memDepth, memWidth = memWidth,
    memOutReg = memOutReg, putAddrWidth = putAddrWidth, hostWinAddrWidth = hostWinAddrWidth,
    sinks = eventPlan.allSinks, withTestTap = withTestTap)

  /** The hub's puts for this core (already gated by its mask bit and piped by the parent), merged onto
    * the up-link with the channels' reports. The SoC top drives it. */
  val hubIn = Flow(Put(EventLink.inboxAddrWidth))
  riscvSoc.time     := time

  // re-expose the host program/data image-load entry as a fabric node (the toplevel connects host
  // masters / the AXI fabric here, exactly as before); a leaf slave node bridges it to RiscvSoc's
  // concrete `iLoad` slave-IO (the fabric can't cross the hard Component boundary — see RiscvSoc). The
  // narrower negotiated fields (source/address/size differ per top) are resized up into RiscvSoc's
  // generous fixed `iLoad` param so the hard Component is byte-identical across tops.
  //
  // This bridge node is in `dspCd` (matching RiscvSoc's now-dspCd `iLoad`), so the combinational
  // `bridgeLoad` stays single-domain and the host→dsp clock crossing lands on the FABRIC arc the
  // toplevel builds INTO `iMemPortArb` (its host master in hostCd → this dspCd up-node) — i.e. OUTSIDE
  // the hard RiscvSoc Component / its pblock, instead of on the old iLoad→arb arc inside it.
  val iMemLoad = dspCd { new Area {
    val up = Node.up()
    val thread = spinal.core.fiber.Fiber build new Area {
      up.m2s.supported load up.m2s.proposed.intersect(tilelink.M2sTransfers(
        get = tilelink.SizeRange.upTo(0x100), putFull = tilelink.SizeRange.upTo(0x100),
        putPartial = tilelink.SizeRange.upTo(0x100)))
      up.s2m.none()
      RiscqRfWithPulseTableFiber.bridgeLoad(riscvSoc.iLoad.node.bus, up.bus)
    }
  } }
  val iMemPortArb = iMemLoad.up

  // ── host window: the core's posted result stream crosses riscq → host HERE, outside the hard
  // RiscvSoc Component (and outside the core's pblock), exactly where `iMemLoad` puts the host→dsp
  // image-load CDC. The pop side is `hostCd`, where the shared `HostWindowFunnel` lives.
  //
  // The push side is `dspCd`, NOT `riscqCd`: `riscqCd` is the same clock with the per-run core reset,
  // and a cross-clock FIFO whose push pointer is zeroed per run while its pop pointer is not would
  // desync (the pop side would see a huge fake occupancy). Same clock ⇒ the bridge's riscqCd-driven
  // Stream feeds it directly, and a reset mid-run simply drops `valid` — beats are atomic, so nothing
  // is torn; whatever was already accepted drains to the same addresses (the base is allocated once per
  // session), which is idempotent.
  val hostWinFifo = StreamFifoCC(HostCmd(hostWinAddrWidth), hostWinFifoDepth, dspCd, hostCd)
  hostWinFifo.io.push << riscvSoc.hostCmd
  val hostCmd = hostWinFifo.io.pop

  // run-completion flag (specs/software/23): a plain level out of the core, crossed to `hostCd` by the
  // toplevel's BufferCC alongside the other host-control registers. Nothing to buffer — it is sticky
  // until `riscqReset` clears it.
  val done = riscvSoc.done

  // re-expose the CPU data-bus decode as a tap node for the test harness (withTestTap only): the
  // toplevel's test master connects here and the requests bridge into RiscvSoc's `dTap` slave-IO,
  // mirroring the iMemLoad bridge. Null when no test tap (the real SoC: the CPU is the sole master).
  val dMemTap = withTestTap generate riscqCd { new Area {
    val up = Node.up()
    val thread = spinal.core.fiber.Fiber build new Area {
      up.m2s.supported load up.m2s.proposed.intersect(tilelink.M2sTransfers(
        get = tilelink.SizeRange.upTo(0x100), putFull = tilelink.SizeRange.upTo(0x100),
        putPartial = tilelink.SizeRange.upTo(0x100)))
      up.s2m.none()
      RiscqRfWithPulseTableFiber.bridgeLoad(riscvSoc.dTap.node.bus, up.bus)
    }
  } }
  val dMemPortDec = if (withTestTap) dMemTap.up else null   // the test-master connection point

  // ── host-loaded complex envelope memory, one write-only bank per channel (read by the channel) ──
  // Write-only banks: the host only ever loads these, so each uses BramWriteFiber (a tiny combinational
  // write slave + narrow-host-bus→wide-line sub-word steering) instead of the read/write BramFiber.
  // The bank stores the INTERPOLATED line (`envWidth`), shrinking the widest BRAM banks; `expandEnv`
  // below reconstructs the full lane batch on read. Named `<channel>MemFiber`.
  // (a kind without a bank — dio — has envDepth 0 and no fiber; its host-map slot stays a hole)
  val envMems: Seq[Option[BramWriteFiber]] = spec.channels.map { ch =>
    if (ch.envDepth == 0) None else {
      val f = hostCd(BramWriteFiber(1, ch.envWidth, ch.envDepth, hostCd, dspCd, withOutReg = memOutReg))
      f.setCompositeName(this, s"${ch.name}MemFiber")
      Some(f)
    }
  }

  // ════════════════════════ converter-edge DSP datapath, reached over the posted link ══════════════
  def getPipe[T <: Data](data: T, cycles: Int): T = {
    var res = data
    for (_ <- 0 until cycles) {
      res = RegNext(res)
      res.addAttribute("DONT_TOUCH")
    }
    res
  }

  val posted = dspCd { new Composite(this, "posted") {
    // One channel per spec entry off its demuxed sub-window of the (piped) posted command stream — each
    // channel pipes the stream itself (per-channel `linkPipe` copies keep the pipe fanout at one).
    // A pulse table of ≥ 2 slots lands in distributed RAM (PulseParamBuffer.useMem); a 1-slot channel
    // has no addressable table and stays a register file automatically.
    def mkChannel(ch: ChannelSpec, k: Int): Channel = {
      val envAddrWidth = log2Up(ch.envDepth)   // the bank's own address width — the table `env` field,
                                               // the memPort and the RAM address port all key off it
      val c: Channel = ch.kind match {
        case "pulse" =>
          PulseDriveChannel(pulseNum = ch.slots, batchSize = batchSize, dataWidth = w,
            envAddrWidth = envAddrWidth, durWidth = durWidth, timeWidth = timeWidth, memLatency = memLatency,
            prescaleAmp = prescaleAmp, saturate = saturate, phasorMethod = phasorMethod, realOutput = true,
            queueDepth = spec.queueDepth)
        case "dio" =>
          TimedDio(slots = ch.slots, timeWidth = timeWidth, durWidth = durWidth, queueDepth = spec.queueDepth)
        case "demod" =>
          // the demod carrier: a scheduled, envelope-shaped complex pulse (a PulseDriveChannel pointed at
          // the decoder). Its posted RF sub-window carries the same fire/freq/table/startTime map as a
          // drive channel; software programs a matched-filter envelope once and fires the demod aligned
          // with the readout window. adcBatch lanes (the ADC batch), not batchSize.
          DemodChannel(pulseNum = ch.slots, batchSize = adcBatch, dataWidth = w,
            envAddrWidth = envAddrWidth, durWidth = durWidth, timeWidth = timeWidth, memLatency = memLatency,
            prescaleAmp = prescaleAmp, saturate = saturate, phasorMethod = phasorMethod, queueDepth = spec.queueDepth)
      }
      c.setCompositeName(this, s"${ch.name}Channel")
      c.cmd << PutLink.demux(getPipe(riscvSoc.cmd, linkPipe), k * SocSpecMap.rfChStride, SocSpecMap.rfChStride, 16)
      c.timeBcast := time
      c
    }
    val channels: Seq[Channel] = spec.channels.zipWithIndex.map { case (ch, k) => mkChannel(ch, k) }
    val demodChannel = channels(spec.channels.indexWhere(_.kind == "demod"))

    // readout decoder: CARRIER-TRIGGERED — the demod carrier's Flow valid delimits the integration
    // window (rising edge restarts the accumulator, falling edge latches the result), so there is no
    // decoder RF sub-window, no startTime/dur/time inputs, and no arm. One `play(demod)` IS the readout.
    // The carrier hop keeps one register stage (now carrying valid+payload, edges preserved by `.stage`).
    val decoder = ReadoutDecoder(ReadoutDecoderParams(
      batchSize = adcBatch, dataWidth = w, accWidth = readoutAccWidth,
      maxWinLog2 = readoutMaxWinLog2, saturate = saturate))
    decoder.io.carrier << demodChannel.carrier.get.stage()

    // the up-link: every reporting channel's source, in the plan's order (the demod reports through the
    // decoder here — it is the one kind whose source needs the ADC), serialised into puts at its sink's
    // offsets, merged onto one Flow(Put) and piped `linkPipe` stages into RiscvSoc's inbox.
    val sources: Seq[EventSource] = eventPlan.reporters.map { case (k, _, _, _) =>
      spec.channels(k).kind match {
        case "demod" => EventLink.resultSource(decoder.io.res.valid, decoder.io.res.payload, decoder.io.real, decoder.io.imag, readoutAccWidth)
        case _       => channels(k).event.get
      }
    }
    val hubQ  = hubIn.toStream.queue(hubQueueDepth)   // a burst of hub beats waits here for the arbiter
    val upSrc = EventLink.merge(sources, eventPlan.sinks, extra = Seq(hubQ))
    riscvSoc.resultIn << getPipe(upSrc, linkPipe)

    /** This core's system puts (node ≥ localNodes) for the board hub, on their own piped copy. */
    val xput = PutLink.nonLocal(getPipe(riscvSoc.cmd, linkPipe), SocSpecMap.localNodes)
  } }

  // ── datapath handles exported to the rest of the SoC (by channel name / kind, never by position) ──
  def channel(name: String): Channel = posted.channels(spec.index(name))
  def xput = posted.xput
  /** DAC-bound channels' pulses, in list order — the index the SoC's `(core, k) → dac` map uses. */
  val dacPulses: Seq[Flow[Vec[Complex]]] = posted.channels.flatMap(_.dacOut)
  /** the pulses whose fires trigger the shared readout trace (`trace: true` channels) */
  val tracePulses: Seq[Flow[Vec[Complex]]] =
    spec.channels.zip(posted.channels).collect { case (s, c) if s.trace => c.dacOut.get }
  val decoderRd      = posted.decoder
  // qubit-build conveniences (the sims observe them); absent channels raise at elaboration
  def gatePulse      = channel("gate").dacOut.get
  def readoutPulse   = channel("ro").dacOut.get
  def startTime      = channel("gate").asInstanceOf[PulseDriveChannel].startTime  // gate buffer's per-buffer startTime

  // ── envelope-memory read ports (reconstruct the full `lanes`-lane batch from the interpolated line) ──
  def expandEnv(data: Bits, interp: Int, lanes: Int): Bits =
    if (interp == 1) data
    else {
      val sampleBits = 2 * w
      Vec.tabulate(lanes)(m => data((m / interp) * sampleBits, sampleBits bits)).asBits
    }
  def wireEnv(ram: riscq.memory.Bram[Bits], memPort: MemReadPort[Bits], interp: Int, lanes: Int): Unit = {
    val p = ram.fastPort
    p.enable := True; p.write := False; p.mask.setAllTo(False); p.wdata.setAllTo(False)
    p.address := memPort.cmd.payload
    memPort.rsp := expandEnv(p.rdata, interp, lanes)
  }
  for (((ch, c), Some(mem)) <- spec.channels.zip(posted.channels).zip(envMems)) {
    require(c.envLanes % ch.interp == 0, s"${spec.name}/${ch.name}: interp ${ch.interp} must divide ${c.envLanes} lanes")
    wireEnv(mem.rams(0), c.memPort.get, ch.interp, c.envLanes)
  }
  /** the timed-DIO channels, by spec, for the toplevel's board ports */
  val dios: Seq[(ChannelSpec, TimedDio)] =
    spec.channels.zip(posted.channels).collect { case (ch, d: TimedDio) => (ch, d) }

  // ── DAC (real lane only) + ADC ──
  val dac = dacPulses.map(_ => ComplexBatch(batchSize, w)).toList
  for ((d, p) <- dac.zip(dacPulses)) d := p.payload
  val adc = ComplexBatch(adcBatch, w)
  decoderRd.io.adc := adc
}

/** Companion utilities for [[RiscqRfWithPulseTableFiber]]. */
object RiscqRfWithPulseTableFiber {
  /** Bridge a (narrower) host tilelink master `up` onto RiscvSoc's generous fixed-param `iLoad` slave
   *  bus `dn`, resizing the per-top-varying fields (source/address/size) — `dn`'s widths are a superset
   *  of `up`'s, so the A-channel resizes up and the D-channel response resizes back down (the upper
   *  source/size bits round-trip as zero). Both are simple put/get buses (data, no BCE). */
  def bridgeLoad(dn: spinal.lib.bus.tilelink.Bus, up: spinal.lib.bus.tilelink.Bus): Unit = {
    // A channel: master `up` → slave `dn`
    dn.a.valid   := up.a.valid
    up.a.ready   := dn.a.ready
    dn.a.opcode  := up.a.opcode
    dn.a.param   := up.a.param
    dn.a.source  := up.a.source.resized
    dn.a.address := up.a.address.resized
    dn.a.size    := up.a.size.resized
    dn.a.mask    := up.a.mask
    dn.a.data    := up.a.data
    dn.a.corrupt := up.a.corrupt
    dn.a.debugId := up.a.debugId
    // D channel: slave `dn` → master `up`
    up.d.valid   := dn.d.valid
    dn.d.ready   := up.d.ready
    up.d.opcode  := dn.d.opcode
    up.d.param   := dn.d.param
    up.d.source  := dn.d.source.resized
    up.d.size    := dn.d.size.resized
    up.d.denied  := dn.d.denied
    up.d.data    := dn.d.data
    up.d.corrupt := dn.d.corrupt
    if (up.p.sinkWidth > 0) up.d.sink := dn.d.sink.resized
  }
}
