package riscq.wr.gty.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.wr.gty.CommaAligner
import riscq.wr.pcs.Enc8b10b

import scala.util.Random

/**
 * W4 gate, spec 01 §5: a behavioral serializer feeds the 1000Base-X idle stream at **all 20
 * word-boundary offsets**. For each offset the aligner must lock onto the comma at exactly that
 * position; at the target position it holds `aligned` with no reset requests; at any other it
 * emits dice-throw `rxResetReq` pulses (`diceCount` advancing). Comma-less input drops the link
 * after the hysteresis; both comma disparities occur naturally in the idle stream.
 *
 * Run: `mill runMain riscq.wr.gty.sim.CommaAlignerSim`.
 */
object CommaAlignerSim extends App {

  SimConfig.compile(CommaAligner(targetPos = 0, upCount = 40, lossCount = 60))
    .doSim("aligner", seed = 3) { dut =>
      dut.io.rxDataRaw #= 0
      dut.clockDomain.forkStimulus(10)
      dut.clockDomain.waitSampling(5)

      // idle words (I1/I2 discipline like WrTxPcs), tracked running disparity
      var rd = false
      def idleWord(): Int = {
        val (c0, r0) = Enc8b10b.model(0xbc, isK = true, rd)
        val second   = if (r0) 0xc5 else 0x50 // I1 flips back to RD−, I2 preserves
        val (c1, r1) = Enc8b10b.model(second, isK = false, r0)
        rd = r1
        (c1 << 10) | c0
      }

      var last = 0
      def drive(w: Int): Unit = { dut.io.rxDataRaw #= w; dut.clockDomain.waitSampling() }
      def driveShifted(off: Int): Unit = {
        val w = idleWord()
        drive(if (off == 0) w else ((w << (20 - off)) | (last >>> off)) & 0xfffff)
        last = w
      }
      def forceLost(): Unit = { for (_ <- 0 until 100) drive(0) ; assert(!dut.io.linkUp.toBoolean) }

      var totalDice = 0
      for (off <- 0 until 20) {
        forceLost()
        // the slip model leaves (20−off) stale bits at the front, so the comma lands there
        val expectPos = (20 - off) % 20
        var seenReq = false
        var cycles  = 0
        while (!seenReq && cycles < 500) {
          driveShifted(off)
          cycles += 1
          if (dut.io.posValid.toBoolean)
            assert(dut.io.commaPos.toInt == expectPos, s"comma at ${dut.io.commaPos.toInt}, expected $expectPos")
          if (dut.io.rxResetReq.toBoolean) seenReq = true
        }
        if (off == 0) {
          assert(!seenReq, "reset request at the target position")
          assert(dut.io.linkUp.toBoolean && dut.io.aligned.toBoolean, "not aligned at target offset")
        } else {
          assert(seenReq, s"no dice-throw request at offset $off")
          assert(!dut.io.aligned.toBoolean, s"aligned at wrong offset $off")
          totalDice += 1
          dut.clockDomain.waitSampling(2) // let the dice register commit past the pulse
          assert(dut.io.diceCount.toInt == totalDice, "diceCount did not advance")
        }
      }

      // loss of sync: re-align at 0, then starve the commas
      forceLost()
      for (_ <- 0 until 200) driveShifted(0)
      assert(dut.io.aligned.toBoolean)
      for (_ <- 0 until 100) drive(0)
      assert(!dut.io.linkUp.toBoolean, "link did not drop on comma-less input")

      println(s"[CommaAlignerSim] PASS  20 offsets, ${totalDice} dice throws, loss-of-sync ok")
    }
}
