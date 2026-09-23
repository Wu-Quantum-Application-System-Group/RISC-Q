"""The X6Y3 process (spec 13 §8's order; spec 14's two-qubit order): the qcal/QubiC
autocalibration as a host script over the universal classes — readout chain → GE chain →
[EF] → [two-qubit chain per pair] → [RPE polish]. Ordinary host python: the ordering, retries and
conditional re-runs need no DSL because they run on the host between on-core calibrations."""

from __future__ import annotations

from riscq.cal.cals import (JAZZ, CZRPE, Amplitude, CZAmplitude, CZFrequency, CZSweep, EFAmplitude,
                            EFFrequency, EFPhase, Fidelity, Frequency, LocalPhases, Phase,
                            ReadoutCalibration, ReadoutFidelity, RelativePhase, RPEAmplitude,
                            RPEFrequency, RPEPhase, Separation)
from riscq.cal.process.step import step


def calibration_x6y3(cfg, qubits, drv, apply=True, verbose=False) -> list:
    """The Calibration_X6Y3 flow: ReadoutCalibration → Separation → Fidelity → ReadoutFidelity →
    Frequency → Amplitude (coarse) → Amplitude (fine, relative) → Phase, over `qubits` (a bare int
    is one qubit) SIMULTANEOUSLY — every step calibrates all cores in one run (spec 13 §8). Each
    cell is `r = Cal(...).run(drv); r.apply()`. The fine (n_gates=4) amplitude sweep is
    `relative_amp`, 0.7–1.3× whatever the coarse step just wrote. Returns the list of Results.
    `Window` (the demod-window sweep) is OURS, not qcal's, so it is not in this chain (spec 13 §5).

    The point/shot counts are the lighter end of each cal's range: a chained autocal only needs
    each step to IMPROVE its estimate (the next step refines it), not the sub-1 % single-shot
    precision the standalone cals target."""
    results: list = []
    for cal in (ReadoutCalibration(cfg, qubits),
                Separation(cfg, qubits, points=7, shots=16),   # two prep reruns: 2 x the shots
                Fidelity(cfg, qubits, points=5, shots=16),     # the readout-AMP sweep (qcal's knob)
                ReadoutFidelity(cfg, qubits, shots=24),
                Frequency(cfg, qubits, points=11, shots=64),
                Amplitude(cfg, qubits, n_gates=1, points=15, shots=120),          # coarse
                Amplitude(cfg, qubits, n_gates=4, amp_span=(0.7, 1.3), relative_amp=True,
                          shots=120),                                             # fine, relative
                Phase(cfg, qubits, points=7, shots=64, relative_phase=True)):
        step(cal, drv, results, apply=apply, verbose=verbose)
    return results


def calibration_ef(cfg, qubits, drv, classifier, apply=True, verbose=False) -> list:
    """The EF chain (spec 14 §3): EFAmplitude → EFFrequency → EFPhase on the 3-level classifier."""
    results: list = []
    for cal in (EFAmplitude(cfg, qubits, classifier=classifier, shots=64),
                EFFrequency(cfg, qubits, classifier=classifier, shots=64),
                EFPhase(cfg, qubits, classifier=classifier, shots=64)):
        step(cal, drv, results, apply=apply, verbose=verbose)
    return results


def calibration_pair(cfg, pair, drv, apply=True, verbose=False, rpe=False) -> list:
    """The two-qubit chain for one pair (spec 14's order): JAZZ → CZSweep → CZFrequency →
    [RelativePhase, drive form] → CZAmplitude → LocalPhases → [CZRPE]."""
    from riscq.cal.cz import cz_coupler_form
    cals = [JAZZ(cfg, pair), CZSweep(cfg, pair), CZFrequency(cfg, pair)]
    if not cz_coupler_form(cfg, pair):
        cals.append(RelativePhase(cfg, pair))
    cals += [CZAmplitude(cfg, pair), LocalPhases(cfg, pair)]
    if rpe:
        cals.append(CZRPE(cfg, pair))
    results: list = []
    for cal in cals:
        step(cal, drv, results, apply=apply, verbose=verbose)
    return results


def calibration_rpe(cfg, qubits, drv, apply=True, verbose=False) -> list:
    """The single-qubit RPE polish: RPEFrequency → RPEAmplitude → RPEPhase."""
    results: list = []
    for cal in (RPEFrequency(cfg, qubits), RPEAmplitude(cfg, qubits), RPEPhase(cfg, qubits)):
        step(cal, drv, results, apply=apply, verbose=verbose)
    return results
