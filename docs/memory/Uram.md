# Uram / UramBlackBox — true-dual-port, single-clock UltraRAM over the Xilinx template

**Source:** `src/riscq/memory/Uram.scala`, `src/riscq/memory/UramBlackBox.v` · **Package:**
`riscq.memory` · **Type:** Component (`Uram`) + BlackBox (`xilinx_ultraram_true_dual_port_bytewrite`)

## Purpose

A true dual-port, byte-write **UltraRAM** wrapping the Xilinx UltraRAM HDL template
(`UramBlackBox.v`, `ram_style = "ultra"`). Same typed `MemReadWritePort` interface as
[`Bram`](Bram.md), but backed by the UltraScale+ UltraRAM primitive for **deep, dense** on-chip
storage — at the cost that UltraRAM is **single-clock** (see below).

## When to pick URAM over BRAM

Reach for `Uram` when the table is **large** and BRAM is scarce: each UltraRAM primitive holds far
more bits than a single BRAM (288 Kb vs 36 Kb) and places into the dedicated URAM columns, so big
arrays cost fewer primitives and free BRAM for elsewhere. Reach for [`Bram`](Bram.md) /
[`DualClockRam`](DualClockRam.md) when the array is small, or when you need a **clock crossing** —
which UltraRAM cannot do.

## The single-clock gotcha

The UltraRAM primitive has **one clock for both ports**. `Uram` therefore drives both ports from
`ClockDomain.current` (`uram.io.clk := ClockDomain.current.readClockWire`) — there is no `fastCd` /
`slowCd` split. This is the load-bearing difference from `Bram` and `DualClockRam`, which are
genuinely two-clock: **`Uram` cannot itself bridge two clock domains.** If you need a converter↔host
crossing, do it in a `DualClockRam`/`Bram` and keep the URAM inside one clock region.

## Two layers

- **`xilinx_ultraram_true_dual_port_bytewrite`** — the raw blackbox over `UramBlackBox.v` (named
  after the template's Verilog module): a true dual-port RAM with per-byte write enables and `NBPIPE`
  output pipeline registers, sized by `AWIDTH` / `DWIDTH` / `NUM_COL` (byte columns) / `NBPIPE`. The Verilog is read at elaboration and inlined (`setInlineVerilog`).
- **`Uram[T <: Data]`** — the typed wrapper: two `MemReadWritePort[T]` slave ports (`io.port0` /
  `io.port1`, one per UltraRAM port), translating the generic handshake to the template's byte-write
  interface (`mask & (write #* maskWidth)` → `wea`/`web`, `enable` → `mem_en*`). A port writes byte
  `i` when `enable && write && mask(i)`; a read is `enable && !write`.

## Read latency

Address → `dout` latency is **`NBPIPE + 2` cycles**: one memory-read register, `NBPIPE` output
pipeline stages, and the `dout` output register, each gated by a delayed copy of `mem_en`. The extra
pipe depth is what lets UltraRAM run fast despite its size; downstream alignment math depends on this
latency being exact (bus fibers are told `pipeNum + 2` — see `TileLinkCpuMemFiber`), so the sim
asserts it as a single spike.

## Interface & configuration

| Param | Meaning / cost |
|---|---|
| `dataType` | payload type `T`; its bit width **must be a multiple of 8** (per-byte mask). |
| `addressWidth` | address width in bits (depth = `1 << addressWidth`). |
| `pipeNum` | UltraRAM output pipeline depth `NBPIPE` (default 3); higher = better fmax, longer read latency. |

Ports: `io.port0` / `io.port1`, both in the current clock domain. See the source for the full
`MemReadWritePort` bundle.

## Contracts & gotchas

- **Single-clock only** — see above; do clock crossings elsewhere.
- **Concurrent same-address access from both ports is a true-dual-port write/read race** — the user
  must avoid it.
- **The blackbox reads `UramBlackBox.v` from a relative path at elaboration** — generate/sim from
  the repo root so the file resolves.
- Both port writes share one clock block, so (unlike the two-clock BRAMs) the sim does **not** need
  `-Wno-MULTIDRIVEN`.

## Usage

```scala
val ram = Uram(Bits(32 bits), addressWidth = 7, pipeNum = 3) // 32-bit x 128, latency 5
ram.io.port0 <> a
ram.io.port1 <> b
```

Generate the Verilog (32-bit × 128):

```bash
mill runMain riscq.memory.UramGen
```

## Verification

`riscq.memory.sim.UramSim` drives the wrapper **through Verilator against the committed
`UramBlackBox.v`** (proving the `.v` matches the wrapper), checked against a software memory model on
the `-2` RFSoC default clock: the exact `NBPIPE + 2` read latency (single spike), whole-array fill on
port 0 read back through **both** ports, byte masks, and a **concurrency** stress where port 0 writes
the low half while port 1 reads the disjoint high half in the same cycles — all bit-exact.

```bash
mill runMain riscq.memory.sim.UramSim
```

## Related

- [Bram](Bram.md) — two-clock BRAM template; pick it for clock crossings or small arrays.
- [DualClockRam](DualClockRam.md) — the inferred two-clock BRAM.
- [package README](README.md) · [`TileLinkMemFiber`](../soc/TileLinkMemFiber.md)
