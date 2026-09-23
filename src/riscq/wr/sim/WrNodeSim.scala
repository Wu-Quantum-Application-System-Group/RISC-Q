package riscq.wr.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.soc.{PulseTableSoc, SocChannelMap}
import riscq.wr.gty.GtySimPhy

/**
 * W5 map/CDC gate (spec 06 §4): a single `PulseTableSoc(withWhiteRabbit = true)` with the
 * `GtySimPhy` serial **self-loopback**, every register access through the real host path
 * (AXI → `Axi4ToTilelinkFiber` → hostBus → the WrNode window at `map.wrBase`).
 *
 * Checks:
 *   - register-map smoke: CTRL powers up with `resetAll` set; a write releases it and the role
 *     scratch bit reads back;
 *   - link-up: STATUS reaches ready+aligned+synced through the modeled dice-throw;
 *   - both TSUs capture on a self-looped frame (TX `t1`, RX `t2`), seq/ack behave, the frame body
 *     round-trips through RXF, and the TX→RX latency is the loopback constant (±1-cycle dither);
 *   - **64-bit latch torn-read**: `refTime` is forced just below 2^32 so the second exchange's
 *     capture crosses the boundary — after the new capture lands, a hi read WITHOUT a fresh lo
 *     read still returns the previous exchange's latched hi word (lo-read-latches-hi, 06 §2);
 *   - the sync marker arms over the bus and fires the `wrMarker` pin at the programmed syncTime;
 *   - the error counters stay zero end-to-end on the clean loopback.
 *
 * Run: `mill runMain riscq.wr.sim.WrNodeSim`.
 */
object WrNodeSim extends App {
  val REF_FORCE = BigInt("FFFFF000", 16) // 4096 cycles below 2^32: exchange 1 below, exchange 2 above

