# MemMapFiber — memory-mapped register block on the fabric

**Source:** `src/riscq/soc/fabric/MemMapFiber.scala` · **Package:** `riscq.soc.fabric` · **Type:** fabric fibers (`Area`)

## Purpose

Attaches a block of CPU/host-mapped control registers to the `tilelink.fabric` as a slave, using a
Tilelink `SlaveFactory` to do the address decode and read/write plumbing. This is how non-memory
peripherals — the qubit core's time/control block and the host control block — are reached over the
bus. Ported from the RISC-Q reference (`riscq.soc.Misc`).

## The two flavours

- **`MemMapFiber`** — register block whose mappings are contributed *incrementally* via `addMapping`.
  The control block uses this so each piece ([ControlMemMaps](ControlMemMaps.md)' `TimeMemMap`,
  `HostMemMap`, and the [`ReadoutResultSink`](EventLink.md) read map) registers its own
  offsets onto one shared slave.
- **`MemMapDriverFiber`** — register block whose single `driveProc` builds the whole map at once. Used
  for the host AXI control registers (`riscqReset`, `fromHost`, the 64-bit `timeOffset`).

## Role in the system

```
dMemPortDec (CPU dBus region) ──► MemMapFiber.up (slave) ──► SlaveFactory ──► TimeMemMap / HostMemMap / ResultSink regs
host AXI fabric              ──► MemMapDriverFiber.up      ──► SlaveFactory ──► riscqReset / fromHost / timeOffset
```

In [`RiscvSoc`](RiscvSoc.md) the control block is one `MemMapFiber` connected `at SizeMapping(0,
1<<16) of dMemPortDec`. The host control block is a `MemMapDriverFiber` off the host AXI→Tilelink
bridge.

## Design rationale

- **One slave, many contributors.** Splitting `addMapping` from elaboration lets independent fibers
  each own a slice of the register map without anyone owning the whole layout — `TimeMemMap`,
  `HostMemMap` and the readout sink are wired in different parts of `RiscvSoc` yet land on the same
  `SlaveFactory`. `MemMapDriverFiber` is the degenerate case where one closure builds everything, which
  fits the host registers (they are defined in one place).
- **SlaveFactory does the decode.** Each fiber only negotiates the node (load supported m2s transfers
  from the proposed set intersected with `get`/`putFull`/`putPartial` at the data width; `s2m.none()`)
  and constructs `new SlaveFactory(up.bus, false)`; the mappings register their reads/writes against
  it. No hand-rolled address decode.
- **Offsets are relative to the region bus.** A fiber connects `at SizeMapping(localOffset, …) of
  regionBus`, never at an absolute host address — the region decoder has already rebased the window
  ([SOC_TIPS](SOC_TIPS.md) §1.2).
- **Halting reads are a feature.** A read of a register gated by a `valid` (e.g. the readout `res`)
  simply stalls the bus until the slave un-halts — exactly the CPU spin-wait the software relies on.

## Interface & configuration

- `addressWidth` (default 10), `dataWidth` (default 32) — the slave's window/word size.
- `MemMapFiber.addMapping(mapping: SlaveFactory => Unit)` — register a fragment before elaboration.
- `MemMapDriverFiber(driveProc: SlaveFactory => Unit, …)` — the whole map in one closure.

Each exposes one `Node.up()` slave. The register field layout itself lives in the mapping closures
(see [ControlMemMaps](ControlMemMaps.md) for the time/host/result maps); fields are conventionally at
bus bits `[16,32)`.

## Verification

The control-block register map (`time` / `timeCmp` / `waitTimeCmp` / `fromHost`) is checked by its
dedicated control-map sim, and end-to-end by [PulseTableSocSim](PulseTableSoc.md) (AXI host writes the
control block, releases reset) and [PulseTableSocCpuSim](PulseTableSoc.md) (the CPU reads `time` and
writes the control registers).

```bash
mill runMain riscq.soc.sim.PulseTableSocSim
```

## Related

[ControlMemMaps](ControlMemMaps.md) · [EventLink](EventLink.md) ·
[RiscvSoc](RiscvSoc.md) · [SOC_TIPS](SOC_TIPS.md)
