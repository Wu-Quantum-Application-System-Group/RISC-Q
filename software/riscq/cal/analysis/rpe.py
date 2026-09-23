"""riscq.cal.analysis.rpe — robust phase estimation (spec 14 F5).

RPE amplifies a small coherent error by repeating a gate `d` times and reading the accumulated
phase out in two quadratures. Repeating over an exponential depth ladder gives an angle estimate
whose precision improves like 1/d rather than 1/sqrt(shots), which is what makes it the polish
step after the conventional (fit-a-curve) calibrations have converged.

The classes live in riscq.cal.cals.rpe (universal-cal V5).
"""

import math
from dataclasses import dataclass

import numpy as np

from riscq.cal._vendor.pyrpe import Q, RobustPhaseEstimation

TWO_PI = 2.0 * np.pi

#: The three two-qubit state pairs an RPE CZ experiment measures. Each yields one accumulated
#: per-CZ angle; together they invert to the ZZ / IZ / ZI generator angles.
CZ_STATE_PAIRS = ((0, 1), (2, 3), (3, 1))

#: Ideal-CZ targets in the generator convention CZ = expm(-i/2 (θ_IZ·IZ + θ_ZI·ZI + θ_ZZ·ZZ)),
#: for which diag(1, 1, 1, -1) == CZ(π/2, π/2, -π/2).
CZ_TARGETS = {"ZZ": -np.pi / 2, "IZ": np.pi / 2, "ZI": np.pi / 2}

#: The X90 rotation angle RPE is calibrating towards.
X90_TARGET = np.pi / 2


def wrap(theta):
    """Wrap an angle (or array) into [-π, π) — the reference's `rectify_angle`."""
    return (theta + np.pi) % TWO_PI - np.pi


#: Minimum sqrt((2P_sin-1)^2 + (2P_cos-1)^2) at the shallowest depth for the run to mean anything.
#: Ideally the contrast is 1; it decays with depth as the state decoheres.
MIN_CONTRAST = 0.1


class RPEBranchError(RuntimeError):
    """No generation of the ladder can be trusted, so there is no angle to report.

    Two distinct failures raise this:

    - the consistency check rejected even the shallowest generation. The reference returns -1
      here, which it then uses as a *negative index*, silently trusting the deepest — least
      reliable — generation instead of failing;
    - the signal has no contrast. That one is invisible to the consistency check: a flat P = 1/2
      makes `arctan2(0, 0)` return 0 at every depth, which is perfectly self-consistent and
      reports as a converged angle of exactly zero — a dead qubit reads out as a perfect gate.
      Hence the explicit contrast floor.
    """


@dataclass
class Angles:
    """One RPE experiment's output.

    `estimates` maps each angle name to its per-generation ladder (one entry per depth);
    `errors` is the same ladder minus the ideal target. `last_good` indexes the deepest
    generation that survived the consistency check — every quantity a caller should act on is
    evaluated there, and everything past it is measured but discarded.
    """

    depths: tuple
    estimates: dict
    errors: dict
    last_good: int
    n_shots: int
    #: Per-depth signal contrast, ideally 1 and decaying towards 0 as the state decoheres. Worth
    #: plotting: it is what tells you whether the ladder was too deep for the qubit.
    contrast: np.ndarray = None
    #: The RAW per-experiment pyRPE ladders this angle set was inverted from, keyed by whatever
    #: labels the experiments (for `cz_angles`, the state pair), each
    #: {"ladder", "contrast", "last_good"}. Only the CZ estimator fills it, because only its
    #: inversion mixes three independent experiments: a composite angle that disagrees with the
    #: rest of the calibration says nothing about WHICH ladder moved, and the identities worth
    #: checking (`A(2,3) == A(3,1)` on a converged tree — spec 14 §3 finding 9) live on the raw
    #: ladders, not on the inverted angles.
    ladders: dict = None

    @property
    def trusted(self):
        """The trusted angle per name: the ladder evaluated at the last good generation."""
        return {name: float(ladder[self.last_good]) for name, ladder in self.estimates.items()}

    @property
    def trusted_error(self):
        """The trusted deviation from target per name — this is what a cal loop drives to zero."""
        return {name: float(ladder[self.last_good]) for name, ladder in self.errors.items()}

    @property
    def uncertainty(self):
        """Shot-noise-limited 1-sigma on the trusted angle: π / (2·L·sqrt(N_shots)).

        L is the last good *depth*. The reference hard-codes 2**last_good, which silently
        misreports the moment the ladder is not exactly 1, 2, 4, ...; indexing `depths` is the
        same number for a doubling ladder and correct for any other.
        """
        return np.pi / (2.0 * self.depths[self.last_good] * np.sqrt(self.n_shots))

    @property
    def last_good_depth(self):
        return self.depths[self.last_good]


