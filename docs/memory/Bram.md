# Bram / BramBlackBox — true-dual-port, dual-clock BRAM over the Xilinx template

**Source:** `src/riscq/memory/Bram.scala`, `src/riscq/memory/BramBlackBox.v` · **Package:**
`riscq.memory` · **Type:** Component (`Bram`) + BlackBox (`BramBlackBox`)

## Purpose

A true dual-port, two-clock, byte-write block RAM — same role as [`DualClockRam`](DualClockRam.md),
but built by **wrapping the Xilinx BRAM HDL template** (`BramBlackBox.v`) rather than letting Vivado
infer it from a `Mem`. Use it when you want the template's exact write-first / output-register
behaviour under your control instead of relying on inference.

## Role in the system

The blackbox sibling of `DualClockRam` for clock-crossing on-chip storage between a slow host/control
clock and a fast converter clock. `Bram` mirrors `DualClockRam`'s `fastPort` / `slowPort` naming on
purpose so the two are **drop-in interchangeable**; it is wired into the SoC through
[`BramFiber`](../soc/BramFiber.md) and the write-only [`BramWriteFiber`](../soc/BramWriteFiber.md).

## Two layers

- **`BramBlackBox`** — the raw blackbox over `BramBlackBox.v`: a **write-first**, byte-write BRAM with
  the two ports on **independent clocks** (`clka` / `clkb`), sized by `NB_COL` (byte columns) ×
  `COL_WIDTH` (8) × `RAM_DEPTH`. The Verilog source is read at elaboration and inlined
  (`setInlineVerilog`), so the committed `.v` is the single source of truth and gets exercised by the
  sim.
- **`Bram[T <: Data]`** — the typed wrapper most code uses: two `MemReadWritePort[T]` slave ports
  (one per BRAM port, each in its own clock domain), translating the generic read/write handshake to
  the template's byte-write interface. A port writes byte `i` when `enable && write && mask(i)`
  (`mask & (write #* maskWidth)` → `wea`/`web`); a read is `enable && !write`.

## Read latency — the output-register choice

`RAM_PERFORMANCE` (exposed as the `outReg` / `highPerformance` flag) picks the output stage:

| Mode | `outReg` | Latency (address → `dout`) | Trade |
|---|---|---|---|
| `LOW_LATENCY` | `false` | **1 cycle** | reads the memory register directly |
| `HIGH_PERFORMANCE` | `true` (default) | **2 cycles** | adds one output register; better clock-to-out |

The output register is gated by `regce*` and cleared by `rst*`, but **only the output is reset — the
stored contents never are**. `Bram` holds the register transparent (`regce*` tied high, `rst*` low)
so a read always propagates; reset the value in the **consumer's** pipeline, not the RAM.

## Interface & configuration

| Param | Meaning / cost |
|---|---|
| `dataType` | payload type `T`; its bit width **must be a multiple of 8** (per-byte mask). |
| `depth` | number of words; address width is `log2Up(depth)`. |
| `fastCd` / `slowCd` | the two clock domains the ports live in — **pass the same domain twice for a single-clock BRAM**. |
| `outReg` | `true` → `HIGH_PERFORMANCE` (latency 2); `false` → `LOW_LATENCY` (latency 1). |

Ports: `io.port0` / `io.port1`, with aliases `fastPort` = `port0` (fast/device clock `fastCd`) and
`slowPort` = `port1` (slow/host clock) matching `DualClockRam`. See the source for the full
`MemReadWritePort` bundle.

## Contracts & gotchas

- **Concurrent same-address access from both ports is a true-dual-port write/read race** — the user
  must avoid it, as for any TDP RAM.
- **The array is written from two clock blocks** (one storage, two ports), which Verilator reports as
  `MULTIDRIVEN`; intentional, so the sim adds `-Wno-MULTIDRIVEN` (see the [package README](README.md)).
- **`BramBlackBox` reads `BramBlackBox.v` from a relative path at elaboration** — generate/sim from
  the repo root so the file resolves.

## Usage

```scala
val ram = Bram(Bits(32 bits), depth = 256, fastCd = dspCd, slowCd = hostCd) // outReg = true
ram.fastPort <> producer
ram.slowPort <> consumer
```

Generate the Verilog (32-bit × 256, external fast/slow clocks):

```bash
mill runMain riscq.memory.BramGen
```

## Verification

`riscq.memory.sim.BramSim` drives the wrapper **through Verilator against the committed
`BramBlackBox.v`** (so it also proves the `.v` matches the SpinalHDL wrapper), checked against a
software memory model: the exact read latency (single spike at `L = 1`/`2`), cross-domain write→read
both directions, byte masks, both `highPerformance` settings, and power-of-two and odd depths — all
bit-exact, with the two ports at co-prime periods.

```bash
mill runMain riscq.memory.sim.BramSim
```

## Related

- [DualClockRam](DualClockRam.md) — the inferred (`Mem`-based) equivalent; drop-in by port name.
- [Uram](Uram.md) — single-clock UltraRAM for deep/dense storage.
- [package README](README.md) · [`BramFiber`](../soc/BramFiber.md) · [`TileLinkMemFiber`](../soc/TileLinkMemFiber.md)
