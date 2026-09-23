# PutLink — the posted-write payload, pipe, and demux

**Source:** `src/riscq/soc/link/PutLink.scala` (the `Put` bundle and the `pipe`/`demux`/`nonLocal` helpers
of `object PutLink`) · **Package:** `riscq.soc.link` · **Type:** Bundle + helper object

The narrow, one-way, posted link itself: the `Put` write bundle, the `RegNext` pipe that spans
distance, and the address demux that fans the single stream to each converter-edge channel. `Put` is the
one beat type on every link that crosses a core's boundary — the down-link to the core's channels, the
up-link into its inbox ([EventLink](EventLink.md)) and the board hub's puts ([PutHub](PutHub.md)). On the
down-link the [PutBridge](PutBridge.md) produces the stream and the channels (e.g.
[PulseParamBuffer](PulseParamBuffer.md), [TimedDio](TimedDio.md)) consume it.

## `Put` — one posted register write

```scala
case class Put(addrWidth: Int) extends Bundle {
  val address = UInt(addrWidth bits)   // byte offset within the receiver's window
  val data    = Bits(32 bits)          // the CPU's 32-bit store word
}
```

A single 32-bit store, decoded by the far-side channel exactly as the old `SlaveFactory` did (16-bit
fields packed at bit 16). Carried in a `Flow`, so **no ack on this path** — the bridge terminated the
CPU's TileLink D channel locally. This is the whole reason the link can be pipelined to any length:
nothing on it waits for a reply.

## `PutLink.pipe` — spanning distance

```scala
def pipe[T <: Data](flow: Flow[T], depth: Int): Flow[T]
```

Adds `depth` plain `RegNext` stages to a posted stream (`depth = 0` is identity). This is the
**timing-insensitive long-haul link**: every stage is an ordinary register, so the placer can stretch it
across the die. `depth` is the per-direction `linkPipe` knob (default 4) — more stages buy more physical
distance / timing slack between the core and the converters at the cost of a constant, predictable latency
that the lead-time scheduler absorbs (see [PutBridge](PutBridge.md) and [ARCH](ARCH.md) §2). Because
nothing on the path is timing-critical, the depth never needs to be tuned for fmax — only for floorplan
reach.

> In `RiscqRfWithPulseTableFiber` the actual stages are built by a local `getPipe` that also tags each
> `RegNext` `DONT_TOUCH` so the placer cannot collapse the chain; `PutLink.pipe` is the plain version used
> in the sims. Same shape, same posted semantics.

## `PutLink.demux` — fan to one sub-window

```scala
def demux(cmd: Flow[Put], base: BigInt, size: BigInt, outWidth: Int): Flow[Put]
```

Routes the bridge's single ordered stream to one channel's address window: the output is valid only when
the address falls in `[base, base+size)`, and is **rebased** to that window (the low `outWidth` bits). It
is **pure combinational routing — no arbiter, no collision**, because a `Flow` has no back-pressure and the
far-side channels are independent (each only reacts to addresses in its own window). The per-core fiber demuxes the put
window into one `0x10000` sub-window per entry of the core's channel list — channel `k` at `k·0x10000`
([SocSpec](SocSpec.md)). On the qubit builds that is gate drive `@0x0`, readout drive `@0x10000`, demod
carrier `@0x20000`, with `@0x30000` unmapped (the decoder has no CPU-facing registers).

## `PutLink.nonLocal` — the system half of the stream

```scala
def nonLocal(cmd: Flow[Put], localNodes: Int): Flow[Put]
```

The complement of the channel demuxes: valid for beats whose node (`address >> 16`) is `≥ localNodes`
(`SocSpecMap.localNodes = 16`), i.e. every put that is not for one of the core's own channels. The
address is passed through whole — the board hub dispatches on `{node, offset}`
([specs/cross-core/02](../../specs/cross-core/02-put-network.md) §4). Not yet consumed by any top (R0 of
that spec); `PutBridgeSim` checks the routing.

## Latency / timing

`pipe(·, d)` adds exactly `d` cycles; `demux` is combinational (0 cycles). Every register is a plain
`RegNext` with no enable and no feedback — there is no critical path to close on the link.

## Verification

No dedicated sim — the pipe and demux are exercised inside the link sims: `PutBridgeSim` asserts the
demux routes each beat to the correct rebased sub-window, and `ReadoutResultLinkSim` sweeps the pipe over
`linkPipe ∈ {0, 4, 16}` to show the path is distance-tolerant (the result is bit-exact at every depth).

```bash
mill runMain riscq.soc.sim.PutBridgeSim
mill runMain riscq.soc.sim.ReadoutResultLinkSim
```

## Related

- [PutBridge](PutBridge.md) — produces the `Put` stream and acks the CPU locally.
- [PulseParamBuffer](PulseParamBuffer.md) / [RfChannels](RfChannels.md) — the demuxed consumers.
- [EventLink](EventLink.md) — the matching up-`Flow`, piped the same way.
- [ARCH](ARCH.md) — why the link is narrow, one-way, and posted.
