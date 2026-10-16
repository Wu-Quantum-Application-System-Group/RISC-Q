# SrcPlugin — ALU/branch operand select + the shared add/sub & comparators

**Source:** `src/riscq/riscv/execute/SrcPlugin.scala` · **Package:** `riscq.riscv.execute` · **Type:** FiberPlugin (+ companion `AreaObject`)

## Purpose

Builds the two operands every arithmetic/branch op consumes (`SRC1`, `SRC2`) and the one
**shared** add/sub and comparator that the integer ALU *and* the branch unit both read. There is a
single operand add/sub and a single less-than/equal compare in the whole core; this plugin owns them.

## Role in the system

It sits across two pipeline stages (see [pipeline.md](pipeline.md)):

```
regReadAt:  RS1_DATA/RS2_DATA (RegFilePlugin) ─┐
            Decode.IMM, PC                     ├─► SRC1/SRC2 muxes ─(StageLink register)─┐
                                               ┘                                          │
executeAt:  SRC1,SRC2 (registered) ─► ADD_SUB / LESS / EQ  ──────────────────────────────► IntAluPlugin, BranchPlugin, LsuPlugin
```

- The **source-select muxes** are built at `regReadAt` (one stage before `executeAt`). The
  `regReadAt→executeAt` StageLink then *registers* `SRC1`/`SRC2`, so the 32-bit add at `executeAt`
  starts from a register rather than from the operand-select logic — keeping the mux off the
  execute critical cone.
- The **add/sub + comparators** run at `executeAt` and publish `Execute.ADD_SUB`, `Execute.LESS`,
  `Execute.EQ` (see `execute/Execute.scala`). Downstream:
  [IntAluPlugin](IntAluPlugin.md) folds `ADD_SUB`/`LESS` into its result mux; [BranchPlugin](BranchPlugin.md)
  uses `EQ`/`LESS` for the branch condition and reuses `ADD_SUB` as the **JALR target** (`rs1+imm`);
  [LsuPlugin](LsuPlugin.md) reads `ADD_SUB` as the load/store **effective address** (`rs1+imm`).

## Design rationale

- **One adder, one comparator, shared.** Mirrors VexiiRiscv's `SrcPlugin`: a register-relative add
  (`SUB`/`SLT*`/every branch subtract) and a PC-relative or immediate add all collapse to one
  `SRC1 ± SRC2`. The LSU and JALR get their address adds for free instead of instantiating their
  own. The `addSub` net carries a `KeepAttribute` so Vivado does not fold the shared adder into the
  comparators' LUT chain (VexiiRiscv does the same) — cheap fmax insurance.
- **Instruction-agnostic.** The plugin *defines* the four control payloads in its companion object
  (`SRC1_SEL`, `SRC2_RS2`, `DO_SUB`, `CMP_UNSIGNED`) and reads them back as decoded payloads, so it
  never inspects opcode/funct itself. It registers only their **defaults** with
  [DecoderPlugin](DecoderPlugin.md); each per-instruction override is registered by the plugin that
  *implements* that instruction (IntAluPlugin for OP/OP-IMM/LUI/AUIPC, BranchPlugin for the
  conditional branches). This keeps SrcPlugin free of any instruction list — a new rs1+imm
  consumer just sets these knobs in its own decode.
- **Operand-register fanout cap.** `SRC1`/`SRC2` are very high fanout — they feed the adder,
  comparators, the shifter, the bitwise ops and the ALU fast-forward, and the single-cycle ALU
  result-forward loop runs through them and is route-dominated. `aluOperandMaxFanout` (default 16)
  asks Vivado to replicate these registers; it is a placement hint only, bit-exact.

## The four control payloads (companion object)

| payload | meaning |
|---|---|
| `SRC1_SEL` | SRC1 source: `RF` (rs1), `ZERO` (LUI), or `PC` (AUIPC) |
| `SRC2_RS2` | SRC2 is rs2 (OP / BRANCH) vs the immediate (everything else) |
| `DO_SUB`   | subtract for SUB / SLT* / every branch (they all compare via SRC1−SRC2) |
| `CMP_UNSIGNED` | unsigned less-than for SLTU/SLTIU/BLTU/BGEU |

LUI/AUIPC are handled here, not as a post-mux special case: LUI sets `SRC1=0`, AUIPC sets `SRC1=PC`,
both take `SRC2=imm`, so the adder result is exactly the U-immediate / PC+immediate and the ALU's
result mux needs no opcode override.

## Latency / timing

Combinational within each stage; one StageLink register between the muxes (`regReadAt`) and the
add/sub (`executeAt`). No halts.

## Verification

Exercised end-to-end by [ExecuteSim](../../src/riscq/riscv/sim/ExecuteSim.scala) (lock-step vs a
Scala golden RV32I model covering every ALU op, all branches taken/not-taken, JAL/JALR, and
back-to-back dependent pairs) and by the full `rv32ui-p-*` suite under RVLS/Spike in
[RvTestSim](../../src/riscq/riscv/sim/RvTestSim.scala).

```bash
mill runMain riscq.riscv.sim.ExecuteSim
mill runMain riscq.riscv.sim.RvTestSim
```

## Related

[IntAluPlugin](IntAluPlugin.md) · [BranchPlugin](BranchPlugin.md) · [LsuPlugin](LsuPlugin.md) ·
[DecoderPlugin](DecoderPlugin.md) · [RegFilePlugin](RegFilePlugin.md) · [pipeline.md](pipeline.md)
