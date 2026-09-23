"""The two-qubit calibrations on the base (specs/universal-cal/02 §4; spec two-qubit/01, /04):
`JAZZ`, `CZSweep`, the conditionality-tomography family (`CZFrequency`, `CZAmplitude`,
`CZAmpFreqSweep`, `RelativePhase`) and the Ramsey-peak pair (`LocalPhases`, `SpectatorPhase`).

A pair is ONE key whose sequence names both members' gates absolutely (`qubit/<j>/x90`) and
whose `Measure.reads` lists the cores that read out; the CZ is the sub-sequence `cz_tone()`
builds from the pair's `two_qubit/(i, j)/CZ/pulse` list — the coupler line's tone, or a `Par` of
the two lines' tones in the two-qubit-drive form (the target line's slot phase is the relative
phase), each line retuned f_GE → f_CZ → f_GE around it by the compiler's R5. An EF-sandwich pair
plays the shelf qubit's EF X before and after the whole CZ train (`cz_shelf()`). The config
resolvers / analysis helpers are `riscq.cal.cz`'s."""

from __future__ import annotations

import math

import numpy as np

from riscq.cal import fits
from riscq.cal.axes import Axis, Param, seated_phase
from riscq.cal.base import batches, seconds
from riscq.cal.calibration import Calibration, Estimate
from riscq.cal.experiment import Series
from riscq.cal.measure import Measure
from riscq.cal.sequence import Cond, Gate, Idle, Par, Repeat, Rz
from riscq.cal.cz import (_branch_correction, _cz_amp, _cz_dur_batches, _cz_entry,
                                _cz_local_set, _cz_pulse, _cz_pulse_set, _cz_rel_phase_set,
                                _cz_spectator_set, _fit_complex_freq, _fringe_peak, _local_phase,
                                _mean_offset, coupler_core, cz_coupler_form, cz_sandwich, pair_key)
from riscq.map import LEAD, pack16
from riscq.pulses import Pulse, units

HPI = math.pi / 2
PREP = Param("prep", (0, 1))                                   # the partner in |0> / |1>
SP = Param("sp", (0, 1))                                       # the spectator in |0> / |1>
#: k_cz_cond's close codes 0..3 = +Y90, +X90, −Y90, −X90 as the extra close phase; (0, 2) and
#: (3, 1) are the balanced pairs whose fringe centre divides out of an RPE angle (spec 14 F5).
QUAD = Param("quad", tuple(seated_phase(p) for p in (HPI, 0.0, -HPI, math.pi)))
QUAD_XY = Param("quad", (seated_phase(0.0), seated_phase(HPI)))   # JAZZ: X90 / Y90 close


def x90(q) -> str:
    return f"qubit/{int(q)}/x90"


def X(q) -> list:
    """The |1> prep as two X90s (qcal's X = X90·X90, base.prep)."""
    return [Gate(x90(q)), Gate(x90(q))]


def Y90(q) -> Gate:
    return Gate(x90(q), phase=HPI)


def cz_tone(cfg, pair, m, freq=None, amp=None, dur=None, czmax=None, phase=None) -> list:
    """The pair's CZ tone(s) as elements: the coupler line's pulse (coupler form) or a `Par` of the
    control and target lines' pulses (drive form), each a literal `Pulse` at `CZ/freq` on that
    core's gate channel. `freq` / `amp` / `dur` sweep the tone(s) in lock-step (the retune word,
    the slot amp, the slot dur — k_cz_pop's three knobs); a `dur` sweep is followed by the idle
    that keeps the tone's START fixed across it (`czmax` the envelope length); `phase` sweeps the
    TARGET line's slot phase (RelativePhase)."""
    ctrl, tgt = int(pair[0]), int(pair[1])
    f_cz = float(cfg[f"two_qubit/{pair_key(pair)}/CZ/freq"])
    n = _cz_dur_batches(cfg, pair, m) if czmax is None else int(czmax)

    def tone(drive, line, ph=None):
        p = _cz_pulse(cfg, pair, m, n, drive)
        return Gate(Pulse(p.env, p.amp, f_cz, p.phase), line=line, freq=freq, amp=amp, dur=dur,
                    phase=ph)

    if cz_coupler_form(cfg, pair):
        out = [tone(0, coupler_core(cfg, pair))]
    else:
        out = [Par([tone(0, ctrl), tone(1, tgt, phase)])]
    if dur is not None:
        out.append(Idle(n - dur))
    return out


