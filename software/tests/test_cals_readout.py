"""L0 gates for universal-cal V2: each readout class on the base recovers the planted readout
physics on the host-pure responder — the |0>/|1> clusters' own chain angle, the Gaussian
assignment error of a known cluster separation, the dispersive Lorentzian's argmax. The answers
read the run state generically (the prep / knob runtime params, the compiled demod frame).

The rule (tests/responder.py): every `@r.answer` computes its response from FIRST PRINCIPLES —
the textbook readout the class is running — and never from riscq.cal.
"""

import math
from pathlib import Path

import numpy as np
import pytest
from scipy.special import erfc

from riscq.cal.cals import (Fidelity, Punchout, ReadoutCalibration, ReadoutFidelity, Resonator,
                            Separation, Window)
from riscq.pulses import units
from tests.responder import counts, counts_heralded, iq_sum, raw_iq
from tests.cal_fixtures import _MEANS, _cfg, _cfg2, _clf

SIM2Q = Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"

# ── the analytic readout the answers are built on (first principles, never riscq.cal) ──

RO_A_OVER_SIGMA = 2.2    # cluster half-separation in units of the per-quadrature readout noise:
#                          means 4.4σ apart = qcal SNR 1.1, well over ReadoutCalibration's 0.5 floor
CHAIN_PHASE = 0.8        # the readout chain's absolute IQ angle in the ZERO demod frame (rad)
IQ_SCALE = 1e5           # decoder counts per unit of readout response (the integrator's own scale;
#                          the `out` words are integers, so a response of O(1) has to be seated)


def _s(n, m):
    return units.ns(n, m.params) * 1e-9


def _misassign(snr):
    """The Gaussian assignment error of a discriminator `snr` σ away from each cluster: the noise
    tail Φ(−snr) that crosses the threshold."""
    return 0.5 * erfc(np.asarray(snr, float) / math.sqrt(2))


def _antipodal_iq(prep, shots, theta, seed=0, a_over_sigma=RO_A_OVER_SIGMA):
    """The textbook dispersion-free readout: |0> and |1> emit the same tone π out of phase, so the
    two clusters sit at ±A along the readout chain's own axis (`CHAIN_PHASE`) with isotropic
    Gaussian noise σ. A demod carrier compiled at `theta` rotates the measured phasor by e^{+iθ}."""
    rng = np.random.default_rng(seed + prep)
    mean = (1 - 2 * prep) * a_over_sigma * np.exp(1j * CHAIN_PHASE)
    noise = rng.normal(0, 1, shots) + 1j * rng.normal(0, 1, shots)
    return IQ_SCALE * (mean + noise) * np.exp(1j * theta)


def _res_count(z) -> int:
    """The on-chip discriminator: sign(sumR) against a ZERO threshold — |0> on +real reads res=0,
    |1> on −real reads res=1 (base.res_sign's +1 convention)."""
    return int(np.count_nonzero(np.asarray(z).real < 0))


def _lorentzian(f_hz, f_r, kappa, chi, state):
    """A driven resonator's response, pulled to f_r + χ by |0> (state=+1) and f_r − χ by |1>
    (state=−1): S(f) = 1 / (1 + 2i(f − f_r ∓ χ)/κ). Textbook, and the reason a dispersive sweep has
    two different answers — argmax |S(|0>)| sits at f_r + χ, argmax |S(|0>) − S(|1>)| at f_r."""
    return 1.0 / (1.0 + 2j * (np.asarray(f_hz, float) - f_r - state * chi) / kappa)


# ── reading the run state ──

def _src(prog, params):
    return {**prog.bindings, **(params or {})}


def _prep(params) -> int:
    """The PREP rerun param — the FIRST runtime param of every prepped readout class."""
    return int(params["r0"])


def _demod_phase(prog) -> float:
    t = prog.tables.get("demod") or prog.tables["tbl_demod"]
    return t[0][0] * math.pi / (1 << 15)


