# TileLinkMemFiber — on-chip RAM as a Tilelink fabric slave

**Source:** `src/riscq/soc/fabric/TileLinkMemFiber.scala` · **Package:** `riscq.soc.fabric` · **Type:** fabric fibers (`Area`) + their slave-logic `Component`s

## Purpose

Exposes an on-chip RAM port (`MemReadWritePort` / `MemWriteCmd`) on the `tilelink.fabric`
interconnect, so a host AXI bus or the CPU can read/write the SoC's instruction, pulse-envelope and
readout memories. Ported from the RISC-Q reference (`riscq.misc.TileLinkAccess`).

## The three flavours — and when each is used

| Fiber | Backing port | Used for |
|---|---|---|
| `TileLinkMemReadWriteFiber` | full read/write `MemReadWritePort` | host-reachable RAMs, readout buffers (`robs`) |
| `TileLinkMemWriteFiber` | write-only `MemWriteCmd` Flow | host-loaded pulse-envelope memory |
| `TileLinkCpuMemFiber` | full read/write `MemReadWritePort` | **only** the CPU instruction/data RAM in [RiscvSoc](RiscvSoc.md) |

[`DualClockRamFiber`](DualClockRamFiber.md) and [`BramFiber`](BramFiber.md) build banks of
read/write fibers over their RAMs; the envelope path uses the write-only fiber for host loading.

## Design rationale

- **Read-write needs a read-reorder buffer; CPU-mem does not.** `TileLinkMemReadWriteLogic` carries a
  4-entry rotating buffer (indexed by a wrapping id) that holds in-flight read responses, so a `Get`'s
  `d` beat returns the word read for *its* request even with several outstanding and the d-channel
  back-pressured. The buffer covers the RAM read latency `1 + withOutReg`. It is stored in a
  *distributed* (`ram_style = "distributed"`) `Mem` rather than a `Vec` of indexed registers: the
  single LUTRAM write port has a constant-true enable, so the per-slot flip-flops (and their one-hot
  write clock-enables) drop out — bit-identical, fewer FFs.
- **`TileLinkCpuMemFiber` is the stripped-down variant — valid *only* on the CPU bus.** It removes the
  reorder buffer and the elastic pipeline stages entirely: a plain fixed-latency in-order slave
  (`a.ready := True` ⇒ fetch sustains IPC = 1; the response is `Delay`ed by `L = 1 + withOutReg` to
  line up with `io.port.rdata`). This is sound because **both CPU masters hold `d.ready` permanently
  high** — [FetchPlugin](../riscv/FetchPlugin.md)'s `iBus.d.ready := True` and the
  [LsuPlugin](../riscv/LsuPlugin.md) data path through `DataMemBusToTilelink` (single-outstanding,
  `d.ready := True`) — and the host master, which *does* overlap fetch (a mid-run global rewrite, a
  result read-back), can never have more responses outstanding than the path back to it can hold.
  That is the real invariant, and it is arithmetic, not a usage rule:
  **`Axi4ToTilelinkFiber(slotsCount)` ≤ the d-channel buffering between this slave and that bridge.**
  In [PulseTableSoc](PulseTableSoc.md) that is 4 ≤ 14 — three `StreamPipe.FULL` connections (2 beats
  each) on `hostBus → riscqMemBus → iMemPortArb` plus the dsp→host `FifoCc`'s `dDepth` of 8 — and a
  `require` there keeps it honest. The d-channel is therefore never back-pressured and there is
  nothing for an elastic buffer to absorb. A simulation `assert` guards that premise. Anything that
  raises `slotsCount`, or issues bursts into the core RAM, must re-check the arithmetic
  (see [specs/software/23](../../specs/software/23-done-register.md) §1.3).
- **Width matching: only the write-only fiber bridges sub-word.** The read/write and CPU-mem fibers
  *force* the bus word equal to the RAM word (`forceDataWidth`), so they carry no byte-lane logic.
  `TileLinkMemWriteLogic` is the one that bridges a (possibly wider) RAM word to a narrower bus by
  sub-word byte addressing — the low address bits pick which of `ratio = outBytes / inBytes` lanes a
  bus beat writes, asserting only that lane's mask. This is what lets a 32-bit host bus fill a 512-bit
  envelope line over 16 partial writes.
- **`beatMax == 1` is about `blockSize`, not bursts.** These word-wide fibers assert single-beat
  transfers; the failure mode is a too-*small* `blockSize` on a wide fiber (the WidthAdapter negotiates
  `sizeBytes = 0`), not a too-large one. Set the host bridge's `blockSize` ≥ the widest on-chip word in
  bytes (see [SOC_TIPS](SOC_TIPS.md) §1.3–1.4).

## Latency / timing

Read latency is the RAM's `1 + withOutReg` cycles; `withOutReg` must match the backing RAM's output
register so the response control lines up with `rdata`. Writes ack in one cycle (`ACCESS_ACK`).

## Interface & configuration

Each fiber exposes one `Node.up()` slave node. In its build thread it loads the supported m2s
transfers (intersecting the proposed set with `get`/`putFull`/`putPartial` over the RAM word size),
sets `s2m.none()`, and instantiates its slave-logic component. The meaningful parameters are the
backing RAM `port` and `withOutReg`; the address width is derived from the port. The parent connects
`fiber.up at SizeMapping(offset, size) of regionBus` — offsets are **relative** to the region bus
([SOC_TIPS](SOC_TIPS.md) §1.2).

## Verification

[RamOnFabricSim](../../src/riscq/soc/sim/RamOnFabricSim.scala) drives the CPU-mem fiber under RVLS
lock-step (the `rv32ui-p` suite). The read/write and write-only fibers are exercised end-to-end in the
assembled SoC by [PulseTableSocSim](PulseTableSoc.md) (AXI host round-trip into the instruction /
pulse-envelope / readout memories).

```bash
mill runMain riscq.soc.sim.RamOnFabricSim
mill runMain riscq.soc.sim.PulseTableSocSim
```

## Related

[DualClockRamFiber](DualClockRamFiber.md) · [BramFiber](BramFiber.md) · [RiscqFiber](RiscqFiber.md) ·
[RiscvSoc](RiscvSoc.md) · [SOC_TIPS](SOC_TIPS.md)
