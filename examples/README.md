# examples — co-simulation notebooks

Runnable notebooks that drive the **Verilated `PulseTableSoc`** under cocotb — the same host control
software (`riscq.lang`/`riscq.run` to compile and run on-core kernels, `riscq.cal`/`riscq.sim` for
the calibrations) that runs on the ZCU216 board, here against a Verilated SoC instead of real
hardware.

Start with [`amplitude_sweep.ipynb`](amplitude_sweep.ipynb) — a minimal drive demo — then the
calibration notebook, which runs against a `TwoLevelModel` with **planted ground truth** instead of
a real qubit (a co-sim analogue of the hardware `Calibration_X6Y3` flow; specs
[`05-simulation`](../specs/software/05-simulation.md),
[`06-calibrations`](../specs/software/06-calibrations.md)).

| Notebook | What it shows |
|---|---|
| [`amplitude_sweep.ipynb`](amplitude_sweep.ipynb) | the smallest end-to-end example — an on-core `@kernel` fires a train of gate pulses with a ramping amplitude; the notebook captures the raw gate **DAC waveform** off the SoC and plots the pulse train, the pulse shape, and the (linear) peak-vs-amplitude. No qubit model — just the drive signal the hardware emits |
| [`single_qubit_calibration.ipynb`](single_qubit_calibration.ipynb) | the `Calibration_X6Y3` flow, one experiment per cell — ReadoutCalibration → Separation → Fidelity → ReadoutFidelity → Frequency → Amplitude (coarse) → Amplitude (fine) → Phase — pulling a deliberately-detuned `Config` back to the planted `f_ge`/Rabi ground truth |
| [`calibration_x6y3_cosim.ipynb`](calibration_x6y3_cosim.ipynb) | the full **qcal-parity** version of the same chain (spec 13): the config of record is a qcal tree ([`calibration_x6y3_cosim.yaml`](calibration_x6y3_cosim.yaml)) round-tripped through `Config.from_qcal`/`save_qcal` every cell, `readout/herald: true` (every counts shot post-selected like qcal's transpiler), **both** cores calibrated simultaneously on the frequency-multiplexed readout, plus the reference notebook's per-qubit Phase loop and absolute pass |
| [`calibration_process_x6y3_cosim.ipynb`](calibration_process_x6y3_cosim.ipynb) | **the full calibration *process*** (spec [software/14](../specs/software/14-full-calibration-x6y3.md)) — the flux-tunable-qubit walkthrough's own five-stage arc, minus everything flux, on the 3-core `sim-2q1c` build: **1 readout** (`Punchout` → `ReadoutCalibration` → `Separation` → `Fidelity` → the three `Window` knobs → the confusion matrix) → **2 frequency** (`Frequency` + the `RPEFrequency` polish) → **3 one-qubit gates** (`Amplitude`, `Phase`, the X180's amplitude and its own axis phase, the `RPEAmplitude`/`RPEPhase` polish, `T1`/`T2`, the EF subspace, the 3-level confusion matrix + `rcorr`, DRAG) → **4 the CZ** (seed, `JAZZ`, the 2D `CZAmpFreqSweep` landscape, then the 1D chain, the `CZRPE` polish and `SpectatorPhase`) → **5 validation** (R at an amplified gate count). Supersedes `calibration_x6y3_full_cosim.ipynb`, which has the same readout/1Q/EF/two-qubit spine but none of the punchout, window-knob, coherence, 3-level-confusion, DRAG, 2D-landscape or RPE stages |
| [`calibration_x6y3_full_cosim.ipynb`](calibration_x6y3_full_cosim.ipynb) | *(superseded by `calibration_process_x6y3_cosim.ipynb` — kept as the spec-04 X5 record)* the **FULL X6Y3 flow** (spec [two-qubit/04](../specs/two-qubit/04-x6y3-fixed-frequency.md) §2) end-to-end on the 3-core `sim-2q1c` build: readout → 1Q GE → the **EF subspace** (3-level `ClassifierN` training, `EFFrequency`, `EFAmplitude` X90 + X, `EFPhase`) → **two-qubit** (the `calc_cz_frequency` drive-form seed, `JAZZ`, then the two-qubit-drive CZ chain — `CZSweep('freq')`, `CZFrequency`, `RelativePhase`, `CZAmplitude`, `LocalPhases`, the 3-core `SpectatorPhase`) → an n-amplified R validation. Every stage a `from_qcal`/`save_qcal` round trip through [`calibration_x6y3_full_cosim.yaml`](calibration_x6y3_full_cosim.yaml), each section against its own planted model (`TwoLevelModel` → `ThreeLevelModel` → the drive-form `TwoQubitModel`) |

