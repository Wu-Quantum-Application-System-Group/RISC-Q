# riscq vs RISC-Q BenchMinimal: architecture comparison

Detailed record of the investigation into why the original RISC-Q reference design's
`riscq.soc.BenchMinimal` closes **652 MHz** post-route while riscq's default at the time closed
**~507 MHz** on the same `xczu49dr-ffvf1760-2-e` part and the same `Bench`/Vivado flow — and which
differences are real, transferable levers vs. feature omissions.

**Headline: the 652 is not a feature-parity number.** BenchMinimal's core has **no shifter at
all** (no SLL/SRL/SRA — the plugin list simply omits `BarrelShifterPlugin`), no CSR, and no
predictor. Adding RISC-Q's own shifter to it drops it to **570 MHz**, and its worst path becomes
*exactly* riscq's #1 binder loop. Interlocking the 1-ahead RAW hazard instead of live-forwarding
(which RISC-Q's *full* config does) recovers it to **666 MHz**. riscq's ~507 wall is the same
loop, which RISC-Q avoids either by feature omission (minimal) or by paying an interlock bubble
(full).

## 1. Method & tools

- Source-level plugin-by-plugin comparison of both cores.
- RISC-Q side: BenchMinimal's exact plugin list, varied on a shifter (add
  `BarrelShifterPlugin(shiftAt=0, formatAt=0)`) and on `allowBypassFrom` (0 = live stage-0 bypass,
  BenchMinimal's setting; 1 = registered-only bypass + `HazardPlugin` interlock, the *full*
  `RiscqParams` setting), each through the stock `Bench`+`VivadoFlow` post-route run (1000 MHz
  target); plus a port of riscq's `bench.PathAnalysis` (PathTracer logic-level ranking + collapsed
  chains), so pre-synth cones compare 1:1 across the two cores.
- riscq side: a `VivadoRouteBench` default run (reproduces 507 exactly), `PathAnalysis` runs,
  and one RTL probe (§5).

