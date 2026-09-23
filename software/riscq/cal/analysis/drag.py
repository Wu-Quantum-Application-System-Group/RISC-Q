"""Fast-DRAG envelope optimisation (spec 06): the EF spectral weight and the grid search, moved from
riscq.cal.drag at universal-cal V6 (the `Leakage` class lives in riscq.cal.cals.single)."""

from __future__ import annotations

import numpy as np

from riscq.cal.base import batches, gate_ch, qubit_freq
from riscq.pulses import envelopes

N_GRID = tuple(range(2, 11))                                   # qcal's default sweeps
W_GRID = (0.1, 0.3, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0)
PAD = 1000                                                     # zero-padding each side, as qcal


def ef_spectral_weight(cfg, q, m, name="x90", kwargs=None) -> float:
    """qcal's leakage score (optimization/pulse.py:57-72): |FFT(envelope)| at the EF transition.

    The envelope is built on the gate channel's stored-sample grid, zero-padded `PAD` samples each
    side (which interpolates the spectrum finely enough to land on the transition), transformed, and
    read at the bin nearest the EF DETUNING f_EF − f_GE — qcal shifts its frequency axis by f_GE and
    looks up f_EF, which is the same bin. Lower is less leakage: that spectral component is what
    drives |1> → |2>."""
    path = f"qubit/{q}/{name}"
    ch = gate_ch(m)
    n = batches(cfg[f"{path}/dur"], m) * ch.samples_per_line
    rate = ch.samples_per_line * m.params.dsp_freq_hz
    env = envelopes.build(cfg[f"{path}/env"], n,
                          rate, **(cfg[f"{path}/kwargs"] if kwargs is None else kwargs))
    spec = np.abs(np.fft.fftshift(np.fft.fft(np.pad(env, (PAD, PAD)))))
    freqs = np.fft.fftshift(np.fft.fftfreq(len(env) + 2 * PAD, 1.0 / rate))
    detune = float(cfg[f"qubit/{q}/EF/freq"]) - qubit_freq(cfg, q)
    return float(spec[int(np.abs(freqs - detune).argmin())])


def optimize_fast_drag(cfg, q, m, name="x90", n_grid=N_GRID, w_grid=W_GRID) -> dict:
    """qcal's `optimize_FAST_DRAG`, host-only: coordinate descent on the FAST_DRAG hyperparameters,
    scoring each candidate by `ef_spectral_weight`. `N` (the number of cosine terms) is swept first
    and fixed at its argmin, then EACH `weights` entry in turn — qcal's order exactly, and the order
    matters, since the weights shape the spectrum the chosen `N` produces.

    Returns the proposal `{qubit/{q}/{name}/kwargs: {...}}` — the whole kwargs dict, since that is the
    Config leaf (F0 gave it a write-back path into the qcal tree). Pure host arithmetic: no driver, no
    shots. Call it before `Leakage`, which refines the same knobs against the real qubit."""
    kw = dict(cfg[f"qubit/{q}/{name}/kwargs"])
    assert "N" in kw and "weights" in kw, \
        f"optimize_fast_drag needs FAST_DRAG kwargs (N, weights) at qubit/{q}/{name}, got {sorted(kw)}"

    def sweep(values, apply):
        """Score every candidate, then leave `kw` at the one with the least EF weight."""
        scores = []
        for v in values:
            apply(v)
            scores.append(ef_spectral_weight(cfg, q, m, name, kw))
        apply(values[int(np.argmin(scores))])

    def set_n(v):
        kw["N"] = int(v)

    def set_w(v, i):
        w = list(kw["weights"])
        w[i] = float(v)
        kw["weights"] = w

    sweep(list(n_grid), set_n)
    for i in range(len(kw["weights"])):
        sweep(list(w_grid), lambda v, i=i: set_w(v, i))
    return {f"qubit/{q}/{name}/kwargs": kw}
