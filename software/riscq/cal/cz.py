"""The pair's CZ in the Config (spec two-qubit/01 §2, /04 §4.2): the `two_qubit/(i, j)/…` accessors,
the pulse-list resolvers (drive indices, virtual-Z entries by channel, the EF-sandwich layout), the
write-back builders and the Ramsey-peak / signed-fringe analysis helpers, moved from
riscq.cal.twoqubit at universal-cal V6."""

from __future__ import annotations

import copy
import math
import re

import numpy as np

from riscq.cal.config import _amp_phase
from riscq.cal.base import batches, gate_ch
from riscq.map import pack16
from riscq.pulses import Pulse, envelopes, units


def pair_key(pair) -> str:
    """The `two_qubit` config key for a qubit pair — qcal's tuple repr, space included:
    (0, 1) -> "(0, 1)" (== str((0, 1)), so a config stays interchangeable with qcal, spec 01 §2)."""
    i, j = pair
    return f"({int(i)}, {int(j)})"


def coupler_core(cfg, pair) -> int:
    """The homogeneous-array core index that plays coupler (i, j)'s CZ drive. A coupler is an ordinary
    extra core whose gate channel is the CZ drive (spec 01 §1); the qubit<->core map is identity (qubit
    q on core q) and each coupler's core is carried in the config as `two_qubit/(i, j)/core`, not
    hard-coded — sim-2q1c maps coupler (0, 1) to core 2."""
    return int(cfg[f"two_qubit/{pair_key(pair)}/core"])


def calc_cz_frequency(cfg, pairs, state: str = "02", form: str = "parametric") -> None:
    """Seed each pair's CZ drive frequency from the 1Q spectrum — pure config arithmetic, no hardware.
    The CZ activates the |11> <-> |{02, 20}> avoided crossing, and `form` picks the gate form's seed
    arithmetic (spec 04 §4.4) from the two two-excitation levels

        f_11 = f_GE(i) + f_GE(j)                                   # the |11> energy
        f_02 = f_GE(j) + f_EF(j)   /   f_20 = f_GE(i) + f_EF(i)    # the intermediate |02> / |20>

      'parametric' — the coupler-flux tone at the level DETUNING: CZ/freq = |f_state − f_11| (qcal
                     `calculate_parametric_cz_frequency`, cz.py:37-77);
      'drive'      — the two-qubit-drive form's IN-BAND tone: CZ/freq = (f_11 + f_state)/4, half the
                     two-photon midpoint (qcal's commented-out midpoint form — reproduces X6Y3's
                     plain pairs' calibrated freqs to within ~100 MHz, spec 04 §1).

    GE freqs are `qubit/{q}/freq`, EF freqs `qubit/{q}/EF/freq` (the EF prerequisite, Q1) — both in Hz.
    Writes `two_qubit/(i, j)/CZ/freq`. `state` picks the intermediate level ('02' the target's EF, the
    default; '20' the control's — walkthrough stage 4)."""
    assert state in ("02", "20"), f"intermediate state must be '02' or '20', got {state!r}"
    assert form in ("parametric", "drive"), f"form must be 'parametric' or 'drive', got {form!r}"
    for pair in pairs:
        i, j = int(pair[0]), int(pair[1])
        f_ge_i, f_ge_j = float(cfg[f"qubit/{i}/freq"]), float(cfg[f"qubit/{j}/freq"])
        f_11 = f_ge_i + f_ge_j
        f_02 = f_ge_j + float(cfg[f"qubit/{j}/EF/freq"])
        f_20 = f_ge_i + float(cfg[f"qubit/{i}/EF/freq"])
        f_int = f_02 if state == "02" else f_20
        cfg[f"two_qubit/{pair_key(pair)}/CZ/freq"] = \
            (f_11 + f_int) / 4 if form == "drive" else abs(f_int - f_11)


def joint_populations(bits_by_core: dict, order) -> np.ndarray:
    """Joint two-qubit populations [P(00), P(01), P(10), P(11)] from per-core per-shot bits, zipped by
    SHOT INDEX (spec 01 §5). Both cores record their shots in the same deterministic order — a fixed
    grid walked by one program per core in one run — so shot k on `order[0]` (control) and shot k on
    `order[1]` (target) are the same repetition. `bits_by_core[c]` is a 1-D array of that core's 0/1
    classified shots; the two lengths MUST match — a mismatch means the per-core streams desynced (the
    alignment contract, not a silent truncation). The result is indexed 2*b_control + b_target."""
    ctrl, tgt = order
    bc = np.asarray(bits_by_core[ctrl], dtype=int).ravel()
    bt = np.asarray(bits_by_core[tgt], dtype=int).ravel()
    if bc.shape != bt.shape:
        raise ValueError(f"shot-count mismatch: control {bc.shape} vs target {bt.shape} — streams desynced")
    counts = np.bincount(2 * bc + bt, minlength=4)
    return counts / counts.sum()


