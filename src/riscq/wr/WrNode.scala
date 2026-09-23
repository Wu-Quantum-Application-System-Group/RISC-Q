package riscq.wr

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.misc.SingleMapping
import spinal.lib.bus.tilelink
import riscq.soc.fabric.MemMapDriverFiber
import riscq.wr.gty.WrPhyIo
import riscq.wr.pcs.{WrRxPcs, WrTxPcs}
import riscq.soc.link.PutLane
import riscq.wr.time.{RefTimeTsu, SyncMarker}

/**
 * The White Rabbit node (spec 06): PCS + timestamping + marker behind one memory-mapped register
 * window on the SoC's host path — the `hostCtrlDriver` idiom. All sequencing is host software
 * (spec 07); the node is pure dataplane + registers.
 *
 * Construction mirrors `BramFiber`: instantiate in the **host clock domain**, pass the other
 * domains explicitly. `phy` is the [[WrPhyIo]] contract — driven by `WrGtyPhy` on hardware (W4)
 * or by the `GtySimPhy` model in sims; the node builds the `clk_ref`/`clk_rx` domains from its
 * clock pins (reset-less: the PCS is self-recovering via the sync monitor, registers carry BOOT
 * init values).
 *
 * `refTime`/`syncTime` are dspClk inputs from `riscqArea` — the node never owns or modifies
 * them; `timeOffset` stays in the host control block (spec 04 §3).
 *
 * Register map (32-bit regs at `up`, offsets per spec 06 §2):
 * {{{
 * 0x000 CTRL    w: [0] resetAll  [1] resetRxDatapath  [2] calMode  [3] role (scratch)
 *               [4] pmaLoopback (near-end PMA loopback for the W6 self-test, spec 01 §9 —
 *                   wired to the GTY LOOPBACK port by the SoC's vivado build; no-op in sims)
 * 0x004 STATUS  r: [0] ready [1] aligned [2] synced [15:8] diceCount
 * 0x010 TXTS_LO r: ts[31:0], latches ts[63:32]   0x014 TXTS_HI r: the latch
 * 0x018 TXTS_CTRL r: [0] valid [1] overrun [7:4] seq ; w: ack
 * 0x020/0x024/0x028: RXTS, idem
 * 0x030/0x034 MARKER_LO/HI w: markerTime   0x038 MARKER_CTRL w: [7:0] width + arm strobe,
 *                                                           r: [0] armed [1] missed
 * 0x03c MARKER_PERIOD w: re-arm period (0 = one-shot)
 * 0x040 TXF_DATA w: [7:0] byte [8] last    0x044 TXF_STAT r: [6:0] push-side occupancy
 * 0x050 RXF_DATA r: [7:0] byte [8] last [16] valid (pop-on-read)   0x054 RXF_STAT r: occupancy
 * 0x060..0x070 r: codeErr, dispErr, crcErr, dropped, syncLoss (torn: read twice)
 * }}}
 *
 * CDC classes (spec 06 §1): config/status = `BufferCC` levels (counters torn — software reads
 * twice); timestamps = quasi-static-while-`valid` capture + `BufferCC`'d valid/seq, 64-bit
 * lo-read-latches-hi; strobes (ack, marker arm) = toggle-CC; messages = `StreamFifoCC`.
 */
