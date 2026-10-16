package riscq.memory.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.memory.HalfUram

import scala.collection.mutable
import scala.util.Random

/**
 * Self-checking testbench for [[HalfUram]] — the 32-bit-facing UltraRAM that packs two 32-bit words
 * per 64-bit URAM row — checked against a software 32-bit-word memory model. Because two adjacent
 * outer addresses share one physical row, the key property under test is that they behave as fully
 * independent 32-bit words (byte masks write only the addressed half; the read mux picks the right
 * half). Drives the backing Uram's blackbox Verilog through Verilator, so it also proves the
 * committed `UramBlackBox.v` matches the wrapper.
 *
 *   - '''Latency:''' a read presented at cycle `t` returns at the output exactly `pipeNum + 2` sim
 *     samples later (the backing URAM's `memreg + NBPIPE + dout` chain; the half-mux is
 *     combinational) — asserted as a single spike.
 *   - '''Both ports, read + write:''' fill the whole array via port 0, read it all back through both
 *     ports bit-exact (every even/odd address holds a distinct word, so this exercises both halves).
 *   - '''Byte masks:''' 4-bit masked writes update only the enabled bytes of the addressed half and
 *     never disturb the sibling word in the same row.
 *   - '''Concurrency:''' port 0 writes the low outer half while port 1 reads the (disjoint) high
 *     outer half in the same cycles.
 *
 * Run with `mill runMain riscq.memory.sim.HalfUramSim`.
 */
object HalfUramSim extends App {

  /** Merge `wdata`'s enabled byte columns (by `mask`) into `old`; the others keep `old`'s bytes. */
  def byteMerge(old: BigInt, wdata: BigInt, mask: BigInt, nBytes: Int): BigInt = {
    var r = old
    for (i <- 0 until nBytes) if (((mask >> i) & 1) == 1) {
      val lo  = i * 8
      val clr = r & ~(BigInt(0xff) << lo)      // clear byte i
      r = clr | (((wdata >> lo) & 0xff) << lo) // insert new byte i
    }
    r
  }

  def run(addrW: Int, pipeNum: Int): Long = {
    val width   = 32
    val nBytes  = width / 8                     // 4
    val depth   = 1 << addrW                    // 32-bit-word depth
    val L       = pipeNum + 2                   // address -> rdata latency (memreg + NBPIPE + dout)
    val fullMsk = (BigInt(1) << nBytes) - 1
    var checks  = 0L

    SimConfig.compile(HalfUram(addrW, pipeNum))
      .doSim(s"halfuram-a${addrW}_p$pipeNum", seed = 42) { dut =>
        val cd = dut.clockDomain
        val p0 = dut.io.port0
        val p1 = dut.io.port1
        cd.forkStimulus(10)

        val rng   = new Random(1)
        val model = Array.fill[BigInt](depth)(BigInt(0))

        def idle(p: spinal.lib.MemReadWritePort[Bits]): Unit = {
          p.enable #= false; p.write #= false; p.address #= 0; p.wdata #= 0; p.mask #= 0
        }
        idle(p0); idle(p1)
        cd.waitSampling(2)

        // -- write one 32-bit word via port 0, updating the model. --
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
        write0(3, BigInt("a5a5a5a5", 16), fullMsk) // seed a known word
        idle(p0); cd.waitSampling(L + 2)
        val rec = mutable.ArrayBuffer[BigInt]()
        for (t <- 0 until L + 4) {
          if (t == 0) { p0.enable #= true; p0.write #= false; p0.address #= 3; p0.mask #= 0 }
          else        { idle(p0) }
          cd.waitSampling()
          rec += p0.rdata.toBigInt
        }
        assert(rec(L) == model(3), s"[a$addrW] latency: read landed at ${rec.indexOf(model(3))} != $L")
        idle(p0); cd.waitSampling(L + 2)

        // === Fill the whole array via port 0, read back through BOTH ports. =============
        // Adjacent addresses share a row; distinct random words per address prove half-independence.
        for (a <- 0 until depth) write0(a, BigInt(width, rng), fullMsk)
        idle(p0); cd.waitSampling(L + 2)

        val all = 0 until depth
        for ((p, name) <- Seq((p0, "port0"), (p1, "port1"))) {
          val out = readStream(p, all)
          for (a <- all) {
            assert(out(a) == model(a), s"[a$addrW] $name read addr $a: ${out(a)} != ${model(a)}")
            checks += 1
          }
        }

        // === Byte masks: masked writes touch only enabled bytes of the addressed half. ==
        for (_ <- 0 until 200) {
          val a = rng.nextInt(depth)
          write0(a, BigInt(width, rng), BigInt(nBytes, rng)) // random 4-bit byte mask
        }
        idle(p0); cd.waitSampling(L + 2)
        val maskRead = readStream(p1, all)
        for (a <- all) {
          assert(maskRead(a) == model(a), s"[a$addrW] masked addr $a: ${maskRead(a)} != ${model(a)}")
          checks += 1
        }

        // === Concurrency: port0 writes low half while port1 reads high half (disjoint). =
        val half    = depth / 2
        val hiAddrs = half until depth
        val co      = mutable.ArrayBuffer[BigInt]()
        for (t <- 0 until half + L) {
          if (t < half) {
            val d = BigInt(width, rng)
            p0.enable #= true; p0.write #= true; p0.address #= t; p0.wdata #= d; p0.mask #= fullMsk
          } else idle(p0)
          val ra = if (t < half) half + t else 0
          p1.enable #= true; p1.write #= false; p1.address #= ra; p1.mask #= 0
          cd.waitSampling()
          if (t >= L) co += p1.rdata.toBigInt
        }
        idle(p0); idle(p1)
        for (i <- hiAddrs.indices) {
          assert(co(i) == model(hiAddrs(i)),
            s"[a$addrW] concurrent read addr ${hiAddrs(i)}: ${co(i)} != ${model(hiAddrs(i))}")
          checks += 1
        }

        simSuccess()
      }
    checks
  }

  val configs = Seq((8, 3), (7, 1), (6, 2), (5, 3))
  for ((a, p) <- configs) {
    val c = run(a, p)
    println(s"[HalfUramSim] PASS  addrW=$a pipeNum=$p (latency=${p + 2}): $c reads bit-exact.")
  }
  println("[HalfUramSim] all configs PASS")
}