Eight notebooks target **real hardware** instead of the co-sim and are therefore *not* executed in CI:

| Notebook | What it shows |
|---|---|
| [`remote_pulse.ipynb`](remote_pulse.ipynb) | the ZCU216 quickstart — connect to the board server with `RemoteDriver`, upload/load a gateware bundle, and fire a pulse train from an on-core `@kernel` on a real DAC. Server setup: [docs/software/board-server.md](../docs/software/board-server.md) |
| [`host_window.ipynb`](host_window.ipynb) | the **host window (DMA)** smoke test — an on-core `@kernel` stores into an `Array(host=True)`, the funnel writes it into the PS DDR4 CMA buffer over `S_AXI_HP0_FPD`, and `rq.run` reads it back with `read_host`; checked against the same values in a RAM array, then a result 4× the whole core RAM. Needs a window-capable bundle (e.g. `build/x6y3-hostwin`). Run it before any capture larger than the core RAM (raw-IQ readout cal, a `Resonator` scan keeping every shot) |
| [`calibration_x6y3.ipynb`](calibration_x6y3.ipynb) | the hardware `Calibration_X6Y3.ipynb` (qcal + QubiC) reproduced step-for-step on the real X6Y3 chip: the real qcal tree ([`cal-config-x6y3.yaml`](cal-config-x6y3.yaml)) through `Config.from_qcal`, all 8 qubits, the reference's exact knobs (the two `Resonator` scans that open the session — wideband 6.53 → 6.85 GHz on q0, then ± 25 MHz on q1–q7 — ± 2.5 MHz Separation, ± 0.005 readout-amp Fidelity, ± 2.5/± 5 MHz Ramsey detunings, coarse + relative-fine Amplitude, per-qubit + absolute Phase), heralded throughout. Its co-sim twin above is the CI-verified reference for every code path |
| [`calibration_process_x6y3.ipynb`](calibration_process_x6y3.ipynb) | **the full calibration process on the real chip** — the hardware twin of `calibration_process_x6y3_cosim.ipynb`: the same five stages, same classes, same order, on all 8 qubits and all 8 ring pairs at hardware shot counts, through the real qcal tree ([`cal-config-x6y3.yaml`](cal-config-x6y3.yaml)). Carries the two stages the co-sim cannot check — `Leakage` (its `ThreeLevelModel` never populates \|2⟩ from a GE drive, so there is no leakage to plant) and the punchout ridge — and awaits a board session. Supersedes `calibration_x6y3_full.ipynb` |
| [`calibration_x6y3_full.ipynb`](calibration_x6y3_full.ipynb) | *(superseded by `calibration_process_x6y3.ipynb` — kept as the spec-04 X5 record)* the **FULL flow** on the real chip (spec two-qubit/04 §5 X5): `calibration_x6y3.ipynb`'s readout + 1Q chain extended with the EF subspace (a 3-level classifier trained from RAW \|0⟩/\|1⟩/\|2⟩ clouds — \|2⟩ prepped with the tree's stored EF X — then EF freq/amp/phase on all 8) and the two-qubit section: `JAZZ` around the ring, the two-qubit-drive CZ chain on all 8 ring pairs — plain pairs first, the (5, 6)/(6, 7) EF-sandwich pairs after their q6 EF-X prerequisite — the pulse-list-driven spectator phases, and an n-amplified conditionality-R validation. Authored against the co-sim twin above; awaits a board session |
| [`readout_robs.ipynb`](readout_robs.ipynb) | fire **one** readout-drive pulse at 2.76 GHz on qubit 1's readout channel (1) on the `xm650-loopback` board and plot the SoC's readout-observation buffer (`robs`) — the raw ADC-rate trace the hardware streams (per-lane sum of the mapped ADCs) for the whole time the pulse is valid, i.e. the looped-back readout tone coming back off the DAC (folded to ~0.76 GHz at the 2 GS/s ADC). The minimal `robs` demo — one `rq.run` + one `rq.read_robs` |
| [`vna.ipynb`](vna.ipynb) | a wideband VNA on the `xm650-loopback` board — from-scratch on-core `@kernel`s sweep the readout carrier 0.1 → 7.9 GHz (the full 8 GS/s Nyquist zone, 781 points at 10 MHz) and plot the mean shot amplitude per frequency. Two implementations, both bounded by the 16 KB core RAM: one keeps **every** shot's raw IQ (6 MB → one `rq.rerun` per frequency, 781 reruns), the other **accumulates each shot's power** `re²+im²` on-core (phase-insensitive, 1 word/point, sweeping the frequency code on-core via a wrapping-Q16 accumulator → the whole sweep in one rerun); the notebook compares their data-collection time |
| [`iq_scatter.ipynb`](iq_scatter.ipynb) | single-shot IQ at a **fixed** 2.75 GHz on the `xm650-loopback` board — the readout drive (ch 1) plays a measurement tone, the demod carrier (ch 2, `demod_freq_to_code` = 4× the drive code, folded above its own Nyquist) returns one `(I, Q)` per shot. Records **10,000 shots** (10 reruns of 1,000 — the 16 KB core RAM caps one `out` buffer at ~2,000 words) and draws the IQ **scatter**; the blob's 1-σ spread is the readout noise floor |

