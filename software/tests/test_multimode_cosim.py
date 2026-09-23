"""L2 gate of universal-cal V4 (spec 24 U4's exact-population pattern): the sequence a multimode
calibration plays — the GE π, EF π and an f0g1 flat-top swap on the second line, resolved from the
Config through the universal base — moves the photon on the co-simulated `CavityModel` exactly as
the drive integral predicts, read off `model_state()` after ONE shot (no shot statistics)."""

import math

import numpy as np
import pytest

from riscq.cal import Config
from riscq.cal.axes import Param
from riscq.cal.experiment import Experiment
from riscq.cal.measure import Measure
from riscq.cal.sequence import Cond, Gate
from riscq.pulses import units
from riscq.sim.models import _amp_est
from tests.test_models import _CAV_A, _CAV_EF, _CAV_F0G1, _CAV_GE, _CAV_MS, _CAV_N, _cav_hz

pytestmark = pytest.mark.cosim

RATE = math.pi / (_CAV_N * _CAV_A)          # one π per _CAV_N batches at amplitude _CAV_A on every line
FLAT = _CAV_N - 2                           # the flat whose drive integral, ramps included, is closest to π
AMP = _CAV_A / 32768.0                      # the model's amplitude unit is a DAC sample; ours is [−1, 1]


def _cfg(m):
    s = lambda n: units.ns(n, m.params) * 1e-9   # noqa: E731
    c = Config()
    c["qubit/0/freq"] = _cav_hz(_CAV_GE)
    c["qubit/0/EF/freq"] = _cav_hz(_CAV_EF)
    c["qubit/0/x/env"] = c["qubit/0/EF/x/env"] = "square"
    c["qubit/0/x/dur"] = c["qubit/0/EF/x/dur"] = s(_CAV_N)
    c["qubit/0/x/amp"] = c["qubit/0/EF/x/amp"] = AMP
    c["qubit/0/x90/amp"] = AMP / 2
    c["qubit/0/T1"] = s(1000)
    c["readout/0/freq"] = units.demod_code_to_freq(2048, m.params)
    c["readout/0/amp"] = 0.5
    c["readout/0/dur"] = s(56)
    c["readout/0/demod/dur"] = s(40)
    c["reset/relax"] = s(8)
    c["lines/f0g1/core"] = 0
    c["lines/flux/core"] = 0
    c["mode/M1/line"] = "f0g1"
    c["mode/M1/freq"] = _cav_hz(_CAV_F0G1)
    c["mode/M1/amp"] = AMP
    c["mode/M1/pi"] = s(FLAT + 4)               # 2-batch gaussian half-ramps each side of the flat
    c["mode/M1/ramp"] = s(2)
    c["mode/M1-S1/line"] = "flux"
    c["mode/M1-S1/freq"] = _cav_hz(_CAV_MS)
    c["mode/M1-S1/amp"] = AMP
    c["mode/M1-S1/pi"] = s(FLAT + 4)
    c["mode/M1-S1/ramp"] = s(2)
    return c


def _model():
    return {"kind": "cavity", "core": 0, "f_ge": _cav_hz(_CAV_GE), "f_ef": _cav_hz(_CAV_EF),
            "rabi_ge_rad_per_amp": RATE, "rabi_ef_rad_per_amp": RATE,
            "f_f0g1": _cav_hz(_CAV_F0G1), "f0g1_rad_per_amp": RATE,
            "f_ms": _cav_hz(_CAV_MS), "ms_rad_per_amp": RATE,
            "chi_ge": 0.0, "chi_ef": 0.0, "collapse": False, "noise_scale": 0.0, "t1": 10 ** 9}


def _expected_angle(m, cfg, spec):
    """The swap angle the model integrates for a config gate: rate × Σ per-batch amp estimate."""
    from riscq.cal.gates import resolve
    g = resolve(cfg, 0, spec, m)
    env = g.pulse.envelope() if g.flat else g.pulse.env
    amp = (g.pulse.up if g.flat else g.pulse).amp
    spl = g.line.samples_per_line
    per_batch = env.reshape(-1, spl).repeat(16 // spl, axis=1) * amp * 32768.0
    code = units._freq_code(g.carrier_hz, m.params)
    total = 0.0
    for b, row in enumerate(per_batch):
        k = 16 * b + np.arange(16)
        total += _amp_est(np.rint(row.real * np.cos(math.pi * code * k / (1 << 15))))
    return RATE * total


def _one_shot(cosim, cfg, seq):
    drv, m = cosim
    drv.sim.set_model(_model())
    Experiment(cfg, [0], {0: seq}, {0: ()}, (), Measure.counts(), 1, label="cavity").run(drv)
    return drv.sim.model_state()


def test_f0g1_flat_top_swaps_the_photon(cosim_mm):
    """|g,0> → X → EF/X → f0g1 π: P(M = 1) at the drive integral's sin²(θ/2), θ ≈ π; and a
    half-length swap (the `hpi` entry) leaves the population at ~½."""
    drv, m = cosim_mm
    cfg = _cfg(m)
    theta = _expected_angle(m, cfg, "mode/M1/pi")
    st = _one_shot(cosim_mm, cfg, [Gate("x"), Gate("EF/x"), Gate("mode/M1/pi")])
    assert st["p_M"][1] == pytest.approx(math.sin(theta / 2) ** 2, abs=0.005), (st["p_M"], theta)
    assert st["p_M"][1] > 0.99
    cfg["mode/M1/hpi"] = cfg["mode/M1/pi"] / 2
    theta_h = _expected_angle(m, cfg, "mode/M1/hpi")
    st = _one_shot(cosim_mm, cfg, [Gate("x"), Gate("EF/x"), Gate("mode/M1/hpi")])
    assert st["p_M"][1] == pytest.approx(math.sin(theta_h / 2) ** 2, abs=0.005)


def test_flux_swap_moves_the_photon_to_storage(cosim_mm):
    """PREP_M then the beam-splitter π on the flux line: the photon ends in S."""
    drv, m = cosim_mm
    cfg = _cfg(m)
    st = _one_shot(cosim_mm, cfg, [Gate("x"), Gate("EF/x"), Gate("mode/M1/pi"), Gate("mode/M1-S1/pi")])
    assert st["p_S"][1] > 0.95, st["p_S"]
    assert st["p_M"][1] < 0.05


def test_cond_prep_and_read_back(cosim_mm):
    """The photon is read back onto the qubit by the reverse gates (READ_M) under a `Cond` prep
    param. Two back-to-back swaps put six plays on the f0g1 channel inside one LEAD: the compiler's
    R4 margin must space them (a late push plays the previous slot's params)."""
    drv, m = cosim_mm
    cfg = _cfg(m)
    p = Param("prep", (1,))
    seq = [Cond(p, [Gate("x"), Gate("EF/x"), Gate("mode/M1/pi"), Gate("mode/M1/pi"), Gate("EF/x")])]
    drv.sim.set_model(_model())
    Experiment(cfg, [0], {0: seq}, {0: ()}, (p,), Measure.counts(), 1, label="cavity").run(drv)
    st = drv.sim.model_state()
    assert st["p_qubit"][1] > 0.95 and st["p_M"][1] < 0.05, (st["p_qubit"], st["p_M"])
