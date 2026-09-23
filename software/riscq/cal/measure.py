"""Measure (specs/universal-cal/01 §5): how a sequence's readout is taken and decoded.

`Measure.counts / raw / levels / iqsum` name the result mode of `k_batched`; `tables()` builds
the reading core's ro/demod slots and the `MeasInfo` the sequence header needs (base.readout_tables
is the source of every code); `decode()` turns a core's `out` into the population / IQ array the
analyses consume, res-sign and herald pairs folded (base.population*). A `levels` measure
captures in the classifier's zero frame — the invariant the six old call sites restated."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from riscq.cal import base
from riscq.cal.batched import COUNTS, IQSUM, RAW
from riscq.cal.sequence import Meas, MeasInfo


@dataclass(frozen=True)
class Measure:
    mode: str                  # counts | raw | levels | iqsum
    herald: bool = False
    phase: float | None = None     # demod discrimination phase override (levels/raw: 0.0)
    win: float | None = None       # demod window override (seconds)
    classifiers: dict | None = None
    level: int = 2
    sh: int = 0
    host: bool = False             # put `out` in the host window (spec 22) — big raw captures
    meas: Meas = Meas()
    reads: tuple = ()              # the qubits that read out (default: the key itself); a pair
    #                                experiment reads both members, each on its own core

    @staticmethod
    def counts(herald: bool = False, meas: Meas = Meas(), reads: tuple = ()) -> "Measure":
        return Measure("counts", herald, meas=meas, reads=tuple(reads))

    @staticmethod
    def raw(phase: float | None = 0.0, meas: Meas = Meas(), host: bool = True) -> "Measure":
        return Measure("raw", phase=phase, meas=meas, host=host)

    @staticmethod
    def levels(classifiers: dict, level: int = 2, meas: Meas = Meas(), host: bool = False) -> "Measure":
        return Measure("levels", phase=0.0, classifiers=classifiers, level=level, meas=meas,
                       host=host)

    @staticmethod
    def iqsum(sh: int, meas: Meas = Meas()) -> "Measure":
        return Measure("iqsum", sh=sh, meas=meas)

    @property
    def kernel_mode(self) -> int:
        return {"counts": COUNTS, "raw": RAW, "levels": RAW, "iqsum": IQSUM}[self.mode]

    def out_size(self, npts: int, shots: int) -> int:
        if self.mode == "counts":
            return 2 * npts if self.herald else npts
        if self.mode == "iqsum":
            return 2 * npts
        return 2 * npts * shots

    def tables(self, cfg, q, m, tables, core=None) -> MeasInfo:
        """Register qubit q's ro/demod slots in `tables` (riscq.cal.gates.Tables) and return the
        header's MeasInfo. Codes are base.readout_tables' (the config's readout/{q}/* in physical
        units). `core` puts the slots on another core's readout channels — a non-reading core
        (a coupler, a spectator) carries them uninitialised-never-fired so its init preamble is
        the readers' and the cores' grids stay aligned."""
        ro, demod, code, win, ddly = base.readout_tables(cfg, q, m, phase=self.phase, win=self.win)
        c = q if core is None else core
        ro_ch, demod_ch = base.ro_ch(m, c), base.demod_ch(m, c)
        tables.add_table(ro_ch, "meas", ro.pulses["meas"], ro.freq_hz)
        tables.add_table(demod_ch, "sq", demod.pulses["sq"], 0.0)
        return MeasInfo(ro_ch, demod_ch, code, win, ddly, self.meas)

    def decode(self, out: np.ndarray, q, npts: int, shots: int, sign: int = 1):
        """A core's `out` → P (counts / levels), IQ shots (raw: (npts·shots, 2)), or the complex
        per-point sums (iqsum)."""
        if self.mode == "counts":
            return (base.population_heralded(out, sign) if self.herald
                    else base.population(out, shots, sign))
        if self.mode == "levels":
            return base._levels_pop(out, npts, shots, self.classifiers[q], self.level)
        if self.mode == "raw":
            return np.asarray(out, dtype=float).reshape(npts * shots, 2)
        z = np.asarray(out, dtype=float).reshape(npts, 2)
        return z[:, 0] + 1j * z[:, 1]