def _signed_fft_freq(t: np.ndarray, z: np.ndarray) -> float:
    """The signed dominant FFT frequency of a complex signal z on the uniform grid t (qcal's
    `est_freq_fft`, zz.py:539-576): the two conditional Ramseys can run at opposite-sign fringe
    frequencies, and a real cosine fit only gives |f| — the complex quadrature I − jQ resolves the
    SIGN. Returns cycles per unit of t (Hz when t is seconds)."""
    n = len(t)
    dt = (t[-1] - t[0]) / (n - 1) if n > 1 else 1.0
    spec = np.abs(np.fft.fft(z - np.mean(z)))
    freqs = np.fft.fftfreq(n, d=dt)
    return float(freqs[int(np.argmax(spec))])


def _fit_complex_freq(t: np.ndarray, z: np.ndarray):
    """Fit the complex quadrature z = I − jQ to a decaying phasor A·e^{i(2πf·t+φ)}·e^{−t/τ} and return
    (signed f, ok). Using BOTH quadratures at once — and seeding f from the complex FFT — pins the
    fringe's sign AND magnitude in one fit, which is far steadier on the short, noisy, low-contrast
    co-sim fringes than an unsigned real-cosine fit patched with a separate FFT sign (that pair let one
    branch lock onto a spurious harmonic). curve_fit runs on the stacked [Re, Im]."""
    from scipy.optimize import curve_fit
    t = np.asarray(t, float)
    z = np.asarray(z, complex)
    span = (t[-1] - t[0]) or 1.0
    seed = _signed_fft_freq(t, z)

    def model(_t, ar, ai, f, tau):
        e = (ar + 1j * ai) * np.exp(1j * 2 * np.pi * f * _t) * np.exp(-_t / tau)
        return np.concatenate([e.real, e.imag])

    p0 = [z[0].real or 1e-3, z[0].imag or 1e-3, seed, span]
    try:
        popt, pcov = curve_fit(model, t, np.concatenate([z.real, z.imag]), p0=p0, maxfev=20000)
    except (RuntimeError, ValueError, TypeError):
        return seed, False
    ok = bool(np.all(np.isfinite(popt)) and np.all(np.isfinite(np.diag(pcov))))
    return float(popt[2]), ok





# ── CZ pulse table + config accessors (spec two-qubit/01 §2, §4.4-4.6; layout-aware per 04 §4.2) ──
#
# Two gate forms share one pulse-list schema: the coupler-drive form (one physical pulse on a
# dedicated coupler core, spec 01 §2) and qcal's two-qubit-drive form (two consecutive drives on the
# pair's own gate channels; X6Y3, spec 04 §1). String entries are pulse REFERENCES (the (5,6)/(6,7)
# EF-X shelving sandwich) and shift every position, so nothing is found by index: drives by the
# qcal `find_pulse_index` walk, virtual-Z entries by their `channel` key.

def cz_coupler_form(cfg, pair) -> bool:
    """Gate-form detection (spec 04 §4.1): a pair with a dedicated coupler core recorded at
    `two_qubit/(i, j)/core` is the coupler-drive form; without it, the two-qubit-drive form."""
    return f"two_qubit/{pair_key(pair)}/core" in cfg


def _channel_qubit(channel) -> int | None:
    """The core index a pulse-entry channel names — 'Q<i>.qdrv' (qcal) or bare 'Q<i>' (the synthetic
    co-sim configs) → i (the qubit↔core map is identity, spec 01 §1); None for anything else (a
    coupler channel like 'C0_1')."""
    mm = re.fullmatch(r"Q(\d+)(?:\..*)?", str(channel))
    return int(mm.group(1)) if mm else None


def _cz_pulses(cfg, pair) -> list:
    """A pair's raw CZ pulse list."""
    return cfg[f"two_qubit/{pair_key(pair)}/CZ/pulse"]


