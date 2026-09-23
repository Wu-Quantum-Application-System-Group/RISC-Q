"""Experiment (specs/universal-cal/01 §7.1): the ONE runner. Per key (a qubit label): resolve
the sequence's gates, compile it (riscq.cal.sequence), size the grid period ONCE from its
longest point (R9), compile `k_batched` for every core it touches with the generated header,
then `rq.setup` + one `rq.rerun` per point of the Params' cartesian product, decoding each
reading core's `out` through the Measure. What 31 `run()`s did by hand."""

from __future__ import annotations

import itertools
from dataclasses import dataclass, field

import numpy as np

from riscq import run as rq
from riscq.cal import base
from riscq.cal.axes import Param
from riscq.cal.batched import NONE, k_batched
from riscq.cal.measure import Measure
from riscq.cal.sequence import compile_sequence, emit_header
from riscq.lang import Array, Group, compile_kernel
from riscq.pulses import units


def _ident(label: str) -> str:
    """The label as a C-identifier-safe file-name piece (it names the include, so a program's
    c_source says which experiment it is)."""
    return "".join(c if c.isalnum() or c == "_" else "_" for c in label) + "_" if label else ""


@dataclass
class Series:
    """One key's data: the exact axis values (`x[i]`, physical; `codes[i]`, ints) and `y` — an
    array, or `{param values: array}` when the experiment reran over Params."""

    key: object
    x: list = field(default_factory=list)
    codes: list = field(default_factory=list)
    y: object = None
    params: tuple = ()


