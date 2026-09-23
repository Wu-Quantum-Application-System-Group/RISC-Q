package riscq.wr.gty

import spinal.core._
import spinal.lib._
import riscq.misc.{BUFG_GT, IBUFDS_GTE4}

import scala.io.Source

/**
 * BlackBox over `src/riscq/wr/gty/WrGtyChannel.v` — the harvested-attribute `GTYE4_CHANNEL`
 * wrapper (spec 01 §2): 1.25 Gb/s from QPLL0, raw 20-bit datapath, TX/RX buffers bypassed,
 * no GT comma logic. Only the WR-needed ports are exposed; everything else is tied inside the
 * template per the wizard harvest.
 */
case class WrGtyChannelBb() extends BlackBox {
  setDefinitionName("WrGtyChannel")
  val io = new Bundle {
    val GTYRXP, GTYRXN = in Bool ()
    val GTYTXP, GTYTXN = out Bool ()
    val QPLL0CLK, QPLL0REFCLK, QPLL1CLK, QPLL1REFCLK = in Bool ()
    val GTTXRESET, GTRXRESET, TXPROGDIVRESET, RXPROGDIVRESET = in Bool ()
    val TXUSERRDY, RXUSERRDY = in Bool ()
    val TXRESETDONE, RXRESETDONE, TXPMARESETDONE, RXPMARESETDONE, GTPOWERGOOD = out Bool ()
    val RXCDRLOCK = out Bool ()
    val TXUSRCLK, TXUSRCLK2, RXUSRCLK, RXUSRCLK2 = in Bool ()
    val TXOUTCLK, RXOUTCLK = out Bool ()
    val TXDATA = in Bits (20 bits)
    val RXDATA = out Bits (20 bits)
    val TXDLYSRESET, TXSYNCMODE, TXSYNCALLIN, TXSYNCIN = in Bool ()
    val TXDLYSRESETDONE, TXPHALIGNDONE, TXSYNCOUT, TXSYNCDONE = out Bool ()
    val RXDLYSRESET, RXSYNCMODE, RXSYNCALLIN, RXSYNCIN = in Bool ()
    val RXDLYSRESETDONE, RXPHALIGNDONE, RXSYNCOUT, RXSYNCDONE = out Bool ()
    val LOOPBACK = in Bits (3 bits)
    val TXPD, RXPD = in Bits (2 bits)
    // power-on hold (the wizard's gtye4_delay_powergood contract — see WrGtyPhy.powerOn)
    val TXPISOPD   = in Bool ()
    val TXRATE     = in Bits (3 bits)
    val TXRATEMODE = in Bool ()
  }
  noIoPrefix()
  setInlineVerilog(Source.fromFile("src/riscq/wr/gty/WrGtyChannel.v").mkString)
}

/** BlackBox over `src/riscq/wr/gty/WrGtyCommon.v` — the `GTYE4_COMMON` QPLL0 wrapper (QPLL1 dark). */
case class WrGtyCommonBb() extends BlackBox {
  setDefinitionName("WrGtyCommon")
  val io = new Bundle {
    val GTREFCLK00     = in Bool ()
    val QPLL0RESET     = in Bool ()
    val QPLL0LOCK      = out Bool ()
    val QPLL0OUTCLK    = out Bool ()
    val QPLL0OUTREFCLK = out Bool ()
    val QPLL1LOCK      = out Bool ()
    val QPLL1OUTCLK    = out Bool ()
    val QPLL1OUTREFCLK = out Bool ()
  }
  noIoPrefix()
  setInlineVerilog(Source.fromFile("src/riscq/wr/gty/WrGtyCommon.v").mkString)
}

case class WrGtyPhyParams(commaTargetPos: Int = 0)