case class WrNode(
    phy: WrPhyIo,
    refTime: UInt,
    syncTime: UInt,
    hostCd: ClockDomain,
    dspCd: ClockDomain
) extends Area {
  val bootCfg = ClockDomainConfig(resetKind = BOOT)
  val refCd   = ClockDomain(phy.clkRef, config = bootCfg, frequency = FixedFrequency(62.5 MHz))
  val rxCd    = ClockDomain(phy.clkRx, config = bootCfg, frequency = FixedFrequency(62.5 MHz))

  // ── host-domain control state ──────────────────────────────────────────────────────────────
  val ctrl = hostCd { new Area {
    val resetAll        = Reg(Bool()) init True // boards come up held in reset, like riscqReset
    val resetRxDatapath = Reg(Bool()) init False
    val calMode         = Reg(Bool()) init False
    val role            = Reg(Bool()) init False // software scratch: which side is master
    val pmaLoopback     = Reg(Bool()) init False // W6 self-test; consumed by the SoC's real-phy build
    phy.resetAll := resetAll
    phy.resetRxDatapath := resetRxDatapath
  }}

  /** host-domain single-cycle strobe → single-cycle pulse in `cd` (toggle CC). */
  def pulseTo(cd: ClockDomain)(strobe: Bool): Bool = {
    val toggle = hostCd { val t = Reg(Bool()) init False; when(strobe) { t := !t }; t }
    cd {
      val s    = BufferCC(toggle, init = False)
      val last = RegNext(s) init False
      s =/= last
    }
  }

  // ── the lane: the PCS pair, shared by the host's frames (markers) and the hub's put frames
  //    (specs/cross-core/02 D5). The host side stays a byte FIFO each way; the put side is the
  //    dspCd `putTx` / `putRx` the SoC's hub drives. ──
  val lane = PutLane(refCd, rxCd, dspCd)
  val putTx = lane.putTx
  val putRx = lane.putRx
  val txFifo = new StreamFifoCC(Fragment(Bits(8 bits)), depth = 64, pushClock = hostCd,
    popClock = refCd, withPopBufferedReset = false) // refCd is reset-less (BOOT init)
  lane.hostTx << txFifo.io.pop
  val txArea = refCd { new Area {
    val pcs = lane.tx.pcs
    pcs.io.calMode := BufferCC(ctrl.calMode, init = False)
    phy.txDataRaw := pcs.io.txDataRaw
  }}

  val rxFifo = new StreamFifoCC(Fragment(Bits(8 bits)), depth = 64, pushClock = rxCd,
    popClock = hostCd, withPopBufferedReset = false)
  val rxArea = rxCd { new Area {
    val pcs = lane.rx.pcs
    pcs.io.rxDataRaw := phy.rxDataRaw
    rxFifo.io.push << lane.hostRx
  }}

  // ── dspClk: timestamping + marker ─────────────────────────────────────────────────────────
  val dspArea = dspCd { new Area {
    val txTsu, rxTsu = RefTimeTsu()
    txTsu.io.trigger := txArea.pcs.io.txTrigger // cross-domain: the TSU synchronizes internally
    rxTsu.io.trigger := rxArea.pcs.io.rxTrigger
    for (t <- Seq(txTsu, rxTsu)) t.io.refTime := refTime

    val marker = SyncMarker()
    marker.io.syncTime := syncTime
  }}
  val marker = dspArea.marker.io.marker

  // ── host-domain mirrors of the cross-domain state ─────────────────────────────────────────
  val mirror = hostCd { new Area {
    val ready     = BufferCC(phy.ready, init = False)
    val aligned   = BufferCC(phy.aligned, init = False)
    val synced    = BufferCC(rxArea.pcs.io.synced, init = False)
    val diceCount = BufferCC(phy.diceCount) // quasi-static once aligned
    val counters = Seq( // torn multi-bit CDC: software reads twice (spec 06 §1)
      rxArea.pcs.io.codeErrCnt, rxArea.pcs.io.dispErrCnt, rxArea.pcs.io.crcErrCnt,
      rxArea.pcs.io.droppedCnt, rxArea.pcs.io.syncLossCnt).map(c => BufferCC(c))

    // TSU state: ts/seq are stable while valid (the one-outstanding-exchange contract),
    // so a plain BufferCC of the whole word is coherent whenever valid is seen high.
    def tsuMirror(tsu: RefTimeTsu) = new Area {
      val valid   = BufferCC(tsu.io.valid, init = False)
      val overrun = BufferCC(tsu.io.overrun, init = False)
      val seq     = BufferCC(tsu.io.seq)
      val ts      = BufferCC(tsu.io.ts)
      val hiLatch = Reg(Bits(32 bits)) init 0
    }
    val txTs = tsuMirror(dspArea.txTsu)
    val rxTs = tsuMirror(dspArea.rxTsu)

    val markerTime  = Reg(UInt(64 bits)) init 0
    val markerWidth = Reg(UInt(8 bits)) init 100
    val markerPeriod = Reg(UInt(32 bits)) init 0
    val markerArmed  = BufferCC(dspArea.marker.io.armed, init = False)
    val markerMissed = BufferCC(dspArea.marker.io.missed, init = False)
  }}

  dspArea.marker.io.markerTime := dspCd(BufferCC(mirror.markerTime))
  dspArea.marker.io.width := dspCd(BufferCC(mirror.markerWidth))
  dspArea.marker.io.period := dspCd(BufferCC(mirror.markerPeriod))

  // ── the register file ─────────────────────────────────────────────────────────────────────
  val regs = hostCd(MemMapDriverFiber(addressWidth = 12, dataWidth = 32, driveProc = { factory =>
    factory.readAndWrite(ctrl.resetAll, 0x000, bitOffset = 0)
    factory.readAndWrite(ctrl.resetRxDatapath, 0x000, bitOffset = 1)
    factory.readAndWrite(ctrl.calMode, 0x000, bitOffset = 2)
    factory.readAndWrite(ctrl.role, 0x000, bitOffset = 3)
    factory.readAndWrite(ctrl.pmaLoopback, 0x000, bitOffset = 4)

    factory.read(mirror.ready, 0x004, bitOffset = 0)
    factory.read(mirror.aligned, 0x004, bitOffset = 1)
    factory.read(mirror.synced, 0x004, bitOffset = 2)
    factory.read(mirror.diceCount, 0x004, bitOffset = 8)

    for ((m, tsu, base) <- Seq(
        (mirror.txTs, dspArea.txTsu, 0x010),
        (mirror.rxTs, dspArea.rxTsu, 0x020))) {
      factory.read(m.ts(31 downto 0), base) // lo read latches hi — torn-read safety
      factory.onReadPrimitive(SingleMapping(base), haltSensitive = false, null) {
        m.hiLatch := m.ts(63 downto 32).asBits
      }
      factory.read(m.hiLatch, base + 0x4)
      factory.read(m.valid, base + 0x8, bitOffset = 0)
      factory.read(m.overrun, base + 0x8, bitOffset = 1)
      factory.read(m.seq, base + 0x8, bitOffset = 4)
      val ackStrobe = False
      factory.onWritePrimitive(SingleMapping(base + 0x8), haltSensitive = false, null) {
        ackStrobe := True
      }
      tsu.io.ack := pulseTo(dspCd)(ackStrobe)
    }

    factory.write(mirror.markerTime(0, 32 bits), 0x030)
    factory.write(mirror.markerTime(32, 32 bits), 0x034)
    factory.write(mirror.markerWidth, 0x038, bitOffset = 0)
    factory.read(mirror.markerArmed, 0x038, bitOffset = 0)
    factory.read(mirror.markerMissed, 0x038, bitOffset = 1)
    val armStrobe = False
    factory.onWritePrimitive(SingleMapping(0x038), haltSensitive = false, null) { armStrobe := True }
    dspArea.marker.io.arm := pulseTo(dspCd)(armStrobe)
    factory.readAndWrite(mirror.markerPeriod, 0x03c)

    val txPush = factory.createAndDriveFlow(Bits(9 bits), 0x040)
    txFifo.io.push.valid := txPush.valid // software respects occupancy: no overflow by contract
    txFifo.io.push.last := txPush.payload(8)
    txFifo.io.push.fragment := txPush.payload(7 downto 0)
    factory.read(txFifo.io.pushOccupancy, 0x044)

    val rxWord = Stream(Bits(9 bits))
    rxWord.arbitrationFrom(rxFifo.io.pop)
    rxWord.payload := rxFifo.io.pop.last ## rxFifo.io.pop.fragment
    factory.readStreamNonBlocking(rxWord, 0x050, validBitOffset = 16, payloadBitOffset = 0)
    factory.read(rxFifo.io.popOccupancy, 0x054)

    for ((c, i) <- mirror.counters.zipWithIndex) factory.read(c, 0x060 + 4 * i)
  }))
}