Both flows are deterministic (verified again: the baseline re-run reproduced 652 MHz / 789 LUT /
706 FF bit-for-bit, riscq's default re-run reproduced 507 / WNS −0.972), so cross-variant deltas
are real hardware differences.

## 2. Measured results

| Config | post-route fmax | LUT/FF | worst path |
|---|---|---|---|
| RISC-Q minimal as benched (no shifts, live bypass) | **652** | 789/706 | `RS2_PHYS addr reg → RAMD32 read + bypass + src-select → SRC2 reg` (1.48 ns, 5 lvl, 70 % route) |
| RISC-Q minimal + barrel shifter, live bypass (`bp0`) | **570** | 901/710 | `SRC1_reg[31] → shifter → format/bypass mux → SRC1/SRC2 regs` (1.68 ns, 65 % route) — riscq's #1 loop shape; SRC1[31] is the SRA sign bit |
| RISC-Q minimal + shifter, `allowBypassFrom=1` (registered-only bypass + interlock, `bp1`) | **666** | 821/720 | `RS2_PHYS → BYPASSED_AT/ctrl CE` — the hazard/stall network; the datapath is **off** the critical path |
| riscq default (fresh re-run, same session) | **507** | — | #1 `SRC2→RS1_DATA` (srcA loop, 7 lvl, 65 % route); #2 `RS1_DATA→MISPREDICT` (JALR add+compare, CARRY8=3, 515); #3/#4 `GShare history→pcReg/NEXT_PC_PRED` (256:1 counter mux, MUXF7/F8, 516/518); then more srcA pairs, `BtbPlugin tags→pcReg` (524), writeback `SRC2→RD_DATA` (524), `Fetch_WORD→pcReg` (529) |
| riscq probe: srcA gate `down.isFiring`→`isValid` (§5) | 499 | — | top-10 = srcA **data** loop only (`SRC→ALU/shifter→RD_DATA→fwd→select→SRC`, ~2.0 ns, ~68 % route); MISPREDICT, GShare→pcReg, BTB→pcReg, WORD→pcReg all left the top-15 |

The 570→666 swing (+96) on the *same* feature set is the value of getting the live forward out of
the loop on this part; 652→570 (−82) is what one barrel shifter inside that loop costs.

Pre-synth PathTracer depths (logic levels, matched endpoints):

| endpoint | riscq default | RISC-Q minimal |
|---|---|---|
| pcReg / PC state | **14** | **5** |
| SRC1/SRC2 regs | **13** (8 with §5 probe) | 9 |
| RS1/RS2_DATA regs | 13 (8 with probe) | — (same cone as SRC) |
| MISPREDICT reg | 11 | n/a (no predictor ⇒ no compare; redirect = registered `COND`) |
| writeback RD_DATA / DATA reg | 8 | 6 |
| fetch reserveId (worst control cone) | 15 | 11 |

In *both* cores the deepest pre-synth cones into the SRC registers are **enable/control chains**,
not data (PathTracer counts the register-enable cone); but riscq's are 4–9 levels deeper because
its control spine serializes more (§4.3) and its PC loop carries the predictor read (§4.4).

## 3. Pipeline maps

**RISC-Q minimal** (3 sub-chains + skid; `executeAt=0`, read at −1, write at 2):

```
fetch0  PC inject (PcPlugin), FetchCachelessPlugin fork (2 ids)
fetch1  join: per-id reorder buffer + same-cycle rsp bypass
decode0 DecoderPlugin (decode registered into execute via DecoderService specs)
skid    S2MLink + CtrlLink, throwWhen(usingReady=false) on flush  ← RISC-Q's skid location
pre_execute_1 (execute −1)
        RegReadPlugin: async RAMD32 read; bypass mux over DATA@0 (live!), DATA@1, DATA@2, read;
          youngest source gets a dedicated final 2:1 (`when(sel.lsb)`), KeepAttribute(tmp)
        HazardPlugin: haltWhen(rs hazard not yet bypassable | flush hazard)  ← load-use interlock
        SrcPlugin.src (relaxedRs): SRC1/SRC2 source-select muxes  → registered into execute0
execute0  ADD_SUB = SRC1 ± SRC2; LESS derived from ADD_SUB.msb (no separate comparators);
          IntAlu 3-way result mux (ADD_SUB / SLT / BITWISE); IntFormat → WriteBack DATA broadcast
          (one-hot OR-merge of ports); Branch: PC_TRUE/PC_FALSE (KeepAttribute), EQ, COND;
          LSU fork (fire-and-forget stores)
execute1  Branch jumpLogic: doIt = isValid && SEL && COND  (all registered; pcPort + flushPort);
          LSU load join (pipelined — execute0 is NOT halted)
execute2  LSU wb port OR-merged into DATA; regfile write
```

PcPlugin: `state` reg; `self.pc = state + 4·increment` where `increment` is a **registered** bit;
priority OH-mux over jump ports; trap-hold is `RegNext`'d with a combinational `haltWhen`
correction at fetch0. **Nothing else is in the PC loop** (5 levels).

**riscq default** (one chain ctrl0–5): ctrl0 pcAt (PC advance with **BTB tag compare + GShare
256:1 counter read folded in**), ctrl1 fetchDataAt (join + reorder buffer), skid1, ctrl2 decodeAt
(`DecodingSpec` payloads + BRANCH_TARGET precompute), ctrl3 regReadAt (read + 2-deep forward
srcA/srcB + src-select), ctrl4 executeAt (shared add + **separate LESS/EQ comparators** + 6-way
ALU mux **incl. barrel shifter** + RD_DATA as 3 chained 2:1 muxes (CSR→load→jump→ALU) + branch
resolve incl. `NEXT_PC_PRED` compares + LSU (halts per access) + CSR), ctrl5 regWriteAt=jumpAt
(registered write + redirect/flush).

## 4. The differences that matter (and the ones that don't)

### 4.1 Content of the live-forward loop — THE gap (measured)

Both cores live-forward the 1-ahead producer's *combinational* execute result into the operand
select one stage upstream (riscq srcA ≡ RISC-Q `DATA@0` bypass). What differs is **what sits
between the SRC register and the forward tap**:

- RISC-Q minimal: `ADD_SUB → 3-way mux → OHMux-or of 2 ports` — and **no shifter exists**.
- riscq: `ADD_SUB/shifter(5 lvl)/logic → 6-way mux → isCsr?(isLoad?(isJump?(...)))` 3 more levels.

The A/B proves the loop content is the lever: +shifter = −82 MHz even on RISC-Q. And RISC-Q's own
full config (`riscq.scala: RiscqParams.getPlugins`, which *does* include the shifter) sets
`WriteBackPlugin(allowBypassFrom = 1)` — i.e. **RISC-Q itself refuses to live-forward past a
shifter** and takes the interlock bubble instead (an unconditional version of that interlock costs
~18.6 % loop IPC).

### 4.2 Forward-select gating: `isValid` vs `down.isFiring`

RISC-Q's bypass enables (`RegReadPlugin.bypassLogic`) are
`node.isValid && node.up(ENABLE) && PHYS === PHYS` — **no firing/cancel/ready terms**. riscq's
`srcA.valid` uses `down.isFiring`, which pulls the LSU-halt + mispredict-cancel + ready spine into
the operand-select mux (the 13-level pre-synth SRC cone). The weaker gate is safe in riscq's
pipeline too:

- a **halted** producer's bogus forward value is never *latched* — the consumer's SRC registers
  only update when regReadAt's down fires, which requires executeAt to accept, i.e. the producer
  to leave;
- a **cancelled** producer (older branch flushing executeAt) implies the consumer at regReadAt is
  inside the same flush range and is being thrown too — forwarding garbage to a doomed
  instruction is harmless;
- a **bubble** at executeAt has `isValid = 0`.

Probe (§5): pre-synth SRC cone 13→8; post-route, MISPREDICT/predictor/pcReg paths leave the
top-15 — but fmax 507→499 because the srcA *data* loop then binds alone. **Conclusion: the gate
fix is necessary but not sufficient; it must ship together with 4.1.**

### 4.3 Flush style: `usingReady=false` everywhere vs riscq's `usingReady=true` correctors

RISC-Q's `PipelinePlugin.flushLogic` throws every ctrl **and** the skid with
`throwWhen(..., usingReady = false)` (cancel/forgetOne-based). riscq's branch/CSR correctors use
`usingReady = true` (only the skids get the `usingReady=false` treatment), so the
mispredict/CSR-redirect broadcast rides the **ready chain** into the fetch enables. That spine is
riscq's deepest pre-synth cone (15 levels):
`MISPREDICT reg → ctrl4 throw → cancel → LSU active → halt → ctrl4 valid → CSR exception →
doRedirect → ctrl1 throw → ctrl0 ready → pcReg/reserveId enable` — and post-route it shows up as
`Fetch_WORD → pcReg` (#13, 81 % route).

### 4.4 PC-loop content

RISC-Q's PC loop is 5 levels (`+4` from a registered increment bit, redirect OH-mux). riscq folds
the **BTB full-tag compare** and the **GShare 256:1 counter mux** into the same single-cycle loop
(14 levels pre-synth; post-route co-binders #3/#4/#8 at ~516–524, MUXF7/F8 = the counter mux).
RISC-Q offers no transferable predictor structure (it has none); the transferable *principle* is
"keep the PC loop minimal".

### 4.5 Smaller structural deltas

- **LESS derived from the subtract**: RISC-Q computes
  `LESS = (SRC1.msb === SRC2.msb) ? ADD_SUB.msb | mux(UNSIGNED, SRC2.msb, SRC1.msb)` — no 32-bit
  comparators. riscq builds two separate `<` carry chains next to the binder (extra SRC fanout).
  (EQ is a separate `===` in both.)
- **One-hot OR result merge**: RISC-Q's `WriteBackPlugin` merges write ports as
  `OHMux.or(hits, payloads)` then `up(DATA) | muxed` per stage (loads join the merge two stages
  later); riscq's RD_DATA is 3 chained 2:1 priority muxes *at executeAt*, with load and CSR data
  inside the executeAt cone.
- **Pipelined LSU** (IPC, not fmax): RISC-Q forks at execute0, joins at execute1, writes at
  execute2; stores are fire-and-forget (`NoRspStore`), loads don't halt execute0 (load-use is a
  HazardPlugin stall). riscq's LSU halts executeAt for every access's full round trip.
- **Fetch**: structurally identical (riscq's fork/join/reorder-buffer/bypass is the same design;
  RISC-Q is the obvious ancestor). Not a differentiator.
- **Skid location**: RISC-Q has it at decode→execute; riscq measured that placement as a clear
  regression (~−33 % loop IPC) — it does not transfer.
- **KeepAttribute usage**: RISC-Q pins the bypass mux output (`"Hurt me no more"`) and
  PC_TRUE/PC_FALSE; riscq pins addSub. Anecdotal, not measured here.

## 5. riscq probe detail (the `isValid` select-gate)

One-line change in `RegFilePlugin`: `srcA.valid = down.isFiring && …` → `isValid && …`.
PathAnalysis: SRC/RS_DATA endpoints 13→8 levels (the control spine left the select).
`VivadoRouteBench` (workspace `bench/VivadoRouteBenchProbe`): **499 MHz** (WNS −1.006) vs 507
baseline; top-10 became exclusively `SRC ↔ SRC/RS_DATA/RD_DATA` (the srcA data loop, ~2.0 ns,
~68 % route, some pairs CARRY8=4 = the full 32-bit add); MISPREDICT (#2), GShare→pcReg (#3/#4),
BTB→pcReg (#8) and WORD→pcReg (#13) all dropped out of the top-15. On its own the gate is only half
a lever (necessary but not sufficient — see §4.2/§6); it pays off paired with the selective forward.

## 6. Levers that close the gap (ranked by value)

The architectural levers the comparison points to, ranked by expected value. The top two
(`selectiveSrcA`, the cached GShare direction bit) have since been implemented and are baked into the
core; they are kept here for the reasoning that motivated them.

1. **`selectiveSrcA` — selective live forward + targeted interlock** (highest value; targets the
   #1 binder cluster). Forward the 1-ahead producer live **only for cheap producers**
   (ADD/SUB/logic/SLT — a dedicated shallow result tap, not the full RD_DATA), and **interlock
   one cycle** at regReadAt when the 1-ahead producer is a shift/load/CSR/jump whose rd matches an
   operand (a class-gated halt). Back-to-back *ALU*-dependent
   pairs — the common loop pattern — stay bubble-free; only shift→use / csr→use / jump→use /
   load→use pairs pay 1 cycle (load-use already effectively pays it via the LSU halt). Ship
   together with the `isValid` select gate (§4.2/§5) — measured individually, neither suffices;
   together they reproduce RISC-Q's `bp1` shape (666 on the parity probe) for the common case.
   Expected riscq ceiling afterwards: the ~516–524 predictor/writeback cluster.
2. **GShare direction bit cached in the BTB entry** (targets #3/#4/#8, ~516–524). At learn time,
   store the *predicted direction* (from the GShare counter) into the BTB entry; the PC loop then
   reads only the 4-entry BTB (the 256:1 counter mux + hash XOR leave the loop). GShare still
   trains its counters at commit; the cached bit refreshes on every learn of that branch. Hint-only
   ⇒ correctness-neutral; accuracy delta = one-execution staleness of the direction.
3. **`usingReady=false` corrector flushes** (RISC-Q's idiom; targets `Fetch_WORD→pcReg` and the
   ready-chain spine). riscq already uses it for skids (`throwSkidsBefore`), so the forgetOne path
   is proven in-repo; needs a careful review of the existing assumptions around `isCancel` consumers
   (LSU wrong-path gate) and the PcPlugin redirect (which writes pcReg directly, independent of
   the ready chain — so the flush need not ride ready).
4. **LESS from the subtract + one-hot RD_DATA merge** (congestion relief around the binder; cheap
   A/Bs). Derive LESS from `ADD_SUB.msb`/sign bits (DO_SUB already subtracts for all compares) and
   flatten RD_DATA into an AND-OR one-hot merge using the already-decoded registered `IS_*`
   payloads. Watch for route re-binding; same-RTL flag A/B each.
5. **Pipelined LSU** (IPC axis, fmax-neutral): adopt RISC-Q's fork@execute / join@+1 / wb@+2 with
   fire-and-forget stores — riscq already owns the pattern in fetch (per-id reorder buffer).
   Removes the per-access execute halt; load-use becomes a 1-bubble interlock (which lever 1's
   interlock already provides).

## 7. Corrected reference framing

The RISC-Q "structural analysis (652 MHz)" reference number should be read with this
caveat: 652 is an **RV32I-minus-shifts** core with no CSR and no predictor, and its clean
`RS_addr → read+fwd+select → SRC` worst path exists *only because* the live-forward loop has no
deep producer in it. Feature-adjusted (with shifter, live forward), RISC-Q lands at **570**;
with shifter + interlock at **666** — bracketing riscq's structural position rather than
dominating it by 145 MHz.