def _cz_drive_indices(pulses) -> list[int]:
    """The indices of the PHYSICAL drive entries of a CZ pulse list — qcal's `find_pulse_index` walk
    (qcal/calibration/utils.py:11-28) over the whole list: skip string references and virtualz
    entries; what remains is the drive — ONE entry in the coupler form, TWO consecutive ones
    (control, then target with the calibrated relative phase) in the two-qubit-drive form."""
    return [i for i, p in enumerate(pulses)
            if not isinstance(p, str) and p.get("env") != "virtualz"]


def _cz_vz_entry(pulses, q: int) -> dict | None:
    """Qubit q's virtual-Z entry of a CZ pulse list, matched by its `channel` key (04 §4.2) — the
    pair's own ZI/IZ corrections and the spectator entries alike. None when q has no entry."""
    for p in pulses:
        if not isinstance(p, str) and p.get("env") == "virtualz" \
                and _channel_qubit(p.get("channel")) == q:
            return p
    return None


def _cz_entry(cfg, pair, drive: int = 0) -> dict:
    """A pair's `drive`-th physical CZ drive entry: 0 = the coupler drive (coupler form) / the
    CONTROL drive (two-qubit-drive form), 1 = the TARGET drive (04 §1)."""
    pulses = _cz_pulses(cfg, pair)
    return pulses[_cz_drive_indices(pulses)[drive]]


def _cz_amp(cfg, pair) -> float:
    """The CZ drive's normalized amplitude (equal on both lines in the two-qubit-drive form)."""
    return float(_cz_entry(cfg, pair).get("kwargs", {}).get("amp", 1.0))


def _cz_dur_batches(cfg, pair, m) -> int:
    """The CZ drive's length in batches (the drive entry's `time`, seconds)."""
    return batches(float(_cz_entry(cfg, pair)["time"]), m)


def _local_phase(cfg, pair, q: int) -> float:
    """QUBIT q's virtual-Z phase (rad) in a pair's CZ pulse list (the control's = ZI, the target's
    = IZ, a neighbour's = its spectator correction; spec 01 §3, §4.6) — matched by channel, 0.0
    when the entry is absent."""
    p = _cz_vz_entry(cfg.get(f"two_qubit/{pair_key(pair)}/CZ/pulse") or [], q)
    return 0.0 if p is None else float(p.get("kwargs", {}).get("phase", 0.0))


def _local_phase_code(cfg, pair, q: int) -> int:
    """`_local_phase` as the seated virtual-Z word the kernels fold per CZ."""
    return pack16(units._phase_code(_local_phase(cfg, pair, q)))


def _cz_pulse_set(cfg, pair, key: str, value) -> list:
    """A deep copy of a pair's CZ pulse list with the physical drive's `time` (dur) or `amp` updated
    on EVERY drive entry — one in the coupler form, both lines in the two-qubit-drive form (qcal
    calibrates them jointly, 04 §4.3). The proposal payload, since the entries sit inside a
    slash-path list leaf, so the whole list is written back."""
    pulses = copy.deepcopy(_cz_pulses(cfg, pair))
    for i in _cz_drive_indices(pulses):
        if key == "time":
            pulses[i]["time"] = float(value)
        else:
            pulses[i].setdefault("kwargs", {})["amp"] = float(value)
    return pulses


def _cz_pulse(cfg, pair, m, dur_batches: int, drive: int = 0, amp=None) -> Pulse:
    """The BASEBAND (freq_hz=None — the kernel programs the carrier at runtime) CZ Pulse of a pair's
    `drive`-th physical line: a `dur_batches`-long envelope at the entry's amp/phase. `amp` overrides
    the config amp (the AMP-ladder centre). The envelope's shape kwargs are the entry's `kwargs` minus
    amp/phase (X6Y3 carries `ramp_fraction` there, spec 04 §3), merged over the legacy
    `{env_func, ...}` dict form."""
    entry = _cz_entry(cfg, pair, drive)
    a0, phase, kw = _amp_phase(entry.get("kwargs"))
    a = a0 if amp is None else float(amp)
    envspec = entry.get("env", "square")
    ch = gate_ch(m)
    n = int(dur_batches) * ch.samples_per_line
    if isinstance(envspec, dict):                              # the {env_func, ...kwargs} dict form
        name = envspec.get("env_func", "square")
        ekw = {**{k: v for k, v in envspec.items() if k != "env_func"}, **kw}
    else:                                                      # a qcal env NAME: shape kwargs in `kwargs`
        name, ekw = str(envspec), kw
    env = envelopes.build(name, n, ch.samples_per_line * m.params.dsp_freq_hz, **ekw)
    return Pulse(env, amp=a, phase=phase)