def _ladder(cos_counts, sin_counts):
    """Run pyRPE over per-depth count pairs -> (per-generation angle ladder, last good index).

    `cos_counts` / `sin_counts` map depth -> (n_plus, n_minus). "Plus" is the outcome whose
    probability is (1 + cos(d·φ))/2 resp. (1 + sin(d·φ))/2; picking which measured bitstring that
    is belongs to the experiment, not here.
    """
    depths = sorted(cos_counts)
    if depths != sorted(sin_counts):
        raise ValueError("cos and sin count dicts must cover the same depths")

    q = Q()
    for d in depths:
        q.process_cos(d, np.asarray(cos_counts[d], dtype=int))
        q.process_sin(d, np.asarray(sin_counts[d], dtype=int))

    contrast = np.array([q.amplitude_N(d) for d in depths])
    if not contrast[0] > MIN_CONTRAST:
        raise RPEBranchError(
            f"no signal contrast at depth {depths[0]} ({contrast[0]:.3f} < {MIN_CONTRAST}); "
            "the qubit is not responding — an angle fitted to this would be meaningless"
        )

    analysis = RobustPhaseEstimation(q)
    last_good = analysis.check_unif_local(historical=True)
    if last_good < 0:
        raise RPEBranchError(
            f"no generation passed the consistency check (depths {depths}); "
            "the estimates disagree even at the shallowest rung"
        )
    return np.asarray(analysis.angle_estimates), min(last_good, _alive(contrast)), contrast


def _alive(contrast):
    """Index of the deepest rung whose signal is still above the contrast floor.

    The consistency check alone does not bound the ladder at the coherence time: once the state
    has decohered the two quadratures are both ~1/2, the extracted angle is arbitrary, and
    successive dead rungs can easily agree with each other to within their (by then very narrow)
    consistency windows — accepting a confidently wrong answer. Contrast decays with depth, so
    the trustworthy prefix is the run of rungs before it first drops through the floor.
    """
    below = np.flatnonzero(contrast <= MIN_CONTRAST)
    return int(below[0]) - 1 if below.size else len(contrast) - 1


def idle_angles(cos_counts, sin_counts, n_shots, depths=None):
    """1Q frequency RPE (the reference's gate='I'): phase accumulated over a bare idle.

    Circuit at depth d: prep (Y90 for cos, X90 for sin) -> idle · d -> Y(-90) -> measure.
    The estimated 'Z' angle is the phase per idle step; it is its own error (target 0), and
    `freq_error_hz` converts it to the detuning that produced it.
    """
    ladder, last_good, contrast = _ladder(cos_counts, sin_counts)
    z = wrap(ladder)
    return Angles(
        depths=tuple(depths or sorted(cos_counts)),
        estimates={"Z": z},
        errors={"Z": z},
        last_good=last_good,
        n_shots=n_shots,
        contrast=contrast,
    )


def x90_angles(direct_cos, direct_sin, interleaved_cos, interleaved_sin, n_shots, depths=None):
    """1Q amplitude + phase RPE (the reference's gate='X90').

    Two experiments run against the same X90. The **direct** one (X90 repeated d times) measures
    the rotation angle, so it sees amplitude error. The **interleaved** one repeats an echo block
    that cancels the rotation angle and amplifies the tilt of the rotation axis out of x̂, so it
    sees drive-phase error. The reference's linearized estimator recombines them into Cartesian
    axis components:

        ε      = θ_direct / (π/2) - 1                        (fractional over-rotation)
        θ_off  = sin(θ_int / 2) / (2 cos(π ε / 2))           (axis tilt toward ẑ)
        X      = (π/2)(1 + ε) cos(θ_off)   -> target π/2
        Z      = (π/2)(1 + ε) sin(θ_off)   -> target 0

    The ladders are truncated to their common length: the interleaved block spends four X90s per
    repetition, so its depth ladder is typically the shallower of the two.
    """
    direct, direct_k, direct_c = _ladder(direct_cos, direct_sin)
    interleaved, interleaved_k, interleaved_c = _ladder(interleaved_cos, interleaved_sin)

    n = min(len(direct), len(interleaved))
    direct, interleaved = wrap(direct[:n]), wrap(interleaved[:n])
    last_good = min(direct_k, interleaved_k, n - 1)
    contrast = np.minimum(direct_c[:n], interleaved_c[:n])

    epsilon = direct / X90_TARGET - 1.0
    tilt = np.sin(interleaved / 2.0) / (2.0 * np.cos(np.pi * epsilon / 2.0))
    magnitude = X90_TARGET * (1.0 + epsilon)
    x, z = magnitude * np.cos(tilt), magnitude * np.sin(tilt)

    return Angles(
        depths=tuple((depths or sorted(direct_cos))[:n]),
        estimates={"X": x, "Z": z},
        errors={"X": x - X90_TARGET, "Z": z},
        last_good=last_good,
        n_shots=n_shots,
        contrast=contrast,
    )


