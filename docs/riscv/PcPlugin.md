# PcPlugin — the program counter and next-PC select

**Source:** [`src/riscq/riscv/fetch/PcPlugin.scala`](../../src/riscq/riscv/fetch/PcPlugin.scala),
[`src/riscq/riscv/fetch/Fetch.scala`](../../src/riscq/riscv/fetch/Fetch.scala) ·
**Package:** `riscq.riscv.fetch` · **Type:** `FiberPlugin`

Holds the architectural PC and drives the head of the [pipeline](pipeline.md). Each cycle the
head stage fires, the PC advances to its **predicted successor**, that successor is published as
`Fetch.NEXT_PC_PRED` for the execute corrector to check, and any redirect (misprediction, trap)
overrides the advance.

## Role in the data flow

`PcPlugin` lives at stage `pcAt`. It is the meeting point of three things that want to set the
next PC, in strict priority:

```
                 ┌── correctors (highest)  : BranchPlugin mispredict, CsrPlugin trap/mret
   next pcReg ◀──┤── fetch redirect (mid)  : BTB in btbPredictLate mode (newFetchRedirect)
                 └── sequential advance     : predicted successor (predictor target, else PC+4)
```

- The **predictor** ([BtbPlugin](BtbPlugin.md)) registers via `newPredictor()` and steers the
  *next* fetch speculatively — it needs no flush, because it steers before any wrong-path
  instruction exists.
- The **correctors** ([BranchPlugin](BranchPlugin.md) mispredict, [CsrPlugin](CsrPlugin.md)
  trap/mret) register via `newRedirect()`; they target *older* instructions, so they have highest
  priority and also flush the younger wrong-path stages (via `PipelinePlugin.throwStagesBefore` /
  `throwSkidsBefore`).
- A **fetch redirect** (`newFetchRedirect()`) is the middle tier — used only by the BTB in
  `btbPredictLate` mode, where the predicted target re-steers `pcReg` one cycle after the PC
  stage. Kept a separate list so the corrector `redirects` stay ≤ 2 and the later corrector writes
  still win.

A sub-pipeline with **no predictor** (the fetch/decode unit tests) gets a never-valid idle
predictor ⇒ pure sequential PC+4 fetch. A single-issue core has **at most two correctors** (a
branch and a trap/mret, on different opcodes), so they never fire the same cycle; assertions
enforce ≤ 1 predictor and ≤ 2 redirects.

## What it drives into the pipeline

At `pcAt` the `fetcher` area sets `up.valid := True`, `up(Global.PC) := pcReg`, and the two
prediction payloads ([`Fetch`](../../src/riscq/riscv/fetch/Fetch.scala)):

- **`Fetch.NEXT_PC_PRED`** — the successor the fetch *decided to fetch next*: the predictor's
  target on a predicted-taken transfer, else PC+4. The execute corrector redirects iff this
  disagrees with the real successor, so with no predictor it degrades to the plain "redirect only
  when taken" rule.
- **`Fetch.PREDICTED_TAKEN`** — the predictor's 1-bit direction. Lets [BranchPlugin](BranchPlugin.md)
  split the misprediction check into a cheap 1-bit *direction* test plus a single 32-bit *target*
  compare, instead of two 32-bit `NEXT_PC_PRED` compares muxed by `take`.

## The next-PC recurrence — the route-bound binder

The `pcReg → predictedNext → pcReg` self-loop is one of the core's two textbook, route-dominated
fmax binders (see [RISCV.md](RISCV.md)). [RiscqParam](RiscqParam.md) exposes three levers here:

- **`pcRegMaxFanout`** (default 16) — `max_fanout` on `pcReg` so Vivado replicates it, giving each
  consumer (BTB index/tag, the +4 adder, the fetcher) a nearby copy. Bit-exact.
- **`pcOneHotRedirect`** (default on) — select the next PC with a balanced one-hot mux
  (`OHMasking.firstV2` + `OhMux.or`) instead of the serial priority `when`-chain, removing the
  `IS_JALR→pcReg` redirect binder. Priority is preserved (high→low: correctors last-registered
  first → fetch redirects → sequential). Bit-exact; helps packed multi-core density.
- **`btbPredictLate`** (default off) — when off, a BTB hit folds the target into the *same*
  cycle's next PC (0-bubble), but that puts the BTB hit + target mux on the recurrence. When on,
  `pcReg` advances purely sequentially (`pcReg→+4→pcReg`) and the BTB re-steers a cycle later via
  a fetch redirect, at a 0→1 bubble cost on a correctly-predicted taken branch. (`pcAdvance` picks
  `pcReg+4` vs `predictedNext` accordingly.)

All three are architecturally identical — the execute corrector backstops every misprediction —
and RVLS-gated.

## PC narrowing

`pcReg` holds the (possibly narrowed) PC **offset**; its reset value is `resetVector`'s in-region
low bits (`resetPcOffset`, == `resetVector` when full-width). The constant high bits are rebuilt
(`Global.fullPc`) at the visible boundaries, not here. See
[RiscqParam.fetchPcWidth](RiscqParam.md).

## Whitebox signals

`pcReg` and `redirectFire` (a corrector won the PC and flushed this cycle) are `simPublic` for the
testbenches and the [RVLS probe](RiscqProbe.md).

## Verification

Exercised by `FetchSim` / `FetchIpcSim` (sequential + redirect behaviour), `BranchIpcSim`
(predictor steering and mispredict recovery), and the full `RvTestSim` under RVLS lock-step.

## Related

- [FetchPlugin](FetchPlugin.md) — consumes `Global.PC` at the fork and `NEXT_PC_PRED` flows on.
- [BtbPlugin](BtbPlugin.md) / [GSharePlugin](GSharePlugin.md) — the predictor that drives
  `newPredictor()` / `newFetchRedirect()`.
- [BranchPlugin](BranchPlugin.md) / [CsrPlugin](CsrPlugin.md) — the redirect correctors.
- [pipeline.md](pipeline.md) — the flush helpers (`throwStagesBefore`, head special-case).
