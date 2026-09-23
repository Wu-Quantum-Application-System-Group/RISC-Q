"""Readout classification (spec 21): the pooled-GMM `Classifier`/`ClassifierN`, the two-cluster SNR
and the readout-correction matrix helper `rcorr`, moved from riscq.cal.readout at universal-cal V6."""

from __future__ import annotations

import math

import numpy as np


def _snr(iq0: np.ndarray, iq1: np.ndarray) -> float:
    """qcal's two-cluster SNR (machine_learning/clustering.py:120, adopted verbatim per spec 13 §2 so
    that thresholds and argmaxes are comparable numbers): ‖Δmeans‖ / Σ(2·√cov), where `cov` is the
    SPHERICAL cluster variance its GMM fits — the mean of the per-axis variances. Note the
    denominator is 2σ₀ + 2σ₁, so SNR = 1 means the cluster means are 4σ apart (~2 % assignment
    error), and the number is ~4× smaller than a plain distance/σ SNR. This is the LABELLED-cloud
    version (ClassifierN); `Classifier.separation` feeds the same formula the FITTED parameters,
    like qcal (spec 21 §1)."""
    dist = float(np.hypot(*(iq1.mean(0) - iq0.mean(0))))
    err = sum(2.0 * math.sqrt(float(np.mean(iq.var(0)))) for iq in (iq0, iq1))
    return dist / (err or 1e-9)


def _gmm_fit(clusters, tol=1e-3, max_iter=100, reg=1e-6):
    """Deterministic spherical EM on the POOLED points (spec 21 §2.1) — the in-house equivalent of
    qcal's `GaussianMixture(covariance_type='spherical', means_init=labelled means)` fit, without the
    RNG sklearn hides in its init (with only `means_init` given it still seeds weights/variances from
    a k-means pass on the global RNG; our gates are bit-reproducible, so the fit must be).

    `clusters` are the prep-labelled clouds — used ONLY to initialize (means = labelled means, qcal's
    `means_init`; weights = shot fractions; σ² = each cloud's mean per-axis variance). EM then runs
    unsupervised on the pooled points: sklearn's semantics (`reg_covar` 1e-6 variance floor, converge
    when the mean log-likelihood moves < `tol`, `max_iter` 100). Returns (means (k,2), sigmas (k,),
    weights (k,))."""
    clusters = [np.atleast_2d(np.asarray(c, float)) for c in clusters]
    x = np.vstack(clusters)
    n, d = x.shape
    means = np.array([c.mean(0) for c in clusters])
    var = np.array([float(np.mean(c.var(0))) + reg for c in clusters])
    w = np.array([len(c) / n for c in clusters])
    prev = -np.inf
    for _ in range(max_iter):
        d2 = ((x[:, None, :] - means[None, :, :]) ** 2).sum(2)             # (n, k)
        logp = np.log(w) - 0.5 * d * np.log(2 * np.pi * var) - d2 / (2 * var)
        top = logp.max(1, keepdims=True)
        lse = top[:, 0] + np.log(np.exp(logp - top).sum(1))                # log-sum-exp per point
        resp = np.exp(logp - lse[:, None])
        nk = resp.sum(0)
        w = nk / n
        means = (resp.T @ x) / nk[:, None]
        d2 = ((x[:, None, :] - means[None, :, :]) ** 2).sum(2)
        var = (resp * d2).sum(0) / (d * nk) + reg
        ll = float(lse.mean())
        if abs(ll - prev) < tol:
            break
        prev = ll
    return means, np.sqrt(var), w


def _gmm_predict(iq: np.ndarray, means, sigmas, weights) -> np.ndarray:
    """Posterior argmax of the fitted spherical mixture — the component 0..k-1 of each point. The
    2π constant drops; the variance and weight terms stay, so the boundary honours them (NOT the
    nearest-mean or labelled-midpoint rule: with prep decay the pooled clusters are unequal and the
    honest boundary is off the midpoint)."""
    iq = np.atleast_2d(np.asarray(iq, float))
    var = np.asarray(sigmas) ** 2
    d2 = ((iq[:, None, :] - np.asarray(means)[None, :, :]) ** 2).sum(2)
    return (np.log(weights) - np.log(var) - d2 / (2 * var)).argmax(1)


def res_fidelity(iq0: np.ndarray, iq1: np.ndarray, phase: float) -> float:
    """What the ON-CHIP discriminator would score on these clusters at demod phase `phase`.

    The host `Classifier` puts its boundary wherever the data says; the hardware's is `sign(sumR)`
    at a HARD ZERO, and the only knob that moves the data relative to it is the demod phase — a
    ROTATION. A rotation can put the |0>→|1> axis on the real axis, but it cannot move the cluster
    MIDPOINT off it: whether the threshold ends up between the clusters is then a property of the
    physics, not of the calibration.

    It works out when the two responses are antipodal (a flat readout tone, `m1 = −m0`) or conjugate
    (a dispersive resonator probed AT `f_r`) — in both the midpoint is on the imaginary axis once the
    axis is rotated onto the real one. Probed OFF resonance it does not: for the spec-15 scenario at
    `f_r + 1.5 MHz` the rotated midpoint sits at 0.44 against a half-separation of 0.35, so BOTH
    clusters land on the +real side and the `res` bit stops discriminating while the host classifier
    is untouched (spec 15 §9.6 measured 0.66 against qcal's 0.998).

    So this is measured, not assumed: `½[P(res=0 | |0>) + P(res=1 | |1>)]` on the calibration's own
    shots, rotated by the phase it is about to propose. The demod carrier carries `e^{iφ}`, so the
    integral rotates the same way.
    """
    rot = np.exp(1j * float(phase))
    z0 = (np.asarray(iq0, float) @ [1, 1j]) * rot
    z1 = (np.asarray(iq1, float) @ [1, 1j]) * rot
    return 0.5 * (float(np.mean(z0.real > 0)) + float(np.mean(z1.real < 0)))


