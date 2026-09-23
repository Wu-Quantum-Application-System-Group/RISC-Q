"""The multimode (transmon + cavity modes) calibrations (specs/universal-cal/02 §5, spec 24 §3.8–3.12):
a sideband swap on another line is a flat-top gate whose length, frequency and amplitude are
swept — `LengthRabi`, `Chevron`, `ErrorAmplification` — with the photon prep / read-back as
`prep` / `unprep` gate lists and `chi()` composing two T2 runs. The same classes serve the qubit
line (a gate spec `'x'` on `line='qubit'`)."""

from __future__ import annotations

import math

import numpy as np

from riscq.cal import base
from riscq.cal.analysis.estimators import chevron, errormap, length_rabi
from riscq.cal.axes import Axis, Param
from riscq.cal.base import Result
from riscq.cal.calibration import Calibration, Estimate
from riscq.cal.cals.single import T2
from riscq.cal.experiment import Series
from riscq.cal.gates import resolve
from riscq.cal.sequence import Gate, Repeat
from riscq.map import pack16
from riscq.pulses import units

PREP_f = ("x", "EF/x")                                   # |f, 0>   (QICK's PREP_f)
PREP_M = ("x", "EF/x", "transition/f0-g1/pi")           # one photon in M (prep_man_photon)
READ_M = ("transition/f0-g1/pi", "EF/x")                # the photon back onto the qubit


def _parent(spec: str) -> str:
    return spec.rsplit("/", 1)[0]


class LengthRabi(Calibration):
    """The swap length (spec 24 §3.10): `gate` (a flat-top spec, e.g. `'mode/M1/pi'`) played with
    its flat length swept on-core; the damped cosine's first extremum is the π length, `hpi = pi −
    T/4`. Proposes `<parent>/pi` and `<parent>/hpi`. `durs` = (lo, hi, points) seconds."""

    def __init__(self, cfg, qubits, gate, durs=(20e-9, 2e-6, 100), shots=64, **kw):
        kw.setdefault("readout", "mapback")
        super().__init__(cfg, qubits, shots, **kw)
        self.gate, self.durs = gate, durs

    def axes(self, q, m):
        lo, hi, n = self.durs
        rg = resolve(self.cfg, q, self.gate, m)
        reps = rg.pulse.reps if rg.flat else 1
        ax = Axis.dur(lo / reps, hi / reps, int(n), m)          # per body piece (spec 24 §4.3)
        self._reps = reps
        return (ax,)

    def sequence(self, q, x):
        return [Gate(self.gate, dur=x[0])]

    def analyze(self, q, s: Series, m) -> Estimate:
        x = np.asarray(s.codes[0], float) * self._reps                 # the flat length (batches)
        p = _parent(self.gate)
        est = length_rabi(x, s.y, m, f"{p}/pi", f"{p}/hpi")
        est.data["x"] = x
        return est


class Chevron(Calibration):
    """Frequency × length (spec 24 §3.9): host reruns over the gate's carrier (`span`, `points`)
    × the on-core length sweep; per-row damped cosines, contrast → `<parent>/freq`, period →
    `<parent>/{pi, hpi}`."""

    def __init__(self, cfg, qubits, gate, span=2e6, points=11, durs=(20e-9, 2e-6, 25), shots=64, **kw):
        kw.setdefault("readout", "mapback")
        super().__init__(cfg, qubits, shots, **kw)
        self.gate, self.span, self.points, self.durs = gate, float(span), int(points), durs

    def params(self, q, m):
        f0 = float(self.cfg[f"{_parent(self.gate)}/freq"])
        self._freqs = f0 + np.linspace(-self.span / 2, self.span / 2, self.points)
        self._f = Param("f", tuple(units.freq_to_code(f, m.params) for f in self._freqs))
        return (self._f,)

    def axes(self, q, m):
        lo, hi, n = self.durs
        rg = resolve(self.cfg, q, self.gate, m)
        self._reps = rg.pulse.reps if rg.flat else 1
        return (Axis.dur(lo / self._reps, hi / self._reps, int(n), m),)

    def sequence(self, q, x):
        return [Gate(self.gate, dur=x[0], freq=self._f)]

    def analyze(self, q, s: Series, m) -> Estimate:
        x = np.asarray(s.codes[0], float) * self._reps
        rows = [s.y[(c,)] for c in self._f.values]
        p = _parent(self.gate)
        est = chevron(self._freqs, x, rows, m, f"{p}/freq", f"{p}/pi", f"{p}/hpi")
        est.data.update(x=x, y=np.array(rows), freqs=self._freqs)
        return est


