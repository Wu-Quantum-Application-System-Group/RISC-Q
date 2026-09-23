# LsuPlugin — cacheless load/store unit

**Source:** `src/riscq/riscv/execute/LsuPlugin.scala` · **Package:** `riscq.riscv.execute` · **Type:** FiberPlugin (+ companion `AreaObject`)

## Purpose

The load/store unit: `LB/LH/LW/LBU/LHU`, `SB/SH/SW` over a simplified, single-outstanding data bus.
It is the first thing in the riscq pipeline that ever **back-pressures** — it halts the execute stage
until memory answers.

## Role in the system

Lives at `executeAt` (see [pipeline.md](pipeline.md)). Its master port is `dBus`, a
[DataMemBus](MemBus.md) (a `cmd` request stream + a `rsp` response flow), not raw Tilelink — the full
Tilelink A/D boilerplate lives at the core→fabric boundary (`DataMemBusToTilelink`), keeping the
plugin small. The effective address `rs1 + imm` is already computed by [SrcPlugin](SrcPlugin.md) as
`Execute.ADD_SUB` (loads carry the I-immediate, stores the S-immediate), so the LSU adds no adder of
its own. Loaded data is published as `Execute.LOAD_DATA`; whether the op is a load is the decoded
`IS_LOAD`, read by [WriteBackPlugin](WriteBackPlugin.md).

## Design rationale — issue the request AT the consuming stage

The load-bearing decision: the LSU issues its single outstanding `Get`/`Put` **at `executeAt`, the
stage that consumes the result** — not forked from an upstream stage.

`CtrlLink.forkStream` fires on **bus-accept** (downstream `ready`), *not* on node-advance. With one
outstanding request and **no reorder buffer**, a fork at an upstream stage could issue the *next*
request while the current instruction is still held by a downstream stall — and the response would
then corrupt the held slot. There are two valid answers to this; the LSU and fetch pick different
ones:

- **LSU (here):** issue the request *at the consuming stage*. It is then trivially bound to the one
  instruction sitting there — no id, no reorder buffer needed. The stage simply halts until the
  answer arrives. This is correct precisely because **execute is the oldest stage**: a load/store is
  never flushed mid-flight by an older instruction, so there is no orphaned-response handling.
- **Fetch (contrast):** [FetchPlugin](FetchPlugin.md) needs IPC=1 across multi-cycle memory latency,
  so it forks at `pcAt` (upstream, multi-outstanding) and tags each `Get` with a **per-id reorder
  buffer** to demux responses by id. The LSU does not pay that complexity because a single-issue
  load can simply stall.

This is the depth-1 stripping of VexiiRiscv's `LsuCachelessPlugin` — no AMO, no MMU/PMA, no store
buffer.

### Single-outstanding halt handshake

`cmd` is raised once (`active && !cmdSent`); `cmdSent`/`rspGot` track the in-flight request and reset
at commit (`down.isFiring`); `haltWhen(active && !rspNow)` holds the stage until the response (or its
already-captured copy) is present. Because `dBus.rsp` is a Flow, the LSU is always ready for it.

### Latch operands on the first active cycle

The forwarded operands (`rs1`/`rs2`) arrive through the 1-deep forwarding bypass, whose window is a
**single cycle** — but a load/store *halts* this stage for the whole access. So everything derived
from rs1/rs2 (effective address, store data, the byte offset / shift / mask) is **snapshotted on the
first active cycle** (`first = active && !latched`) and used from then on; otherwise later stall
cycles would re-read closed-bypass garbage. funct3/opcode are StageLink-held and already stable. (The
multiply unit latches operands for the same reason — see [MulPlugin](MulPlugin.md).)

### Wrong-path gate

The branch corrector resolves at `executeAt+1` (`jumpAt`), so an older branch can flush this stage
the same cycle a younger load/store sits in it. `throwWhen` raises `isCancel` (and drops `isFiring`)
but does **not** clear `isValid`, so the plugin must drop `active` on `up.isCancel` itself — otherwise
a wrong-path store would still drive its bus write. It uses the node's own `up.isCancel`, not
BranchPlugin's signal, to avoid a cross-plugin dependency; loads are gated too (no spurious bus
traffic on a wrong path).

## Access model

One simple code path: every access is a **word-sized, word-aligned** bus transfer with a byte mask.
funct3's low bits pick a 1/2/4-byte mask; a store shifts its bytes/mask into the addressed lane; a
load reads the whole word, shifts the addressed bytes down, then sign/zero-extends per funct3.
Misaligned access is out of scope — RV32I requires natural alignment and the tests obey it. The bus
stays XLEN(32)-wide regardless of how narrow the fetch PC becomes, since `rs1+imm` can point anywhere
in the SoC map (put window, readout, data RAM).

## Latency / timing

II depends on the memory: a load/store halts `executeAt` until `rsp` returns (one cycle on the
on-chip data RAM). Straight-line non-memory code is unaffected.

## Verification

The `rv32ui-p-{lb,lh,lw,lbu,lhu,sb,sh,sw,...}` ELFs run under RVLS/Spike in
[RvTestSim](../../src/riscq/riscv/sim/RvTestSim.scala) (the probe whiteboxes the committed access —
address, byte length, value — for the trace backends). [ExecuteSim](../../src/riscq/riscv/sim/ExecuteSim.scala)
covers the surrounding execute datapath. The fabric-wired core runs the same suite end-to-end in
`riscq.soc.sim.RamOnFabricSim`.

```bash
mill runMain riscq.riscv.sim.RvTestSim
mill runMain riscq.soc.sim.RamOnFabricSim
```

## Related

[MemBus](MemBus.md) · [SrcPlugin](SrcPlugin.md) · [FetchPlugin](FetchPlugin.md) ·
[WriteBackPlugin](WriteBackPlugin.md) · [BranchPlugin](BranchPlugin.md) · [pipeline.md](pipeline.md)
