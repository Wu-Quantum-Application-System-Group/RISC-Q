package riscq.soc.link

import spinal.core._
import spinal.lib._
import riscq.soc.spec.SocSpecMap

/** One beat of the hub's ordered broadcast: the put every core's inbox may receive, plus the
  * destination `mask` (bit i = core i) the parent's per-core replica gates `valid` with. */
case class HubBeat(cores: Int) extends Bundle {
  val mask = Bits(cores bits)
  val put  = RfCmd(EventLink.inboxAddrWidth)
}

/**
 * The board hub (specs/cross-core/02 §4–§5, decisions D3/D4): one per board, in the parent outside
 * every core pblock. It arbitrates the cores' **non-local puts** (node ≥ `SocSpecMap.localNodes`, see
 * [[RfLink.nonLocal]]) and the puts arriving over the **lane** from the other board onto one ordered
 * stream of [[PutFrame]]s and dispatches each by node id:
 *
 *   - **group `g`** (`SocSpecMap.groupNode(g)`): the put's data is `{slot, bit}`; the hub keeps a copy
 *     of the group's shared word, sets bit `slot`, and re-puts the **whole word** to every local core's
 *     `board[g]` (mask = all). A locally published bit is also forwarded on the lane, so every board's
 *     copy sees every publish (slots are disjoint, so the order across boards does not matter);
 *   - **barrier `b`** (`SocSpecMap.barrierNode(b)`): an arrival's data is the expected member count.
 *     The **root** (board 0) counts every arrival, local or forwarded, keyed by (board, core) — the
 *     first arrival's count is latched, a later disagreeing count raises the sticky `countMismatch`.
 *     On the last arrival it re-puts `time + releaseSlack` into its local arrivers' release mailbox
 *     (mask = who arrived) and sends one **release frame** per remote board with arrivers (offset =
 *     that board's arriver mask, data = the time). A non-root hub forwards its local arrivals to the
 *     root and turns a release frame into the local mailbox re-put. Barriers are rendezvous only;
 *   - **a core's inbox** (`SocSpecMap.inboxNode(board, core)`): a unicast — forwarded as-is to core i
 *     on this board (mask = 1 << i) or over the lane to its board.
 *
 * Ordering is the whole contract: a source's puts are consumed in order and every re-put or
 * forwarded frame leaves in consumption order (the lane is one ordered stream, the release frames
 * of a barrier follow the arrival that completed it), so a member that sees a release has already
 * seen every board bit published before that member's own arrival (§3.4) — on either board. A beat
 * into a full per-source queue is dropped (posted semantics; a core cannot issue puts faster than
 * one per few cycles against a worst-case round-robin wait of `cores + 1` cycles).
 *
 * ==Pipeline (R3, 500 MHz)==
 * Every source queue feeds a register; the round-robin **grant** is one stage and the payload **mux**
 * the next; then the node **decode**; a **pre-read** of the addressed barrier state; the **compute**
 * (count, compare, the beat and the lane frame); and a **write-back** stage for the per-id state, so
 * no stage carries a wide mux or a 16-way demux. A barrier beat for an id that one of the two later
 * stages is still writing waits one cycle (rare: arrivals). Latency ≈ 7 cycles queue-to-beat.
 */
