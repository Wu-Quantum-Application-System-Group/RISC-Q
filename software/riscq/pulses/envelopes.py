"""Envelope shape library: complex float64 arrays on the CHANNEL's stored-sample grid, peak
|value| = FULL = 32767/32768 (the largest SF(16) value — "1.0" nominal, clip-safe by
construction).

Grid conventions (this build: batch = 16 DAC samples, gateInterp 4 / readoutInterp 16):
  - gate channel:    4 stored samples per batch (grid rate 4 * f_dsp), each held x4 at the DAC;
  - readout channel: 1 stored sample per batch (grid rate f_dsp), held x16.
An envelope shorter than a whole number of lines is zero-padded at packing time (riscq.pulses.pack)."""

from __future__ import annotations

import numpy as np

FULL = 32767 / 32768   # largest SF(16) value; all library envelopes peak here


def gaussian(n_samples: int, sigmas: float) -> np.ndarray:
    """Gaussian truncated at +-sigmas, peak FULL."""
    t = np.linspace(-sigmas, sigmas, n_samples)
    e = np.exp(-0.5 * t * t)
    return (FULL * e / e.max()).astype(complex)


def drag(n_samples: int, sigmas: float, alpha: float) -> np.ndarray:
    """DRAG: gaussian + 1j * alpha * d(gaussian)/dt, derivative in per-stored-sample units;
    the whole envelope is renormalized so max|env| = FULL (clip-safe for any alpha)."""
    g = np.exp(-0.5 * np.linspace(-sigmas, sigmas, n_samples) ** 2)
    e = g + 1j * alpha * np.gradient(g)
    return e * (FULL / np.abs(e).max())


def cosine_square(n_samples: int, ramp_fraction: float = 0.25) -> np.ndarray:
    """Square with raised-cosine edges — qcal's `cosine_square` (sequence/pulse_envelopes.py) on our
    grid: each ramp is round(n_samples * ramp_fraction) samples of (1 − cos(pi*k/n_ramp))/2, rising
    then falling, with a flat top between. Named as the qcal config names it (kwarg `ramp_fraction`),
    so `envelopes.build(name, n, rate, **cfg_kwargs)` feeds it straight from the Config."""
    n_ramp = round(n_samples * ramp_fraction)
    if 2 * n_ramp > n_samples:
        raise ValueError(f"ramp_fraction {ramp_fraction}: 2 ramps of {n_ramp} exceed {n_samples} samples")
    cos = FULL * 0.5 * (1 - np.cos(np.pi * np.arange(2 * n_ramp) / n_ramp))   # rising ++ falling
    flat = np.full(n_samples - 2 * n_ramp, FULL)
    return np.concatenate((cos[:n_ramp], flat, cos[n_ramp:])).astype(complex)


cos_edge_square = cosine_square    # the pre-qcal name


def square(n_samples: int) -> np.ndarray:
    return np.full(n_samples, FULL + 0j)


def _fast_coefficients(n_terms: int, t_p: float, theta: float, freq_intervals, weights) -> np.ndarray:
    """The FAST cosine-series coefficients (qcal sequence/utils.py): minimise the pulse's spectral
    weight over the given frequency intervals subject to the rotation-angle constraint sum(c) = theta/t_p.
    The basis is g_n(t) = 1 − cos(2*pi*n*t/t_p) on [0, t_p], whose Fourier transform is analytic."""
    def g_hat(n, f):                       # FT of g_n (qcal fourier_transform_basis)
        return t_p * (np.sinc(f * t_p) - 0.5 * (np.sinc((f - n / t_p) * t_p)
                                                + np.sinc((f + n / t_p) * t_p)))

    A = 1e-10 * np.eye(n_terms)            # qcal's regularisation against a singular A
    for (f_lo, f_hi), w in zip(freq_intervals, weights):
        f = np.linspace(f_lo, f_hi, 200)
        G = np.array([g_hat(n + 1, f) for n in range(n_terms)])
        A += w * np.trapezoid(G[:, None, :] * G[None, :, :], dx=f[1] - f[0], axis=-1)
    ones = np.ones((n_terms, 1))
    aug = np.block([[A + A.T, -ones], [ones.T, np.zeros((1, 1))]])   # Lagrange multiplier on sum(c)
    rhs = np.concatenate((np.zeros(n_terms), [theta / t_p]))
    return np.linalg.solve(aug, rhs)[:-1]


def fast_drag(n_samples: int, sample_rate: float, alpha: float = 0.0, anh: float = -200e6,
              N: int = 4, weights=None, freq_intervals=None, theta: float = np.pi / 2,
              **_) -> np.ndarray:
    """FAST DRAG (PRX Quantum 5, 030353) — the X6Y3 gate envelope, qcal's `FAST_DRAG`: the real part
    is the FAST cosine series whose spectrum is suppressed on `freq_intervals` (default: around the
    ef transition |anh| and a high-frequency band), the imaginary part the DRAG correction
    alpha/(2*pi*anh) * dI/dt. Kwarg names are the qcal config's (alpha / anh / N / weights) so a
    Config's `kwargs` dict feeds it directly; `amp`/`phase` are NOT env parameters here (they are the
    pulse's own slot codes — and qcal normalises them out of the envelope anyway). Peak |value| = FULL."""
    if freq_intervals is None:              # qcal's defaults: ef transition, then a high-freq cutoff
        freq_intervals = [[0.9 * abs(anh), 1.1 * abs(anh)], [2 * abs(anh), 5 * abs(anh)]]
    if weights is None:
        weights = [1.0, 0.1]
    t_p = n_samples / sample_rate
    t = np.linspace(0.0, t_p, n_samples)
    c = _fast_coefficients(int(N), t_p, theta, freq_intervals, weights)
    i_env = sum(c[n] * (1 - np.cos(2 * np.pi * (n + 1) * t / t_p)) for n in range(int(N)))
    i_env = i_env / np.abs(i_env).max()
    e = i_env + 1j * (alpha / (2 * np.pi * anh)) * np.gradient(i_env, 1.0 / sample_rate)
    return e * (FULL / np.abs(e).max())


_BY_NAME = {"square": lambda n, rate, **kw: square(n),
            "CW": lambda n, rate, **kw: square(n),
            "cosine_square": lambda n, rate, **kw: cosine_square(n, **kw),
            "FAST_DRAG": lambda n, rate, **kw: fast_drag(n, rate, **kw)}


def build(name: str, n_samples: int, sample_rate: float, **kwargs) -> np.ndarray:
    """The envelope a Config names: a qcal env name + its kwargs on `n_samples` stored
    samples of a channel whose stored-sample rate is `sample_rate` (Hz)."""
    if name not in _BY_NAME:
        raise ValueError(f"unknown envelope {name!r} (have {sorted(_BY_NAME)})")
    return _BY_NAME[name](n_samples, sample_rate, **kwargs)


def arb(samples) -> np.ndarray:
    """User-supplied envelope; validated (1-D, |value| <= FULL) and converted to complex128."""
    a = np.asarray(samples, dtype=complex)
    if a.ndim != 1 or a.size == 0:
        raise ValueError(f"arb envelope must be a non-empty 1-D array, got shape {a.shape}")
    peak = float(np.max(np.maximum(np.abs(a.real), np.abs(a.imag))))
    if peak > FULL:
        raise ValueError(f"arb envelope |value| {peak} exceeds FULL = 32767/32768")
    return a.copy()
