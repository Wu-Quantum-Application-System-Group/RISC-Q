package riscq.riscv.prediction

import spinal.core._
import spinal.core.fiber.Retainer
import spinal.lib._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.execute.BranchPlugin
import riscq.riscv.misc.PipelinePlugin

/**
 * GShare conditional-branch direction predictor: a table of 2-bit saturating counters indexed by
 * `PC ^ global-history`. [[BtbPlugin]] caches this predictor's direction bit per entry (refreshed at
 * commit from `learnTaken`) to decide whether a conditional branch *in the BTB* is predicted taken
 * (unconditional jumps stay always-taken). Like the BTB it only refines a hint — the execute
 * corrector is authoritative — so it carries no recovery machinery.
 *
 * Simplifications vs VexiiRiscv's `GSharePlugin` (all fine because it is a pure hint):
 *  - **Register table** (not BRAM banks); resets to weakly-taken, so no boot-clear.
 *  - **Commit-time history.** The global history shifts in the *resolved* outcome at execute, so
 *    it is always non-speculative ⇒ no flush recovery. (Slightly stale vs a speculative history,
 *    which only costs a little accuracy, never correctness.)
 *  - To train the counter the prediction read at `pcAt` actually used, the **hash is carried** to
 *    execute as a payload; the counter is re-read there (live read-modify-write) and saturated
 *    toward the outcome. No counter-value payload, no extra learn-context plumbing.
 */
class GSharePlugin(p: RiscqParam) extends FiberPlugin {
  // BtbPlugin caches this predictor's direction bit per entry (so the predict-stage read leaves the
  // PC self-loop). We expose the prediction GShare makes for the just-committed branch
  // (`counters(hash).msb`, read at the learn stage before this commit trains it — so it is
  // one-execution stale) and a retainer so BtbPlugin reads it only after it is driven, mirroring
  // BranchPlugin's `learn` handshake.
  val learnDirRetainer = Retainer()
  var learnTaken: Bool = null

  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val lock  = retains(pp.elaborationLock)
    val dlock = retains(learnDirRetainer)
    awaitBuild()

    val w        = p.gshareHistoryWidth
    val entries  = 1 << w
    val ctrWidth = 2
    val ctrMax   = (1 << ctrWidth) - 1
    val weaklyTaken = 1 << (ctrWidth - 1) // MSB set ⇒ predict taken; a fair start for a just-taken branch

    // No reset on the global history (intentional): it is a pure predictor hint (the
    // execute corrector is authoritative), so its reset value is never needed for correctness, and
    // dropping the init keeps it out of the async-reset control-set group.
    val history  = Reg(Bits(w bits))

    // Direction counters: a flip-flop array (default) or a synchronous-read `Mem` (p.gshareMem). Both
    // reset to weakly-taken (no boot-clear) — the Mem carries the same init content. Only the learn side
    // touches them (the predict stage reads the BTB's cached direction bit instead), so the Mem is a plain
    // 1R/1W: the read is presented at executeAt and the matching write lands one cycle later.
    val regCounters = if (p.gshareMem) null
                      else Vec.fill(entries)(RegInit(U(weaklyTaken, ctrWidth bits)))
    val memCounters = if (!p.gshareMem) null
                      else Mem(UInt(ctrWidth bits), entries).init(Seq.fill(entries)(U(weaklyTaken, ctrWidth bits)))

    def hashOf(pc: UInt): UInt = pc(2, w bits) ^ history.asUInt // drop the 2 word-aligned low bits

    // The index used at prediction, carried so learning trains the very same counter.
    val HASH = Payload(UInt(w bits))

    // ---- Predict @ pcAt: remember the hash for training. ----
    // BtbPlugin reads the per-entry cached direction bit, so the live counter read (the 2^w:1 mux in
    // the PC loop) is dropped here — only the hash is carried forward to train the right counter.
    val c0 = pp.ctrl(p.pcAt)
    val read = new c0.Area {
      val hash = hashOf(up(Global.PC))
      HASH := hash
    }

    // ---- Learn @ execute (commit): saturate the counter + shift the outcome into history. ----
    host[BranchPlugin].learnRetainer.await()
    val cmd = host[BranchPlugin].learn
    val cE  = pp.ctrl(p.executeAt)
    val update = new cE.Area {
      // The learn is registered (`cmd` describes the instruction that was at executeAt *last* cycle),
      // so delay the per-instruction HASH by a matching cycle to RMW the very counter that cmd refers
      // to (and to shift the right outcome into history).
      val hash   = RegNext(apply(HASH))
      // Read the counter `cmd` refers to. Register array: an async read at the already-registered
      // `hash`. Mem: present the *live* executeAt HASH to the sync read so its registered output lands
      // the next cycle — exactly when `cmd` describes that instruction (the Mem's address register
      // takes the place of `RegNext(HASH)` on the read path, so both modes land `ctr` on the same
      // cycle). `readFirst` keeps the accepted stale-by-1 same-hash behaviour: a write to a just-read
      // address returns the pre-write value, as the FF array's registered write did.
      val ctr    = if (p.gshareMem) memCounters.readSync(apply(HASH), readUnderWrite = readFirst)
                   else             regCounters(hash)
      // GShare's (pre-update) prediction for the branch `cmd` refers to — the bit BtbPlugin caches.
      // It aligns with `cmd` (both registered the same cycle), so the BTB direction store samples the
      // right counter.
      learnTaken = CombInit(ctr.msb)
      // Split the counter RMW across a register. The single-cone `hash → 2^w:1 read mux → ±1 saturate
      // → 2^w-way write decode` splits into two register-bounded halves: read + register the counter
      // and the learn controls/hash this cycle, then saturate + write the array the next cycle. The
      // extra learn-latency cycle is free (commit-time, the branch re-fetches many stages later); a
      // same-hash back-to-back pair trains off a 1-cycle-stale counter (accuracy only, never
      // correctness). History still shifts here — it is a plain shift register, off the cone.
      val ctrR   = RegNext(ctr)
      val hashR  = RegNext(hash)
      val takenR = RegNext(cmd.taken)
      val doR    = RegNext(cmd.valid && cmd.isConditional) init False
      val incSat = (ctrR === ctrMax) ? ctrR | (ctrR + 1)
      val decSat = (ctrR === 0)      ? ctrR | (ctrR - 1)
      val next   = takenR ? incSat | decSat
      if (p.gshareMem) memCounters.write(hashR, next, enable = doR)
      else             when(doR) { regCounters(hashR) := next }
      when(cmd.valid && cmd.isConditional) {
        history := (history ## cmd.taken).resize(w) // newest outcome at the LSB, drop the oldest
      }
    }

    dlock.release() // learnTaken is driven (or unused) — let BtbPlugin proceed
    lock.release()
  }
}