def cz_local(cfg, pair) -> list:
    """The pair's local virtual-Z corrections, one `Rz` per member on its GE frame (the ZI / IZ
    entries of the CZ pulse list, folded once per CZ as k_cz_cond's `zi` / `iz`)."""
    return [Rz(x90(q), _local_phase(cfg, pair, q)) for q in pair
            if _local_phase(cfg, pair, q) != 0.0]


def cz_shelf(cfg, pair) -> list:
    """The EF-sandwich shelf's EF X (spec 04 §1 / X4), or nothing on a plain pair."""
    shelf = cz_sandwich(cfg, pair)
    return [] if shelf is None else [Gate(f"qubit/{shelf}/EF/x")]


def cz(cfg, pair, m, n=1, local=True, **knobs) -> list:
    """`Gate('CZ')` ×n: the shelf around a train of tones, each followed by the local phases
    (`local=False` for the calibrations that MEASURE those phases)."""
    body = cz_tone(cfg, pair, m, **knobs) + (cz_local(cfg, pair) if local else [])
    shelf = cz_shelf(cfg, pair)
    return shelf + ([Repeat(n, body)] if isinstance(n, Param) or n != 1 else body) + shelf


def _freq_axis(lo_hz, hi_hz, n, m) -> Axis:
    """A carrier sweep between two Hz endpoints' own codes (base.sweep_q16's endpoints)."""
    return Axis.freq_codes(units._freq_code(lo_hz, m.params), units._freq_code(hi_hz, m.params), n, m)


def _phi_axis(points: int) -> Axis:
    """A full-turn virtual-Z sweep from −π, endpoint-EXCLUSIVE (+π is −π: an inclusive span
    collapses to a zero step — the old _phi_sweep, the X3-caught bug)."""
    c0 = units._phase_code(-math.pi)
    dc = 0 if points <= 1 else round((1 << 16) / points)
    codes = c0 + dc * np.arange(points, dtype=np.int64)
    return Axis("phase", pack16(int(c0)), pack16(int(dc)), codes, codes * math.pi / (1 << 15))


class PairCalibration(Calibration):
    """A calibration whose key is the pair `(control, target)`; both members read out unless
    `reads` narrows it."""

    def __init__(self, cfg, pair, shots, **kw):
        super().__init__(cfg, [], shots, **kw)
        self.pair = (int(pair[0]), int(pair[1]))
        self.qubits = [self.pair]
        self.reads = self.pair

    def measure(self) -> Measure:
        return Measure.counts(reads=self.reads)

    def label(self) -> str:
        return f"{type(self).__name__} {self.pair}"

    def _cond(self, n, ramsey, **knobs) -> list:
        """The conditionality-tomography circuit (spec 01 §4.5; k_cz_cond): a Ramsey on `ramsey`
        (Y90 prep, a `QUAD` close) around `n` CZs with the partner prepped |0>/|1> (`PREP`)."""
        other = self.pair[1] if ramsey == self.pair[0] else self.pair[0]
        return ([Par([Y90(ramsey), Cond(PREP, X(other))])] + cz(self.cfg, self.pair, self.m, n, **knobs)
                + [Gate(x90(ramsey), phase=QUAD)])

    @staticmethod
    def _R(y: dict, tgt) -> np.ndarray:
        """The conditionality R = √((P0_C1X − P0_C0X)² + (P0_C1Y − P0_C0Y)²) from the four
        branches {prep} × {quad 0, 1} of `y[(prep, quad)][tgt]` (the old _cond_R)."""
        q0, q1 = QUAD.values[0], QUAD.values[1]
        P0 = {(c, k): 1.0 - y[(c, qv)][tgt] for c in (0, 1) for k, qv in ((0, q0), (1, q1))}
        return np.sqrt((P0[(1, 0)] - P0[(0, 0)]) ** 2 + (P0[(1, 1)] - P0[(0, 1)]) ** 2)

    @staticmethod
    def _peak(x, R):
        """A parabola vertex (a < 0, in range) or the argmax — CZFrequency's refine."""
        i = int(np.argmax(R))
        fit = fits.fit_parabola(x, R)
        star = (float(fit.value) if fit.ok and fit.params["a"] < 0 and x.min() <= fit.value <= x.max()
                else float(x[i]))
        return star, fit, float(R[i]) > 0.5