def _q16(prog, params, x0="x0", dx="dx0"):
    s = _src(prog, params)
    n = int(prog.bindings["npts"])
    return ((int(s[x0]) + np.arange(n, dtype=np.int64) * int(s[dx])) >> 16).astype(np.int64)


def _cnt(p1, prog):
    shots = int(prog.bindings["shots"])
    return counts_heralded(p1, shots) if prog.bindings.get("herald") else counts(p1, shots)


def _is_raw(prog) -> bool:
    return prog.bindings.get("mode") == 1


def _antipodal(progs, params):
    out = {}
    for q, prog in progs.items():
        z = _antipodal_iq(_prep(params[q]), int(prog.bindings["shots"]), _demod_phase(prog))
        out[q] = {"out": raw_iq(z) if _is_raw(prog) else _cnt([_res_count(z) / len(z)], prog)}
    return out


def test_readout_calibration_finds_the_chain_angle(responder, socmap):
    """The clusters are planted antipodal along the chain's own angle CHAIN_PHASE and captured in
    the ZERO demod frame, so the demod phase that lands the |0>→|1> axis on +real is −CHAIN_PHASE;
    the separation is the planted A/σ ratio under qcal's ‖Δm‖/(2σ₀+2σ₁) definition."""
    m = socmap
    r = responder(SIM2Q)
    r.answer(_antipodal)
    cfg = _cfg(m, x90_amp=0.495)
    cfg["readout/0/demod/phase"] = 1.0                 # the capture frame is forced to 0 regardless
    res = ReadoutCalibration(cfg, 0, shots=64).run(r.drv)
    assert res.ok
    assert _demod_phase(r.setups[-1][0]) == pytest.approx(0.0, abs=1e-9)
    assert res.proposal["readout/0/demod/phase"] == pytest.approx(-CHAIN_PHASE, abs=0.1)
    assert res.proposal["readout/0/res_sign"] == 1
    assert res.data[0]["separation"] == pytest.approx(RO_A_OVER_SIGMA / 2, rel=0.2)
    assert res.data[0]["res_fidelity"] > 0.75


def test_readout_fidelity_is_the_planted_gaussian_error(responder, socmap):
    """Under the fixed on-chip discriminator (a zero threshold on the real part) the clusters'
    projection is A·cos(CHAIN_PHASE) σ from it, so both off-diagonals are Φ(−A cos φ)."""
    m = socmap
    r = responder(SIM2Q)
    r.answer(_antipodal)
    res = ReadoutFidelity(_cfg(m, x90_amp=0.495), 0, shots=400).run(r.drv)
    eps = float(_misassign(RO_A_OVER_SIGMA * math.cos(CHAIN_PHASE)))
    assert np.allclose(res.data[0]["confusion"], [[1 - eps, eps], [eps, 1 - eps]], atol=0.03)
    assert res.data[0]["fidelity"] == pytest.approx(1 - eps, abs=0.03)
    assert res.proposal["readout/0/fidelity"] == res.data[0]["fidelity"]


def test_readout_fidelity_3level_confusion(responder, socmap):
    """Three perfectly separated clouds, one per prepared level (the |2> prep is its own run, the
    GE π + the stored EF X), decoded by the pre-trained 3-level classifier: the identity."""
    m = socmap
    r = responder(SIM2Q)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            level = 2 if "ReadoutFidelity3_ef" in prog.c_source else _prep(params[q])
            n = int(prog.bindings["shots"])
            out[q] = {"out": np.tile(_MEANS[level], (n, 1)).reshape(-1).astype(np.int64)}
        return out

    cfg = _cfg(m, x90_amp=0.495)
    cfg["qubit/0/EF/freq"] = 45e6
    cfg["qubit/0/EF/x/amp"] = 0.6
    res = ReadoutFidelity(cfg, 0, shots=16, n_levels=3, classifier=_clf()).run(r.drv)
    assert np.array_equal(res.data[0]["confusion"], np.eye(3))
    assert res.proposal["readout/0/fidelity"] == 1.0
    assert res.proposal["readout/0/cmat"] == np.eye(3).tolist()


