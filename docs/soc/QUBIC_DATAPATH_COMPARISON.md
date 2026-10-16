# Pulse-generator → DAC datapath: QubiC `zcu216_14_2` vs. our `PulseTableSoc`

A side-by-side analysis of the **pulse-generator-to-DAC datapath** in the QubiC gateware
(LBNL's `gateware` repository, top config `top/zcu216_14_2`) and our `PulseTableSoc`
(`src/riscq/soc/PulseTableSoc.scala`). Both designs solve the same problem with the same overall
shape; this note records exactly where they line up and the few places they diverge — useful when
reasoning about resource cost (DSP/BRAM), phase coherence, and the DAC output mapping.

Sources read for this comparison:

- QubiC: `gateware/dsp/{element.sv, ammod.v, phtime.v, xmultadd.sv, dsp.sv}`,
  `gateware/top/zcu216_14_2/dsp_config.yaml`, and the *generated* RTL under
  `top/zcu216_14_2/build_*/gensrc/{dsp_cores.sv, qubit_core.sv}`.
- Ours: `src/riscq/soc/{PulseTableSoc.scala, RiscqRfWithPulseTableFiber.scala}`,
  `src/riscq/dsp/pulse/{PulseGenerator.scala, CarrierBatchGenerator.scala, PhasorBatchGenerator.scala,
  EnvelopeReader.scala}` — see also [`docs/dsp/PulseGenerator.md`](../dsp/PulseGenerator.md).

> Note on terminology: this project's *primary* reference is **RISC-Q** (a RISC-V + qubit-control
> design). **QubiC** is the lower-level LBNL gateware that the signal datapath ultimately descends
> from; the readout-drive envelope interpolation (`readoutInterp`/`gateInterp` in `PulseTableSoc`)
> already borrows its `INTPRATIO` idea. This note compares directly against QubiC's `ammod` element.

---

## 1. The shared shape (what is the same)

Per dsp-clock both designs emit a **batch of 16 complex samples × 16-bit**, route the **real lane**
to a DAC, and **sum** every logical channel mapped to the same physical DAC. Internally both are:

```
              ┌─ base CORDIC ─┐   ┌─ × per-lane phasor ─┐   ┌─ × envelope ─┐   ┌ real lane ┐
  freq,phase ─┤ amp·exp(iφ_t) ├─→ │ 16× complex-mul     │ → │ 16× cmplx-mul│ → │  → DAC     │ → Σ per DAC
   amp ───────┘               │   └─────────────────────┘   └──────────────┘   └───────────┘
```

So both share: 16-lane SIMD batch (`dac_samples_per_clk = 16` ↔ `N = 16`); **one** base-phase CORDIC
with **amplitude riding the CORDIC x-input** (no separate amplitude multiply); **two complex multiplies
per lane** (× a per-sub-sample phasor, then × the envelope); Q15 renormalisation inside each complex
multiply (`[30:15]` slice); **real lane only** to the DAC (the imag/Q lane is dropped); **wrap, not
saturate**, on the channel sum; and `INTPRATIO`-style envelope compression (one stored complex sample
block-replicated across the batch for low-bandwidth drives).

Our chain is, in effect, a cleaned-up, time-scheduled reimplementation of the QubiC `ammod` element.

---

## 2. Stage-by-stage