class ErrorAmplification(Calibration):
    """Error amplification (spec 24 §3.11): a train of `n·2` plays of `gate` per rerun over
    `n ∈ range(n_start, n_pulses + n_step, n_step)`, the knob (`'amp'` or `'freq'`) swept on-core;
    the frequency train flips the phase by π every pulse (an echo). Populations multiplied over n,
    Gaussian-fitted → `<parent>/{amp | freq}`."""

    def __init__(self, cfg, qubits, gate, knob="amp", span=0.3, points=41, n_pulses=10, n_step=2,
                 n_start=1, shots=64, **kw):
        assert knob in ("amp", "freq"), f"knob must be 'amp' or 'freq', got {knob!r}"
        kw.setdefault("readout", "mapback")
        super().__init__(cfg, qubits, shots, **kw)
        self.gate, self.knob, self.span, self.points = gate, knob, float(span), int(points)
        self.ns = tuple(range(int(n_start), int(n_pulses) + int(n_step), int(n_step)))

    def _path(self, q) -> str:
        return f"{_parent(self.gate)}/{self.knob}"

    def params(self, q, m):
        self._n = Param("n", self.ns)
        return (self._n,)

    def axes(self, q, m):
        v = float(self.cfg[self._path(q)])
        if self.knob == "amp":
            return (Axis.amp(v * (1 - self.span), min(1.0, v * (1 + self.span)), self.points),)
        return (Axis.freq(v - self.span / 2, v + self.span / 2, self.points, m),)

    def sequence(self, q, x):
        if self.knob == "amp":
            body = [Gate(self.gate, amp=x[0]), Gate(self.gate, amp=x[0])]
        else:
            body = [Gate(self.gate, freq=x[0]), Gate(self.gate, freq=x[0], phase=math.pi)]
        return [Repeat(self._n, body)]

    def analyze(self, q, s: Series, m) -> Estimate:
        rows = {n: s.y[(n,)] for n in self.ns}
        if self.knob == "amp":
            x = np.asarray(s.codes[0], float)
            est = errormap(x, rows, self._path(q), to_value=lambda c: float(c / units.AMP_SCALE))
        else:
            x = np.asarray(s.x[0], float)
            est = errormap(x, rows, self._path(q))
        est.data.update(x=x, y=np.array(list(rows.values())))
        return est


def chi(cfg, q, mode: str, drv, **kw) -> tuple:
    """The dispersive shift (spec 24 §3.12): two T2 runs, without and with one photon in `mode`
    (its `prep` = `mode/<m>/prep` gate list, default PREP_M), χ = the fringe-frequency difference;
    proposes `mode/<m>/chi_ge` (or `chi_ef` with `gate='EF/X90'`). Returns (Result, χ Hz)."""
    gate = kw.pop("gate", "X90")
    prep = tuple(cfg.get(f"mode/{mode}/prep", list(PREP_M)))
    a = T2(cfg, q, gate=gate, **kw).run(drv)
    b = T2(cfg, q, gate=gate, prep=prep, **kw).run(drv)
    if not (a.ok and b.ok):
        return Result(False, {"without": a.data, "with": b.data}, {"without": a.fit, "with": b.fit},
                      {}, cfg, f"chi {mode}"), math.nan
    m = base.socmap(drv)
    fs = m.params.dsp_freq_hz
    delta = (b.fit[q].value - a.fit[q].value) * fs               # cycles/batch → Hz
    key = "chi_ef" if gate.startswith("EF/") else "chi_ge"
    return Result(True, {"without": a.data, "with": b.data}, {"without": a.fit, "with": b.fit},
                  {f"mode/{mode}/{key}": float(delta)}, cfg, f"chi {mode}"), float(delta)
