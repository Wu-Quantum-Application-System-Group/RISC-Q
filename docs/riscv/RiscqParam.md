# RiscqParam — the core configuration object

**Source:** [`src/riscq/riscv/RiscqParam.scala`](../../src/riscq/riscv/RiscqParam.scala) ·
**Package:** `riscq.riscv` · **Type:** `case class` + plugin-list builder

`RiscqParam` is the single source of truth for one [`Riscq`](RISCV.md) core: it holds every
elaboration-time knob and exposes the `plugins()` builders that assemble the configured plugin
list. `Riscq(RiscqParam().plugins())` builds the default core. This doc covers what each knob
controls and what choosing it costs; the source is the exhaustive field list.

## Stage geometry

The pipeline is one in-order [`CtrlLink` chain](pipeline.md); its depth is fully parameterized by
a few stage indices, and bumping one **inserts a registered stage** (exactly as VexiiRiscv's
`aluAt`/`jumpAt`/`forkAt`). The defaults are intentionally *derived* so the geometry stays
self-consistent:

| knob | default | meaning |
|---|---|---|
| `pcAt` | 0 | where the PC is injected and the fetch `Get` is forked |
| `fetchLatency` | 1 | instruction-memory read latency **in pipeline stages** |
| `decodeAtOverride` | None → `fetchDataAt + 1` | the decode stage |
| `executeAtOverride` | None → `decodeAt + 2` | the back-end (ALU/branch/LSU/CSR) stage |

Derived (read-only) accessors: `fetchDataAt = pcAt + fetchLatency` (where `Fetch.WORD` arrives),
`regReadAt = executeAt - 1` (register read, so operands are registered into execute),
`regWriteAt = executeAt + 1` (= `jumpAt`; RD_DATA is registered, breaking the ALU→regfile-write
cone). With the defaults these land at 0 / 1 / 2 / 3 / 4 / 5 (see the stage map in
[RISCV.md](RISCV.md)).

**Why `decodeAt`/`executeAt` are `Option`s, not plain `Int`s.** They default to positions derived
from `fetchDataAt`, so raising `fetchLatency` slides the whole back end down the chain in
lock-step instead of leaving decode hard-wired at stage 2 (which would collide with — or fall
*before* — `fetchDataAt` once `fetchLatency > 1`). Scala forbids a default in the parameter list
from referencing an earlier parameter, hence the `Option` + derived-accessor split. Pass
`Some(i)` only to pin an index explicitly. `require`s catch any override (or `fetchLatency`) that
would fold the back end onto or ahead of the fetched word.

**`fetchLatency`** must be ≥ the memory's response latency `L` to sustain IPC = 1: the
multi-outstanding [fetch](FetchPlugin.md) keeps `fetchLatency` requests in flight so a word
arrives every cycle. Raising it costs the PC-carrying flop copies across the extra stages.

## Predictor sizing

- **`btbSets`** (default 2) — direct-mapped [BTB](BtbPlugin.md) entry count (power of two ≥ 2).
  More entries capture more branch sites at the cost of BTB tag/target flops.
- **`gshareHistoryWidth`** (default 6) — [GShare](GSharePlugin.md) global-history / counter-index
  width; `2^w` 2-bit counters. Wider history sharpens direction prediction but grows the counter
  table. (Constrained: `fetchPcWidth ≥ 2 + gshareHistoryWidth` for the PC hash.)

## Optional ISA extensions

- **`withMul`** (default **on**) — adds the Zmmul multiply-only unit ([MulPlugin](MulPlugin.md)).
  Set it false (SoC spec `with_mul: false`, or `RISCQ_WITH_MUL=0` in the sims/benches) for a
  minimal RV32I-only build. Costs a 3-cycle bubble per multiply. DIV/REM are not implemented.

## Resource-shrink flags (default on; bit-exact A/B)

Each defaults on, is RVLS-gated bit-exact, and trims flops:

- **`gshareMem`** — store the GShare 2-bit counters in a synchronous-read `Mem` (BRAM/LUTRAM)
  instead of a `2^w×2` flip-flop array + `2^w:1` read mux + `2^w`-way write decode. The big
  control-set / congestion win on a multi-instance floorplan.