| Stage | QubiC (`element` / `ammod`) | Ours (`PulseGenerator` chain) |
|---|---|---|
| **Trigger / scheduling** | Distributed processor (`proc`) runs a pulse program; `pulseout.cstrobe` loads `{amp,freq,phase,env_word,cfg}` into the element; an address counter walks `[envstart, envstart+envlength)`. Fires whenever the proc strobes. | Lead-time `TimedQueue`s: each parameter is queued against global `io.time` and popped **early by its own pipeline latency**, so all factors of a pulse align on the same output sample. Window `[startTime, startTime+dur)` from a `dur` down-counter. |
| **Envelope source** | Env BRAM (32-bit/line = 1 complex sample), block-replicated `INTPRATIO×` per lane: qdrv `INTPRATIO=1` (full res), rdrv `=16` (held across the batch). | `EnvelopeReader` over a host-written BRAM: gate drive interp 4 (128-bit line; the **512-bit** full line at interp 1), readout drive interp 16 (32-bit line replicated). Same idea, ported (per-channel `interp`, `expandEnv`). |
| **Per-lane phasor** `exp(iπ·f·k)` | **Pre-computed in software, stored in the freq BRAM**: each freq entry = `[freq27, phasor₁…phasor₁₅]` (512-bit). Lane 0 = identity (`0x7fff0000`); lanes 1-15 read straight out of BRAM (`ammod.v`). | **Computed on-chip** by `PhasorBatchGenerator` (rotation-mode CORDIC, **0 DSP, 0 BRAM**), regenerated when freq changes. |
| **Base / time phase** | `phtime`: `phasetime = freq·tcnt` (27×27 mult); `phaseinit = phasetime[26:10] + pini`. Free-running `tcnt`. | `CarrierBatchGenerator`: `gPhase = freq·(N·t) + phase`, recomputed from absolute `time` every cycle, truncated to SF(w). |
| **Base CORDIC** | `cordicg`: `(cos,sin) = ampx·exp(j·phaseinit)`. | `Cordic`: `amp·exp(iπ·gPhase)`; gain stage dropped when `prescaleAmp`. |
| **Mult 1 (× phasor)** | `mult1`: 16× carrier × `(cosp[i],sinp[i])`, `cmultiplier` (Karatsuba 3-DSP). | `CarrierBatchGenerator`: 16× `ComplexMul` carrier × `phasor[k]`. |
| **Mult 2 (× envelope)** | `mult2`: 16× result × `(envx,envy)`. | `PulseGenerator.envMuls`: 16× `ComplexMul` carrier × env; `realOutput=true` prunes the imag-output mult (3→2 DSP/lane). |
| **Real lane out** | `elementout.multix` → `qdrv_out` (Q lane `multiy` discarded). | `pulse(k).re`; `pulse.im := 0` under `realOutput`. |
| **Per-DAC summation** | Hard-wired by `dsp_config.yaml` → generated `dsp_cores.sv`: `dac[i]=qubit_qdrv[i]+drive_qdrv[i]`, `dac[3+i]=coupler_cdrv[i]+coupler_dc[i]`, `dac[7]=slicesum(rdrv×3)`. Optional `xmultadd` **NDAC×NDAC complex mixing matrix** (not used here). | Generic `dacMap` + `AdderTree` over the real lanes of co-mapped channels; wrap mod 2^w. `converterPipe` register barrier into the RFDC edge. No mixing matrix. |

---

## 3. The differences that actually matter

1. **Phasor batch — stored vs. generated.** QubiC pre-computes the 15 intra-batch sub-phasors per
   frequency in software and spends a wide **freq-BRAM line** to hold them; the FPGA only computes the
   time-varying base phase. We regenerate the whole phasor batch **on-chip** with a CORDIC, so we spend
   logic/latency but **no phasor BRAM and no software phasor tables**. This is the central structural
   divergence and the main DSP/BRAM trade-off between the two.

2. **Scheduling model — processor-driven vs. time-queued.** QubiC strobes each element from an
   instruction stream and lets the env address-counter set the pulse length. We push parameters with a
   `startTime` and pop them by **lead-time** so every factor aligns at the DAC automatically, with the
   window from a `dur` counter. Same per-sample math, different control philosophy.

3. **Phase accumulation — incremental vs. absolute.** QubiC accumulates phase from a free-running
   `tcnt` (`phasetime = freq·tcnt`). We recompute `gPhase = freq·(N·t) + phase` from **absolute time**
   every cycle and truncate to SF(w): because `(a·(b mod 2^w)) mod 2^w = a·b mod 2^w` and one wrap is a
   whole number of turns, the phase is **exact mod 2π** — pulses stay phase-coherent across arbitrary
   time gaps and channels without a maintained accumulator.

4. **Output mapping richness.** QubiC carries a general **complex DAC×DAC mixing matrix** (`xmultadd`,
   `coef[i][j]`) and a per-channel **DC-offset** path (`coupler.dc`); the DAC sum is whatever the YAML
   `dac_outputs` block specifies. Ours is a plain additive `dacMap`/`AdderTree` — no mixing matrix, no
   DC path — plus the `converterPipe` boundary registers.

5. **Heterogeneous vs. homogeneous cores.** The `zcu216_14_2` config is **not** 14 identical qubits: it
   is **3 qubit + 3 drive + 4 coupler** cores (`dsp_config.yaml`) driving **8 DACs**, each core type
   carrying a different mix of sig-gens (`qdrv`/`rdrv`/`rdlo`, `cdrv`/`dc`). Our `PulseTableSoc` is
   **14 homogeneous qubit cores**, each gate-drive + readout-drive + demod + decoder, mapped one
   gate-drive channel per DAC plus a shared readout DAC (`SocChannelMap`).

