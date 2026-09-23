# PutHub — the board hub: groups, counted barriers, unicast (core ↔ core over puts)

**Source:** `src/riscq/soc/link/PutHub.scala` · **Package:** `riscq.soc.link` · **Type:** `Area`
(`PutHub`) + bundle (`HubBeat`)

One per board, in the SoC top outside every core pblock. It is the only place cores meet: every core's
**system puts** (a store whose node id is ≥ `SocSpecMap.localNodes`, split off the posted link by
[`PutLink.nonLocal`](PutLink.md)) are arbitrated onto one ordered stream and dispatched by node id into
**re-puts** on one ordered broadcast that every core's inbox listens to
([specs/cross-core/02](../../specs/cross-core/02-put-network.md) §4, decisions D3/D4).

## Role in the system

```
  core 0 xput ─┐                                ┌─ dispatch by node ─────────────────────────────┐
  core 1 xput ─┼─ queue ─ round-robin ─ arb ────┤  group g    : board[g](slot) := bit → re-put word │
    …          │                                │  barrier b  : count; last → re-put time to arrivers │ ─▶ out {mask, put}
  core N xput ─┘                                │  inbox i    : forward the put to core i           │      ─▶ per-core replica
                                                └───────────────────────────────────────────────────┘        (valid & mask(i)) ─▶ linkPipe ─▶ core i's merge ─▶ inbox
```

A `HubBeat` is `{mask, put}`: the put (`{offset, data}`, the inbox address form of
[EventLink](EventLink.md)) and the destination mask, bit *i* = core *i*. The SoC top makes one replica of
the beat per core with `valid` gated by that core's mask bit, pipes it like the down-link, and hands it to
the core shell's up-link merge as one more source. So a re-put costs one beat whatever its fan-out.

## Dispatch

| Node (`SocSpecMap`) | Put data | Hub state | Re-put |
|---|---|---|---|
| `groupNode(g)` | `{slot[5:1], bit[0]}` | the group's shared word (master copy) | the **whole word** to every core's `board[g]` (mask = all) |
| `barrierNode(b)` | the expected member `count` | per id: arrival count, the first arrival's count, the arrival bitmap | on the last arrival, `time + releaseSlack` to the **arrivers'** release mailbox (mask = who arrived); the id is cleared |
| `inboxNode(i)` | any word | — | the put unchanged to core *i* (mask = `1 << i`) — a signal into one of its mailboxes |

Barriers are **rendezvous only**: every member arrives and waits, so one release mailbox per core is
enough and a member is never more than one release behind. A disagreeing count from a later arrival
raises the sticky `countMismatch` (host control `0x54`, cleared by `riscqReset`); the barrier still
releases on the first count. Two ids in flight never mix; the same id re-entered in a loop counts afresh.

**Ordering is the contract.** A source's puts are consumed in order (one queue per source, a
round-robin arbiter over them), and every re-put leaves in consumption order on the one broadcast. So a
core that has seen a release has already seen every board bit that was published before that member's
arrival (§3.4 of the spec) — which is why a `remote(g, slot)` read right after `barrier()` needs no
sequence number. A beat into a full per-source queue (depth 8) is dropped: posted semantics, and a core
cannot issue puts faster than one per few cycles against a worst-case round-robin wait of `cores` cycles.

## Several boards (the lane)

With `boards > 1` (a `SocSpec` with `board`/`boards`, on a `with_white_rabbit` build) the hub has one
more source and one more sink: `laneIn` / `laneOut`, [PutLane](PutLane.md) frames from and to the
other board. Node ids carry the board in their top 4 bits for inbox units (`SocSpecMap.inboxNode(board,
core)`); group and barrier nodes are system-wide services. Dispatch then also:

- **forwards** a locally published group bit on the lane (every board applies every publish once,
  order-free because slots are disjoint), a local barrier arrival to the **root** (board 0), and a
  unicast whose board is not this one;