  SimConfig
    .addSimulatorFlag("-Wno-MULTIDRIVEN") // the clock-crossing Bram blackbox arrays (PulseTableSocSim)
    .addSimulatorFlag("--x-initial 0")    // 0-init the host→dsp CDC FIFO state (PulseTableSocSim)
    .compile(PulseTableSoc(qubitNum = 1, dacMap = SocChannelMap.dacMap(1),
      adcMap = SocChannelMap.adcMap(1), withWhiteRabbit = true, wrMarkerDac = Some(4)))
    .doSim("wrNode", seed = 3) { dut =>
      val hostCd = dut.clockDomain
      val dspCd  = dut.dspCd

      // idle bus + converters before the first edge (SOC_TIPS: no floating valids in reset)
      dut.io.axi.ar.valid #= false; dut.io.axi.aw.valid #= false; dut.io.axi.w.valid #= false
      dut.io.axi.r.ready #= false; dut.io.axi.b.ready #= false
      for (a <- dut.io.adc) { a.valid #= true; a.payload #= 0 }
      // node-driven phy pins float until the loopback model forks (W3 discipline)
      val phy = dut.wrPhy
      phy.clkRef #= false; phy.clkRx #= false; phy.rxDataRaw #= 0
      phy.ready #= false; phy.aligned #= false; phy.diceCount #= 0

      // ps timebase (the GtySimPhy model sleeps in ps): both clocks 10 ns / 100 MHz
      hostCd.forkStimulus(10000)
      dspCd.forkStimulus(10000)
      GtySimPhy.loopback(phy, GtySimPhy.Config(delayAtoBPs = 40000, seed = 5))
      fork { sleep(1500000000L); simFailure("watchdog: WrNodeSim did not finish in 1.5 ms sim time") }
      hostCd.waitSampling(40)

      val axi  = Axi4Master(dut.io.axi, hostCd)
      val base = BigInt(dut.map.wrBase)
      def rd(off: Long): Long =
        axi.read(base + off, 4).zipWithIndex.map { case (b, i) => (b.toLong & 0xffL) << (8 * i) }.sum
      def wr32(off: Long, v: Long): Unit =
        axi.write(base + off, List.tabulate(4)(i => ((v >> (8 * i)) & 0xff).toByte))
      def poll(off: Long, mask: Long, want: Long, what: String): Unit = {
        var spins = 0
        while ((rd(off) & mask) != want) { spins += 1; assert(spins < 3000, s"poll timeout: $what") }
      }

      // ── register smoke: power-up resetAll, release + role scratch readback ──
      assert((rd(0x000) & 1) == 1, "CTRL.resetAll must power up set")
      wr32(0x000, 0x8) // release resetAll, set role
      assert(rd(0x000) == 0x8, "CTRL readback after release/role write")

      // ── link-up through the modeled dice-throw ──
      poll(0x004, 0x7, 0x7, "STATUS never reached ready+aligned+synced")
      println(f"[WrNodeSim] link up, STATUS=0x${rd(0x004)}%x")

      // pin refTime just below the 2^32 boundary: exchange 1 captures below it, exchange 2 above
      dut.riscqArea.refTime #= REF_FORCE

      def sendFrame(typ: Int, seq: Int): Unit = { wr32(0x040, typ); wr32(0x040, 0x100 | seq) }
      def takeTs(tsBase: Long, expSeq: Int): BigInt = {
        poll(tsBase + 8, 1, 1, s"TSU@$tsBase no capture")
        val ctrl = rd(tsBase + 8)
        assert((ctrl & 2) == 0, s"TSU@$tsBase overrun")
        assert(((ctrl >> 4) & 0xf) == (expSeq & 0xf), s"TSU@$tsBase seq ${(ctrl >> 4) & 0xf} != $expSeq")
        val lo = rd(tsBase) // latches hi
        val hi = rd(tsBase + 4)
        wr32(tsBase + 8, 1) // ack
        (BigInt(hi) << 32) | BigInt(lo)
      }
      def popFrame(): Seq[Int] = {
        val bytes = collection.mutable.ArrayBuffer[Int]()
        var done  = false
        var spins = 0
        while (!done) {
          val w = rd(0x050)
          if ((w & 0x10000) != 0) { bytes += (w & 0xff).toInt; done = (w & 0x100) != 0 }
          else { spins += 1; assert(spins < 3000, "RXF starved") }
        }
        bytes.toSeq
      }

      // ── exchange 1 (below 2^32): both TSUs capture, body round-trips ──
      sendFrame(1, 0)
      val t1a = takeTs(0x010, 1)
      val t2a = takeTs(0x020, 1)
      assert(popFrame() == Seq(1, 0), "frame 1 body mismatch")
      assert(t1a >= REF_FORCE && t1a < (BigInt(1) << 32), s"t1a=$t1a not in the forced pre-wrap window")
      val d1 = (t2a - t1a).toLong
      assert(d1 > 0 && d1 < 400, s"self-loop latency $d1 cycles out of range")
      println(s"[WrNodeSim] exchange 1: t1=$t1a t2=$t2a latency=$d1 cycles")

      // ── cross the 2^32 boundary ──
      dspCd.waitSampling(6000)
      assert(dut.riscqArea.refTime.toBigInt > (BigInt(1) << 32), "refTime did not cross 2^32")

      // ── exchange 2: torn-read protection on the 64-bit latch ──
      sendFrame(1, 1)
      poll(0x018, 1, 1, "TXTS no capture (exchange 2)")
      assert(rd(0x014) == 0,
        "TXTS_HI must still return exchange 1's latch (0) until a lo read re-latches — torn read!")
      val t1b = takeTs(0x010, 2)
      val t2b = takeTs(0x020, 2)
      assert(popFrame() == Seq(1, 1), "frame 2 body mismatch")
      assert((t1b >> 32) == 1, s"t1b=$t1b should have crossed 2^32")
      val d2 = (t2b - t1b).toLong
      assert((d2 - d1).abs <= 2, s"latency not constant: $d1 vs $d2")
      println(s"[WrNodeSim] exchange 2: t1=$t1b latency=$d2 cycles — torn-read latch holds")

      // ── marker: arm at a future syncTime, pin fires, one-shot disarms ──
      val target = t2b + 8000
      wr32(0x03c, 0)                       // one-shot
      wr32(0x030, (target & 0xffffffffL).toLong)
      wr32(0x034, ((target >> 32) & 0xffffffffL).toLong)
      wr32(0x038, 50)                      // width 50, arms
      assert((rd(0x038) & 1) == 1, "marker should report armed")
      waitUntil(dut.wrMarker.toBoolean)
      // spare-DAC marker route (wrMarkerDac = 4): full-scale on every lane while the marker is
      // high (RegNext + the shared RFDC stage = 2 dspClk cycles behind the pin), 0 after.
      val fullScale = (0 until 16).map(i => BigInt(0x7FFF) << (16 * i)).reduce(_ | _)
      dspCd.waitSampling(3)
      assert(dut.io.dac(4).payload.toBigInt == fullScale,
        "DAC 4 should carry the full-scale marker step while the marker is high")
      dspCd.waitSampling(60)               // width 50 + settle
      assert(!dut.wrMarker.toBoolean, "marker pin should have dropped after `width` cycles")
      assert(dut.io.dac(4).payload.toBigInt == 0, "DAC 4 should return to 0 after the marker")
      assert((rd(0x038) & 3) == 0, "one-shot marker should disarm, not miss")
      println("[WrNodeSim] marker armed over the bus, fired, and stepped DAC 4 full-scale")

      // ── clean loopback: every error counter still zero ──
      for (i <- 0 until 5) {
        val c = rd(0x060 + 4 * i)
        assert(c == 0, s"error counter $i = $c on a clean loopback")
      }
      println("[WrNodeSim] all PASS")
    }
}