def _cz_rel_phase_set(cfg, pair, phase: float) -> list:
    """A fresh CZ pulse list with the TARGET drive line's tone phase set — qcal RelativePhase's
    `CZ/pulse/{idx+1}/kwargs/phase` param (cz.py:1553-1560); the proposal payload (a list leaf,
    written back whole). Two-qubit-drive form only (the second physical drive entry)."""
    pulses = copy.deepcopy(_cz_pulses(cfg, pair))
    idx = _cz_drive_indices(pulses)
    if len(idx) < 2:
        raise ValueError(f"pair {tuple(pair)}: no target drive line (coupler form?) — "
                         f"the relative phase is a two-qubit-drive knob")
    pulses[idx[1]].setdefault("kwargs", {})["phase"] = float(phase)
    return pulses


_EF_X_REF = re.compile(r"single_qubit/(\d+)/EF/X/pulse")


def cz_sandwich(cfg, pair) -> int | None:
    """The EF-SHELVED qubit of an EF-sandwich pair, or None for a plain pulse list (spec 04 §1 /
    X4). X6Y3's (5,6)/(6,7) bracket their two drive tones with STRING-REFERENCE pre/post-pulses —
    `single_qubit/6/EF/X/pulse`, a qcal config-path pulse reuse — that play that qubit's EF X around
    the tone: |1>→|2> before (shelve), |2>→|1> after (un-shelve), so the CZ activates in the shelved
    manifold. Exactly that layout is supported: TWO identical references, one before the first
    drive and one after the last, naming the EF X of a PAIR MEMBER whose `qubit/{q}/EF/{freq, x}`
    the config carries. Anything else is a loud error, never a silent mis-play."""
    pulses = _cz_pulses(cfg, pair)
    refs = [(i, p) for i, p in enumerate(pulses) if isinstance(p, str)]
    if not refs:
        return None

    def fail(why):
        raise ValueError(f"pair {tuple(pair)}: unsupported CZ string-reference layout — {why} "
                         f"(X4 supports X6Y3's EF-X sandwich: one identical "
                         f"'single_qubit/<q>/EF/X/pulse' reference before and one after the drives)")

    if len(refs) != 2 or refs[0][1] != refs[1][1]:
        fail(f"expected 2 identical references, got {[p for _, p in refs]}")
    mm = _EF_X_REF.fullmatch(refs[0][1])
    if not mm:
        fail(f"reference {refs[0][1]!r} is not an EF X pulse path")
    q = int(mm.group(1))
    if q not in (int(pair[0]), int(pair[1])):
        fail(f"referenced qubit {q} is not a member of the pair")
    drives = _cz_drive_indices(pulses)
    if not (refs[0][0] < drives[0] and refs[1][0] > drives[-1]):
        fail("the references must bracket the drive tones (pre + post)")
    for key in (f"qubit/{q}/EF/freq", f"qubit/{q}/EF/x/amp"):
        if key not in cfg:
            fail(f"missing {key} (the shelved qubit's EF calibration)")
    return q


# ── CZ local single-qubit phases (spec 01 §4.6) ──────────────────────────────────────────────────

def _fringe_peak(phi, P):
    """The φ at which a Ramsey P fringe peaks — the first-harmonic phase of P over the full-turn sweep
    φ (`atan2` of Σ (P−P̄)·e^{iφ}), which is robust to the cosine sign that a curve fit leaves ambiguous.
    Returns (φ_peak in (−π, π], the fringe contrast)."""
    P = np.asarray(P, float)
    z = complex(np.sum((P - P.mean()) * np.exp(1j * np.asarray(phi, float))))
    return math.atan2(z.imag, z.real), float(P.max() - P.min())


def _mean_offset(a: float, b: float) -> float:
    """The midpoint of a and b along the SHORTER arc, wrap-aware, in (−π, π]. Arithmetic — NOT a
    circular mean. Well-conditioned only when the two offsets are CLOSE: antipodal inputs (~π apart)
    sit exactly on its wrap boundary, where noise flips which side the midpoint lands — π away. That
    degeneracy is why the spectator-|1> branch's conditional π is removed (`_branch_correction`)
    BEFORE the midpoint, never averaged across."""
    d = (b - a + math.pi) % (2 * math.pi) - math.pi
    return (a + d / 2 + math.pi) % (2 * math.pi) - math.pi