def test_fidelity_picks_the_planted_drive_amp(responder, socmap):
    """The assignment error is planted as a discriminator SNR peaking at a drive amplitude `star`
    inside the swept span; the argmax of ½[P(0|0) + P(1|1)] is the grid point nearest it."""
    m = socmap
    r = responder(SIM2Q)
    a_star = 0.014
    star = units._amp_code(a_star)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            a = _q16(prog, params[q])
            eps = _misassign(2.5 - 0.02 * np.abs(a - star))
            out[q] = {"out": _cnt(eps if _prep(params[q]) == 0 else 1 - eps, prog)}
        return out

    cfg = _cfg(m, x90_amp=0.495)
    cfg["readout/0/amp"] = 0.012
    points = 7
    res = Fidelity(cfg, 0, amp_span=0.005, points=points, shots=64).run(r.drv)
    assert res.ok
    x, y = res.data[0]["x"], res.data[0]["y"]
    assert np.argmax(y) == int(np.argmin(np.abs(x - a_star)))
    assert res.proposal["readout/0/amp"] == pytest.approx(a_star, abs=0.010 / (points - 1))
    assert y.max() == pytest.approx(1 - float(_misassign(2.5)), abs=0.03)


def test_separation_argmax_is_the_dispersive_centre(responder, socmap):
    """A dispersive readout pulled ±χ by the qubit state: the |0> and |1> Lorentzians are farthest
    APART at the bare f_r (not at either peak), so the two-state separation argmax — and the
    proposed readout frequency — is the centre of the sweep, where f_r was planted."""
    m = socmap
    r = responder(SIM2Q)
    # the sweep walks the RO-DRIVE code, so the response is evaluated at 4·code (the demod code the
    # class matches to it); χ is one full sweep step there, κ a couple, so the ±χ pull is resolvable
    f_r, kappa, chi = 2048, 96.0, 48.0

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            codes = _q16(prog, params[q])
            shots = int(prog.bindings["shots"])
            prep = _prep(params[q])
            rows = []
            for c in codes:
                s = _lorentzian(4 * c, f_r, kappa, chi, +1 if prep == 0 else -1)
                rng = np.random.default_rng(int(c) * 7 + prep)
                rows.append(IQ_SCALE * (s * 3 + (rng.normal(0, 1, shots) + 1j * rng.normal(0, 1, shots))))
            out[q] = {"out": raw_iq(np.concatenate(rows))}
        return out

    cfg = _cfg(m, x90_amp=0.495)
    points = 7
    # the span must resolve the ±χ pull: χ = 6 DEMOD codes = 24 freq codes, so sweep ±2χ
    res = Separation(cfg, 0, span=units.code_to_freq(36, m.params), points=points, shots=24).run(r.drv)
    x, y = res.data[0]["x"], res.data[0]["y"]
    assert np.argmax(y) == points // 2                          # the bare f_r, the sweep centre
    assert res.proposal["readout/0/freq"] == pytest.approx(float(cfg["readout/0/freq"]),
                                                           abs=units.code_to_freq(1, m.params))
    # |0> alone peaks χ ABOVE f_r — the reason the class scores separation, not magnitude
    assert np.argmax(res.data[0]["mag0"]) > points // 2


