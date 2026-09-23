"""Calibration (specs/universal-cal/01 §7.2): the base every calibration class is ~25 lines on.

A subclass gives `axes(q, m)`, `sequence(q, x)` (the swept block; `prep`/`unprep` wrap it) and
`analyze(q, series, m) -> Estimate`; `run(drv)` is the one template: build the Experiment, run it,
analyze per key, return the unchanged `Result` contract (`ok/oks/data/fit/proposal/apply/plot`).
`readout` selects the Measure: 'counts' (the hardware res bit), 'mapback' (counts, with the
un-prep gates appended — spec 24 §3.7) or 'classifier' (raw IQ + a per-qubit ClassifierN)."""

from __future__ import annotations

from dataclasses import dataclass, field

from riscq.cal import base
from riscq.cal.base import Result
from riscq.cal.experiment import Experiment, Series
from riscq.cal.measure import Measure
from riscq.cal.sequence import Gate


@dataclass
class Estimate:
    ok: bool
    fit: object = None
    proposal: dict = field(default_factory=dict)
    fallback: bool = False
    data: dict = field(default_factory=dict)     # extra per-key plot data (sig, fringes, …)


class Calibration:
    def __init__(self, cfg, qubits, shots, prep=(), unprep=(), readout="counts", classifier=None):
        if readout not in ("counts", "mapback", "classifier"):
            raise ValueError(f"readout must be counts | mapback | classifier, got {readout!r}")
        self.cfg, self.qubits, self.shots = cfg, base.qubits_list(qubits), int(shots)
        self.prep, self.unprep, self.readout = tuple(prep), tuple(unprep), readout
        self.classifier = classifier
        self.data, self.fit = {}, {}

    # ── hooks ──

    def axes(self, q, m) -> tuple:
        return ()

    def params(self, q, m) -> tuple:
        return ()

    def sequence(self, q, x) -> list:
        raise NotImplementedError

    def analyze(self, q, s: Series, m) -> Estimate:
        raise NotImplementedError

    def label(self) -> str:
        return f"{type(self).__name__} {self.qubits}"

    def measure(self) -> Measure:
        if self.readout == "classifier":
            clf = self.classifier if isinstance(self.classifier, dict) \
                else {q: self.classifier for q in self.qubits}
            return Measure.levels(clf, level=2)
        return Measure.counts(herald=base.heralding(self.cfg))

    # ── the template ──

    def _full(self, q, x, sequence=None) -> list:
        tail = self.unprep if self.readout == "mapback" else ()
        body = (self.sequence if sequence is None else sequence)(q, x)
        return [Gate(s) for s in self.prep] + list(body) + [Gate(s) for s in tail]

    def _series(self, drv, sequence=None, params=None) -> dict:
        """Build + run the Experiment (the template's first half) → {key: Series}. `sequence(q,
        x)` / `params` override the hooks for a class that runs several experiments in one
        `run` (a ladder of passes, the two roles of a pair, the two circuits of an RPE)."""
        m = self.m = base.socmap(drv)
        params = tuple(self.params(self.qubits[0], m) if params is None else params)
        axes = {q: tuple(self.axes(q, m)) for q in self.qubits}
        seqs = {q: self._full(q, axes[q], sequence) for q in self.qubits}
        exp = Experiment(self.cfg, self.qubits, seqs, axes, params, self.measure(), self.shots,
                         label=type(self).__name__)
        return exp.run(drv)

    def run(self, drv) -> Result:
        series = self._series(drv)
        m = self.m
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            s = series[q]
            est = self.analyze(q, s, m)
            data[q] = {"x": s.x[0] if s.x else None, "y": s.y, **est.data}
            fit[q], oks[q] = est.fit, bool(est.ok)
            if est.ok:
                proposal.update(est.proposal)
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, self.cfg, self.label(), oks=oks)