Each notebook owns the sim lifecycle itself: `server.start(...)` brings up the cocotb bench,
`drv.sim.set_model(...)` plants the `TwoLevelModel`, the calibrations run, and a final cell calls
`server.stop(drv)`.

## Running

The notebooks import the editable-installed `riscq` package, so they run from any working directory.
Execute one headless (this is exactly what CI does):

```bash
jupyter nbconvert --to notebook --execute --inplace examples/amplitude_sweep.ipynb
jupyter nbconvert --to notebook --execute --inplace examples/single_qubit_calibration.ipynb
```

or open them interactively:

```bash
jupyter lab examples/
```

`amplitude_sweep.ipynb` runs in a couple of minutes. `single_qubit_calibration.ipynb` is slower
(~30 min headless) and `calibration_x6y3_cosim.ipynb` slower again (~60 min: two qubits, heralded
shots, three Phase passes): the readout-classifier cals need a long idle reset between shots
(`relax ≫ T1`) so the `|0⟩`/`|1⟩` clusters separate under the qcal SNR metric — the point is
convergence to known truth, not speed. `calibration_x6y3_full_cosim.ipynb` covers the whole flow
(readout + 1Q + EF + two-qubit) in ~45 min by running on a 0.75×-scaled reset budget (its yaml's
header explains the sizing) with co-sim-light shots — its per-stage code paths are the ones the
committed `--cosim` pytest gates pin down tightly.

## Requirements

- `riscq` installed (`pip install -e software`) with its deps (numpy, scipy, Pyro5), plus `qutip`
  (the `TwoLevelModel` physics), `matplotlib`, and `jupyter`.
- `verilator` and the riscv LLVM toolchain (`riscv64-unknown-elf-clang` + binutils) on `PATH` —
  the co-sim Verilates the SoC and cross-compiles the on-core kernels. The first run
  elaborates + Verilates the DUT (~1–2 min); later runs reuse the cached build under
  `software/build/sim-2q/`.
