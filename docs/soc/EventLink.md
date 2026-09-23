# EventLink — the up-link: puts into the core's inbox (DSP → core)

**Source:** `src/riscq/soc/link/EventLink.scala` · **Package:** `riscq.soc.link` ·
**Type:** descriptors (`EventSource`, `SinkSpec`, `EventPlan`) + helpers (`object EventLink`:
`resultSource`, `serialize`, `merge`) + the core-side sinks (`ReadoutResultSink`, `EventFifoSink`, `LatestSink`, `MailboxSink`, `Area`s)

The link's **only** return path: one narrow posted `Flow(RfCmd)` per core carries **word writes into the
core's inbox** — every reporting channel's report, serialised into puts at the offsets of its sink's
register window, which the CPU then reads locally. It is the up-direction counterpart to the down-link
([RfLinkBridge](RfLinkBridge.md) / [RfLink](RfLink.md)) and, since
[specs/cross-core/02](../../specs/cross-core/02-put-network.md) R1, carries the **same bundle** in both
directions: nothing on the link is typed, and the same path carries the board hub's re-puts (group
words, barrier releases, signals — [PutHub](PutHub.md)) since R2.

## Role in the system

```
  reporting channel ──▶ EventSource ─▶ serialize ─┐                        ┌─▶ ReadoutResultSink ─▶ CPU
     (decoder res / DIO edges)                    ├─ merge ─ linkPipe ─▶ ───┤     (halting res read)
  reporting channel ──▶ EventSource ─▶ serialize ─┘  Flow(RfCmd{offset, data}) └─▶ EventFifoSink ─────▶ CPU
         (DSP region)                                 posted, up, no ack         (halting pop_event)
                                                                               (core region, local halt)
```

A put is `{offset, data}`: `offset` (`EventLink.inboxAddrWidth` = 12 bits) is the word's address
relative to `sinkBase` (`0x4200`) — sink `k`'s word `w` is `k·0x20 + 4·w` — and `data` the 32-bit word.
The sinks are plain address decodes into the registers the CPU reads.

**Why a posted up-`Flow`, not a bus read.** The reports genuinely live at the converter edge (the other
RF reads — `time`, `waitTimeCmp` — are core-local; see [ControlMemMaps](ControlMemMaps.md)). A
back-pressured halting read across a long bus would stall the CPU there-and-back for the whole
round-trip, defeating the point of moving the core away. Instead the DSP **pushes** and the CPU halts on
a **local** copy — [ARCH](ARCH.md), "read paths are split".

## Structure

### Reporters (`EventSource`, DSP side)

`EventSource(kind, data: Flow[Bits], time: Option[UInt])` — `kind` picks the sink, `data` carries the
payload beats, `time` (FIFO kinds) the cause time of each beat. Channel kinds that own a reporter expose
it as `Channel.event` ([RfChannels](RfChannels.md)); the demod's is built by the core shell from the
decoder it feeds — the one kind whose source needs the ADC.

`EventLink.resultSource(resValid, res, real, imag, accWidth)` builds the readout result's reporter: the
decoder's `res.valid` **level** becomes two beats — one on its rising edge carrying
`{settled = 1, res, real, imag}`, one on its falling edge carrying `{settled = 0, …}` — so the level is
rebuilt at the sink with the link's delay and no beat is ever missed.

### Serialisation (`EventLink.serialize`) — one report, several puts

