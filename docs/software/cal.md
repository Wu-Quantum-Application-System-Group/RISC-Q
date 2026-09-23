# `riscq.cal` — the calibration library

One library for both autocalibration processes the project supports — the qcal/QubiC X6Y3 process
and the QICK multimode process — on **one batched kernel** and a **host sequence compiler**. Design of
record: [specs/universal-cal](../../specs/universal-cal/README.md) (architecture, the catalog of every
calibration as *sequence · axes · measure · analysis*, the V0–V6 plan and its findings).

## What a calibration is

Every calibration is `reset · prep · swept block · unprep · MEAS`, run as a batched sweep on a fixed
grid period: the on-core kernel walks `npts × shots` readouts, the host reruns it over the runtime
parameters, and the class fits the decoded populations and proposes Config writes. The pieces:

| Layer | Module | What it holds |
|---|---|---|
| gates | `gates.py` | a gate spec (`'x90'`, `'EF/x'`, `'qubit/<j>/x90'`, `'mode/M1/pi'`, `'transition/f0-g1/pi'`, a literal `Pulse`/`FlatTop` with a `line`) → the calibrated baseband pulse on its channel, carrier, virtual-Z pair; `Tables` groups a core's slots |
| sequence | `sequence.py` | the elements (`Gate`, `Rz`, `Idle`, `Train`, `Repeat`, `Cond`, `Par`, `Meas`, `ActiveReset`) and the compiler that applies the ten timing rules (R1–R10 in its docstring) and emits ONE C header per core (`seq_init / seq_point / seq_shot / seq_meas / meas_tail`) |
| axes | `axes.py` | `Axis` (an on-core affine sweep: amp / freq / dur / wait / phase, exact host mirror of the realized codes), `Param` (a runtime int the host rewrites per rerun), `Lin` (affine arithmetic over both) |
| measure | `measure.py` | `Measure.counts / raw / levels / iqsum` — the result mode, the readout slots, the decode; `reads` for pair experiments |
| kernel | `batched.py` | `k_batched`, the one `@kernel`: the grid, the two axes `a`/`b`, the params `r0..r3`, the modes COUNTS / RAW / IQSUM / NONE, the herald |
| runner | `experiment.py` | `Experiment`: compile per key, one period per key, `rq.setup` + a rerun per point of the Params' product, decode per reading core |
| base | `calibration.py` | `Calibration` (hooks `axes / params / sequence / analyze / measure`, `readout='counts'|'mapback'|'classifier'`, `prep/unprep`) and `Estimate`; `Result` is `base.Result` (unchanged contract: `ok/oks/data/fit/proposal/apply/plot`) |
| classes | `cals/single.py`, `cals/readout.py`, `cals/multimode.py`, `cals/twoqubit.py`, `cals/rpe.py` | the calibrations (≤ ~30 lines each; RPE/CZ ≤ 60) |
| analysis | `fits.py`, `analysis/estimators.py`, `analysis/classifier.py`, `analysis/rpe.py`, `analysis/drag.py` | curve fits, the estimators the classes share, the GMM readout classifier, the RPE ladders, fast-DRAG |
| config | `config.py`, `cz.py`, `adapters/multimode.py` | the slash-path `Config` (+ `from_qcal / save_qcal / snapshot`), the pair's `two_qubit/(i, j)/CZ` resolvers, the QICK YAML adapter |
| processes | `process/x6y3.py`, `process/multimode.py`, `process/step.py` | the two host scripts (order, gating, snapshot + apply per step) |
| co-sim | `riscq.sim.models` | `TwoLevelModel`, `ThreeLevelModel`, `TwoQubitModel`, `CavityModel` (transmon ⊗ M ⊗ S on the gate / f0g1 / flux lines) |

## Writing a calibration

```python
class LengthRabi(Calibration):
    def __init__(self, cfg, qubits, gate, durs=(20e-9, 2e-6, 100), shots=64, **kw):
        kw.setdefault("readout", "mapback")          # the un-prep gates are appended before MEAS
        super().__init__(cfg, qubits, shots, **kw)
        self.gate, self.durs = gate, durs

    def axes(self, q, m):                            # the on-core sweep(s): at most two
        lo, hi, n = self.durs
        return (Axis.dur(lo, hi, int(n), m),)

    def sequence(self, q, x):                        # the swept block, x = the axes
        return [Gate(self.gate, dur=x[0])]

    def analyze(self, q, s, m) -> Estimate:          # s.x / s.codes / s.y (or {param values: y})
        return length_rabi(np.asarray(s.codes[0], float), s.y, m, "mode/M1/pi", "mode/M1/hpi")
```

- `params(q, m)` returns the runtime `Param`s; the runner reruns the one image over their cartesian
  product and `s.y` becomes `{values: array}`. A `Cond(param, body)` plays its body when the param
  is nonzero (a prep branch); a `Repeat(param, body)` / `Train(gate, param)` is a runtime loop.
- A pair calibration subclasses `PairCalibration` (`cals/twoqubit.py`): its key is the pair, its
  gates are absolute (`qubit/<j>/x90`), its `Measure.reads` lists the members that read out, and
  the CZ is the sub-sequence `cz()` builds from the pair's pulse list (coupler form: the coupler
  line's tone; drive form: a `Par` of the two lines' tones; an EF-sandwich pair wraps the train in
  the shelf's EF X).
- Several experiments in one `run` (a ladder of passes, two roles, two circuits): call
  `self._series(drv, sequence=..., params=...)` per experiment (`CZAmplitude`, `LocalPhases`,
  `RPEPhase`, `CZRPE`).

## The timing rules the compiler owns

The compiler's docstring (`sequence.py`) states R1–R10. The ones a class author feels: a sequence
is end-anchored at `t_ro − SEP`; lines are sequential unless in a `Par` (end-aligned); a channel
that plays two carriers retunes with a `LEAD` gap (the phasor regen); the depth-4 timed queue is
paced (`TRAIN_AHEAD`) and a push must still land `LEAD + POST_MARGIN` before its play — a play whose
wait would not gets an idle gap, and a runtime loop is placed on the `TRAIN_STEP` grid; flat-top
slots hold ≥ 2 batches; one frame per (channel, carrier) with the virtual-Z bracket; ONE grid period
per experiment, sized at the sweep's worst case. The findings behind those rules — the late-fire
gotcha, the II=2 pop, the cross-core grid skew — are in [specs/universal-cal/03-plan §3](../../specs/universal-cal/03-plan.md)
and [docs/soc/SOC_TIPS.md](../soc/SOC_TIPS.md) §5 / §9.4.

## Verification

- **L0, host-pure** (`tests/test_sequence.py`, `tests/test_cals_*.py`): the timing tables and the C
  against the rules; every class on the `Responder` against planted physics (`tests/responder.py`'s
  rule: the answer models the intended physics from first principles).
- **L2, exact populations on the co-sim** (`tests/test_multimode_cosim.py`, `tests/test_cal.py`):
  one shot, `drv.sim.set_model(...)`, `drv.sim.model_state()` — the pattern of record (CLAUDE.md).
- **L1, signal parity** (the V0–V5 gate, `tests/test_parity*.py` at commit `6486c6e`.. of
  `feat/universal-cal`): every class emitted the same DAC windows as the kernel-per-class code it
  replaced, bit-exact per shot; retired with that code at V6.

Run the host suite from `software/` with `python3 -m pytest tests -q -m "not cosim"`; the co-sim
gates with `--cosim` against the `sim-2q` / `sim-2q1c` / `sim-mm` builds in `software/build/`.
