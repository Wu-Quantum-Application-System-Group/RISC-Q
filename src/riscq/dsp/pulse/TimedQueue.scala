package riscq.dsp.pulse

import spinal.core._
import spinal.lib._

/** One queued parameter update: the `data` to apply and the `startTime` it must land at the DAC. */
case class TimedEntry[T <: Data](dataType: HardType[T], timeWidth: Int) extends Bundle {
  val data      = dataType()
  val startTime = UInt(timeWidth bits)
}

/** Shared IO for every TimedQueue implementation (RISC-Q [[TimedQueue]] + the variants), so one
 *  testbench body can drive any of them: external `time`, a `Stream` push of `{data, startTime}`,
 *  and a `Flow` pop that fires once when the head is due. */
case class TimedQueueIo[T <: Data](dataType: HardType[T], timeWidth: Int) extends Bundle {
  val time = in port UInt(timeWidth bits)                  // external SoC batch-time counter
  val push = slave port Stream(TimedEntry(dataType, timeWidth))
  val pop  = master port Flow(dataType)                    // fires once, when the head is due
}

/** Common supertype of all TimedQueue implementations — exposes the shared [[TimedQueueIo]] and the
 *  exported pop `latency`, so testbenches can drive any of them uniformly. */
trait TimedQueueLike[T <: Data] extends Component {
  def io: TimedQueueIo[T]
  def latency: Int
}

/**
 * Implementation selector for [[TimedQueue]]. All four share the same io and external contract —
 * the pop `Flow` fires '''once''' per entry at exactly `time = startTime − leadTime − timeOffset`
 * (wrap-safe), provided the entry was pushed ≥ `leadTime + 3` cycles before its `startTime` (the
 * software LEAD margin); backpressured `Stream` push; external latency 0. They differ in FIFO
 * storage and due-test structure, i.e. in '''replication congestion cost''' (control sets, FFs)
 * and back-to-back drain rate (II — irrelevant for the well-spaced schedules the software
 * contract guarantees; malformed schedules still drain, one entry per II cycles).
 *
 * Post-route on a 48-queue bank (xczu49dr, depth 4), per queue vs [[TimedQueueImpl.RegHead]]:
 *
 * {{{
 *   impl        II   control sets      FF     LUT    storage         note
 *   RegHead      2   (ref: ~2.9/q,     ref    ref    LUTRAM          the deployed default
 *                     81 tiny sets/48q)
 *   Srl          2   −1.7/q, tiny→0    ~=     ~=     SRL             same FSM; watch the SRL tap→head path
 *   Shadow       3   −0.8/q            −12/q  +23/q  LUTRAM          CE-free shadow due register
 *   SrlShadow    3   −2.5/q, tiny→0    −15/q  +18/q  SRL             the congestion-leanest compose
 * }}}
 *
 * At depth 32 the LUTRAM is depth-free (RAM32 primitives), so the `Srl*` LUT premium grows
 * (~+50 %) while their control-set/FF wins persist. Every impl is verified cycle-exactly:
 * [[riscq.dsp.pulse.sim.TimedQueueSim]] (RegHead), [[riscq.dsp.pulse.sim.TimedQueueImplSim]]
 * (Srl / Shadow / SrlShadow).
 */
sealed trait TimedQueueImpl
object TimedQueueImpl {

  /** The RISC-Q-style deployed default: async-read `StreamFifo` (distributed-RAM payload), an
   *  `m2sPipe` head stage, a '''registered''' 3-operand due compare (`time + const − startTime`)
   *  and a 1-cycle fire-once blank. II=2. The `useVec`/`forFMax`/`regHead` knobs of [[TimedQueue]]
   *  select sub-options of this impl only. */
  case object RegHead extends TimedQueueImpl

  /** RegHead's exact pop FSM (m2sPipe head + registered compare + blank, II=2), with two changes:
   *  the FIFO stores the '''deadline precomputed at push''' (2-operand due subtract), and storage
   *  is an [[SrlFifo]] shift array — no LUTRAM, no FIFO pointers, so the per-queue pointer
   *  clock-enables (the tiny, CLB-fragmenting control sets) vanish. The SRL dynamic-tap read into
   *  the head stage is the one path to watch at very high clocks. */
  case object Srl extends TimedQueueImpl

  /** No head pipe stage: a '''CE-free, reset-free shadow register''' tracks the FIFO head's
   *  precomputed deadline one cycle behind, and the registered due compare reads the shadow — the
   *  `ptr → LUTRAM → compare` cone is still cut at a register at a fraction of the m2sPipe area
   *  (−12 FF and −1 control set per queue). After a pop the shadow is stale one extra cycle, so
   *  the fire-once blank stretches to two cycles: II=3. Pop data reads the LUTRAM directly. */
  case object Shadow extends TimedQueueImpl

  /** [[Shadow]]'s FSM over [[SrlFifo]] storage — the congestion-leanest compose: every flip-flop
   *  is CE-free or reset-free (no unique control sets beyond the shared reset), −15 FF/queue.
   *  II=3. */
  case object SrlShadow extends TimedQueueImpl
}

