"""Single-qubit calibrations on the universal base (specs/universal-cal/02 §2)."""

from __future__ import annotations

import math

import numpy as np

from riscq.cal import base
from riscq.cal.analysis.estimators import (cosine_axis, damped_decay, exp_decay, line_crossing,
                                           lorentz_peak, rabi_amplitude, ramsey_abs_smallest,
                                           ramsey_vfit)
from riscq.cal.axes import Axis, Param
from riscq.cal.base import Result
from riscq.cal.calibration import Calibration, Estimate
from riscq.cal.experiment import Series
from riscq.cal.gates import drive_sigma, resolve
from riscq.cal.measure import Measure
from riscq.cal.sequence import Gate, Idle, Repeat, Rz, Train
from riscq.pulses import Pulse, envelopes
from riscq.pulses import units

TWO_PI = 2 * math.pi
PERIOD_FRAC = {"X90": 0.25, "X": 0.5}    # qcal: the fraction of a Rabi period the gate rotates
SPEC = {"X90": "x90", "X": "x", "EF/X90": "EF/x90", "EF/X": "EF/x"}   # gate name → Config spec
GE_PI = ("x90", "x90")                    # the GE π the EF calibrations prepare |1> with (spec 04 §2)


def _kind(gate: str) -> str:
    """'X90' | 'X' of a gate name ('EF/X' → 'X')."""
    kind = gate.split("/")[-1]
    assert gate in SPEC and kind in PERIOD_FRAC, f"gate must be one of {sorted(SPEC)}, got {gate!r}"
    return kind


def _sub(gate: str) -> str:
    """The Config subspace prefix: '' for GE, 'EF/' for EF."""
    return "EF/" if gate.startswith("EF/") else ""


def check_train(gate: str, n_gates: int) -> None:
    """qcal's repetition guard (single_qubit.py:154-158): an amplified sweep lands back on |0>."""
    if int(n_gates) > 1:
        step = 4 if _kind(gate) == "X90" else 2
        assert int(n_gates) % step == 0, \
            f"n_gates must be a multiple of {step} for gate {gate!r}, got {n_gates}"


class Amplitude(Calibration):
    """Rabi amplitude calibration (spec 13 §7): a paced train of `n_gates` plays of the Config's own
    `gate` ('X90' | 'X') with the amplitude swept on-core; `rabi_amplitude` fits the |1> population
    and proposes `qubit/{q}/{x90|x}/amp` (+ `qubit/{q}/rabi` for n_gates=1). `amp_span` is
    normalized, or MULTIPLES of the current amp with `relative_amp` (qcal's fine pass)."""

    def __init__(self, cfg, qubits, gate="X90", n_gates=1, amp_span=(0.03, 0.97), points=21,
                 relative_amp=False, shots=160, derive_hpi=False, **kw):
        check_train(gate, n_gates)
        super().__init__(cfg, qubits, shots, **kw)
        self.gate, self.n_gates, self.points = gate, int(n_gates), int(points)
        self.amp_span, self.relative_amp = amp_span, bool(relative_amp)
        self.target_angle = TWO_PI * PERIOD_FRAC[_kind(gate)]
        self.derive_hpi = bool(derive_hpi)      # QICK: hpi = pi/2 for a linear drive (spec 24 §3.5)
        self.recovered_rabi = {}

    def path(self, q) -> str:
        return f"qubit/{q}/{SPEC[self.gate]}/amp"

    def label(self) -> str:
        return f"Amplitude {self.qubits} {self.gate} n_gates={self.n_gates}"

    def axes(self, q, m):
        lo, hi = self.amp_span
        if self.relative_amp:
            lo, hi = lo * float(self.cfg[self.path(q)]), hi * float(self.cfg[self.path(q)])
        return (Axis.amp(lo, hi, self.points),)

    def sequence(self, q, x):
        return [Train(Gate(SPEC[self.gate], amp=x[0]), self.n_gates)]

    def analyze(self, q, s: Series, m) -> Estimate:
        rg = resolve(self.cfg, q, SPEC[self.gate], m)
        sig = np.array([drive_sigma(m, rg, int(c)) for c in s.codes[0]]) * self.n_gates
        est = rabi_amplitude(s.codes[0], sig, s.y, self.n_gates, self.target_angle, self.path(q),
                             f"qubit/{q}/{_sub(self.gate)}rabi" if self.n_gates == 1 else None)
        if "rabi" in est.data:
            self.recovered_rabi[q] = est.data.pop("rabi")
        if est.ok and self.derive_hpi and _kind(self.gate) == "X":
            est.proposal[f"qubit/{q}/{_sub(self.gate)}x90/amp"] = est.proposal[self.path(q)] / 2
        est.data["x"] = np.asarray(s.codes[0], dtype=float)     # the fit axis is the code (as before)
        return est