class JAZZ(PairCalibration):
    """Residual-ZZ Ramsey (spec 01 §4.3; the old JAZZ's docstring): a Hahn-echo Ramsey on the
    target — `X90 · w · π · w · Rz(2π·detune·2w) · close` — with the control in |0>/|1> and the
    midpoint π on BOTH qubits; the signed fringe frequency of I − jQ per control state, and
    ZZ11 = f(1) − f(0). Writes `two_qubit/(i, j)/ZZ11`."""

    def __init__(self, cfg, pair, detune=2e6, points=20, t0=40e-9, dt=40e-9, shots=120):
        super().__init__(cfg, pair, shots)
        self.detune, self.points = float(detune), int(points)
        self.t0, self.dt = float(t0), float(dt)

    def params(self, q, m):
        return (PREP, QUAD_XY)

    def axes(self, q, m):
        return (Axis.wait_batches(batches(self.t0, m), batches(self.dt, m), self.points, m,
                                  detune=self.detune),)

    def sequence(self, q, x):
        c, t = self.pair
        w = x[0]
        return [Par([[Gate(x90(t))], [Cond(PREP, X(c))]]), Idle(w), Par([X(t), X(c)]), Idle(w),
                Rz(x90(t), w.pair * 2), Gate(x90(t), phase=QUAD_XY)]   # the full delay is 2w

    def analyze(self, q, s: Series, m) -> Estimate:
        c, t = self.pair
        t_s = np.array([seconds(2 * w, m) for w in s.codes[0]])
        P = {(prep, k): s.y[(prep, qv)][t] for prep in (0, 1) for k, qv in enumerate(QUAD_XY.values)}

        def signed(I, Q):
            z = (np.asarray(I, float) - np.mean(I)) - 1j * (np.asarray(Q, float) - np.mean(Q))
            return _fit_complex_freq(t_s, z)

        (f0, ok0), (f1, ok1) = signed(P[(0, 0)], P[(0, 1)]), signed(P[(1, 0)], P[(1, 1)])
        data = {"t": t_s, "I0": P[(0, 0)], "Q0": P[(0, 1)], "I1": P[(1, 0)], "Q1": P[(1, 1)],
                "f0": f0, "f1": f1}
        return Estimate(ok0 and ok1, (f0, f1), {f"two_qubit/{pair_key(q)}/ZZ11": f1 - f0}, data=data)


class CZSweep(PairCalibration):
    """CZ resonance / return sweep (spec 01 §4.4; the old CZSweep's docstring): prep |11>, sweep
    ONE field of the CZ tone — `'freq'` (±`span` Hz; a parabola on the control's P(1) dip →
    `CZ/freq`), `'dur'` (the slot dur under a fixed max envelope; one cosine period is the round
    trip → pulse `time`) or `'amp'` (→ pulse `amp`) — and read the CONTROL's P(1)."""

    def __init__(self, cfg, pair, knob="freq", span=10e6, lo=None, hi=None, points=21, shots=120):
        assert knob in ("freq", "dur", "amp"), f"knob must be freq/dur/amp, got {knob!r}"
        super().__init__(cfg, pair, shots)
        self.knob, self.span, self.lo, self.hi, self.points = knob, float(span), lo, hi, int(points)

    def axes(self, q, m):
        cfg, pair = self.cfg, self.pair
        if self.knob == "freq":
            f_cz = float(cfg[f"two_qubit/{pair_key(pair)}/CZ/freq"])
            lo = f_cz - self.span if self.lo is None else self.lo
            hi = f_cz + self.span if self.hi is None else self.hi
            return (_freq_axis(lo, hi, self.points, m),)
        if self.knob == "amp":
            lo = 0.02 if self.lo is None else self.lo
            hi = min(0.99, 2 * _cz_amp(cfg, pair)) if self.hi is None else self.hi
            return (Axis.amp(lo, hi, self.points),)
        czd = _cz_dur_batches(cfg, pair, m)
        lo = 1.0 / m.params.dsp_freq_hz if self.lo is None else self.lo
        hi = seconds(2 * czd, m) if self.hi is None else self.hi
        self._czmax = batches(hi, m)
        return (Axis.dur(lo, hi, self.points, m),)

    def sequence(self, q, x):
        c, t = self.pair
        knobs = {self.knob: x[0]}
        if self.knob == "dur":
            knobs["czmax"] = self._czmax
        return [Par([X(c), X(t)])] + cz(self.cfg, self.pair, self.m, local=False, **knobs)

    def analyze(self, q, s: Series, m) -> Estimate:
        pk = pair_key(q)
        xax, P1 = np.asarray(s.x[0], float), s.y[q[0]]
        if self.knob == "freq":
            fit = fits.fit_parabola(xax, P1)
            ok = (fit.ok and fit.params["a"] > 0 and xax.min() <= fit.value <= xax.max()
                  and (P1.max() - P1.min()) > 0.15)
            return Estimate(ok, fit, {f"two_qubit/{pk}/CZ/freq": float(fit.value)} if ok else {})
        fit = fits.fit_cosine(xax, P1)
        full = 1.0 / fit.value if (fit.ok and fit.value > 0) else math.nan
        ok = fit.ok and fit.value > 0 and xax.min() <= full <= xax.max()
        key = "time" if self.knob == "dur" else "amp"
        return Estimate(ok, fit, {f"two_qubit/{pk}/CZ/pulse": _cz_pulse_set(self.cfg, q, key, full)}
                        if ok else {})


