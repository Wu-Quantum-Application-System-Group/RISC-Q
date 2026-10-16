package riscq.memory.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.memory.Uram

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[Uram]] (the UltraRAM blackbox + its Verilog template), checked
 * against a software byte-addressable memory model. Exercises, on the `-2` RFSoC default clock:
 *
 *   - '''Latency:''' a read presented at cycle `t` returns at the output exactly `NBPIPE + 2` cycles
 *     later (memory-read register + `NBPIPE` output stages + `dout` register) — asserted as a single
 *     spike so the declared latency is sharp (the readout/scheduler alignment math depends on it).
 *   - '''Both ports, read + write:''' write the whole array via port 0, then read it all back through
 *     *both* ports bit-exact (each port has its own read datapath).
 *   - '''Byte masks:''' masked writes update only the enabled byte columns; read-back is bit-exact
 *     against the per-byte model merge.
 *   - '''Concurrency:''' port 0 writes the low half while port 1 reads the (disjoint) high half in the
 *     same cycles — a true dual-port stress with no interference.
 *
 * Drives the blackbox Verilog through Verilator, so it also proves the committed `UramBlackBox.v`
 * matches the SpinalHDL wrapper. Run with `mill runMain riscq.memory.sim.UramSim`.
 */
object UramSim extends App {

  /** Merge `wdata`'s enabled byte columns (by `mask`) into `old`; the others keep `old`'s bytes. */
  def byteMerge(old: BigInt, wdata: BigInt, mask: BigInt, nBytes: Int): BigInt = {
    var r = old
    for (i <- 0 until nBytes) if (((mask >> i) & 1) == 1) {
      val lo  = i * 8
      val clr = r & ~(BigInt(0xff) << lo)           // clear byte i
      r = clr | (((wdata >> lo) & 0xff) << lo)       // insert new byte i
    }
    r
  }

  def run(width: Int, addrW: Int, pipeNum: Int): Long = {
    val nBytes  = width / 8
    val depth   = 1 << addrW
    val L       = pipeNum + 2                       // address -> dout latency (memreg + NBPIPE + dout)
    val fullMsk = (BigInt(1) << nBytes) - 1
    val wMask   = (BigInt(1) << width) - 1
    var checks  = 0L

    SimConfig.compile(Uram(Bits(width bits), addrW, pipeNum))
      .doSim(s"uram-w${width}_a${addrW}_p$pipeNum", seed = 42) { dut =>
        val cd  = dut.clockDomain
        val p0  = dut.io.port0
        val p1  = dut.io.port1
        cd.forkStimulus(10)

        val rng   = new Random(1)
        val model = Array.fill[BigInt](depth)(BigInt(0))

        def idle(p: spinal.lib.MemReadWritePort[Bits]): Unit = {
          p.enable #= false; p.write #= false; p.address #= 0; p.wdata #= 0; p.mask #= 0
        }
        idle(p0); idle(p1)
        cd.waitSampling(2)

        // -- write one word via port 0 (enable+write high), updating the model. --
        def write0(addr: Int, data: BigInt, mask: BigInt): Unit = {
          p0.enable #= true; p0.write #= true; p0.address #= addr; p0.wdata #= data; p0.mask #= mask
          cd.waitSampling()
          model(addr) = byteMerge(model(addr), data, mask, nBytes)
        }

        // -- stream reads on `p` for `addrs`, return outputs aligned to the addresses (offset L). --
        def readStream(p: spinal.lib.MemReadWritePort[Bits], addrs: Seq[Int]): Seq[BigInt] = {
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

        // === Latency: a single read pulse appears only at sample L. =====================
        write0(3, BigInt("a5a5a5a5", 16) & wMask, fullMsk)  // seed a known word
        idle(p0); cd.waitSampling(L + 2)
        val rec = mutable.ArrayBuffer[BigInt]()
        for (t <- 0 until L + 4) {
          // read addr 3 only at t==0, idle (disabled) otherwise so the value can't refill
          if (t == 0) { p0.enable #= true; p0.write #= false; p0.address #= 3; p0.mask #= 0 }
          else        { idle(p0) }
          cd.waitSampling()
          rec += p0.rdata.toBigInt
        }
        val want = model(3)
        assert(rec(L) == want, s"[w$width] latency: read landed at ${rec.indexOf(want)} != $L")
        idle(p0); cd.waitSampling(L + 2)

        // === Fill the whole array via port 0, read back through BOTH ports. =============
        for (a <- 0 until depth) write0(a, BigInt(width, rng), fullMsk)
        idle(p0); cd.waitSampling(L + 2)

        val all = 0 until depth
        for ((p, name) <- Seq((p0, "port0"), (p1, "port1"))) {
          val out = readStream(p, all)
          for (a <- all) {
            assert(out(a) == model(a), s"[w$width] $name read addr $a: ${out(a)} != ${model(a)}")
            checks += 1
          }
        }

        // === Byte masks: masked writes touch only enabled columns. =====================
        for (_ <- 0 until 200) {
          val a = rng.nextInt(depth)
          write0(a, BigInt(width, rng), BigInt(nBytes, rng)) // random byte mask
        }
        idle(p0); cd.waitSampling(L + 2)
        val maskRead = readStream(p1, all)
        for (a <- all) {
          assert(maskRead(a) == model(a), s"[w$width] masked addr $a: ${maskRead(a)} != ${model(a)}")
          checks += 1
        }

        // === Concurrency: port0 writes low half while port1 reads high half (disjoint). =
        val half = depth / 2
        val hiAddrs = half until depth
        val co = mutable.ArrayBuffer[BigInt]()
        for (t <- 0 until half + L) {
          // port 0 writes a fresh random word into the low half, one per cycle
          if (t < half) {
            val d = BigInt(width, rng)
            p0.enable #= true; p0.write #= true; p0.address #= t; p0.wdata #= d; p0.mask #= fullMsk
          } else idle(p0)
          // port 1 reads the (untouched) high half
          val ra = if (t < half) half + t else 0
          p1.enable #= true; p1.write #= false; p1.address #= ra; p1.mask #= 0
          cd.waitSampling()
          // (low-half writes aren't read back in this phase, so the model isn't updated for them)
          if (t >= L) co += p1.rdata.toBigInt
        }
        idle(p0); idle(p1)
        // port1's high-half reads must match the (unchanged) model captured before this phase
        for (i <- hiAddrs.indices) {
          assert(co(i) == model(hiAddrs(i)),
            s"[w$width] concurrent read addr ${hiAddrs(i)}: ${co(i)} != ${model(hiAddrs(i))}")
          checks += 1
        }

        simSuccess()
      }
    checks
  }

  val configs = Seq((32, 7, 3), (32, 6, 1), (64, 5, 2), (16, 5, 3))
  for ((w, a, p) <- configs) {
    val c = run(w, a, p)
    println(s"[UramSim] PASS  width=$w addrW=$a pipeNum=$p (latency=${p + 2}): $c reads bit-exact.")
  }
  println("[UramSim] all configs PASS")
}
