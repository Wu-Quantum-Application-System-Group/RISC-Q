"""Robust phase estimation on the base (specs/universal-cal/02 §2/§4; spec 14 F5): the ladder
depth is a `Param`, so one image serves every rung and the deepest rung sizes the ONE grid
period every depth shares (R9 — the state-prep systematic `the old RPEFrequency._periods`
measured). The estimators (`idle_angles`, `x90_angles`, `cz_angles`, …) are `riscq.cal.analysis.rpe`'s."""

from __future__ import annotations

import math

import numpy as np

from riscq.cal.axes import Param, seated_phase
from riscq.cal.base import Result, batches, train_step
from riscq.cal.calibration import Calibration
from riscq.cal.cals.twoqubit import PREP, QUAD, PairCalibration
from riscq.cal.analysis.rpe import (CZ_STATE_PAIRS, X90_TARGET, RPEBranchError, cz_angles, damped_update,
                           freq_error_hz, idle_angles, vz_correction, x90_angles,
                           x90_direct_angles)
from riscq.cal.sequence import Gate, Idle, Repeat, Rz, Train
from riscq.cal.cz import _cz_local_set, _local_phase, pair_key

HPI = math.pi / 2


def _n(P, shots) -> int:
    return int(round(float(np.atleast_1d(P)[0]) * shots))


class _Ladder(Calibration):
    """The single-qubit RPE base: ascending `depths`, a damped proposal."""

    def __init__(self, cfg, qubits, depths, shots, gain, max_step):
        super().__init__(cfg, qubits, shots)
        self.depths = tuple(int(d) for d in depths)
        self.gain, self.max_step = float(gain), max_step
        if min(self.depths) < 1:
            raise ValueError(f"depths must be >= 1, got {self.depths}")
        if sorted(self.depths) != list(self.depths):
            raise ValueError(f"depths must be ascending, got {self.depths}")
        self.angles = {}

    def _result(self, data, fit, proposal, oks) -> Result:
        self.data, self.fit = data, fit
        return Result(all(oks.values()), data, fit, proposal, self.cfg, self.label(), oks=oks)


