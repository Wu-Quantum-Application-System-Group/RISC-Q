# DSP48 — DSP48E2 inference & packing notes (Xilinx UltraScale+ / Vivado)

Hard-won, **measured** notes on how Vivado maps SpinalHDL multiply / multiply-add pipelines onto the
**DSP48E2** slice of our target part (`xczu49dr-ffvf1760-2-e`, Virtex UltraScale+ RFSoC), and how to
steer that mapping for fmax. Most of this was learned closing the `riscq.dsp.ComplexMul` /
`PulseGenerator` fmax gap (581 → 707 MHz) — the worked example throughout is that 3-multiplier
complex product. Read this before writing any new DSP-heavy datapath (mixers, FIR/CIC, the demod
path, matrix math).

The one-line takeaway: **a DSP-bound path's speed is decided by the *pipeline structure you feed
Vivado*, not by the arithmetic.** The same multiply-add is a 0.8 ns single-ALU op or a 1.5 ns
two-ALU cascade depending on where its operands are registered. Plan the register placement.

---

## 1. The slice (only the parts that bit us)

A DSP48E2 is a fixed datapath with optional pipeline registers at every stage:

```
 A (30b) ─┬─[A1]─[A2]─┐
          │           ├─ pre-adder (D±A) ─[ADREG] ─┐
 D (27b) ─┴─[DREG]────┘                            ├─ 27×18 mult ─[MREG] ─┐
 B (18b) ───[B1]─[B2]──────────────────────────────┘                     │
                                                                          ├─ ALU ── [PREG] ── P (48b)
 C (48b) ───[CREG] ───────────────────────────────────────── X/Y/Z mux ──┤            │
 PCIN (48b, from prev DSP PCOUT, opt. >>17) ──────────────────────────────┘            └─► PCOUT
```

- **Multiplier** is 27×18 signed. Operands wider than that need multiple DSPs.
- **ALU** computes `P = Z ± (X + Y + CARRYIN)`, where X/Y/Z are muxed from `{M (product), P, PCIN, C,
  A:B, 0}`. The common "multiply-add" is `P = M + C` or `P = M + PCIN`.
- **MREG** (after the multiplier) and **PREG** (after the ALU) are the two registers that matter for
  cascades. **Both are optional**, and *which one Vivado uses as a pipeline-stage boundary* is the
  whole game (§3).
- **PCIN/PCOUT** is a *dedicated, fast* cascade route between vertically-adjacent DSPs (no fabric
  route). It can right-shift PCIN by 17 (for building wide multiplies). It carries the ALU result
  **combinationally** unless PREG is enabled — that is the trap (§3.1).
- **C port** is a normal 48-bit data input with its own register (**CREG**). Feeding an addend through
  C (a *settled register*) is the cheap alternative to feeding it through a *live* PCIN cascade.
- **Pattern detector** can do saturation/round/overflow detection in-DSP, but Vivado mostly leaves
  our `±Amax` clamps in fabric (§4).

### Reading DSP paths in `report_timing`

The cell/pin names tell you exactly what Vivado built. From our reports:

| Netlist node | What it is |
|---|---|
| `…/DSP_A_B_DATA_INST/A[i]` | the A (or B) input register feeding the multiplier; `A[i]` fans out internally to the partial-product rows |
| `…/DSP_M_DATA_INST` | **MREG** — the registered multiplier output |
| `…/DSP_C_DATA_INST` | **CREG** — the registered C-port addend (a *settled* operand — good) |
| `…/DSP_OUTPUT_INST/ALU_OUT[i]` | the ALU output (→ PREG / PCOUT) |
| `Logic Levels: … DSP_ALU=N` | **N chained ALU ops between two registers** — `N=1` good, `N≥2` is a cascade wall |

**`DSP_ALU=2` is the red flag.** It means two DSP add/accumulate ops sit between the source and
destination registers with only the dedicated PCOUT→PCIN route between them — ~1.5 ns of pure DSP
logic (≈ 0.7 ns/ALU + the cascade). A high `ALU_OUT[40]`-style index on a ≤36-bit accumulator means
Vivado used the **>>17 shifted** PCIN cascade and put accumulator high bits on the path.

---

## 2. How SpinalHDL pipeline stages become DSP registers

With the pipeline API (`Node` / `Payload` / `StageLink`) you declare *logical* stages; Vivado then
**retimes and packs** combinational logic and its registers into DSPs as it sees fit. Two rules we
verified:

1. **A payload defined at stage `n` and read at stage `n+k` is auto-registered through the
   intermediate stages.** Reading it `k ≥ 2` stages later makes it a *settled* register at the
   consumer; reading it the *very next* stage (`k = 1`) makes it a *fresh* value Vivado will try to
   cascade live.
