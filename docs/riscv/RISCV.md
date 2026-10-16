# RISC-V core — the riscq RV32I control core

**Source:** [`src/riscq/riscv/`](../../src/riscq/riscv/) · **Package:** `riscq.riscv` · **Type:** plugin-on-a-host core

The control processor at the heart of the project's signal-processing SoC: a single-issue,
in-order, pipelined **RV32I** core in **SpinalHDL**, with an **optional Zmmul** (multiply-only) extension.
It is built **from scratch** following VexiiRiscv's plugin + pipeline *patterns* but
deliberately stripped of its generality (no multi-lane, no RVC aligner, no MMU/PMP, no OoO
machinery). It runs on Xilinx Virtex UltraScale+ `xczu49dr-ffvf1760-2-e`
and closes timing at **~590 MHz post-route OOC** on the `-2` part (with M, ~508 MHz; the
multiply unit is the binder).

This file is the per-core overview: the architecture, the verification approach, the durable
gotchas, and an index into the per-plugin reference docs. The core measured against the RISC-Q
reference design is in [RISC-Q-COMPARISON.md](RISC-Q-COMPARISON.md).

CPU-internal code lives under `riscq.riscv`; the signal peripherals and SoC fabric built around
the core live in sibling subpackages of `riscq` (see [`../soc/`](../soc/), [`../dsp/`](../dsp/)).

## Design philosophy

Correctness and clean design come first; **fmax is a soft, best-effort constraint**. A feature is
done when it passes a self-checking SpinalSim *and* per-instruction RVLS/Spike lock-step. Each ISA
extension is its own optional `FiberPlugin` gated behind a `RiscqParam` flag, so the base RV32I core
stays minimal and a config can ship without it — extension logic is never hard-wired into the RV32I
datapath.

## Architecture

**Plugins on a host.** Every feature is a `FiberPlugin` (`spinal.lib.misc.plugin`). The
toplevel [`Riscq`](../../src/riscq/riscv/Riscq.scala) owns only a fiber `Database` (for shared
constants) and a `PluginHost` (on which plugins register and elaborate); it generates no
hardware itself. `Riscq(param.plugins())` assembles a configured core — see
[RiscqParam](RiscqParam.md) for how the plugin list is built.

**One unified pipeline chain.** [`PipelinePlugin`](pipeline.md) is a *single* `CtrlLink` chain
(`spinal.lib.misc.pipeline`). A feature plugin grabs `pp.ctrl(i)` and injects logic with
`new pp.ctrl(i).Area { ... }`. We use **one** chain rather than VexiiRiscv's three (fetch /
decode / execute) because riscq has no RVC aligner and no multi-lane execute, so a single
in-order chain is the simpler exact fit.

**Stage partitioning.** Stage indices are derived from [`RiscqParam`](RiscqParam.md) and
partition the chain into **Fetch → Decode → Execute**. With the defaults:

| stage | index | role |
|---|---|---|
| `pcAt`        | 0 | PC injected; fetch fork issues the iBus `Get`; BTB / GShare read |
| `fetchDataAt` | 1 | instruction word joined (`Fetch.WORD` written) |
| `decodeAt`    | 2 | decode: register fields, immediate type, control payloads |
| `regReadAt`   | 3 | register-file read + forwarding + SRC mux; branch `pc+imm` precompute |
| `executeAt`   | 4 | ALU / branch-resolve / LSU / CSR / multiply |
| `regWriteAt` = `jumpAt` | 5 | rd write-back; branch redirect + flush |

Bumping a `RiscqParam` index inserts a registered stage (exactly as VexiiRiscv's
`aluAt`/`jumpAt`/`forkAt`); raising `fetchLatency` slides the whole back end down in lock-step.

**Shared constants.** A fiber `Database` in [`Global`](../../src/riscq/riscv/Global.scala) is
published once by [`RiscqPlugin`](../../src/riscq/riscv/RiscqPlugin.scala) (`Global.XLEN`,
`Global.FETCH_PC_WIDTH`, `Global.PC_BASE`, …). Reads **block** until the value is set, so plugin
elaboration order doesn't matter.

**Injecting logic — the elaboration protocol.** A plugin holds `pp.elaborationLock` (via
`retains(...)`) from its `during setup` until it has registered all its stages; `PipelinePlugin`
then connects every link and calls `Builder` once. See [pipeline.md](pipeline.md).

## Build & run

```bash
mill runMain riscq.riscv.RiscqGen          # generate Verilog (Riscq.v)
mill runMain riscq.riscv.sim.FetchSim      # a SpinalSim testbench (Verilator)
mill runMain riscq.riscv.sim.RvTestSim     # all rv32ui-p-* ELF tests (add a name to run one)
mill runMain riscq.riscv.bench.PathAnalysis # static longest-path + fanout report (pre-synth)
mill runMain riscq.riscv.bench.VivadoBench  # Vivado synth: est. fmax + top critical paths
```

The DUT-agnostic analysis/bench engines live in [`riscq.bench`](../bench/); the
`riscq.riscv.bench.*` objects are thin entry points that feed the core into them.

