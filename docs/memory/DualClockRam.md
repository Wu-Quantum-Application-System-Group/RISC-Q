# DualClockRam — Mem-inferred true-dual-port, dual-clock, byte-write BRAM

**Source:** `src/riscq/memory/VivadoRam.scala` · **Package:** `riscq.memory` · **Type:** Component

## Purpose

A true dual-port block RAM whose two byte-write read/write ports sit in **two different clock
domains** — a clock-crossing BRAM. It is the default on-chip RAM for handing a word array between a
slow control/host clock and a fast converter clock without an explicit CDC FIFO: one side writes, the
other reads, each at its own rate, over one shared storage array.

## Role in the system

It is the data-handoff buffer between clock regions. It is wrapped as a bank of fabric slaves by
[`DualClockRamFiber`](../soc/DualClockRamFiber.md) and backs the fabric-wired core in
`RamOnFabricSim`; the current SoC uses its drop-in sibling [`Bram`](Bram.md) (via `BramFiber` /
`BramWriteFiber`) for the pulse-envelope banks and readout buffers. Picture it as:

```
  slowCd side  ──slowPort──▶┌───────────┐◀──fastPort── fastCd side
  (host/control)            │  one Mem  │            (converter/dsp)
                            └───────────┘
```

## Why inferred, not blackboxed

`DualClockRam` builds the BRAM from a plain SpinalHDL `Mem` and lets **Vivado infer** the
clock-crossing true-dual-port primitive, steered only by the `ram_style` attribute (default
`"block"`). The alternative — wrapping a vendor HDL template — is what its siblings [`Bram`](Bram.md)
and [`Uram`](Uram.md) do. Inferring is preferred when you do not need to pin a specific template
behaviour: there is no `.v` file to keep in sync, and the `Mem`-based form simulates and elaborates
identically. Choose `Bram` instead when you want the explicit Xilinx write-first / output-register
template; choose `DualClockRam` when you just want a clock-crossing BRAM.

## How it works

- One `Mem.fill(depth)(Bits(width bit))`, tagged `ram_style = style`, shared by both ports.
- Each port is a `mem.readWriteSyncPort(width/8, clockCrossing = true)` built inside its own
  `ClockingArea` — `slowPort` in `slowCd`, `fastPort` in `fastCd` — and re-exported as a `slave`
  byte-write read/write port. `clockCrossing = true` is what tells SpinalHDL the two ports legitimately
  cross domains.
- A port writes byte `i` when `enable && write && mask(i)`; a read is `enable && !write`.

### Latency

The `Mem`'s synchronous read is **1 cycle** (address at `t` → `rdata` at `t+1`). Setting
`withOutRegFast` / `withOutRegSlow` adds one `RegNext` on that side's `rdata`, making it **2 cycles** —
spend it on the fast side to ease clock-to-out timing. Each side's latency is independent, so the two
ports can have different `L`.

## Interface & configuration

| Param | Meaning / cost |
|---|---|
| `width` | data width in bits; **must be a multiple of 8** (one mask bit per byte). |
| `depth` | number of words. |
| `fastCd` / `slowCd` | the two clock domains the ports live in. |
| `withOutRegFast` / `withOutRegSlow` | add one output register on that side (latency 1→2) for timing. |
| `style` | `ram_style` value steering Vivado inference; default `"block"`. |

Ports: `fastPort` (in `fastCd`) and `slowPort` (in `slowCd`), each a `slave` byte-write read/write
port. The `Mem` is marked `Verilator.public` for sim inspection. See the source for the exact port
bundle.

## Contracts & gotchas

- **Same-address concurrent access from both ports is a write/read race** — avoid it in software, as
  for any true dual-port RAM. The sims deliberately keep the two ports in non-overlapping phases.
- **The two ports drive one array from two clock blocks**, which Verilator reports as `MULTIDRIVEN`;
  it is intentional, so the sim adds `-Wno-MULTIDRIVEN` (see the [package README](README.md)).

## Usage

```scala
val ram = DualClockRam(width = 32, depth = 256, fastCd = dspCd, slowCd = hostCd)
ram.fastPort <> producer
ram.slowPort <> consumer
```

Generate the Verilog (32-bit × 256, external fast/slow clocks):

```bash
mill runMain riscq.memory.DualClockRamGen
```

## Verification

`riscq.memory.sim.DualClockRamSim` drives it against a software memory model with the two clocks at
**co-prime** periods (so the crossing is genuinely exercised), checking cross-domain write→read both
directions, byte masks, both `withOutReg` settings, and power-of-two and odd depths — all bit-exact,
accounting for each side's `1 + withOutReg` latency.

```bash
mill runMain riscq.memory.sim.DualClockRamSim
```

## Related

- [Bram](Bram.md) — the blackbox-template equivalent (drop-in `fastPort`/`slowPort`).
- [Uram](Uram.md) — single-clock UltraRAM for deep/dense storage.
- [package README](README.md) · [`DualClockRamFiber`](../soc/DualClockRamFiber.md)
