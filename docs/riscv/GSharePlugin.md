# GSharePlugin — GShare conditional-branch direction predictor

**Source:** `src/riscq/riscv/prediction/GSharePlugin.scala` · **Package:** `riscq.riscv.prediction`
· **Type:** FiberPlugin

## Purpose

Predict the *direction* (taken / not-taken) of conditional branches: a table of 2-bit saturating
counters indexed by `PC ^ global-history`. It only refines a hint — [BranchPlugin](BranchPlugin.md)
at execute is authoritative — so it carries no recovery machinery. Unconditional jumps don't use it
(the [BTB](BtbPlugin.md) predicts them always-taken).

## How it pairs with the BTB

GShare does **not** drive the PC self-loop directly. Instead its direction bit for each branch is
**cached per BTB entry**: at commit, [BtbPlugin](BtbPlugin.md) reads `learnTaken` (the counter MSB
GShare computed for the just-committed branch) and stores it in that entry. The predict-stage read in
the PC loop is then a tiny `sets`:1 register mux (the cached bit), not GShare's live `2^w`:1 counter
mux. So GShare's only live presence in the pipeline is at `pcAt` (compute + carry the hash) and at
execute (train the counters). With no GShare instantiated, a conditional BTB hit just predicts
always-taken.

```
pcAt:    hash = PC ^ history ─▶ HASH (carried to execute to train the same counter)
execute: counters(hash) ─┬─▶ learnTaken (MSB) ─▶ BtbPlugin caches it per entry
                         └─▶ read-modify-write toward the resolved outcome; shift outcome into history
```

## Design rationale (it is a pure hint, so it can be lean)

- **Commit-time, non-speculative history.** The global history shifts in the *resolved* outcome at
  execute, so it is never speculative ⇒ no flush recovery. Slightly stale vs a speculative history,
  which costs a little accuracy, never correctness.
- **Carry the hash, not the counter value.** The prediction read at `pcAt` records its hash as a
  payload (`HASH`, local to this plugin); the counter is re-read at execute (live read-modify-write) and saturated
  toward the outcome. No counter-value payload, no extra learn-context plumbing — and the same hash
  trains the exact counter the prediction used.
- **RMW split across a register.** The single cone `hash → 2^w:1 read mux → ±1 saturate → 2^w-way
  write decode` is split into two register-bounded halves (read+register this cycle, saturate+write
  next). The extra learn-latency cycle is free (commit-time; the branch re-fetches many stages
  later); a same-hash back-to-back pair trains off a 1-cycle-stale counter (accuracy only, never
  correctness).

## The counter store — `gshareMem` (default on) and the control-set win

The counters are either a flip-flop array (`gshareMem = false`) or a synchronous-read `Mem`
(`gshareMem = true`, default). Both reset to weakly-taken (MSB set) so there is no boot-clear; the
`Mem` carries the same init content. Only the learn side touches the counters (the predict stage
reads the BTB's cached bit), so the `Mem` is a plain 1R/1W: the read address is the live executeAt
`HASH` and its registered output lands the next cycle — exactly when the registered `learn` command
describes that instruction (the Mem's address register stands in for the FF path's `RegNext(HASH)`).
`readUnderWrite = readFirst` preserves the accepted stale-by-1 same-hash behaviour.

**Why `gshareMem` is the default — a control-set fact.** As a flip-flop array, the `2^w`-entry × 2-bit
counters needed a `2^w`-way one-hot write decode → `2^w` unique clock-enables → one tiny 2-FF control
set each, dominating the core's control-set count. Storing them in a `Mem` (LUTRAM, ~16 LUT, 0 BRAM)
with a single write-enable collapses that: the core's control-set count drops from **283 to 28**.
This is the headline lever for the **multi-instance (per-qubit) SoC**, where routing congestion from
replicated cores is the constraint — see [PulseTableSoc](../soc/PulseTableSoc.md). The flag is a
bit-exact A/B (RVLS-gated); the SoC builds its cores with it on by default.

## Configuration

`gshareHistoryWidth` (default 6) sets `2^w` counters and the history/hash width. Wider improves
direction accuracy on history-correlated branches at more storage; the `fetchPcWidth` narrowing must
keep `≥ 2 + gshareHistoryWidth` bits for the PC hash. See [RiscqParam](RiscqParam.md).

## Verification

`BranchIpcSim` compares branch IPC with GShare against the `pluginsBtbOnly()` (always-taken) and
`pluginsNoPredict()` references; correctness is covered by the whole-core `RvTestSim` under RVLS
lock-step (a hint can't break correctness). `ControlSetBench` reports the control-set count/histogram
to confirm the 283→28 result.

```bash
mill runMain riscq.riscv.sim.BranchIpcSim
mill runMain riscq.riscv.bench.ControlSetBench
```

## Related

[BtbPlugin](BtbPlugin.md) · [BranchPlugin](BranchPlugin.md) · [PcPlugin](PcPlugin.md) ·
[RiscqParam](RiscqParam.md) · [PulseTableSoc](../soc/PulseTableSoc.md)
