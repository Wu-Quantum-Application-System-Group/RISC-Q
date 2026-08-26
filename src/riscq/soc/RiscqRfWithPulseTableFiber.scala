package riscq.soc

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.plugin.Hostable
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink.fabric.{Node, MasterBus}
import riscq.dsp.{ComplexBatch, SinCosMethod}
import riscq.dsp.pulse.{ReadoutDecoder, ReadoutDecoderParams}
import riscq.riscv.RiscqParam
import riscq.soc.fabric.BramWriteFiber
import riscq.soc.rf.{PulseDriveChannel, DemodChannel}
import riscq.soc.link.{RfLink, ReadoutResultLink, RfCmd}

/**
 * One qubit core — the agentic counterpart of the RISC-Q reference `RiscqRfWithPulseTableFiber`. After
 * the registered-boundary floorplan carve-out this is a **thin shell**: a hard [[RiscvSoc]] Component
 * (the timing-critical RISC-V core + I/D RAM + control block + posted-link bridge + readout-result sink)
 * plus the converter-edge DSP datapath reached over the **narrow, one-way, posted link** — two pulse
 * drives, a demod LO and a readout decoder — so the core can be floorplanned far from the converters.
 *
 * The split is at the already-registered seam (the posted link): [[RiscvSoc]] exposes `cmd`
 * (posted RF writes, from the `RfLinkBridge`) and `resultIn` (the readout result, into the
 * `ReadoutResultSink`); this shell applies the `linkPipe` RegNext stages each way and demuxes `cmd` to
 * the per-generator channels. Everything past `getPipe(riscvSoc.cmd, linkPipe)` — the demux, the
 * channels, the decoder, the envelope BRAMs, dac/adc — lives here (the parent), not in [[RiscvSoc]].
 *
 * CPU data-bus decode and the `0x10000` RF window / `0x80000000` data-RAM maps are all inside
 * [[RiscvSoc]] now; the host program/data image load enters [[RiscvSoc]] over its (dspCd) `iLoad`
 * slave-IO, re-exposed here as the `iMemPortArb` fabric node — the node that now carries the host→dsp
 * clock crossing (moved out of [[RiscvSoc]]), so the toplevel wiring is otherwise unchanged.
 *
 * Differences from the reference: the pulse-envelope RAM is **512-bit** complex; the DAC carries only
 * the real lane; the readout result returns on the posted up-link. `linkPipe` is the per-direction
 * `RegNext` depth.
 */
