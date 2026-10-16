package riscq.dsp.pulse.sim

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import riscq.dsp._
import riscq.dsp.pulse._

/**
 * Self-checking testbench for [[EnvelopeReader]]. A small test top wraps the reader around a
 * preloaded `Mem` wired with exactly `memLatency` cmd→rsp cycles; each address holds a word with
 * `re_0 = addr` (so a lane decodes the address) plus distinct per-lane re/im patterns. We drive a
 * load/auto-increment schedule, capture `io.env` every cycle, and against a Scala model of the
 * address register verify: the measured pipeline latency equals the exported `latency`
 * (= memLatency + 2), the address sequence after a load auto-increments, and every lane's re/im
 * matches the word layout. Run with `mill runMain riscq.dsp.pulse.sim.EnvelopeReaderSim`.
 */
object EnvelopeReaderSim extends App {

  /** test top: EnvelopeReader + a preloaded Mem with a `memLatency`-cycle read path. */
  case class Tb(p: EnvelopeReaderParams, content: Seq[BigInt]) extends Component {
    val io = new Bundle {
      val addr = slave port Flow(UInt(p.addrWidth bits))
      val env  = out port ComplexBatch(p.batchSize, p.dataWidth)
    }
    val reader = EnvelopeReader(p)
    reader.io.addr << io.addr
    io.env := reader.io.env
    val mem = Mem.fill(content.length)(Bits(p.wordWidth bits)) init (content.map(c => B(c, p.wordWidth bits)))
    // external read path: sync read (1 cycle) + (memLatency-1) extra regs = memLatency total.
    reader.io.memPort.rsp := Delay(mem.readSync(reader.io.memPort.cmd.payload), p.memLatency - 1)
  }

  def wmask(w: Int): BigInt = (BigInt(1) << w) - 1
  def signedW(pat: BigInt, w: Int): BigInt = if (pat.testBit(w - 1)) pat - (BigInt(1) << w) else pat

  // per-address, per-lane content patterns (re_0 = addr identifies the address; rest are distinct).
  def reK(a: Int, k: Int, w: Int): BigInt = (if (k == 0) BigInt(a) else BigInt(a * 5 + k * 11 + 3)) & wmask(w)
  def imK(a: Int, k: Int, w: Int): BigInt = BigInt(a * 7 + k * 13 + 1) & wmask(w)

  def run(p: EnvelopeReaderParams, sched: Int => Option[Int], cycles: Int): Unit = {
    val w   = p.dataWidth
    val n   = p.batchSize
    val nA  = 1 << p.addrWidth
    val mask = nA - 1
    val content = (0 until nA).map { a =>
      var word = BigInt(0)
      for (k <- 0 until n) {
        word |= reK(a, k, w) << (2 * k * w)
        word |= imK(a, k, w) << ((2 * k + 1) * w)
      }
      word
    }

    // captured env per cycle: env(c)(k) = (re, im)
    val cap = Array.ofDim[(BigInt, BigInt)](cycles, n)

    SimConfig.compile(Tb(p, content)).doSim(s"env-w${w}n${n}-mem${p.memLatency}", seed = 42) { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.addr.valid #= false
      dut.clockDomain.waitSampling(2)
      for (c <- 0 until cycles) {
        sched(c) match {
          case Some(a) => dut.io.addr.valid #= true; dut.io.addr.payload #= a
          case None    => dut.io.addr.valid #= false
        }
        dut.clockDomain.waitSampling()
        for (k <- 0 until n)
          cap(c)(k) = (dut.io.env(k).re.toBigInt, dut.io.env(k).im.toBigInt)
      }
    }

    // model address register: R(c) = cmd address during cycle c. R(0) is the (unknown) free-run warmup
    // value — but sched pins a load at cycle 0, so R(1..) is fully determined (load else +1).
    val R = Array.ofDim[Int](cycles + 1)
    R(0) = -1 // unknown warmup value; only ever referenced at c-L = 0, which we skip
    for (c <- 0 until cycles) R(c + 1) = sched(c).map(_ & mask).getOrElse((R(c) + 1) & mask)

    // find the cmd→env pipeline lag L vs the (post-addrReg) command address R: env(0).re == R(c-L).
    // R already absorbs the addrReg register (R(c+1) = f(io.addr[c])), so L = mem + out reg =
    // memLatency + 1; the exported io.addr→env latency adds that addrReg back: L + 1 = memLatency + 2.
    val L = (0 to 8).find(l => ((l + 1) until cycles).forall(c => cap(c)(0)._1 == BigInt(R(c - l)))).getOrElse(-1)
    val exported = p.memLatency + 2 // EnvelopeReader.latency = 1 (addr reg) + memLatency + 1 (out reg)
    assert(L == p.memLatency + 1 && L + 1 == exported,
      s"[env mem=${p.memLatency}] cmd→env lag $L (expected ${p.memLatency + 1}); io.addr→env = ${L + 1} vs exported $exported")

    // full lane / re-im alignment vs the model, every settled cycle.
    for (c <- (L + 1) until cycles; k <- 0 until n) {
      val a = R(c - L)
      assert(cap(c)(k)._1 == signedW(reK(a, k, w), w),
        s"[env mem=${p.memLatency}] cycle $c lane $k re ${cap(c)(k)._1} != ${signedW(reK(a, k, w), w)} (addr $a)")
      assert(cap(c)(k)._2 == signedW(imK(a, k, w), w),
        s"[env mem=${p.memLatency}] cycle $c lane $k im ${cap(c)(k)._2} != ${signedW(imK(a, k, w), w)} (addr $a)")
    }
    println(f"[EnvelopeReaderSim] PASS  w=$w n=$n addr=${p.addrWidth} memLatency=${p.memLatency}: " +
      f"latency=$exported (cmd→env lag $L + addrReg), address sequence + lane/re-im layout verified over $cycles cycles.")
  }

  // pin the address at c=0 (addr 50), auto-increment, reload at c=20 (addr 10) and c=45 (addr 60).
  val sched: Int => Option[Int] = c =>
    if (c == 0) Some(50) else if (c == 20) Some(10) else if (c == 45) Some(60) else None

  run(EnvelopeReaderParams(batchSize = 4, dataWidth = 8, addrWidth = 6, memLatency = 2), sched, 80)
  run(EnvelopeReaderParams(batchSize = 4, dataWidth = 8, addrWidth = 6, memLatency = 1), sched, 80)
  run(EnvelopeReaderParams(batchSize = 8, dataWidth = 12, addrWidth = 6, memLatency = 2), sched, 80)
  println("[EnvelopeReaderSim] all configs PASS")
}