/** Internal FIFO payload for the deadline-precompute impls: data + `startTime − (lead+offset+1)`,
 *  the constant subtract folded in at push so the per-cycle due test is a single 2-operand
 *  subtract. (Also used by [[TimedQueueDeadline]] in `TimedQueueVariants.scala`.) */
case class TimedStored[T <: Data](dataType: HardType[T], timeWidth: Int) extends Bundle {
  val data     = dataType()
  val deadline = UInt(timeWidth bits)
}

/**
 * Minimal shift-register FIFO — the storage of the `Srl*` impls. On push every slot shifts
 * (slot 0 takes the new entry); the oldest entry sits at the dynamic tap `slots(count − 1)`,
 * '''async''' like `StreamFifo`'s LUTRAM read. The slots carry no reset and share one write
 * enable — the Xilinx dynamic-SRL idiom (SRL16E/SRLC32E, depth ≤ 32) — so the storage costs no
 * flip-flop control sets and there are no FIFO pointers, only the CE-free occupancy count.
 * `io.pop` is the tap `Stream` (payload/valid combinational, consumed by `ready`).
 */
case class SrlFifo[T <: Data](dataType: HardType[T], depth: Int) extends Component {
  require(depth >= 1 && depth <= 32, "SrlFifo targets single-level SRL16E/SRLC32E storage")
  val io = new Bundle {
    val push = slave port Stream(dataType())
    val pop  = master port Stream(dataType())
  }
  val slots = Vec.fill(depth)(Reg(dataType()))
  val count = Reg(UInt(log2Up(depth + 1) bits)) init 0

  io.push.ready := count =/= depth
  val doPush = io.push.fire
  when(doPush) {
    slots(0) := io.push.payload
    for (i <- 1 until depth) slots(i) := slots(i - 1)
  }
  io.pop.valid   := count =/= 0
  io.pop.payload := slots((count - 1).resize(log2Up(depth)))
  count := count + U(doPush) - U(io.pop.fire)
}

/**
 * Lead-time scheduler — latches `startTime` at enqueue and fires the pop `Flow` once at exactly
 * `time = startTime − leadTime − timeOffset`, decoupling enqueue-arrival-time from fire-time (the
 * property the posted-link SoC architecture is built on). `leadTime` is the consumer path's
 * latency to the DAC, so the entry is popped that many cycles early and lands at the DAC at
 * `startTime`. Push is a `Stream` so Tilelink glue sees backpressure when full.
 *
 * '''Wrap window — ±2^(timeWidth−1) only.''' The due test is a SIGNED difference of the (free-running,
 * never-reset) `time` and `startTime` (`!(time + leadC1 − startTime).msb`), so it is correct ONLY when
 * the schedule is within ±2^(timeWidth−1) of now — ample for a real near-future `startTime` (a fire is
 * ~`leadTime` ahead of `time`). The trap: an ABSOLUTE `startTime` far from `time` is NOT "immediately
 * due". With `startTime = 0` and `time` in the UPPER half of its range (2^(timeWidth−1) ≤ time), the
 * value `time + leadC1 − 0` has its msb set, so the entry reads as ~2^(timeWidth−1) in the FUTURE,
 * never pops within the run, and its consumer (e.g. the pulse-generator phasor regen) silently keeps
 * its old value. `time = PulseTableSoc.refTime + timeOffset` free-runs and is never reset, so it sits
 * in the upper half ~half the time on hardware (yet is ≈0 for a whole co-sim). Rule: schedule freq /
 * params against a near-`now()` time (`init_pulse_params` uses `set_start(ch, now())`), never an
 * absolute 0. See docs/soc/SOC_TIPS.md §5.
 *
 * `impl` selects the microarchitecture — see [[TimedQueueImpl]] for the four options and their
 * congestion/II trade; the default [[TimedQueueImpl.RegHead]] is the RISC-Q-style deployed
 * implementation:
 *
 * {{{
 *   due    = ! ( (time + (leadTime + timeOffset + 1) − startTime) .msb )   // wrap-safe geq, +1 below
 *   timeUp = RegNext(head.valid && due)                                    // registered 32-bit compare
 *   blank  = RegNext(pop.valid)                                            // fire-once / drain blank
 *   pop    = timeUp && !blank                                              // lean 2-FF form
 * }}}
 *
 * The registered compare stays off the combinational `pop.valid → consumer` path; the `+1` (and
 * for the precompute impls, the deadline constant) cancels the compare register's cycle, so the
 * pop still lands at exactly `time = startTime − leadTime` (external [[latency]] 0). The lean pop
 * form drops the redundant `head.valid` term: the sims assert the DUT
 * matches the ORIGINAL expression every cycle, which is that bit-exactness proof. The m2sPipe
 * head stage (`regHead`, default on) makes the compare read `startTime` from a
 * register instead of pointer→LUTRAM; every impl keeps that property, and every impl shares the
 * same `lead + 3` exact-pop push margin.
 *
 * RegHead-only storage knobs (must stay at their defaults for the other impls): `useVec` — false
 * (default) puts the FIFO payload in a distributed-RAM `Mem` (fewest control sets), true is a
 * register array (higher fmax); `forFMax` — false (default) plain binary FIFO pointers, true the
 * fmax-tuned gray/empty-tracker; `regHead` — the registered head stage (default on; `false` recovers the
 * direct async-read compare, one fewer register but a 6-level due cone).
 */
