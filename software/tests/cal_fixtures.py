"""Config builders and constants shared by the calibration tests.

These used to live in tests/test_cal.py, tests/test_cal_host.py, tests/test_cal_drag.py and
tests/test_twoqubit.py; they are collected here so no test module has to import another one.
Config builders and constants ONLY — the planted-physics answer models stay in the test file
that plants them.
"""

import copy
import math
from pathlib import Path

import numpy as np

from riscq.cal import Config
from riscq.pulses import units

CONFIGS = Path(__file__).resolve().parents[1] / "configs"

F_GE = 50e6                              # planted qubit frequency (freq code 2048)
RELAX = 1600                             # co-sim relax head (batches) — the Config carries SECONDS


def _s(n_batches, m):
    """batches → seconds: the co-sim's own short times, expressed in the Config's physical units
    (spec 13 §2)."""
    return units.ns(n_batches, m.params) * 1e-9


def _cfg(m, qfreq=F_GE, x90_amp=0.5, dur=40, drive=None, relax=RELAX):
    """The co-sim Config, in PHYSICAL units (spec 13 §3). `dur` is the demod WINDOW (batches here,
    seconds in the tree) and `drive` the readout-drive length — the projective model only emits its
    tone while the drive is on, so the drive must cover the window (default: window + 16)."""
    c = Config()
    c["qubit/0/freq"] = float(qfreq)
    c["qubit/0/x90/amp"] = float(x90_amp)
    c["qubit/0/T1"] = _s(120, m)
    c["readout/0/freq"] = float(units.demod_code_to_freq(2048, m.params))
    c["readout/0/amp"] = 0.5
    c["readout/0/dur"] = _s(dur + 16 if drive is None else drive, m)
    c["readout/0/demod/dur"] = _s(dur, m)
    c["reset/relax"] = _s(relax, m)
    return c


def _cfg2(m, freqs=(F_GE, F_GE), x90_amp=0.5, relax=RELAX):
    """`_cfg`'s two-core twin (spec 13 §8): the same tree with a block for core 0 AND core 1, each
    on its own carrier — what a simultaneous multi-qubit cal reads."""
    c = _cfg(m, qfreq=freqs[0], x90_amp=x90_amp, relax=relax)
    other = _cfg(m, qfreq=freqs[1], x90_amp=x90_amp, relax=relax)
    c["qubit/1"] = other["qubit/0"]
    c["readout/1"] = other["readout/0"]
    return c


def _ef_cfg(m):
    """`_cfg` plus the EF block and the X90 virtual-Z pair — the tree an EF/vz sequence reads."""
    c = _cfg(m, F_GE)
    c["qubit/0/x/amp"] = 0.9
    c["qubit/0/x90/vz"] = [0.1, 0.2]
    c["qubit/0/EF/freq"] = 45e6
    c["qubit/0/EF/x90/amp"] = 0.4
    return c


# ── the 3-level classifier the EF / leakage cals decode with ──

_MEANS = np.array([[10.0, 0.0], [-5.0, 8.66], [-5.0, -8.66]])      # |0>/|1>/|2> IQ centroids


def _clf(seed=3):
    from riscq.cal import ClassifierN
    rng = np.random.default_rng(seed)
    return ClassifierN([_MEANS[k] + 0.1 * rng.standard_normal((30, 2)) for k in range(3)])


def _levels_iq(p, shots):
    """A RAW `out` whose P(|2>) is exactly `p`: round(p·shots) shots on the |2> centroid, rest
    on |1>."""
    iq = np.zeros((shots, 2))
    n2 = int(round(float(np.clip(p, 0.0, 1.0)) * shots))
    iq[:n2] = _MEANS[2]
    iq[n2:] = _MEANS[1]
    return iq.reshape(-1)


def _leakage_cfg(q=0):
    """A co-sim-scaled Config for the compile: a square gate, so the sweep is over the vz pair."""
    c = Config()
    c[f"qubit/{q}/freq"] = 50e6
    c[f"qubit/{q}/x90/amp"] = 0.5
    c[f"qubit/{q}/x90/vz"] = [0.0, 0.0]
    c[f"qubit/{q}/T1"] = 2.4e-7
    c[f"readout/{q}/freq"] = 1e8
    c[f"readout/{q}/amp"] = 0.5
    c[f"readout/{q}/dur"] = 1.12e-7
    c[f"readout/{q}/demod/dur"] = 8e-8
    c["reset/relax"] = 6.4e-6
    return c


# ── the two-qubit trees ──

def _cz_config():
    """A minimal two-qubit Config with a coupler-drive CZ entry (spec 01 §2)."""
    cfg = Config()
    for q in (0, 1):
        cfg[f"qubit/{q}/freq"] = 50e6
        cfg[f"qubit/{q}/x90/amp"] = 0.5
    cfg["two_qubit/(0, 1)/core"] = 2
    cfg["two_qubit/(0, 1)/CZ/freq"] = 25e6
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "C0_1", "time": 200e-9, "kwargs": {"amp": 0.35, "phase": 0.0},
         "env": {"env_func": "cosine_square", "ramp_fraction": 0.1}},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.3}},   # a ZI correction
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": -0.2}},  # an IZ correction
    ]
    return cfg