case class RiscqRfWithPulseTableFiber(
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
    gatePulseNum: Int = 8,
    readoutMaxWinLog2: Int = 14,  // decoder no-overflow bound: longest demod window ≤ 2^this batches
    readoutAccWidth: Int = 32,
    prescaleAmp: Boolean = true,
    saturate: Boolean = false,
    phasorMethod: SinCosMethod = SinCosMethod.Cordic,
    memDepth: Int = 1024,
    memWidth: Int = 32,
    memOutReg: Boolean = true,
    envDepth: Int = 1024,
    readoutInterp: Int = 16,
    gateInterp: Int = 4,
    demodInterp: Int = 4,
    demodPulseNum: Int = 1,
    linkPipe: Int = 4,
    queueDepth: Int = 4,          // per-parameter TimedQueue depth in every drive/demod PulseGenerator
    withTestTap: Boolean = false
) extends Area {
  val w        = dataWidth
  // envelope read-port address width = the envelope RAM's own address width (log2Up(envDepth)). Derived,
  // not a knob: the pulse-table `env` field, the channel `memPort` address, and the RAM address port all
  // key off this, so an independent value would silently under-address a deeper bank (env_depth > 1024).
  val envAddrWidth = log2Up(envDepth)
  val envWidth = batchSize * 2 * w          // complex envelope line (512 for N=16, w=16)
  val memLatency = 1 + memOutReg.toInt      // envelope RAM read latency (sync read + out reg)
  // 0x40000-byte RF window: gate 0x00000, readout drive 0x10000, demod 0x20000. The old decoder
  // sub-window 0x30000 is unmapped/reserved (the carrier-triggered decoder needs no CPU register), so
  // every existing channel address is unchanged and rfAddrWidth stays 18.
  val rfAddrWidth = 18

  // ════════════════════════ the timing-critical core unit (hard Component) ═════════════════════════
  val riscvSoc = RiscvSoc(
    plugins = plugins, riscqCd = riscqCd,
    timeWidth = timeWidth, readoutAccWidth = readoutAccWidth, memDepth = memDepth, memWidth = memWidth,
    memOutReg = memOutReg, rfAddrWidth = rfAddrWidth, withTestTap = withTestTap)
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

  // ── host-loaded complex pulse-envelope memory (write-only host side; read by the channels below) ──
  // Write-only banks: the host only ever loads these, so each uses BramWriteFiber (a tiny combinational
  // write slave + narrow-host-bus→wide-line sub-word steering) instead of the read/write BramFiber.
  require(batchSize % readoutInterp == 0, "readoutInterp must divide the batch size")
  require(batchSize % gateInterp == 0, "gateInterp must divide the batch size")
  require(adcBatch % demodInterp == 0, "demodInterp must divide the ADC batch size")
  val demodEnvFull    = adcBatch * 2 * w    // full demod-carrier envelope line (128 for adcBatch=4, w=16)
  val readoutEnvWidth = envWidth / readoutInterp
  val gateEnvWidth    = envWidth / gateInterp
  val demodEnvWidth   = demodEnvFull / demodInterp
  val pulseMemFiber   = hostCd(BramWriteFiber(1, gateEnvWidth,    envDepth, hostCd, dspCd, withOutReg = memOutReg))
  val readoutMemFiber = hostCd(BramWriteFiber(1, readoutEnvWidth, envDepth, hostCd, dspCd, withOutReg = memOutReg))
  val demodMemFiber   = hostCd(BramWriteFiber(1, demodEnvWidth,   envDepth, hostCd, dspCd, withOutReg = memOutReg))

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
    // one CPU-writable drive channel off a demuxed sub-window of the (piped) posted command stream.
    def mkDriveChannel(pulseNum: Int, base: BigInt, cmdDn: Flow[RfCmd]) = {
      val ch = PulseDriveChannel(pulseNum = pulseNum, batchSize = batchSize, dataWidth = w,
        envAddrWidth = envAddrWidth, durWidth = durWidth, timeWidth = timeWidth, memLatency = memLatency,
        prescaleAmp = prescaleAmp, saturate = saturate, phasorMethod = phasorMethod, realOutput = true,
        queueDepth = queueDepth)
      ch.io.cmd << RfLink.demux(cmdDn, base, 0x10000, 16)
      ch.io.timeBcast := time
      ch
    }
    // The gate table lands in distributed RAM (PulseParamBuffer.useMem defaults on for pulseNum ≥ 2);
    // the ro channel (pulseNum = 1) has no addressable table so it stays a register file automatically.
    val gateChannel = mkDriveChannel(gatePulseNum, 0x0,     getPipe(riscvSoc.cmd, linkPipe))
    val roChannel   = mkDriveChannel(1,            0x10000, getPipe(riscvSoc.cmd, linkPipe))

    // demod carrier: a scheduled, envelope-shaped complex pulse (a PulseDriveChannel pointed at the
    // decoder). Its posted RF sub-window carries the same fire/freq/table/startTime map as a drive
    // channel; software programs a matched-filter envelope once and fires the demod aligned with the
    // readout window. adcBatch lanes (the ADC batch), not batchSize.
    val demodChannel = DemodChannel(pulseNum = demodPulseNum, batchSize = adcBatch, dataWidth = w,
      envAddrWidth = envAddrWidth, durWidth = durWidth, timeWidth = timeWidth, memLatency = memLatency,
      prescaleAmp = prescaleAmp, saturate = saturate, phasorMethod = phasorMethod, queueDepth = queueDepth)
    demodChannel.io.cmd << RfLink.demux(getPipe(riscvSoc.cmd, linkPipe), 0x20000, 0x10000, 16)
    demodChannel.io.timeBcast := time

    // readout decoder: CARRIER-TRIGGERED — the demod carrier's Flow valid delimits the integration
    // window (rising edge restarts the accumulator, falling edge latches the result), so there is no
    // decoder RF sub-window, no startTime/dur/time inputs, and no arm. One `play(demod)` IS the readout.
    // The carrier hop keeps one register stage (now carrying valid+payload, edges preserved by `.stage`).
    val decoder = ReadoutDecoder(ReadoutDecoderParams(
      batchSize = adcBatch, dataWidth = w, accWidth = readoutAccWidth,
      maxWinLog2 = readoutMaxWinLog2, saturate = saturate))
    decoder.io.carrier << demodChannel.io.carrier.stage()

    // result up-link → the core-local sink inside RiscvSoc: res.valid is forwarded as a LEVEL (the
    // decoder holds it high from settle until the next window's start), so the sink mirrors it.
    val upSrc = ReadoutResultLink.source(decoder.io.res.valid, decoder.io.res.payload, decoder.io.real, decoder.io.imag, readoutAccWidth)
    riscvSoc.resultIn << getPipe(upSrc, linkPipe)
  } }

  // ── datapath handles exported to the rest of the qubit core / SoC ──
  val gatePulse      = posted.gateChannel.io.pulse
  val readoutPulse   = posted.roChannel.io.pulse
  val gateMemPort    = posted.gateChannel.io.memPort
  val readoutMemPort = posted.roChannel.io.memPort
  val demodMemPort   = posted.demodChannel.io.memPort
  val decoderRd      = posted.decoder
  val startTime      = posted.gateChannel.startTime    // gate buffer's per-buffer startTime (sims observe it)

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
  wireEnv(pulseMemFiber.rams(0),   gateMemPort,    gateInterp,    batchSize)
  wireEnv(readoutMemFiber.rams(0), readoutMemPort, readoutInterp, batchSize)
  wireEnv(demodMemFiber.rams(0),   demodMemPort,   demodInterp,   adcBatch)

  // ── DAC (real lane only) + ADC ──
  val dac = List.fill(2)(ComplexBatch(batchSize, w))
  dac(0) := gatePulse.payload
  dac(1) := readoutPulse.payload
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