/**
 * The deterministic-latency GTY PHY (spec 01): one channel + common assembled with the fabric
 * controllers — [[GtyResetCtrl]] (`clk_free` = this component's domain), [[BufferBypassCtrl]]
 * per direction (each in its user-clock domain), and the [[CommaAligner]] dice-throw. Presents
 * the [[WrPhyIo]] master contract to `WrNode` — the same bundle `GtySimPhy` drives in sims.
 *
 * Determinism levers (spec D1): buffers bypassed (fixed word↔serial phase per reset), no GT
 * comma logic / no RXSLIDE (bitslide 0 by construction), alignment by RX-datapath re-reset until
 * the comma lands at position 0. `clk_ref` = TXOUTCLK/`BUFG_GT`, `clk_rx` = RXOUTCLK/`BUFG_GT`,
 * both 62.5 MHz at 1.25 Gb/s / 20-bit raw.
 *
 * Verified by elaboration (`WrGtyPhyGen`) + Vivado OOC synthesis (`WrGtyPhyVivadoBench`); the
 * behaviour is board territory (W6) — the FSMs carry their own scripted-GT sims.
 */
case class WrGtyPhy(p: WrGtyPhyParams = WrGtyPhyParams()) extends Component {
  val io = new Bundle {
    val refClkP, refClkN = in Bool ()
    val rxP, rxN         = in Bool ()
    val txP, txN         = out Bool ()
    val phy              = master(WrPhyIo())
    val loopback         = in Bits (3 bits) // PMA loopback for the W6 self-test
  }

  val refBuf = IBUFDS_GTE4()
  refBuf.I := io.refClkP
  refBuf.IB := io.refClkN
  refBuf.CEB := False

  val common = WrGtyCommonBb()
  common.io.GTREFCLK00 := refBuf.O

  val channel = WrGtyChannelBb()
  channel.io.GTYRXP := io.rxP
  channel.io.GTYRXN := io.rxN
  io.txP := channel.io.GTYTXP
  io.txN := channel.io.GTYTXN
  channel.io.QPLL0CLK := common.io.QPLL0OUTCLK
  channel.io.QPLL0REFCLK := common.io.QPLL0OUTREFCLK
  channel.io.QPLL1CLK := False
  channel.io.QPLL1REFCLK := False
  channel.io.LOOPBACK := io.loopback
  channel.io.TXPD := 0
  channel.io.RXPD := 0

  // power-on hold (the wizard's gtye4_delay_powergood contract, harvest report note 4):
  // until GTPOWERGOOD + a settle delay, force TXPISOPD=1, TXRATE=001, TXRATEMODE=1 and hold
  // GTTXRESET; only then hand the resets to GtyResetCtrl. Counted on clk_free (≤ 62.5 MHz).
  val powerOn = new Area {
    val good = BufferCC(channel.io.GTPOWERGOOD, init = False)
    val cnt  = Reg(UInt(9 bits)) init 0
    val done = Reg(Bool()) init False
    when(!good) {
      cnt := 0
      done := False
    } elsewhen (!done) {
      cnt := cnt + 1
      when(cnt === 256) { done := True }
    }
  }
  channel.io.TXPISOPD := !powerOn.done
  channel.io.TXRATE := powerOn.done ? B"000" | B"001"
  channel.io.TXRATEMODE := !powerOn.done

  // usrclk networks (div-1 BUFG_GT; 20-bit raw ⇒ USRCLK == USRCLK2)
  val refBufg = BUFG_GT().driveDefaults()
  refBufg.I := channel.io.TXOUTCLK
  val rxBufg = BUFG_GT().driveDefaults()
  rxBufg.I := channel.io.RXOUTCLK
  val clkRef = refBufg.O
  val clkRx  = rxBufg.O
  channel.io.TXUSRCLK := clkRef
  channel.io.TXUSRCLK2 := clkRef
  channel.io.RXUSRCLK := clkRx
  channel.io.RXUSRCLK2 := clkRx
  io.phy.clkRef := clkRef
  io.phy.clkRx := clkRx

  val bootCfg = ClockDomainConfig(resetKind = BOOT)
  val refCd   = ClockDomain(clkRef, config = bootCfg, frequency = FixedFrequency(62.5 MHz))
  val rxCd    = ClockDomain(clkRx, config = bootCfg, frequency = FixedFrequency(62.5 MHz))

  // datapath: straight wires — no shifter (bitslide 0 by construction, spec 01 §5)
  channel.io.TXDATA := io.phy.txDataRaw
  io.phy.rxDataRaw := channel.io.RXDATA

  // ── reset sequencing (clk_free domain) ─────────────────────────────────────────────────────
  val aligner = rxCd(CommaAligner(targetPos = p.commaTargetPos))
  aligner.io.rxDataRaw := channel.io.RXDATA

  val reset = GtyResetCtrl()
  reset.io.resetAll := BufferCC(io.phy.resetAll, init = True) || !powerOn.done
  val diceReq = new Area { // aligner's clk_rx request pulse → clk_free (toggle CC)
    val toggle = rxCd { val t = Reg(Bool()) init False; when(aligner.io.rxResetReq) { t := !t }; t }
    val sync   = BufferCC(toggle, init = False)
    val pulse  = sync =/= RegNext(sync, False)
  }
  reset.io.resetRxDatapath := BufferCC(io.phy.resetRxDatapath, init = False) || diceReq.pulse
  reset.io.qpllLock := common.io.QPLL0LOCK
  common.io.QPLL0RESET := reset.io.qpllReset
  channel.io.GTTXRESET := reset.io.gtTxReset || !powerOn.done // != GTPOWERGOOD hold, then sequenced
  channel.io.TXPROGDIVRESET := reset.io.txProgDivReset
  channel.io.GTRXRESET := reset.io.gtRxReset
  channel.io.RXPROGDIVRESET := reset.io.rxProgDivReset
  channel.io.TXUSERRDY := reset.io.txUserRdy
  channel.io.RXUSERRDY := reset.io.rxUserRdy
  reset.io.txPmaResetDone := channel.io.TXPMARESETDONE
  reset.io.rxPmaResetDone := channel.io.RXPMARESETDONE
  reset.io.txResetDone := channel.io.TXRESETDONE
  reset.io.rxResetDone := channel.io.RXRESETDONE

  // ── buffer bypass, single-lane auto mode (spec 01 §4) ─────────────────────────────────────
  val txBypass = refCd(BufferBypassCtrl())
  txBypass.io.start := reset.io.txDone
  txBypass.io.dlySResetDone := channel.io.TXDLYSRESETDONE
  txBypass.io.syncDone := channel.io.TXSYNCDONE
  channel.io.TXDLYSRESET := txBypass.io.dlySReset
  channel.io.TXSYNCMODE := True
  channel.io.TXSYNCALLIN := channel.io.TXPHALIGNDONE
  channel.io.TXSYNCIN := False

  val rxBypass = rxCd(BufferBypassCtrl())
  rxBypass.io.start := reset.io.rxDone
  rxBypass.io.dlySResetDone := channel.io.RXDLYSRESETDONE
  rxBypass.io.syncDone := channel.io.RXSYNCDONE
  channel.io.RXDLYSRESET := rxBypass.io.dlySReset
  channel.io.RXSYNCMODE := True
  channel.io.RXSYNCALLIN := channel.io.RXPHALIGNDONE
  channel.io.RXSYNCIN := False

  // ── status to the node ─────────────────────────────────────────────────────────────────────
  val alignedCc  = BufferCC(aligner.io.aligned, init = False)
  val txBypassCc = BufferCC(txBypass.io.done, init = False)
  val rxBypassCc = BufferCC(rxBypass.io.done, init = False)
  io.phy.ready := reset.io.txDone && reset.io.rxDone && txBypassCc && rxBypassCc && alignedCc
  io.phy.aligned := alignedCc
  io.phy.diceCount := BufferCC(aligner.io.diceCount)
}

/** Elaboration gate: `mill runMain riscq.wr.gty.WrGtyPhyGen`. */
object WrGtyPhyGen extends App {
  SpinalVerilog(WrGtyPhy())
}