---

## 4. Cost implication (qualitative)

Per drive both spend roughly **two complex multiplies per lane** after the base CORDIC (~16×3 + 16×2
DSP with `realOutput`, plus 1 DSP for the time-phase product), so DSP usage is the same order. The BRAM
picture differs: QubiC's freq memory is **wide** (it holds the per-frequency phasor tables), whereas
our phasor batch is **computed** (0 BRAM) and only the envelope memory consumes BRAM. That matters at
14 qubits, where BRAM-tile pressure — not DSP or LUT — is the routability wall; generating the phasor
batch instead of storing it keeps a whole class of wide BRAM banks out of the design.

---

## 5. The QubiC alignment as a drop-in variant

Difference #2 above (scheduling model) is the one place to A/B QubiC's *mechanism* directly, so it
is available as a second generator with the **same datapath and bit-exact output**:

- **`PulseGenerator`** (default) — lead-time scheduling: **one `TimedQueue` per parameter** (amp, phase,
  freq→carrier, freq→phasor, addr, dur), each popped `startTime − leadₓ` so a parameter enters its
  consumer exactly when its own pipeline carries it to the DAC. Six wrap-safe 32-bit registered
  time-compares.
- **`PulseGeneratorAligned`** (`src/riscq/dsp/pulse/PulseGeneratorAligned.scala`) — the QubiC
  `element`/`ammod` alignment: the four co-fired parameters `{amp, phase, addr, dur}` share **one**
  `TimedQueue` (one entry, one `startTime`, one shared pop valid), popped once at the *latest* of their
  lead times `maxLead`. Each is then realigned to its consumer by a static **SRL** of `maxLead − leadₓ`
  (QubiC's `reg_delay1`), and **one shared valid shift register** (QubiC's `busy_sr`) is tapped at those
  depths. `freq` keeps its own two queues (QubiC also routes the frequency word separately). Narrow
  `addr`/`dur` are the delayed side — mirroring QubiC delaying the env **address** (12 bit) rather than
  the 512-bit env data. Selectable from `PulseDriveChannel(useAligned = true)`.

Both pop every consumer at `startTime − leadₓ`, so the carrier/envelope/gate inputs are timing-identical
and the output pulse is **bit-exact** — `PulseGeneratorAlignedSim` reuses the `PulseGeneratorSim` golden
unchanged (all 5 configs, incl. back-to-back and virtual-Z, pass). Only the alignment hardware differs.

**Resource / fmax** (`riscq.dsp.pulse.bench.AlignedComparisonBench`, `xczu49dr-2`, OOC,
N=16 w=16 saturate=true Cordic, queueDepth=4) — synth and post-route (`RISCQ_CMP_ROUTE=true`):

| metric | queued (6 queues) | aligned (1 queue + SRL) | Δ |
|---|--:|--:|--:|
| fmax — synth (MHz) | 666 | 662 | −0.6 % |
| fmax — **post-route** (MHz) | **644** | **622** | **−3.4 %** |
| LUT total — synth | 5337 | 5198 | −2.6 % |
| LUT total — post-route | 4921 | 4771 | −3.0 % |
| LUT as SRL | 1 | 47 | (alignment delay lines) |
| FF | 8913 | 8908 | −0.1 % |
| CARRY8 | 569 | 557 | −2.1 % |
| DSP48E2 | 97 | 97 | 0 |
| BRAM36 | 0 | 0 | 0 |

Takeaway: the QubiC alignment is consistently **~3 % leaner in LUTs/CARRY8** (it trades four registered
wrap-safe 32-bit time compares for distributed SRLs) at **equal FF/DSP/BRAM**. The fmax cost is in the
noise at synth (−0.6 %) and a small but real **−3.4 % post-route** (644→622 MHz) — the wide combined-queue
payload + SRL taps route slightly worse than six narrow independent queues, though both clear the SoC's
~555 MHz target with margin. So for this datapath the scheme is close to a wash: a few percent of LUTs
saved for a few percent of fmax, and the real choice is clarity (per-parameter lead-time intent) vs.
fidelity to the QubiC `busy_sr`/`reg_delay1` structure.
