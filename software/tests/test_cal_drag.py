"""Host tests for the DRAG calibration (spec 14 F3): the FAST_DRAG spectral optimizer.

The optimizer is host-only arithmetic — an envelope, an FFT and an argmin — so it is checked against
a REIMPLEMENTATION of qcal's own loop (optimization/pulse.py:19-110) on the same envelope: same
padding, same axis convention, same coordinate-descent order. If the two ever disagree, one of them
has drifted from the paper's FAST DRAG.
"""

import numpy as np
import pytest

from riscq.cal import Config, Leakage
from riscq.cal.analysis.drag import N_GRID, PAD, W_GRID, ef_spectral_weight, optimize_fast_drag
from riscq.cal.base import GATE_CH, batches
from riscq.map import SocMap, SocParams
from riscq.pulses import envelopes

from pathlib import Path

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
SIM2Q = CONFIGS / "sim-2q.json"
PARAMS = SocParams.load(CONFIGS / "zcu216-14q.json")
M = SocMap(PARAMS)


def _cfg(q=0, n=2, weights=(0.1, 0.1)):
    """An X6Y3-shaped FAST_DRAG gate: 35 ns, the config-of-record kwargs."""
    c = Config()
    c[f"qubit/{q}/freq"] = 5.4988e9
    c[f"qubit/{q}/EF/freq"] = 5.2466e9                 # 252 MHz below GE: the leakage transition
    c[f"qubit/{q}/x90/env"] = "FAST_DRAG"
    c[f"qubit/{q}/x90/dur"] = 35e-9
    c[f"qubit/{q}/x90/amp"] = 0.1056
    c[f"qubit/{q}/x90/phase"] = 0.0
    c[f"qubit/{q}/x90/kwargs"] = {"alpha": 1.0, "anh": -259.56e6, "N": int(n),
                                  "weights": list(weights)}
    return c


def _qcal_score(cfg, q, kw):
    """qcal's own scoring loop, reimplemented from optimization/pulse.py:57-72: build the envelope,
    zero-pad 1000 each side, FFT + fftshift, shift the frequency axis by f_GE, and read the magnitude
    at the bin nearest f_EF."""
    ch = M.channel(GATE_CH)
    n = batches(cfg[f"qubit/{q}/x90/dur"], M) * ch.samples_per_line
    rate = ch.samples_per_line * PARAMS.dsp_freq_hz
    env = envelopes.build("FAST_DRAG", n, rate, **kw)
    padded = np.pad(env, (PAD, PAD), mode="constant", constant_values=0.0 + 0.0j)
    spectrum = np.abs(np.fft.fftshift(np.fft.fft(padded)))
    freqs = np.fft.fftshift(np.fft.fftfreq(len(padded), 1.0 / rate)) + cfg[f"qubit/{q}/freq"]
    idx = int(np.abs(freqs - cfg[f"qubit/{q}/EF/freq"]).argmin())
    return float(spectrum[idx])


def test_spectral_weight_matches_qcals_scoring():
    """Our `ef_spectral_weight` is qcal's number: the axis-shifted lookup at f_EF and our baseband
    lookup at f_EF − f_GE are the same bin."""
    cfg = _cfg()
    for n in (2, 5, 9):
        kw = {**cfg["qubit/0/x90/kwargs"], "N": n}
        assert ef_spectral_weight(cfg, 0, M, "x90", kw) == pytest.approx(_qcal_score(cfg, 0, kw))


def test_optimizer_matches_qcals_coordinate_descent():
    """The full optimum agrees with qcal's loop run independently: N first at its argmin, then each
    weight in turn against the ALREADY-chosen ones (the order is part of the answer)."""
    cfg = _cfg()
    got = optimize_fast_drag(cfg, 0, M)["qubit/0/x90/kwargs"]

    kw = dict(cfg["qubit/0/x90/kwargs"])              # qcal's loop, independently
    kw["N"] = int(N_GRID[int(np.argmin([_qcal_score(cfg, 0, {**kw, "N": n}) for n in N_GRID]))])
    for i in range(len(kw["weights"])):
        scores = []
        for w in W_GRID:
            trial = list(kw["weights"])
            trial[i] = float(w)
            scores.append(_qcal_score(cfg, 0, {**kw, "weights": trial}))
        kw["weights"] = list(kw["weights"])
        kw["weights"][i] = float(W_GRID[int(np.argmin(scores))])

    assert got["N"] == kw["N"]
    assert got["weights"] == kw["weights"]
    assert got["alpha"] == 1.0 and got["anh"] == -259.56e6      # untouched kwargs ride along