class Classifier:
    """Two prep-labelled Gaussian IQ clusters (|0>, |1>) → an UNSUPERVISED 2-component spherical GMM
    fit on the pooled shots (qcal's scheme, spec 21 §1-2.2): the labels only initialize the fit and
    name the components — they never place the boundary, so a |1>-prep shot that decayed into the
    |0> cloud is assigned where it LANDED instead of dragging a supervised threshold toward it.
    `m0`/`m1` are the fitted component means, `separation` qcal's SNR on the FITTED parameters,
    `classify` the posterior argmax (weights and variances included — with prep decay the pooled
    clusters are unequal and the honest boundary is off the labelled midpoint)."""

    def __init__(self, iq0: np.ndarray, iq1: np.ndarray):
        self.iq0, self.iq1 = iq0, iq1
        means, sigmas, weights = _gmm_fit([iq0, iq1])
        if float(np.mean(_gmm_predict(iq0, means, sigmas, weights))) > 0.5:
            # anchor guard: EM started on the labelled means, but if it converged with the
            # components swapped (majority of the |0>-prep shots in component 1), swap back — qcal
            # trusts the init anchoring alone (its majority-vote remap is commented out); this only
            # fires where qcal would silently flip labels, and it is deterministic.
            means, sigmas, weights = means[::-1], sigmas[::-1], weights[::-1]
        self.means, self.sigmas, self.weights = means, sigmas, weights
        self.m0, self.m1 = means[0], means[1]
        self.separation = float(np.hypot(*(self.m1 - self.m0)) / (2.0 * sigmas.sum()))

    def classify(self, iq: np.ndarray) -> np.ndarray:
        """0 (|0>) / 1 (|1>) per point — the fitted mixture's posterior argmax."""
        return _gmm_predict(iq, self.means, self.sigmas, self.weights)

    def confusion(self) -> np.ndarray:
        """2×2 confusion: row = prepared state, col = classified state (normalised)."""
        c = np.zeros((2, 2))
        for state, iq in ((0, self.iq0), (1, self.iq1)):
            pred = self.classify(iq)
            c[state, 0] = np.mean(pred == 0)
            c[state, 1] = np.mean(pred == 1)
        return c


class ClassifierN:
    """N labelled Gaussian IQ clusters (|0>, |1>, ..., |N-1>) — the multi-level readout GMM (spec
    two-qubit/01 §5, `n_levels`). Nearest-cluster-mean assignment: for well-separated clusters this is
    the boundary a diagonal-covariance GMM finds, and the |2> cloud a leaked/EF-prepped shot lands in
    is a third centroid, not a mislabelled |1>. `separation` is the MINIMUM pairwise cluster SNR (the
    worst-separated pair bounds three-level fidelity); `means` are the per-level IQ centroids."""

    def __init__(self, clusters):
        self.clusters = [np.atleast_2d(np.asarray(c, float)) for c in clusters]
        assert len(self.clusters) >= 2, "ClassifierN needs at least two labelled clusters"
        self.means = np.array([c.mean(0) for c in self.clusters])          # (N, 2)
        self.separation = min(_snr(self.clusters[i], self.clusters[j])
                              for i in range(len(self.clusters))
                              for j in range(i + 1, len(self.clusters)))

    def classify(self, iq: np.ndarray) -> np.ndarray:
        """The level 0..N-1 of each point, by nearest cluster mean."""
        iq = np.atleast_2d(np.asarray(iq, float))
        d = np.linalg.norm(iq[:, None, :] - self.means[None, :, :], axis=2)  # (npts, N)
        return d.argmin(1)

    def confusion(self) -> np.ndarray:
        """N×N confusion: row = prepared level, col = classified level (each row normalised)."""
        n = len(self.clusters)
        c = np.zeros((n, n))
        for state, iq in enumerate(self.clusters):
            pred = self.classify(iq)
            for k in range(n):
                c[state, k] = np.mean(pred == k)
        return c


def rcorr(p, cmat):
    """qcal's `rcorr_cmat`: undo the readout confusion on a population vector (or a row-stack of them).

    The measured populations are the true ones pushed through the confusion matrix — row = PREPARED
    level, column = MEASURED level, each row summing to 1 — so `p_meas = p_true @ cmat` and the
    correction is the solve `p_true = p_meas @ cmat⁻¹`. Everything the reference reads where |2>
    matters (Leakage, the RAP branch, reset) goes through it.

    The result is NOT clipped: on noisy data a corrected population can land slightly outside [0, 1],
    and silently squashing it would hide exactly the miscalibration this correction exists to expose."""
    p = np.asarray(p, float)
    rows = np.atleast_2d(p)
    c = np.asarray(cmat, float)
    assert c.ndim == 2 and c.shape[0] == c.shape[1] == rows.shape[1], \
        f"confusion {c.shape} does not match populations {rows.shape}"
    out = np.linalg.solve(c.T, rows.T).T         # p_true @ c = p_meas  ->  cᵀ p_trueᵀ = p_measᵀ
    return out[0] if p.ndim == 1 else out
