package riscq.riscv

import spinal.lib.misc.plugin.Hostable
import riscq.riscv.misc.PipelinePlugin
import riscq.riscv.fetch.{FetchPlugin, PcPlugin}
import riscq.riscv.decode.DecoderPlugin
import riscq.riscv.regfile.RegFilePlugin
import riscq.riscv.execute.{SrcPlugin, IntAluPlugin, BranchPlugin, LsuPlugin, WriteBackPlugin, MulPlugin}
import riscq.riscv.csr.CsrPlugin
import riscq.riscv.prediction.{BtbPlugin, GSharePlugin}

/**
 * Central riscq configuration.
 *
 * The pipeline is a single in-order CtrlLink chain; its depth is fully parameterized by
 * the per-stage indices below, which partition the chain into the 3 parts
 * (Fetch / Decode / Execute). Bumping an index inserts a registered stage, exactly as
 * VexiiRiscv's `aluAt`/`jumpAt`/`forkAt` parameters do.
 */
case class RiscqParam(
    xlen: Int = 32,
    resetVector: BigInt = 0x80000000L,
    // ---- Fetch part stage indices ----
    pcAt: Int = 0,        // PC injected into the pipeline here
    // Instruction-memory read latency, in pipeline stages: the fetch forks the `Get` at `pcAt` and
    // joins the word `fetchLatency` stages later (`fetchDataAt = pcAt + fetchLatency`). It must be at
    // least the memory's response latency `L` to sustain IPC = 1 — the multi-outstanding fetch keeps
    // `fetchLatency` requests in flight so a word arrives every cycle (see FetchPlugin). Default 1.
    fetchLatency: Int = 1,
    // ---- Decode / Execute stage indices ----
    // These default to positions *derived from* `fetchDataAt` (see the `decodeAt`/`executeAt` accessors
    // below) so that raising `fetchLatency` slides the whole back end down the chain in lock-step instead
    // of leaving decode hard-wired at stage 2 (which would collide with — or fall *before* — `fetchDataAt`
    // once `fetchLatency > 1`). Pass `Some(i)` only to pin an index explicitly; Scala forbids a default in
    // this list from referencing an earlier param, hence the Option + derived-accessor split rather than a
    // plain `Int = pcAt + fetchLatency + 1`.
    decodeAtOverride: Option[Int] = None,    // default: fetchDataAt + 1
    executeAtOverride: Option[Int] = None,   // default: decodeAt + 2
    // Direct-mapped BTB entry count (power of two ≥ 2); see prediction.BtbPlugin.
    btbSets: Int = 2,
    gshareHistoryWidth: Int = 6, // GShare global-history / counter-index width (2^w counters)
    // Optional ISA extensions. Each gates a self-contained FiberPlugin; the base RV32I core is
    // unchanged when off. `withMul` adds the Zmmul multiply unit (execute.MulPlugin) — multiply only
    // (mul/mulh/mulhsu/mulhu), no divide, so the ISA is RV32I + Zmmul, not full M.
    // Default on; set `withMul = false` (config `with_mul: false`) for a minimal RV32I-only build.
    withMul: Boolean = true,
    // ---- Resource-shrink flags ----
    // Each defaults on and is a bit-exact A/B with identical architectural correctness (RVLS-gated).
    //   gshareMem: store the GShare 2-bit counters in a synchronous-read `Mem` (BRAM/LUTRAM)
    //     instead of a 2^w×2 flip-flop array + 2^w:1 read mux + 2^w-way write decode. See
    //     prediction.GSharePlugin.
    gshareMem: Boolean = true,
    //   csrWarl: apply each CSR's WARL/WLRL latitude (mtval≡0, mie keeps only MSIE/MTIE/MEIE, mcause
    //     stores the 4-bit code, mtvec/mepc force [1:0]=0, mstatus.MPP≡11) — fewer FFs, and strictly
    //     *more* machine-only-Spike-conformant. See csr.CsrPlugin.
    csrWarl: Boolean = true,
    //   deriveImmFromWord: stop carrying the 32-bit `Decode.IMM` payload on the decode→regRead link.
    //     Instead carry only the 3-bit `Decode.IMM_TYPE` format and rebuild the immediate at `regReadAt`
    //     (the consumer stage — SrcPlugin's SRC2 mux + BranchPlugin's pc+imm precompute both read it there)
    //     from the `Fetch.WORD` already carried through to executeAt. Bit-identical immediate produced one
    //     stage later ⇒ RVLS bit-exact; fmax-neutral (the format mux just moves decode→regRead). See
    //     DecoderPlugin.
    deriveImmFromWord: Boolean = true,
    // ---- Timing-closure levers ----
    // These default to the settings that close timing on the target floorplan; set any to off (0/false)
    // for the simpler pre-optimization baseline. Only aluFastAddOnly costs IPC; the rest are bit-exact /
    // no-IPC.
    //   aluOperandMaxFanout: `max_fanout = N` on the executeAt ALU operand registers (Execute.SRC1/SRC2).
    //     The single-cycle ALU result-forward loop is route-dominated; capping fanout makes Vivado
    //     replicate the operand register so each consumer routes from a nearby copy. 0 = off. Bit-exact
    //     (attribute only). See SrcPlugin.
    aluOperandMaxFanout: Int = 16,
    //   aluFastAddOnly: shorten the single-cycle ALU result-forward loop by forwarding the shallow
    //     `ALU_FAST` tap ONLY for the ADD-select class (ADD/ADDI/SUB/LUI/AUIPC), dropping SLT/XOR/OR/AND
    //     from the fast mux. Those producers become "expensive" and take the existing 1-cycle interlock
    //     when a dependent op immediately follows. Removes a result-mux level from the loop at an IPC cost
    //     (back-to-back dependent SLT/bitwise pairs bubble). Architecturally identical (interlock covers
    //     the dropped cases) — RVLS bit-exact. This is the one lever with an IPC cost. See
    //     IntAluPlugin/RegFilePlugin.
    aluFastAddOnly: Boolean = true,
    //   lateWriteback: move the final rd-write 2:1 mux (`aluWritesRd ? aluResult | nonAlu`) from executeAt
    //     to the existing regWriteAt stage, taking it off the `SRC→RD_DATA` register path. No extra
    //     pipeline depth ⇒ no IPC cost (srcB already forwards from regWriteAt). The RVLS retire keeps a
    //     sim-only duplicate mux at executeAt (pruned in synthesis). See WriteBackPlugin.
    lateWriteback: Boolean = true,
    //   pcRegMaxFanout: `max_fanout = N` on the architectural PC register. The fetch predicted-PC self-loop
    //     (`pcReg→pcReg` via BTB/`NEXT_PC_PRED`) is route-dominated; replicating pcReg gives each consumer
    //     (BTB index/tag, the +4 adder, the fetcher) a nearby copy. 0 = off. Bit-exact. See PcPlugin.
    pcRegMaxFanout: Int = 16,
    //   btbPredictLate: take the BTB tag-compare + the 32-bit target/seq next-PC mux OUT of the
    //     pcReg→pcReg self-loop. With it off (baseline), a BTB hit folds the target into the *same*
    //     cycle's next PC (0-bubble prediction) — but that puts the BTB hit (`read_hit`, fanout 32) and
    //     the target mux on the recurrence, the route-bound binder. With it on, pcReg advances purely
    //     sequentially (`pcReg→+4→pcReg`) and the BTB issues its target as a **registered, 1-cycle-late
    //     fetch redirect** that flushes the single wrong-path fall-through fetch (the pcAt..pcAt+1 stage).
    //     `NEXT_PC_PRED` still carries the prediction so the execute corrector is unchanged. IPC cost: a
    //     correctly-predicted taken branch/jump goes 0 → 1 bubble (vs the full ~5-cycle mispredict it
    //     still avoids). Architecturally identical (the execute corrector backstops every misprediction)
    //     — RVLS-gated. false = the 0-bubble baseline. See PcPlugin / BtbPlugin.
    btbPredictLate: Boolean = false,
    //   pcOneHotRedirect: select the next PC with a balanced one-hot mux (`OHMasking.firstV2` + `OhMux.or`)
    //     instead of the serial priority `when`-chain — removes the `IS_JALR→pcReg` redirect binder.
    //     Helps the packed multi-core density (several cores sharing one clock region) where the serial
    //     redirect chain dominates. Bit-exact (RVLS). See PcPlugin.
    pcOneHotRedirect: Boolean = true,
    //   lateBadTarget: move the 32-bit *target* compare (`NEXT_PC_PRED =/= TARGET_ALIGNED` → BAD_TARGET)
    //     OFF the executeAt adder cone and run it at jumpAt instead. Baseline computes it at executeAt,
    //     chained right after the JALR adder (`rs1+imm → targetAligned → 32-bit compare → register`) —
    //     that chain is the timing critical path. With this flag executeAt ends at the adder→register
    //     (only TARGET_ALIGNED, already registered, crosses) and jumpAt does the compare from registers.
    //     To keep jumpAt's high-fanout flush shallow despite the added compare, the cheap *direction*
    //     mispredict (cfi & predicted-taken disagrees with take, `take ⇒ isCfi`) is precomputed at
    //     executeAt and registered (Execute.DIR_MISPREDICT); jumpAt's decision is then
    //     `isFiring & (DIR_MISPREDICT | (TAKE & badTarget))`. Bit-identical to the baseline (RVLS).
    //     Default off = the baseline. See BranchPlugin (the `br` and `act` areas).
    lateBadTarget: Boolean = false,
    //   aluNoFastForward: drop the srcA fast-forward (the shallow `ALU_FAST` tap) ENTIRELY and interlock
    //     every 1-ahead RAW hazard instead, so the SRC-feeding cone ends only at registered sources —
    //     taking the `ALU-result-mux → RD_DATA → forward` loop off the path. IPC cost: a 1-cycle bubble
    //     whenever an operand depends on the instruction one ahead (vs aluFastAddOnly, which only bubbles
    //     SLT/bitwise). Architecturally identical (the widened interlock covers every dropped forward) —
    //     RVLS bit-exact. Default off. See RegFilePlugin (the `forward` area).
    aluNoFastForward: Boolean = false,
    //   aluResultOneHot: build the ALU result mux as a balanced one-hot masked-OR cone (each candidate
    //     ANDed with its `ALU_SEL` one-hot bit, OR-reduced) instead of the priority `switch` mux. Zero
    //     IPC, bit-exact. Placement-sensitive: helps weak/congested regions but can regress a clean one
    //     (it widens wiring congestion), so it earns its keep mainly on a packed/congested floorplan.
    //     Default off. See IntAluPlugin (the result mux).
    aluResultOneHot: Boolean = false,
    //   csrCommitMaxFanout: `MAX_FANOUT = N` on CsrPlugin's sampled CSR write qualifier (`writeEn`),
    //     the root of the per-CSR clock-enable decode (the observed 5-level
    //     `valid → … → mstatusMie → mtvec → mscratch/CE` chain). 0 = off.
    //     Bit-exact (attribute only). See CsrPlugin.
    csrCommitMaxFanout: Int = 0,
    // NOTE: several bit-exact levers are now BAKED IN — always on, no longer flags: parallel per-CSR
    //   commit, the LSU pure-data snapshot select, the registered load down-shift, the mispredict kept off
    //   the LSU/Mul halt, pre-decoded env ops, plus the MAX_FANOUT=16 caps on the jumpAt mispredict root
    //   (BranchPlugin) and the fetch reorder-buffer control (FetchPlugin), and the MAX_FANOUT=4 cap on
    //   the drive buffer's dcOffset register (PulseParamBuffer). See those plugins.
    // ---- Pipeline back-pressure cuts ----
    // Each `i` replaces the plain StageLink at the ctrl(i)→ctrl(i+1) boundary with a skid buffer
    // (StageLink + CtrlLink + S2MLink): the S2MLink registers the upstream `ready` so the execute-stage
    // halt/flush stops rippling combinationally back to the fetch fork — no steady-state cost (the buffer
    // is transparent when empty). Wrong-path instructions in a skid are cancelled by
    // PipelinePlugin.throwSkidsBefore. Default = fetchData→decode (Seq(1)); empty = none.
    skidAfterOverride: Option[Seq[Int]] = None,
    // ---- Fetch-PC narrowing ----
    // Width of the architectural PC and of every carried code-address (Global.FETCH_PC_WIDTH). None ⇒ full
    // XLEN (the baseline). Some(w) carries only the low w-bit PC *offset* through the pipeline and
    // reconstructs the full 32-bit address (pcBase | offset) at the four visible boundaries — the iBus
    // address, the AUIPC SRC1, the JAL/JALR return address written to rd, and the mepc/mtvec CSR reads;
    // pcBase = resetVector & ~(2^w − 1). Saves carried PC flip-flops per core. In-region behaviour is
    // bit-identical (RVLS-gated); a control transfer leaving [pcBase, pcBase+2^w) wraps within the region —
    // a documented memory-map contract. `w` must hold the whole code region with no wrap. Default None. See
    // RiscqPlugin / Global.fullPc and the reconstruction sites (FetchPlugin / SrcPlugin / WriteBackPlugin /
    // CsrPlugin).
    fetchPcWidth: Option[Int] = None,
    // Sizes the SparseMemory image the testbenches preload (the core fetches/accesses over Tilelink).
    memWords: Int = 1 << 14
) {
  /** Width of the fetch PC / carried code addresses: the narrowed width when set, else full XLEN. */
  def fetchPcW: Int = fetchPcWidth.getOrElse(xlen)
  /** Constant high bits prepended to a carried PC offset to rebuild a full address (its low fetchPcW bits
   *  are 0, so the rebuild is a constant OR). 0 when not narrowing. */
  def pcBase: BigInt = resetVector & ~((BigInt(1) << fetchPcW) - 1)
  /** Reset value of the carried PC offset = the resetVector's in-region low bits (== resetVector when full). */
  def resetPcOffset: BigInt = resetVector & ((BigInt(1) << fetchPcW) - 1)
  require(fetchPcW <= xlen, s"fetchPcWidth ($fetchPcW) must be <= xlen ($xlen)")
  require(fetchPcW >= 2 + gshareHistoryWidth,
    s"fetchPcWidth ($fetchPcW) must be >= 2 + gshareHistoryWidth (${2 + gshareHistoryWidth}) for the GShare PC hash")
  /** Stage where the fetched instruction word is available (after the `fetchLatency`-cycle memory read). */
  def fetchDataAt: Int = pcAt + fetchLatency

  /** Stage where the instruction is decoded: one stage after the word is available (so it tracks
   *  `fetchLatency`), unless pinned via `decodeAtOverride`. */
  def decodeAt: Int = decodeAtOverride.getOrElse(fetchDataAt + 1)

  /** Stage where the back end runs (ALU + branch-resolve + LSU + CSR + write-back): two stages after
   *  decode (so the registered operand read at `regReadAt = executeAt-1 = decodeAt+1` sits between decode
   *  and execute), unless pinned via `executeAtOverride`. With the defaults this is stage 4. */
  def executeAt: Int = executeAtOverride.getOrElse(decodeAt + 2)

  def skidAfter: Seq[Int] = skidAfterOverride.getOrElse(Seq(fetchLatency))

  // Stage ordering the rest of the core relies on; catches a `*Override` (or `fetchLatency`) that would
  // fold the back end onto/ahead of the fetched word — the exact breakage when `fetchLatency > 1` left
  // the old hard-wired indices behind.
  require(fetchLatency >= 1, s"fetchLatency must be >= 1, got $fetchLatency")
  require(decodeAt > fetchDataAt, s"decodeAt ($decodeAt) must be after fetchDataAt ($fetchDataAt)")
  require(executeAt >= decodeAt + 2,
    s"executeAt ($executeAt) must be >= decodeAt+2 ($decodeAt+2) so regReadAt sits between decode and execute")

  /** Stage where the register file is read: one stage before `executeAt`, so the operands are
   *  registered into `executeAt` (the distributed-RAM async read leaves the executeAt cone) and the
   *  2-deep forwarding network covers the read→write hazard. */
  def regReadAt: Int = executeAt - 1

  /** Stage where the register file is written: one stage *after* `executeAt` (== `jumpAt`, so no added
   *  depth) — RD_DATA is registered, breaking the ALU/JALR-add → RAMD32-write cone at a register. */
  def regWriteAt: Int = executeAt + 1

  /** Just the Fetch part: PC + synchronous instruction memory. */
  def fetchPlugins(): Seq[Hostable] = Seq(
    new RiscqPlugin(xlen, fetchPcW, pcBase),
    new PipelinePlugin(this),
    new PcPlugin(this),
    new FetchPlugin(this)
  )

  /** Fetch + Decode — the pipeline the decode unit test exercises (no execute = no redirect). */
  def decodePlugins(): Seq[Hostable] = fetchPlugins() :+ new DecoderPlugin(this)

  /** The whole core: Fetch + Decode + Execute (incl. the cacheless LSU) + BTB + GShare. */
  def plugins(): Seq[Hostable] = pluginsBtbOnly() :+ new GSharePlugin(this)

  /**
   * Core + BTB but **no GShare** — a conditional BTB hit then predicts always-taken. The A/B
   * reference for showing GShare's direction prediction (see `sim.BranchIpcSim`).
   */
  def pluginsBtbOnly(): Seq[Hostable] = pluginsNoPredict() :+ new BtbPlugin(this)

  /**
   * The core *without* any fetch-stage predictor. Correctness is identical (the execute corrector
   * is authoritative and degrades to "redirect when taken"), so this is the A/B reference the
   * branch-IPC sim compares against.
   */
  def pluginsNoPredict(): Seq[Hostable] = decodePlugins() ++ Seq(
    new RegFilePlugin(this),
    new SrcPlugin(this),
    new IntAluPlugin(this),
    new BranchPlugin(this),
    new LsuPlugin(this),
    new CsrPlugin(this),
    new WriteBackPlugin(this)
  ) ++ (if (withMul) Seq(new MulPlugin(this)) else Nil)
}