class CZFrequency(PairCalibration):
    """CZ conditionality vs the CZ carrier (spec 01 §4.5; qcal cz.Frequency): R at each carrier
    ±`span` around `CZ/freq` over `ngates` CZs; the parabola-refined argmax → `CZ/freq`; fails
    below a genuine conditional response (R ≤ 0.5)."""

    def __init__(self, cfg, pair, span=6e6, points=15, ngates=1, shots=120):
        super().__init__(cfg, pair, shots)
        self.span, self.points, self.ngates = float(span), int(points), int(ngates)

    def params(self, q, m):
        return (PREP, QUAD)

    def axes(self, q, m):
        f_cz = float(self.cfg[f"two_qubit/{pair_key(self.pair)}/CZ/freq"])
        return (_freq_axis(f_cz - self.span, f_cz + self.span, self.points, m),)

    def sequence(self, q, x):
        return self._cond(self.ngates, self.pair[1], freq=x[0])

    def analyze(self, q, s: Series, m) -> Estimate:
        xax = np.asarray(s.x[0], float)
        R = self._R(s.y, q[1])
        star, fit, ok = self._peak(xax, R)
        return Estimate(ok, fit, {f"two_qubit/{pair_key(q)}/CZ/freq": star} if ok else {},
                        data={"R": R, "P0": {k: 1.0 - v[q[1]] for k, v in s.y.items()}})


class CZAmpFreqSweep(PairCalibration):
    """The 2D CZ (amp × freq) seed landscape (qcal cz.AmpFreqSweep; the old CZAmpFreqSweep's
    docstring): the on-core freq sweep × a host `amp` Param on every physical line in lock-step,
    R per cell, the plain 2D argmax → `CZ/freq` + the drive `amp`. `data["R"]` is [amp, freq]."""

    def __init__(self, cfg, pair, amps=None, span=6e6, points=15, ngates=1, shots=120):
        super().__init__(cfg, pair, shots)
        self.amps = None if amps is None else np.asarray(amps, dtype=float)
        self.span, self.points, self.ngates = float(span), int(points), int(ngates)

    def _amp_axis(self) -> np.ndarray:
        if self.amps is not None:
            return self.amps
        a0 = _cz_amp(self.cfg, self.pair)
        return np.clip(np.linspace(0.5 * a0, 1.5 * a0, 7), 0.002, 0.999)

    def params(self, q, m):
        self._amps = self._amp_axis()
        self._amp = Param("amp", tuple(pack16(units._amp_code(float(a))) for a in self._amps))
        return (self._amp, PREP, QUAD)

    axes = CZFrequency.axes

    def sequence(self, q, x):
        return self._cond(self.ngates, self.pair[1], freq=x[0], amp=self._amp)

    def analyze(self, q, s: Series, m) -> Estimate:
        fax, amps = np.asarray(s.x[0], float), self._amps
        R = np.array([self._R({k[1:]: v for k, v in s.y.items() if k[0] == a}, q[1])
                      for a in self._amp.values])
        ka, kf = np.unravel_index(int(np.argmax(R)), R.shape)
        amp_star, f_star, r_max = float(amps[ka]), float(fax[kf]), float(R[ka, kf])
        ok, pk = r_max > 0.5, pair_key(q)
        prop = {f"two_qubit/{pk}/CZ/freq": f_star,
                f"two_qubit/{pk}/CZ/pulse": _cz_pulse_set(self.cfg, q, "amp", amp_star)} if ok else {}
        return Estimate(ok, {"amp": amp_star, "freq": f_star, "R": r_max}, prop,
                        data={"amps": amps, "freqs": fax, "R": R})


