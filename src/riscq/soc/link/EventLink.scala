package riscq.soc.link

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SingleMapping
import riscq.soc.spec.CoreSpec

/**
 * The **up-link** (DSP → core): one narrow posted `Flow(Put)` per core — **puts into the core's
 * inbox** (specs/cross-core/02 §3.3). A channel's report is serialised into one or more 32-bit word
 * writes at the offsets of its sink's register window, so the core-side end is a plain address decode
 * into the same registers the CPU reads; nothing on the link is typed. The address is the offset from
 * `sinkBase` (`inboxAddrWidth` bits): sink `k`'s word `w` is `k·0x20 + 4·w`.
 *
 * The sink kinds (chosen per channel kind by [[EventPlan]]) and their put protocols:
 *
 *   - [[ReadoutResultSink]] (`result`) — the readout result's latched-level semantics. A settled
 *     window is three puts, `real`@+4, `imag`@+8, then `{valid = 1, res}`@+0; the level dropping (a
 *     new window opened) is one put `{valid = 0}`@+0. The `res` read halts while `valid` is low and is
 *     idempotent, so the `READOUT_LEAD` freshness contract is exactly what it was.
 *   - [[EventFifoSink]] (`fifo`) — a consume-on-read FIFO with a sequence number and the cause time,
 *     for edge-like reports. An entry is its data words @+0/+4/+8 followed by the cause `time`@+0x10,
 *     which commits it.
 *
 * Sinks sit at `0x4200 + 0x20·k` in the core's control block, k = the reporter's order among the
 * core's reporting channels; the qubit builds' single demod sink therefore keeps `res@0x4200`,
 * `real@0x4204`, `imag@0x4208`. [[EventPlan]] derives all of this from a `CoreSpec`, so the hard
 * `RiscvSoc` and the core shell agree on the layout without either naming a channel kind.
 */

/** One reporting channel's contribution: `kind` picks the sink, `data` carries the payload beats,
  * `time` (FIFO kinds) the cause time of each beat. */
case class EventSource(kind: String, data: Flow[Bits], time: Option[UInt] = None)

/** One sink of a core's control block. `base` is the CPU address of its register window, `tag` its
  * order among the core's sinks. */
case class SinkSpec(name: String, kind: String, tag: Int, base: Int, dataWidth: Int)

object EventLink {
  val sinkBase       = 0x4200
  val sinkStride     = 0x20
  val inboxAddrWidth = 12          // put offsets from sinkBase: sink k word w = k·0x20 + 4·w
  val resultKind     = "result"
  val fifoKind       = "fifo"
  val latestKind     = "latest"    // plain words written by puts (the group board)
  val mailboxKind    = "mailbox"   // one word + full flag: halting consume-on-read (release, signals)

  // the cross-core inbox registers (specs/cross-core/02 §3.3), fixed past the channels' sinks
  val boardBase      = 0x4300      // board[g] = the group words, 4 bytes each
  val releaseBase    = 0x4320      // the barrier release mailbox (the released time)
  val mailboxBase    = 0x4340      // signal mailboxes, one 0x20 window each
  val mailboxNum     = 2
  def mailboxAddr(m: Int): Int = mailboxBase + m * sinkStride
  def boardOffset: Int   = boardBase - sinkBase       // the same registers as inbox put offsets
  def releaseOffset: Int = releaseBase - sinkBase
  def mailboxOffset(m: Int): Int = mailboxAddr(m) - sinkBase

  /** The readout result's data layout: `{settled, res, real, imag}` — `settled` = 1 on the beat that
    * carries a fresh integral, 0 on the beat that reports the level dropping (a new window opened). */
  def resultDataWidth(accWidth: Int): Int = 2 + 2 * accWidth

  /** The result reporter: the decoder's `res.valid` LEVEL becomes two posted beats — one on its rising
    * edge carrying `{1, res, real, imag}`, one on its falling edge carrying `{0, …}` — so the level is
    * rebuilt at the sink with the link's delay and no beat is ever missed. */
  def resultSource(resValid: Bool, res: Bool, real: SInt, imag: SInt, accWidth: Int): EventSource = {
    val last = RegNext(resValid) init False
    val out  = Flow(Bits(resultDataWidth(accWidth) bits))
    out.valid   := resValid =/= last
    out.payload := resValid ## res ## real.asBits ## imag.asBits
    EventSource(resultKind, out)
  }