- at the root, **counts every arrival** keyed by `(board, core)` and, on the last one, re-puts the
  release to its local arrivers and sends **one release frame per remote board** with arrivers (the
  frame's offset is that board's arriver mask, its data the root's time); the arbiter is held while
  those frames leave, so they follow the completing arrival in order;
- at a non-root, turns a **release frame** into the local mailbox re-put.

The released value is the root's `time`; White Rabbit keeps every board's `syncTime` aligned, so it
schedules identically everywhere. `PutLaneSim` checks all of this over the real PCS pair.

## Interface & configuration

`PutHub(cores, board = 0, boards = 1, addrWidth = putAddrWidth, groups = groupNodes, barrierIds =
barrierIds, queueDepth = 8, releaseSlack = 0)` — `in: Vec(Flow(Put))` one per core, `laneIn:
Flow(PutFrame)` / `laneOut: Stream(PutFrame)` (tied off on a one-board build), `time: UInt(32)` (a
local replica of `syncTime`), `out: Flow(HubBeat)`, `countMismatch: Bool`. `releaseSlack` is added to the stamped time; it
stays 0 because software adds `LEAD` to the returned `t0` before scheduling, and `LEAD`'s margin covers
the delivery.

Node ids on one board: `localNodes = 16` (the core's own channels), then `groupNodes = 4`,
`barrierIds = 16`, then one inbox node per core. All from `SocSpecMap`; the python twin is
`riscq.map.SocMap` (`GROUP_NODE0` / `BARRIER_NODE0` / `INBOX_NODE0`), which `riscq_map.h` emits for the
firmware ops `publish` / `barrier` / `remote` / `signal` / `wait_signal` (`software/fw/riscq.h`).

## Latency / timing

Store → bridge (1) → `linkPipe` (4) → hub queue + arbiter (2) → decode + two pipeline stages + the
dispatch stage (4) → replica + `linkPipe` (5) → core merge (2) → `linkPipe` (4) → inbox (1) ≈ 23 cycles
from a publish to every core's board. The stages are what closes the hub at 500 MHz (R3): each
per-source queue feeds the arbiter through a register, then the arbiter mux, the node decode, the
pre-read of the addressed barrier state and its read-modify-write each get a cycle; a barrier beat
directly behind one for the same id waits one cycle for that write. The floorplanned 14q build with the
hub closes `dspClk` at **−0.111 ns** (`vivado-scripts/riscvsoc`, `build/14q-putnet-r3e`), better than the
−0.156 ns of the build without it; the single-cycle hub v0 had closed at −2.331 ns.
`XcoreCpuSim` measures the whole publish → barrier → schedule of two cores at ~46 cycles after reset
release. Every stage is a plain register; nothing is timing-critical, and the hub sits in the floating
region between the core column and the datapath (it must not enter the per-core pblocks).

## Verification

- `riscq.soc.sim.PutHubSim` — the hub alone with four sources against a software model: random
  same-cycle publish bursts track the group words; random barrier subsets in random order, two ids in
  flight, re-entry — exactly one release per epoch to exactly the arrivers, stamped near the hub time;
  publish-then-arrive orders the board beat before the release; unicast lands with mask `1 << i`; a
  disagreeing count raises `countMismatch`.
- `riscq.soc.sim.XcoreCpuSim` — two RISC-V cores running `sw/xcore.S`: each publishes a bit to group 0,
  core 0 signals core 1's mailbox, both `barrier(0, 2)`, core 1 `wait_signal`s, each fires a gate pulse
  at `t0 + 1024` only if the other's bit is set. Both cores read the same `t0`; the mailbox carried
  `0x77` and was consumed; DAC 1 (core 1) rose at exactly `t0 + 1024` (+ the converter pipe) for `dur`
  batches, DAC 8 (core 0) stayed silent.

- `riscq.soc.sim.PutLaneSim` — two hubs over the WR lane ([PutLane](PutLane.md)): the multi-board dispatch.

```bash
mill runMain riscq.soc.sim.PutHubSim
mill runMain riscq.soc.sim.XcoreCpuSim
mill runMain riscq.soc.sim.PutLaneSim
```

## Related

[EventLink](EventLink.md) (the inbox kinds it writes: `latest`, `mailbox`) · [PutLink](PutLink.md)
(`nonLocal`) · [RiscvSoc](RiscvSoc.md) · [PulseTableSoc](PulseTableSoc.md) ·
[specs/cross-core/02](../../specs/cross-core/02-put-network.md)
