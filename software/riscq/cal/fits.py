"""The scipy fit helpers the calibrations use (spec 06 §3): the subset qcal actually fits.

Each returns a `Fit(ok, value, error, params)`: `value` is the quantity the calibration reads
(frequency, decay time, parabola vertex, line slope), `params` a dict of every fitted coefficient,
`error` the 1σ uncertainty on `value`. A failed or ill-conditioned fit (curve_fit does not
converge, or the covariance is non-finite) returns `ok=False` — never a silent fallback, so a
calibration can refuse to update the config.
"""

from __future__ import annotations

import math
import warnings
from collections import namedtuple

import numpy as np
from scipy.optimize import curve_fit

Fit = namedtuple("Fit", ["ok", "value", "error", "params"])

_FAIL = (RuntimeError, ValueError, TypeError)
_NONE = Fit(False, math.nan, math.nan, {})


def _fittable(x: np.ndarray, y: np.ndarray, n_params: int) -> bool:
    """Enough points, and enough of them, to seed and fit `n_params` coefficients?

    Every helper below SEEDS from the data — `y.max()`, `y[0]`, an FFT peak — before `curve_fit` is
    ever reached, so a sweep whose points all failed upstream would raise out of the helper instead
    of coming back as a refused fit. `ok=False, never an exception` is the one thing every caller
    relies on: a calibration decides whether to write the config on it (spec 17 G3).
    """
    return len(x) >= n_params and len(x) == len(y)


def _fft_freq_seed(x: np.ndarray, y: np.ndarray) -> float:
    """Dominant non-DC FFT frequency of y on a ~uniform x grid (cycles per x-unit) — the seed
    that lets curve_fit lock onto the right sinusoid instead of a harmonic."""
    n = len(x)
    dx = (x[-1] - x[0]) / (n - 1) if n > 1 else 1.0
    spec = np.abs(np.fft.rfft(y - np.mean(y)))
    freqs = np.fft.rfftfreq(n, d=dx)
    if len(spec) <= 1:
        return 1.0 / (n * dx)
    return float(freqs[1 + int(np.argmax(spec[1:]))]) or 1.0 / (n * dx)


#: Sub-harmonics of the FFT peak that every sinusoidal fit is ALSO started from (see `_best_of`).
_SEED_SCALES = (1.0, 0.5, 1 / 3.0, 0.25)


def _best_of(model, x, y, starts):
    """Fit `model` from several starting points and keep the lowest-residual result.

    One FFT seed is not enough for a sweep covering less than a full period: the peak bin then sits
    at the span's own fundamental — well ABOVE the true frequency — and curve_fit locks onto a
    harmonic of it. Measured on a real half-period Rabi sweep (spec 17 E7): the FFT-seeded fit
    returned 3.5x the true frequency with SSR 2.36, a sub-harmonic seed returned the right one with
    SSR 0.033, and the wrong answer passed the caller's in-range guard silently. The residual is
    what decides, exactly as `fit_absolute_value` already does for its two orientations.

    Returns `(ssr, popt, perr)` or None when every start failed.
    """
    best = None
    for p0 in starts:
        try:
            popt, pcov = curve_fit(model, x, y, p0=list(p0), maxfev=20000)
        except _FAIL:
            continue
        if not np.all(np.isfinite(popt)):
            continue
        ssr = float(np.sum((y - model(x, *popt)) ** 2))
        if not np.isfinite(ssr):     # finite popt can still overflow the model (exp -> inf·cos);
            continue                 # a NaN ssr compares False forever and would shadow later fits
        if best is None or ssr < best[0]:
            best = (ssr, popt, np.sqrt(np.diag(pcov)))
    return best


