# WriteBackPlugin — the rd-source mux

**Source:** `src/riscq/riscv/execute/WriteBackPlugin.scala` · **Package:** `riscq.riscv.execute` · **Type:** FiberPlugin

## Purpose

Selects what gets written back to `rd` and presents it to the register file. With a single execute
stage this collapses to one small mux over the rd sources each producing plugin computed, plus a
generic hook (`addRdSource`) for optional extensions to contribute their own source.

## Role in the system

Reads the per-source decoded selects and data payloads, drives `Execute.RD_DATA` / `Execute.RD_WRITE`
to the [RegFilePlugin](RegFilePlugin.md) write port (see `execute/Execute.scala`). The base sources:

| source | data payload | select payload | owner |
|---|---|---|---|
| ALU result | `Execute.ALU_RESULT` | `IntAluPlugin.ALU_WRITES_RD` | [IntAluPlugin](IntAluPlugin.md) |
| load data  | `Execute.LOAD_DATA`  | `LsuPlugin.IS_LOAD`         | [LsuPlugin](LsuPlugin.md) |
| jump link  | `Execute.LINK_PC`    | `BranchPlugin.IS_JUMP`      | [BranchPlugin](BranchPlugin.md) |
| CSR read   | `Execute.CSR_RD_DATA`| `CsrPlugin.IS_CSR`         | [CsrPlugin](CsrPlugin.md) |

Traps / `ECALL` are handled by CsrPlugin, not here.

## Design rationale

### Generic `addRdSource(sel, data)` — extensions contribute without the base importing them

An optional extension registers an rd source with
`addRdSource(sel: Payload[Bool], data: Payload[Bits])` during its own setup while it holds this
plugin's `elaborationLock` (mirroring DecoderPlugin's registration handshake). The base mux then folds
each registered `(sel, data)` in **without importing the extension** — e.g. [MulPlugin](MulPlugin.md)
contributes its product via `addRdSource(SEL_MUL, MUL_RD_DATA)`, and `RD_WRITE` is OR'd with every
extra select. A config without the extension is byte-identical to before. This is the inversion that
keeps the base plugin closed to extension: the base does not know what `M` (or any future extension)
is, yet it routes its result.

### One-hot ⇒ priority is irrelevant

The base four sources **and** every registered extra are **mutually exclusive** — at most one is set
for any rd-writing op. So although the code is written as a priority mux (a `foldRight` of 2:1
muxes), the priority never matters: it is really a one-hot select. This is what makes `addRdSource`
safe to call from any plugin in any order — a contributor cannot accidentally outrank or be outranked
by another source, because no two are ever live together.

### Late-arriving ALU result kept shallow

The non-ALU sources are mutually exclusive and arrive early, so they are pre-merged off the ALU path:
the late ALU result stays one 2:1 mux from the output. Extension sources fold into that early
`nonAlu` pre-merge, in front of the base CSR/load/jump.

## Configuration lever (RiscqParam)

- **`lateWriteback`** (default on) — move the final rd 2:1 mux (`aluWritesRd ? aluResult | nonAlu`)
  from `executeAt` to the existing `regWriteAt` stage, off the `SRC → RD_DATA` register path. This
  adds **no** pipeline depth (srcB already forwards from `regWriteAt` and the regfile write is there)
  ⇒ no IPC cost; only the mux's combinational delay moves, shortening the route-dominated ALU result
  path. The late mux is also rebuilt as a strict one-hot masked-OR cone (`OHMasking.firstV2` +
  `OhMux.or`) — a balanced OR tree instead of the chained priority mux, flatter logic on the write
  path, same result. An executeAt copy (`RD_DATA_DBG`) is kept so the RVLS retire point stays at
  executeAt; it has no hardware consumer and is pruned in synthesis. With the flag off, the real
  `RD_DATA` mux stays at executeAt.

## Latency / timing

Combinational; no halts. The rd-write path is one of the core's two route-dominated binders, which is
why the late ALU result is kept one 2:1 from the output and (by default) the final mux moves to
`regWriteAt`.

VexiiRiscv's `WriteBackPlugin` pipelines many write ports from arbitrary stages down to one
register-file port; with a single execute stage that whole mechanism collapses to this mux.

## Verification

Covered by [ExecuteSim](../../src/riscq/riscv/sim/ExecuteSim.scala) (every rd-writing op vs a golden
model), [MulSim](../../src/riscq/riscv/sim/MulSim.scala) (the `addRdSource` path), and the full
`rv32ui-p` / `rv32um-p` suites under RVLS/Spike in
[RvTestSim](../../src/riscq/riscv/sim/RvTestSim.scala).

```bash
mill runMain riscq.riscv.sim.ExecuteSim
mill runMain riscq.riscv.sim.RvTestSim
```

## Related

[IntAluPlugin](IntAluPlugin.md) · [LsuPlugin](LsuPlugin.md) · [BranchPlugin](BranchPlugin.md) ·
[CsrPlugin](CsrPlugin.md) · [MulPlugin](MulPlugin.md) · [RegFilePlugin](RegFilePlugin.md) ·
[pipeline.md](pipeline.md)