class CZAmplitude(PairCalibration):
    """CZ conditionality vs the drive amp, an error-amplification ladder (qcal cz.Amplitude): for
    each `n_gates` a narrowing amp window (`window`/n) around the running estimate, the parabola
    vertex of R refines it; writes the drive `amp` on every physical line."""

    def __init__(self, cfg, pair, n_gates=(1, 3, 5, 7, 9), window=0.3, points=11, shots=120):
        super().__init__(cfg, pair, shots)
        self.n_gates = tuple(int(n) for n in n_gates)
        self.window, self.points = float(window), int(points)

    def params(self, q, m):
        return (PREP, QUAD)

    def axes(self, q, m):
        wn = self.window / self._n
        return (Axis.amp(max(0.002, self._amp * (1 - wn)), min(0.999, self._amp * (1 + wn)),
                         self.points),)

    def sequence(self, q, x):
        return self._cond(self._n, self.pair[1], amp=x[0])

    def run(self, drv):
        from riscq.cal.base import Result
        pair, pk = self.pair, pair_key(self.pair)
        self._amp, passes, moved = _cz_amp(self.cfg, pair), [], False
        for self._n in self.n_gates:
            s = self._series(drv)[pair]
            xax, R = np.asarray(s.x[0], float), self._R(s.y, pair[1])
            fit = fits.fit_parabola(xax, R)
            if fit.ok and fit.params["a"] < 0 and xax.min() <= fit.value <= xax.max():
                self._amp, moved = float(np.clip(fit.value, 0.002, 0.999)), True
            passes.append({"n": self._n, "x": xax, "R": R, "fit": fit, "amp": self._amp})
        self.data, self.fit = {pair: {"passes": passes}}, {pair: passes[-1]["fit"]}
        prop = {f"two_qubit/{pk}/CZ/pulse": _cz_pulse_set(self.cfg, pair, "amp", self._amp)} \
            if moved else {}
        return Result(moved, self.data, self.fit, prop, self.cfg, self.label(), oks={pair: moved})


class RelativePhase(PairCalibration):
    """CZ conditionality vs the TARGET line's tone phase (spec 04 §4.3; qcal cz.RelativePhase):
    two-qubit-drive form only; `points` phases over `span` around the config value as a Param on
    the target tone (the frame offset carries it — the slot is built at 0); R's parabola-refined
    peak → the target drive entry's `kwargs/phase`."""

    def __init__(self, cfg, pair, span=2 * math.pi, points=15, ngates=1, shots=120):
        assert not cz_coupler_form(cfg, pair), \
            f"pair {tuple(pair)}: RelativePhase needs the two-qubit-drive form (a coupler pair has ONE tone)"
        super().__init__(cfg, pair, shots)
        self.span, self.points, self.ngates = float(span), int(points), int(ngates)

    def params(self, q, m):
        p0 = float(_cz_entry(self.cfg, self.pair, drive=1).get("kwargs", {}).get("phase", 0.0))
        self._phis = p0 + np.linspace(-self.span / 2, self.span / 2, self.points)
        self._phi = Param("phi", tuple(seated_phase(float(p)) for p in self._phis))
        return (self._phi, PREP, QUAD)

    def sequence(self, q, x):
        return self._cond(self.ngates, self.pair[1], phase=self._phi)

    def analyze(self, q, s: Series, m) -> Estimate:
        R = np.array([self._R({k[1:]: v for k, v in s.y.items() if k[0] == p}, q[1])[0]
                      for p in self._phi.values])
        star, fit, ok = self._peak(self._phis, R)
        prop = {f"two_qubit/{pair_key(q)}/CZ/pulse": _cz_rel_phase_set(self.cfg, q, star)} if ok else {}
        return Estimate(ok, fit, prop, data={"x": self._phis, "R": R})


