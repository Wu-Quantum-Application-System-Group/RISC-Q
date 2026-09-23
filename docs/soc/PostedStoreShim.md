# PostedStoreShim — local-ack posted-store adapter

**Source:** `src/riscq/soc/fabric/PostedStoreShim.scala` · **Package:** `riscq.soc.fabric` · **Type:** `Component`

## Purpose

Makes CPU stores effectively non-blocking. It sits on the core's simplified
[`DataMemBus`](../riscv/LsuPlugin.md) and **acks a write locally in one cycle** so the store retires
immediately, while the write drains downstream in the background. Reads pass through and return the
slave's real response. The verified [LsuPlugin](../riscv/LsuPlugin.md) is left completely untouched —
it keeps its plain blocking, single-outstanding contract (send `cmd`, wait for `rsp`).

## Role in the system

Spliced inside [`RiscqFiber`](RiscqFiber.md), between the LSU and the `DataMemBusToTilelink` bridge:

```
LsuPlugin.dBus ──► PostedStoreShim ──► DataMemBusToTilelink ──► dBus (Tilelink fabric master)
                   (write: ack here, 1 cyc)
                   (read : forward, real rsp)
```

It is the **local-store** half of the SoC's posting strategy; the [`PutBridge`](PutBridge.md)
is the same idea applied to channel writes over the long posted link (see [ARCH](ARCH.md) §5.1). Both exist
because the LSU waits for a d-channel ack — posting the ack near the core keeps that arc short and
fast.

## Design rationale

- **Why post stores at all.** The LSU issues one access and *halts* until its `rsp`. If every store
  waited for the slave's round-trip ack, store-heavy code would stall on the bus latency. Acking writes
  locally lets the CPU commit the store next cycle.
- **Store buffer preserves program order.** An accepted write is dropped into a small `StreamFifo`
  (`bufDepth` entries) and drained downstream in order. A read is **held until the buffer is empty**, so
  it can never overtake a not-yet-written store — store→load RAW stays correct (the read is only
  forwarded after the prior store's downstream ack, i.e. after the slave committed the write).
- **One transaction outstanding downstream.** A single `down.cmd` is in flight at a time, and
  `inflightIsRead` remembers its kind, so a returning `down.rsp` is unambiguous: a read response is
  forwarded to `up.rsp`, a store ack is swallowed.
- **`up.rsp` stays a backpressure-free `Flow` — no arbiter.** Because the upstream master is blocking
  single-outstanding, the synthetic write-ack (a `RegNext` of an accepted write) and a forwarded read
  response are **never live in the same cycle** (the master is idle of writes whenever a read is in
  flight), so they combine with a plain OR — no arbitration needed.
- **Leaves the core verified.** The alternative — making stores posted inside the LSU — would touch
  verified core logic. The shim does it at the bus boundary instead, and rides the *simplified* bus so
  it never has to speak Tilelink.
- **Must be pinned to the core's reset.** The shim is stateful; [`RiscqFiber`](RiscqFiber.md) wraps it
  in the captured core clock domain so the host reset clears its buffer. Without that it powers up with
  a garbage FIFO and drains a stray store on release.

## Latency / timing

- **Write:** retires in **1 cycle** — `up.cmd` accepted this cycle, `up.rsp` (the local ack) next
  cycle, mirroring the slave's single-cycle response latency.
- **Read:** the downstream slave's real latency, plus any wait for the store buffer to drain first.
- The CPU only back-pressures when the buffer is full (`bufDepth` posted stores in flight).

## Contract & caveats

- **Preserved:** same-slave store ordering, and store→load RAW (the buffer-empty gate).
- **Relaxed:** store→store ordering across *different* downstream slaves — the early ack lets the CPU
  proceed before a write lands, so two stores to different slaves may complete out of order. Acceptable
  for this SoC's memory map; do not rely on cross-slave store ordering.

## Interface & configuration

- `p : DataMemBusParam` — the bus geometry (matches the LSU's `dBus`).
- `bufDepth` (default 4) — store-buffer depth = posted stores in flight before the CPU back-pressures.
- `io.up` (slave `DataMemBus`) ← LSU; `io.down` (master `DataMemBus`) → the Tilelink bridge.

Standalone `Component` so it can be unit-tested in isolation.

## Verification

[PostedStoreShimSim](../../src/riscq/soc/sim/PostedStoreShimSim.scala) drives `io.up` exactly as the
LSU would (strictly single-outstanding, blocking) and models `io.down` as a byte-masked golden memory
with randomised back-pressure and random response latency. It fires 3000 random reads/writes over a
small (8-word) address set — so store→load RAW collisions are frequent — checking every read against an
architectural reference updated at issue time, then a final read-sweep proves every posted store
drained correctly. This proves the CPU never deadlocks, reads return correct data, RAW is honoured, and
the buffer/back-pressure path is sound. The splice is additionally covered under RVLS by
[RamOnFabricSim](RiscqFiber.md).

```bash
mill runMain riscq.soc.sim.PostedStoreShimSim
```

## Related

[RiscqFiber](RiscqFiber.md) · [LsuPlugin](../riscv/LsuPlugin.md) · [PutBridge](PutBridge.md) ·
[ARCH](ARCH.md)