- **`csrWarl`** — apply each CSR's WARL/WLRL latitude (`mtval≡0`, `mie` keeps only MSIE/MTIE/MEIE,
  `mcause` stores the 4-bit code, `mtvec`/`mepc` force `[1:0]=0`, `mstatus.MPP≡11`). Fewer FFs and
  strictly *more* machine-only-Spike-conformant.
- **`deriveImmFromWord`** — stop carrying the 32-bit `Decode.IMM` payload on the decode→regRead
  link; carry only the 3-bit `Decode.IMM_TYPE` format and rebuild the immediate at `regReadAt`
  (the consumer stage — both `SrcPlugin`'s SRC2 mux and `BranchPlugin`'s `pc+imm` precompute read
  it there) from the `Fetch.WORD` already carried to executeAt. Bit-identical immediate one stage
  later ⇒ RVLS bit-exact, fmax-neutral, −29 FF/core.

## Fetch-PC narrowing

- **`fetchPcWidth`** (default `None` = full XLEN) — `Some(w)` carries only the low `w`-bit PC
  **offset** through the pipeline and reconstructs the full address (`pcBase | offset`) at the
  four boundaries where software observes a 32-bit address: the iBus address, the AUIPC SRC1, the
  JAL/JALR return address, and the `mepc`/`mtvec` CSR reads. `pcBase = resetVector & ~(2^w−1)`.
  Saves ≈ `20·(32−w)` carried PC flops per core (e.g. −336 at w=16, −420 at w=12) and is plausibly
  fmax-helpful (a narrower `pcReg→pcReg` loop). In-region behaviour is bit-identical (RVLS-gated);
  a control transfer leaving `[pcBase, pcBase+2^w)` **wraps within the region** — a documented
  memory-map contract, so `w` must hold the whole code region with one bit of headroom. The PC
  width is split from XLEN precisely because the LSU data bus spans the full 32-bit SoC map and
  must stay XLEN-wide; the accessors `fetchPcW`, `pcBase`, `resetPcOffset` feed
  [`Global`](../../src/riscq/riscv/Global.scala) / `Global.fullPc`.

## Timing-closure levers

These default to the settings that close timing on the target floorplan; set any to off (`0`/
`false`) for the simpler pre-optimization baseline. **Only `aluFastAddOnly` costs IPC**; the rest
are bit-exact / no-IPC. The two binders they attack are route-dominated (see
[RISCV.md](RISCV.md) gotchas), so these are placement/routing levers, not pipeline-depth ones.

| lever | default | what it does / costs |
|---|---|---|
| `aluOperandMaxFanout` | 16 | `max_fanout=N` on the executeAt ALU operand regs so Vivado replicates them; each consumer routes from a near copy. Attribute-only, bit-exact. |
| `aluFastAddOnly` | true | forward the shallow `ALU_FAST` tap only for the ADD-select class (ADD/ADDI/SUB/LUI/AUIPC), dropping SLT/XOR/OR/AND from the fast mux — removes a result-mux level from the loop. **The one IPC cost**: back-to-back dependent SLT/bitwise pairs take the existing 1-cycle interlock. RVLS bit-exact. |
| `lateWriteback` | true | move the final rd-write 2:1 mux from executeAt to regWriteAt, off the `SRC→RD_DATA` path. No added depth, no IPC cost. |
| `pcRegMaxFanout` | 16 | `max_fanout=N` on the architectural PC register so the route-bound fetch self-loop replicates pcReg. Bit-exact. |
| `pcOneHotRedirect` | true | select the next PC with a balanced one-hot mux (`OHMasking.firstV2` + `OhMux.or`) instead of a serial priority `when`-chain — removes the `IS_JALR→pcReg` redirect binder; helps packed multi-core density. Bit-exact. |
| `btbPredictLate` | false | take the BTB tag-compare + target mux out of the `pcReg→pcReg` self-loop, issuing the target as a registered 1-cycle-late fetch redirect. IPC cost: a correctly-predicted taken branch goes 0→1 bubble. Off = the 0-bubble baseline. |
| `lateBadTarget` | false | run the 32-bit target compare at jumpAt instead of chained after the executeAt JALR adder. Off = baseline. |
| `aluNoFastForward` | false | drop the srcA fast-forward entirely and interlock every 1-ahead RAW hazard, taking the result-mux→RD_DATA→forward loop fully off the path. IPC cost: a 1-cycle bubble on any 1-ahead operand dependency. |
| `aluResultOneHot` | false | build the ALU result mux as a balanced one-hot masked-OR cone. Zero IPC, bit-exact; placement-sensitive — earns its keep on a packed/congested floorplan but can regress a clean region. |
| `csrCommitMaxFanout` | 0 | `MAX_FANOUT=N` on CsrPlugin's CSR write qualifier (`writeEn`), the root of the per-CSR clock-enable decode. 0 = off. Attribute-only, bit-exact. |