class LocalPhases(PairCalibration):
    """CZ local single-qubit phases (spec 01 §4.6; qcal cz.LocalPhases): a Ramsey `Y90 · CZ ·
    Rz(φ) · Y90` on the ACTIVE qubit over a full turn of φ with the partner in |0>/|1> (`SP`);
    the |0>-branch peak midpointed with the |1>-branch peak after its conditional π is removed
    (`_branch_correction`, spec 04 §3/X1) — with the active role on the control (→ ZI) and the
    target (→ IZ), written into the channel-matched virtual-Z entries. Only the active reads."""

    def __init__(self, cfg, pair, points=15, shots=120):
        super().__init__(cfg, pair, shots)
        self.points = int(points)

    def measure(self) -> Measure:
        return Measure.counts(reads=(self._active,))

    def params(self, q, m):
        return (SP,)

    def axes(self, q, m):
        return (_phi_axis(self.points),)

    def sequence(self, q, x):
        a = self._active
        s = self.pair[1] if a == self.pair[0] else self.pair[0]
        return ([Par([Y90(a), Cond(SP, X(s))])] + cz(self.cfg, self.pair, self.m, local=False)
                + [Rz(x90(a), x[0]), Y90(a)])

    def _offset(self, drv):
        s = self._series(drv)[self.pair]
        phi, offs, data = np.asarray(s.x[0], float), [], {}
        for sp in (0, 1):
            P = s.y[(sp,)]
            off, contrast = _fringe_peak(phi, P)
            data[sp] = {"P": P, "offset": off, "contrast": contrast}
            offs.append(off if contrast > 0.15 else math.nan)
        if any(math.isnan(o) for o in offs):
            return math.nan, data, phi
        return _branch_correction(offs[0], offs[1]), data, phi

    def run(self, drv):
        from riscq.cal.base import Result
        pair, pk = self.pair, pair_key(self.pair)
        self._active = pair[0]
        zi, zdata, phi = self._offset(drv)                  # control → ZI
        self._active = pair[1]
        iz, idata, _ = self._offset(drv)                    # target  → IZ
        self.data = {pair: {"phi": phi, "ZI": zdata, "IZ": idata, "zi": zi, "iz": iz}}
        ok = not (math.isnan(zi) or math.isnan(iz))
        prop = {f"two_qubit/{pk}/CZ/pulse": _cz_local_set(self.cfg, pair, zi, iz)} if ok else {}
        return Result(ok, self.data, {}, prop, self.cfg, self.label(), oks={pair: ok})


class SpectatorPhase(PairCalibration):
    """CZ spectator phase (spec 04 §4.5; qcal SpectatorPhase; the old SpectatorPhase's
    docstring): a Ramsey on a NEIGHBOUR bracketing the pair's CZ — the pair preps (the
    `conditional` member in |0>/|1>, `SP`) and fires its tones while the spectator plays
    `Y90 · window · Rz(φ) · Y90`, the window a LEAD wider than the tone on each side (the pair's
    retune gaps; the explicit idle after the tone keeps the bracket symmetric). The wrap-aware
    mean of the two branch peaks → the spectator's virtual-Z entry in the pair's list. Two-qubit-
    drive form only; only the spectator reads."""

    def __init__(self, cfg, pair, spectator, conditional=None, points=15, shots=120):
        super().__init__(cfg, pair, shots)
        self.spectator = int(spectator)
        self.conditional = int(conditional) if conditional is not None else self.pair[0]
        assert self.spectator not in self.pair, \
            f"spectator {self.spectator} is a member of pair {self.pair} — the pair's own phases " \
            f"are LocalPhases' job"
        assert self.conditional in self.pair, \
            f"conditional qubit {self.conditional} must be a member of pair {self.pair}"
        assert not cz_coupler_form(cfg, self.pair), \
            f"pair {self.pair}: SpectatorPhase supports the two-qubit-drive form only"
        self.points = int(points)
        self.reads = (self.spectator,)

    def params(self, q, m):
        return (SP,)

    def axes(self, q, m):
        return (_phi_axis(self.points),)

    def sequence(self, q, x):
        sp = self.spectator
        return ([Par([Y90(sp), Cond(SP, X(self.conditional))])]
                + cz(self.cfg, self.pair, self.m, local=False)
                + [Idle(LEAD), Rz(x90(sp), x[0]), Y90(sp)])

    def analyze(self, q, s: Series, m) -> Estimate:
        phi, offs, branches = np.asarray(s.x[0], float), [], {}
        for sp in (0, 1):
            P = s.y[(sp,)]
            off, contrast = _fringe_peak(phi, P)
            branches[sp] = {"P": P, "offset": off, "contrast": contrast}
            offs.append(off if contrast > 0.15 else math.nan)
        ok = not any(math.isnan(o) for o in offs)
        corr = _mean_offset(offs[0], offs[1]) if ok else math.nan    # qcal's mean, wrap-aware — NO π
        prop = {f"two_qubit/{pair_key(q)}/CZ/pulse":
                _cz_spectator_set(self.cfg, q, self.spectator, corr)} if ok else {}
        return Estimate(ok, None, prop, data={"phi": phi, "spectator": self.spectator,
                                                "branches": branches, "corr": corr})

    def label(self) -> str:
        return f"SpectatorPhase {self.pair} Q{self.spectator}"
