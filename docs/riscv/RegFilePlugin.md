# RegFilePlugin — integer register file (32×XLEN, x0≡0) + operand forwarding

**Source:** `src/riscq/riscv/regfile/RegFilePlugin.scala` · **Package:** `riscq.riscv.regfile`
· **Type:** FiberPlugin

## Purpose

The architectural integer register file: 32 entries of XLEN bits, with `x0` hard-wired to 0. Built
as a small **asynchronous-read** memory (distributed RAM, `RAMD32`): two combinational read ports for
rs1/rs2 and one synchronous write port for rd. On top of the raw storage it implements the
operand-forwarding network that keeps back-to-back dependent instructions bubble-free.

## Role in the system & stage placement

The read is split one stage ahead of execute and the write one stage behind it, spanning three
[pipeline](pipeline.md) stages:

- **Read at `regReadAt` (= `executeAt-1`).** Two async reads (x0 ⇒ 0) drive `Execute.RS1_RAW`/
  `RS2_RAW`. The regReadAt→executeAt StageLink *registers* them, so the async read leaves the
  executeAt critical cone, and *holds* them across a stall so a stalled load/CSR keeps its operands.
- **Forward + source-select at `regReadAt`.** The 2-deep forward and the SrcPlugin source-select sit
  here (one stage early), driving `Execute.RS1_DATA`/`RS2_DATA` into executeAt.
- **Write at `regWriteAt` (= `executeAt+1` = `jumpAt`, so no added depth).** `RD_DATA` is registered
  across the StageLink, breaking the ALU/JALR-add → distributed-RAM-write cone at a register.

## How operands and write-back are "claimed" (the contract)

There is no port-allocation API here (unlike VexiiRiscv's read/write-port service). The contract is
**payload-based**:

- The two read ports are owned by this plugin and published as `Execute.RS1_DATA`/`RS2_DATA` —
  [SrcPlugin](SrcPlugin.md) reads those to form the ALU/branch operands.
- The single write port consumes `Execute.RD_DATA` (the value) gated by `Execute.RD_WRITE` (commit
  flag). Any execute plugin that writes a register produces those payloads — the ALU and
  [WriteBackPlugin](WriteBackPlugin.md) for the base sources, and an extension via
  `WriteBackPlugin.addRdSource`. `x0` never writes; `down.isFiring` gates out a flushed/bubble
  instruction so a wrong-path write never lands.

## Forwarding & interlock (the design rationale)

The read→write gap is 2 stages, so a result must be forwarded to a dependent that reads it before the
write commits. A **selective 2-deep forward** keeps the deep producers out of the 1-ahead loop —
this is the core's #1 fmax binder, so what sits in the loop matters:

- **srcA** — the 1-ahead producer's *shallow* `Execute.ALU_FAST` tap, forwarded **only for cheap
  producers** (a non-shift ALU op, whose result is available without the barrel shifter),
  `isValid`-gated.
- **srcB** — the 2-ahead producer's registered `regWriteAt` commit (any class), `isValid`-gated.
- Priority srcA > srcB > raw read; x0 never forwards.

An **expensive** 1-ahead producer (shift/load/CSR/jump/multiply) commits too late for srcB and isn't
covered by srcA, so a class-gated **1-cycle interlock** holds `regReadAt` until it reaches
`regWriteAt` (after the bubble srcB covers it). This is `isValid`-gated, not `isFiring`: a halted
producer (e.g. a load awaiting memory, or a multi-cycle multiply) still owns rd and must still
block — which is why a halting multi-cycle unit like [MulPlugin](MulPlugin.md) needs **zero**
forwarding changes. Back-to-back ALU pairs (the common case) stay bubble-free.

`RiscqParam` levers tune this loop (all RVLS bit-exact except where noted): `aluFastAddOnly`
narrows the fast tap to the ADD class (small IPC cost); `aluNoFastForward` drops srcA entirely and
interlocks every 1-ahead hazard; `aluOperandMaxFanout` caps the operand-register fanout so Vivado
replicates it. See [RiscqParam](RiscqParam.md) and the
[RISC-Q comparison](RISC-Q-COMPARISON.md) for why this loop is the binder.

## Reset / storage notes

`regs` is a `Mem.fill(32)` initialised to all-zero for a deterministic reset state, whiteboxed for
the lock-step testbench. The committed write (rd/PC/instruction) is whiteboxed at `executeAt` for
[RiscqProbe](RiscqProbe.md) — kept there because an instruction firing at executeAt is past any flush
and will reach regWriteAt, keeping the RVLS retire point consistent.

## Verification

`ExecuteSim` exercises the forwarding/interlock against random dependent sequences; the whole-core
`RvTestSim` validates register state under RVLS/Spike lock-step.

```bash
mill runMain riscq.riscv.sim.ExecuteSim
mill runMain riscq.riscv.sim.RvTestSim
```

## Related

[SrcPlugin](SrcPlugin.md) · [IntAluPlugin](IntAluPlugin.md) · [WriteBackPlugin](WriteBackPlugin.md) ·
[MulPlugin](MulPlugin.md) · [pipeline](pipeline.md) · [RiscqParam](RiscqParam.md) ·
[RISC-Q comparison](RISC-Q-COMPARISON.md)