class Frequency(Calibration):
    """Ramsey vs artificial detuning — qcal's V-fit (spec 13 §7). One resident image; each
    detuning is a rerun that only rewrites the virtual-Z pair coupled to the wait axis. `detune` is
    the ladder step (±k·detune, k = 1..n_detune/2) or `detunings` an explicit signed list (Hz);
    `t0`/`dt` the wait grid, or `t_max` spreading `points` waits over [0, t_max]."""

    def __init__(self, cfg, qubits, detune=5e6, n_detune=4, points=14, t0=80e-9, dt=40e-9,
                 shots=96, detunings=None, t_max=None, gate="X90", **kw):
        super().__init__(cfg, qubits, shots, **kw)
        self.spec = SPEC[gate]
        self.detune, self.n_detune, self.points = float(detune), int(n_detune), int(points)
        self.t0, self.dt = float(t0), float(dt)
        self.detunings = None if detunings is None else [float(d) for d in detunings]
        self.t_max = None if t_max is None else float(t_max)
        self.recovered_detuning_code = {}

    def _d_codes(self, m) -> list:
        if self.detunings is not None:
            return [units._freq_code(d, m.params) for d in self.detunings]
        D = units._freq_code(self.detune, m.params)
        return [dc for k in range(1, self.n_detune // 2 + 1) for dc in (-k * D, k * D)]

    def _wait_grid(self, m) -> tuple:
        from riscq.cal.base import batches
        if self.t_max is not None:
            return 0, batches(self.t_max / (self.points - 1), m)
        return batches(self.t0, m), batches(self.dt, m)

    def _freq_path(self, q) -> str:
        return f"qubit/{q}/{_sub(self.spec)}freq"

    def _carrier(self, q) -> float:
        return float(self.cfg[self._freq_path(q)])

    def params(self, q, m):
        self._det = Param("det", tuple(self._d_codes(m)))
        return (self._det,)

    def axes(self, q, m):
        t0, dt = self._wait_grid(m)
        return (Axis.wait_batches(t0, dt, self.points, m, detune=self._det),)

    def sequence(self, q, x):
        w = x[0]
        return [Gate(self.spec), Idle(w), Rz(self.spec, w.pair), Gate(self.spec)]

    def analyze(self, q, s: Series, m) -> Estimate:
        wf = np.asarray(s.codes[0], dtype=float)
        fringes = {dc: s.y[(dc,)] for dc in self._det.values}
        est = ramsey_vfit(wf, fringes, self._carrier(q), m, self._freq_path(q))
        if "detuning_code" in est.data:
            self.recovered_detuning_code[q] = est.data.pop("detuning_code")
        return est


class T2(Calibration):
    """T2* (Ramsey): one delay sweep at a small artificial detuning; the damped-cosine τ.
    `echoes=n` inserts n π pulses (Hahn / CPMG — the total wait is split into 2n idles, so the
    swept idle is the total over 2n and the reported x-axis the total); `apply_freq=True` adds
    QICK's abs-smallest re-tune of the carrier from the single fringe (spec 24 §3.4)."""

    def __init__(self, cfg, qubits, detune=1.5e6, points=15, t0=80e-9, dt=80e-9, shots=120,
                 echoes=0, apply_freq=False, gate="X90", **kw):
        super().__init__(cfg, qubits, shots, **kw)
        self.spec = SPEC[gate]
        self.detune, self.points, self.t0, self.dt = float(detune), int(points), float(t0), float(dt)
        self.echoes, self.apply_freq = int(echoes), bool(apply_freq)
        self.recovered_t2 = {}

    def _segments(self) -> int:
        return 2 * self.echoes if self.echoes else 1

    def axes(self, q, m):
        from riscq.cal.base import batches
        n = self._segments()
        return (Axis.wait_batches(batches(self.t0, m) // n, batches(self.dt, m) // n, self.points, m,
                                  detune=self.detune),)

    def sequence(self, q, x):
        w = x[0]
        if not self.echoes:
            return [Gate(self.spec), Idle(w), Rz(self.spec, w.pair), Gate(self.spec)]
        pi = [Gate(self.spec), Gate(self.spec)]
        return [Gate(self.spec), Repeat(self.echoes, [Idle(w)] + pi + [Idle(w)]),
                Rz(self.spec, w.pair), Gate(self.spec)]

    def analyze(self, q, s: Series, m) -> Estimate:
        wf = np.asarray(s.codes[0], float) * self._segments()           # the total wait
        est = damped_decay(wf, s.y, m, f"qubit/{q}/{_sub(self.spec)}T2")
        est.data["x"] = wf
        if est.ok:
            self.recovered_t2[q] = est.proposal[f"qubit/{q}/{_sub(self.spec)}T2"]
            if self.apply_freq:
                path = f"qubit/{q}/{_sub(self.spec)}freq"
                f_fit = est.fit.value * m.params.dsp_freq_hz          # cycles/batch → Hz
                est.proposal.update(ramsey_abs_smallest(est.fit._replace(value=f_fit), self.detune,
                                                        float(self.cfg[path]), path))
        return est


class T1(Calibration):
    """T1: prepare |1> (qcal's `gate` choice: X90·X90 or the config's X), idle, read out; the
    exponential decay of P(|1>). `t0`/`dt` are the delay (prep end → readout) grid in seconds —
    default from SEP in steps of ~3·T1/points."""

    def __init__(self, cfg, qubits, points=9, t0=None, dt=None, shots=120, gate="X90", **kw):
        assert gate in ("X90", "X"), f"prep gate must be 'X90' or 'X', got {gate!r}"
        super().__init__(cfg, qubits, shots, **kw)
        self.points, self.t0, self.dt, self.gate = int(points), t0, dt, gate
        self.recovered_t1 = {}

    def axes(self, q, m):
        from riscq.cal.base import SEP, batches
        t1_guess = batches(self.cfg[f"qubit/{q}/T1"], m)
        t0 = SEP if self.t0 is None else batches(self.t0, m)
        dt = max(8, int(3 * t1_guess / self.points)) if self.dt is None else batches(self.dt, m)
        assert t0 >= SEP, f"the first delay must be at least SEP = {SEP} batches"
        return (Axis.wait_batches(t0 - SEP, dt, self.points, m),)      # the sequence ends SEP early

    def sequence(self, q, x):
        prep = [Gate("x90"), Gate("x90")] if self.gate == "X90" else [Gate("x")]
        return prep + [Idle(x[0])]

    def analyze(self, q, s: Series, m) -> Estimate:
        from riscq.cal.base import SEP
        delays = np.asarray(s.codes[0], float) + SEP                 # prep end → readout
        est = exp_decay(delays, s.y, m, f"qubit/{q}/T1")
        est.data["x"] = delays
        if est.ok:
            self.recovered_t1[q] = est.proposal[f"qubit/{q}/T1"]
        return est


class Phase(Calibration):
    """X90 virtual-Z (Stark) phase — qcal's two-sequence line crossing (spec 13 §6); or, with
    `gate='X'`, the X pulse's own axis phase from the X90·X·X90 cosine minimum (spec 14 §3.3).
    The two circuits are two runs over the same phase axis."""

    CHI2_MAX = 10.0             # qcal's underfitting guard (single_qubit.py:1067)

    def __init__(self, cfg, qubits, gate="X90", points=21, span=None, shots=120,
                 relative_phase=False, **kw):
        super().__init__(cfg, qubits, shots, **kw)
        self.gate, self.kind, self.points = gate, _kind(gate), int(points)
        self.span = float(math.pi if span is None and self.kind == "X" else 0.25 if span is None else span)
        self.relative_phase = bool(relative_phase)
        self.recovered_vz, self.fallback = {}, {}

    def _x90(self) -> str:
        return _sub(self.gate) + "x90"

    def _x(self) -> str:
        return _sub(self.gate) + "x"

    def _centre(self, q) -> float:
        if not self.relative_phase:
            return 0.0
        if self.kind == "X":
            return float(self.cfg.get(f"qubit/{q}/{self._x()}/phase", 0.0))
        return float(self.cfg.get(f"qubit/{q}/{self._x90()}/vz", [0.0, 0.0])[0])

    def axes(self, q, m):
        c = self._centre(q)
        return (Axis.phase(c - self.span, c + self.span, self.points),)

    @staticmethod
    def circuit(name: str, x90: str, x: str, p) -> list:
        """qcal's Phase circuits on gate `x90`/`x` (spec 13 §6, spec 14 §3.3): the swept phi is the
        X90's virtual-Z pair (vz0 = vz1 = phi) in the two crossing circuits, the X's own axis in
        the third."""
        if name == "Y180_X90":
            return [Rz(x90, math.pi / 2), Gate(x90, vz=p), Gate(x90, vz=p), Rz(x90, -math.pi / 2),
                    Gate(x90, vz=p)]
        if name == "X180_Y90":
            return [Gate(x90, vz=p), Gate(x90, vz=p), Rz(x90, math.pi / 2), Gate(x90, vz=p)]
        return [Gate(x90), Gate(x, phase=p), Gate(x90)]

    CIRCUITS = {name: (lambda name: lambda p: Phase.circuit(name, "x90", "x", p))(name)
                for name in ("Y180_X90", "X180_Y90", "X90_X_X90")}

    def label(self) -> str:
        return f"Phase {self.qubits}" + (" X" if self.kind == "X" else "")

    def run(self, drv) -> Result:
        from riscq.cal.experiment import Experiment
        m = base.socmap(drv)
        axes = {q: self.axes(q, m) for q in self.qubits}
        names = ("X90_X_X90",) if self.kind == "X" else ("Y180_X90", "X180_Y90")
        pops = {}
        for name in names:
            seqs = {q: [Gate(g) for g in self.prep]
                    + self.circuit(name, self._x90(), self._x(), axes[q][0]) for q in self.qubits}
            exp = Experiment(self.cfg, self.qubits, seqs, axes, (), self.measure(), self.shots,
                             label=f"Phase/{name}")
            pops[name] = exp.run(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            x = axes[q][0].values
            if self.kind == "X":
                est = cosine_axis(x, pops["X90_X_X90"][q].y, self._centre(q),
                                  f"qubit/{q}/{self._x()}/phase")
                data[q] = {"x": x, "y": pops["X90_X_X90"][q].y}
            else:
                est = line_crossing(x, pops["Y180_X90"][q].y, pops["X180_Y90"][q].y, self.CHI2_MAX,
                                    f"qubit/{q}/{self._x90()}/vz")
                data[q] = {"x": x, **est.data}
            self.recovered_vz[q], self.fallback[q] = est.data.get("phi"), est.fallback
            fit[q], oks[q] = est.fit, bool(est.ok)
            if est.ok:
                proposal.update(est.proposal)
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, self.cfg, self.label(), oks=oks)


class Leakage(Calibration):
    """qcal's `Leakage` (spec 14 F3): an n×X90 train read for P(|2>) on the pre-trained 3-level
    classifier, once per value of ONE swept Config path (a virtual-Z pair or an envelope kwarg —
    compiled in, so each value is its own compile), taking the value that minimises it."""

    def __init__(self, cfg, qubits, classifier, path, values, n_gates=101, shots=32, maximize=False):
        super().__init__(cfg, qubits, shots, readout="classifier", classifier=classifier)
        self.path, self.values = str(path), list(values)
        self.n_gates, self.maximize = int(n_gates), bool(maximize)

    def measure(self) -> Measure:
        clf = self.classifier if isinstance(self.classifier, dict) \
            else {q: self.classifier for q in self.qubits}
        return Measure.levels(clf, level=2, host=True)

    def run(self, drv) -> Result:
        from riscq.cal.experiment import Experiment
        pops = {q: [] for q in self.qubits}
        for v in self.values:
            cfg = self.cfg.copy()
            for q in self.qubits:
                cfg[self.path.format(q=q)] = v
            seqs = {q: [Train(Gate("x90"), self.n_gates)] for q in self.qubits}
            series = Experiment(cfg, self.qubits, seqs, {q: () for q in self.qubits}, (),
                                self.measure(), self.shots, label="Leakage").run(drv)
            for q in self.qubits:
                pops[q].append(float(np.ravel(series[q].y)[0]))
        data, fit, proposal = {}, {}, {}
        pick = np.argmax if self.maximize else np.argmin
        for q in self.qubits:
            y = np.array(pops[q])
            data[q] = {"x": np.arange(len(self.values), dtype=float), "y": y, "values": list(self.values)}
            fit[q] = None
            proposal[self.path.format(q=q)] = self.values[int(pick(y))]
        self.data, self.fit = data, fit
        return Result(True, data, fit, proposal, self.cfg, f"Leakage {self.qubits} {self.path}")


class Spectroscopy(Calibration):
    """Pulse-probe spectroscopy (spec 24 §3.3, §3.8, §3.13): a weak probe tone whose carrier is
    swept on-core, bracketed by `prep`/`unprep` gates; the Lorentzian centre is proposed at
    `target` — `qubit/{q}/freq` by default, `qubit/{q}/EF/freq` with `prep=('x90','x90')` and
    `target='EF/freq'`, or an absolute `mode/<m>/freq` / `transition/<t>/freq` on another `line`.
    `probe` is (amp, dur seconds) of a square tone (a Pulse may be given instead)."""

    def __init__(self, cfg, qubits, span=10e6, points=101, probe=(0.02, 1e-6), line="qubit",
                 target="freq", shots=64, **kw):
        super().__init__(cfg, qubits, shots, **kw)
        self.span, self.points, self.probe, self.line, self.target = float(span), int(points), probe, line, target
        self.recovered_freq = {}

    def path(self, q) -> str:
        t = self.target
        return t if t.startswith(("mode/", "transition/")) else f"qubit/{q}/{t}"

    def axes(self, q, m):
        f0 = float(self.cfg[self.path(q)])
        return (Axis.freq(f0 - self.span / 2, f0 + self.span / 2, self.points, m),)

    def sequence(self, q, x):
        f0 = float(self.cfg[self.path(q)])
        if isinstance(self.probe, Pulse):
            probe = Pulse(self.probe.env, self.probe.amp, f0, self.probe.phase)
        else:
            from riscq.cal.base import batches, line as line_of
            amp, dur = self.probe
            ch = line_of(self.cfg, q, self.line, self.m)
            probe = Pulse(envelopes.square(batches(dur, self.m) * ch.samples_per_line), float(amp), f0)
        return [Gate(probe, line=self.line, freq=x[0])]

    def analyze(self, q, s: Series, m) -> Estimate:
        est = lorentz_peak(s.x[0], s.y, self.path(q))
        if est.ok:
            self.recovered_freq[q] = est.proposal[self.path(q)]
        return est


# ── the EF classes: aliases of the GE ones with the GE π prep and the 3-level readout ──

def EFAmplitude(cfg, qubits, classifier, gate="X90", **kw):
    """qcal's `Amplitude(subspace='EF')` (spec 01 §4.1): `Amplitude` on the EF gate after a GE π,
    read as P(|2>) on the pre-trained 3-level classifier."""
    kw.setdefault("shots", 48)
    return Amplitude(cfg, qubits, gate="EF/" + gate, prep=GE_PI, readout="classifier",
                     classifier=classifier, **kw)


def EFFrequency(cfg, qubits, classifier, **kw):
    """qcal's `Frequency(subspace='EF')`: the EF Ramsey V-fit after a GE π, read as P(|2>)."""
    kw.setdefault("shots", 48)
    return Frequency(cfg, qubits, gate="EF/X90", prep=GE_PI, readout="classifier",
                     classifier=classifier, **kw)


def EFPhase(cfg, qubits, classifier, gate="X90", **kw):
    """qcal's `Phase(subspace='EF')`: the crossing circuits on the EF X90 (or the EF X's axis)."""
    kw.setdefault("shots", 48)
    return Phase(cfg, qubits, gate="EF/" + gate, prep=GE_PI, readout="classifier",
                 classifier=classifier, **kw)
