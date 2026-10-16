package riscq.memory.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.memory.Bram

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[Bram]] (the true-dual-port BRAM blackbox + its Verilog template,
 * `BramBlackBox.v`) — the blackbox sibling of [[DualClockRamSim]]'s `DualClockRam`. Driven against a
 * software memory model with the two ports' clocks running at different (co-prime) periods, so the
 * two-clock storage is exercised, not just a single-clock RAM. Phases never read+write the same port
 * concurrently, so there are no write/read races to model:
 *
 *   - '''Latency:''' a single read pulse appears at the output exactly `L` cycles later
 *     (`L = 1` for `LOW_LATENCY`, `2` for `HIGH_PERFORMANCE`), asserted as a sharp spike.
 *   - '''Cross-domain write→read:''' write the whole array on port 0, read it all back on port 1
 *     (and vice-versa) — bit-exact, accounting for each side's read latency.
 *   - '''Byte masks:''' masked writes update only the enabled byte columns; read-back bit-exact
 *     against the per-byte model merge.
 *   - both `highPerformance` settings, power-of-two and odd depths, several widths.
 *
 * Drives the blackbox Verilog through Verilator, so it also proves the committed `BramBlackBox.v`
 * matches the SpinalHDL wrapper. The BRAM array is written from two clock blocks (one array, two
 * ports — a true dual-port RAM), which Verilator flags as MULTIDRIVEN; that is intentional, so the
 * check is silenced (as in `DualClockRamSim`). Run with
 * `mill runMain riscq.memory.sim.BramSim`.
 */
object BramSim extends App {

  /** Testbench wrapper: owns the two external clock domains and re-exports both RAM ports. */
  case class Dut(width: Int, depth: Int, highPerformance: Boolean) extends Component {
    val fast  = ClockDomain.external("fast")
    val slow  = ClockDomain.external("slow")
    val ram   = Bram(Bits(width bits), depth, fast, slow, highPerformance)
    val port0 = slave(cloneOf(ram.io.port0)); port0 <> ram.io.port0
    val port1 = slave(cloneOf(ram.io.port1)); port1 <> ram.io.port1
  }

  /** Merge `wdata`'s enabled byte columns (by `mask`) into `old`; the others keep `old`'s bytes. */
  def byteMerge(old: BigInt, wdata: BigInt, mask: BigInt, nBytes: Int): BigInt = {
    var r = old
    for (i <- 0 until nBytes) if (((mask >> i) & 1) == 1) {
      val lo = i * 8
      r = (r & ~(BigInt(0xff) << lo)) | (((wdata >> lo) & 0xff) << lo)
    }
    r
  }

  def run(width: Int, depth: Int, highPerformance: Boolean): Long = {
    val nBytes  = width / 8
    val fullMsk = (BigInt(1) << nBytes) - 1
    val wMask   = (BigInt(1) << width) - 1
    val L       = if (highPerformance) 2 else 1  // address -> dout latency
    var checks  = 0L

    SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")
      .compile(Dut(width, depth, highPerformance))
      .doSim(s"bram-w${width}_d${depth}_hp$highPerformance", seed = 42) { dut =>
        // port 0 (fast) and port 1 (slow) at co-prime periods so edges interleave irregularly.
        dut.fast.forkStimulus(7)
        dut.slow.forkStimulus(20)

        val rng   = new Random(1)
        val model = Array.fill[BigInt](depth)(BigInt(0))

        def idle(p: spinal.lib.MemReadWritePort[Bits]): Unit = {
          p.enable #= false; p.write #= false; p.address #= 0; p.wdata #= 0; p.mask #= 0
        }
        idle(dut.port0); idle(dut.port1)
        dut.fast.waitSampling(3); dut.slow.waitSampling(3)

        // write `ops` on one port (stepping that port's clock), updating the model.
        def writeStream(p: spinal.lib.MemReadWritePort[Bits], cd: ClockDomain,
                        ops: Seq[(Int, BigInt, BigInt)]): Unit = {
          for ((a, d, m) <- ops) {
            p.enable #= true; p.write #= true; p.address #= a; p.wdata #= d; p.mask #= m
            cd.waitSampling()
            model(a) = byteMerge(model(a), d, m, nBytes)
          }
          idle(p)
        }

        // read `addrs` on one port; rdata for the address presented at cycle t appears at t+L.
        def readStream(p: spinal.lib.MemReadWritePort[Bits], cd: ClockDomain,
                       addrs: Seq[Int]): Seq[BigInt] = {
          val out = mutable.ArrayBuffer[BigInt]()
          for (t <- 0 until addrs.size + L) {
            val a = if (t < addrs.size) addrs(t) else 0
            p.enable #= true; p.write #= false; p.address #= a; p.mask #= 0
            cd.waitSampling()
            if (t >= L) out += p.rdata.toBigInt
          }
          idle(p)
          out.toSeq
        }

        val all = 0 until depth
        def settle(): Unit = { dut.fast.waitSampling(L + 3); dut.slow.waitSampling(L + 3) }

        def checkRead(out: Seq[BigInt], tag: String): Unit =
          for (a <- all) {
            assert(out(a) == model(a), s"[w$width d$depth] $tag addr $a: ${out(a)} != ${model(a)}")
            checks += 1
          }

        // === Latency: a single read pulse appears only at sample L. =====================
        writeStream(dut.port0, dut.fast, Seq((3, BigInt("a5a5a5a5", 16) & wMask, fullMsk)))
        settle()
        val rec = mutable.ArrayBuffer[BigInt]()
        for (t <- 0 until L + 4) {
          // read addr 3 only at t==0, idle (disabled) otherwise so the value can't refill.
          if (t == 0) { dut.port0.enable #= true; dut.port0.write #= false; dut.port0.address #= 3; dut.port0.mask #= 0 }
          else        { idle(dut.port0) }
          dut.fast.waitSampling()
          rec += dut.port0.rdata.toBigInt
        }
        assert(rec(L) == model(3), s"[w$width] latency: read landed at ${rec.indexOf(model(3))} != $L")
        idle(dut.port0); settle()

        // === port0 (fast) writes -> port1 (slow) reads =================================
        writeStream(dut.port0, dut.fast, all.map(a => (a, BigInt(width, rng), fullMsk)))
        settle()
        checkRead(readStream(dut.port1, dut.slow, all), "p0->p1")

        // === port1 (slow) writes -> port0 (fast) reads =================================
        writeStream(dut.port1, dut.slow, all.map(a => (a, BigInt(width, rng), fullMsk)))
        settle()
        checkRead(readStream(dut.port0, dut.fast, all), "p1->p0")

        // === byte-masked writes (port0) -> read back (port1) ===========================
        val maskOps = (0 until 4 * depth).map { _ =>
          (rng.nextInt(depth), BigInt(width, rng), BigInt(nBytes, rng))
        }
        writeStream(dut.port0, dut.fast, maskOps)
        settle()
        checkRead(readStream(dut.port1, dut.slow, all), "masked")

        simSuccess()
      }
    checks
  }

  val configs = Seq(
    (32, 256, true),
    (32, 256, false),
    (32, 255, false),   // odd depth
    (16, 64,  true),
    (64, 32,  true)
  )
  for ((w, d, hp) <- configs) {
    val c = run(w, d, hp)
    println(s"[BramSim] PASS  width=$w depth=$d highPerformance=$hp (latency=${if (hp) 2 else 1}): $c reads bit-exact.")
  }
  println("[BramSim] all configs PASS")
}