`serialize(source, sink, queueDepth = 4)` turns a reporter into an ordered `Stream(RfCmd)`: each event
is queued (`queueDepth`; a beat into a full queue is **dropped**, posted semantics, which the FIFO
sinks' sequence numbers expose) and then walked word by word, one put per cycle. The word list is the
only place a sink kind's put protocol is written:

| Sink kind | Puts per event | Order |
|---|---|---|
| `result`, settled beat | 3 | `real`@+4, `imag`@+8, then `{valid = 1, res}`@+0 — `valid` last, so the integrals are in place when the level rises |
| `result`, cleared beat | 1 | `{valid = 0}`@+0 (the stale integrals are left alone) |
| `fifo` | `ceil(dataWidth/32)` + 1 | data words @+0/+4/+8, then the cause `time`@+0x10, which **commits** the entry |
| `latest` | 1 per word | a plain word overwritten by the put to its offset (the [PutHub](PutHub.md)'s group board) |
| `mailbox` | 1 | one word + a full flag: the put fills it, the halting read consumes it (the barrier release, the signal mailboxes) |

`merge(sources, sinks)` joins the reporters' streams onto the one up-link `Flow(RfCmd)`: one reporter is
its serialiser alone (the qubit builds); several share a round-robin arbiter with no lock — a put is
self-contained and every sink is written by exactly one reporter, so interleaving is harmless.

### The plan (`EventPlan`) — the only kind → sink table

`EventPlan(spec: CoreSpec, readoutAccWidth = 32)` derives a core's whole up-link layout from its channel
list, so the hard [`RiscvSoc`](RiscvSoc.md) and the core shell agree without either naming a channel kind:

| Channel kind | Sink kind | Payload |
|---|---|---|
| `demod` | `result` | `{settled, res, real, imag}`, `2 + 2·accWidth` bits |
| `dio` | `fifo` | `{changed[15:0], levels[15:0]}` (32 bits) + the cause time |

`reporters` are the core's channels of a reporting kind, in list order; `sinks` gives each a
`SinkSpec(name, kind, tag, base, dataWidth)` with `tag` = its order among the reporters and
`base = 0x4200 + 0x20·tag` (`EventLink.sinkBase` / `sinkStride`) in the core's control block. The qubit
builds have exactly one reporter, so their demod sink keeps `res`@`0x4200`, `real`@`0x4204`,
`imag`@`0x4208`.

`xcoreSinks` are the **cross-core inbox registers** every core has past the channels' sinks (at most 8
of those), written only by the [PutHub](PutHub.md)'s re-puts; `allSinks` = both, what `RiscvSoc` builds:

| Name | Kind | CPU address | Written by | Read |
|---|---|---|---|---|
| `board` | `latest` | `0x4300 + 4·g` (`groupNodes` = 4 words) | a group re-put (the whole shared word) | `remote(g, slot)` = one load, `>> slot & 1`, non-halting |
| `release` | `mailbox` | `0x4320` | a barrier release (the released time) | `barrier()`'s halting load, consume-on-read |
| `mbox0`, `mbox1` | `mailbox` | `0x4340`, `0x4360` | a unicast `signal` from the one core bound to it | `wait_signal(m)`, halting, consume-on-read |

### `ReadoutResultSink` — the `result` sink

Latches `real`/`imag` from their puts and `{valid, res}` from the +0 put, so the decoder's `res.valid`
level is rebuilt one link delay later. Its `mapping(factory)` contributes the read map to the
core-local [MemMapFiber](MemMapFiber.md):

| Offset | Read |
|---|---|
| `base + 0` | `res` — **HALTS** until the integral has settled; non-consuming, so **idempotent** |
| `base + 4` | `real` (SF(32) accumulator) |
| `base + 8` | `imag` |

There is no `arm` and no consume. Because the level is low exactly while the next window integrates, a
`res` read that races a fresh window halts until it settles. **Freshness is a software timing contract**,
not a hardware clear: the level holds the *previous* window's result high through the `LEAD` gap between
a `play` and the window opening, so software waits past the window's opening
(`wait_until(t + RQ_RO_LEAD)`) before reading — past `winStart` the stale level has dropped, so the
halting read can only return the new window ([specs/new-readout-decoder](../../specs/new-readout-decoder/README.md) §2.4).
This contract is **unchanged** by the put refactor; the settled result lands two cycles later than the
single wide beat did, inside `READOUT_LEAD`'s margin.

### `LatestSink` / `MailboxSink` — the cross-core kinds

`LatestSink(words, base)` is `words` plain registers, each overwritten by the put to its offset and read
back non-halting. `MailboxSink(base)` is one word plus a full flag: the put to `+0` fills it, the read at
`base` **halts** while empty and **consumes** (clears the flag). A second put before the read overwrites
the word and the flag stays set — which is why a mailbox has exactly one sender by contract (a barrier
release can only follow this core's own arrival; a signal mailbox is bound to one sending core at
compile time) and many-to-one traffic uses a `fifo` with the sender in the data.

### `EventFifoSink` — the `fifo` sink

A consume-on-read queue (depth 8) for edge-like reports: the data-word puts fill a staging register and
the `time` put commits the entry with a running 32-bit sequence number. The queue is fed
`toFlow.toStream`, so a commit into a full queue is dropped — but the counter still advances, so a
**gap in `seq` means an event was lost**.

| Offset | Read |
|---|---|
| `base + 0` | `pop` — **HALTS** until an event is queued, returns its first data word and **consumes** it |
| `base + 4` / `+ 8` | data words 1 / 2 of the **last popped** event (payloads up to 96 bits) |
| `base + 0x10` | `time` — the batch time of the popped event's cause |
| `base + 0x14` | `seq` — its sequence number (gaps = overflow) |
| `base + 0x18` | `count` — the queue occupancy (non-halting) |

The firmware ops are `pop_event` / `event_word` / `event_time` / `event_seq` / `event_count`
(`software/fw/riscq.h`); the sink base folds from a channel's `.sink` in the kernel language
([TimedDio](TimedDio.md)).

## Latency / timing

The up-path adds `linkPipe` plain `RegNext` stages (default 4) plus the serialisation (one cycle per
put, three for a settled readout result), absorbed like the down-link. The sink read and its halt are
local single-cycle arcs. Pipe depth is a floorplan knob, never a timing-closure one. Boundary width:
`12 + 32 + valid` wires, down from the typed beat's `67 … 100`.

## Verification

- `riscq.soc.sim.EventSinkSim` — the generic up-link: two reporters (a `result` source and a `fifo`
  source with a cause time) are serialised into puts and share one `Flow(RfCmd)` through
  `EventLink.merge` and `linkPipe` stages into their two sinks at `0x4200`/`0x4220`. Asserts the FIFO
  sink's `pop` halts, returns the first data word and consumes it, with `time`/`seq` reading back
  exactly and `count` the occupancy; that a beat of each reporter landing on the **same cycle** reaches
  its own sink (the arbiter never drops or misroutes); and that the result sink still rebuilds the level
  (`res` halts, is idempotent, carries the beat's value).
- `riscq.soc.sim.ReadoutResultLinkSim` — a real [ReadoutDecoder](../dsp/ReadoutDecoder.md) integrating a
  tone over two carrier-`Flow` windows; the level is carried as puts and rebuilt by the sink. A
  `MasterAgent` reads `res`/`real`/`imag` from the sink's local map (the `res` read halts until the
  integral settles) and the values are **bit-exact** vs the windowed-demod golden. The freshness
  contract is proven directly: a read before the first window settles halts and returns A; re-reading
  with no new window returns A again (**idempotent**); the rebuilt `valid` drops while window B
  integrates and rises on its settle; a read after waiting past B's opening returns B. Swept over
  `linkPipe ∈ {0, 4, 16}`.
- `riscq.soc.sim.TimedDioSim` — the `fifo`-kind reporter at the source: every input edge posts
  `{changed, levels}` stamped with the batch the changed sample was taken ([TimedDio](TimedDio.md)).
  End-to-end through the sink, `software/tests/test_dio.py` has a kernel's halting `pop_event` return the
  host-driven edge with its time and sequence number.

```bash
mill runMain riscq.soc.sim.EventSinkSim
mill runMain riscq.soc.sim.ReadoutResultLinkSim
mill runMain riscq.soc.sim.TimedDioSim
```

## Related

- [ReadoutDecoder](../dsp/ReadoutDecoder.md) — produces `{res, real, imag}` and `res.valid`.
- [TimedDio](TimedDio.md) — the `fifo`-kind reporter (input edges + cause time).
- [RfLink](RfLink.md) / [RfLinkBridge](RfLinkBridge.md) — the matching down-link, the same `RfCmd`.
- [RiscvSoc](RiscvSoc.md) — instantiates the sinks from the plan's `SinkSpec`s.
- [SocSpec](SocSpec.md) — the channel list `EventPlan` reads.
- [ControlMemMaps](ControlMemMaps.md) — the other (core-local) RF reads.
- [ARCH](ARCH.md) — the read-path split ("read paths are split").
- [specs/cross-core/02](../../specs/cross-core/02-put-network.md) — the put network this is R1 of.
