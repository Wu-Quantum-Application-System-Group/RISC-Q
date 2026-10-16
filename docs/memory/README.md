# riscq.memory — on-chip RAM building blocks

**Package:** `riscq.memory` · **Source:** `src/riscq/memory/`

On-chip RAM blocks for the SoC, replicated from the RISC-Q reference and cleaned up. They share one
purpose: hand a word array between two agents — typically a **slow control/host clock** and a **fast
converter clock** — over a **true dual-port** RAM, with **per-byte write masks**. All four are byte
addressable (width a multiple of 8, one mask bit per byte) and expose two independent read/write
ports so the two sides never have to arbitrate for access.

## The four blocks — and when to use each

| Block | Storage | Clocks | How it's built | Pick it when |
|---|---|---|---|---|
| [`DualClockRam`](DualClockRam.md) | block RAM | **two** (fastCd / slowCd) | **inferred** from a SpinalHDL `Mem` (`ram_style`) | you want a clock-crossing BRAM with no vendor template — the default on-chip RAM |
| [`Bram`](Bram.md) | block RAM | **two** (fastCd / slowCd) | **blackbox** over a Xilinx HDL template (`BramBlackBox.v`) | you want explicit control of the BRAM template / output-register stage |
| [`Uram`](Uram.md) | UltraRAM | **one** (shared) | **blackbox** over a Xilinx HDL template (`UramBlackBox.v`) | you need deep/dense storage and BRAM is scarce — but you do **not** need a clock crossing |
| [`HalfUram`](HalfUram.md) | UltraRAM | **one** (shared) | width adapter over a 64-bit [`Uram`](Uram.md) | you want a **32-bit** URAM array at **half the primitives** — packs two 32-bit words per 64-bit row |

`DualClockRam` and `Bram` are interchangeable on the surface: both are true-dual-port, two-clock,
byte-write, and `Bram` deliberately mirrors `DualClockRam`'s `fastPort` / `slowPort` naming so it is a
drop-in. The difference is `DualClockRam` lets Vivado **infer** the BRAM from a `Mem` (simplest, no
`.v` to maintain), whereas `Bram` **wraps an explicit Xilinx template** so you get its exact
write-first / output-register behaviour. `Uram` is the odd one out: the UltraRAM primitive is
**single-clock**, so it cannot itself bridge two clock domains — it trades the clock crossing for
much deeper, denser storage. [`HalfUram`](HalfUram.md) wraps a 64-bit `Uram` in a width adapter to
present a 32-bit port at twice the depth (two words per row), so a 32-bit array costs half as many
URAM primitives; it inherits `Uram`'s single-clock constraint and read latency.

## Common theme: true dual-port + byte masks

- **True dual-port:** two ports over **one** storage array. Each port reads or writes independently;
  there is no internal arbitration. Concurrent **same-address** access from both ports is a
  write/read race and is the caller's responsibility to avoid, exactly as for any TDP RAM.
- **Byte-write masks:** a port writes byte column `i` only when `enable && write && mask(i)`; a read
  is `enable && !write`. The typed wrappers (`Bram`/`Uram`) present this through the generic
  `MemReadWritePort[T]` handshake.
- **Synchronous read with a known, sharp latency** (see each doc): an address presented at cycle `t`
  returns its word at `t + L`. The readout/scheduler alignment math downstream depends on `L` being
  exact, which is why every sim asserts it as a single spike.

## Verification

Each block has a self-checking SpinalSim that drives it against a software byte-addressable memory
model and asserts **bit-exact** read-back. The shared checks:

- **Cross-domain write→read** — fill the whole array on one port, read it all back on the other,
  accounting for that port's read latency; then the reverse direction.
- **Byte masks** — random masked writes update only the enabled byte columns; read-back is compared
  against a per-byte model merge.
- **Exact read latency** — a single read pulse must appear at the output exactly `L` cycles later and
  nowhere else.
- **Cross-domain / concurrent access** — the two-clock sims run the ports at **co-prime periods** so
  edges interleave irregularly; the URAM sim writes one (disjoint) half while reading the other in
  the same cycles.

```bash
mill runMain riscq.memory.sim.DualClockRamSim
mill runMain riscq.memory.sim.BramSim
mill runMain riscq.memory.sim.UramSim
mill runMain riscq.memory.sim.HalfUramSim
```

**Load-bearing sim gotcha — `-Wno-MULTIDRIVEN`.** A true dual-port RAM is **one storage array written
from two clock blocks**. Verilator flags that as `MULTIDRIVEN` and, by default, errors out. It is
intentional here, so the two **dual-clock** sims (`DualClockRamSim`, `BramSim`) add
`SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")` to silence just that one check. `UramSim` and
`HalfUramSim` do **not** need the flag: the UltraRAM template is single-clock, so both port writes live
in one clock block.

## Docs

- [DualClockRam](DualClockRam.md) — `Mem`-inferred true-dual-port, dual-clock, byte-write BRAM.
- [Bram](Bram.md) — blackbox over the Xilinx true-dual-port two-clock BRAM template.
- [Uram](Uram.md) — blackbox over the Xilinx single-clock true-dual-port UltraRAM template.
- [HalfUram](HalfUram.md) — width adapter over a 64-bit `Uram`: a 32-bit port at twice the depth (two words per row).

These blocks are wired into the SoC through fibers — see
[`DualClockRamFiber`](../soc/DualClockRamFiber.md), [`BramFiber`](../soc/BramFiber.md),
[`BramWriteFiber`](../soc/BramWriteFiber.md), and the Tilelink memory adapters in
[`TileLinkMemFiber`](../soc/TileLinkMemFiber.md) (which also front the per-core `Uram` / `HalfUram`
in [`RiscvSoc`](../soc/RiscvSoc.md)).