class Experiment:
    def __init__(self, cfg, keys, sequences: dict, axes: dict, params: tuple, measure,
                 shots: int, label: str = ""):
        self.cfg, self.keys = cfg, list(keys)
        self.sequences, self.axes = sequences, axes
        self.params = tuple(params)
        # one Measure for every key, or {key: Measure} when each qubit's readout carries its own
        # swept axis (the readout-frequency cals) — the mode / herald must agree across keys
        self.measures = measure if isinstance(measure, dict) else {q: measure for q in self.keys}
        self.shots, self.label = int(shots), label
        self.progs: dict = {}
        self.compiled: dict = {}

    def _pairs(self, axes: tuple, values: tuple) -> dict:
        """The kernel's x0/dx0/x1/dx1 for this rerun (the coupled detuning pair rides on x1)."""
        p = {"x0": 0, "dx0": 0, "x1": 0, "dx1": 0}
        if axes:
            p["x0"], p["dx0"] = axes[0].x0, axes[0].dx
        if len(axes) > 1:
            p["x1"], p["dx1"] = axes[1].x0, axes[1].dx
        elif axes and axes[0].coupled is not None:
            det = axes[0].coupled
            if isinstance(det, Param):
                dc = values[self.params.index(det)]
            else:
                dc = units._freq_code(float(det), self._m.params)
            p["x1"], p["dx1"] = axes[0].phase_pair(int(dc))
        return p

    def run(self, drv, before=None, after=None) -> dict:
        """Compile, load and run every rerun; `before(vals)` / `after(vals)` are called around
        each rerun with the Params' values (the exact-population instrument re-plants the co-sim
        model before a pass and reads `model_state()` after it — xcheck/exact.py)."""
        progs, signs, timeout = self.compile(drv)
        m = self._m
        series = {q: Series(q, [a.values for a in self.compiled[q][1]],
                            [a.codes for a in self.compiled[q][1]], {}, self.params)
                  for q in self.keys}
        for idx in itertools.product(*(range(p.n) for p in self.params)):
            par, vals = {}, {}
            for q in self.keys:
                comp, axes, rcore, npts = self.compiled[q]
                vals[q] = tuple(p.value(q, i) for p, i in zip(self.params, idx))
                words = self._pairs(axes, vals[q])
                words.update({f"r{i}": int(v) for i, v in enumerate(vals[q])})
                for core in set(comp.cores()) | set(rcore):
                    par[core] = {k: v for k, v in words.items() if k in progs[core].params}
            if before is not None:
                before(vals[self.keys[0]])
            out = rq.rerun(drv, m, progs, params=par, results=["out"], timeout=timeout)
            if after is not None:
                after(vals[self.keys[0]])
            for q in self.keys:
                comp, axes, rcore, npts = self.compiled[q]
                y = {r: self.measures[q].decode(out[c]["out"], r, npts, self.shots, signs[q][r])
                     for c, r in rcore.items()}
                series[q].y[vals[q]] = y if len(y) > 1 else next(iter(y.values()))
        if not self.params:
            for s in series.values():
                s.y = s.y[()]
        return series

    def compile(self, drv) -> tuple:
        """Compile every core's program and `rq.setup` them → (progs, res-signs, timeout). `run`
        is this plus the rerun loop; a test that drives the reruns itself (tests/probe.py) calls
        this and then reruns `progs` with its own params."""
        m = self._m = base.socmap(drv)
        cfg = self.cfg
        herald = bool(next(iter(self.measures.values())).herald)
        progs, timeout, signs, plans = {}, 0, {}, {}
        for q in self.keys:
            meas = self.measures[q]
            axes = tuple(self.axes[q])
            if len(axes) > 2 or (len(axes) == 2 and axes[0].coupled is not None):
                raise ValueError("at most two on-core axes (a coupled detuning takes the second)")
            reads = tuple(meas.reads) or (q,)                 # the qubits that read out
            qref = q if isinstance(q, int) else reads[0]      # relative gate specs resolve on it
            from riscq.cal.gates import Tables
            tables = Tables()
            minfo = {r: meas.tables(cfg, r, m, tables) for r in reads}
            comp = compile_sequence(cfg, qref, self.sequences[q], m, axes, self.params,
                                    minfo[reads[0]])
            for k, v in tables._pulses.items():          # the readout slots join the sequence's tables
                comp.tables._pulses.setdefault(k, {}).update(v)
                comp.tables._info.setdefault(k, tables._info[k])
                comp.tables._carrier.setdefault(k, tables._carrier[k])
            rcore = {base.gate_ch(m, r).core: r for r in reads}
            cores = sorted(set(comp.cores()) | set(rcore))
            for core in cores:                           # a non-reading core carries the readout
                if core not in rcore:                    # slots too (never fired)
                    meas.tables(cfg, reads[0], m, comp.tables, core=core)
            _pad_tables(comp.tables, cores)
            plans[q] = (meas, axes, reads, minfo, comp, rcore, cores)
        # ONE barrier group of every core the experiment runs: all grids start from the same
        # released time (k_batched's docstring)
        grp = Group(sorted({c for plan in plans.values() for c in plan[6]}), id=0)
        for q, (meas, axes, reads, minfo, comp, rcore, cores) in plans.items():
            first = minfo[reads[0]]
            seq_len = comp.seq_len
            period = base.grid_period(base.relax_batches(cfg, m), seq_len, first.win, first.ddly,
                                      herald=herald)
            hoff = base.herald_offset(seq_len, first.ddly) if herald else 0
            npts = axes[0].n if axes else 1
            # unused axes / params are bound to 0 at compile time: a runtime param is a RAM load
            # in the shot path, and the old kernels' posting margins are a few batches — a per-point
            # readout retune lands ~67 batches after it is issued, the old readout kernels issue it
            # 71 before the play, and one load costs ~6 (03-plan §3.1)
            fixed = {}
            if len(axes) < 2 and not (axes and axes[0].coupled is not None):
                fixed.update(x1=0, dx1=0)
            if not axes:
                fixed.update(x0=0, dx0=0)
            fixed.update({f"r{i}": 0 for i in range(len(self.params), 4)})
            for core in cores:
                r = rcore.get(core)
                hdr = emit_header(comp, core, minfo[r] if r is not None else None, self.label)
                progs[core] = compile_kernel(
                    k_batched, m, core=core, grp=grp, tables=comp.tables.for_core(core),
                    include=[(f"seq_{_ident(self.label)}core{core}.h", hdr)],
                    out=Array(meas.out_size(npts, self.shots), host=meas.host) if r is not None
                    else Array(1),
                    npts=npts, shots=self.shots, period=period,
                    mode=meas.kernel_mode if r is not None else NONE,
                    herald=int(herald and r is not None), hoff=hoff, sh=meas.sh, **fixed)
            self.compiled[q] = (comp, axes, rcore, npts)
            signs[q] = {r: base.res_sign(cfg, r) for r in reads}
            timeout = max(timeout, base.batch_timeout(npts * self.shots * period))
        self.progs = progs
        rq.setup(drv, m, progs)
        return progs, signs, timeout


def _pad_tables(tables, cores) -> None:
    """Equal slot counts per channel name across the experiment's cores: a core's init preamble
    is one store per slot, so an unequal count shifts its `now() + period` grid by tens of batches
    against the others' (the spectator finding of spec 04 — the old drive tables carried a
    never-fired pad slot for the same reason). Pads copy the channel's last slot."""
    by_name: dict = {}
    for (c, idx), pulses in tables._pulses.items():
        if c in cores:
            by_name.setdefault(tables._info[(c, idx)].name, []).append(pulses)
    for group in by_name.values():
        n = max(len(t) for t in group)
        for t in group:
            last = list(t.values())[-1]
            for i in range(len(t), n):
                t[f"pad{i}"] = last