def _branch_correction(off0: float, off1: float) -> float:
    """The written local-phase correction from the two spectator-branch fringe peaks (`off0` =
    spectator |0>, `off1` = spectator |1>) — qcal's combination (cz.py:2013-2051): remove the
    conditional π from the |1> branch, then the shorter-arc midpoint.

    Derivation on OUR sequence `Y90 · cz · Rz(φ) · Y90`, reading P(1) (qcal closes Y⁻90 and reads
    P(0) — the same fringe): the prep puts the ACTIVE qubit on +X and the CZ leaves it at azimuth
    ψ_s per spectator state s — ψ₀ = θ_local + θ_ZZ, ψ₁ = ψ₀ + π + δ (the conditional π plus the
    residual conditionality error δ = −2·θ_ZZ − π, zero at an exact conditional π).

    A frame word SUBTRACTS from the accrued angle — the kernels apply it as the close's
    `set_phase_offset`, so P(1) = (1 + cos(ψ_s − φ))/2 and each branch peaks at φ = +ψ_s (the
    convention is pinned on RTL by the CZRPE zero-amp gate, where a planted config vz comes back
    NEGATED; spec 14 §3 finding 9). So off0 = ψ₀ is ITSELF the correction — writing it makes the
    kernels' effective local phase θ_raw − ψ₀, i.e. exactly −θ_ZZ — and off1 = ψ₀ + π + δ carries
    the conditional π. Removing it (qcal's NEGATIVE-amplitude fit prior on the |1> branch: its
    reported zero is the raw fringe's trough = peak ∓ π) leaves ψ₀ + δ, and the shorter-arc midpoint
    with off0 is ψ₀ + δ/2 = θ_local − π/2, which lands the effective local phase on **+π/2 exactly,
    whatever θ_ZZ is** — the residual conditional error split evenly between the branches, and
    continuous in δ of either sign. The RAW midpoint instead sits at ψ₀ + (π + δ)/2 ≡ local ± π/2
    with a noise-unstable sign (the raw peaks are ~π apart — `_mean_offset`'s wrap boundary),
    calibrating an exp(iπ/4·ZZ)-like composite instead of diag(1,1,1,−1) (spec 04 §3, fixed in X1).
    The ∓π shift's own sign is immaterial mod 2π."""
    return _mean_offset(off0, off1 - math.pi)


def _cz_local_set(cfg, pair, zi: float, iz: float) -> list:
    """A fresh CZ pulse list with the control's ZI and the target's IZ virtual-Z phases set — the
    entries matched by CHANNEL (04 §4.2), never by position (the LocalPhases proposal: both live in
    the list leaf, so the whole list is written back)."""
    pulses = copy.deepcopy(_cz_pulses(cfg, pair))
    for q, phase in ((int(pair[0]), zi), (int(pair[1]), iz)):
        p = _cz_vz_entry(pulses, q)
        if p is None:
            raise ValueError(f"pair {tuple(pair)}: no virtualz entry for qubit {q} in the CZ pulse list")
        p.setdefault("kwargs", {})["phase"] = float(phase)
    return pulses


def _cz_spectator_set(cfg, pair, q: int, phase: float) -> list:
    """A fresh CZ pulse list with SPECTATOR qubit q's virtual-Z phase set — the channel-matched
    entry (04 §4.2) of the PAIR's list (spectator corrections live with the gate that causes them).
    The SpectatorPhase proposal payload (a list leaf, written back whole, like `_cz_local_set`)."""
    pulses = copy.deepcopy(_cz_pulses(cfg, pair))
    p = _cz_vz_entry(pulses, q)
    if p is None:
        raise ValueError(f"pair {tuple(pair)}: no virtualz entry for spectator qubit {q} "
                         f"in the CZ pulse list")
    p.setdefault("kwargs", {})["phase"] = float(phase)
    return pulses


def _cz_freq_word(cfg, pair, m) -> int:
    """The config `CZ/freq` as the SEATED carrier word a drive-form line retunes to."""
    return units.freq_to_code(float(cfg[f"two_qubit/{pair_key(pair)}/CZ/freq"]), m.params)