def test_optimizer_recovers_the_optimum_from_detuned_kwargs():
    """The point of the exercise: started away from the optimum, the descent walks back to it and
    lowers the EF spectral weight. (Detuned to N=7 / weights=[5, 5], ~20x the leakage.)"""
    cfg = _cfg(n=7, weights=(5.0, 5.0))
    before = ef_spectral_weight(cfg, 0, M)
    kw = optimize_fast_drag(cfg, 0, M)["qubit/0/x90/kwargs"]
    after = ef_spectral_weight(cfg, 0, M, "x90", kw)
    print(f"\n[drag] EF spectral weight {before:.4g} -> {after:.4g}  (N {7} -> {kw['N']}, "
          f"weights {[5.0, 5.0]} -> {kw['weights']})")
    assert after < before / 2
    assert (kw["N"], kw["weights"]) == (2, [0.1, 0.1])


def test_the_x6y3_kwargs_are_a_fixed_point_of_the_optimizer():
    """The X6Y3 config of record was tuned with this very optimizer, so re-running it must not move:
    N = 2 and weights = [0.1, 0.1] are its argmin on qcal's default grids.

    Both grids are MONOTONE here (the EF weight rises 0.0027 -> 0.081 across N, and 0.0027 -> 0.0031
    across each weight), so the optimum sits at the smallest candidate — i.e. the true optimum may lie
    BELOW the grid, and this answer is "at or below the smallest candidate". qcal's grids have the
    same edge, and the `Leakage` measurement is what refines past it."""
    cfg = _cfg()
    start = dict(cfg["qubit/0/x90/kwargs"])
    kw = optimize_fast_drag(cfg, 0, M)["qubit/0/x90/kwargs"]
    assert (kw["N"], kw["weights"]) == (start["N"], list(start["weights"])) == (2, [0.1, 0.1])
    assert ef_spectral_weight(cfg, 0, M, "x90", kw) == ef_spectral_weight(cfg, 0, M)


def test_optimizer_rejects_a_non_fast_drag_gate():
    cfg = _cfg()
    cfg["qubit/0/x90/kwargs"] = {"ramp_fraction": 0.25}
    with pytest.raises(AssertionError, match="FAST_DRAG kwargs"):
        optimize_fast_drag(cfg, 0, M)


# ── Leakage: the measured refinement (spec 14 F3) ──
#
# NO co-sim gate, and that is a finding, not an omission: the qutrit model has no leakage path at all.
# `ThreeLevelModel.adc_batch` demodulates the gate DAC against BOTH transitions and rotates only the
# stronger one, so a GE drive moves {|0>, |1>} and never populates |2>. A planted-leakage knob would
# have to be invented in the simulator. The class is therefore gated host-pure — the real compile, the
# real per-point config edit and the real argmin all run — and its physics is unvalidated until the
# board (spec 14 F3 note).

_MEANS = np.array([[10.0, 0.0], [-5.0, 8.66], [-5.0, -8.66]])      # |0>/|1>/|2> IQ centroids


def _clf(seed=3):
    from riscq.cal.analysis.classifier import ClassifierN
    rng = np.random.default_rng(seed)
    return ClassifierN([_MEANS[k] + 0.1 * rng.standard_normal((30, 2)) for k in range(3)])


def _levels_iq(p, shots):
    """A RAW `out` whose P(|2>) is exactly `p`: round(p·shots) shots on the |2> centroid, rest on |1>."""
    iq = np.zeros((shots, 2))
    n2 = int(round(float(np.clip(p, 0.0, 1.0)) * shots))
    iq[:n2] = _MEANS[2]
    iq[n2:] = _MEANS[1]
    return iq.reshape(-1)