(See [SrcPlugin](SrcPlugin.md), [IntAluPlugin](IntAluPlugin.md), [RegFilePlugin](RegFilePlugin.md),
[PcPlugin](PcPlugin.md), [BranchPlugin](BranchPlugin.md), [WriteBackPlugin](WriteBackPlugin.md),
[CsrPlugin](CsrPlugin.md) for each lever's mechanism.)

## Pipeline back-pressure cuts

- **`skidAfterOverride`** (default `None` → `skidAfter = Seq(fetchLatency)`, i.e. `Seq(1)` = the
  fetchData→decode boundary) — each index `i` replaces the
  plain `StageLink` at the `ctrl(i)→ctrl(i+1)` boundary with a **skid buffer** (StageLink +
  CtrlLink + S2MLink). The S2MLink registers the upstream `ready` so an execute-stage halt/flush
  stops rippling combinationally back to the fetch fork. No steady-state cost (transparent when
  empty); wrong-path instructions in a skid are cancelled by `PipelinePlugin.throwSkidsBefore`.
  Empty = no skids. See [pipeline.md](pipeline.md).

## Memory image

- **`memWords`** (default `1<<14`) — sizes the `SparseMemory` image the testbenches preload (the
  core fetches/accesses it over Tilelink). A sim-harness knob, not core hardware.
- **`xlen`** (default 32) / **`resetVector`** (default `0x8000_0000`) — the base ISA width and the
  reset PC.

## How `plugins()` is assembled

The builders nest, so each adds exactly one layer:

- `fetchPlugins()` — `RiscqPlugin` (publishes the `Global` constants) + [`PipelinePlugin`](pipeline.md)
  + [`PcPlugin`](PcPlugin.md) + [`FetchPlugin`](FetchPlugin.md). The minimal fetch sub-pipeline the
  FetchSim exercises.
- `decodePlugins()` — `fetchPlugins()` + [`DecoderPlugin`](DecoderPlugin.md). The decode unit test
  (no execute ⇒ no redirect).
- `pluginsNoPredict()` — `decodePlugins()` + the full back end ([RegFile](RegFilePlugin.md),
  [Src](SrcPlugin.md), [IntAlu](IntAluPlugin.md), [Branch](BranchPlugin.md), [Lsu](LsuPlugin.md),
  [Csr](CsrPlugin.md), [WriteBack](WriteBackPlugin.md), + [Mul](MulPlugin.md) when `withMul`). No
  fetch-stage predictor — correctness is identical (the execute corrector is authoritative), so
  this is the A/B reference the branch-IPC sim compares against.
- `pluginsBtbOnly()` — `pluginsNoPredict()` + [`BtbPlugin`](BtbPlugin.md). A conditional BTB hit
  then predicts always-taken; the A/B reference showing GShare's direction prediction.
- `plugins()` — `pluginsBtbOnly()` + [`GSharePlugin`](GSharePlugin.md). The whole core.

## Related

- [PipelinePlugin](pipeline.md) · [FetchPlugin](FetchPlugin.md) · [PcPlugin](PcPlugin.md) ·
  [RISCV.md overview](RISCV.md)
