"""Readout calibrations on the universal base (specs/universal-cal/02 §1): the readout is a `Meas`
element whose drive frequency / amplitude / length and demod window / delay are sweepable, the
qubit prep is a `Cond` on a runtime `prep` param, and the two prep states are two reruns of one
image. The classifier layer (`Classifier`, `ClassifierN`, `res_fidelity`, `rcorr`) is
`riscq.cal.analysis.classifier`'s."""

from __future__ import annotations

import math

import numpy as np

from riscq.cal import base
from riscq.cal.axes import Axis, Param
from riscq.cal.base import SEP, Result, batches, seconds
from riscq.cal.calibration import Calibration
from riscq.cal.experiment import Experiment
from riscq.cal.measure import Measure
from riscq.cal.analysis.classifier import Classifier, res_fidelity
from riscq.cal.sequence import Cond, Gate, Meas
from riscq.map import READOUT_MAX_WIN_LOG2, pack16
from riscq.pulses import units

PREP = Param("prep", (0, 1))


def _prep_gates(gate: str) -> list:
    """qcal's two |1> preps (spec 13 §4): X90·X90, or the config's own X."""
    assert gate in ("X90", "X"), f"prep gate must be 'X90' or 'X', got {gate!r}"
    return [Gate("x90"), Gate("x90")] if gate == "X90" else [Gate("x")]


def _prepped(gate: str, prep: Param = PREP) -> list:
    return [Cond(prep, _prep_gates(gate))]


def _ro_axis(cfg, q, m, span_hz: float, points: int) -> Axis:
    """The readout-frequency sweep ±span around the config frequency, as the old k_vna cals built
    it: a code span either side of the centre code, delta-based Hz on the axis."""
    f0 = float(cfg[f"readout/{q}/freq"])
    c0 = units._freq_code(f0, m.params)
    span = abs(units._freq_code(span_hz, m.params))
    return Axis.freq_codes(c0 - span, c0 + span, points, m, ref=(f0, c0))


def _diagonal(series, q) -> tuple:
    """The confusion diagonal from the two prep reruns → (P(1|0), P(1|1), ½[P(0|0) + P(1|1)])."""
    p0, p1 = series[q].y[(0,)], series[q].y[(1,)]
    return p0, p1, 0.5 * ((1.0 - p0) + p1)