def _leakage_cfg(q=0):
    """A co-sim-scaled Config for the compile: a square gate, so the sweep is over the vz pair."""
    c = Config()
    c[f"qubit/{q}/freq"] = 50e6
    c[f"qubit/{q}/x90/amp"] = 0.5
    c[f"qubit/{q}/x90/vz"] = [0.0, 0.0]
    c[f"qubit/{q}/T1"] = 2.4e-7
    c[f"readout/{q}/freq"] = 1e8
    c[f"readout/{q}/amp"] = 0.5
    c[f"readout/{q}/dur"] = 1.12e-7
    c[f"readout/{q}/demod/dur"] = 8e-8
    c["reset/relax"] = 6.4e-6
    return c


def test_leakage_picks_the_minimum_of_a_planted_p2(responder):
    """(F3 gate) `Leakage` end-to-end host-pure: P(|2>) is a planted parabola over the swept
    virtual-Z pair, answered through the responder. The class's REAL per-point compile (a deep
    n×X90 train), the ClassifierN decode and the argmin write-back all run — and it must pick the
    planted minimum, recompiling once per point because a vz pair is baked into the gate table,
    not a runtime param."""
    r = responder(SIM2Q)
    cfg = _leakage_cfg()
    phases = [-0.2, -0.1, 0.0, 0.1, 0.2]
    star = 0.1                                          # the planted least-leaky phase
    state = {"runs": 0}

    @r.answer
    def _(progs, params):
        p = 0.05 + 2.0 * (phases[state["runs"] % len(phases)] - star) ** 2
        state["runs"] += 1
        return {q: {"out": _levels_iq(p, int(prog.bindings["shots"]))}
                for q, prog in progs.items()}

    res = Leakage(cfg, 0, _clf(), "qubit/{q}/x90/vz", [[p, p] for p in phases],
                  n_gates=8, shots=8).run(r.drv)

    assert state["runs"] == len(phases) == len(r.setups)     # ONE compile + run per point
    assert res.proposal == {"qubit/0/x90/vz": [star, star]}
    assert res.data[0]["y"].argmin() == phases.index(star)
    assert cfg["qubit/0/x90/vz"] == [0.0, 0.0]          # the original config is untouched
    print(f"\n[leakage] P(2)={np.round(res.data[0]['y'], 3).tolist()} -> "
          f"vz={res.proposal['qubit/0/x90/vz']}")

    # maximize=True is qcal's diagnostic direction — the same sweep, the other extremum
    state["runs"] = 0
    rmax = Leakage(cfg, 0, _clf(), "qubit/{q}/x90/vz", [[p, p] for p in phases], n_gates=8, shots=8,
                   maximize=True).run(r.drv)
    assert rmax.proposal == {"qubit/0/x90/vz": [-0.2, -0.2]}


def test_leakage_captures_in_the_classifiers_zero_demod_frame(responder):
    """(spec 14 finding 7) `Leakage` reads P(|2>) through a pre-trained `ClassifierN`, whose
    training captures are deliberately zero-frame (`Measure.levels` pins `phase=0.0`). A
    config-frame capture would arrive rotated by the stored demod phase relative to the
    classifier's means — 0 on the co-sim configs, −109.9°…+39.0° on X6Y3 — so the train must
    capture at phase 0 too. (The res-bit cals are the deliberate opposite: there the stored phase
    IS the discriminator.) Asserted on the compiled demod slot, which is where the frame is baked."""
    r = responder(SIM2Q)
    cfg = _leakage_cfg()
    cfg["readout/0/demod/phase"] = -1.918                # the config frame the res bit would use
    r.answer(lambda progs, params: {q: {"out": _levels_iq(0.1, int(p.bindings["shots"]))}
                                    for q, p in progs.items()})
    Leakage(cfg, 0, _clf(), "qubit/{q}/x90/vz", [[0.0, 0.0], [0.1, 0.1]], n_gates=4,
            shots=8).run(r.drv)
    assert len(r.setups) == 2                            # one compile per swept point
    assert [prog.tables["tbl_demod"][0][0] for s in r.setups for prog in s.values()] == [0, 0]
