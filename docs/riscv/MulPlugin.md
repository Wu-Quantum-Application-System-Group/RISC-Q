# MulPlugin — optional RV32 M multiply

**Source:** `src/riscq/riscv/execute/MulPlugin.scala` · **Package:** `riscq.riscv.execute` · **Type:** FiberPlugin (+ companion `AreaObject`)

## Purpose

The optional RV32 **M** multiply unit (Zmmul — multiply only): `MUL`, `MULH`, `MULHSU`, `MULHU`. Gated
by `RiscqParam.withMul` (default **on** — set it false, or `RISCQ_WITH_MUL=0` in the sims/benches, for
a minimal RV32I build).
DIV/REM are **not yet implemented** (a future `DivPlugin`; encodings would join `decode/Rv32m`).

## Role in the system

A self-contained `FiberPlugin`. riscq has a single execute stage, so — like the
[LSU](LsuPlugin.md) — it lives at `executeAt` and **halts** the stage while a small internal
pipeline computes the product, then fires. It contributes nothing to the RV32I datapath beyond one
registered rd-write source: it calls `WriteBackPlugin.addRdSource(SEL_MUL, MUL_RD_DATA)` (see
[WriteBackPlugin](WriteBackPlugin.md)), so with `withMul` off the core is byte-identical to before.
The four encodings are made legal via `DecoderPlugin.addInstruction` (see [DecoderPlugin](DecoderPlugin.md)).

## Design rationale

### Halt-execute, not a new pipeline stage

A multi-cycle unit on a single execute stage halts that stage for a fixed count, then fires — the
LSU pattern — rather than adding pipeline stages. A `counter` (0..`latency`) increments while
`active && busy`, resets whenever this is *not* an in-progress multiply (a non-mul, a flushed mul, or
the completing cycle), and `haltWhen(isValid && SEL_MUL && busy)` holds the stage. Operands are
captured on the **first** resident cycle because their forwarding-bypass window is one cycle (same
reason the LSU latches). `active` drops on `up.isCancel` — an older branch resolving at `jumpAt`
flushes `executeAt` — but the cancel is kept out of the halt qualifier: a cancelled multiply is only
ever hit on its first cycle, so the one extra halted cycle sits inside the mispredict flush shadow
(zero IPC).

The existing forwarding/interlock needs **zero changes**: any `RD_WRITE` producer that is not a
*cheap ALU op* is automatically treated as "expensive" by [RegFilePlugin](RegFilePlugin.md), so a
dependent consumer interlocks until the producer reaches `regWriteAt`. A halting multiply keeps
`isValid && RD_WRITE` asserted while stalled, so dependents simply wait — for free.

### DSP-friendly datapath: 33×33→64 split into four 27×18 partials

Each 32-bit operand is sign-extended to a 33-bit signed value (funct3 picks which operand is signed:
MUL/MULH signed×signed, MULHSU signed×unsigned, MULHU unsigned×unsigned). One signed 33×33→64
multiply then covers all four ops — MUL keeps the low word, the MULH* group the high word. Each
operand is split into a low (17-bit → 18-bit signed non-negative) and a high (16-bit signed) chunk
with `op = hi·2^17 + lo` exactly, so each of the four partial products fits a single Xilinx **27×18
DSP48** (Vivado infers DSP_ALU/DSP_OUTPUT — see [../dsp/DSP48.md](../dsp/DSP48.md)).

### Latency 3 — split the weighted sum across a register (the fmax fix)

This is a stated design fact, not a tuning knob: **`latency = 3`, so a multiply costs a 3-cycle
bubble**, trading IPC for timing.

- Cycle 1: the four partial-product multiplies are registered (`ppLL/ppLH/ppHL/ppHH`).
- Cycle 2: form **two independent partial sums in parallel** — the two weight-2^17 partials
  (`ppLH+ppHL`, pre-shifted into the 64-bit lane) and the non-overlapping 2^0 / 2^34 "ends"
  (`ppLL + ppHH<<34`, which overlap in only two bits).
- Cycle 3: add the two partial sums into the final 64-bit `product`.

The product is built across **two** register stages rather than one because the original single-cycle
weighted accumulate (sum all four DSP partials at 2^0/2^17/2^34 in one cycle: ~10 logic levels,
DSP_ALU + CARRY8 chains) was the lone dominant timing binder — it dropped post-route fmax to ~391 MHz.
Splitting it into a 2-level adder tree across a register recovered the multiplier (~391 → ~508 MHz),
the same lesson as VexiiRiscv's multi-stage `sumsSpec`: chained wide adds want a register split, not
one cycle. Two's-complement addition is modular, so the low 64 bits hold the correct value for every
signed/unsigned case (the product always fits in 64).

The result half (`MUL_RD_DATA`) is valid on the completing cycle; WriteBackPlugin samples it only
when the stage fires, so an in-progress value is never committed.

This is the depth-1 stripping of VexiiRiscv's `MulPlugin` — no multi-lane, no `MulSpliter`
generality, no `rsUnsignedPlugin`, no 64-bit MULW.

## Latency / timing

3-cycle halt per multiply (II≈3 for back-to-back multiplies); straight-line non-mul code is
unaffected. With `withMul`, the multiplier is no longer the binder — the remaining critical paths are
the base core's CSR-write and forwarding cones.

## Verification

[MulSim](../../src/riscq/riscv/sim/MulSim.scala) self-checks random + signed/unsigned corner operands
and ALU/MUL forwarding against a Scala golden model; the `rv32um-p-{mul,mulh,mulhsu,mulhu}` ELFs run
under RVLS/Spike in [RvTestSim](../../src/riscq/riscv/sim/RvTestSim.scala) (the probe advertises
`RV32I_Zmmul` to Spike when `withMul`, otherwise the multiply ops decode as illegal). All 43 tests pass (39
`rv32ui-p` + 4 `rv32um-p` mul group).

```bash
mill runMain riscq.riscv.sim.MulSim
mill runMain riscq.riscv.sim.RvTestSim   # withMul defaults on ⇒ includes the rv32um-p mul group
```

## Related

[WriteBackPlugin](WriteBackPlugin.md) · [LsuPlugin](LsuPlugin.md) · [RegFilePlugin](RegFilePlugin.md) ·
[DecoderPlugin](DecoderPlugin.md) · [DSP48 notes](../dsp/DSP48.md) · [RiscqParam](RiscqParam.md)