class ReadoutCalibration(Calibration):
    """Raw IQ per prep state in the ZERO demod frame → a trained Classifier, the demod phase that
    lands the |0>→|1> axis on +real, and the res-sign convention (spec 13 §5, spec 21). Two
    conditions: the clusters separate (> 0.5) AND the proposal works on-chip (`res_fidelity` >
    0.75)."""

    def __init__(self, cfg, qubits, shots=16, gate="X90"):
        super().__init__(cfg, qubits, shots)
        self.gate = gate
        self.classifier = {}

    def measure(self) -> Measure:
        return Measure.raw(phase=0.0)

    def params(self, q, m):
        return (PREP,)

    def sequence(self, q, x):
        return _prepped(self.gate)

    def run(self, drv) -> Result:
        m = base.socmap(drv)
        seqs = {q: self.sequence(q, ()) for q in self.qubits}
        series = Experiment(self.cfg, self.qubits, seqs, {q: () for q in self.qubits}, (PREP,),
                            self.measure(), self.shots, label="ReadoutCalibration").run(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            iq0, iq1 = series[q].y[(0,)], series[q].y[(1,)]
            clf = Classifier(iq0, iq1)
            self.classifier[q] = clf
            demod_phase = -math.atan2(*(clf.m0 - clf.m1)[::-1])   # |0>→|1> axis onto +real
            res_fid = res_fidelity(iq0, iq1, demod_phase)
            data[q] = {"iq0": iq0, "iq1": iq1, "separation": clf.separation,
                       "res_fidelity": res_fid, "means": clf.means, "sigmas": clf.sigmas,
                       "weights": clf.weights}
            proposal[f"readout/{q}/demod/phase"] = float(demod_phase)
            proposal[f"readout/{q}/res_sign"] = 1
            fit[q] = clf
            oks[q] = bool(clf.separation > 0.5 and res_fid > 0.75)
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, self.cfg,
                      f"ReadoutCalibration {self.qubits}", oks=oks)

    def plot(self, raw=False):
        """qcal's readout-calibration figure (calibration/readout.py:248-436, spec 21 §2.4): one
        panel per qubit — the pooled IQ shots under the trained classifier's decision regions
        (`contourf` of `classify` over a 200×200 mesh of the data bbox). Default draws the shots as
        a Greys hexbin; `raw=True` scatters them coloured by PREP label (the one place the labels
        appear — the regions come from the unsupervised fit). Differences from qcal, each for a
        reason: matplotlib imports lazily (headless CI imports cal without it); nothing is saved
        (no data manager — the notebooks show inline); the scatter alpha adapts to the shot count
        (qcal's 0.03 assumes thousands of shots; co-sim runs 16-64). Returns the figure."""
        import matplotlib.pyplot as plt          # lazy: headless CI must import cal without it
        from matplotlib.colors import ListedColormap
        from matplotlib.patches import Patch
        ncols = min(len(self.qubits), 4)
        nrows = -(-len(self.qubits) // ncols)
        fig, axes = plt.subplots(nrows, ncols, figsize=(5 * ncols, 4 * nrows),
                                 layout="constrained", squeeze=False)
        cmap = ListedColormap([(0.122, 0.467, 0.706), (1.0, 0.498, 0.055)])   # qcal's |0>/|1> pair
        for ax, q in zip(axes.ravel(), self.qubits):
            clf = self.classifier[q]
            xy = np.vstack([clf.iq0, clf.iq1])
            x_min, x_max = xy[:, 0].min() - 1, xy[:, 0].max() + 1
            y_min, y_max = xy[:, 1].min() - 1, xy[:, 1].max() + 1
            xx, yy = np.meshgrid(np.linspace(x_min, x_max, 200), np.linspace(y_min, y_max, 200))
            zz = clf.classify(np.column_stack([xx.ravel(), yy.ravel()])).reshape(xx.shape)
            if raw:
                prep = np.repeat([0, 1], [len(clf.iq0), len(clf.iq1)])
                ax.scatter(xy[:, 0], xy[:, 1], c=prep, cmap=cmap, vmin=0, vmax=1,
                           alpha=max(0.03, min(1.0, 100 / len(xy))))
            else:
                ax.hexbin(xy[:, 0], xy[:, 1], cmap="Greys", gridsize=75)
            ax.contourf(xx, yy, zz, cmap=cmap, alpha=0.15)
            ax.set_xlim(x_min, x_max)
            ax.set_ylim(y_min, y_max)
            ax.set_xlabel("I")
            ax.set_ylabel("Q")
            ax.ticklabel_format(axis="both", style="sci", scilimits=(0, 0))
            ax.text(0.05, 0.9, f"R{q}", size=15, transform=ax.transAxes)
            ax.legend(handles=[Patch(color=cmap(i), alpha=1.0) for i in (0, 1)], labels=[0, 1])
        for ax in axes.ravel()[len(self.qubits):]:
            ax.axis("off")
        return fig


class Separation(Calibration):
    """Readout frequency: the matched-pair frequency sweep, RAW, both prep states (two reruns),
    a Classifier per point, argmax of the fitted two-state SNR (spec 13 §5, spec 21)."""

    def __init__(self, cfg, qubits, span=2.5e6, points=31, shots=32, gate="X90"):
        super().__init__(cfg, qubits, shots)
        self.span, self.points, self.gate = float(span), int(points), gate

    def run(self, drv) -> Result:
        m = base.socmap(drv)
        axes = {q: (_ro_axis(self.cfg, q, m, self.span, self.points),) for q in self.qubits}
        seqs = {q: _prepped(self.gate) for q in self.qubits}
        meas = {q: Measure.raw(phase=None, meas=Meas(freq=axes[q][0])) for q in self.qubits}
        series = Experiment(self.cfg, self.qubits, seqs, axes, (PREP,), meas, self.shots,
                            label="Separation").run(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            npts = self.points
            i0 = series[q].y[(0,)].reshape(npts, self.shots, 2)
            i1 = series[q].y[(1,)].reshape(npts, self.shots, 2)
            clfs = [Classifier(i0[i], i1[i]) for i in range(npts)]
            seps = np.array([c.separation for c in clfs])
            mag0 = np.hypot(i0[:, :, 0].mean(1), i0[:, :, 1].mean(1))
            best = int(np.argmax(seps))
            freqs = axes[q][0].values
            data[q] = {"x": freqs, "y": seps, "mag0": mag0}
            proposal[f"readout/{q}/freq"] = float(freqs[best])
            fit[q] = clfs[best]
            oks[q] = bool(seps[best] > 0.5) and 0 < best < npts - 1
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, self.cfg,
                      f"Separation {self.qubits}", oks=oks)


class Resonator(Calibration):
    """Resonator spectroscopy (qcal's `Resonator`, spec 20 §8): the |0> readout response over an
    evenly spaced frequency list, summed coherently on-core (IQSUM). Proposes nothing."""

    def __init__(self, cfg, qubits, freqs, shots=64):
        super().__init__(cfg, qubits, shots)
        self.freqs = freqs if isinstance(freqs, dict) else {q: freqs for q in self.qubits}

    def run(self, drv) -> Result:
        m = base.socmap(drv)
        sh = max(0, (self.shots - 1).bit_length())
        fs = units.sample_rate(m.params)
        axes, meas = {}, {}
        for q in self.qubits:
            f = np.asarray(self.freqs[q], float)
            npts = len(f)
            assert 8 * npts <= m.mem_bytes // 2, \
                (f"a {npts}-point scan needs {8 * npts} B of IQ sums — over half the core's "
                 f"{m.mem_bytes} B RAM; split the range")
            step = np.diff(f)
            assert npts > 1 and np.allclose(step, step[0]), \
                "the on-core sweep is a linear ramp — `freqs` must be evenly spaced"
            c0 = units._freq_code(float(f[0]), m.params)
            axes[q] = (Axis.freq_codes(c0, c0 + round((f[-1] - f[0]) * (1 << 16) / fs), npts, m,
                                       fold=True, ref=(float(f[0]), c0)),)
            meas[q] = Measure.iqsum(sh, meas=Meas(freq=axes[q][0]))
        series = Experiment(self.cfg, self.qubits, {q: [] for q in self.qubits}, axes, (), meas,
                            self.shots, label="Resonator").run(drv)
        data, fit = {}, {}
        for q in self.qubits:
            iq = series[q].y * (1 << sh) / self.shots            # mean IQ per point
            data[q] = {"x": axes[q][0].values, "iq": iq, "mag": np.abs(iq)}
            fit[q] = None
        self.data, self.fit = data, fit
        return Result(True, data, fit, {}, self.cfg, f"Resonator {self.qubits}")


class Punchout(Calibration):
    """The readout punchout map (spec 14 F2): the |0> response over a freq × drive-amplitude grid,
    the amplitude a runtime param on the readout slot. Proposes nothing."""

    def __init__(self, cfg, qubits, amps=(0.05, 0.2, 0.5), span=2.5e6, points=31, shots=16):
        super().__init__(cfg, qubits, shots)
        self.amps = [float(a) for a in amps]
        self.span, self.points = float(span), int(points)

    def run(self, drv) -> Result:
        m = base.socmap(drv)
        amp = Param("amp", tuple(pack16(units._amp_code(a)) for a in self.amps))
        axes = {q: (_ro_axis(self.cfg, q, m, self.span, self.points),) for q in self.qubits}
        meas = {q: Measure.raw(phase=None, meas=Meas(freq=axes[q][0], amp=amp)) for q in self.qubits}
        series = Experiment(self.cfg, self.qubits, {q: [] for q in self.qubits}, axes, (amp,), meas,
                            self.shots, label="Punchout").run(drv)
        data, fit = {}, {}
        for q in self.qubits:
            rows = []
            for a in amp.values:
                z = series[q].y[(a,)].reshape(self.points, self.shots, 2).mean(1)
                rows.append(np.hypot(z[:, 0], z[:, 1]))
            data[q] = {"x": axes[q][0].values, "amps": np.array(self.amps, float), "mag": np.array(rows)}
            fit[q] = None
        self.data, self.fit = data, fit
        return Result(True, data, fit, {}, self.cfg, f"Punchout {self.qubits}")


class Fidelity(Calibration):
    """Assignment fidelity vs the readout DRIVE amplitude (qcal's knob, spec 13 §5): the amp swept
    on-core on the readout slot, COUNTS under the fixed hardware discriminator, one rerun per prep
    state, argmax of ½[P(0|0) + P(1|1)]."""

    def __init__(self, cfg, qubits, amp_span=0.005, points=11, shots=32, gate="X90"):
        super().__init__(cfg, qubits, shots)
        self.amp_span, self.points, self.gate = float(amp_span), int(points), gate

    def run(self, drv) -> Result:
        m = base.socmap(drv)
        herald = base.heralding(self.cfg)
        axes, meas = {}, {}
        for q in self.qubits:
            a = float(self.cfg[f"readout/{q}/amp"])
            lo, hi = max(0.0, a - self.amp_span), min(1.0, a + self.amp_span)
            assert lo < hi, f"readout amp sweep [{lo}, {hi}] is empty (amp={a}, span={self.amp_span})"
            axes[q] = Axis.amp(lo, hi, self.points)
            meas[q] = Measure.counts(herald=herald, meas=Meas(amp=axes[q]))
        series = Experiment(self.cfg, self.qubits, {q: _prepped(self.gate) for q in self.qubits},
                            {q: (axes[q],) for q in self.qubits}, (PREP,), meas, self.shots,
                            label="Fidelity").run(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            p0, p1, fidq = _diagonal(series, q)
            amps = axes[q].values
            best = int(np.argmax(fidq))
            data[q] = {"x": amps, "y": fidq, "p0": p0, "p1": p1}
            proposal[f"readout/{q}/amp"] = float(amps[best])
            fit[q] = None
            oks[q] = bool(fidq[best] > 0.75)
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, self.cfg, f"Fidelity {self.qubits}",
                      oks=oks)


class ReadoutFidelity(Calibration):
    """The confusion matrix at the calibrated readout, from the `res` bit under the fixed
    discriminator (two reruns of one single-point program); `n_levels=3` measures the 3×3 matrix
    host-side over RAW shots with a pre-trained ClassifierN, the |2> prep its own run (spec 14 F2)."""

    def __init__(self, cfg, qubits, shots=64, gate="X90", n_levels=2, classifier=None):
        assert n_levels in (2, 3), f"n_levels must be 2 or 3, got {n_levels}"
        assert n_levels == 2 or classifier is not None, \
            "a 3-level confusion needs a pre-trained ClassifierN (`classifier=`)"
        super().__init__(cfg, qubits, shots, classifier=classifier)
        self.gate, self.n_levels = gate, int(n_levels)

    def run(self, drv) -> Result:
        if self.n_levels == 3:
            return self._run_3level(drv)
        herald = base.heralding(self.cfg)
        seqs = {q: _prepped(self.gate) for q in self.qubits}
        series = Experiment(self.cfg, self.qubits, seqs, {q: () for q in self.qubits}, (PREP,),
                            Measure.counts(herald=herald), self.shots,
                            label="ReadoutFidelity").run(drv)
        data, fit, proposal = {}, {}, {}
        for q in self.qubits:
            p0, p1, fid = _diagonal(series, q)
            conf = np.array([[1.0 - p0[0], p0[0]], [1.0 - p1[0], p1[0]]])
            fidelity = float(fid[0])
            data[q] = {"confusion": conf, "fidelity": fidelity}
            fit[q] = conf
            proposal[f"readout/{q}/fidelity"] = fidelity
        self.data, self.fit = data, fit
        return Result(True, data, fit, proposal, self.cfg, f"ReadoutFidelity {self.qubits}")

    def _run_3level(self, drv) -> Result:
        clfs = self.classifier if isinstance(self.classifier, dict) \
            else {q: self.classifier for q in self.qubits}
        for q in self.qubits:
            assert f"qubit/{q}/EF/x/amp" in self.cfg, f"a |2> prep needs qubit/{q}/EF/x/* in the config"
        seqs = {q: _prepped(self.gate) for q in self.qubits}
        raw = Measure.raw(phase=0.0)
        s01 = Experiment(self.cfg, self.qubits, seqs, {q: () for q in self.qubits}, (PREP,), raw,
                         self.shots, label="ReadoutFidelity3").run(drv)
        ef = {q: [Gate("x90"), Gate("x90"), Gate("EF/x")] for q in self.qubits}   # GE π then EF π
        s2 = Experiment(self.cfg, self.qubits, ef, {q: () for q in self.qubits}, (), raw,
                        self.shots, label="ReadoutFidelity3_ef").run(drv)
        data, fit, proposal = {}, {}, {}
        for q in self.qubits:
            clouds = [s01[q].y[(0,)], s01[q].y[(1,)], s2[q].y]
            conf = np.array([[float(np.mean(clfs[q].classify(iq) == k)) for k in range(3)]
                             for iq in clouds])
            fidelity = float(np.mean(np.diag(conf)))
            data[q] = {"confusion": conf, "fidelity": fidelity, "iq": clouds}
            fit[q] = conf
            proposal[f"readout/{q}/cmat"] = conf.tolist()
            proposal[f"readout/{q}/fidelity"] = fidelity
        self.data, self.fit = data, fit
        return Result(True, data, fit, proposal, self.cfg, f"ReadoutFidelity3 {self.qubits}")


class Window(Calibration):
    """The readout timing sweep scored like Fidelity, argmax over `durs` (seconds) of one of three
    knobs: 'demod/dur' (the integration window), 'dur' (the drive length), 'demod/delay' (when the
    window opens). Compiled once at the LONGEST candidate; each point is a runtime param (a slot
    dur, seated, or the demod's play offset), per qubit its own list (spec 20 U3)."""

    KNOBS = ("demod/dur", "dur", "demod/delay")

    def __init__(self, cfg, qubits, durs=(160e-9, 400e-9), shots=32, gate="X90", knob="demod/dur"):
        assert knob in self.KNOBS, f"knob must be one of {self.KNOBS}, got {knob!r}"
        super().__init__(cfg, qubits, shots)
        self.durs = {q: [float(d) for d in (durs[q] if isinstance(durs, dict) else durs)]
                     for q in self.qubits}
        lengths = {len(v) for v in self.durs.values()}
        assert len(lengths) == 1, f"every qubit's `durs` list must be the same length, got {lengths}"
        self.gate, self.knob = gate, knob

    def _path(self, q) -> str:
        return f"readout/{q}/{self.knob}"

    def run(self, drv) -> Result:
        m = base.socmap(drv)
        cfg = self.cfg
        vals = {q: [batches(d, m) for d in self.durs[q]] for q in self.qubits}
        if self.knob == "demod/dur":
            for w in (w for row in vals.values() for w in row):
                assert w <= (1 << READOUT_MAX_WIN_LOG2), \
                    f"demod window {w} over the decoder no-overflow cap {1 << READOUT_MAX_WIN_LOG2}"
        worst = cfg.copy()                              # sizes the envelopes and the grid period
        for q in self.qubits:
            worst[self._path(q)] = max(self.durs[q])
        seated = self.knob != "demod/delay"             # slot durs are register words; the delay is plain
        knob = Param("knob", {q: tuple(pack16(v) if seated else v for v in vals[q])
                              for q in self.qubits})
        meas = Meas(**{{"demod/dur": "dur", "dur": "drive_dur", "demod/delay": "delay"}[self.knob]: knob})
        series = Experiment(worst, self.qubits, {q: _prepped(self.gate) for q in self.qubits},
                            {q: () for q in self.qubits}, (knob, PREP),
                            Measure.counts(herald=base.heralding(cfg), meas=meas), self.shots,
                            label="Window").run(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            fids = []
            for i in range(len(vals[q])):
                k = knob.value(q, i)
                p0, p1 = series[q].y[(k, 0)], series[q].y[(k, 1)]
                fids.append(float(0.5 * ((1.0 - p0[0]) + p1[0])))
            best = int(np.argmax(fids))
            data[q] = {"x": np.array(vals[q], float), "y": np.array(fids)}
            proposal[self._path(q)] = seconds(vals[q][best], m)
            fit[q] = None
            oks[q] = bool(fids[best] > 0.75)
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, cfg,
                      f"Window {self.qubits} {self.knob}", oks=oks)


def classifier3(cfg, qubits, drv, shots=64, prep2=("x90", "x90", "EF/x")):
    """{q: ClassifierN} from |0>/|1>/|2> RAW reference clouds captured in the zero-phase demod
    frame, every qubit in parallel per level: no gate, the X90·X90 π, then `prep2` (the GE π and
    the stored EF X — spec two-qubit/04 X5's 3-level classifier training)."""
    from riscq.cal.analysis.classifier import ClassifierN
    qs = base.qubits_list(qubits)
    clouds = {q: [] for q in qs}
    for gates in ((), ("x90", "x90"), tuple(prep2)):
        series = Experiment(cfg, qs, {q: [Gate(g) for g in gates] for q in qs}, {q: () for q in qs},
                            (), Measure.raw(), shots, label="classifier3").run(drv)
        for q in qs:
            clouds[q].append(series[q].y)
    return {q: ClassifierN(clouds[q]) for q in qs}
