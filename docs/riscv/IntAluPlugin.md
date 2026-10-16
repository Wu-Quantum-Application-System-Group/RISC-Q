# IntAluPlugin — the integer ALU

**Source:** `src/riscq/riscv/execute/IntAluPlugin.scala` · **Package:** `riscq.riscv.execute` · **Type:** FiberPlugin (+ companion `AreaObject`)

## Purpose

Every RV32I register-writing op that is not a load or a jump: `ADD(I)`, `SUB`, the logicals
`AND(I)/OR(I)/XOR(I)`, the set-less-than `SLT(I)(U)`, the shifts `SLL(I)/SRL(I)/SRA(I)`, and
`LUI/AUIPC`. It produces `Execute.ALU_RESULT` and the decoded `ALU_WRITES_RD` flag that
[WriteBackPlugin](WriteBackPlugin.md) reads.

## Role in the system

Lives entirely at `executeAt` (see [pipeline.md](pipeline.md)). Its heavy arithmetic is **not its
own**: the add/sub and the less-than comparison arrive pre-computed from [SrcPlugin](SrcPlugin.md)
(`Execute.ADD_SUB`, `Execute.LESS`), shared with the branch unit. This plugin adds only the bitwise
ops, the barrel shifter, and the result mux on top.

```
SrcPlugin: SRC1, SRC2, ADD_SUB, LESS ─► [ shifter | XOR/OR/AND | SLT ] ─► result mux ─► ALU_RESULT ─► WriteBackPlugin
                                                                                     └─► ALU_FAST ─► RegFilePlugin srcA forward
```

## Design rationale

- **Reuse the shared adder/comparator.** LUI/AUIPC fold into the ADD select (SrcPlugin already set
  `SRC1=0`/`PC` and `SRC2=imm`), so the result mux needs no post-switch opcode override. The plugin
  never instantiates a second adder.
- **Decode keys it owns vs knobs it borrows.** It defines its own result/shift selects (`ALU_SEL`,
  `SHIFT_LEFT`, `SHIFT_ARITH`, `ALU_WRITES_RD`) and registers them with [DecoderPlugin](DecoderPlugin.md);
  separately it registers, *for its own instructions*, the operand/compare overrides that belong to
  SrcPlugin (`SRC1_SEL`, `SRC2_RS2`, `DO_SUB`, `CMP_UNSIGNED`). The payloads are SrcPlugin's; the
  per-instruction values are the implementer's to declare. (This is the decoder-service split used
  across the execute plugins.)
- **Single-cycle barrel shifter.** `SLL/SRL/SRA` are one combinational barrel shift (~5 mux levels),
  not an iterative shifter — simpler and, on this part, not the binder. The shift amount is the low
  5 bits of `SRC2` (rs2 for `SLL`, the I-immediate for `SLLI`).
- **`ALU_FAST` — the shallow forward tap.** Alongside the full `ALU_RESULT`, the plugin publishes a
  shallower `ALU_FAST` that [RegFilePlugin](RegFilePlugin.md) uses as its single-cycle srcA forward
  source. `ALU_FAST` deliberately drops the barrel shifter (a shift producer interlocks, so its
  result is never forwarded one-cycle-cheap — a don't-care), keeping the shifter out of the
  route-dominated `operand → ALU → forward → operand` loop. It equals `ALU_RESULT` for every op a
  cheap forwarding producer can be.

## Configuration levers (RiscqParam)

Both default to the timing-favouring setting; toggling them is RVLS-verified.

- **`aluFastAddOnly`** (default on) — narrow `ALU_FAST` to *only* the ADD class (`ALU_FAST := addSub`,
  no result mux), one logic level shorter in the forward loop. RegFilePlugin then treats
  SLT/XOR/OR/AND producers as "expensive" (interlocked), so those cases are dead and dropped. Costs
  a little IPC (back-to-back dependent SLT/bitwise pairs bubble one cycle) for shorter timing.
- **`aluResultOneHot`** (default off) — build the main result mux as a balanced one-hot
  masked-OR cone (each candidate ANDed with its `ALU_SEL` one-hot bit, OR-reduced as a balanced
  tree) instead of the priority `switch`'s chained 2:1 muxes. `AluSel` covers all six selects
  exactly, so precisely one term is non-zero ⇒ **bit-identical** to the switch. Placement-sensitive
  (helps congested regions, can regress a clean one), so it stays flag-gated.

## Latency / timing

Single-cycle, II=1, no halts. Its result is the late arrival into WriteBackPlugin's rd mux, and the
`ALU_RESULT → forward → operand` loop is one of the core's two route-dominated binders (the other is
the PC/redirect loop in [BranchPlugin](BranchPlugin.md)).

## Verification

[ExecuteSim](../../src/riscq/riscv/sim/ExecuteSim.scala) lock-steps every ALU op (including
LUI/AUIPC and dependent-pair forwarding) against a Scala golden model; the `rv32ui-p-*` suite in
[RvTestSim](../../src/riscq/riscv/sim/RvTestSim.scala) checks it under RVLS/Spike.

```bash
mill runMain riscq.riscv.sim.ExecuteSim
mill runMain riscq.riscv.sim.RvTestSim
```

## Related

[SrcPlugin](SrcPlugin.md) · [WriteBackPlugin](WriteBackPlugin.md) · [RegFilePlugin](RegFilePlugin.md) ·
[DecoderPlugin](DecoderPlugin.md) · [pipeline.md](pipeline.md)
