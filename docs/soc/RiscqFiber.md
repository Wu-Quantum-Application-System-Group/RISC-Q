# RiscqFiber — core → Tilelink-fabric adapter

**Source:** `src/riscq/soc/fabric/RiscqFiber.scala` · **Package:** `riscq.soc.fabric` · **Type:** fabric fiber (`Area`) + companion `Component` `DataMemBusToTilelink`

## Purpose

The thin shim that plugs one RISC-V [`Riscq`](../riscv/RISCV.md) core into the SoC's `tilelink.fabric`
interconnect. It instantiates the core and re-exposes its two memory masters — instruction fetch and
data — as `tilelink.fabric.Node.down()` master nodes the rest of the SoC can decode to slaves
(RAMs, the control block, the put link).

## Role in the system

```
FetchPlugin.iBus (real Tilelink master) ───────────────────────────► iBus : Node.down()
LsuPlugin.dBus (simplified DataMemBus) ─► PostedStoreShim ─► DataMemBusToTilelink ─► dBus : Node.down()
```

`RiscqFiber` is the bottom of the per-core stack: [`RiscvSoc`](RiscvSoc.md) decodes `iBus`/`dBus` to
the core's I/D RAM, the control block and the [`PutBridge`](PutBridge.md); everything above is
ordinary fabric wiring.

## Design rationale

- **Two different master types, two different adapters.** [FetchPlugin](../riscv/FetchPlugin.md)
  already exposes a *real* Tilelink master (`iBus`), so the fetch side is a plain node adapter — force
  the (already-known) m2s parameters onto the down node and connect the bus. [LsuPlugin](../riscv/LsuPlugin.md)
  instead speaks the core's simplified `DataMemBus` (a `cmd` Stream + `rsp` Flow), so the data side
  first passes through `DataMemBusToTilelink`, which turns each `cmd` into one `a` beat (read → `Get`,
  write → `PutPartial`) and answers the single-outstanding `rsp` from each `d`. This keeps the Tilelink
  boilerplate out of the verified LSU — it is the riscq analog of VexiiRiscv's `CachelessBusToTilelink`,
  minus AMO. Because the bus is strictly single-outstanding, `d.ready` is simply held high.
- **Posted stores spliced onto the data bus.** A [`PostedStoreShim`](PostedStoreShim.md) sits on the
  `DataMemBus` *ahead* of the bridge: stores ack locally in one cycle (non-blocking writes), reads pass
  through. The shim rides the simplified bus, so it never has to speak Tilelink, and the verified LSU is
  untouched.
- **`rework` because the node's `bus` loads late.** A fabric `Node`'s `bus` Handle is only available
  inside that node's own elaboration (Fiber) thread, so the actual connection (`node.bus << master`)
  and the data-side bridge must be done inside `node.bus.component.rework { … }`. The masters are
  already IOs of the child `Riscq` component, so `<<` is a normal parent-reads-child-output connect —
  do **not** `setAsDirectionLess()` them (see [SOC_TIPS](SOC_TIPS.md) §1.1).
- **`coreCd` captured in the Area body.** The data-bus rework runs later, inside a Fiber thread whose
  implicit `ClockDomain` is *not* the core's. `PostedStoreShim` is stateful, so it is explicitly pinned
  to the captured core clock domain — otherwise the host reset would not clear its buffer and it would
  power up with a garbage FIFO that drains a stray store.

## Latency / timing

The adapter itself adds no logic latency beyond the `StreamPipe.FULL` register stages set on each
node's down connection (`a` and `d`), which exist to give the placer slack across the fabric. Store
retirement and read latency are governed by [`PostedStoreShim`](PostedStoreShim.md) and the slave.

## Interface & configuration

- `iBus`, `dBus` — the two `Node.down()` master nodes toward the fabric.
- `plugins` — the core's plugin list (e.g. `RiscqParam().plugins()`).
- `storeBufDepth` (default 4) — depth of the posted-store buffer in the spliced shim.

`dBus.bus` is published `simPublic` so testbenches can monitor the data bus.

## Verification

[RamOnFabricSim](../../src/riscq/soc/sim/RamOnFabricSim.scala) wires `iBus`/`dBus` over
[TileLinkMem fibers](TileLinkMemFiber.md) to a shared RAM preloaded from an rv32 ELF and re-runs the
`rv32ui-p` suite under RVLS/Spike lock-step — proving the bridge + memory fibers + address decode.
The posted-store splice is unit-checked by [PostedStoreShimSim](PostedStoreShim.md).

```bash
mill runMain riscq.soc.sim.RamOnFabricSim
```

## Related

[FetchPlugin](../riscv/FetchPlugin.md) · [LsuPlugin](../riscv/LsuPlugin.md) ·
[PostedStoreShim](PostedStoreShim.md) · [TileLinkMemFiber](TileLinkMemFiber.md) ·
[RiscvSoc](RiscvSoc.md) · [SOC_TIPS](SOC_TIPS.md)
