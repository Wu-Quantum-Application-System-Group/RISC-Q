# BranchPlugin — branch/jump resolution + the predictor corrector

**Source:** `src/riscq/riscv/execute/BranchPlugin.scala` · **Package:** `riscq.riscv.execute` · **Type:** FiberPlugin (+ companion `AreaObject`)

## Purpose

Resolves all control flow — `BEQ/BNE/BLT/BGE/BLTU/BGEU`, `JAL`, `JALR` — and acts as the
**predictor corrector**: it computes the *true* successor, compares it against what fetch actually
predicted, and on a misprediction redirects the PC and flushes the wrong-path stages. It also feeds
a `learn` event back to the fetch predictors so they improve.

## Role in the system

The compare results (`EQ`, `LESS`) come pre-computed from [SrcPlugin](SrcPlugin.md), shared with the
ALU. The plugin reads the prediction fetch chose (`Fetch.NEXT_PC_PRED`, set by
[PcPlugin](PcPlugin.md) from [BtbPlugin](BtbPlugin.md)/[GSharePlugin](GSharePlugin.md)) and drives
`PcPlugin.redirect` + a flush on a mismatch.

It spans three pipeline points (see [pipeline.md](pipeline.md)):

```
executeAt-1 (precompute):  BRANCH_TARGET = PC + imm           (PC-relative, no register operand)
executeAt   (resolve):     take? , JALR target = ADD_SUB,     register TAKE / TARGET_ALIGNED / BAD_TARGET
                           learn payload (registered 1 cycle) ─► BTB / GShare
executeAt+1 (jumpAt, act): mispredict? ─► PcPlugin.redirect + throwStagesBefore(jumpAt)
```

## Design rationale

- **Corrector, not just "redirect when taken".** It compares the resolved successor to the predicted
  one. With **no** predictor `NEXT_PC_PRED` is always PC+4, so a misprediction reduces to exactly
  "taken" — the same corrector degrades to the simple rule as a special case, and a *correctly*
  predicted taken branch needs neither redirect nor flush.
- **JALR reuses the shared adder.** JALR's target is `rs1 + imm` with the low bit cleared — exactly
  SrcPlugin's `Execute.ADD_SUB` (JALR takes the default src decode: SRC1=rs1, SRC2=imm, no subtract),
  so no second adder is added. BRANCH/JAL targets are PC-relative (`pc + imm`) and need no register
  operand, so they are precomputed one stage early at `executeAt-1` as `Execute.BRANCH_TARGET` and
  registered forward — keeping that adder off the executeAt resolve cone, and registering it only
  *once* (executeAt-1 → executeAt) rather than through every decode→execute StageLink.
- **Resolve at executeAt, act one stage later (the `jumpAt` split).** The expensive part — the
  32-bit target compare (predicted next-PC vs the taken target) — runs at `executeAt` and is
  registered as a *single bit* (`BAD_TARGET`); the cheap 1-bit direction test plus the
  **high-fanout flush** run at `jumpAt = executeAt+1`, off that compare's critical cone. This is
  VexiiRiscv's `BAD_TARGET`/`ALIGNED_JUMPED` split. It ends the resolve→redirect→PC cone at a
  register (the PC self-loop is the core's second route-dominated binder) at the cost of one
  mispredict-penalty cycle, and lets Vivado replicate the redirect broadcast.
- **`learn` stays at executeAt and is registered one cycle.** The branch is the oldest instruction
  on its own path, so it is never flushed by itself ⇒ the BTB/GShare train **exactly once**,
  regardless of where the redirect acts. The learn payload is computed combinationally then
  registered one cycle, so the predictor write-decode starts from a register instead of sitting at
  the end of the `operand → subtract → resolve → predictor-write` cone. The train is hint-only and
  the branch re-fetches many stages later, so a 1-cycle-late learn is correctness-neutral (GShare
  delays its HASH read by a matching cycle to train the same counter).
- **Flush off the `up.ready` spine.** The misprediction decision at `jumpAt` is assembled from
  *registered* resolution payloads, so it must gate on `down.isFiring` — the StageLink only refreshes
  those registers when an instruction advances, so they are stale during a bubble (jumpAt is the last
  stage, never cancelled, so `isFiring` is just "a valid instruction is present"). The flush range
  `pcAt..jumpAt` (exclusive) **includes executeAt**, so a younger store or CSR-trap sitting there is
  cancelled — see [LsuPlugin](LsuPlugin.md)'s and [CsrPlugin](CsrPlugin.md)'s wrong-path gates, which
  key off the node's own `up.isCancel`.

Far simpler than VexiiRiscv's `BranchPlugin`: single lane, no branch-history feed, no
misaligned-target trap.

## Companion-object payloads

`IS_BRANCH`, `IS_JAL`, `IS_JALR` classify the instruction; `IS_JUMP` (= JAL || JALR, rd = PC+4) is
read by [WriteBackPlugin](WriteBackPlugin.md). The return address is published as `Execute.LINK_PC`.
The conditional branches also register SrcPlugin's `SRC2_RS2`/`DO_SUB`/`CMP_UNSIGNED` overrides
(every branch tests rs1−rs2; BLTU/BGEU unsigned).

## Configuration lever (RiscqParam)

- **`lateBadTarget`** (default off) — move the 32-bit target compare *itself* off the executeAt
  adder cone and run it at `jumpAt`, registering only `TARGET_ALIGNED` plus a cheap direction-mispredict
  bit (`DIR_MISPREDICT`). Baseline keeps the compare at executeAt (chained after the JALR adder). It
  regressed in the fmax campaign and stays flag-gated.

## Latency / timing

Single-issue, II=1. Misprediction penalty is the flush of the younger fetch/decode stages plus the
one extra cycle from acting at `jumpAt`. The resolve/JALR cone and the PC-redirect loop are the
core's primary binders; both have been pushed to start/end at registers as above.

## Verification

[ExecuteSim](../../src/riscq/riscv/sim/ExecuteSim.scala) checks all six branches (taken and
not-taken, including a backward loop) and JAL/JALR against a golden model;
[BranchIpcSim](../../src/riscq/riscv/sim/BranchIpcSim.scala) verifies prediction is architecturally
invisible (identical committed-PC stream across no-predict / BTB-only / BTB+GShare) and that it cuts
mispredictions; the full `rv32ui-p-*` suite runs under RVLS/Spike in
[RvTestSim](../../src/riscq/riscv/sim/RvTestSim.scala). A `dbgMispredict` whitebox marks each
committed wrong-prediction.

```bash
mill runMain riscq.riscv.sim.ExecuteSim
mill runMain riscq.riscv.sim.BranchIpcSim
mill runMain riscq.riscv.sim.RvTestSim
```

## Related

[SrcPlugin](SrcPlugin.md) · [PcPlugin](PcPlugin.md) · [BtbPlugin](BtbPlugin.md) ·
[GSharePlugin](GSharePlugin.md) · [WriteBackPlugin](WriteBackPlugin.md) · [pipeline.md](pipeline.md)
