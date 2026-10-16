# PipelinePlugin — the single CtrlLink-chain skeleton

**Source:** [`src/riscq/riscv/misc/PipelinePlugin.scala`](../../src/riscq/riscv/misc/PipelinePlugin.scala)
· **Package:** `riscq.riscv.misc` · **Type:** `FiberPlugin` (named `"pipeline"`)

The pipeline skeleton: **one** `CtrlLink` chain (`spinal.lib.misc.pipeline`) that every feature
plugin injects into. It owns no datapath of its own — it allocates the stage nodes, connects them
into a chain, splices in skid buffers, and runs the SpinalHDL pipeline `Builder` exactly once.
The stage indices are partitioned into Fetch / Decode / Execute by [`RiscqParam`](RiscqParam.md).

## Role in the system

Every other core plugin reaches the pipeline through this one:

```
RiscqParam (stage indices)
        │
   PipelinePlugin ── ctrl(i) ──▶ feature plugins do `new pp.ctrl(i).Area { ... }`
        │                         (PcPlugin, FetchPlugin, Decoder, RegFile, Src, IntAlu,
        │                          Branch, Lsu, Csr, WriteBack, Mul, Btb, GShare)
        └── Builder(all links + ctrls + skids)   ← runs once, in the build phase
```

A plugin grabs a stage with `host[PipelinePlugin].ctrl(i)` and injects logic into it; the chain
itself, the inter-stage registers, and the back-pressure wiring are all this plugin's job.

## Why one chain, not three

VexiiRiscv splits its pipeline into **three** chains (fetch / decode / execute) because it has an
RVC aligner between fetch and decode and a multi-lane execute. riscq has **neither** — it is
single-issue, in-order, RV32I (+ Zmmul) with one 32-bit instruction per fetch — so a single in-order
`CtrlLink` chain is the simpler exact fit. Stage *roles* (Fetch / Decode / Execute) are then just
index ranges within the one chain, derived from [`RiscqParam`](RiscqParam.md), rather than
separate pipeline objects.

## The stage nodes

`ctrl(id)` lazily allocates (and memoises) a `CtrlLink` per integer index, named `ctrl<id>`.
Plugins request only the indices they touch; in the build phase the plugin fills any skipped
intermediate indices up to the max so the chain is contiguous. The chain is connected
`ctrl(i) → ctrl(i+1)` with a plain `StageLink` (one data register; `ready` propagates
combinationally backward), except at the skid boundaries below. The last stage never
back-pressures (`ctrls.last.down.ready := True` — the core does not stall its own retirement
yet). A `MAX_FANOUT=128` attribute caps the `regReadAt` (`ctrl(3)`) ready-net fanout (guarded so
the short decode-only sub-pipeline, which has no stage 3, doesn't trip it).

## The elaboration protocol (`elaborationLock`)

The Builder can only run **after** every plugin has finished registering its stages. That is
enforced with a `Retainer`:

1. `PipelinePlugin` exposes `val elaborationLock = Retainer()`.
2. Each feature plugin, in its `during setup` block, does `val lock = retains(pp.elaborationLock)`
   and holds it until it has injected all its `ctrl(i).Area` logic, then `lock.release()`.
3. `PipelinePlugin.logic` (in the build phase) `await()`s the lock, then builds all the
   `StageLink`s/`S2MLink`s and calls `Builder(all)` **once**.

This is why plugin elaboration order doesn't matter: the lock gates the single Builder call on
all contributors, and shared constants are read through the blocking [`Global`](../../src/riscq/riscv/Global.scala)
`Database`.

## Skid buffers (`skidAfter`)

For each boundary index in [`RiscqParam.skidAfter`](RiscqParam.md) (default `Seq(1)` = the
fetchData→decode boundary), the plain `StageLink` is replaced by a **skid buffer**: a
`StageLink(ctrl_i.down → skid.up)` (keeps the single data register) followed by an
`S2MLink(skid.down → ctrl_{i+1}.up)`. The S2MLink sets `skid.down.ready := !rValid` — a *local*
register — which **cuts the backward `ready` chain** so an execute-stage halt/flush no longer
ripples combinationally all the way back to the fetch fork. The buffer is transparent when empty,
so steady-state throughput and latency are unchanged; it only stores one in-flight item when the
downstream stalls. The skid nodes are created in the **setup** phase (`skidSetup`) so the
build-phase correctors can reference them before the Builder runs.

## Flushing wrong-path instructions

The branch/CSR correctors ([BranchPlugin](BranchPlugin.md), [CsrPlugin](CsrPlugin.md)) cancel the
younger, wrong-path instructions in the range `[pcAt, untilExclusive)` via two helpers:

- **`throwStagesBefore(cond, untilExclusive)`** — throws each *ctrl* stage in range off the
  `up.ready` spine (`throwWhen(usingReady = false)` = `forgetOne`): the redirect clears that
  stage's StageLink valid register directly rather than forcing `up.ready := True` and riding the
  ready chain into every register's clock-enable. This keeps the high-fanout redirect net off the
  serial ready spine (riscq's analog of VexiiRiscv's centralized age-based `isFlushedAt`). The
  **head** (`pcAt`) is special — it has no upstream StageLink register, so it cannot take a
  `forgetOne` and is thrown with `usingReady = true`; that is correctness-sufficient because
  `throwWhen` always `terminate`s (`down.valid := False`) and [PcPlugin](PcPlugin.md) redirects
  `pcReg` directly so the next fetch is correct.
- **`throwSkidsBefore(cond, untilExclusive)`** — a ctrl-stage throw forces `ctrl(b+1).up.ready`,
  which clears the S2M buffer but does **not** reach the StageLink register at `skid.up`. So a
  wrong-path instruction held in a *full* skid would otherwise survive and later commit. This
  helper raises a `forgetOne` on the skid (`throwWhen(usingReady = false)` →
  `down.valid clearWhen forgetOne`) for every skid whose boundary lies strictly inside the flush
  range (`b >= pcAt && b+1 < untilExclusive`). It matters at decode→execute because the execute
  stage routinely halts and back-pressures decode (filling the skid during a flush); at the old
  fetch→decode boundary the buffer almost never filled during a flush, which hid the bug.

## Verification

Exercised indirectly by every core sim — the fetch/decode unit tests build short sub-pipelines
(which is why the `ctrls.length > fetchLatency + 2` and `idMax` guards exist), and the full `RvTestSim` /
`BranchIpcSim` / `FetchIpcSim` drive the complete chain with skids and flushes under RVLS
lock-step.

## Related

- [RiscqParam](RiscqParam.md) — the stage indices and `skidAfter` this plugin reads.
- [PcPlugin](PcPlugin.md) / [BranchPlugin](BranchPlugin.md) / [CsrPlugin](CsrPlugin.md) — the
  redirect/flush callers.
- [RISCV.md overview](RISCV.md) — the stage map and the plugin-on-a-host architecture.