The build needs the `ext/SpinalHDL` and `ext/rvls` submodules; run `git submodule update --init`
after cloning. `RvTestSim` (and the SoC's `RamOnFabricSim`) load the prebuilt `rv32ui-p-*` /
`rv32um-p-*` riscv-tests ELFs from the `ext/NaxSoftware` submodule (`ext/NaxSoftware/riscv-tests`). RVLS lock-step is forced on with `RISCQ_RVLS=1` (off with
`RISCQ_RVLS=0`); the run prints `RVLS/Spike per-instruction lock-step: ON` when it is active.

## Verification

**Self-checking SpinalSim first → RVLS/Spike lock-step + Konata later.** riscq does **not** depend on
the VexiiRiscv build module: `build.mill` compiles the `rvls.spinal.*` Spike-lockstep bindings straight
from the `ext/rvls` submodule, and the two small support pieces are vendored under `src/` —
`riscq.riscv.test.konata.Backend` (the Konata trace writer) and `spinal.lib.misc.Elf` (the jelf-based
ELF loader). `SparseMemory` and the Tilelink `MemoryAgent` come from SpinalHDL proper.

**Per-instruction Spike lock-step** ([`sim/RvTestSim`](../../src/riscq/riscv/sim/RvTestSim.scala)
+ [`RiscqProbe`](RiscqProbe.md) → `rvls.spinal.TraceBackend`) auto-runs when
`ext/rvls/build/apps/rvls.so` exists (`make` in `ext/rvls`). Spike runs **machine-only**
(RV32I/M), so the probe must `addRegion` (RVLS faults outside declared regions) and `xlenExtend`
rd writes, and rvls is **patched** for the WARL-stub CSRs ([`patches/`](../../patches/README.md))
— the `ext/rvls` submodule tracks a fork that already carries the patch. All `rv32ui-p-*` (and, with
`withMul`, the `rv32um-p-*` mul group) ELFs pass under lock-step.

## Gotchas (durable)

- **Instantiate plugins inside elaboration**, never at an `App`'s top level — doing it before
  `SimConfig.compile{ Riscq(...) }` throws `GlobalData ... null`. In sims, reach a plugin from
  the elaborated DUT via `dut.host[PluginType]`, then `plugin.logic.<member>` for `simPublic`
  signals. (`simPublic` needs `import spinal.core.sim._`.)
- **`CtrlLink.forkStream` fires on *bus-accept* (downstream `ready`), not on node-advance.** With
  one outstanding request and no reorder buffer, a fork at an upstream stage can issue the *next*
  request while the current instruction is held by a downstream stall, and the response corrupts
  the held slot. Two valid answers: issue the `Get` *at the consuming stage* (the
  [LSU](LsuPlugin.md), single outstanding), or fork upstream but tag each `Get` with a **per-id
  reorder buffer** so responses demux by id (the [fetch](FetchPlugin.md), multi-outstanding, to
  sustain IPC = 1 across memory latency).
- **A get-only Tilelink bus (`withDataA=false`) has null `a.mask`/`a.data`/`a.corrupt`** — don't
  drive them; `size` alone says "one word". See [MemBus](MemBus.md).
- **Vivado synthesis (`VivadoBench`/`VivadoRouteBench`) is fully deterministic and
  Verilog-order-insensitive** — zero run-to-run spread; signal-declaration order has no effect.
- **The two fmax binders are textbook and route-dominated** (not logic-depth-dominated): the ALU
  result→operand forward loop and the `pcReg → predicted-target → pcReg` redirect loop. On FPGA
  ~68% of the critical path is interconnect, so deeper-pipeline levers buy far less than FO4 math
  predicts; the timing levers in [RiscqParam](RiscqParam.md) attack routing/placement, not depth.
- **Trust the mill build, not IDE diagnostics** — the IDE lags after new files/imports.
- Conventions: 2-space indent (scalafmt), pipeline payloads `UPPER_CASE`, parameterize widths via
  [`RiscqParam`](RiscqParam.md) (never hard-code).

## Module index

Configuration & skeleton
- [RiscqParam](RiscqParam.md) — core configuration: stage indices, fetch latency, predictor
  sizes, optional extensions, the timing-closure levers, and how `plugins()` is assembled.
- [PipelinePlugin](pipeline.md) — the single CtrlLink-chain skeleton + the elaboration/skid
  protocol.
- [MemBus / DataMemBus](MemBus.md) — the Tilelink bus shapes the fetch (get-only) and LSU use.

Fetch & prediction
- [PcPlugin](PcPlugin.md) — the architectural PC, redirects, and the fetch predictor port.
- [FetchPlugin](FetchPlugin.md) — cacheless Tilelink iBus fetch with a per-id reorder buffer.
- [BtbPlugin](BtbPlugin.md) — branch target buffer (fetch-stage predictor).
- [GSharePlugin](GSharePlugin.md) — GShare direction predictor.

Decode & register file
- [DecoderPlugin](DecoderPlugin.md) — RV32I/M instruction decode.
- [RegFilePlugin](RegFilePlugin.md) — 32×XLEN register file + the forwarding network.

Execute
- [SrcPlugin](SrcPlugin.md) — operand (SRC1/SRC2) selection.
- [IntAluPlugin](IntAluPlugin.md) — the integer ALU.
- [BranchPlugin](BranchPlugin.md) — branch/jump resolution + predictor correction.
- [LsuPlugin](LsuPlugin.md) — the cacheless load/store unit (Tilelink dBus).
- [MulPlugin](MulPlugin.md) — the optional Zmmul multiply unit.
- [WriteBackPlugin](WriteBackPlugin.md) — the generic rd-source mux.

System & verification
- [CsrPlugin](CsrPlugin.md) — minimal machine-mode Zicsr + traps.
- [RiscqProbe](RiscqProbe.md) — the RVLS/Spike + Konata trace probe.
- [RISC-Q-COMPARISON](RISC-Q-COMPARISON.md) — the core measured against the RISC-Q reference.