def test_resonator_magnitude_peaks_on_the_planted_pole(responder, socmap):
    """A single-state Lorentzian planted at demod... freq code 520: the coherently summed |iq| over
    the evenly spaced list must peak on the frequency of that code."""
    m = socmap
    r = responder(SIM2Q)
    pole = 520

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            codes = _q16(prog, params.get(q))
            z = 1e4 * _lorentzian(codes, pole, 30.0, 0.0, 1)
            out[q] = {"out": iq_sum(z, prog.bindings["shots"], prog.bindings["sh"])}
        return out

    cfg = _cfg(m, x90_amp=0.495)
    codes = np.arange(500, 541, 5)
    res = Resonator(cfg, 0, units.code_to_freq(codes, m.params), shots=16).run(r.drv)
    mag = res.data[0]["mag"]
    assert int(np.argmax(mag)) == int(np.flatnonzero(codes == pole)[0])
    assert res.data[0]["x"][int(np.argmax(mag))] == pytest.approx(
        units.code_to_freq(pole, m.params), abs=units.code_to_freq(1, m.params))


def test_punchout_rows_track_the_drive_amp(responder, socmap):
    """The |0> response is planted as (drive amp) × a Lorentzian at the sweep centre: every
    amplitude row peaks at the centre, and the rows scale by the ratio of the amplitude CODES."""
    m = socmap
    r = responder(SIM2Q)

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            codes = _q16(prog, params[q])
            amp = int(params[q]["r0"]) >> 16
            shots = int(prog.bindings["shots"])
            z = np.repeat(1e3 * amp / 1000.0 * _lorentzian(4 * codes, 2048, 40.0, 0.0, 1), shots)
            out[q] = {"out": raw_iq(z)}
        return out

    cfg = _cfg(m, x90_amp=0.495)
    amps, points = (0.05, 0.2), 5
    res = Punchout(cfg, 0, amps=amps, span=units.code_to_freq(6, m.params), points=points,
                   shots=4).run(r.drv)
    mag = res.data[0]["mag"]
    assert mag.shape == (len(amps), points)
    assert list(np.argmax(mag, axis=1)) == [points // 2] * len(amps)
    ratio = units._amp_code(amps[1]) / units._amp_code(amps[0])
    assert np.allclose(mag[1] / mag[0], ratio, rtol=2e-3)   # the decoder's sums are integers


@pytest.mark.parametrize("knob", ["demod/dur", "dur", "demod/delay"])
def test_window_picks_the_planted_optimum(responder, socmap, knob):
    """Each timing knob is planted with its own optimum per qubit — the window/drive length that
    matches the response tone, or the delay that centres it — and the fidelity argmax over the
    candidate list must write exactly that value back for BOTH qubits at once."""
    m = socmap
    r = responder(SIM2Q)
    tone, tau = {0: 32, 1: 96}, {0: 16, 1: 48}
    centre = tau if knob == "demod/delay" else tone

    def value(params):
        """The knob's runtime param: seated for a slot dur, plain for the demod delay."""
        return int(params["r0"]) if knob == "demod/delay" else int(params["r0"]) >> 16

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            v = value(params[q])
            if knob == "demod/delay":
                snr = 0.2 * max(0, tone[q] - abs(v - tau[q])) / math.sqrt(tone[q])
            else:
                snr = 0.2 * min(v, tone[q]) / math.sqrt(v)
            eps = _misassign(snr)
            prep = int(params[q]["r1"])
            out[q] = {"out": _cnt([eps if prep == 0 else 1 - eps], prog)}
        return out

    durs = {q: [_s(max(1, t - 16), m), _s(t, m), _s(t + 16, m)] for q, t in centre.items()}
    cfg = _cfg2(m, x90_amp=0.495)
    for q in tone:
        cfg[f"readout/{q}/demod/dur"] = _s(3 * max(tone.values()) // 2, m)
        cfg[f"readout/{q}/dur"] = _s(2 * max(tone.values()), m)
    res = Window(cfg, [0, 1], durs=durs, shots=200, knob=knob).run(r.drv)
    assert res.ok
    for q in tone:
        assert res.proposal[f"readout/{q}/{knob}"] == pytest.approx(_s(centre[q], m))
        assert int(np.argmax(res.data[q]["y"])) == 1        # the middle candidate is the plant
