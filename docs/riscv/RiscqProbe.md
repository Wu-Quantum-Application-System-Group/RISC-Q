# RiscqProbe — simulation verification harness (RVLS lock-step + Konata)

**Source:** `src/riscq/riscv/test/RiscqProbe.scala` · **Package:** `riscq.riscv.test`
· **Type:** plain class (driven from a `doSim` testbench)

## Purpose

Drive external trace consumers from the running core's whitebox signals: any number of
`rvls.spinal.TraceBackend`s (a `FileBackend` text trace, or an `RvlsBackend` Spike lock-step once
`librvls.so`/`rvls.so` is built) plus an optional Konata pipeline trace. This is how every whole-core
sim (`RvTestSim`) checks the core against the golden ISA model per instruction.

Because riscq is **single-issue, in-order, one retire per cycle**, the probe is a tiny fraction of
VexiiRiscv's: no fetch/decode/uop id rings, no reorder buffer, no multi-hart. Each cycle it reads the
one execute slot and reconstructs the Konata stage timeline from a small in-flight FIFO.

## Role in the system

```
Riscq (sim) whitebox dbg* signals ─▶ RiscqProbe.onSamplings ─┬─▶ TraceBackend(s)  (FileBackend / RVLS-Spike)
                                                             └─▶ konata.Backend   (optional pipeline trace)
```

It is constructed against an elaborated `Riscq` DUT and reaches each plugin's `simPublic` `dbg*`
signals via `dut.host[...]`: the [RegFilePlugin](RegFilePlugin.md) execute slot (committed PC /
instruction / rd write), [FetchPlugin](FetchPlugin.md) join (fetch-fire for Konata),
[LsuPlugin](LsuPlugin.md) (load/store address/size/data), [CsrPlugin](CsrPlugin.md) (trap cause +
CSR read/write), and [PcPlugin](PcPlugin.md) (redirect-fire, to flush the Konata in-flight queue).

## Per-cycle behaviour

On each clock sampling: a fetch-fire pushes an `InFlight(fetchCycle, pc, word)` record; an execute
retire either emits a `trap` (a retiring ECALL/EBREAK/illegal) or a `commit`, replaying any memory
access, integer rd write, and CSR read/write to every backend in the order RVLS expects; a
redirect-fire flushes every younger in-flight record (they were on the wrong path). `commitsCallbacks`
lets a testbench watch the committed PC — e.g. the rv32ui-p `pass`/`fail` symbols — and
`storeCallbacks` watches committed stores. A **liveness** guard fails the sim if no instruction
commits for `livenessThreshold` cycles (default 16000), catching a hang.

## The lock-step contract (the non-obvious requirements)

Spike runs **machine-only** (RV32I or RV32I_Zmmul), which imposes three things the probe must get right or
the comparison diverges:

- **`addRegion`.** RVLS/Spike treats every address as MMIO and faults on any fetch/load/store outside
  a declared region. riscq has a single flat RAM (the testbench's `SparseMemory` image), so the probe
  announces the whole XLEN address space as one main-memory region (kind 0 = RAM) per CPU.
- **`xlenExtend`.** Spike stores integer registers sign-extended to 64 bits even in RV32 mode, so a
  negative-looking 32-bit rd write (bit 31 set) must be widened the same way (`(v << 32) >> 32`)
  before it is compared, or every such write mismatches.
- **The ISA string.** `add()` advertises `s"RV${xlen}I" + (if (p.withMul) "_Zmmul" else "")` — i.e.
  `RV32I` for the base core, `RV32I_Zmmul` when `withMul` is on (multiply-only, no divide). If the
  string omits an enabled extension, Spike decodes those opcodes as illegal and the multiply tests
  fault inside RVLS rather than the core. (Add
  the matching letter for any future extension — VexiiRiscv does the same per-flag.)

Separately, the CSRs riscq models as WARL stubs (`satp`, `pmp*`, …) require rvls to be **patched**
with a `stub_csr_t` so Spike's read-back matches riscq's stub behaviour — see [CsrPlugin](CsrPlugin.md)
and `patches/`.

## Usage

Instantiate inside a `doSim` block against the elaborated DUT, add one or more backends, and let the
clock run:

```scala
val probe = new RiscqProbe(dut, param, kb = Some(konataBackend))
probe.add(rvlsBackend)               // or a FileBackend for a text trace
probe.commitsCallbacks += { (hart, pc) => /* watch for pass/fail */ }
```

RVLS auto-enables when `ext/rvls/build/apps/rvls.so` is reachable; force it with `RISCQ_RVLS=1`
(off with `0`). A run prints `RVLS/Spike per-instruction lock-step: ON` when active.

## Verification (what runs the probe)

```bash
mill runMain riscq.riscv.sim.RvTestSim   # all rv32ui-p (+ rv32um-p under withMul) ELFs, lock-step
```

`RvTestSim` loads each official `riscv-tests` ELF into `SparseMemory`, runs the core, and lets the
probe lock-step it against Spike instruction by instruction. All 39 `rv32ui-p-*` pass (43 with the M
multiply group when `withMul`).

## Related

[RegFilePlugin](RegFilePlugin.md) · [CsrPlugin](CsrPlugin.md) · [LsuPlugin](LsuPlugin.md) ·
[FetchPlugin](FetchPlugin.md) · [PcPlugin](PcPlugin.md) · [RiscqParam](RiscqParam.md)
