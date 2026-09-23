# RfLinkBridge — core-side posted-write funnel for the RF link

**Source:** `src/riscq/soc/link/RfLinkBridge.scala` · **Package:** `riscq.soc.link` · **Type:** `Area` (Fiber-elaborated TileLink slave)

The bridge that lets a RISC-V core talk to converter-edge DSP that sits **physically far away**. It is a
tiny TileLink slave mapped over the core's whole RF window; every CPU store into that window is **acked
locally in one cycle** and forwarded as one beat of a narrow, ordered, posted `Flow(RfCmd)` that travels
down the long link with no return path. It is the head of the down-link; [RfLink](RfLink.md) pipes and
demuxes the stream, [PulseParamBuffer](PulseParamBuffer.md) consumes it at the far end.

## Role in the system

```
  CPU dBus ──Put──▶ RfLinkBridge ──AccessAck (1 cyc, local)──▶ CPU       (core region)
                         │
                         └── Flow(RfCmd){address,data} ─── linkPipe RegNext × N ──▶ demux ──▶ buffers
                                            posted, one-way, no back-pressure       (DSP region)
```

It lives next to the core (inside `RiscvSoc`; see [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md)),
which re-exports the bridge's `cmd` stream so the parent can apply the `linkPipe` stages and route it.
The window is the core's **put window** (`rfAddrWidth = 28`): `node · 0x10000 + offset`, where nodes
`0..15` are the core's own channels (gate drive / readout drive / demod on the qubit builds) and nodes
`≥ 16` are system units the parent hands to the board hub ([RfLink.nonLocal](RfLink.md)). The bridge does
not interpret the node — it acks and forwards every word store the same way.

## Why a local ack is correct — the lead-time contract

The core's `LsuPlugin` issues a TileLink write and **waits for a d-channel `AccessAck`**. You cannot
simply drop the D channel and stretch the bus across the die: the CPU would hang, and adding pipeline
stages to a *round-trip* bus only lengthens the stall. The fix is to **terminate D locally** — the bridge
acks in one cycle so the store retires next cycle and the core's bus arc stays short and inside the core
region — while the actual write travels **posted** (no ack) down the long haul.

This is sound only because the CPU is a **lead-time scheduler, not a real-time driver**: it writes a pulse
table entry and a `startTime = time + lead`, and the DSP's `TimedQueue` emits the pulse at the exact cycle
`time == startTime`. The *arrival time of the posted write is irrelevant* as long as it lands before `time`
reaches `startTime` (i.e. `lead > link latency`). So the long posted path costs only a constant,
predictable latency the lead time absorbs — nothing on it is timing-critical. See [ARCH](ARCH.md) §2 for
the full rationale, and [PulseGenerator](../dsp/PulseGenerator.md) / [TimedQueue](../dsp/TimedQueue.md)
for the lead-time pop. Alternatives rejected: making stores posted *inside* `LsuPlugin` (touches the
verified core) and a wide async TileLink CDC FIFO (carries the full a+d channel set, harder to span than a
one-way register chain) — [ARCH](ARCH.md) §4.

## Behaviour & contracts

- **Write-only.** `up.m2s.supported` advertises only single-word (size-4) `PutFull`/`PutPartial` and
  `s2m.none()`, so the fabric **never routes a Get/read here**. RF reads return on the separate up-`Flow`
  ([EventLink](EventLink.md)); control-block reads are core-local ([ControlMemMaps](ControlMemMaps.md)).
- **One ordered posted beat per accepted Put.** `cmd.valid := bus.a.fire`, with `address` rebased to the
  RF window and the 32-bit `data` passed through. A single path is a shift register, so **order is
  preserved** — all the per-generator write sequencing needs (write `startTime`, then the table entry,
  then the `outId` fire, all in order to the same buffer, so the fire enqueues the just-written `startTime`).
- **No back-pressure.** A `Flow` has none; the CPU issues RF writes far below link bandwidth, so a beat is
  never dropped. Cross-generator order is *not* guaranteed and does not need to be — the fire selects by
  `startTime` value, not arrival.

## Latency

Ack is 1 cycle (`bus.d << rsp.stage()`). The posted path adds `linkPipe` plain `RegNext` stages each way
(default 4), all timing-insensitive — see [RfLink](RfLink.md).

## Configuration

`RfLinkBridge(rfAddrWidth)` — the byte-address width of the put window (and of the `RfCmd.address`
field), `SocSpecMap.putAddrWidth` in every build. That is the only knob; the data width is fixed at 32.

## Verification

`riscq.soc.sim.RfLinkBridgeSim` drives the bridge's TileLink slave with a `MasterAgent` issuing an
interleaved stream of word stores across two sub-windows plus one system put (node `0x21`) and asserts:
every accepted `Put` emerges as exactly one `Flow(RfCmd)` beat **in order** with the right
`{address, data}`; the system put leaves on `RfLink.nonLocal` untouched and on no channel; the demux routes each beat to
the correct rebased sub-window; and **every store completes** (`putFullData` blocks on the D ack, so a
missing ack would hang — proving the local ack keeps up at one store per request).

```bash
mill runMain riscq.soc.sim.RfLinkBridgeSim
```

## Related

- [RfLink](RfLink.md) — the `RfCmd` bundle, the `linkPipe` pipe, and the address demux.
- [PulseParamBuffer](PulseParamBuffer.md) — the far-end consumer of the demuxed stream.
- [EventLink](EventLink.md) — the up-`Flow` that carries the channels' reports back.
- [ARCH](ARCH.md) — the posted-link architecture and the lead-time enabler.
- [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md) — the qubit core that instantiates it.
