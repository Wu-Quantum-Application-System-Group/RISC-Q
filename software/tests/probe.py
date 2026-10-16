"""L2 state probes.

The question an L2 test asks is *did the signal the SoC just played put the qubit where the gate
intends?* The RTL runs for real; the answer is then **read**, not re-measured with shot statistics.

Two ways to read it, both exact:

- `Probe.state()` — `drv.sim.model_state()`, the model's own ground truth. Sees everything:
  Bloch phase, |2⟩ leakage, joint two-qubit populations. Does not exercise the readout chain.
- `Probe.sigma_z()` — through the **real** demod/decoder. In soft (`collapse=False`) mode the
  emitted tone amplitude is `readout_amp · ⟨σz⟩`, a continuous signed function of the state, so
  with `noise_scale=0` **one shot is a complete measurement** — there is nothing for repetition to
  average away. Prefer this when the readout path is part of what the test is pinning. Measured
  agreement with the model's own Bloch z: 4 decimal places.

Resetting the qubit costs nothing: re-issuing `set_model` rebuilds the model, so the state returns
to |0⟩ in **zero** simulated cycles. That is what removes the ~1600-batch T1-relax head that
dominates the old tests.

**Load once, rerun per point.** Measured on sim-2q: `rq.setup` (the image load, word-by-word over
AXI) costs ~6 640 simulated batches, a `rerun` ~1 980, `set_model` ~400. So a resident program
probes a point for ~2 200 batches where a fresh `rq.run` costs ~8 400 — a 5-point ladder is ~17 k
instead of ~84 k, i.e. the difference between fitting the 20 k budget and not.

The rule: assert against an **analytic** target — what the gate is supposed to do — never
against another run of the same model.

    p = Probe(cosim, {0: prog})
    spec = dict(kind="twolevel", core=0, f_ge=F_GE, readout_code=RO_CODE,
                rabi_rad_per_amp=rabi_for(m, x90, F_GE, math.pi / 2))   # an exact X90
    b = p.state(spec, {0: {"n": 1}})["bloch"]        # params are PER CORE, like rq.rerun's
    assert b[2] == pytest.approx(0.0, abs=0.02)      # on the equator
    assert b[1] == pytest.approx(-1.0, abs=0.02)     # ...on −y: the axis is pinned too

`params` is keyed by core and forwarded verbatim to `rq.rerun`, which does `params.get(core, {})`.
A bare `{"n": 1}` is therefore not an error — it silently writes NO params and the run uses the
compile-time defaults. Always key by core.
"""

from __future__ import annotations

import numpy as np

from riscq import run as rq
from riscq.cal.base import gate_sigma

TIMEOUT = 3_000_000     # sim cycles; an L2 probe is one short shot, so this is pure slack


def rabi_for(m, pulse, carrier_hz: float, target_rad: float) -> float:
    """The model `rabi_rad_per_amp` that makes `pulse` rotate by exactly `target_rad`.

    THE helper for writing an analytic L2 target: plant this rate and the pulse becomes a perfect
    gate, so the expected state is the textbook one. `rate = target / Σ amp_est`, where `amp_est`
    is the model's per-batch phase-blind RMS over the bit-exact DAC golden — the same samples the
    model integrates.

    Take the amplitude from the pulse itself. `units.AMP_SCALE` is 19896, not 2**15 (DAC headroom),
    so a hand-written "half scale = 16384" silently mis-scales every angle by 1.65x.
    """
    return float(target_rad) / gate_sigma(m, pulse, carrier_hz, pulse.amp_code())


def sigma_z(z, z_ref) -> np.ndarray:
    """⟨σz⟩ from a soft readout, projected onto the |0⟩ reference phasor.

    The demod-LO phase is fixed by the readout code and the (fixed) grid position but sits at an
    arbitrary absolute angle, so a bare `Re(z)` is not the signal. `z_ref` is the same readout with
    the qubit left in |0⟩ (⟨σz⟩ = +1).

    Dividing by |z_ref|² (not |z_ref|) is what makes the result the dimensionless ⟨σz⟩: the
    reference itself must come back at exactly +1.
    """
    z, z_ref = np.asarray(z, dtype=complex), np.asarray(z_ref, dtype=complex)
    return (z * z_ref.conjugate()).real / (np.abs(z_ref) ** 2)


class Probe:
    """A resident program under repeated state probes: load once, rerun per point.

    Each probe rebuilds the model first (`set_model`), so every point starts from |0⟩ with no
    relax head. `params` is the per-core runtime scalar dict of that point.
    """

    def __init__(self, cosim, progs, timeout: int = TIMEOUT):
        self.drv, self.m = cosim
        self.progs = dict(progs)
        self.timeout = timeout
        rq.setup(self.drv, self.m, self.progs)

    def _rerun(self, spec, params=None):
        _check_params(params)
        self.drv.sim.set_model(spec)          # rebuild → |0⟩, in zero simulated cycles
        return rq.rerun(self.drv, self.m, self.progs, params=params, results=["out"],
                        timeout=self.timeout)

    def state(self, spec, params=None) -> dict:
        """Play one point from |0⟩ and return the model's exact state.

        `{"bloch": [x, y, z]}` for two-level, `{"populations": [...]}` for three-level,
        `{"populations": ..., "marginals": ...}` for two-qubit, `{"models": [...]}` for a
        MultiModel.
        """
        self._rerun(spec, params)
        return self.drv.sim.model_state()

    def iq(self, spec, params=None) -> dict:
        """Play one point from |0⟩ and return each core's `out` array as complex IQ pairs."""
        return {core: _as_complex(d["out"]) for core, d in self._rerun(spec, params).items()}

    def sigma_z(self, spec, params=None, rate_key: str = "rabi_rad_per_amp") -> dict:
        """⟨σz⟩ per core, through the real readout: one |0⟩-reference point (`rate_key` zeroed, so
        every pulse is a no-op without changing the readout structure) and one point under test."""
        ref = self.iq({**spec, rate_key: 0.0}, params)
        z = self.iq(spec, params)
        return {core: sigma_z(z[core], ref[core]) for core in z}


def _check_params(params) -> None:
    """`params` is keyed by CORE, like `rq.rerun`'s (`params.get(core, {})`). A flat
    `{"n": 1}` is not an error there — it silently matches no core, writes nothing, and the run
    quietly uses the compile-time defaults. That reads as a physics result, so catch it here."""
    if params and not all(isinstance(k, int) for k in params):
        raise AssertionError(
            f"probe params must be keyed by core, got keys {sorted(map(str, params))}. "
            f"Write {{0: {params}}}, not {params} — a flat dict silently writes no params.")


def _as_complex(arr) -> np.ndarray:
    """An `out` array of interleaved (real, imag) integrator results as complex."""
    a = np.asarray(arr, dtype=float).reshape(-1, 2)
    return a[:, 0] + 1j * a[:, 1]