def x90_direct_angles(cos_counts, sin_counts, n_shots, depths=None):
    """The X90's rotation angle alone, from the direct (repeated-X90) experiment.

    This is what an amplitude calibration needs — the drive amplitude is linear in the rotation
    angle, so the correction is the ratio (pi/2)/angle. It carries no information about where the
    rotation axis points; recovering that needs the interleaved echo too (`x90_angles`).
    """
    ladder, last_good, contrast = _ladder(cos_counts, sin_counts)
    x = wrap(ladder)
    return Angles(
        depths=tuple(depths or sorted(cos_counts)),
        estimates={"X": x},
        errors={"X": x - X90_TARGET},
        last_good=last_good,
        n_shots=n_shots,
        contrast=contrast,
    )


def cz_angles(pair_counts, n_shots, depths=None):
    """CZ ZZ / IZ / ZI RPE (the reference's gate='CZ').

    `pair_counts` maps each of `CZ_STATE_PAIRS` to its (cos_counts, sin_counts). Each state pair
    is a Ramsey on one qubit with the other held in |0> or |1>, so it accumulates a different
    combination of the CZ's generator angles per repetition:

        A(0,1) = θ_IZ + θ_ZZ      (control |0>: ideally 0 — no phase on the target)
        A(2,3) = θ_IZ - θ_ZZ      (control |1>: ideally π)
        A(3,1) = θ_ZI - θ_ZZ      (target  |1>: ideally π)

    which inverts to ZZ = (A01 - A23)/2, IZ = (A01 + A23)/2, ZI = A31 + ZZ. Only the (0, 1)
    ladder is wrapped to [-π, π); the other two sit near π, where wrapping would straddle the cut.

    The three raw ladders come back on `Angles.ladders` as well as the inverted angles: the
    inversion is a 3->3 mix, so an angle that contradicts the rest of the calibration is only
    diagnosable against the ladder it came from (spec 14 §3 finding 9).
    """
    missing = set(CZ_STATE_PAIRS) - set(pair_counts)
    if missing:
        raise ValueError(f"missing counts for CZ state pair(s) {sorted(missing)}")

    per_pair, last_goods, contrasts = {}, [], []
    for pair in CZ_STATE_PAIRS:
        cos_counts, sin_counts = pair_counts[pair]
        ladder, k, c = _ladder(cos_counts, sin_counts)
        per_pair[pair] = {"ladder": wrap(ladder) if pair == (0, 1) else ladder,
                          "contrast": c, "last_good": k}
        last_goods.append(k)
        contrasts.append(c)

    n = min(len(d["ladder"]) for d in per_pair.values())
    a01, a23, a31 = (per_pair[p]["ladder"][:n] for p in CZ_STATE_PAIRS)
    contrast = np.min([c[:n] for c in contrasts], axis=0)

    zz = 0.5 * (a01 - a23)
    estimates = {"ZZ": zz, "IZ": 0.5 * (a01 + a23), "ZI": a31 + zz}
    errors = {name: ladder - CZ_TARGETS[name] for name, ladder in estimates.items()}

    return Angles(
        depths=tuple((depths or sorted(pair_counts[(0, 1)][0]))[:n]),
        estimates=estimates,
        errors=errors,
        last_good=min(*last_goods, n - 1),
        n_shots=n_shots,
        contrast=contrast,
        ladders=per_pair,
    )


def freq_error_hz(angle_z, idle_time):
    """Phase accumulated per idle step -> the detuning in Hz that produced it.

    A qubit detuned by Δf from its drive accumulates 2π·Δf·t of phase over an idle of length t,
    so Δf = θ / (2π·t). Subtract this from the config frequency. The estimate is unambiguous only
    while |Δf| < 1/(2·t) — a 100 ns idle resolves ±5 MHz, which is why the ladder starts short.
    """
    return angle_z / (TWO_PI * idle_time)


def vz_correction(x_angle, z_angle):
    """The virtual-Z pair shift that straightens a rotation axis tilted out of the drive plane.

    RPE reports the gate as exp(-i(X·sigma_x + Z·sigma_z)/2) — a rotation of Omega = hypot(X, Z)
    about an axis in the x-z plane. Its symmetric decomposition is Rz(beta)·Rx(X')·Rz(beta) with

        beta = atan2(Z·tan(Omega/2), Omega)

    (the same algebra `Phase`'s ac-Stark model uses, and beta -> (2/pi)·Z for a small tilt on a
    quarter turn — NOT Z/2), so the pulse leaves beta of Z on EACH side and adding beta to BOTH
    virtual-Z slots cancels it exactly. The frame the ladder measures already includes the config's
    current pair, so this is the SHIFT to apply, not the new pair.
    """
    omega = math.hypot(x_angle, z_angle)
    return math.atan2(z_angle * math.tan(omega / 2.0), omega)


def damped_update(old, correction, gain=0.5, max_step=None, multiplicative=False):
    """Apply one damped, clipped correction to a config value.

    This is the whole feedback rule (spec 14 §4): the reference wraps RPE in Kalman/CMA
    optimizers, but the walkthrough's own guidance is a damped clip update, so that is what we
    adopt. Damping keeps a wrong-branch estimate from throwing the parameter across the map;
    clipping bounds the damage when it does.
    """
    step = gain * correction
    if max_step is not None:
        step = float(np.clip(step, -max_step, max_step))
    return old * (1.0 + step) if multiplicative else old + step
