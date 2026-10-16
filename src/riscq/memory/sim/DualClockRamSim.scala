package riscq.memory.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.memory.DualClockRam

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[DualClockRam]] — a true dual-port BRAM whose two byte-write ports
 * live in *independent* clock domains. Driven against a software memory model with the two clocks
 * running at different (and co-prime) periods, so the clock-crossing storage is exercised, not just
 * a single-clock RAM. Phases never overlap on a port, so there are no write/read races to model:
 *
 *   - '''Cross-domain write→read:''' write the whole array on the fast port, read it all back on the
 *     slow port (and vice-versa) — bit-exact, accounting for each side's read latency
 *     (`1 + withOutReg`).
 *   - '''Byte masks:''' masked writes update only the enabled byte columns; read-back bit-exact
 *     against the per-byte model merge.
 *   - both `withOutReg` settings, power-of-two and odd depths, several widths.
 *
 * Run with `mill runMain riscq.memory.sim.DualClockRamSim`.
 */
object DualClockRamSim extends App {

  /** Testbench wrapper: owns the two external clock domains and re-exports both RAM ports. */
  case class Dut(
      width: Int, depth: Int,
      withOutRegFast: Boolean, withOutRegSlow: Boolean
  ) extends Component {
    val fast = ClockDomain.external("fast")
    val slow = ClockDomain.external("slow")
    val ram  = DualClockRam(width, depth, fast, slow, withOutRegFast, withOutRegSlow)
    val fastPort = slave(cloneOf(ram.fastPort)); fastPort <> ram.fastPort
    val slowPort = slave(cloneOf(ram.slowPort)); slowPort <> ram.slowPort
  }

  def byteMerge(old: BigInt, wdata: BigInt, mask: BigInt, nBytes: Int): BigInt = {
    var r = old
    for (i <- 0 until nBytes) if (((mask >> i) & 1) == 1) {
      val lo = i * 8
      r = (r & ~(BigInt(0xff) << lo)) | (((wdata >> lo) & 0xff) << lo)
    }
    r
  }

  def run(width: Int, depth: Int, woRegFast: Boolean, woRegSlow: Boolean): Long = {
    val nBytes  = width / 8
    val fullMsk = (BigInt(1) << nBytes) - 1
    var checks  = 0L

    // The clock-crossing Mem is written from two different clock blocks (one storage array, two
    // ports) — a true dual-port RAM. Verilator flags that as MULTIDRIVEN and (by default) errors;
    // it's intentional here, so silence just that check.
    SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")
      .compile(Dut(width, depth, woRegFast, woRegSlow))
      .doSim(s"dcram-w${width}_d${depth}_f$woRegFast-s$woRegSlow", seed = 42) { dut =>
        // fast and slow at co-prime periods so edges interleave irregularly.
        dut.fast.forkStimulus(7)
        dut.slow.forkStimulus(20)

        val rng   = new Random(1)
        val model = Array.fill[BigInt](depth)(BigInt(0))

        def idle(p: spinal.lib.MemReadWritePort[Bits]): Unit = {
          p.enable #= false; p.write #= false; p.address #= 0; p.wdata #= 0; p.mask #= 0
        }
        idle(dut.fastPort); idle(dut.slowPort)
        dut.fast.waitSampling(3); dut.slow.waitSampling(3)

        // write `addrs` on one port (stepping that port's clock), updating the model.
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
                       addrs: Seq[Int], lat: Int): Seq[BigInt] = {
          val out = mutable.ArrayBuffer[BigInt]()
          for (t <- 0 until addrs.size + lat) {
            val a = if (t < addrs.size) addrs(t) else 0
            p.enable #= true; p.write #= false; p.address #= a; p.mask #= 0
            cd.waitSampling()
            if (t >= lat) out += p.rdata.toBigInt
          }
          idle(p)
          out.toSeq
        }

        val Lfast = 1 + (if (woRegFast) 1 else 0)
        val Lslow = 1 + (if (woRegSlow) 1 else 0)
        val all   = 0 until depth

        def settle(): Unit = { dut.fast.waitSampling(Lfast + 3); dut.slow.waitSampling(Lslow + 3) }

        def checkRead(out: Seq[BigInt], tag: String): Unit =
          for (a <- all) {
            assert(out(a) == model(a), s"[w$width d$depth] $tag addr $a: ${out(a)} != ${model(a)}")
            checks += 1
          }

        // === fast writes -> slow reads =================================================
        writeStream(dut.fastPort, dut.fast, all.map(a => (a, BigInt(width, rng), fullMsk)))
        settle()
        checkRead(readStream(dut.slowPort, dut.slow, all, Lslow), "fast->slow")

        // === slow writes -> fast reads =================================================
        writeStream(dut.slowPort, dut.slow, all.map(a => (a, BigInt(width, rng), fullMsk)))
        settle()
        checkRead(readStream(dut.fastPort, dut.fast, all, Lfast), "slow->fast")

        // === byte-masked writes (fast) -> read back (slow) ============================
        val maskOps = (0 until 4 * depth).map { _ =>
          (rng.nextInt(depth), BigInt(width, rng), BigInt(nBytes, rng))
        }
        writeStream(dut.fastPort, dut.fast, maskOps)
        settle()
        checkRead(readStream(dut.slowPort, dut.slow, all, Lslow), "masked")

        simSuccess()
      }
    checks
  }

  val configs = Seq(
    (32, 256, false, false),
    (32, 256, true,  false),
    (32, 255, false, true),   // odd depth
    (16, 64,  true,  true),
    (64, 32,  false, true)
  )
  for ((w, d, ff, fs) <- configs) {
    val c = run(w, d, ff, fs)
    println(s"[DualClockRamSim] PASS  width=$w depth=$d woRegFast=$ff woRegSlow=$fs: $c reads bit-exact.")
  }
  println("[DualClockRamSim] all configs PASS")
}
