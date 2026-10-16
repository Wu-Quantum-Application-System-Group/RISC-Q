# FetchPlugin — cacheless pipelined instruction fetch

**Source:** [`src/riscq/riscv/fetch/FetchPlugin.scala`](../../src/riscq/riscv/fetch/FetchPlugin.scala)
· **Package:** `riscq.riscv.fetch` · **Type:** `FiberPlugin`

Pipelined, cacheless instruction fetch over a Tilelink master bus (`iBus`), sustaining **IPC = 1**
on straight-line code despite instruction-memory latency. It is the depth-N stripping of
VexiiRiscv's `FetchCachelessPlugin` — no PMA / MMU / PMP / trap.

## How it works: fork at `pcAt`, join at `fetchDataAt`

The fetch is split across the [pipeline](pipeline.md):

```
  pcAt (fork)                         fetchDataAt = pcAt + fetchLatency (join)
  ──────────                          ────────────────────────────────────────
  forkStream() ──▶ iBus.a GET         iBus.d ──▶ reorder buffer ──▶ Fetch.WORD
  (tag with reserveId)                (demux by source id, read by ID)
```

A **fork** at `pcAt` issues a `Get` for the PC sitting there, tagged with the next source id; a
**join** `fetchLatency` stages later picks the word back up. Decoupling issue from consumption
lets several `Get`s be in flight at once (one per source id), so the fetch slots stay full across
the memory latency instead of stalling on every word. To sustain IPC = 1 under a memory response
latency of `L` cycles, set [`fetchLatency`](RiscqParam.md) `>= L`; `idCount` distinct ids then
cover the in-flight requests (`idCount = 1 << log2Up(joinAt − forkAt + 1)`, a clean power of two
so a binary counter cycles ids and each id stays free long enough for its slot to reach the join).

## Why the per-id reorder buffer is required

This is the load-bearing design point. **`CtrlLink.forkStream` fires on bus-accept (downstream
`ready`), not on node-advance.** A naive single-outstanding fetch that forks at an upstream stage
is therefore *unsafe*: the fork can issue the *next* request while the current instruction is held
by a downstream stall, and the response would corrupt the held slot. There are two valid answers
in riscq:

1. Issue the `Get` **at the consuming stage** so it is trivially bound to the one instruction
   there — what the [LSU](LsuPlugin.md) does (single outstanding).
2. Fork upstream but tag each `Get` with a **per-id reorder buffer** so responses demux by id —
   what the fetch does, to keep multiple `Get`s outstanding and hit IPC = 1.

The buffer is one word slot + an "in flight" flag per id (`buffer.words` / `buffer.inflight`,
indexed by source id), plus a `reserveId` counter handing out the next id. Responses may return
**out of order** (different ids) or **before** their slot reaches the join, so they are
demultiplexed by id on `iBus.d.fire` and read back at the join by the carried `ID` payload — with
a **same-cycle bypass** for a response landing exactly when its slot arrives (`bypass = iBus.d.fire
&& iBus.d.source === id`).

## Flush handling — orphaned in-flight Gets

A branch flush can orphan an in-flight `Get` (its instruction is cancelled before the word
returns). The response still drains — it clears the id's `inflight` flag and the dropped word is
simply never read — while the reused id is held off by `inflight` until the orphan returns. The
fork stalls (`haltWhen(full)`) whenever the id it is about to reserve is still awaiting its
response. This is what makes forking at an upstream stage safe: each request carries its own id
into the buffer.

## The bus

`iBus` is a **get-only** Tilelink master ([MemBus](MemBus.md) with `withStore = false`,
`idCount > 1`). Get-only means `a.mask`/`a.data`/`a.corrupt` are null — the fork drives only
`opcode = GET`, `param`, `source`, `size`, and `address`. The address is **full XLEN-wide** (it
must reach the instruction RAM at the fabric's decode base `0x8000_0000`), so when the fetch PC is
narrowed it is reconstructed with `Global.fullPc(PC)` (= `pcBase | offset`) onto the bus — see
[RiscqParam.fetchPcWidth](RiscqParam.md). `iBus.d.ready` is tied high (the buffer always accepts a
response).

## Latency / throughput

- **Latency:** `fetchLatency` stages from fork to join (default 1).
- **Throughput:** IPC = 1 on straight-line code when `fetchLatency >= L` (the memory response
  latency); the multi-outstanding fork keeps `fetchLatency` requests in flight so a word arrives
  every cycle. The fork stalls only when its next id is still busy (buffer pressure) or the
  downstream halts.

## Whitebox signals

The committed fetch is whiteboxed at the join for the testbench / [RVLS probe](RiscqProbe.md):
`dbgFiring`, `dbgPc` (reconstructed full PC for the Konata trace), `dbgWord`, plus `iBus` itself —
all `simPublic`.

## Verification

`FetchSim` checks correct fetch behaviour; `FetchIpcSim` confirms steady IPC = 1 across memory
latency (the whole point of the reorder buffer); the full `RvTestSim` drives real ELFs under RVLS
lock-step.

## Related

- [PcPlugin](PcPlugin.md) — supplies `Global.PC` at the fork; the join carries it on.
- [MemBus](MemBus.md) — the get-only, multi-outstanding `iBus` parameters.
- [pipeline.md](pipeline.md) — the fork/join stages and skid/flush mechanics.
- [RiscqParam](RiscqParam.md) — `fetchLatency`, `pcAt`, `fetchPcWidth`.