def _drive_cfg():
    """A minimal two-qubit-drive Config (spec 04 §1): NO coupler core — both CZ lines on the pair's
    own gate channels at the shared in-band `CZ/freq`, the TARGET line carrying the relative phase.
    Distinct GE carriers so the f_GE → f_CZ retune is visible per core."""
    cfg = Config()
    for q, f in ((0, 50e6), (1, 75e6)):
        cfg[f"qubit/{q}/freq"] = f
        cfg[f"qubit/{q}/x90/amp"] = 0.5
        cfg[f"readout/{q}/freq"] = 10e6
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = 56e-8
        cfg[f"readout/{q}/demod/dur"] = 40e-8
    cfg["reset/relax"] = 8e-6
    cfg["two_qubit/(0, 1)/CZ/freq"] = 25e6
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "Q0", "time": 30e-8, "kwargs": {"amp": 0.35, "phase": 0.0}, "env": "square"},
        {"channel": "Q1", "time": 30e-8, "kwargs": {"amp": 0.35, "phase": 0.267}, "env": "square"},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.0}},
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": 0.0}},
    ]
    return cfg


def _spect_cfg():
    """`_drive_cfg()` + a third qubit (the ring spectator) and its channel-matched vz entry in the
    pair's CZ pulse list (the X6Y3 layout: every pair carries 1-2 spectator corrections)."""
    cfg = _drive_cfg()
    cfg["qubit/2/freq"] = 60e6
    cfg["qubit/2/x90/amp"] = 0.5
    cfg["readout/2/freq"] = 12e6
    cfg["readout/2/amp"] = 0.5
    cfg["readout/2/dur"] = 56e-8
    cfg["readout/2/demod/dur"] = 40e-8
    pl = copy.deepcopy(cfg["two_qubit/(0, 1)/CZ/pulse"])
    pl.append({"channel": "Q2", "env": "virtualz", "kwargs": {"phase": 0.0}})
    cfg["two_qubit/(0, 1)/CZ/pulse"] = pl
    return cfg


def _sandwich_cfg():
    """`_drive_cfg()` rebuilt as an EF-sandwich pair (the X6Y3 (5,6)/(6,7) layout on the 2-core
    sim-2q labels): qubit 1's EF keys + the SAME drive/vz list bracketed by the two identical
    `single_qubit/1/EF/X/pulse` string references."""
    cfg = _drive_cfg()
    cfg["qubit/1/EF/freq"] = 125e6
    cfg["qubit/1/EF/x/amp"] = 0.6
    pl = cfg["two_qubit/(0, 1)/CZ/pulse"]
    cfg["two_qubit/(0, 1)/CZ/pulse"] = (["single_qubit/1/EF/X/pulse"] + pl[:2]
                                        + ["single_qubit/1/EF/X/pulse"] + pl[2:])
    return cfg


# ── the planted CZ pseudo-qubit (drive form), shared by the sweep and ladder answers ──

_AF_AMP = 0.35                   # the planted 2π-round-trip amp — `_drive_cfg()`'s own CZ amp
_AF_N = 60                       # CZ tone length in batches (the planted product's grid)
_AF_DELTA_PER_HZ = 6.0 / 1.5e6   # planted detuning ramp: 6 rad of axis walk at 1.5 MHz off


def _cz_uv(amp, freq, f_star):
    """The planted (|11>, |02>) amplitudes after one CZ tone: `_AF_N` batches, each rotating the
    {|11>, |02>} pseudo-qubit by theta/N about an axis advancing by delta/N per batch — exactly
    `TwoQubitModel`'s drive-form activation (demod-then-rotate per batch, the axis ramping with the
    carrier detuning), reproduced to 1e-3 across amp AND detuning. theta = 2*pi at `_AF_AMP` on
    resonance, where the round trip closes and stamps the conditional pi."""
    theta = 2 * math.pi * amp / _AF_AMP
    delta = _AF_DELTA_PER_HZ * (freq - f_star)
    a, b = 1 + 0j, 0j
    c, s = math.cos(theta / (2 * _AF_N)), math.sin(theta / (2 * _AF_N))
    for t in range(_AF_N):
        e = np.exp(1j * delta * t / _AF_N)
        a, b = c * a - 1j * s * b / e, -1j * s * e * a + c * b
    return a, b


def _cz_branch_p0(u, v, quad):
    """The tomography branch's target P(read 0) for a CZ that left |11> with amplitude u and |02>
    with v. The control-|1> row holds (|0> + u|1>)/sqrt(2) after the Y90 prep + the CZ, and the
    close is Y90 (quad 0) or X90 (quad 1) — |1 - u|^2/4 and |1 - i*u|^2/4 — while the |02> leg
    parks |v|^2/2 of the target in |2>, which our ONE-BIT discriminator reads as a coin flip
    (+|v|^2/4; the |2> ambiguity of spec 01 §4.5, which qcal's 3-level classifier resolves and we
    do not). The control-|0> branch is the same with u = 1, v = 0 (the CZ cannot touch |01>)."""
    return abs(1 - (1j * u if quad else u)) ** 2 / 4 + abs(v) ** 2 / 4
