"""Estimators (specs/universal-cal/01 §8): the decision functions the calibration classes share.
Each is pure — arrays in, an `Estimate` out — and owns its guards and fallbacks once."""

from __future__ import annotations

import math

import numpy as np

from riscq.cal import fits
from riscq.cal.calibration import Estimate
from riscq.pulses import units

TWO_PI = 2 * math.pi


def rabi_amplitude(codes, sig, P, n_gates: int, target_angle: float, path: str,
                   rabi_path: str | None = None) -> Estimate:
    """qcal's amplitude rule (spec 13 §7). n_gates=1: cosine of P vs the drive integral `sig` →
    the Rabi RATE, and the code whose n·rabi·σ is `target_angle`, which must lie INSIDE the swept
    codes (qcal's in_range guard). n_gates>1: the parabola vertex of the amplified train (P
    MINIMISES at the tuned amp: upward, in range)."""
    codes = np.asarray(codes, dtype=float)
    if n_gates == 1:
        fq = fits.fit_cosine(sig, P)
        if not fq.ok:
            return Estimate(False, fq)
        rabi = float(TWO_PI * fq.value)
        g = sig[-1] / (int(codes[-1]) * n_gates)            # sig per amp-code (linear)
        a_star = (target_angle / rabi) / g
        ok = bool(codes[0] <= a_star <= codes[-1])
        prop = {path: float(np.clip(a_star / units.AMP_SCALE, 0.0, 1.0))}
        if rabi_path:
            prop[rabi_path] = rabi
        return Estimate(ok, fq, prop if ok else {}, data={"sig": sig, "rabi": rabi})
    fq = fits.fit_parabola(codes, P)
    ok = bool(fq.ok and fq.params["a"] > 0 and codes[0] <= fq.value <= codes[-1])
    prop = {path: float(np.clip(fq.value / units.AMP_SCALE, 0.0, 1.0))} if ok else {}
    return Estimate(ok, fq, prop, data={"sig": sig})


CODE_PER_CYCLE_PER_BATCH = 1 << 12       # a fringe in cycles/batch → a DAC-rate freq code (2^16 / 16)


def ramsey_vfit(wf, fringes: dict, carrier_hz: float, m, path: str) -> Estimate:
    """qcal's V-fit (spec 13 §7): each fringe (P vs wait, batches) is a damped cosine whose UNSIGNED
    frequency is |δ + applied|; fitting those against the applied detuning codes to a·|x − b| + c
    puts the vertex at b = −δ, so the corrected carrier is `carrier + b` (qcal's `old + b`)."""
    applied, obs, fits_ = [], [], {}
    for dc, P in fringes.items():
        f = fits.fit_damped_cosine(wf, P)
        fits_[dc] = (wf, P, f)
        if f.ok:
            applied.append(dc)
            obs.append(CODE_PER_CYCLE_PER_BATCH * f.value)
    x, y = np.array(applied, float), np.array(obs, float)
    v = fits.fit_absolute_value(x, y)
    ok = bool(v.ok and len(x) >= 3 and v.params["a"] > 0 and x.min() <= v.value <= x.max())
    data = {"x": x, "y": y, "applied": x, "obs": y, "fringes": fits_}
    if not ok:
        return Estimate(False, v, data=data)
    dcode = -float(v.value)
    data["detuning_code"] = dcode
    return Estimate(True, v, {path: carrier_hz - units.code_to_freq(dcode, m.params)}, data=data)


def exp_decay(delays, P, m, path: str) -> Estimate:
    """T1: P = A·exp(−Δt/τ) + C with A > 0 (P decays 1 → 0); τ reported in seconds."""
    f = fits.fit_exp_decay(delays, P)
    ok = bool(f.ok and f.params["amp"] > 0)
    return Estimate(ok, f, {path: f.value / m.params.dsp_freq_hz} if ok else {})


def damped_decay(wf, P, m, path: str) -> Estimate:
    """T2*: the damped-cosine decay τ (batches → seconds)."""
    f = fits.fit_damped_cosine(wf, P)
    ok = bool(f.ok and f.params["tau"] > 0)
    return Estimate(ok, f, {path: f.params["tau"] / m.params.dsp_freq_hz} if ok else {})


def line_crossing(x, p_y, p_x, chi2_max: float, path: str) -> Estimate:
    """qcal Phase's two-line crossing (spec 13 §6) → the virtual-Z pair [φ, φ]."""
    fit_pair, phi, fallback, ok = _line_crossing(x, p_y, p_x, chi2_max)
    return Estimate(ok, fit_pair, {path: [phi, phi]} if ok else {}, fallback,
                    data={"y": (p_y - p_x) ** 2, "p0": p_y, "p1": p_x, "phi": phi})