  def inboxOffset(sink: SinkSpec): Int = sink.base - sinkBase

  /**
   * One reporter's events as an ordered stream of puts into its sink: each queued event (data, and the
   * cause time for FIFO kinds) is walked word by word — `(offset, data, enable)` per kind — one put per
   * cycle, a disabled word skipped. Back-pressure only stalls the walk; a beat into a full event queue
   * is dropped (posted semantics; FIFO sinks expose it as a `seq` gap).
   */
  def serialize(src: EventSource, sink: SinkSpec, queueDepth: Int = 4): Stream[Put] = new Composite(src.data, "serialize") {
    val dataW = src.data.payload.getWidth
    val timeW = src.time.map(_.getWidth).getOrElse(0)
    val ev = Stream(Bits(dataW + timeW bits))
    ev.valid   := src.data.valid
    ev.payload := (if (timeW > 0) src.time.get.asBits ## src.data.payload else src.data.payload)
    val q    = ev.queue(queueDepth)
    val data = q.payload(0, dataW bits)
    val time = if (timeW > 0) q.payload(dataW, timeW bits) else B(0, 32 bits)

    val words: Seq[(Int, Bits, Bool)] = src.kind match {
      case `resultKind` =>
        val acc     = (dataW - 2) / 2
        val settled = data(2 * acc + 1)
        Seq((4, data(acc, acc bits), settled),           // real
            (8, data(0, acc bits), settled),             // imag
            (0, data(2 * acc, 2 bits), True))            // {valid, res} — last, so real/imag are in place
      case `fifoKind` =>
        val n = (dataW + 31) / 32
        require(n <= 3, "a fifo sink serves at most three data words")
        val padded = data.resize(32 * n)
        (0 until n).map(i => (4 * i, padded(32 * i, 32 bits), True)) :+ (0x10, time.resize(32), True)
    }
    val offV = Vec(words.map(w => U(w._1 + inboxOffset(sink), inboxAddrWidth bits)))
    val datV = Vec(words.map(_._2.resize(32)))
    val enV  = Vec(words.map(_._3))
    val idx  = Reg(UInt(log2Up(words.length) bits)) init 0
    val last = idx === words.length - 1

    val out = Stream(Put(inboxAddrWidth))
    out.valid           := q.valid && enV(idx)
    out.payload.address := offV(idx)
    out.payload.data    := datV(idx)
    val advance = q.valid && (!enV(idx) || out.ready)
    when(advance)(idx := (last ? U(0) | idx + 1).resized)
    q.ready := advance && last
  }.out

  /** Merge the reporters' put streams onto the one up-link `Flow(Put)`: one reporter is its serialiser
    * alone; several share a round-robin arbiter (a put is self-contained, so no lock is needed). */
  def merge(sources: Seq[EventSource], sinks: Seq[SinkSpec], queueDepth: Int = 4,
            extra: Seq[Stream[Put]] = Nil): Flow[Put] = {
    require(sources.length == sinks.length, "one sink per reporter")
    val streams = sources.zip(sinks).map { case (s, sk) => serialize(s, sk, queueDepth) } ++ extra
    val merged  = if (streams.length == 1) streams.head else StreamArbiterFactory().roundRobin.noLock.on(streams)
    merged.ready := True
    val out = Flow(Put(inboxAddrWidth))
    out.valid   := merged.valid
    out.payload := merged.payload
    out
  }
}

/**
 * What a core's up-link and control block look like for its channel list: which channels report, with
 * which sink kind and payload width. The kind table is the only place a channel kind is tied to a sink
 * kind.
 */
case class EventPlan(spec: CoreSpec, readoutAccWidth: Int = 32, groups: Int = riscq.soc.spec.SocSpecMap.groupNodes) {
  private val kinds: Map[String, (String, Int)] = Map(
    "demod" -> (EventLink.resultKind, EventLink.resultDataWidth(readoutAccWidth)),
    "dio"   -> (EventLink.fifoKind, 32))                 // {changed[15:0], levels[15:0]} + cause time
  val reporters: Seq[(Int, String, String, Int)] =   // (channel index, name, sink kind, data width)
    spec.channels.zipWithIndex.collect { case (ch, k) if kinds.contains(ch.kind) =>
      val (sk, dw) = kinds(ch.kind); (k, ch.name, sk, dw) }
  val sinks: Seq[SinkSpec] = reporters.zipWithIndex.map { case ((_, name, sk, dw), t) =>
    SinkSpec(name, sk, t, EventLink.sinkBase + t * EventLink.sinkStride, dw) }
  def channelOf(sink: SinkSpec): Int = reporters(sink.tag)._1
  require(sinks.length <= 8, "the channels' sinks must stay below the cross-core inbox registers at 0x4300")
  /** The cross-core inbox registers every core has (specs/cross-core/02 §3.3): the group board, the
    * barrier release mailbox and the signal mailboxes. `tag` continues the sink numbering. */
  val xcoreSinks: Seq[SinkSpec] = {
    val t0 = sinks.length
    Seq(SinkSpec("board", EventLink.latestKind, t0, EventLink.boardBase, 32 * groups),
        SinkSpec("release", EventLink.mailboxKind, t0 + 1, EventLink.releaseBase, 32)) ++
      (0 until EventLink.mailboxNum).map(m => SinkSpec(s"mbox$m", EventLink.mailboxKind, t0 + 2 + m, EventLink.mailboxAddr(m), 32))
  }
  val allSinks: Seq[SinkSpec] = sinks ++ xcoreSinks
}

/** The address decode every sink shares: `mine` for a put into this sink's `0x20` window, `word` its
  * word index. */
private[link] class InboxWindow(resultIn: Flow[Put], base: Int) {
  private val off = EventLink.inboxOffset(SinkSpec("", "", 0, base, 0))
  require(off % EventLink.sinkStride == 0)
  val mine = resultIn.valid && (resultIn.payload.address >> 5) === (off >> 5)
  val word = resultIn.payload.address(2, 3 bits)
  val data = resultIn.payload.data
}

/**
 * Core-side readout-result register — the `result` sink. It rebuilds the decoder's `res.valid` level
 * from the posted puts (`{valid, res}`@+0, `real`@+4, `imag`@+8; a settled window writes all three,
 * `valid` last). Because the level is low exactly during the next window's integration, a `res` read
 * that races a fresh window halts until it settles — the local-halt contract, no `arm`, an
 * **idempotent** read.
 *
 * Freshness is a software timing contract (not a hardware clear): the level holds the *previous*
 * window's result high through the `LEAD` gap between a `play` and the window opening, so software
 * waits past the window's opening (`READOUT_LEAD`) before reading — past `winStart` the stale level
 * has dropped, so the halting read can only return the new window (`specs/new-readout-decoder` §2.4).
 */
case class ReadoutResultSink(accWidth: Int, base: Int = EventLink.sinkBase) extends Area {
  val resultIn = Flow(Put(EventLink.inboxAddrWidth))
  val in = new InboxWindow(resultIn, base)

  val valid = Reg(Bool()) init False
  val res   = Reg(Bool()) init False
  val real  = Reg(SInt(accWidth bits)) init 0
  val imag  = Reg(SInt(accWidth bits)) init 0
  when(in.mine) {
    switch(in.word) {
      is(0) { valid := in.data(1); res := in.data(0) }
      is(1) { real := in.data(0, accWidth bits).asSInt }
      is(2) { imag := in.data(0, accWidth bits).asSInt }
    }
  }

  /** Local read map: `res`@base (HALTS until the integral has settled), `real`@+4, `imag`@+8. */
  def mapping(factory: tilelink.SlaveFactory): Unit = {
    factory.read(res, base)
    factory.onReadPrimitive(SingleMapping(base), haltSensitive = false, null) {
      when(!valid)(factory.readHalt())
    }
    factory.read(real, base + 4)
    factory.read(imag, base + 8)
  }
}

/**
 * Core-side FIFO sink for edge-like reports: an entry's data words arrive as puts @+0/+4/+8 and its
 * cause `time`@+0x10 commits it, with a running sequence number. `pop`@base is a **halting,
 * consuming** read (halts until an event is queued, returns its first data word and pops it); the
 * popped event's remaining words stay readable at +4/+8 (data words 1, 2), `time`@+0x10, `seq`@+0x14;
 * `count`@+0x18 is the occupancy (non-halting). A gap in `seq` means an event was dropped upstream (a
 * full queue at the reporter).
 */
case class EventFifoSink(dataWidth: Int, base: Int, depth: Int = 8, timeWidth: Int = 32) extends Area {
  require(dataWidth <= 96, "an event sink serves at most three data words")
  val resultIn = Flow(Put(EventLink.inboxAddrWidth))
  val in = new InboxWindow(resultIn, base)

  case class Entry() extends Bundle {
    val data = Bits(dataWidth bits)
    val time = UInt(timeWidth bits)
    val seq  = UInt(32 bits)
  }
  val nWords = (dataWidth + 31) / 32
  val staged = Vec(Reg(Bits(32 bits)) init 0, nWords)     // data words of the entry being assembled
  val commit = in.mine && in.word === 4                     // the time put (+0x10) completes an entry
  for (i <- 0 until nWords) when(in.mine && in.word === i)(staged(i) := in.data)

  val seqCounter = Reg(UInt(32 bits)) init 0
  val push = Stream(Entry())
  push.valid := commit
  push.payload.data := staged.asBits.resize(dataWidth)
  push.payload.time := in.data.asUInt.resize(timeWidth)
  push.payload.seq  := seqCounter
  when(commit)(seqCounter := seqCounter + 1)
  val fifo = StreamFifo(Entry(), depth)
  fifo.io.push << push.toFlow.toStream   // drop on full (posted semantics); seq gaps expose it
  val last = Reg(Entry())
  fifo.io.pop.ready := False

  private def word(bits: Bits, k: Int): Bits = {
    val padded = bits.resize(96)
    padded(32 * k, 32 bits)
  }

  def mapping(factory: tilelink.SlaveFactory): Unit = {
    factory.read(word(fifo.io.pop.payload.data, 0), base)
    factory.onReadPrimitive(SingleMapping(base), haltSensitive = false, null) {
      when(!fifo.io.pop.valid)(factory.readHalt())
    }
    factory.onReadPrimitive(SingleMapping(base), haltSensitive = true, null) {
      fifo.io.pop.ready := True
      last := fifo.io.pop.payload
    }
    factory.read(word(last.data, 1), base + 4)
    factory.read(word(last.data, 2), base + 8)
    factory.read(last.time, base + 0x10)
    factory.read(last.seq, base + 0x14)
    factory.read(fifo.io.occupancy, base + 0x18)
  }
}

/**
 * `latest`-kind inbox registers: `words` plain 32-bit words at `base`, each overwritten by the put to
 * its offset, read back non-halting. The group board (`board[g]`, specs/cross-core/02 §3.3): the hub
 * re-puts a group's whole shared word after every publish, so `remote(g, slot)` is one load and a shift.
 */
case class LatestSink(words: Int, base: Int) extends Area {
  require(words <= 8, "a latest sink is one 0x20 window")
  val resultIn = Flow(Put(EventLink.inboxAddrWidth))
  val in = new InboxWindow(resultIn, base)
  val regs = Vec(Reg(Bits(32 bits)) init 0, words)
  for (i <- 0 until words) when(in.mine && in.word === i)(regs(i) := in.data)
  def mapping(factory: tilelink.SlaveFactory): Unit =
    for (i <- 0 until words) factory.read(regs(i), base + 4 * i)
}

/**
 * `mailbox`-kind inbox register: one word plus a full flag. A put to `base` fills it; the read at
 * `base` **halts** while empty and **consumes** (clears the flag). The barrier release (one per core —
 * a halted core waits on one barrier at a time) and the signal mailboxes (one sender each; the
 * address says who) are this kind. A second put before the read overwrites the word; the flag stays.
 */
case class MailboxSink(base: Int) extends Area {
  val resultIn = Flow(Put(EventLink.inboxAddrWidth))
  val in = new InboxWindow(resultIn, base)
  val data = Reg(Bits(32 bits)) init 0
  val full = Reg(Bool()) init False
  when(in.mine && in.word === 0) { data := in.data; full := True }
  def mapping(factory: tilelink.SlaveFactory): Unit = {
    factory.read(data, base)
    factory.onReadPrimitive(SingleMapping(base), haltSensitive = false, null) {
      when(!full)(factory.readHalt())
    }
    factory.onReadPrimitive(SingleMapping(base), haltSensitive = true, null) {
      full := False
    }
  }
}
