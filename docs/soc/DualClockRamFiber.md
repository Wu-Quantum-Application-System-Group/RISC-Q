# DualClockRamFiber — clock-crossing RAM bank as a fabric slave

**Source:** `src/riscq/soc/fabric/DualClockRamFiber.scala` · **Package:** `riscq.soc.fabric` · **Type:** fabric fiber (`Area`)

## Purpose

Wraps a bank of `num` [`DualClockRam`](../memory/DualClockRam.md)s as Tilelink fabric memories, with
each RAM's **host-side** port exposed as its own slave and its **device-side** port left for the caller
to wire into the datapath — a bridge between the slow host/CPU clock and the fast dsp/batch clock for
memories both sides touch. Ported from the RISC-Q reference (`riscq.soc.Misc`). No current top
instantiates it: the SoC uses the HDL-template siblings instead ([`BramFiber`](BramFiber.md) for the
`robs` trace buffer, [`BramWriteFiber`](BramWriteFiber.md) for the envelope banks), and the CPU RAM is a
`Uram`/`Bram` inside [`RiscvSoc`](RiscvSoc.md).

## Role in the system — the clock crossing

```
hostCd  ── up : Node ──► [ ram(i).slowPort  ║  DualClockRam  ║  ram(i).fastPort ] ──► datapath (dspCd)
(host/CPU side, Tilelink)                    (CDC inside the RAM)                    (caller wires this)
```

The clock crossing lives *inside* each `DualClockRam` (true dual-port, dual-clock BRAM): the fiber and
its Tilelink slaves run in `hostCd`; the `fastPort` the caller exports runs in `dspCd`. The host loads
the RAM over the fabric at the slow clock; the real-time datapath reads/writes it at the fast clock.

## Design rationale

- **One node, `num` windows.** All `num` RAMs hang off a single `up` node at consecutive `step`-sized
  windows (`step = 1 << log2Up(width*depth/8)`), each via a [`TileLinkMemReadWriteFiber`](TileLinkMemFiber.md)
  on the RAM's `slowPort` connected `at SizeMapping(i*step, step) of up`. The host sees a contiguous
  address range that fans to the individual banks.
- **`withOutReg` must match the slave's latency assumption.** It is threaded into both the RAM
  (`withOutRegFast`) and the read/write fiber, so the fiber's read-reorder buffer depth lines up with
  the RAM read latency. The slow (host) side always carries its output register (`withOutRegSlow =
  true`).
- **Device side is deliberately open.** The fiber exposes only the host port as a fabric slave; the
  `fastPort` is returned raw so the qubit core can wire it to whatever datapath consumes it (e.g. the
  envelope reader). Keeping that out of the fiber keeps the fabric concern (host access) separate from
  the datapath concern.

## Latency / timing

Host-side read latency is `1 + withOutReg` cycles at `hostCd`; the device side latency is the RAM's
`fastPort` latency at `dspCd`. The two ports are independent (true dual-port).

## Interface & configuration

- `num`, `width`, `depth` — bank count and per-RAM geometry.
- `hostCd` — clock domain of the host (`slowPort`) side **and** the Tilelink fabric here.
- `dspCd` — clock domain of the device (`fastPort`) side.
- `withOutReg` (default true) — output-register / read-latency select, applied to both the RAM and the
  fiber.

Exposes `up` (the slave node) and `rams` (for the caller to reach each `fastPort`).

## Verification

The backing RAM is signed off by [DualClockRamSim](../memory/DualClockRam.md) (clock-crossing golden).
The fiber bank itself has no SoC-level coverage, since no current top instantiates it.

```bash
mill runMain riscq.memory.sim.DualClockRamSim
```

## Related

[BramFiber](BramFiber.md) (drop-in HDL-template sibling) · [DualClockRam](../memory/DualClockRam.md) ·
[TileLinkMemFiber](TileLinkMemFiber.md) · [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md)