def cosine_axis(x, y, centre: float, path: str) -> Estimate:
    """qcal Phase(gate='X')'s cosine minimum → the X pulse's own axis phase (spec 14 §3.3)."""
    f, phi, fallback, ok = _cosine_axis(x, y, centre)
    return Estimate(ok, f, {path: float(phi)} if ok else {}, fallback, data={"phi": phi})


def lorentz_peak(x, y, path: str) -> Estimate:
    """A spectroscopy line's centre → the frequency at `path` (spec 24 §3.3)."""
    f = fits.fit_lorentzian(x, y)
    return Estimate(bool(f.ok), f, {path: float(f.value)} if f.ok else {})


def ramsey_abs_smallest(fit, applied_hz: float, carrier_hz: float, path: str) -> dict:
    """QICK's single-detuning re-tune rule (spec 24 §3.4): with one applied detuning f_R and an
    unsigned fitted fringe f_fit, the carrier error is whichever of f_R ∓ f_fit is smaller in
    magnitude (the qubit is assumed nearly tuned); proposes `carrier + that`."""
    f_fit = float(fit.value)
    cand = sorted((applied_hz - f_fit, applied_hz + f_fit), key=abs)
    return {path: carrier_hz + cand[0]}


def _first_extremum(fit, x_max: float, toward_max: bool) -> float:
    """The first positive x where A·cos(2πf·x + φ) + C reaches the extreme AWAY from x = 0 —
    QICK's `pi_length` rule (spec 24 §3.10): a π swap is half a period from the start."""
    f, phi, A = float(fit.params["freq"]), float(fit.params["phase"]), float(fit.params["amp"])
    start = A * math.cos(phi)                          # the fitted level at x = 0
    k0 = 1 if (start > 0) == (A > 0) else 0            # away from the start: A·cos(kπ) = −sign(start)
    for k in range(k0, k0 + 20, 2):
        x = (k * math.pi - phi) / (TWO_PI * f)
        if x > 0:
            return x
    return math.nan


def length_rabi(x_batches, P, m, pi_path: str, hpi_path: str) -> Estimate:
    """A swap's length calibration (spec 24 §3.10): damped cosine on the flat length, `pi` = the
    first extremum, `hpi = pi − T/4` (QICK: `pi2 = pi − 1/(4f)`), both in seconds."""
    f = fits.fit_damped_cosine(x_batches, P)
    if not f.ok or f.params["freq"] <= 0:
        return Estimate(False, f)
    pi_b = _first_extremum(f, float(np.max(x_batches)), True)
    ok = bool(np.isfinite(pi_b) and x_batches[0] <= pi_b <= x_batches[-1])
    hpi_b = pi_b - 0.25 / f.params["freq"]
    fs = m.params.dsp_freq_hz
    return Estimate(ok, f, {pi_path: pi_b / fs, hpi_path: max(hpi_b, 0.0) / fs} if ok else {},
                    data={"pi_batches": pi_b})


def _smooth_outliers(y: np.ndarray) -> np.ndarray:
    """The notebook's single-point outlier rule (spec 24 §4.6): a row differing from BOTH
    neighbours by more than 8× the median step is replaced by their mean."""
    y = np.array(y, float)
    if len(y) < 3:
        return y
    step = np.median(np.abs(np.diff(y))) or 1.0
    for i in range(1, len(y) - 1):
        if abs(y[i] - y[i - 1]) > 8 * step and abs(y[i] - y[i + 1]) > 8 * step:
            y[i] = 0.5 * (y[i - 1] + y[i + 1])
    return y


def chevron(freqs_hz, x_batches, rows, m, freq_path: str, pi_path: str, hpi_path: str) -> Estimate:
    """The frequency × length chevron (spec 24 §3.9): a damped cosine per frequency row;
    `best_frequency_contrast` = argmax of the fitted row's peak-to-peak (outlier-smoothed) picks
    the frequency, `best_frequency_period` = argmin |ω| the swap time (`pi = π/ω`, `hpi = pi/2`)."""
    x = np.asarray(x_batches, float)
    fits_, contrast, omega = [], [], []
    for row in rows:
        f = fits.fit_damped_cosine(x, row)
        fits_.append(f)
        if f.ok and f.params["freq"] > 0:
            model = f.params["amp"] * np.exp(-x / f.params["tau"]) * np.cos(TWO_PI * f.params["freq"] * x + f.params["phase"])
            contrast.append(float(np.ptp(model)))
            omega.append(TWO_PI * f.params["freq"])
        else:
            contrast.append(math.nan)
            omega.append(math.nan)
    contrast, omega = _smooth_outliers(np.nan_to_num(contrast, nan=0.0)), np.array(omega)
    if not np.any(contrast > 0) or not np.any(np.isfinite(omega)):
        return Estimate(False, fits_, data={"contrast": contrast, "omega": omega})
    i_c = int(np.argmax(contrast))
    i_w = int(np.nanargmin(omega))
    pi_b = math.pi / omega[i_w]
    fs = m.params.dsp_freq_hz
    return Estimate(True, fits_, {freq_path: float(freqs_hz[i_c]), pi_path: pi_b / fs,
                                  hpi_path: 0.5 * pi_b / fs},
                    data={"contrast": contrast, "omega": omega, "i_contrast": i_c, "i_period": i_w})


