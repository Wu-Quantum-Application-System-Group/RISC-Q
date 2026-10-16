# HalfUram — 32-bit-facing UltraRAM that packs two words per row

**Source:** `src/riscq/memory/HalfUram.scala` · **Package:** `riscq.memory` · **Type:** Component

## Purpose

An UltraRAM primitive is **72 bits wide × 4096 deep**; a plain `Uram(Bits(32 bits), …)` stores one
32-bit word per row and wastes more than half the width. `HalfUram` reclaims it by backing the array
with a **64-bit** [`Uram`](Uram.md) and presenting a **32-bit port with twice the depth** — two
32-bit words packed into each 64-bit row. Same array, **half the URAM primitives**.

Use it wherever you'd reach for a 32-bit `Uram` and the array is large enough that primitive count
matters (e.g. a deep 32-bit word table). It is a drop-in on the surface: the same two
`MemReadWritePort`s and the same read latency as `Uram`.

## How it maps

Each 32-bit outer port drives the corresponding 64-bit inner `Uram` port. The address splits:

- `address >> 1` → the URAM **row**,
- `address(0)` → the **half** of the row: `0` = low 32 bits `[31:0]`, `1` = high 32 bits `[63:32]`.

**Writes need no read-modify-write.** The 4-bit outer byte mask is placed into the addressed half of
the inner 8-bit mask (zeros in the other half) and the 32-bit `wdata` is replicated to both halves,
so the URAM's per-byte write enables write only the addressed half — the sibling word in the same row
is untouched.

**Reads** mux the correct 32-bit half out of the returned 64-bit row. The half-select is delayed by
the row's read latency (`Delay(sel, pipeNum + 2)`) so it lines up with `rdata`; the mux itself is
combinational.

## Interface & configuration

| Param | Meaning / cost |
|---|---|
| `addressWidth` | address width of the **32-bit words** (depth = `1 << addressWidth`); the backing URAM has an `addressWidth - 1`-bit address (half the depth) and is 64-bit wide. Must be `>= 1`. |
| `pipeNum` | backing UltraRAM output pipeline depth `NBPIPE` (default 3), passed straight to [`Uram`](Uram.md). |

Ports: `io.port0` / `io.port1`, each `MemReadWritePort(Bits(32 bits), addressWidth, maskWidth = 4)`,
both in the current (single) clock domain.

## Read latency

**`pipeNum + 2` cycles**, address→`rdata` — identical to [`Uram`](Uram.md), because the half-mux is
combinational after the URAM's `dout`. Tell bus fibers this exact latency (see
[`TileLinkMemFiber`](../soc/TileLinkMemFiber.md)), same as for `Uram`.

## Contracts & gotchas

- **Single-clock only** — inherited from [`Uram`](Uram.md); do clock crossings in a
  [`Bram`](Bram.md) / [`DualClockRam`](DualClockRam.md).
- **Concurrent same-address access from both ports is a true-dual-port write/read race** — the
  caller must avoid it. Two adjacent addresses share a physical row, but the byte-masked half-writes
  keep them independent; a genuine same-address (same half) collision on both ports is still a race.

## Usage

```scala
val ram = HalfUram(addressWidth = 13, pipeNum = 3) // 8192 x 32-bit, backed by a 4096 x 64-bit URAM
ram.io.port0 <> a
ram.io.port1 <> b
```

Generate the Verilog (32-bit × 256, backed by a 64-bit × 128 URAM):

```bash
mill runMain riscq.memory.HalfUramGen
```

## Verification

`riscq.memory.sim.HalfUramSim` drives the wrapper **through Verilator against the committed
`UramBlackBox.v`**, checked against a software 32-bit-word memory model: the exact `pipeNum + 2` read
latency (single spike), whole-array fill read back through both ports (every even/odd address distinct
— exercises both halves), 4-bit byte masks (only the addressed half's bytes change, the sibling word
is untouched), and a port0-writes-low / port1-reads-high concurrency stress — all bit-exact.

```bash
mill runMain riscq.memory.sim.HalfUramSim
```

## Related

- [Uram](Uram.md) — the 1-word-per-row UltraRAM this wraps; pick it when you don't need the packing.
- [Bram](Bram.md) / [DualClockRam](DualClockRam.md) — two-clock BRAM; pick for clock crossings or small arrays.
- [package README](README.md)