def fit_cosine(x, y) -> Fit:
    """y ≈ A·cos(2π·f·x + φ) + C, frequency seeded from the FFT peak and its sub-harmonics.
    value = f (≥ 0)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 4):
        return _NONE
    seed, amp, off = _fft_freq_seed(x, y), max((y.max() - y.min()) / 2, 1e-9), y.mean()

    def model(t, A, f, phi, C):
        return A * np.cos(2 * np.pi * f * t + phi) + C

    best = _best_of(model, x, y, [[amp, seed * k, 0.0, off] for k in _SEED_SCALES])
    if best is None:
        return _NONE
    _, (A, f, phi, C), perr = best
    if A < 0:                 # canonicalise to A ≥ 0 (A·cos(θ+φ) = |A|·cos(θ+φ+π)) so `phase` alone
        phi = phi + np.pi     # locates the maximum, at x = −phase/(2π·freq)
    params = {"amp": abs(A), "freq": abs(f), "phase": phi, "offset": C}
    return Fit(bool(np.all(np.isfinite(perr))), abs(f), float(perr[1]), params)


def fit_damped_cosine(x, y) -> Fit:
    """y ≈ A·exp(−x/τ)·cos(2π·f·x + φ) + C. value = f (≥ 0); params carry the decay τ too."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 5):
        return _NONE
    span = (x.max() - x.min()) or 1.0
    seed, amp, off = _fft_freq_seed(x, y), max((y.max() - y.min()) / 2, 1e-9), y.mean()

    def model(t, A, tau, f, phi, C):
        return A * np.exp(-t / tau) * np.cos(2 * np.pi * f * t + phi) + C

    best = _best_of(model, x, y, [[amp, span, seed * k, 0.0, off] for k in _SEED_SCALES])
    if best is None:
        return _NONE
    _, (A, tau, f, phi, C), perr = best
    params = {"amp": A, "tau": tau, "freq": abs(f), "phase": phi, "offset": C}
    return Fit(bool(np.all(np.isfinite(perr))), abs(f), float(perr[2]), params)


def fit_exp_decay(x, y) -> Fit:
    """y ≈ A·exp(−x/τ) + C (A may be either sign). value = τ (> 0)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 3):
        return _NONE
    span = (x.max() - x.min()) or 1.0
    p0 = [(y[0] - y[-1]) or 1.0, span / 2, y[-1]]

    def model(t, A, tau, C):
        return A * np.exp(-t / tau) + C

    try:
        popt, pcov = curve_fit(model, x, y, p0=p0, maxfev=20000)
    except _FAIL:
        return _NONE
    perr = np.sqrt(np.diag(pcov))
    A, tau, C = popt
    params = {"amp": A, "tau": tau, "offset": C}
    ok = bool(np.all(np.isfinite(popt)) and np.all(np.isfinite(perr)) and tau > 0)
    return Fit(ok, tau, float(perr[1]), params)


def fit_parabola(x, y) -> Fit:
    """y ≈ a·x² + b·x + c. value = the vertex x = −b/(2a) (the Amplitude n_gates>1 optimum)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 3):
        return _NONE
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")           # RankWarning on degenerate x -> caught below
            (a, b, c), cov = np.polyfit(x, y, 2, cov=True)
    except (np.linalg.LinAlgError, *_FAIL):
        return _NONE
    if a == 0 or not np.all(np.isfinite(cov)):
        return Fit(False, math.nan, math.nan, {"a": a, "b": b, "c": c})
    vertex = -b / (2 * a)
    da, db = b / (2 * a * a), -1 / (2 * a)     # ∂vertex/∂a, ∂vertex/∂b for error propagation
    var = da * da * cov[0, 0] + db * db * cov[1, 1] + 2 * da * db * cov[0, 1]
    params = {"a": a, "b": b, "c": c, "vertex": vertex}
    return Fit(True, vertex, math.sqrt(var) if var > 0 else math.nan, params)