def errormap(x, rows: dict, path: str, to_value=None) -> Estimate:
    """Error amplification (spec 24 §3.11, arXiv 2406.08295): populations in [0, 1] per pulse
    count n (`rows[n]`), multiplied over n, Gaussian-fitted; `x0` is the tuned knob (`to_value`
    maps a code to the Config value). Falls back to the parabola vertex."""
    prod = np.ones(len(x), float)
    for row in rows.values():
        prod *= np.clip(np.asarray(row, float), 0.0, 1.0)
    f = fits.fit_gaussian(x, prod)
    fallback = False
    if not f.ok:
        f, fallback = fits.fit_parabola(x, prod), True
    ok = bool(f.ok and x[0] <= f.value <= x[-1])
    val = to_value(float(f.value)) if (ok and to_value) else float(f.value)
    return Estimate(ok, f, {path: val} if ok else {}, fallback, data={"product": prod})


def _line_crossing(x, p_y, p_x, chi2_max):
    """qcal Phase's two-line crossing (spec 13 §6), shared by the GE and EF Phase cals: linear-fit
    the two sequences' populations and cross them at phi = (b1 − b0)/(m0 − m1), with qcal's guards —
    a failed fit or an out-of-range crossing fails (no write, README principle 6); an underfit
    (reduced chi2 > `chi2_max`) falls back to the argmin of (p_y − p_x)² over the grid. Returns
    ((fit_y, fit_x), phi, fallback, ok), phi = nan when not ok."""
    f_y, f_x = fits.fit_linear(x, p_y), fits.fit_linear(x, p_x)
    if f_y.ok and f_x.ok:
        m0, b0 = f_y.params["slope"], f_y.params["intercept"]
        m1, b1 = f_x.params["slope"], f_x.params["intercept"]
        phi = (b1 - b0) / (m0 - m1) if m0 != m1 else math.nan
        if x[0] <= phi <= x[-1]:                       # in range (qcal's `in_range`)
            if max(f_y.params["redchi"], f_x.params["redchi"]) > chi2_max:
                return (f_y, f_x), float(x[int(np.argmin((p_y - p_x) ** 2))]), True, True
            return (f_y, f_x), float(phi), False, True
    return (f_y, f_x), math.nan, False, False


def _cosine_axis(x, y, centre):
    """qcal Phase(gate='X')'s cosine fit (spec 14 §3.3), shared by the GE and EF X-phase cals. The
    `X90 · X · X90` population is A·cos(2π·f·phi + ϑ) + C (fit_cosine canonicalises A ≥ 0), MINIMAL
    at (π − ϑ)/(2π·f) — the aligned axis, since the composite only returns to the prepared level
    there. The fringe runs at TWICE the swept axis (a π rotation's axis enters a Bloch rotation as
    2φ), so its period is π and the two solutions a period apart are the same gate
    (R_{φ+π}(π) = −R_φ(π)): take the one nearest the sweep `centre` so an already-calibrated qubit
    does not jump. qcal's guards follow the line-crossing ones — a failed fit or an out-of-range
    solution fails (no write) and falls back to the grid argmin. Returns (fit, phi, fallback, ok)."""
    f = fits.fit_cosine(x, y)
    phi, ok = 0.0, bool(f.ok and f.params["freq"] > 0)
    if ok:
        per = 1.0 / f.params["freq"]
        base = (math.pi - f.params["phase"]) / (2 * math.pi * f.params["freq"])
        phi = base - round((base - centre) / per) * per
    ok = ok and bool(x[0] <= phi <= x[-1])                  # qcal's in_range guard
    if not ok:                                              # ... and its argmin fallback
        return f, float(x[int(np.argmin(y))]), True, False
    return f, float(phi), False, True