case class TimedQueue[T <: Data](
    dataType: HardType[T],
    timeWidth: Int,
    depth: Int,
    leadTime: Int,
    timeOffset: Int = 0,
    useVec: Boolean = false,  // RegHead only — FIFO storage: Mem (default) vs register array
    forFMax: Boolean = false, // RegHead only — FIFO pointers: binary (default) vs gray/empty-tracker
    regHead: Boolean = true,  // RegHead only — registered head stage (default on)
    impl: TimedQueueImpl = TimedQueueImpl.RegHead
) extends Component with TimedQueueLike[T] {
  require(depth >= 1, "queue needs at least one entry")
  require(leadTime + timeOffset >= 0, "lead time must be non-negative")
  require((leadTime + timeOffset) < (BigInt(1) << timeWidth), "lead time must fit in timeWidth bits")
  require(impl == TimedQueueImpl.RegHead || (!useVec && !forFMax && regHead),
    "useVec/forFMax/regHead are RegHead-only knobs — leave them at their defaults for other impls")

  val io = TimedQueueIo(dataType, timeWidth)

  // wrap-safe geq with a +1 so the registered pop still lands at time = startTime − leadTime; the
  // precompute impls fold the same constant into the stored deadline at push.
  val leadC1 = U(leadTime + timeOffset + 1, timeWidth bits)

  /** push side of the deadline-precompute impls: {data, startTime − (lead+offset+1)} at push. */
  private def pushPrecomputed(fifoPush: Stream[TimedStored[T]]): Unit = {
    fifoPush.valid            := io.push.valid
    fifoPush.payload.data     := io.push.payload.data
    fifoPush.payload.deadline := io.push.payload.startTime - leadC1
    io.push.ready             := fifoPush.ready
  }

  /** pop side shared by RegHead/Srl: registered compare on the m2sPipe head + 1-cycle blank. */
  private def popRegistered(head: Stream[_], due: Bool, data: T): Unit = {
    val timeUp = RegNext(head.valid && due) init False // registered compare (off pop path)
    val blank  = RegNext(io.pop.valid) init False      // fire-once / drain blank
    io.pop.valid   := timeUp && !blank                 // lean form (bit-exact; see the sims)
    io.pop.payload := data
    head.ready     := io.pop.valid
  }

  /** pop side shared by Shadow/SrlShadow: CE-free shadow of the head's deadline + 2-cycle blank. */
  private def popShadow(head: Stream[TimedStored[T]]): Unit = {
    val shadowDl    = RegNext(head.payload.deadline)   // CE-free, reset-free: no control set
    val shadowValid = RegNext(head.valid) init False
    val due    = !(io.time - shadowDl).msb
    val timeUp = RegNext(shadowValid && due) init False
    val blank  = RegNext(io.pop.valid) init False      // pop + 1: head advanced, shadow stale
    val blank2 = RegNext(blank) init False             // pop + 2: timeUp still from the stale shadow
    io.pop.valid   := timeUp && !blank && !blank2
    io.pop.payload := head.payload.data                // async storage read, consumed at pop
    head.ready     := io.pop.valid
  }

  val logic = impl match {
    case TimedQueueImpl.RegHead => new Area {
      val fifo = new StreamFifo(TimedEntry(dataType, timeWidth), depth,
        withAsyncRead = true, forFMax = forFMax, useVec = useVec)
      fifo.io.push << io.push
      // regHead: the m2sPipe stage registers valid + payload, so the due compare starts at a
      // FF instead of reaching through pointer→LUTRAM; it refills from the FIFO's async read in
      // the same cycle it is popped, so drain throughput is unchanged (II=2).
      val head = if (regHead) fifo.io.pop.m2sPipe() else fifo.io.pop
      val due  = !(io.time + leadC1 - head.payload.startTime).msb
      popRegistered(head, due, head.payload.data)
    }

    case TimedQueueImpl.Srl => new Area {
      val fifo = SrlFifo(TimedStored(dataType, timeWidth), depth)
      pushPrecomputed(fifo.io.push)
      val head = fifo.io.pop.m2sPipe()
      val due  = !(io.time - head.payload.deadline).msb // 2-operand subtract on the precomputed deadline
      popRegistered(head, due, head.payload.data)
    }

    case TimedQueueImpl.Shadow => new Area {
      val fifo = new StreamFifo(TimedStored(dataType, timeWidth), depth, withAsyncRead = true)
      pushPrecomputed(fifo.io.push)
      popShadow(fifo.io.pop)
    }

    case TimedQueueImpl.SrlShadow => new Area {
      val fifo = SrlFifo(TimedStored(dataType, timeWidth), depth)
      pushPrecomputed(fifo.io.push)
      popShadow(fifo.io.pop)
    }
  }

  /** external cmd→pop latency: 0 (the +1 compensates the compare register). */
  def latency: Int = 0
}
