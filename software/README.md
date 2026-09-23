# riscq control software

The Python package for the riscq PulseTableSoc: kernel compiler (`riscq.lang`), build/run layers
(`riscq.build`/`riscq.run`), driver backends (`riscq.driver`), co-sim (`riscq.sim`), calibrations
(`riscq.cal`), and the ZCU216 board side (`riscq.board`). Design of record:
[specs/software/](../specs/software/README.md).

## Install

Host (dev machine): `pip install -e .[cal]` — scipy is the `cal` extra (fit helpers only);
the co-sim additionally needs the dev environment's cocotb + verilator + riscv-gcc.

## Board install (offline, spec 10 §2)

The ZCU216 ARM runs the same wheel with only numpy + Pyro5 (already-shipped PYNQ packages
`pynq`/`xrfclk`/`xrfdc` are used but never pip-installed). The board has no internet:

```bash
# on a connected machine (numpy is already on the PYNQ image)
pip download riscq Pyro5 serpent -d wheels/        # or: pip wheel . -w wheels/ from this dir
scp -r wheels/ xilinx@<board>:

# on the board
pip install --no-index --find-links wheels/ riscq
```

`tests/test_packaging.py` gates this in CI: the board module surface must import with only
numpy + Pyro5 installed.

## Board server

```bash
riscq-board-server [--bits ~/riscq-bits] [--bundle <name>] [--host 0.0.0.0] [--port 9091]
# → riscq board server @ PYRO:riscq.board@0.0.0.0:9091   (bundle: <name>)
```

Gateware reaches the board as a **bundle** (`top.xsa` + `params.json` + optional `board.json`)
uploaded over the same Pyro5 connection — `riscq.driver.remote.upload_bundle(drv, name, ...)`,
then `drv.board.load(name)`. See [specs/software/10-hardware-driver.md](../specs/software/10-hardware-driver.md).

**CMA pre-check.** `load()` allocates the host-window result buffer once per session — 16 MB per
core, so 224 MB on the 14-qubit build ([specs/software/22](../specs/software/22-host-window.md)).
Check the kernel's contiguous pool clears it *before* bring-up:

```bash
grep -i cma /proc/meminfo      # CmaTotal / CmaFree must both exceed qubit_num × 16 MB
```

`PynqDriver` refuses to construct with a clear message if it does not; the fix is a larger `cma=`
boot arg in the image (or restarting the server to free a leaked buffer).

## Tests

```bash
PYTHONPATH=. pytest tests/ -q                 # host-pure only — seconds
PYTHONPATH=. pytest tests/ --cosim -q         # + the verilator co-sim tier — minutes
PYTHONPATH=. pytest tests/ --cosim --slow -q  # + the full-loop anchors — ~20 min extra
```

Tests are organised in **tiers**, chosen by what a test asserts rather than by what it imports.
Design of record: [specs/software-test-refactor/](../specs/software-test-refactor/README.md).

| Tier | Runs under | Asserts | Mechanism |
|---|---|---|---|
| **L0** host-pure | always | fits, config, packing, IR/C, model physics, **calibration convergence** | the `responder` fixture — the real cal class against an analytic population model, with only `riscq.run`'s four entry points replaced (`tests/responder.py`) |
| **L1** model off | `--cosim` | *does the kernel emit the right samples on the right converter at the right batch?* | `drv.sim.dac_capture_arm/get` against `ZeroModel` |
| **L2** state probe | `--cosim` | *does the emitted signal put the qubit where the gate intends?* | `tests/probe.py` — 1 shot, no noise, `drv.sim.model_state()` or a soft readout |
| **L3** full loop | `--slow` | the whole chain end to end, with real shots, noise and fits | plain co-sim; the regression net for the tiers above |

Why a co-sim test is expensive: the RTL runs at ~7 000 dspClk batches/s (11 500 with the ADC model
off) and a batched sweep costs `points × shots × grid_period` batches, most of it an idle T1-relax
head. Two facts make the fast tiers possible — `set_model` **rebuilds** the model, so resetting the
qubit to |0⟩ costs *zero* simulated cycles; and in soft (`collapse=False`) mode the readout tone is
`readout_amp · ⟨σz⟩`, a continuous signed function of the state, so **one shot is an exact
measurement**.

Every co-sim run prints a per-test **simulated-batch** table — the machine-independent cost unit.
`--batch-cap N` fails a test that overruns (budget: 20 000/test; `--slow` anchors are exempt).

Writing a new test: pick the tier from what you are asserting, and make the expected value
**analytic** — never another run of the same model, which tests nothing. `probe.rabi_for()` plants
the rate that makes a pulse an exact rotation. Never hand-write an amplitude code:
`units.AMP_SCALE` is 19896, not 2**15.
