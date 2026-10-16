# BtbPlugin — full-tag register Branch Target Buffer

**Source:** `src/riscq/riscv/prediction/BtbPlugin.scala` (+ `Prediction.scala`)
· **Package:** `riscq.riscv.prediction` · **Type:** FiberPlugin

## Purpose

A direct-mapped, full-tag, register-based branch target buffer read at the **PC stage** (`pcAt`). On
a hit it steers the next PC straight to the stored target, so a correctly-predicted taken
branch/jump costs **zero** bubbles — no wrong-path instruction is ever fetched, hence no flush. This
is the depth-1 stripping of VexiiRiscv's `BtbPlugin`: no chunks/slices/aligner, no RAS, no dual-port
RAM, no branch-history layers.

## Role in the system

```
pcAt:  PC ─▶ BtbPlugin read ─(hit)▶ PcPlugin.newPredictor() ─▶ next PC = target   (0 bubbles)
                                  └─▶ Fetch.NEXT_PC_PRED (carried so execute can check it)
execute: BranchPlugin resolves ─▶ learn Flow[LearnCmd] ─▶ BtbPlugin (install/refresh entry)
```

It takes a predictor port from [PcPlugin](PcPlugin.md) (`newPredictor()`) at `pcAt`, and learns from
[BranchPlugin](BranchPlugin.md)'s `learn` Flow at execute. [BranchPlugin](BranchPlugin.md) stays the
**authoritative corrector** — it backstops every misprediction — so the BTB carries no recovery
machinery. The two execute correctors (BranchPlugin for branches/jumps, [CsrPlugin](CsrPlugin.md)
for traps/mret) never collide with a BTB prediction (see "full tag" below).

## Design rationale (simple over clever)

- **Register array, not a RAM.** `btbSets` is tiny (default 2), so a `Reg`/`Vec` array is cheaper
  than dual-port-RAM machinery. `valid = RegInit(False)` gives a free boot-clear (no init counter,
  no x-prop). The async read at `pcAt` is a known fmax cost — a sync BRAM read at `pcAt` + predict
  one stage later would be the 1-bubble alternative.
- **Full tag** (every PC bit above the index). Combined with the free boot-clear, the *only* BTB
  hits are PCs the core has actually learned — which are only real taken branches/jumps. So the
  predictor never fires on a non-branch, and the trap/mret corrector never collides with it. The PC
  layout is `[ tag | index | 2'b00 ]` (RV32I word-aligned, no compressed instructions).
- **Direction.** Unconditional jumps are always predicted taken. A conditional branch follows
  [GSharePlugin](GSharePlugin.md)'s direction bit, but that bit is **cached per BTB entry** here
  (refreshed at commit from GShare's `learnTaken`) rather than read live — so the predict-stage read
  is a tiny `sets`:1 register mux instead of GShare's `2^w`:1 counter mux in the PC self-loop. Each
  entry records `isCond` so the read knows which rule applies. GShare is an **optional** dependency:
  with none present (`host.get[GSharePlugin]` empty) a conditional hit simply predicts always-taken.

## Learn interface — `LearnCmd`

`LearnCmd` (in `Prediction.scala`; a `Bundle`, broadcast on [BranchPlugin](BranchPlugin.md)'s `Flow[LearnCmd]`)
carries `pc`, `target`, `taken`, and `isConditional`. There is a single producer (the one branch
unit) and zero-or-more consumers (this BTB, and GShare's counters), so a plain broadcast `Flow` is
enough — far simpler than VexiiRiscv's multi-lane `LearnPlugin`. On a `valid && taken` commit the
BTB installs/refreshes `valid/tags/targets/isCond` at the indexed entry. The cached direction bit is
refreshed on every conditional commit that *owns* the entry (a taken commit installs it; a not-taken
commit updates it only if the entry already belongs to this branch, so an aliasing not-taken commit
never corrupts another entry).

The elaboration uses retainers (`predictorRetainer`, `BranchPlugin.learnRetainer`,
`GSharePlugin.learnDirRetainer`) so the predict, learn, and GShare-cache wiring build in a defined
order regardless of plugin instantiation order.

## `btbPredictLate` (optional)

With `btbPredictLate` off (default) the hit folds into the *same* cycle's next PC (0-bubble), which
puts the tag-compare + 32-bit target mux on the route-bound `pcReg` self-loop. With it on, the
decision is carried to `pcAt+1` and issued as a **registered fetch redirect** that flushes the lone
wrong-path fall-through fetch — 1 bubble on a predicted-taken transfer, but the recurrence stays
`pcReg→+4→pcReg`. `NEXT_PC_PRED` still drives the execute corrector either way. See
[RiscqParam](RiscqParam.md).

## Configuration

`btbSets` — entry count (power of two ≥ 2, default 2). Larger reduces aliasing at more FFs.

## Verification

`BranchIpcSim` measures branch IPC with the BTB present vs `pluginsNoPredict()` / `pluginsBtbOnly()`
references; correctness rides the whole-core `RvTestSim` under RVLS lock-step (the predictor only
refines a hint, so it can never break correctness).

```bash
mill runMain riscq.riscv.sim.BranchIpcSim
mill runMain riscq.riscv.sim.RvTestSim
```

## Related

[GSharePlugin](GSharePlugin.md) · [PcPlugin](PcPlugin.md) · [BranchPlugin](BranchPlugin.md) ·
[FetchPlugin](FetchPlugin.md) · [RiscqParam](RiscqParam.md)
