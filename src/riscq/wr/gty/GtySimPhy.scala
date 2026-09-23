package riscq.wr.gty

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

/**
 * The phy-side contract of the WR node (spec 01 §1 minus board pins and Stage-2 hooks): a 20-bit
 * raw synchronous pipe with constant TX/RX latency, plus the link clocks and the bring-up status.
 * `WrGtyPhy` (W4, real GTY) implements the master side in RTL; in W3 sims the master side is a
 * toplevel io driven by the [[GtySimPhy]] model.
 *
 * No handshakes on the datapath — one word every cycle in both directions, always (idle comes
 * from the PCS). Determinism is the point (spec 01).
 */
case class WrPhyIo() extends Bundle with IMasterSlave {
  val clkRef    = Bool() // 62.5 MHz link/TX clock (TXOUTCLK-derived on hardware)
  val clkRx     = Bool() // recovered 62.5 MHz RX clock
  val txDataRaw = Bits(20 bits) // clkRef domain, 8b/10b-encoded by the PCS
  val rxDataRaw = Bits(20 bits) // clkRx domain, aligned (comma at symbol 0) once `aligned`
  val resetAll        = Bool() // host-domain levels; the phy synchronizes internally
  val resetRxDatapath = Bool()
  val ready     = Bool() // reset done + buffer bypass done + aligned
  val aligned   = Bool()
  val diceCount = UInt(8 bits)

  override def asMaster(): Unit = { // master = the phy
    out(clkRef, clkRx, rxDataRaw, ready, aligned, diceCount)
    in(txDataRaw, resetAll, resetRxDatapath)
  }
}

/**
 * `GtySimPhy` — the simulation stand-in for `WrGtyPhy` (spec 01 §8): a sim-side model that
 * drives two node-facing [[WrPhyIo]] bundles as a cross-wired serial link.
 *
 *   - Each node's `clkRef` is a free toggler with its own phase; each direction serializes the
 *     sender's `txDataRaw` word stream through a queue and replays it on the receiver's `clkRx`,
 *     which is **the sender's clock delayed by the line delay** (ideal-CDR model) — so each
 *     direction is rate-locked by construction, like the real recovered clock.
 *   - Word latency per direction is the constant `floor(delayPs/P) + prefill` words with the
 *     sub-word remainder in the `clkRx` phase — constant per alignment epoch, which is the
 *     property the WR exchange needs; the absolute value is calibrated out (spec 02 §5).
 *   - Alignment models the dice-throw: each (re-)roll picks a random bit offset 0..19; a non-zero
 *     offset mis-groups the words (`aligned` false, `rxDataRaw` slipped); the model re-rolls
 *     every `rollCycles` RX cycles, bumping `diceCount`, until offset 0 lands → `aligned`/`ready`.
 *     `resetAll`/`resetRxDatapath` from the node restart the throw.
 *
 * Self-test: `riscq.wr.gty.sim.GtySimPhySim`.
 */
object GtySimPhy {
  case class Config(
      refPeriodPs: Long = 16000, // 62.5 MHz
      delayAtoBPs: Long = 40000,
      delayBtoAPs: Long = 40000,
      rollCycles: Int = 30, // RX cycles per dice-throw attempt (ms on hardware, spec 01 §5)
      seed: Int = 1,
      rollProbe: Int => Unit = null // self-test hook: called with each rolled bit offset
  )

  /** Fork the full two-node link model. Call once inside `doSim` after inputs are initialized. */
  def link(a: WrPhyIo, b: WrPhyIo, cfg: Config): Unit = {
    val rng    = new Random(cfg.seed)
    val phaseA = 1 + rng.nextInt(cfg.refPeriodPs.toInt - 2)
    val phaseB = 1 + rng.nextInt(cfg.refPeriodPs.toInt - 2)
    direction(a, b, phaseA, cfg.delayAtoBPs, cfg, new Random(rng.nextInt()))
    direction(b, a, phaseB, cfg.delayBtoAPs, cfg, new Random(rng.nextInt()))
  }

  /** Single-node serial self-loopback (one direction, p → p): the node receives its own TX stream
   *  after the line delay — the WrNodeSim / PMA-loopback self-test shape (spec 06 §4). */
  def loopback(p: WrPhyIo, cfg: Config): Unit = {
    val rng   = new Random(cfg.seed)
    val phase = 1 + rng.nextInt(cfg.refPeriodPs.toInt - 2)
    direction(p, p, phase, cfg.delayAtoBPs, cfg, new Random(rng.nextInt()))
  }

  /** One sender clkRef + serializer + receiver clkRx/rxDataRaw/alignment model. */
  private def direction(s: WrPhyIo, r: WrPhyIo, txPhase: Long, delayPs: Long,
                        cfg: Config, rng: Random): Unit = {
    val P     = cfg.refPeriodPs
    val half  = P / 2
    val wordQ = mutable.Queue[Int]()

    // sender clkRef toggler + post-edge txDataRaw sampler
    fork {
      s.clkRef #= false
      sleep(txPhase)
      while (true) {
        s.clkRef #= true
        sleep(1) // let the TX PCS regs settle after the edge
        wordQ += s.txDataRaw.toBigInt.toInt
        sleep(half - 1)
        s.clkRef #= false
        sleep(P - half)
      }
    }

    // receiver: clkRx = sender clock delayed by the line; word regrouping models the alignment
    fork {
      r.clkRx #= false
      r.rxDataRaw #= 0
      r.ready #= false
      r.aligned #= false
      r.diceCount #= 0

      def roll(): Int = {
        val v = rng.nextInt(20)
        if (cfg.rollProbe != null) cfg.rollProbe(v)
        v
      }
      var bitOff    = roll() // the word-boundary die
      var lastWord  = 0
      var dice      = 0
      var cyclesLeft = cfg.rollCycles
      var wasReset  = true

      // effective word latency = prefill words ⇒ line delay = delayPs + 4·P exactly, constant
      val prefill = 3 + (delayPs / P).toInt
      for (_ <- 0 until prefill) wordQ += rng.nextInt(1 << 20)

      sleep(txPhase + (delayPs % P) + P) // rx edges = tx edges shifted by the line delay
      while (true) {
        // node-requested restarts (host-domain levels, model side just watches them)
        if (s.resetAll.toBoolean || r.resetAll.toBoolean || r.resetRxDatapath.toBoolean || wasReset) {
          if (!wasReset) { // an explicit reset arrived mid-run
            r.ready #= false
            r.aligned #= false
          }
          wasReset = false
          bitOff = roll()
          cyclesLeft = cfg.rollCycles
        }
        if (!r.aligned.toBoolean) {
          cyclesLeft -= 1
          if (cyclesLeft <= 0) {
            if (bitOff == 0) {
              r.aligned #= true
              r.ready #= true
            } else { // dice-throw: reset the RX datapath, roll again
              dice += 1
              r.diceCount #= dice min 255
              bitOff = roll()
              cyclesLeft = cfg.rollCycles
            }
          }
        }
        assert(wordQ.nonEmpty, "GtySimPhy word queue underflow")
        val w   = wordQ.dequeue()
        val out = if (bitOff == 0) w else ((w << (20 - bitOff)) | (lastWord >> bitOff)) & 0xfffff
        lastWord = w
        r.rxDataRaw #= out
        sleep(1) // data strictly before the edge
        r.clkRx #= true
        sleep(half)
        r.clkRx #= false
        sleep(P - half - 1)
      }
    }
  }
}