def fit_absolute_value(x, y) -> Fit:
    """y ≈ a·|x − b| + c — qcal's V-fit (`FitAbsoluteValue`, fit_functions.absolute_value). value = b,
    the vertex: Frequency fits the UNSIGNED fringe frequencies |δ + applied| against the applied
    detuning, so the V bottoms out where the applied detuning cancels the config's error.

    Seeded from the data (qcal seeds a=1, b=0, c=0, which only works because its x is in Hz and the
    slope is then ≈1): the vertex at the extreme y, c that y, a the least-squares slope about it. BOTH
    orientations are seeded — a V (vertex at min y) and a Λ (at max y) — and the lower-SSR fit wins:
    the caller's negative-curvature guard (`a < 0` → fail, qcal's) can only be honest if the fit is
    able to land on an inverted V at all."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 3):
        return _NONE

    def model(t, a, b, c):
        return a * np.abs(t - b) + c

    best = None
    for sgn in (1.0, -1.0):
        i = int(np.argmin(y)) if sgn > 0 else int(np.argmax(y))
        b0, c0 = float(x[i]), float(y[i])
        u = np.abs(x - b0)
        denom = float(np.sum(u * u))
        a0 = float(np.sum((y - c0) * u) / denom) if denom else sgn
        try:
            popt, pcov = curve_fit(model, x, y, p0=[a0 or sgn, b0, c0], maxfev=20000)
        except _FAIL:
            continue
        ssr = float(np.sum((y - model(x, *popt)) ** 2))
        if np.isfinite(ssr) and np.all(np.isfinite(popt)) and (best is None or ssr < best[0]):
            best = (ssr, popt, np.sqrt(np.diag(pcov)))
    if best is None:
        return _NONE
    _, (a, b, c), perr = best
    ok = bool(np.all(np.isfinite(perr)))
    return Fit(ok, float(b), float(perr[1]), {"a": a, "b": b, "c": c})


def fit_linear(x, y) -> Fit:
    """y ≈ slope·x + intercept. value = slope; params carry the x-intercept (`root`, where y = 0)
    for the Phase/Frequency crossing fits, and the unweighted reduced chi-squared (`redchi` =
    SSR/(N−2)) that qcal's underfitting guard tests (`FitLinear.error > 10`, spec 13 §6)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 2):
        return _NONE
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")           # RankWarning on degenerate x -> caught below
            (slope, intercept), cov = np.polyfit(x, y, 1, cov=True)
    except (np.linalg.LinAlgError, *_FAIL):
        return _NONE
    ok = bool(np.all(np.isfinite([slope, intercept])) and np.all(np.isfinite(cov)) and slope != 0)
    ssr = float(np.sum((y - (slope * x + intercept)) ** 2))
    params = {"slope": slope, "intercept": intercept,
              "redchi": ssr / (len(x) - 2) if len(x) > 2 else math.inf}
    if slope != 0:
        params["root"] = -intercept / slope
    return Fit(ok, slope, math.sqrt(cov[0, 0]) if np.isfinite(cov[0, 0]) else math.nan, params)


def _peak_seed(x, y):
    """(x0, height, baseline) of the extremum that stands out from the median — a peak or a dip."""
    med = float(np.median(y))
    i_hi, i_lo = int(np.argmax(y)), int(np.argmin(y))
    i = i_hi if (y[i_hi] - med) >= (med - y[i_lo]) else i_lo
    return float(x[i]), float(y[i] - med), med


def fit_lorentzian(x, y) -> Fit:
    """y ≈ y0 + A / (1 + ((x − x0)/w)²) — a spectroscopy line, peak (A > 0) or dip (A < 0);
    value = x0 (spec 24 §3.3). Seeded FFT-free from the extremum vs the median, the width from
    the span's quarter (and its half / double, best residual wins)."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 4):
        return _NONE

    def model(t, y0, A, x0, w):
        return y0 + A / (1.0 + ((t - x0) / w) ** 2)

    x0, A, y0 = _peak_seed(x, y)
    span = float(x[-1] - x[0]) or 1.0
    best = _best_of(model, x, y, [(y0, A, x0, span * s) for s in (0.25, 0.1, 0.5)])
    if best is None:
        return _NONE
    _, (y0, A, x0, w), perr = best
    ok = bool(np.all(np.isfinite(perr)) and x[0] <= x0 <= x[-1] and w != 0)
    return Fit(ok, float(x0), float(perr[2]), {"y0": y0, "A": A, "x0": x0, "w": abs(w)})


def fit_gaussian(x, y) -> Fit:
    """y ≈ y0 + A·exp(−(x − x0)²/(2σ²)); value = x0 (the error-amplification product peak, spec 24
    §3.11). Seeded like the Lorentzian."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    if not _fittable(x, y, 4):
        return _NONE

    def model(t, y0, A, x0, s):
        return y0 + A * np.exp(-0.5 * ((t - x0) / s) ** 2)

    x0, A, y0 = _peak_seed(x, y)
    span = float(x[-1] - x[0]) or 1.0
    best = _best_of(model, x, y, [(y0, A, x0, span * s) for s in (0.25, 0.1, 0.5)])
    if best is None:
        return _NONE
    _, (y0, A, x0, s), perr = best
    ok = bool(np.all(np.isfinite(perr)) and x[0] <= x0 <= x[-1] and s != 0)
    return Fit(ok, float(x0), float(perr[2]), {"y0": y0, "A": A, "x0": x0, "sigma": abs(s)})