class RPEFrequency(_Ladder):
    """1Q qubit frequency by RPE (qcal `gate='I'`; the old RPEFrequency's docstring): a
    Ramsey whose idle is `depth · t_idle`, the closing X90 at the four balanced quadrature phases
    (`QUADRATURES`), pyRPE on the counts, `qubit/{q}/freq` moved by a damped clip of the recovered
    detuning. `Frequency` first: RPE refines, it does not acquire."""

    QUADRATURES = (("cos", 0.0, math.pi), ("sin", -math.pi / 2, math.pi / 2))

    def __init__(self, cfg, qubits, t_idle=100e-9, depths=(1, 2, 4, 8, 16), shots=256, gain=1.0,
                 max_step=None):
        super().__init__(cfg, qubits, depths, shots, gain,
                         float(max_step) if max_step is not None else 0.5 / float(t_idle))
        self.t_idle = float(t_idle)
        self.recovered_detuning = {}

    def params(self, q, m):
        self._depth = Param("depth", self.depths)
        closes = [c for _, plus, minus in self.QUADRATURES for c in (plus, minus)]
        self._close = Param("close", tuple(seated_phase(c) for c in closes))
        return (self._depth, self._close)

    def sequence(self, q, x):
        return [Gate("x90"), Idle(self._depth * batches(self.t_idle, self.m)),
                Gate("x90", phase=self._close)]

    def run(self, drv) -> Result:
        series = self._series(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            y = series[q].y
            counts = {name: {d: (_n(y[(d, seated_phase(plus))], self.shots),
                                 _n(y[(d, seated_phase(minus))], self.shots)) for d in self.depths}
                      for name, plus, minus in self.QUADRATURES}
            carrier = float(self.cfg[f"qubit/{q}/freq"])
            data[q] = {"depths": self.depths, "counts": counts, "carrier": carrier}
            oks[q] = False
            try:
                angles = idle_angles(counts["cos"], counts["sin"], self.shots, self.depths)
            except RPEBranchError as exc:
                data[q]["error"] = str(exc)
                continue
            self.angles[q] = fit[q] = angles
            data[q]["contrast"] = angles.contrast
            detuning = freq_error_hz(angles.trusted["Z"], self.t_idle)   # the CARRIER's error
            self.recovered_detuning[q] = detuning
            proposal[f"qubit/{q}/freq"] = damped_update(carrier, -detuning, gain=self.gain,
                                                        max_step=self.max_step)
            oks[q] = True
        return self._result(data, fit, proposal, oks)


class RPEAmplitude(_Ladder):
    """1Q X90 amplitude by RPE (the direct half of qcal's `gate='X90'`): the X90 train at
    `depth + {0, 1, 2, 3}` gates (`TRAINS`, the balanced closes) on the paced train grid; the
    recovered per-gate angle corrects `qubit/{q}/x90/amp` multiplicatively."""

    TRAINS = (("cos", 2, 0), ("sin", 1, 3))

    def __init__(self, cfg, qubits, depths=(1, 2, 4, 8), shots=256, gain=1.0, max_step=0.25):
        super().__init__(cfg, qubits, depths, shots, gain, float(max_step))
        self.recovered_angle = {}

    def _ns(self) -> tuple:
        return tuple(sorted({d + off for d in self.depths for _, plus, minus in self.TRAINS
                             for off in (plus, minus)}))

    def params(self, q, m):
        self._ng = Param("n", self._ns())
        return (self._ng,)

    def sequence(self, q, x):
        return [Train(Gate("x90"), self._ng)]

    def _counts(self, y) -> dict:
        return {name: {d: (_n(y[(d + plus,)], self.shots), _n(y[(d + minus,)], self.shots))
                       for d in self.depths} for name, plus, minus in self.TRAINS}

    def run(self, drv) -> Result:
        series = self._series(drv)
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            counts = self._counts(series[q].y)
            path = f"qubit/{q}/x90/amp"
            amp = float(self.cfg[path])
            data[q] = {"depths": self.depths, "counts": counts, "amp": amp}
            oks[q] = False
            try:
                angles = x90_direct_angles(counts["cos"], counts["sin"], self.shots, self.depths)
            except RPEBranchError as exc:
                data[q]["error"] = str(exc)
                continue
            self.angles[q] = fit[q] = angles
            data[q]["contrast"] = angles.contrast
            theta = angles.trusted["X"]
            self.recovered_angle[q] = theta
            if theta > 0:                                     # a non-positive angle is not a gate
                proposal[path] = float(np.clip(
                    damped_update(amp, X90_TARGET / theta - 1.0, gain=self.gain,
                                  max_step=self.max_step, multiplicative=True), 0.0, 1.0))
                oks[q] = True
        return self._result(data, fit, proposal, oks)


class RPEPhase(RPEAmplitude):
    """1Q X90 drive phase (axis tilt) by RPE (the interleaved half of qcal's `gate='X90'`,
    the old RPEPhase's docstring): the direct trains (`TRAINS`) plus the interleaved echo —
    `depth` blocks of `Z90 · X90 · X90 · Z90 · Z90 · X90 · X90 · Z90` on the train grid, then
    `tail` closing X90s (`TAILS`) — recombined by `x90_angles`; the recovered tilt shifts both
    slots of `qubit/{q}/x90/vz`. Both circuits share the grid of the longer (the echo's)."""

    TAILS = (("cos", 2, 0), ("sin", 3, 1))

    def __init__(self, cfg, qubits, depths=(1, 2, 4, 8), shots=256, gain=1.0, max_step=0.25):
        super().__init__(cfg, qubits, depths, shots, gain, max_step)
        self.recovered_tilt = {}

    def _echo_params(self):
        self._depth = Param("depth", self.depths)
        self._tail = Param("tail", (0, 1, 2, 3))
        return (self._depth, self._tail)

    def _step(self, q) -> tuple:
        from riscq.cal.gates import resolve
        d = resolve(self.cfg, q, "x90", self.m).dur
        return d, train_step(d)

    def _echo(self, q, x):
        d, step = self._step(q)
        half = [Rz("x90", HPI), Gate("x90"), Idle(step - d), Gate("x90"), Idle(step - d),
                Rz("x90", HPI)]
        return [Repeat(self._depth, half + half), Train(Gate("x90"), self._tail)]

    def sequence(self, q, x):
        """The direct train, padded ahead to the echo's longest rung so both circuits size the
        SAME grid period (R9; the per-circuit period bias the old RPEPhase._period names)."""
        d, step = self._step(q)
        longest = 4 * max(self.depths) + 3 - max(self._ns())
        return [Idle(longest * step), Train(Gate("x90"), self._ng)]

    def run(self, drv) -> Result:
        direct = self._series(drv)
        echo = self._series(drv, sequence=self._echo, params=self._echo_params())
        data, fit, proposal, oks = {}, {}, {}, {}
        for q in self.qubits:
            dc, ye = self._counts(direct[q].y), echo[q].y
            ec = {name: {d: (_n(ye[(d, plus)], self.shots), _n(ye[(d, minus)], self.shots))
                         for d in self.depths} for name, plus, minus in self.TAILS}
            path = f"qubit/{q}/x90/vz"
            pair = [float(v) for v in self.cfg.get(path, [0.0, 0.0])]
            data[q] = {"depths": self.depths, "counts": dc, "echo": ec, "vz": pair}
            oks[q] = False
            try:
                angles = x90_angles(dc["cos"], dc["sin"], ec["cos"], ec["sin"], self.shots, self.depths)
            except RPEBranchError as exc:
                data[q]["error"] = str(exc)
                continue
            self.angles[q] = fit[q] = angles
            data[q]["contrast"] = angles.contrast
            shift = vz_correction(angles.trusted["X"], angles.trusted["Z"])
            self.recovered_tilt[q] = shift
            phi = damped_update(0.5 * (pair[0] + pair[1]), shift, gain=self.gain, max_step=self.max_step)
            proposal[path] = [phi, phi]
            oks[q] = True
        return self._result(data, fit, proposal, oks)


class CZRPE(PairCalibration):
    """CZ ZZ / ZI / IZ generator angles by RPE (qcal `gate='CZ'`; the old CZRPE's
    docstring, incl. the gap-bias caveat): three conditional-Ramsey ladders — (0, 1) / (2, 3)
    Ramsey the TARGET with the control in |0> / |1>, (3, 1) the CONTROL with the target in |1>
    (a second experiment with the roles swapped) — each rung `depth` CZs, read at the balanced
    close pairs (`QUADRATURES`, k_cz_cond's quad codes); `cz_angles` inverts them. The local
    entries are SHIFTED by the damped IZ / ZI errors; the ZZ error is reported, not written."""

    QUADRATURES = (("cos", 0, 2), ("sin", 3, 1))
    RUNGS = {(0, 1): (False, 0), (2, 3): (False, 1), (3, 1): (True, 1)}

    def __init__(self, cfg, pair, depths=(1, 2, 4), shots=128, gain=0.5, max_step=0.25):
        super().__init__(cfg, pair, shots)
        self.depths = tuple(int(d) for d in depths)
        self.gain, self.max_step = float(gain), float(max_step)
        if min(self.depths) < 1:
            raise ValueError(f"depths must be >= 1, got {self.depths}")
        if sorted(self.depths) != list(self.depths):
            raise ValueError(f"depths must be ascending, got {self.depths}")
        self.angles = {}

    def params(self, q, m):
        self._depth = Param("depth", self.depths)
        return (self._depth, PREP, QUAD)

    def run(self, drv) -> Result:
        cfg, pair, pk = self.cfg, self.pair, pair_key(self.pair)
        ctrl, tgt = pair
        counts = {sp: {"cos": {}, "sin": {}} for sp in CZ_STATE_PAIRS}
        for ramsey_ctrl in (False, True):
            ramsey = ctrl if ramsey_ctrl else tgt
            y = self._series(drv, sequence=lambda q, x: self._cond(self._depth, ramsey))[pair].y
            for sp, (rc, prep) in self.RUNGS.items():
                if rc != ramsey_ctrl:
                    continue
                for name, plus, minus in self.QUADRATURES:
                    for d in self.depths:
                        counts[sp][name][d] = tuple(_n(y[(d, prep, QUAD.values[k])][ramsey], self.shots)
                                                    for k in (plus, minus))
        data, fit, prop, ok = {pair: {"depths": self.depths, "counts": counts}}, {}, {}, False
        try:
            angles = cz_angles({sp: (counts[sp]["cos"], counts[sp]["sin"]) for sp in CZ_STATE_PAIRS},
                               self.shots, self.depths)
        except RPEBranchError as exc:
            data[pair]["error"] = str(exc)
        else:
            self.angles[pair] = fit[pair] = angles
            data[pair]["contrast"] = angles.contrast
            data[pair]["ladders"] = angles.ladders
            err = angles.trusted_error
            data[pair]["zz_error"] = err["ZZ"]                 # reported, not written
            prop = {f"two_qubit/{pk}/CZ/pulse": _cz_local_set(
                cfg, pair,
                damped_update(_local_phase(cfg, pair, ctrl), err["ZI"], gain=self.gain,
                              max_step=self.max_step),
                damped_update(_local_phase(cfg, pair, tgt), err["IZ"], gain=self.gain,
                              max_step=self.max_step))}
            ok = True
        self.data, self.fit = data, fit
        return Result(ok, data, fit, prop, cfg, self.label(), oks={pair: ok})