2. **A multiply and the add that consumes its result, in adjacent stages, get fused into a DSP** —
   and Vivado decides whether the boundary register is the MREG (→ live cascade) or the PREG (→ clean
   register). It prefers the *cheaper-area* live cascade unless something forces the PREG (§3.2).

So "where is each operand registered, relative to the op that uses it" is the design variable. You
control it by *which stage you compute things in*, not by adding `Reg()` by hand.

---

## 3. The cascade trap and the fix

### 3.1 What went wrong — live PCIN cascade = two ALU ops

`ComplexMul` (3-mult Karatsuba) shares one term `M = (ar−ai)·bi` between both outputs:
`SRE = PRE + M`, `SIM = PIM + M`. Originally `M`, `PRE`, `PIM` were **all computed in the same stage**
and `M` was consumed by the recombination **in the next stage**. Vivado therefore:

- put `M`'s multiply in one DSP using only its **MREG** (no PREG),
- routed that DSP's ALU output **combinationally** via PCOUT → PCIN into the recombination DSP,
- which did `PIM + M` in *its* ALU and registered at *its* PREG.

Result: `MREG → ALU(M) → PCOUT → PCIN → ALU(PIM+M) → PREG` = **`DSP_ALU=2`, ~1.53 ns**, the post-route
binder in every carrier/envelope mul (~638 MHz wall). It also costs a **4th DSP per mul** (129 total)
because `M` is duplicated into both output cascades.

### 3.2 The fix — stagger the shared term so it arrives via a settled C port

Compute `M` **one pipeline stage ahead** of `PRE`/`PIM`:

```
n2: M = DA·bi            // one stage early — held by the pipeline
n3: PRE = DB·ar, PIM = SB·ai
n4: SRE = PRE + M + ½ulp, SIM = PIM + M + ½ulp   // M is now a SETTLED register → DSP C port
```

Now at the recombination `M` is a registered value fed through the **C port (CREG)**, not a live
PCIN cascade off its own multiply. The recombination is **one ALU op deep** (`DSP_ALU=1`). Measured:
**638 → 669 MHz**, and Vivado no longer needs the extra DSP, so every mul drops to **3 DSP (129 →
97)** — the predicted 3-logical-multiply count. Cost: **+1 latency cycle** (5 → 6). This is exactly
the structure the RISC-Q reference uses (it computes `COMMON` early and holds it to `COMMONR`).

**Rule of thumb:** any term *shared* across DSP ops, or any addend you want via the C port, must be
**produced ≥ 1 stage before** the op that consumes it. Do not compute a shared multiply in the same
stage it is first used.

### 3.3 Use the C *register* (CREG), not just the C port — copy a shared addend per consumer

Staggering `M` (§3.2) keeps the recombination one ALU op deep, but it does **not** by itself register
`M` *at* the consuming DSP. With `SRE = PRE + M`, `SIM = PIM + M` reading the **same** `M` payload,
Vivado held `M` in its own DSP's `PREG` and routed that output **combinationally** into both
recombination ALUs — `M_reg/DSP_OUTPUT → route(fo≈36, ~0.5 ns) → DSP_C_DATA → ALU` in one cycle (the
`DSP_C_DATA` C-port buffer is **un-registered** on this path). Once the fabric pre-adds were gone
(§3.1/§6), *this* became the binder (~1.41–1.45 ns).

The fix is to give each output its **own copy register** of the shared term, one stage before the
recombination — exactly the RISC-Q reference's `COMMONR := COMMON; COMMONI := COMMON`:

```
n4: PRE = DB·ar, PIM = SB·ai;  MCR = M, MCI = M   // two copies, one per output
n5: SRE = PRE + MCR + ½ulp,    SIM = PIM + MCI + ½ulp
```