case class PutHub(
    cores: Int,
    board: Int = 0,
    boards: Int = 1,
    addrWidth: Int = SocSpecMap.putAddrWidth,
    groups: Int = SocSpecMap.groupNodes,
    barrierIds: Int = SocSpecMap.barrierIds,
    queueDepth: Int = 8,
    releaseSlack: Int = 0
) extends Area {
  require(boards >= 1 && board < boards && boards <= 16 && cores <= 16, "≤ 16 boards of ≤ 16 cores")
  val isRoot = board == 0
  val in      = Vec(Flow(RfCmd(addrWidth)), cores)  // per-core non-local puts (piped by the parent)
  val laneIn  = Flow(PutFrame())                     // frames from the other board(s)
  val laneOut = Stream(PutFrame())                   // frames to the other board(s)
  val time    = UInt(32 bits)                        // the batch time (a local replica of syncTime)
  val out     = Flow(HubBeat(cores))                 // the ordered local broadcast, one beat per dispatch
  val countMismatch = Reg(Bool()) init False         // sticky: a barrier's members disagreed on the count

  // ── sources: every core's puts and the lane, as frames, queued and registered ──
  val locals = in.zipWithIndex.map { case (f, i) =>
    val s = Stream(PutFrame())
    s.valid          := f.valid
    s.payload.board  := board
    s.payload.aux    := i
    s.payload.node   := (f.payload.address >> 16).resize(12)
    s.payload.offset := f.payload.address(0, 16 bits)
    s.payload.data   := f.payload.data
    s.queue(queueDepth).m2sPipe()
  }
  val lane = laneIn.toStream.queue(queueDepth).m2sPipe()
  val srcs = locals :+ lane
  val n    = srcs.length

  // ── stage A: the round-robin grant, registered. Stage B: the granted source's payload muxed by the
  //    registered one-hot and popped. A source granted twice in a row with one entry costs a bubble. ──
  case class Picked() extends Bundle { val frame = PutFrame(); val fromLane = Bool() }
  val picked = Stream(Picked())
  val valids = B(srcs.map(_.valid))
  val prio   = Reg(Bits(n bits)) init 1
  val grant  = Reg(Bits(n bits)) init 0
  val advance = !picked.valid || picked.ready          // stage B can take a new grant
  val grantNow = OHMasking.roundRobin(valids, prio)
  when(advance) {
    grant := grantNow
    when(grantNow.orR)(prio := grantNow.rotateLeft(1))
  }
  val payloads = Vec(srcs.map(_.payload))
  // the pop of the captured source happens the cycle AFTER the capture, from a register, so no
  // source's ready hangs off the 15-way fire; a source being popped is not captured again that cycle
  val popReg = Reg(Bits(n bits)) init 0
  picked.valid            := (grant & valids & ~popReg).orR
  picked.payload.frame    := MuxOH(grant, payloads)
  picked.payload.fromLane := grant(n - 1)
  popReg := picked.fire ? grant | B(0, n bits)
  for ((s, i) <- srcs.zipWithIndex) s.ready := popReg(i)

  // ── decode the node class once ──
  case class Decoded() extends Bundle {
    val frame = PutFrame()
    val fromLane, isGroup, isBarrier, isInbox = Bool()
    val g = UInt(log2Up(groups) max 1 bits)
    val b = UInt(log2Up(barrierIds) max 1 bits)
    val c = UInt(log2Up(cores) max 1 bits)
  }
  val s1 = picked.m2sPipe()
  val decoded = Stream(Decoded())
  decoded.arbitrationFrom(s1)
  val decodeArea = new Area {
    val raw  = s1.payload.frame
    val unit = raw.node(0, 8 bits)
    def inRange(base: Int, k: Int): Bool = unit >= base && unit < base + k
    decoded.payload.frame     := raw
    decoded.payload.fromLane  := s1.payload.fromLane
    decoded.payload.isGroup   := inRange(SocSpecMap.groupNode(0), groups) && raw.node(8, 4 bits) === 0
    decoded.payload.isBarrier := inRange(SocSpecMap.barrierNode(0), barrierIds) && raw.node(8, 4 bits) === 0
    decoded.payload.isInbox   := inRange(SocSpecMap.inboxUnit(0), cores)
    decoded.payload.g := (unit - SocSpecMap.groupNode(0)).resize(log2Up(groups) max 1)
    decoded.payload.b := (unit - SocSpecMap.barrierNode(0)).resize(log2Up(barrierIds) max 1)
    decoded.payload.c := (unit - SocSpecMap.inboxUnit(0)).resize(log2Up(cores) max 1)
  }

  // ── the per-id state, written by the write-back stage only ──
  val boardsW  = Vec(Reg(Bits(32 bits)) init 0, groups)
  val counts   = Vec(Reg(UInt(8 bits)) init 0, barrierIds)
  val expected = Vec(Reg(UInt(8 bits)) init 0, barrierIds)
  val arrived  = Vec(Reg(Bits(boards * cores bits)) init 0, barrierIds)   // (board, core) bitmap

  // the write-back stage's registers (what the compute stage decided for one barrier id)
  val wb = new Area {
    val valid    = Reg(Bool()) init False
    val b        = Reg(UInt(log2Up(barrierIds) max 1 bits)) init 0
    val count    = Reg(UInt(8 bits)) init 0
    val setExp   = Reg(Bool()) init False
    val exp      = Reg(UInt(8 bits)) init 0
    val arrived  = Reg(Bits(boards * cores bits)) init 0
  }

  // ── stage 2 pre-reads the addressed barrier state; stage 3 computes; a barrier beat for an id that
  //    stage 3 or the write-back still holds waits (the read would be stale) ──
  val s2  = decoded.m2sPipe()
  val s3  = Stream(Decoded())
  val hazard = Bool()
  s3 << s2.haltWhen(hazard)
  val arb = s3.m2sPipe()
  hazard := s2.valid && s2.payload.isBarrier &&
    ((arb.valid && arb.payload.isBarrier && s2.payload.b === arb.payload.b) || (wb.valid && s2.payload.b === wb.b))
  val cnt = RegNextWhen(counts(s2.payload.b), s3.fire) init 0
  val exp = RegNextWhen(expected(s2.payload.b), s3.fire) init 0
  val arr = RegNextWhen(arrived(s2.payload.b), s3.fire) init 0
  val f   = arb.payload.frame
  val fromLane = arb.payload.fromLane
  val isGroup = arb.payload.isGroup; val isBarrier = arb.payload.isBarrier; val isInbox = arb.payload.isInbox
  val g = arb.payload.g; val b = arb.payload.b; val c = arb.payload.c
  val inboxBoard = f.node(8, 4 bits)

  // the root's release frames of one barrier to the remote boards, one per cycle while `busy`
  val rel = new Area {
    val masks = Vec(Reg(Bits(cores bits)) init 0, boards)
    val id    = Reg(UInt(log2Up(barrierIds) max 1 bits)) init 0
    val stamp = Reg(Bits(32 bits)) init 0
    val pending = Bits(boards bits)
    for ((m, i) <- masks.zipWithIndex) pending(i) := (if (i == board || !isRoot) False else m.orR)
    val busy  = pending.orR
    val pick  = OHMasking.first(pending)
    val frame = PutFrame()
    frame.board  := OHToUInt(pick).resize(4)
    frame.aux    := 0
    frame.node   := U(SocSpecMap.barrierNode(0), 12 bits) + id.resize(12)
    frame.offset := MuxOH(pick, masks).asUInt.resize(16)
    frame.data   := stamp
    if (!isRoot) { masks.foreach(_ := B(0, cores bits)); id := 0; stamp := 0 }   // a non-root never releases
  }

  val beat = Flow(HubBeat(cores))
  beat.valid := False
  beat.payload.assignDontCare()
  val fwd = Stream(PutFrame())          // this cycle's frame for the lane, if any
  fwd.valid := False
  fwd.payload := f
  arb.ready := !rel.busy && (!fwd.valid || fwd.ready)

  // write-back defaults: one barrier id per fire, the values decided below
  wb.valid := False
  if (!isRoot) { wb.b := 0; wb.count := 0; wb.setExp := False; wb.exp := 0; wb.arrived := 0 }   // a non-root never counts
  when(arb.valid && !rel.busy) {
    when(isGroup) {
      val slot    = f.data(1, 5 bits).asUInt
      val newWord = cloneOf(boardsW(g))
      newWord := boardsW(g)
      newWord(slot) := f.data(0)
      when(arb.fire)(boardsW(g) := newWord)
      beat.valid := arb.fire
      beat.payload.mask := B(cores bits, default -> True)
      beat.payload.put.address := U(EventLink.boardOffset, EventLink.inboxAddrWidth bits) + (g << 2).resize(EventLink.inboxAddrWidth)
      beat.payload.put.data    := newWord
      if (boards > 1) fwd.valid := !fromLane          // every board applies every publish once
    }
    when(isBarrier) {
      val isRelease = f.offset =/= 0
      if (isRoot) {
        // count every arrival here, local or forwarded, keyed by (board, core)
        val count   = f.data(0, 8 bits).asUInt
        val first   = cnt === 0
        val want    = first ? count | exp
        val now     = cnt + 1
        val key     = (f.board * cores + f.aux).resize(log2Up(boards * cores) max 1)
        val members = arr | (B(1, boards * cores bits) << key).resize(boards * cores)
        val done    = now === want
        when(arb.fire) {
          when(!first && count =/= exp)(countMismatch := True)
          wb.valid   := True
          wb.b       := b
          wb.setExp  := first
          wb.exp     := count
          wb.count   := done ? U(0, 8 bits) | now
          wb.arrived := done ? B(0, boards * cores bits) | members
          when(done) {
            for (i <- 0 until boards) rel.masks(i) := members(i * cores, cores bits)
            rel.id    := b
            rel.stamp := (time + releaseSlack).asBits
          }
        }
        beat.valid := arb.fire && done
        beat.payload.mask := members(board * cores, cores bits)
        beat.payload.put.address := U(EventLink.releaseOffset, EventLink.inboxAddrWidth bits)
        beat.payload.put.data    := (time + releaseSlack).asBits
      } else {
        // a non-root: forward local arrivals to the root; a release frame → the local mailboxes
        when(isRelease) {
          beat.valid := arb.fire
          beat.payload.mask := f.offset(0, cores bits).asBits
          beat.payload.put.address := U(EventLink.releaseOffset, EventLink.inboxAddrWidth bits)
          beat.payload.put.data    := f.data
        } otherwise {
          fwd.valid := !fromLane
        }
      }
    }
    when(isInbox) {
      when(inboxBoard === board) {
        beat.valid := arb.fire
        beat.payload.mask := (B(1, cores bits) << c).resize(cores)
        beat.payload.put.address := f.offset.resize(EventLink.inboxAddrWidth)
        beat.payload.put.data    := f.data
      } otherwise {
        if (boards > 1) fwd.valid := !fromLane
      }
    }
  }
  // ── the write-back stage: the per-id registers from the registered decision ──
  when(wb.valid) {
    counts(wb.b)  := wb.count
    arrived(wb.b) := wb.arrived
    when(wb.setExp)(expected(wb.b) := wb.exp)
  }
  // the lane: this cycle's forward, or the pending release frames (the arbiter is held meanwhile)
  laneOut.valid   := rel.busy || fwd.valid
  laneOut.payload := rel.busy ? rel.frame | fwd.payload
  fwd.ready := laneOut.ready
  when(rel.busy && laneOut.ready)(rel.masks(OHToUInt(rel.pick)) := B(0, cores bits))
  out << beat.stage()
}
