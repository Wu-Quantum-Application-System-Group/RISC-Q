# CsrPlugin — minimal machine-mode Zicsr + trap layer

**Source:** `src/riscq/riscv/csr/CsrPlugin.scala` (+ `Csr.scala`) · **Package:** `riscq.riscv.csr`
· **Type:** FiberPlugin (+ companion `AreaObject` with the `IS_CSR` / `IS_ECALL` / `IS_EBREAK` /
`IS_MRET` payloads)

## Purpose

The smallest privileged slice that lets the standard `riscv-tests` `-p` (machine-mode) programs boot
and terminate: the Zicsr CSR-access instructions plus machine-mode trap entry/return. Deliberately
tiny next to VexiiRiscv's `PrivilegedPlugin` — machine mode only, no interrupts/CLINT, no S/U mode,
no PMP/MMU, direct `mtvec` only.

## Role in the system

Lives in the single execute stage (`executeAt`) and reuses the same redirect + `throwWhen`-flush
idiom as [BranchPlugin](BranchPlugin.md): it takes a redirect port from [PcPlugin](PcPlugin.md)
(`newRedirect()`) and flushes younger stages on a trap/mret. It registers its own control payload
`IS_CSR` with [DecoderPlugin](DecoderPlugin.md) (default `False`, set `True` for the six `CSRR*`
encodings), so the decode is generic rather than hard-wired here. The env ops are pre-decoded the
same way (`IS_ECALL`/`IS_EBREAK`/`IS_MRET`), so the trap/mret qualifier at execute reads registered
bits instead of an opcode/funct compare cone.

## What it does

- **Zicsr** — `CSRRW/S/C` and the `*I` variants. Reads the old CSR value to rd (via
  `Execute.CSR_RD_DATA`), then writes the new value, honouring the spec rule that a set/clear with
  `rs1`/`zimm == 0` performs **no write side effect**. The `*I` variants source the 5-bit
  zero-extended `zimm` from the rs1 field; the CSR address is `instr[31:20]`. CSR fields are read
  straight off the registered `Fetch.WORD` at execute. Each CSR's write enable is decoded in
  parallel (`writeEn && addr === X`) rather than through a serial `switch`; `csrCommitMaxFanout`
  (default 0 = off) optionally caps the `writeEn` fanout.
- **Trap entry** — a retiring `ECALL`/`EBREAK`, or **any illegal instruction** (`!Decode.LEGAL`),
  saves `mepc`/`mcause`, pushes the `mstatus` interrupt-enable stack (`MPIE ← MIE`, `MIE ← 0`), and
  redirects to `mtvec`. Causes: illegal = 2, ebreak = 3, ecall-from-M = 11.
- **`MRET`** — pops the `mstatus` stack and redirects to `mepc`. `WFI`/`FENCE`/`FENCE.I` are nops.

The redirect flushes the younger (wrong-path) stages via `pp.throwStagesBefore` /
`pp.throwSkidsBefore` at `executeAt` — off the up-ready spine.

## The architecturally-live CSR file

Only the registers that carry real behaviour are stored: `mstatus` (just the MIE/MPIE/MPP fields),
`mtvec`, `mepc`, `mcause`, `mscratch`, `mie` (stored but inert — no interrupts are taken), and
`mtval` (only ever 0). Addresses and bit positions are in [`Csr`](../../src/riscq/riscv/csr/Csr.scala).

Most of these have **no reset value** — intentional, not an omission. Their reset value is never
architecturally observed (`mepc`/`mcause` are HW-written on a trap before any read; `mscratch` is SW
write-before-read; `mtvec` is programmed before the first trap; `mie`/`mtval` are inert), so dropping
the init keeps them out of the async-reset control-set group, for denser packing and a lighter reset
net in the replicated multi-core SoC build. Behaviour is identical — do **not** add an init back.

## WARL/WLRL narrowing (`csrWarl`, default on)

Each CSR's WARL/WLRL latitude is applied so Vivado synthesises away the bits that can never vary:

- `mtval ≡ 0` (spec permits hardwired-zero); `mstatus.MPP ≡ 11` (machine-only, a constant);
- `mtvec`/`mepc` force `[1:0] = 0` (direct mode + IALIGN=32);
- `mcause` stores only the low 4-bit synchronous code (the interrupt bit is never set);
- `mie` keeps only `MSIE`/`MTIE`/`MEIE`.

This is also strictly **more** machine-only-Spike-conformant. The flag is a bit-exact A/B (identity
when off); see [RiscqParam](RiscqParam.md).

## Why rvls is patched for the WARL stubs

The `-p` startup pokes CSRs riscq doesn't model (`satp`, `pmp*`, `medeleg`/`mideleg`, …). Rather than
trap, riscq accepts each as a **write-ignored / read-zero WARL stub** so the boot code never hits an
illegal-instruction trap. A stock Spike, however, would *model* those CSRs and so would diverge from
riscq's stub read-back during [RVLS lock-step](RiscqProbe.md). The fix is to patch rvls with a
`stub_csr_t` for them (`patches/`) so Spike treats them as the same write-ignore/read-zero stubs.
`ext/rvls` is a submodule — re-apply the patch and `make` if it resets.

## Verification

The CSR/trap behaviour is checked end-to-end by the `-p` test suite under per-instruction RVLS/Spike
lock-step (every `rv32ui-p-*` program boots through `mtvec`/`mepc` and terminates via the
ecall/`tohost` convention). Whitebox `dbg*` signals feed [RiscqProbe](RiscqProbe.md).

```bash
mill runMain riscq.riscv.sim.RvTestSim   # rv32ui-p suite under RVLS lock-step
```

## Related

[BranchPlugin](BranchPlugin.md) · [PcPlugin](PcPlugin.md) · [DecoderPlugin](DecoderPlugin.md) ·
[RiscqProbe](RiscqProbe.md) · [RiscqParam](RiscqParam.md)
