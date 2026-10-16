# DecoderPlugin — RV32I decode + generic control-signal decoding engine

**Source:** `src/riscq/riscv/decode/DecoderPlugin.scala` (+ `Rv32i.scala`, `Rv32m.scala`, `Decode.scala`)
· **Package:** `riscq.riscv.decode` · **Type:** FiberPlugin (+ supporting `object`s / `AreaObject`)

## Purpose

Turn the fetched 32-bit instruction word into everything the back end needs to act on it: the
positional fields (rs1/rs2/rd, funct3/funct7), a legality flag, the sign-extended immediate, and the
per-instruction *control* signals each execute plugin consumes. It is both the RV32I decoder and a
generic **decoding engine** that other plugins register against — mirrors VexiiRiscv's
`DecoderPlugin` + `DecoderService`, stripped to one lane (no aligner, no micro-op framework, no
traps inside the decoder).

## Role in the system

Lives at `decodeAt` on the single [pipeline](pipeline.md) chain. It reads the registered
`Fetch.WORD` arriving from [FetchPlugin](FetchPlugin.md), drives the `Decode.*` payloads, and the
StageLinks register every result forward into `regReadAt`/`executeAt`. Downstream readers
([SrcPlugin](SrcPlugin.md), [IntAluPlugin](IntAluPlugin.md), [BranchPlugin](BranchPlugin.md),
[LsuPlugin](LsuPlugin.md), [CsrPlugin](CsrPlugin.md), optional [MulPlugin](MulPlugin.md)) read the
decoded payloads instead of re-comparing opcode/funct themselves, so each late mux select is
register-direct.

```
Fetch.WORD ─▶ DecoderPlugin @decodeAt ─▶ Decode.{RS1,RS2,RD,FUNCT3,FUNCT7,LEGAL,IMM/IMM_TYPE}
                  │                       + each plugin's own control payload (IS_CSR, ALU_SEL, …)
                  └─ Symplify/DecodingSpec, one pass per registered payload
```

## The decoding-engine pattern (the heart)

The decoder produces only the **intrinsic** results — the ones every consumer needs the same way.
The per-instruction *control* bits live in the plugins that consume them. During its `setup`, each
consumer registers what it needs while holding the decoder's `elaborationLock`:

- **`addDecodingDefault(key, value)`** — the value a control payload takes on every legal encoding
  that doesn't override it.
- **`addDecoding(enc, key, value)`** (or a `Seq` of `(key, value)`) — one payload's value for one
  encoding. `value` is `Any`: a `True`/`False`/enum element.
- **`addInstruction(enc, immType)`** — *extensions only*: make a new encoding legal and give it an
  immediate format.

The decoder waits on `elaborationLock` (so every plugin has registered first), then in `logic`
builds **one `Symplify`/`DecodingSpec` pass per registered payload**, all at `decodeAt`. So a new
extension keeps its decoding inside its own `FiberPlugin` — e.g. `MulPlugin` defines `SEL_MUL` and
registers it here with no edit to this file. (Companion `AreaObject`s like `object CsrPlugin { val
IS_CSR = … }` hold the payload keys so cross-plugin readers reference them statically.)

## Base-vs-extension split (a load-bearing invariant — do not break it)

- Base **RV32I legality + immediate format** are seeded inside `DecoderPlugin` from the [`Rv32i`]
  catalog (`Rv32i.all` / `Rv32i.instructions`) — the single source of truth for both the legality
  cover and the immediate-format table, so the two can never disagree.
- Base plugins register **only control decodings**, never `addInstruction` — calling it for a base
  encoding would double-register (duplicate Symplify terms).
- `addInstruction` is reserved for **extension** encodings ([`Rv32m`] for M): it both makes the
  opcode legal and assigns its immediate format. With an extension *disabled*, its opcode is simply
  never registered ⇒ illegal ⇒ traps (the `CsrPlugin` `!LEGAL` path fires).
- The legality/`coverAll` set for every `DecodingSpec.build` is `Rv32i.all ++ extraEncodings`. On an
  illegal word the defaults drive every control to its benign value (0 / RF / ADD); the trap still
  fires.

This means the decode-only config (`decodePlugins()`, used by `DecodeSim` with no execute plugins)
still decodes legality and immediate correctly.

## Immediate handling & `deriveImmFromWord`

The five RISC-V immediate formats (I/S/B/U/J) are rebuilt from the raw word by a pure function of
the word, so it can run wherever the word is available. With `deriveImmFromWord = true` (default)
the decode→regRead link carries only the **3-bit `Decode.IMM_TYPE`**, and the 32-bit `Decode.IMM` is
rebuilt at `regReadAt` from the `Fetch.WORD` (already carried to executeAt). Driver and the
SrcPlugin/BranchPlugin readers are all at `regReadAt`, so the wide immediate crosses no StageLink and
costs no pipeline register. With the flag off, the decoder drives the full `Decode.IMM` at
`decodeAt` and carries it forward. Bit-identical either way (RVLS-gated); see
[RiscqParam](RiscqParam.md).

## Interface & payloads

`Decode` (an `AreaObject`) holds only the intrinsic payloads: `RS1/RS2/RD` (5-bit), `FUNCT3`/`FUNCT7`,
`LEGAL`, `IMM` (XLEN), and `IMM_TYPE` (carried only under `deriveImmFromWord`). `ARCH_REGS = 32` is
an ISA invariant, not a `RiscqParam`. `Rv32i` exposes the field bit-ranges, the 7-bit major
`Opcode`s, the named masked-literal encodings, and the `(encoding, immType)` table. `Rv32m` holds
the four multiply encodings (`MUL/MULH/MULHSU/MULHU`); DIV/REM would join it with a future
`DivPlugin`.

## Verification

`DecodeSim` (the decode-only config) self-checks the intrinsic outputs against a golden decoder over
random + corner words; the whole-core `RvTestSim` exercises every encoding through RVLS/Spike
lock-step. Generate / simulate:

```bash
mill runMain riscq.riscv.RiscqGen      # generate Verilog
mill runMain riscq.riscv.sim.DecodeSim # self-checking decode golden
```

## Related

[FetchPlugin](FetchPlugin.md) · [pipeline](pipeline.md) · [SrcPlugin](SrcPlugin.md) ·
[IntAluPlugin](IntAluPlugin.md) · [BranchPlugin](BranchPlugin.md) · [MulPlugin](MulPlugin.md) ·
[CsrPlugin](CsrPlugin.md) · [RiscqParam](RiscqParam.md)