Each copy is a leaf register feeding only one ALU, so Vivado maps it to that DSP's **CREG**. `M` now
reaches the ALU as a *settled C register* (a reg-to-reg route into CREG, then a DSP-internal `MREG +
CREG` add), and `M`'s output net fans out to just the **two** copies instead of fo≈36. This splits the
combinational route-through into two short paths and moves the binder **off the DSP datapath entirely**
(onto the scheduler's `TimedQueue` 32-bit compare). Cost: the copies are *free* — they land in the
otherwise-idle CREG, not fabric FF.

**Rule:** a C-port addend shared by N DSP ops should be copied into N dedicated one-stage-ahead
registers (one per consumer), so each maps to its DSP's CREG and the producer's fanout stays at N.

### 3.4 Register the DSP output when fabric logic sits on it — even just a saturation clamp

The §4 register-barrier effect, stated as a rule: if the value leaving a DSP passes through fabric
logic (a `±Amax` clamp, a mux) before the next register, Vivado may **over-fold** the cascade (extend
`MREG → ALU → output` combinationally into the downstream register) and blow up FF/route. Keep a
**registered barrier on the DSP output**. In `ComplexMul` the no-sat output is a bare bit-slice (free,
left combinational — the recombination `PREG` drives the next stage directly, like RISC-Q); the
saturating output has a real clamp, so it gets **one extra register stage** (`latency` 6 → 7). Without
that register the saturating build over-folded: **FF 8587 → 10432, fmax 670 MHz**; with it, **FF
10121, 720 MHz** and the binder leaves `ComplexMul`. (No-sat is unaffected: combinational slice, 727 MHz.)

---

## 4. Removing logic can make a DSP path *slower* (the register-barrier effect)

Counter-intuitive but measured. `ComplexMul`'s output stage had a fabric `±Amax` **saturation clamp**
reading the recombination result. That clamp acted as a **register barrier**: because fabric logic
consumed the recombination value, Vivado was forced to land it in a clean PREG, *capping* the DSP
cascade.

When we removed the saturation (the `saturate = false` simple mode, see [PulseGenerator.md](PulseGenerator.md))
the output became a bare bit-slice — *no fabric consumer* — so Vivado **over-folded**: it
extended the DSP cascade further (an extra `DSP_C_DATA` level, reaching `ALU_OUT[40]` via the >>17
shifted PCIN), and fmax **regressed 638 → 478 MHz** even though the design got *smaller* (−38 % LUT).
The M-staggering (§3.2) fixed it (478 → **707 MHz**, the fastest config).

**Lesson:** deleting fabric logic on a DSP output is not free for timing — you may remove the barrier
that was keeping a cascade short. Re-bench DSP-bound modules after *removing* logic, not just after
adding it. If you need a barrier without the logic, register the DSP output explicitly into fabric.

---

## 5. The levers (how to steer DSP inference)

| Lever | Effect | Where we used it |
|---|---|---|
| **Stagger shared/added terms ≥1 stage early** | settled C-port instead of live PCIN cascade; `DSP_ALU=2 → 1`; often −1 DSP/op | `ComplexMul` `M` (§3.2) |
| **Place each pre-add exactly one stage before its multiply** | the link reg becomes the DSP **ADREG** ⇒ `(D±A)·B` packs into the internal pre-adder; pre-add leaves fabric (−LUT/CARRY8, −route into operand port) | `ComplexMul` `DA`/`DB`/`SB` (§3.1/§6) |
| **Copy a shared C-port addend per consumer, one stage early** | each copy → that DSP's **CREG** (settled C register, not a combinational route-through); producer fanout = #consumers | `ComplexMul` `MCR`/`MCI` (§3.3) |
| **Keep a fabric consumer / register on the DSP output** | register barrier caps the cascade; *register the output* if the only consumer is a clamp/slice | `ComplexMul` clamp, registered for sat (§3.4/§4) |
| **`signal.addAttribute("use_dsp", "no")`** | force a multiply into fabric (LUT/CARRY8) — 0 DSP | CORDIC `1/K` KCM (fabric shift-add) |
| **`signal.addAttribute("use_dsp", "yes")` on the PRODUCTS** | pin a multiply into a DSP48 — overrides the global DSP balancer, which silently demotes muls to fabric when device DSP demand nears ~65% (the 14q SoC's 2786 sat on that knife-edge; 18 decoder muls spilled to CARRY8 chains and the build collapsed). Scope it to the product signals only — module-level `use_dsp` also drags pre-adds/recombines into standalone DSPs (+425, NOFIT) | `ComplexMul` `mulM`/`mulPre`/`mulPim` |
| **`signal.addAttribute("max_fanout", n)`** | replicate a high-fanout *fabric* driver next to its loads | clamp-select net (fo=16, route-bound) — see §6 |
| **Fold a round constant into an existing adder** | the `+½ ulp` rides a real adder, no extra stage/logic | CORDIC last micro-rotation |
| **Add a pipeline stage** | break any over-long path; latency is auto-derived in our blocks | ComplexMul 5→6 |

Latency changes are cheap in this codebase: every block **exports** its latency and the toplevel
**derives** all lead times from those exports (no literals), and the end-to-end TB re-verifies them —
so a `+1` ripples automatically. Bit-exactness is independent of pipeline depth, so restructuring is
safe as long as the arithmetic is unchanged (the golden model never changes).

---

## 6. Fabric, not DSP, but on the DSP boundary

Two fabric effects sit right at the DSP edges and showed up as binders once the DSP cascade was fixed:

- **High-fanout select on a DSP output.** The `±Amax` clamp's overflow flag fanned to all `w` output
  bits (fo=16) with ~0.6 ns of routing. Testing both rails as *parallel* flags and capping their
  `max_fanout` so Vivado replicates the select near the muxes recovered it (616 → 638 MHz). Vendor
  attributes are used **only with a post-route measurement behind them** — `max_fanout` here is
  timing-evidenced.
- **Fabric pre-adder feeding the DSP A/B port → the DSP48E2 internal pre-adder.** The Karatsuba
  pre-adds (`br − bi`, …) were done in fabric (CARRY8) and routed into the DSP operand register; after
  the cascade was broken this became the binder (`stages_1_BI_reg → CARRY8×2 → DSP A[17]`, fo≈14,
  ~0.45 ns route). The fix is to place **each pre-add exactly one stage before its multiply** so the
  intervening pipeline-link register becomes the DSP's **ADREG** and Vivado packs `(D±A)·B` into the
  slice's *internal* pre-adder. The pre-add then leaves fabric entirely — both the CARRY8 and its route
  into the operand port vanish. Measured on `ComplexMul`: moving `DB`/`SB` from two stages early to one
  stage early dropped **LUT 5903 → 4838 (sat) / 4032 → 3001 (no-sat, −26 %)** and cut ~190 CARRY8.
  Rule: a pre-add two stages before its multiply is stranded in fabric; one stage before is absorbed.

---

## 7. Checklist for a new DSP-heavy module

1. **Map the multiplies.** 27×18 each; wider operands cost multiple DSPs. Karatsuba/Strassen-style
   sharing reduces *logical* mults but watch the shared term (§3).
2. **Decide register placement up front.** Any operand added/cascaded must be a *settled* register at
   its consumer ⇒ produce it ≥1 stage early. Don't first-use a multiply in the stage it's computed.
3. **Keep one fabric consumer (or explicit reg) on each DSP output** you care about, so cascades stay
   capped (§4) — especially if you later strip rounding/saturation.
4. **Bench, then read the resource nodes** (§1). `DSP_ALU=2` ⇒ a live cascade to break; a high
   `ALU_OUT[]` index ⇒ a shifted PCIN cascade; `DSP_C_DATA` on the path ⇒ a settled addend (good).
5. **Re-bench after removing logic, not just after adding it** (§4).
6. **Use `riscq.dsp.pulse.bench.VivadoRouteBench`** (post-route, real DSP packing) — OOC *synth*
   (`VivadoBench`) under-reports DSP-internal delay. Both are deterministic, so deltas are real.

---

## 8. Measured reference points (this part, OOC `-2`, 1 GHz target)

| Structure | path | delay | fmax |
|---|---|---|---|
| `ComplexMul` recombination, live PCIN `M` cascade | `M_DATA → DSP_OUTPUT`, `DSP_ALU=2` | ~1.53 ns | ~638 MHz |
| same, **no fabric barrier** (over-folded) | `M_DATA → DSP_OUTPUT`, `DSP_ALU=2`+`DSP_C_DATA`, `ALU_OUT[40]` | ~2.05 ns | ~478 MHz |
| `ComplexMul` recombination, **M staggered** (`DSP_ALU=1`, but C-port un-registered) | `DSP_ALU=1` | ≤ ~0.9 ns | not yet limiting |
| binder with fabric pre-adds: fabric pre-add → DSP A/B port | `CARRY8×2–3 → DSP_A_B_DATA/A[i]` (fo≈14 route) | ~1.41–1.49 ns | 669 / 707 MHz |
| binder after pre-adder packed (§3.1/§6): `M` PREG routed combinationally to recombine C | `M_reg/DSP_OUTPUT → route(fo≈36) → DSP_C_DATA → ALU` | ~1.41–1.45 ns | 670 / 690 MHz |
| after `M` copied per output → CREG (§3.3) + sat-output registered (§3.4) | binder leaves the DSP datapath (→ `TimedQueue` 32-bit compare) | n/a | **720 (sat) / 727 (no-sat) MHz** |

Rough rules: one DSP ALU op ≈ **0.8 ns**; a two-ALU PCIN cascade ≈ **1.5 ns**; the dedicated
PCOUT→PCIN route is ~free, fabric→DSP-port routes are not; an un-registered C-port route-through (DSP
output → fabric route → `DSP_C_DATA` → ALU) is ~1.4 ns — use the **CREG** (a copy register per consumer)
to break it.
