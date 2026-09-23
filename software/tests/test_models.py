"""Host-side unit tests for the QuantumModel ADC-seam models (riscq.sim.models). Deterministic,
no sim — the cosim datapath pins live in test_readout."""

import math
from pathlib import Path

import numpy as np

from riscq.map import SocMap, SocParams
from riscq.pulses import units
from riscq.sim import models

M = SocMap(SocParams.load(Path(__file__).resolve().parents[1] / "configs" / "sim-2q.json"))


def test_zero_model():
    z = models.ZeroModel()
    assert z.dac_ids() == []
    assert z.adc_batch(7, {}) == {}


def test_loopback_decimates_every_fourth():
    lb = models.LoopbackModel(gain=1.0, src=0, dst=3)
    assert lb.dac_ids() == [0]
    out = lb.adc_batch(0, {0: np.arange(16, dtype=np.int64)})   # DAC samples 0..15
    assert list(out[3]) == [0, 4, 8, 12]                        # ADC lane j = DAC sample 4j


def test_loopback_gain_and_clip():
    lb = models.LoopbackModel(gain=2.0, src=0, dst=0)
    dac = {0: np.array([100, 0, 0, 0, 20000, 0, 0, 0, -20000, 0, 0, 0, 0, 0, 0, 0], dtype=np.int64)}
    assert list(lb.adc_batch(0, dac)[0]) == [200, 32767, -32768, 0]   # gain then SInt16 clip


def test_loopback_delay():
    lb = models.LoopbackModel(gain=1.0, delay=2, src=0, dst=0)
    batch = lambda v: {0: np.full(16, v, dtype=np.int64)}
    assert list(lb.adc_batch(0, batch(10))[0]) == [0, 0, 0, 0]
    assert list(lb.adc_batch(1, batch(20))[0]) == [0, 0, 0, 0]
    assert list(lb.adc_batch(2, batch(30))[0]) == [10, 10, 10, 10]   # first batch emerges after 2


def test_multi_model_unions_dacs_and_sums_shared_adc():
    """MultiModel drives several sub-models together (spec 13 §8 simultaneous multi-qubit): dac_ids()
    unions the sub-models', and sub-models sharing an ADC (the frequency-multiplexed readout on this
    build, cores 0–6 → ADC 0) SUM on it, re-clipped to the converter range; distinct ADCs do not sum."""
    a = models.LoopbackModel(gain=1.0, src=0, dst=0)   # DAC 0 -> ADC 0
    b = models.LoopbackModel(gain=1.0, src=1, dst=0)   # DAC 1 -> ADC 0 (shared with a)
    mm = models.MultiModel([a, b])
    assert mm.dac_ids() == [0, 1]                       # union, order-preserving
    dac = {0: np.full(16, 10000, dtype=np.int64), 1: np.full(16, 25000, dtype=np.int64)}
    out = mm.adc_batch(0, dac)
    assert set(out) == {0} and list(out[0]) == [32767] * 4   # 10000 + 25000 = 35000 → SInt16 clip
    c = models.LoopbackModel(gain=1.0, src=1, dst=3)   # DAC 1 -> ADC 3 (distinct)
    out2 = models.MultiModel([a, c]).adc_batch(0, dac)
    assert set(out2) == {0, 3}
    assert list(out2[0]) == [10000] * 4 and list(out2[3]) == [25000] * 4


def test_tone_model_batch_time_locked():
    f = units.code_to_freq(F := 1024, M.params)
    tm = models.ToneModel(M, adc=0, freq_hz=f, amp=1000.0)
    assert tm.dac_ids() == []
    s0 = tm.adc_batch(0, {})[0]
    assert len(s0) == models.ADC_BATCH and s0[0] == 1000          # cos(0) = 1
    w = 2 * math.pi * f / (models.ADC_BATCH * M.params.dsp_freq_hz)
    assert abs(s0[1] - round(1000 * math.cos(w))) <= 1            # per-ADC-sample phase advance
    # phase continues across batches: sample index is ADC_BATCH*t + j
    s1 = tm.adc_batch(1, {})[0]
    assert abs(s1[0] - round(1000 * math.cos(w * models.ADC_BATCH))) <= 1


def test_twolevel_pi_pulse_flips_state_and_tone():
    c = 1000.0
    rabi = math.pi / (math.sqrt(2) * c)          # one all-`c` batch -> amp_est = sqrt(2)*c -> pi
    tl = models.TwoLevelModel(M, core=0, rabi_rad_per_amp=rabi, readout_code=2048,
                              readout_amp=5000.0, init_excited=True)
    assert tl.dac_ids() == [M.gate_dac(0)]
    assert tl.sigma_z() == -1.0                  # excited

    excited = tl.adc_batch(0, {M.gate_dac(0): np.zeros(16, dtype=np.int64)})   # DAC ~ 0: no rotation
    assert tl.sigma_z() == -1.0
    assert excited[M.adc_of(0)][0] == -5000      # |1> tone (batch 0, lane 0) is -amp*cos(0)

    tl.adc_batch(1, {M.gate_dac(0): np.full(16, c, dtype=np.int64)})        # a pi pulse
    assert tl.sigma_z() > 0.99                    # flipped to ground
    ground = tl.adc_batch(0, {M.gate_dac(0): np.zeros(16, dtype=np.int64)})  # same batch 0
    assert ground[M.adc_of(0)][0] > 4900         # |0> tone is +amp*cos(0) (pi flip vs |1>)


def test_twolevel_drive_axis_from_detuning():
    """A carrier off f_ge rotates the drive AXIS: two equal batches of a detuned drive rotate the
    Bloch vector about axes that differ by the detuning phase (the Ramsey mechanism), where a
    resonant drive keeps a fixed axis. Here we just check the axis is f_ge-dependent."""
    c = 5000.0
    dac = {M.gate_dac(0): np.full(16, c, dtype=np.int64)}
    # resonant (f_ge=0 vs a DC-ish drive): axis fixed -> repeated batches rotate the same way
    res = models.TwoLevelModel(M, rabi_rad_per_amp=0.05 / c, f_ge=0.0, init_excited=False)
    b0 = res._b.copy()
    res.adc_batch(0, dac)
    res.adc_batch(1, dac)
    # detuned: give f_ge a nonzero code so the demod axis ramps with batch time
    det = models.TwoLevelModel(M, rabi_rad_per_amp=0.05 / c,
                               f_ge=units.code_to_freq(200, M.params), init_excited=False)
    det.adc_batch(0, dac)
    det.adc_batch(1, dac)
    # the detuned run's Bloch vector differs from the resonant one (axis ramped between batches)
    assert not np.allclose(res._b, det._b), "detuning did not change the drive axis"
    assert not np.allclose(res._b, b0), "resonant drive did not rotate the state"


def test_twolevel_shot_noise_reproducible_and_off_by_default():
    base = dict(core=0, readout_code=2048, readout_amp=5000.0, init_excited=False)
    dac = {M.gate_dac(0): np.zeros(16, dtype=np.int64)}
    clean = models.TwoLevelModel(M, **base).adc_batch(0, dac)[M.adc_of(0)]
    # off by default: identical to a second no-noise instance
    assert np.array_equal(clean, models.TwoLevelModel(M, **base).adc_batch(0, dac)[M.adc_of(0)])
    # same seed -> identical noisy ADC (reproducible); and noise actually perturbs the samples
    n1 = models.TwoLevelModel(M, noise_scale=400.0, noise_seed=7, **base).adc_batch(0, dac)[M.adc_of(0)]
    n2 = models.TwoLevelModel(M, noise_scale=400.0, noise_seed=7, **base).adc_batch(0, dac)[M.adc_of(0)]
    assert np.array_equal(n1, n2)
    assert not np.array_equal(n1, clean)


def test_twolevel_fast_forward_equals_repeated_relax():
    """spec 15 C3's gate: `fast_forward(n)` is the n-th power of the per-batch relaxation map, so a
    host driver may skip an idle gap instead of calling `adc_batch` once per batch."""
    kw = dict(core=0, t1=600.0, t2=3000.0, init_excited=True)
    for n in (1, 7, 3200):
        ff = models.TwoLevelModel(M, **kw)
        ff._b[:] = (0.3, -0.4, 0.5)                       # a generic Bloch vector, not a pole
        ff.fast_forward(n)
        step = models.TwoLevelModel(M, **kw)
        step._b[:] = (0.3, -0.4, 0.5)
        for _ in range(n):
            step._relax()
        assert np.allclose(ff._b, step._b, rtol=1e-12, atol=1e-15)   # differs only by float rounding
    # no T1/T2 and n <= 0 are no-ops
    idle = models.TwoLevelModel(M, core=0)
    idle._b[:] = (0.3, -0.4, 0.5)
    idle.fast_forward(1000)
    assert np.array_equal(idle._b, np.array([0.3, -0.4, 0.5]))
    ff = models.TwoLevelModel(M, **kw)
    ff._b[:] = (0.3, -0.4, 0.5)
    ff.fast_forward(0)
    assert np.array_equal(ff._b, np.array([0.3, -0.4, 0.5]))


def test_twolevel_state_is_qutip_qobj():
    import qutip
    tl = models.TwoLevelModel(M, init_excited=False)
    rho = tl.state()
    assert isinstance(rho, qutip.Qobj)
    assert qutip.expect(qutip.sigmaz(), rho) == 1.0     # ground


# ── projective readout (collapse mode, spec 08 §2.4 / B1) ──

def _read_window(tl, bz, i):
    """Plant Bloch-z = bz (a re-prepared superposition), then drive one readout window (readout-drive
    rising edge). Returns the sampled shot s ∈ {0,1} from the latched tone sign."""
    gate0 = np.zeros(16, dtype=np.int64)
    ro_off = np.zeros(16, dtype=np.int64)
    ro_on = np.full(16, 8000, dtype=np.int64)         # readout drive present (amp_est ≫ floor)
    tl._b[:] = (0.0, 0.0, bz)
    tl.adc_batch(2 * i, {M.gate_dac(0): gate0, M.ro_dac(0): ro_off})   # ro idle → arm the edge
    tl.adc_batch(2 * i + 1, {M.gate_dac(0): gate0, M.ro_dac(0): ro_on})  # rising edge → sample+collapse
    return 0 if tl._shot_amp > 0 else 1


def test_twolevel_projective_reads_readout_drive():
    soft = models.TwoLevelModel(M, collapse=False)
    proj = models.TwoLevelModel(M, collapse=True)
    assert soft.dac_ids() == [M.gate_dac(0)]                           # soft ignores ch1 (unchanged)
    assert set(proj.dac_ids()) == {M.gate_dac(0), M.ro_dac(0)}         # projective watches the ro drive


def test_twolevel_projective_collapses_to_a_pole():
    tl = models.TwoLevelModel(M, collapse=True, readout_amp=5000.0, noise_seed=1)
    for i, bz in enumerate((0.4, -0.7, 0.0)):
        s = _read_window(tl, bz, i)
        assert s in (0, 1)
        assert abs(abs(tl._b[2]) - 1.0) < 1e-12, "state did not collapse to a ±1 pole"
        assert (tl._b[2] > 0) == (s == 0)                             # |0>→s=0 (+amp), |1>→s=1 (−amp)


def test_twolevel_projective_binomial_statistics():
    """Repeated reads of a re-prepared superposition sample s ~ Bernoulli((1−bz)/2)."""
    tl = models.TwoLevelModel(M, collapse=True, readout_amp=5000.0, noise_seed=5)
    n, bz = 6000, 0.3
    ones = sum(_read_window(tl, bz, i) for i in range(n))
    p1 = (1 - bz) / 2                                                  # 0.35
    sigma = math.sqrt(p1 * (1 - p1) / n)
    assert abs(ones / n - p1) < 4 * sigma, f"p̂={ones / n:.4f} vs planted p1={p1}"


def test_twolevel_projective_reprepares_from_either_pole():
    """The premise of an on-core shot loop that carries NO reset between shots (test_projective.py):
    the collapse leaves a PURE pole, and one X90 maps EITHER pole onto the equator, so every shot
    re-prepares p1 = 1/2 on its own and the counts stay binomial without a relax head."""
    c = 1000.0
    rabi = (math.pi / 2) / (math.sqrt(2) * c)          # one all-`c` batch = a π/2 rotation
    tl = models.TwoLevelModel(M, collapse=True, rabi_rad_per_amp=rabi, readout_amp=5000.0)
    dac = {M.gate_dac(0): np.full(16, c, dtype=np.int64), M.ro_dac(0): np.zeros(16, dtype=np.int64)}
    for i, bz in enumerate((1.0, -1.0)):               # the two poles a collapse can leave
        tl._b[:] = (0.0, 0.0, bz)
        tl.adc_batch(i, dac)
        assert abs(tl._b[2]) < 1e-12, f"an X90 from bz={bz} left the equator at {tl._b[2]}"


def test_twolevel_projective_extremes_deterministic():
    tl = models.TwoLevelModel(M, collapse=True, readout_amp=5000.0)
    assert all(_read_window(tl, 1.0, i) == 0 for i in range(20))       # |0> always reads 0
    assert all(_read_window(tl, -1.0, i) == 1 for i in range(20))      # |1> always reads 1


def _shot_iq(tl, bz, i, nbatches=40):
    """One projective shot's demodulated IQ: plant Bloch-z = bz, open a readout window (the readout
    drive's rising edge samples and collapses), then integrate the emitted tone against the readout
    code over the window — the IQ point the decoder reports for that shot."""
    gate = np.zeros(16, dtype=np.int64)
    ro_off, ro_on = np.zeros(16, dtype=np.int64), np.full(16, 8000, dtype=np.int64)
    tl._b[:] = (0.0, 0.0, bz)
    t0 = i * (nbatches + 1)
    tl.adc_batch(t0, {M.gate_dac(0): gate, M.ro_dac(0): ro_off})       # ro idle → arm the edge
    z = 0j
    for n in range(nbatches):
        t = t0 + 1 + n
        lanes = tl.adc_batch(t, {M.gate_dac(0): gate, M.ro_dac(0): ro_on})[M.adc_of(0)]
        k = np.arange(models.ADC_BATCH) + models.ADC_BATCH * t
        z += np.sum(lanes * np.exp(-1j * math.pi * tl.readout_code * k / (1 << 15)))
    return z


def test_twolevel_projective_clusters_separate():
    """The cluster statistics `ReadoutCalibration` gates on (host-pure half of the retired
    test_cal.py::test_acquire_shots_chunks): repeated projective reads of a |0> prep and of a |1>
    prep land as two IQ blobs whose qcal SNR — ‖Δmeans‖/(2σ₀ + 2σ₁) — exceeds 1, i.e. the means are
    at least 4σ apart. The two states' tones are π out of phase, so the blobs are antipodal and the
    classifier assigns every shot correctly at this readout_amp/noise_scale."""
    from riscq.cal import Classifier
    tl = models.TwoLevelModel(M, collapse=True, readout_amp=20000.0, noise_scale=400.0,
                              noise_seed=2)
    iq = [np.array([[z.real, z.imag] for z in (_shot_iq(tl, bz, 32 * s + i) for i in range(16))])
          for s, bz in enumerate((1.0, -1.0))]
    clf = Classifier(*iq)
    assert clf.separation > 1.0, f"clusters not 4σ apart: qcal SNR {clf.separation:.2f}"
    assert float(clf.m0 @ clf.m1) < -0.9 * np.hypot(*clf.m0) * np.hypot(*clf.m1)   # antipodal
    assert np.array_equal(clf.confusion(), np.eye(2))


def test_twolevel_decay_in_window_is_off_by_default_and_puts_a_tail_between_the_clusters():
    """spec 15 §3.3's t1-tail scenario: a |1> shot that decays PART-WAY through the window
    integrates part of each tone and lands between the clusters. Default off — with it off the |1>
    shots are a clean cloud even at a T1 shorter than the window."""
    kw = dict(collapse=True, readout_amp=20000.0, t1=20.0, noise_seed=3)   # T1 = half the window
    off = [_shot_iq(models.TwoLevelModel(M, **kw), -1.0, i) for i in range(60)]
    on = [_shot_iq(models.TwoLevelModel(M, decay_in_window=True, **kw), -1.0, i) for i in range(60)]
    # the |0>/|1> poles integrate to +/-A on the real axis; a mid-window jump lands in between
    a = abs(np.mean(off))
    assert np.std(np.real(off)) < 1e-6 * a, "the latched tone must be constant across the window"
    frac = np.mean([-0.9 * a < z.real < 0.9 * a for z in on])
    assert frac > 0.4, f"only {frac:.0%} of |1> shots decayed mid-window at T1 = half the window"
    assert np.mean(np.real(on)) > np.mean(np.real(off))     # the tail pulls |1> toward |0>


# ── dispersive readout (chi != 0, spec 13 Q2) ──

C_RO = 512                                                # a readout-drive DAC code (demod code 4x it)
F_RO = units.code_to_freq(C_RO, M.params)


def _drive(code, amp=9948, n=16):
    """One batch of DAC samples of a square-envelope tone at `code` (what the readout drive is)."""
    return _clip16(amp * np.cos(math.pi * code * np.arange(n) / (1 << 15)))


def _clip16(x):
    return np.clip(np.rint(x), -(1 << 15), (1 << 15) - 1).astype(np.int64)


def _dispersive(chi_code, **kw):
    return models.TwoLevelModel(M, readout_amp=8000.0, f_r=F_RO, chi=units.code_to_freq(chi_code, M.params),
                                kappa=units.code_to_freq(170, M.params), **kw)


def test_dispersive_is_off_by_default():
    """chi = 0 (the default) ⇒ bit-identical to the flat-tone model: same ADC samples, same DACs."""
    base = dict(core=0, readout_code=2048, readout_amp=5000.0, collapse=True, noise_seed=3)
    dac = {M.gate_dac(0): np.zeros(16, dtype=np.int64), M.ro_dac(0): _drive(C_RO)}
    old = models.TwoLevelModel(M, **base)
    new = models.TwoLevelModel(M, f_r=F_RO, kappa=1e6, chi=0.0, **base)     # f_r/kappa set, chi = 0
    assert old.dac_ids() == new.dac_ids()
    for t in range(4):
        assert np.array_equal(old.adc_batch(t, dac)[M.adc_of(0)], new.adc_batch(t, dac)[M.adc_of(0)])


def test_dispersive_recovers_the_drive_carrier_code():
    """The emitted tone TRACKS the drive: its ADC code is 4x the drive's DAC code, recovered exactly
    from the DAC samples (so the matched demod always sees the response at DC)."""
    for c in (256, 512, 700, 1024):
        assert models.TwoLevelModel._carrier_code(_drive(c).astype(float)) == c


def test_dispersive_lorentzian_shifts_with_the_state():
    """|0> pulls the resonator to f_r + chi and |1> to f_r − chi, so max |S21| at |0> is NOT max
    two-state separation: |S(|0>)| peaks at f_r + chi while |S(|0>) − S(|1>)| peaks at f_r. This is
    the ground truth Separation must find (and the |0>-magnitude cal must miss)."""
    chi_code = 60
    tl = _dispersive(chi_code)
    codes = [C_RO + k * chi_code for k in (-2, -1, 0, 1, 2)]
    z0 = [tl._lorentzian(units.code_to_freq(c, M.params), +1.0) for c in codes]
    z1 = [tl._lorentzian(units.code_to_freq(c, M.params), -1.0) for c in codes]
    mag0 = [abs(z) for z in z0]
    sep = [abs(a - b) for a, b in zip(z0, z1)]
    assert int(np.argmax(mag0)) == 3, "the |0> response does not peak at f_r + chi"
    assert int(np.argmax(sep)) == 2, "the two-state separation does not peak at f_r"


def _window(tl, bz, c, batches=40, theta=0.0):
    """Demodulate the model's ADC against the matched carrier (ADC code 4c) over a readout window,
    with the drive playing at DAC code `c` and carrier phase `theta` — i.e. what the decoder integrates."""
    tl._b[:] = (0.0, 0.0, bz)
    tl._ro_last = 0.0
    z = 0j
    for t in range(batches):
        lanes = tl.adc_batch(t, {M.gate_dac(0): np.zeros(16, dtype=np.int64),
                                 M.ro_dac(0): _tone(c, t, theta)})[M.adc_of(0)]
        j = np.arange(models.ADC_BATCH) + models.ADC_BATCH * t
        z += np.sum(lanes * np.exp(-1j * math.pi * (4 * c) * j / (1 << 15)))
    return z


def _tone(c, t, theta=0.0):
    """The readout drive's DAC samples for batch t: a square-envelope tone at code c, phase theta."""
    k = np.arange(16) + 16 * t
    return _clip16(units._amp_code(0.5) * np.cos(math.pi * c * k / (1 << 15) + theta))


def test_dispersive_response_survives_demodulation():
    """What the DECODER sees must BE the resonator: demodulated against the matched carrier, the
    integral has to trace S(f) — magnitude and phase — at every swept drive code. (The regression:
    a batch spans a fraction of a readout-carrier cycle, so any per-batch RMS amplitude estimate
    oscillates at 2ω and folds an image term back onto DC through the demod, bending
    |z(|0>) − z(|1>)| enough to move its argmax — i.e. faking exactly the answer Separation looks for.)"""
    chi = 60
    tl = _dispersive(chi)
    codes = [C_RO + k * chi for k in (-2, -1, 0, 1, 2)]
    z0 = [_window(tl, 1.0, c) for c in codes]
    z1 = [_window(tl, -1.0, c) for c in codes]
    s0 = [tl._lorentzian(units.code_to_freq(c, M.params), +1.0) for c in codes]
    s1 = [tl._lorentzian(units.code_to_freq(c, M.params), -1.0) for c in codes]
    gain = abs(z0[3]) / abs(s0[3])                       # one common scale (drive amp x window x ½)
    for k, c in enumerate(codes):
        assert abs(z0[k] / gain - s0[k]) < 0.06, f"demodulated |0> response off S(f) at code {c}"
        assert abs((z0[k] - z1[k]) / gain - (s0[k] - s1[k])) < 0.06
    assert int(np.argmax([abs(z) for z in z0])) == 3          # |0> magnitude peaks at f_r + chi
    assert int(np.argmax([abs(a - b) for a, b in zip(z0, z1)])) == 2   # separation peaks at f_r


def test_dispersive_response_is_locked_to_the_drive_phase():
    """The resonator answers ITS DRIVE: shift the drive's carrier phase by θ and the demodulated
    response rotates by exactly θ. This is what forbids re-synthesizing the tone from absolute time —
    the readout carrier is referenced to the PULSE START, so an absolute-time tone would land at a
    different phase on every shot of a swept code (the grid period is rounded for one code only), and
    the IQ cluster would smear into a ring instead of a blob."""
    tl = _dispersive(60)
    z = _window(tl, 1.0, C_RO)
    for theta in (0.7, -2.0, 3.0):
        rotated = _window(tl, 1.0, C_RO, theta=theta)
        assert abs(abs(rotated) - abs(z)) < 0.02 * abs(z)                     # same magnitude
        assert abs(math.remainder(np.angle(rotated) - np.angle(z) - theta, 2 * math.pi)) < 0.02


def test_dispersive_tone_scales_with_the_drive_amplitude():
    """The resonator's answer is proportional to the drive — the lever qcal's Fidelity sweeps (the
    flat tone's amplitude is a model constant, which is why the old Fidelity could not sweep it)."""
    tl = _dispersive(60, collapse=True)
    gate = np.zeros(16, dtype=np.int64)
    peak = lambda amp: max(abs(tl.adc_batch(0, {M.gate_dac(0): gate,
                                                M.ro_dac(0): _drive(C_RO, amp)})[M.adc_of(0)]))
    lo, hi = peak(2000), peak(9948)
    assert 0 < lo < hi
    assert abs(hi / lo - 9948 / 2000) < 0.1 * (9948 / 2000)     # linear in the drive amplitude
    assert peak(50) == 0                                        # under the drive floor ⇒ silent


# ── the ac-Stark drive phase (stark_rad_per_sigma != 0, spec 13 Q3) ──

def _stark(stark, **kw):
    """A driven qubit whose drive also Stark-shifts it. rabi is set so one full-amplitude batch is a
    π/2 rotation (amp_est = √2·c), i.e. σ (Σ amp_est) is one batch's worth."""
    c = 9948.0
    return models.TwoLevelModel(M, rabi_rad_per_amp=(math.pi / 2) / (math.sqrt(2) * c),
                                stark_rad_per_sigma=stark / (math.sqrt(2) * c), **kw), c


def test_stark_is_off_by_default():
    """stark_rad_per_sigma = 0 (the default) ⇒ bit-identical to the drive-only model: same Bloch
    vector after a driven batch, same ADC samples."""
    dac = lambda c: {M.gate_dac(0): np.full(16, int(c), dtype=np.int64)}
    old, c = _stark(0.0, readout_amp=5000.0, noise_seed=3)
    new = models.TwoLevelModel(M, rabi_rad_per_amp=old.rabi_rad_per_amp, readout_amp=5000.0,
                               noise_seed=3)                       # the pre-Q3 model, argument for argument
    for t in range(3):
        assert np.array_equal(old.adc_batch(t, dac(c))[M.adc_of(0)],
                              new.adc_batch(t, dac(c))[M.adc_of(0)])
        assert np.array_equal(old._b, new._b)


def test_stark_rotates_the_phase_with_the_drive():
    """The planted Z rotation is stark_rad_per_sigma · Σ amp_est over the pulse: drive N identical
    batches and the accrued phase is N × (the per-batch value). It rotates ONLY the phase (|b| and the
    z-projection of a pole are untouched), and an undriven batch accrues nothing."""
    eps = 0.4                                             # the Z rotation one 1-batch pulse must accrue
    tl, c = _stark(eps)
    tl.rabi_rad_per_amp = 0.0                             # Stark only: no xy rotation to confuse it
    tl._b[:] = (1.0, 0.0, 0.0)                            # on the equator: the phase is visible
    tl.adc_batch(0, {M.gate_dac(0): np.zeros(16, dtype=np.int64)})       # idle ⇒ no phase
    assert math.atan2(tl._b[1], tl._b[0]) == 0.0
    for n in (1, 2, 3):
        tl.adc_batch(n, {M.gate_dac(0): np.full(16, int(c), dtype=np.int64)})
        assert abs(math.atan2(tl._b[1], tl._b[0]) - n * eps) < 1e-9      # +z rotation, linear in σ
        assert abs(np.linalg.norm(tl._b) - 1.0) < 1e-12 and abs(tl._b[2]) < 1e-12


# ── ThreeLevelModel: the driven qutrit for EF cal + 3-level readout (spec two-qubit/01) ──

from riscq.map import ADC_BATCH, BATCH_SIZE   # noqa: E402


def _qtone(code, amp, t):
    """One batch of a DAC carrier at `code` (SF16), amplitude `amp`, at absolute batch `t`."""
    n = BATCH_SIZE * t + np.arange(BATCH_SIZE)
    return {0: np.rint(amp * np.cos(math.pi * code * n / (1 << 15))).astype(np.int64)}


def test_threelevel_ladder_rotations_move_population():
    """The subspace unitaries climb the ladder: a GE Bloch-π takes |0>→|1>, an EF Bloch-π |1>→|2>,
    each leaving the third level untouched (subspace-selective)."""
    tl = models.ThreeLevelModel(M, core=0, f_ge=50e6, f_ef=25e6)
    assert np.allclose(tl.populations(), [1, 0, 0])
    tl._rotate((0, 1), math.pi, 0.0)
    assert np.allclose(tl.populations(), [0, 1, 0], atol=1e-9)
    tl._rotate((1, 2), math.pi, 0.0)
    assert np.allclose(tl.populations(), [0, 0, 1], atol=1e-9)


def test_threelevel_carrier_selects_the_transition():
    """The demod picks the driven transition by carrier: a GE-frequency drive rotates {0,1} (and does
    NOT touch |2>), an EF-frequency drive from |1> rotates {1,2}. amp_est·rate = Bloch angle."""
    A = 10000.0
    ge, ef = 2048, 1024                              # well-separated codes (f_ge, f_ef)
    f_ge, f_ef = units.code_to_freq(ge, M.params), units.code_to_freq(ef, M.params)
    tl = models.ThreeLevelModel(M, core=0, f_ge=f_ge, f_ef=f_ef,
                                rabi_ge_rad_per_amp=math.pi / A, rabi_ef_rad_per_amp=math.pi / A)
    tl.adc_batch(0, _qtone(ge, A, 0))                 # one GE π batch: |0> → |1>
    assert np.allclose(tl.populations(), [0, 1, 0], atol=0.02)
    tl.adc_batch(1, _qtone(ef, A, 1))                 # one EF π batch: |1> → |2>
    assert np.allclose(tl.populations(), [0, 0, 1], atol=0.02)


def _readout_iq(level, code=2048, nbatches=24):
    """The soft-mode demod IQ of a qutrit sitting in `level` (integrate the emitted tone against the
    readout code over `nbatches`)."""
    tl = models.ThreeLevelModel(M, core=0, readout_code=code, init_level=level)
    acc = 0j
    for t in range(nbatches):
        lanes = tl.adc_batch(t, {0: np.zeros(BATCH_SIZE, dtype=np.int64)})[tl.adc].astype(float)
        k = np.arange(ADC_BATCH)
        acc += np.sum(lanes * np.exp(-1j * math.pi * code * (ADC_BATCH * t + k) / (1 << 15)))
    return acc


def test_threelevel_readout_three_distinct_clusters():
    """|0>/|1>/|2> emit the readout tone at three distinct phases (default 120° apart), so the demod
    IQ lands as three separated points of similar magnitude — the 3-level clouds a ClassifierN tells
    apart."""
    iq = [_readout_iq(L) for L in range(3)]
    mags = [abs(z) for z in iq]
    assert min(mags) > 0.5 * max(mags)                    # similar magnitude
    angs = [math.atan2(z.imag, z.real) for z in iq]
    for a, b in ((0, 1), (1, 2), (0, 2)):
        sep = abs((angs[a] - angs[b] + math.pi) % (2 * math.pi) - math.pi)
        assert sep > math.radians(90), f"levels {a},{b} phases too close: {math.degrees(sep):.0f}°"


def _shot_iq3(tl, level, i, nbatches=40):
    """One projective 3-level shot's demodulated IQ: plant the qutrit in `level`, open a readout
    window (the readout drive's rising edge samples and collapses), then integrate the emitted tone
    against the readout code over the window — the IQ point the decoder reports for that shot. The
    `_shot_iq` of the two-level model, lifted to the qutrit."""
    gate = np.zeros(BATCH_SIZE, dtype=np.int64)
    ro_off, ro_on = np.zeros(BATCH_SIZE, dtype=np.int64), np.full(BATCH_SIZE, 8000, dtype=np.int64)
    tl._psi[:] = 0.0
    tl._psi[level] = 1.0
    t0 = i * (nbatches + 1)
    tl.adc_batch(t0, {M.gate_dac(0): gate, M.ro_dac(0): ro_off})        # ro idle → arm the edge
    z = 0j
    for n in range(nbatches):
        t = t0 + 1 + n
        lanes = tl.adc_batch(t, {M.gate_dac(0): gate, M.ro_dac(0): ro_on})[M.adc_of(0)]
        k = np.arange(ADC_BATCH) + ADC_BATCH * t
        z += np.sum(lanes * np.exp(-1j * math.pi * tl.readout_code * k / (1 << 15)))
    return z


def test_threelevel_projective_clusters_classify():
    """The 3-level cluster STATISTICS the EF calibrations rest on (the host-pure half of the migrated
    test_twoqubit_cosim.py::test_three_level_clusters_separate): repeated projective reads of |0>,
    |1> and |2> land as three noisy IQ blobs whose minimum pairwise qcal SNR exceeds 1, so a
    `ClassifierN` assigns each level to itself over 90 % of the time.

    That the three frames survive the real demod/decoder is the RTL half and stays in co-sim, at one
    noiseless shot per level. This half is a property of the model, so it is measured where shots are
    free: 96 per level instead of 48, at the same readout_amp / noise_scale, with a fixed seed."""
    from riscq.cal import ClassifierN
    tl = models.ThreeLevelModel(M, core=0, collapse=True, readout_code=2048, readout_amp=18000.0,
                                noise_scale=400.0, noise_seed=7)
    shots = 96
    clouds = [np.array([[z.real, z.imag]
                        for z in (_shot_iq3(tl, L, 256 * L + i) for i in range(shots))])
              for L in range(3)]
    clf = ClassifierN(clouds)
    conf = clf.confusion()
    assert clf.separation > 1.0, f"3-level clusters not separated (min pairwise SNR {clf.separation:.2f})"
    assert np.all(np.diag(conf) > 0.9), f"3-level confusion diagonal weak: {np.diag(conf)}"
    assert np.allclose(conf.sum(1), 1.0)


def test_threelevel_multimodel_and_dac_ids():
    """The qutrit slots into MultiModel like the two-level one; soft mode reads only the gate DAC,
    collapse mode adds the readout DAC (the window trigger)."""
    soft = models.ThreeLevelModel(M, core=0)
    coll = models.ThreeLevelModel(M, core=0, collapse=True)
    assert soft.dac_ids() == [M.gate_dac(0)]
    assert set(coll.dac_ids()) == {M.gate_dac(0), M.ro_dac(0)}
    built = models.build_model({"kind": "threelevel", "core": 0, "f_ge": 50e6, "f_ef": 25e6}, M)
    assert isinstance(built, models.ThreeLevelModel)


# ── TwoQubitModel: two qutrits + a flux coupler for the CZ cal (spec two-qubit/01 §6) ──

M2Q = SocMap(SocParams.load(Path(__file__).resolve().parents[1] / "configs" / "sim-2q1c.json"))
# f_GE(control) at code 500 and f_EF(target) at code 2548 put the |11>-|02> resonance at code 2048:
# f_CZ = |f_EF(target) - f_GE(control)| = code_to_freq(2048). The partner frequencies (f_GE(target),
# f_EF(control)) are distinct codes that never get driven in the coupler-only tests.
_CZ_CODE = 2048
_FG = (units.code_to_freq(500, M2Q.params), units.code_to_freq(3000, M2Q.params))
_FE = (units.code_to_freq(5000, M2Q.params), units.code_to_freq(2548, M2Q.params))


def _tq_tone(code, amp, t, n=BATCH_SIZE):
    """One batch of a square-envelope DAC carrier at `code` (SF16), amplitude `amp`, at batch `t`."""
    k = BATCH_SIZE * t + np.arange(n)
    return np.rint(amp * np.cos(math.pi * code * k / (1 << 15))).astype(np.int64)


def _tq(**kw):
    return models.TwoQubitModel(M2Q, f_ge=_FG, f_ef=_FE, **kw)


def _coupler_dac(code, amp, t):
    """A DAC dict with the coupler channel (DAC 3) driven and both gate channels (DAC 0, 1) idle."""
    z = np.zeros(BATCH_SIZE, dtype=np.int64)
    return {0: z, 1: z, 3: _tq_tone(code, amp, t)}


def _drive_coupler(md, code, amp, batches):
    for t in range(batches):
        md.adc_batch(t, _coupler_dac(code, amp, t))


def test_twoqubit_dac_ids_and_build():
    """Reads both gate DACs + the coupler drive; collapse adds the shared readout DAC (window trigger).
    build_model constructs it from a JSON-serializable spec (tuple params arrive as lists)."""
    soft = _tq()
    coll = _tq(collapse=True)
    assert soft.dac_ids() == [M2Q.gate_dac(0), M2Q.gate_dac(1), M2Q.gate_dac(2)]
    assert coll.dac_ids() == [M2Q.gate_dac(0), M2Q.gate_dac(1), M2Q.gate_dac(2), M2Q.ro_dac(0)]
    built = models.build_model({"kind": "twoqubit", "f_ge": [50e6, 60e6], "f_ef": [30e6, 35e6],
                                "rabi_cz_rad_per_amp": 1e-4}, M2Q)
    assert isinstance(built, models.TwoQubitModel)


def test_twoqubit_single_qubit_drive_is_product_preserving():
    """A gate drive on one qubit rotates only its subspace and leaves the partner's state exactly put —
    the state stays a PRODUCT (no spurious entanglement). Control GE-π excites control while the target
    marginal stays |0>; then target GE-π reaches |11>."""
    A = 10000.0
    md = _tq(rabi_ge=(math.pi / A, math.pi / A))
    md.adc_batch(0, _coupler_dac(0, 0, 0) | {0: _tq_tone(500, A, 0)})   # GE drive on control
    mc, mt = md.marginals()
    assert mc[1] > 0.7 and mc[2] < 1e-9, f"control not excited into |1>: {mc}"
    assert np.allclose(mt, [1, 0, 0], atol=1e-12), f"target disturbed by control's drive: {mt}"
    # product state: psi factorizes (rank-1), i.e. the joint pops are the outer product of marginals
    assert np.allclose(md.populations(), np.outer(mc, mt), atol=1e-12), "state is not a product"
    md.adc_batch(1, _coupler_dac(0, 0, 1) | {1: _tq_tone(3000, A, 1)})  # GE drive on target
    mc, mt = md.marginals()
    assert mc[1] > 0.7 and mt[1] > 0.7, f"pair did not reach |11>: ctrl {mc} tgt {mt}"


def test_twoqubit_cz_resonance_centered_at_fcz():
    """The parametric drive Rabi-flops |11>-|02> and the transfer peaks when the coupler carrier hits
    f_CZ = |f_EF(target) - f_GE(control)| — the resonance the CZ Frequency cal argmaxes. Off resonance
    the transfer falls off symmetrically; on resonance it reaches full transfer."""
    A, N = 10000.0, 40
    rabi_cz = math.pi / (N * A)                                    # resonant π at N batches
    sweep = list(range(_CZ_CODE - 80, _CZ_CODE + 81, 20))
    p02 = []
    for code in sweep:
        md = _tq(rabi_cz_rad_per_amp=rabi_cz)
        md._psi[:] = 0.0; md._psi[1, 1] = 1.0
        _drive_coupler(md, code, A, N)
        p02.append(md.populations()[0, 2])
    assert sweep[int(np.argmax(p02))] == _CZ_CODE, f"resonance not at f_CZ: P02={np.round(p02, 3)}"
    assert p02[len(sweep) // 2] > 0.99, f"no full transfer on resonance: {p02[len(sweep) // 2]:.3f}"
    assert p02[0] < 0.2 and p02[-1] < 0.2, "transfer did not fall off away from resonance"


def test_twoqubit_offresonant_transfer_matches_rabi_formula():
    """Detuning the coupler carrier reduces the |11>->|02> transfer as the off-resonant Rabi law
    Ω²/(Ω²+Δ²)·sin²(√(Ω²+Δ²)·N/2), with Ω the resonant rotation rate and Δ the demod axis-ramp rate —
    the closed form that emerges from rotating about a ramping equatorial axis (spec 01 §6)."""
    A, N = 10000.0, 40
    rabi_cz = math.pi / (N * A)
    Om = rabi_cz * A                                              # rad/batch on resonance
    for dc in (20, 40, 60):
        md = _tq(rabi_cz_rad_per_amp=rabi_cz)
        md._psi[:] = 0.0; md._psi[1, 1] = 1.0
        _drive_coupler(md, _CZ_CODE + dc, A, N)
        obs = md.populations()[0, 2]
        dlt = dc * BATCH_SIZE * math.pi / (1 << 15)               # axis-ramp per batch (the detuning)
        g = math.hypot(Om, dlt)
        pred = (Om ** 2 / g ** 2) * math.sin(g * N / 2) ** 2
        assert abs(obs - pred) < 0.02, f"dc={dc}: obs P02={obs:.4f} vs Rabi-law {pred:.4f}"


def test_twoqubit_cz_conditional_phase():
    """The gate: a full 2π |11>->|02>->|11> round trip is -I on the {|11>, |02>} subspace, so |11>
    returns with a π phase while |00>/|01>/|10> are untouched — a conditional-π (CZ). Prep an equal
    superposition of the four computational states, drive a resonant 2π, and only |11> flips sign."""
    A, N = 10000.0, 40
    md = _tq(rabi_cz_rad_per_amp=math.pi / (N * A))
    md._psi[:] = 0.0
    for a in (0, 1):
        for b in (0, 1):
            md._psi[a, b] = 0.5
    _drive_coupler(md, _CZ_CODE, A, 2 * N)                        # 2π total → -I on {|11>, |02>}
    got = {(a, b): md._psi[a, b] for a in (0, 1) for b in (0, 1)}
    assert abs(got[(0, 0)] - 0.5) < 1e-3 and abs(got[(0, 1)] - 0.5) < 1e-3, "single-excitation states moved"
    assert abs(got[(1, 0)] - 0.5) < 1e-3, "single-excitation state moved"
    assert abs(got[(1, 1)] + 0.5) < 1e-3, f"|11> did not pick up the conditional π: {got[(1, 1)]:+.4f}"
    assert md.populations()[0, 2] < 1e-3, "population did not return from |02>"


def test_twoqubit_zz_shifts_only_the_control_1_branch():
    """The static ζ|11><11| term (JAZZ, spec 01 §4.3): a target Ramsey precesses at rate ζ ONLY when the
    control is |1> (phase accrues on |11>), and not at all with the control in |0> — so ZZ = f(c=1) −
    f(c=0) recovers ζ. Verified as accumulated phase over N idle batches (t1 off)."""
    zeta, N = 0.05, 30
    dphi = {}
    for ctrl in (0, 1):
        md = _tq(zz_rad_per_batch=zeta)
        md._psi[:] = 0.0
        md._psi[ctrl, 0] = md._psi[ctrl, 1] = 1.0 / math.sqrt(2)
        z = np.zeros(BATCH_SIZE, dtype=np.int64)
        for t in range(N):
            md.adc_batch(t, {0: z, 1: z, 3: z})                  # idle: only ZZ acts
        dphi[ctrl] = math.atan2(md._psi[ctrl, 1].imag, md._psi[ctrl, 1].real) - \
            math.atan2(md._psi[ctrl, 0].imag, md._psi[ctrl, 0].real)
    assert abs(dphi[0]) < 1e-9, f"control |0> branch precessed: {dphi[0]}"
    assert abs(dphi[1] - (-N * zeta)) < 1e-9, f"control |1> branch: {dphi[1]} vs {-N * zeta}"


def test_twoqubit_readout_is_frequency_multiplexed():
    """Each qubit emits its readout tone at a distinct code, summed on the shared ADC; demodulating
    against each code recovers that qubit's level phase (soft mode). Prep |1,0>: control reads the |1>
    tone (120°), target the |0> tone (0°)."""
    code = (2048, 1024)

    def demod(md, c, nb=24):
        z = np.zeros(BATCH_SIZE, dtype=np.int64)
        acc = 0j
        for t in range(nb):
            lanes = md.adc_batch(t, {0: z, 1: z, 3: z})[md.adc[0]].astype(float)
            k = np.arange(ADC_BATCH)
            acc += np.sum(lanes * np.exp(-1j * math.pi * c * (ADC_BATCH * t + k) / (1 << 15)))
        return acc

    a = _tq(readout_code=code); a._psi[:] = 0; a._psi[1, 0] = 1.0
    b = _tq(readout_code=code); b._psi[:] = 0; b._psi[1, 0] = 1.0
    ang0 = math.degrees(math.atan2((z0 := demod(a, 2048)).imag, z0.real))
    ang1 = math.degrees(math.atan2((z1 := demod(b, 1024)).imag, z1.real))
    assert abs((ang0 - 120 + 180) % 360 - 180) < 25, f"control |1> tone at {ang0:.0f}° (want 120°)"
    assert abs((ang1 - 0 + 180) % 360 - 180) < 25, f"target |0> tone at {ang1:.0f}° (want 0°)"


def test_twoqubit_collapse_samples_the_joint_pair_state():
    """Projective readout draws one JOINT (a, b) per window from |psi|² and collapses the pair to it, so
    the two cores' per-shot bits are correlated draws from the pair state (the joint counts, spec 01 §5).
    Repeatedly re-prep a known joint superposition and the sampled histogram matches |psi|²."""
    md = _tq(collapse=True, noise_seed=2)
    target = np.zeros((3, 3), complex)
    target[0, 0], target[1, 1], target[0, 1] = math.sqrt(0.5), math.sqrt(0.3), math.sqrt(0.2)
    z, on = np.zeros(BATCH_SIZE, dtype=np.int64), np.full(BATCH_SIZE, 8000, dtype=np.int64)
    n, counts = 8000, {}
    for i in range(n):
        md._psi[:] = target
        md._ro_on = {d: False for d in md._ro_set}
        md.adc_batch(2 * i, {0: z, 1: z, 3: z, 2: z})            # ro idle → arm the edge
        md.adc_batch(2 * i + 1, {0: z, 1: z, 3: z, 2: on})       # rising edge → sample + collapse
        counts[tuple(md._shot)] = counts.get(tuple(md._shot), 0) + 1
    for cell, amp in (((0, 0), 0.5), ((1, 1), 0.3), ((0, 1), 0.2)):
        assert abs(counts.get(cell, 0) / n - amp) < 0.03, f"{cell}: {counts.get(cell, 0) / n:.3f} vs {amp}"
    assert set(counts) <= {(0, 0), (1, 1), (0, 1)}, f"sampled a zero-amplitude state: {set(counts)}"


def test_twoqubit_jazz_zz_physics():
    """The ζ|11><11| term under the BIRD echo drives the JAZZ measurement (spec two-qubit/01 §4.3): an
    ideal echo Ramsey on the target (X90 · idle w · π-on-both · idle w · Rz · close) with the control in
    |0>/|1> gives the target a control-conditional fringe whose frequency splits by the ZZ. This is the
    ground truth the `JAZZ` cal recovers — verified here with ideal ops so it is deterministic and
    decoupled from co-sim readout SNR: f(control=1) − f(control=0) tracks the planted ζ SIGN and
    vanishes at ζ=0 (a control-independent target ⇒ no split)."""
    PI = math.pi
    ws = np.arange(2, 82, 2)

    def shot(zeta_b, ctrl, w, phi, quad):
        md = models.TwoQubitModel(M2Q, f_ge=(50e6, 50e6), f_ef=(25e6, 25e6), zz_rad_per_batch=zeta_b)
        md._psi[:] = 0.0
        md._psi[0, 0] = 1.0
        if ctrl == 1:
            md._rotate(0, (0, 1), PI, 0.0)                        # control |0> → |1>
        md._rotate(1, (0, 1), PI / 2, 0.0)                        # target X90
        for _ in range(2 * int(w)):                               # 2 idle halves; π-echo negates half 1
            md._psi[1, 1] *= complex(math.cos(zeta_b), -math.sin(zeta_b))
            if _ == int(w) - 1:
                md._rotate(0, (0, 1), PI, 0.0)                    # echo π on both at the midpoint
                md._rotate(1, (0, 1), PI, 0.0)
        md._rotate(1, (0, 1), PI / 2, phi + (PI / 2 if quad else 0.0))   # Rz(phi)+close (Y90 if quad)
        return float(md.marginals()[1][1])                        # target P(|1>)

    def split_freq(zeta_b, ctrl):
        phis = 2 * PI * 0.02 * ws                                 # a small applied detuning per w
        I = np.array([shot(zeta_b, ctrl, w, p, 0) for w, p in zip(ws, phis)])
        Q = np.array([shot(zeta_b, ctrl, w, p, 1) for w, p in zip(ws, phis)])
        z = (I - I.mean()) - 1j * (Q - Q.mean())
        return np.fft.fftfreq(len(ws), d=ws[1] - ws[0])[int(np.argmax(np.abs(np.fft.fft(z))))]

    for zz in (0.12, -0.12):                                      # planted ζ (rad/batch), both signs
        zz_split = split_freq(zz, 1) - split_freq(zz, 0)
        assert np.sign(zz_split) == -np.sign(zz), f"ZZ split sign did not track ζ={zz}: {zz_split}"
        assert abs(zz_split) > 0.02, f"ZZ split vanished for ζ={zz}: {zz_split}"
    assert abs(split_freq(0.0, 1) - split_freq(0.0, 0)) < 1e-9    # ζ=0 ⇒ no control-conditional split


def test_twoqubit_cz_conditionality_R_peaks_at_the_cz():
    """The conditionality metric R (spec two-qubit/01 §4.5) reaches its MAX at the CZ's π-phase point:
    the target Ramsey `Y90 · CZ^n · close` measured with the control in |0>/|1> gives R = √((ΔP0_X)² +
    (ΔP0_Y)²) = 1 only when the coupler drive is a full |11>→|02>→|11> round trip (2π), which stamps a
    conditional π on |11>. This is the ground truth `CZFrequency`/`CZAmplitude` argmax/vertex on — the
    fit MATH is exercised host-pure in test_twoqubit; here the MODEL physics: R vanishes with no drive
    (the control can't touch the target), peaks at the full round trip, and a half trip (population
    stranded in |02>) is NOT the max. Ideal Ramsey ops + the real parametric coupler drive, no readout
    SNR (deterministic, decoupled from co-sim relaxation — the spec 01 §4.3 pattern)."""
    PI, A, N = math.pi, 10000.0, 40
    rabi_cz = PI / (N * A)                                        # resonant π at N batches (half trip)

    def p0_target(ctrl, quad, cz_batches):
        md = _tq(rabi_cz_rad_per_amp=rabi_cz)
        md._psi[:] = 0.0
        md._psi[0, 0] = 1.0
        if ctrl == 1:
            md._rotate(0, (0, 1), PI, 0.0)                       # control |0> → |1>
        md._rotate(1, (0, 1), PI / 2, PI / 2)                    # target Y90
        _drive_coupler(md, _CZ_CODE, A, cz_batches)              # CZ^n as a resonant coupler drive
        md._rotate(1, (0, 1), PI / 2, PI / 2 if quad == 0 else 0.0)   # close Y90 (X-seq) / X90 (Y-seq)
        return float(md.marginals()[1][0])                       # target P(0)

    def R(cz_batches):
        p = {(c, q): p0_target(c, q, cz_batches) for c in (0, 1) for q in (0, 1)}
        return math.hypot(p[(1, 0)] - p[(0, 0)], p[(1, 1)] - p[(0, 1)])

    r_none, r_half, r_full = R(0), R(N), R(2 * N)                 # no CZ, half trip (|02>), full 2π
    assert r_none < 0.05, f"R with no CZ should vanish (control can't touch the target): {r_none:.3f}"
    assert r_full > 0.95, f"R at the full round trip (conditional π) should be ≈1: {r_full:.3f}"
    assert r_full > r_half + 0.3, f"R must MAX at the full trip, not the half trip: {r_half:.3f} vs {r_full:.3f}"


# ── TwoQubitModel drive form (spec two-qubit/04 §4.6 / X3): the two-line CZ activation ──

# Drive-form f_CZ = (f_11 + f_02)/4 = (f_ge0 + 2·f_ge1 + f_ef1)/4 → code (2048 + 2·2048 + 10240)/4
# = 4096, well separated from every per-qubit GE/EF code so the 3-way demod argmax is unambiguous
# (and 4096's counter-rotating demod term integrates to EXACTLY zero over a 16-sample batch, so the
# recovered phases/amplitudes are numerically exact and the closed-form asserts can be tight).
_DFG = (units.code_to_freq(2048, M.params), units.code_to_freq(2048, M.params))
_DFE = (units.code_to_freq(6000, M.params), units.code_to_freq(10240, M.params))
_DCZ_CODE = 4096


def _tqd(**kw):
    """A drive-form TwoQubitModel on the 2-core sim-2q map (no coupler anywhere — the X6Y3 layout)."""
    return models.TwoQubitModel(M, coupler=None, f_ge=_DFG, f_ef=_DFE, **kw)


def _two_line_dac(code, amp0, amp1, dphi, t):
    """Both gate DACs (sim-2q: DAC 0/1) driving the CZ tone at `code` — line 0 at phase 0, line 1 at
    the relative phase `dphi` — at absolute batch `t` (the carriers are time-referenced, as on HW)."""
    k = BATCH_SIZE * t + np.arange(BATCH_SIZE)
    mk = lambda a, p: np.rint(a * np.cos(math.pi * code * k / (1 << 15) + p)).astype(np.int64)
    return {0: mk(amp0, 0.0), 1: mk(amp1, dphi)}


def test_twoqubit_drive_form_dac_ids_and_build():
    """coupler=None selects the drive form: no coupler DAC — the CZ activation reads the pair's own
    gate DACs; build_model maps an ABSENT "coupler" key to the drive form (X6Y3 has no couplers) and
    an explicit one to the coupler path, byte-identical."""
    md = _tqd()
    assert md.dac_ids() == [M.gate_dac(0), M.gate_dac(1)]
    built = models.build_model({"kind": "twoqubit", "f_ge": [50e6, 60e6], "f_ef": [30e6, 35e6]}, M)
    assert isinstance(built, models.TwoQubitModel) and built.coupler_dac is None
    withc = models.build_model({"kind": "twoqubit", "coupler": 2,
                                "f_ge": [50e6, 60e6], "f_ef": [30e6, 35e6]}, M2Q)
    assert withc.coupler_dac == M2Q.gate_dac(2)


def test_twoqubit_drive_form_fcz_from_own_spectrum():
    """The drive-form activation frequency comes from the model's OWN f_ge/f_ef — the in-band
    (f_11 + f_02)/4 tone (the calc_cz_frequency(form='drive') arithmetic, spec 04 §1) — never
    planted; the coupler path keeps its parametric |f_EF(t) − f_GE(c)| detuning untouched."""
    assert _tqd()._cz_code == _DCZ_CODE
    assert _tqd()._cz_code == units._freq_code((_DFG[0] + 2 * _DFG[1] + _DFE[1]) / 4, M.params)
    assert _tq()._cz_code == _CZ_CODE                             # coupler path: unchanged


def test_twoqubit_drive_form_activation_peaks_at_fcz():
    """(X3 gate) the drive-form transfer peaks at f_CZ: both gate lines swept LOCKSTEP around the
    in-band resonance Rabi-flop |11>↔|02> exactly as the coupler path does — full transfer on
    resonance, symmetric falloff off it (both demod args ramp together, so the axis-ramp mechanics
    and the Ω²/(Ω²+Δ²) law carry over verbatim)."""
    A, N = 10000.0, 40
    rabi_cz = math.pi / (N * 2 * A)                               # two aligned lines: |E| = 2A → π at N
    sweep = list(range(_DCZ_CODE - 80, _DCZ_CODE + 81, 20))
    p02 = []
    for code in sweep:
        md = _tqd(rabi_cz_rad_per_amp=rabi_cz)
        md._psi[:] = 0.0; md._psi[1, 1] = 1.0
        for t in range(N):
            md.adc_batch(t, _two_line_dac(code, A, A, 0.0, t))
        p02.append(md.populations()[0, 2])
    assert sweep[int(np.argmax(p02))] == _DCZ_CODE, f"resonance not at f_CZ: P02={np.round(p02, 3)}"
    assert p02[len(sweep) // 2] > 0.99, f"no full transfer on resonance: {p02[len(sweep) // 2]:.3f}"
    assert p02[0] < 0.2 and p02[-1] < 0.2, "transfer did not fall off away from resonance"


def test_twoqubit_drive_form_rate_is_the_coherent_two_line_sum():
    """(X3 gate) the effective drive is the COHERENT sum of the two lines' phasors: |E| =
    |A_c + A_t·e^{iΔφ}| = 2A·cos(Δφ/2) for equal lines. Δφ = 0 doubles a single line (a full π at N
    batches); Δφ = π extinguishes the activation entirely (|11> does not move at all); intermediate
    Δφ follows the closed form — the relative-phase lever RelativePhase turns; one line alone still
    activates at half rate."""
    A, N = 10000.0, 40
    rabi_cz = math.pi / (N * 2 * A)

    def p02(dphi, amp1=A):
        md = _tqd(rabi_cz_rad_per_amp=rabi_cz)
        md._psi[:] = 0.0; md._psi[1, 1] = 1.0
        for t in range(N):
            md.adc_batch(t, _two_line_dac(_DCZ_CODE, A, amp1, dphi, t))
        return float(md.populations()[0, 2])

    assert p02(0.0) > 0.99, f"aligned lines must reach a full π: {p02(0.0):.3f}"
    assert p02(math.pi) < 1e-6, f"anti-phase lines must cancel (E = 0): {p02(math.pi):.2e}"
    for dphi in (math.pi / 2, 2.0):
        theta = math.pi * math.cos(dphi / 2)                      # rabi_cz·|E|·N = π·cos(Δφ/2)
        assert abs(p02(dphi) - math.sin(theta / 2) ** 2) < 0.02, \
            f"Δφ={dphi}: P02={p02(dphi):.4f} vs closed form {math.sin(theta / 2) ** 2:.4f}"
    assert abs(p02(0.0, amp1=0.0) - 0.5) < 0.02                   # one line: θ = π/2 → P02 = 1/2


def test_twoqubit_drive_form_conditional_pi_round_trip():
    """(X3 gate) the round trip is the SAME {|11>, |02>} rotation mechanics as the coupler path
    (test_twoqubit_cz_conditional_phase): a resonant 2π on the two aligned lines is −I on the
    subspace — |11> flips sign, |00>/|01>/|10> untouched (the argmax reads the in-band tone as the
    CZ carrier, never as a GE/EF drive), population returned from |02>."""
    A, N = 10000.0, 40
    md = _tqd(rabi_cz_rad_per_amp=math.pi / (N * 2 * A))
    md._psi[:] = 0.0
    for a in (0, 1):
        for b in (0, 1):
            md._psi[a, b] = 0.5
    for t in range(2 * N):                                        # 2π total → −I on {|11>, |02>}
        md.adc_batch(t, _two_line_dac(_DCZ_CODE, A, A, 0.0, t))
    got = {(a, b): md._psi[a, b] for a in (0, 1) for b in (0, 1)}
    assert abs(got[(0, 0)] - 0.5) < 1e-3 and abs(got[(0, 1)] - 0.5) < 1e-3, "single-excitation states moved"
    assert abs(got[(1, 0)] - 0.5) < 1e-3, "single-excitation state moved"
    assert abs(got[(1, 1)] + 0.5) < 1e-3, f"|11> did not pick up the conditional π: {got[(1, 1)]:+.4f}"
    assert md.populations()[0, 2] < 1e-3, "population did not return from |02>"


def test_twoqubit_drive_form_R_extremal_at_optimal_relative_phase():
    """(X3 gate C) the RelativePhase physics probe: with amp/duration tuned for a full 2π round trip
    at the model's OPTIMAL relative phase (Δφ = 0, where the absolute-time-referenced lines add
    coherently), the conditionality R(Δφ) is extremal there — R ≈ 1 at the optimum, starved as
    |E| = 2A·cos(Δφ/2) shortens the round trip, and 0 at the anti-phase null. Ideal Ramsey ops + the
    real two-line drive (the test_twoqubit_cz_conditionality_R_peaks_at_the_cz pattern)."""
    PI, A, N = math.pi, 10000.0, 40
    rabi_cz = PI / (N * 2 * A)

    def p0_target(ctrl, quad, dphi):
        md = _tqd(rabi_cz_rad_per_amp=rabi_cz)
        md._psi[:] = 0.0
        md._psi[0, 0] = 1.0
        if ctrl == 1:
            md._rotate(0, (0, 1), PI, 0.0)                        # control |0> → |1>
        md._rotate(1, (0, 1), PI / 2, PI / 2)                     # target Y90
        for t in range(2 * N):                                    # the CZ: a full 2π at Δφ = 0
            md.adc_batch(t, _two_line_dac(_DCZ_CODE, A, A, dphi, t))
        md._rotate(1, (0, 1), PI / 2, PI / 2 if quad == 0 else 0.0)   # close Y90 / X90
        return float(md.marginals()[1][0])                        # target P(0)

    def R(dphi):
        p = {(c, q): p0_target(c, q, dphi) for c in (0, 1) for q in (0, 1)}
        return math.hypot(p[(1, 0)] - p[(0, 0)], p[(1, 1)] - p[(0, 1)])

    r = {d: R(d) for d in (0.0, 2.0, -2.0, math.pi)}
    assert r[0.0] > 0.95, f"R at the optimal relative phase should be ≈1: {r[0.0]:.3f}"
    assert r[0.0] > r[2.0] + 0.3 and r[0.0] > r[-2.0] + 0.3, \
        f"R must peak at the optimum: {r[0.0]:.3f} vs ±2.0 rad {r[2.0]:.3f}/{r[-2.0]:.3f}"
    assert r[math.pi] < 0.05, f"R at the anti-phase null should vanish: {r[math.pi]:.3f}"


# ── ClassifierN: the N-cluster readout GMM (nearest-mean over labelled clusters) ──

def test_classifiern_separates_three_clusters():
    from riscq.cal import ClassifierN
    rng = np.random.default_rng(0)
    c0 = rng.normal([1000, 0], 60, (50, 2))
    c1 = rng.normal([-500, 866], 60, (50, 2))            # 120° around the origin
    c2 = rng.normal([-500, -866], 60, (50, 2))
    clf = ClassifierN([c0, c1, c2])
    assert clf.means.shape == (3, 2)
    conf = clf.confusion()
    assert np.all(np.diag(conf) > 0.95)                  # each level classified as itself
    assert np.allclose(conf.sum(1), 1.0)
    assert clf.separation > 1.0                          # well separated (qcal SNR)


# ── TwoQubitModel spec-19 extensions: fast_forward, dispersive split converters, planted CZ errors ──

MX = SocMap(SocParams.load(Path(__file__).resolve().parents[1] / "xcheck" / "configs" / "xcheck-2q.json"))


def test_twoqubit_fast_forward_equals_stepped_idle():
    """`fast_forward(n)` is exactly n silent batches (Medium.idle's contract): the ZZ phase accrues
    n·ζ, the |00>-refilling damping composes in closed form, and the collapse latch clears as
    stepping through `_update_shot` would."""
    kw = dict(zz_rad_per_batch=0.03, t1=40.0, collapse=True, noise_seed=5)
    a, b = _tqd(**kw), _tqd(**kw)
    psi = np.zeros((3, 3), complex)
    psi[0, 0], psi[1, 1], psi[0, 2], psi[1, 0] = 0.5, 0.6j, 0.4, math.sqrt(1 - .25 - .36 - .16)
    a._psi[:] = psi; b._psi[:] = psi
    a._shot, a._ro_on = [1, 1], {d: True for d in a._ro_set}
    b._shot, b._ro_on = [1, 1], {d: True for d in b._ro_set}
    n = 50
    silent = {d: np.zeros(BATCH_SIZE, dtype=np.int64) for d in a.dac_ids()}
    for t in range(n):
        a.adc_batch(t, silent)
    b.fast_forward(n)
    assert np.allclose(a._psi, b._psi, atol=1e-12), f"max dev {np.abs(a._psi - b._psi).max():.2e}"
    assert a._shot == [None, None] and b._shot == [None, None]
    assert not any(a._ro_on.values()) and not any(b._ro_on.values())


def test_twoqubit_dispersive_matches_twolevel_and_splits_converters():
    """Dispersive mode (chi != 0) on the split-converter xcheck-2q map: each qubit's response is
    TwoLevelModel._dispersive's, computed from ITS OWN readout drive on its own converter pair —
    exactly equal on the pure |0>/|1> states — and an undriven qubit's ADC stays silent."""
    fr = [units.code_to_freq(509, MX.params) - 0.5e6, units.code_to_freq(253, MX.params) - 0.5e6]
    kw = dict(f_r=tuple(fr), kappa=4e6, chi=1.2e6, readout_amp=(6500.0, 6500.0))
    md = models.TwoQubitModel(MX, coupler=None, f_ge=(50e6, 60e6), f_ef=(400e6, 450e6), **kw)
    assert md.ro_dacs[0] != md.ro_dacs[1], "xcheck-2q must give each core its own readout DAC"
    assert set(md.dac_ids()) >= set(md.ro_dacs), "chi != 0 must pull the readout drives in"
    k = np.arange(BATCH_SIZE)
    ro = {i: np.rint(6000 * np.cos(math.pi * c * k / (1 << 15))).astype(np.int64)
          for i, c in ((0, 509), (1, 253))}
    z = np.zeros(BATCH_SIZE, dtype=np.int64)
    for a, b in ((0, 0), (1, 1), (0, 1)):
        md._psi[:] = 0.0; md._psi[a, b] = 1.0
        dac = {d: z for d in md.dac_ids()}
        dac[md.ro_dacs[0]] = ro[0]; dac[md.ro_dacs[1]] = ro[1]
        out = md.adc_batch(0, dac)
        for idx, level in ((0, a), (1, b)):
            tl = models.TwoLevelModel(MX, core=idx, f_r=fr[idx], kappa=4e6, chi=1.2e6,
                                      readout_amp=6500.0)
            want = _clip16(tl._dispersive(ro[idx], +1.0 if level == 0 else -1.0))
            assert np.array_equal(out[md.adc[idx]], want), f"state {(a, b)} qubit {idx} response"
    md._psi[:] = 0.0; md._psi[1, 0] = 1.0
    dac = {d: z for d in md.dac_ids()}
    dac[md.ro_dacs[0]] = ro[0]                               # only qubit 0's readout driven
    out = md.adc_batch(1, dac)
    assert np.any(out[md.adc[0]]), "driven qubit must respond"
    assert not np.any(out[md.adc[1]]), "undriven qubit's converter must stay silent"
    # the three levels answer with three distinct phasors (|2> at its own planted pull chi2)
    resp = {}
    for lv in (0, 1, 2):
        md._psi[:] = 0.0; md._psi[lv, 0] = 1.0
        lanes = md.adc_batch(2, dac)[md.adc[0]].astype(float)
        # demod against the drive's own batch-local phase (ADC lane j = DAC sample 4j)
        resp[lv] = complex(np.sum(lanes * np.exp(
            -1j * math.pi * 509 * 4 * np.arange(ADC_BATCH) / (1 << 15))))
    for x, y in ((0, 1), (0, 2), (1, 2)):
        assert abs(resp[x] - resp[y]) > 0.1 * abs(resp[0]), f"levels {x}/{y} indistinct"


def test_twoqubit_cz_line_phase_offset_shifts_the_null():
    """A planted per-line phase offset (spec 19 §2) moves the coherent-sum optimum: in-phase lines
    with an offset of π extinguish the CZ activation (the 2A·cos(Δφ/2) null), 2π/3 halves the
    transfer, and a config relative phase of −offset restores it — RelativePhase's answer."""
    A, N = 10000.0, 40
    rabi = math.pi / (2 * A * N)                             # a π on {|11>,|02>} at Δφ = 0
    def p02(off, dphi=0.0):
        md = _tqd(rabi_cz_rad_per_amp=rabi, cz_phase_offset=(0.0, off))
        md._psi[:] = 0.0; md._psi[1, 1] = 1.0
        for t in range(N):
            md.adc_batch(t, _two_line_dac(_DCZ_CODE, A, A, dphi, t))
        return float(md.populations()[0, 2])
    assert p02(0.0) > 0.98, f"aligned lines must fully transfer: {p02(0.0):.3f}"
    assert abs(p02(2 * math.pi / 3) - 0.5) < 0.03, f"2π/3 offset should halve: {p02(2 * math.pi / 3):.3f}"
    assert p02(math.pi) < 0.02, f"π offset must extinguish: {p02(math.pi):.3f}"
    assert p02(0.4, dphi=-0.4) > 0.98, f"a −offset relative phase must restore: {p02(0.4, -0.4):.3f}"


def test_twoqubit_f_cz_offset_moves_the_resonance():
    """A planted `f_cz_offset_hz` (spec 19 §2) moves the {|11>,|02>} resonance off the spectrum
    arithmetic by exactly the code-rounded offset — what the CZ Frequency cross-check must find."""
    A, N, off_codes = 10000.0, 40, 40
    off_hz = units.code_to_freq(off_codes, M.params)
    md = _tqd(f_cz_offset_hz=off_hz)
    assert md._cz_code == _DCZ_CODE + off_codes
    rabi = math.pi / (2 * A * N)
    def p02(code):
        md = _tqd(rabi_cz_rad_per_amp=rabi, f_cz_offset_hz=off_hz)
        md._psi[:] = 0.0; md._psi[1, 1] = 1.0
        for t in range(N):
            md.adc_batch(t, _two_line_dac(code, A, A, 0.0, t))
        return float(md.populations()[0, 2])
    assert p02(_DCZ_CODE + off_codes) > 0.95, "no transfer at the shifted resonance"
    assert p02(_DCZ_CODE + off_codes) > p02(_DCZ_CODE) + 0.3, "resonance did not move"


# ── CavityModel: a transmon + the M/S bosonic modes (spec 24 §4.7, universal-cal/03 V4) ──

MM = SocMap(SocParams.load(Path(__file__).resolve().parents[1] / "configs" / "sim-mm.json"))
_MM_DAC = {name: MM.channel_named(name, 0).dac for name in ("gate", "f0g1", "flux", "ro")}

# The planted spectrum, in codes (converted to Hz through `units.code_to_freq`, which `_freq_code`
# inverts exactly). Every line's rate is one π per `_CAV_N` batches at amplitude `_CAV_A`, so a
# `_CAV_N`-batch flat-top is the planted π (a full swap) and half of it a √SWAP. The GE/EF/f0g1/MS
# codes are multiples of 2048, so each demod's counter-rotating term integrates to exactly zero over
# a 16-sample batch (the recovered axes are numerically exact and the asserts can be tight).
_CAV_GE, _CAV_EF, _CAV_F0G1, _CAV_MS = 2048, 6144, 8192, 4096
_CAV_CHI_GE, _CAV_CHI_EF = -200, -150          # the M photon's pull on the transmon (negative)
_CAV_N, _CAV_A = 20, 10000.0


def _cav_hz(code):
    return units.code_to_freq(code, MM.params)


def _cav(**kw):
    """The planted multimode model on the sim-mm build (gate/f0g1/flux/ro on DACs 0/1/2/14)."""
    rate = math.pi / (_CAV_N * _CAV_A)
    return models.CavityModel(MM, core=0, f_ge=_cav_hz(_CAV_GE), f_ef=_cav_hz(_CAV_EF),
                              rabi_ge_rad_per_amp=rate, rabi_ef_rad_per_amp=rate,
                              f_f0g1=_cav_hz(_CAV_F0G1), f0g1_rad_per_amp=rate,
                              f_ms=_cav_hz(_CAV_MS), ms_rad_per_amp=rate,
                              chi_ge=_cav_hz(_CAV_CHI_GE), chi_ef=_cav_hz(_CAV_CHI_EF), **kw)


def _cav_dac(t, **lines):
    """The core's DAC batch at absolute batch `t`: each named line (`gate`/`f0g1`/`flux`) plays a
    square-envelope tone `(code, amp)`, every other line is silent."""
    z = np.zeros(BATCH_SIZE, dtype=np.int64)
    dac = {d: z for d in _MM_DAC.values()}
    for name, (code, amp) in lines.items():
        k = BATCH_SIZE * t + np.arange(BATCH_SIZE)
        dac[_MM_DAC[name]] = np.rint(amp * np.cos(math.pi * code * k / (1 << 15))).astype(np.int64)
    return dac


def _cav_play(md, t0, line, code, n=_CAV_N, amp=_CAV_A):
    """Play an `n`-batch flat-top at `code` on `line` from batch `t0`; returns the next batch."""
    for t in range(t0, t0 + n):
        md.adc_batch(t, _cav_dac(t, **{line: (code, amp)}))
    return t0 + n


def _cav_prep_f(md, t0=0):
    """GE π then EF π on the gate line: |g, 0, 0> → |f, 0, 0>."""
    return _cav_play(md, _cav_play(md, t0, "gate", _CAV_GE), "gate", _CAV_EF)


def _cav_prep_photon(md, t0=0):
    """The photon prep of the multimode cals: GE π, EF π, f0g1 π ⇒ |g, 1_M, 0_S>."""
    return _cav_play(md, _cav_prep_f(md, t0), "f0g1", _CAV_F0G1)


def test_cavity_f0g1_swap_loads_a_photon_into_m():
    """The sideband swap: climb the transmon to |f> (GE π then EF π on the gate line), then a
    resonant f0g1 flat-top of the planted π length moves that excitation into ONE M photon —
    the transmon lands back in |g>, P(M=1) ≈ 1. Half the length is a √SWAP (an equal
    |f, 0>/|g, 1> superposition), which is what makes the length sweep a Rabi."""
    md = _cav()
    t = _cav_prep_f(md)
    assert np.allclose(md.p_qubit(), [0, 0, 1], atol=1e-6), f"prep did not reach |f>: {md.p_qubit()}"
    assert np.allclose(md.p_m(), [1, 0, 0], atol=1e-9), "the gate drive moved the cavity"
    _cav_play(md, t, "f0g1", _CAV_F0G1)
    assert md.p_m()[1] > 0.99, f"the π swap did not load the photon: p_M={md.p_m()}"
    assert md.p_qubit()[0] > 0.99, f"the transmon did not return to |g>: {md.p_qubit()}"
    assert md.p_s()[0] > 0.99, "the storage mode moved without a flux drive"

    half = _cav()
    _cav_play(half, _cav_prep_f(half), "f0g1", _CAV_F0G1, n=_CAV_N // 2)
    assert abs(half.p_m()[1] - 0.5) < 0.02, f"half the π length is not a √SWAP: p_M={half.p_m()}"


def test_cavity_f0g1_resonance_is_a_chevron_slice():
    """One length-slice of the M2 chevron: sweep the f0g1 carrier around the planted f_f0g1 at the
    π length. The transfer peaks exactly on resonance and falls off symmetrically as the
    off-resonant Rabi law Ω²/(Ω²+Δ²)·sin²(√(Ω²+Δ²)·N/2) — Δ being the demod axis' ramp rate, the
    detuning this model has no explicit term for — so the swept response is Lorentzian-shaped and
    a frequency × length sweep is a chevron."""
    omega = math.pi / _CAV_N                                   # rad/batch on resonance
    sweep = list(range(_CAV_F0G1 - 150, _CAV_F0G1 + 151, 50))
    p1 = []
    for code in sweep:
        md = _cav()
        _cav_play(md, _cav_prep_f(md), "f0g1", code)
        p1.append(float(md.p_m()[1]))
    assert sweep[int(np.argmax(p1))] == _CAV_F0G1, f"peak not at f_f0g1: p_M1={np.round(p1, 3)}"
    assert p1[len(sweep) // 2] > 0.99, f"no full swap on resonance: {p1[len(sweep) // 2]:.3f}"
    assert p1[0] < 0.1 and p1[-1] < 0.1, f"the response did not fall off: {np.round(p1, 3)}"
    for code, obs in zip(sweep, p1):
        delta = (code - _CAV_F0G1) * BATCH_SIZE * math.pi / (1 << 15)      # axis ramp per batch
        g = math.hypot(omega, delta)
        pred = (omega ** 2 / g ** 2) * math.sin(g * _CAV_N / 2) ** 2
        assert abs(obs - pred) < 0.02, f"code {code}: P(M=1)={obs:.4f} vs Rabi law {pred:.4f}"


def test_cavity_photon_pulls_the_ge_resonance_by_chi():
    """The dispersive shift (M5 / `chi()`): the transmon's GE resonance sits at f_ge + n_M·chi_ge,
    so with a photon in M a π at the BARE f_ge is badly detuned and barely excites, while the same
    pulse at f_ge + chi_ge is a full π. With the cavity empty the bare π is exact — the pull is
    conditioned on the photon, which is what makes the χ measurement a difference of two Ramseys."""
    empty = _cav()
    _cav_play(empty, 0, "gate", _CAV_GE)
    assert empty.p_qubit()[1] > 0.99, f"the bare GE π is not a π at n_M=0: {empty.p_qubit()}"

    p_e = {}
    for label, code in (("bare", _CAV_GE), ("pulled", _CAV_GE + _CAV_CHI_GE)):
        md = _cav()
        t = _cav_prep_photon(md)
        assert md.p_m()[1] > 0.99, "photon prep failed"
        _cav_play(md, t, "gate", code)
        assert md.p_m()[1] > 0.99, f"the gate drive disturbed the cavity ({label})"
        p_e[label] = float(md.p_qubit()[1])
    assert p_e["pulled"] > 0.99, f"a π at f_ge + chi_ge must be a π with the photon: {p_e['pulled']:.4f}"
    assert p_e["bare"] < 0.1, f"a π at the bare f_ge must miss with the photon: {p_e['bare']:.4f}"


def test_cavity_flux_swaps_the_photon_into_storage():
    """The beam splitter (S2): with the photon in M, a flux-line flat-top of the planted length at
    f_MS moves it to the storage mode — |1>_M|0>_S → |0>_M|1>_S — leaving the transmon in |g>."""
    md = _cav()
    t = _cav_prep_photon(md)
    assert md.p_m()[1] > 0.99 and md.p_s()[0] > 0.99, "prep did not leave the photon in M"
    _cav_play(md, t, "flux", _CAV_MS)
    assert md.p_s()[1] > 0.99, f"the photon did not reach storage: p_S={md.p_s()}"
    assert md.p_m()[0] > 0.99, f"the photon did not leave M: p_M={md.p_m()}"
    assert md.p_qubit()[0] > 0.99, f"the beam splitter moved the transmon: {md.p_qubit()}"


def test_cavity_build_model_and_joint_state():
    """`build_model` round-trips a JSON-serializable spec (the planted truth: f_f0g1, the swap
    rates, f_MS, the χs), the model reads the three drive lines (plus the readout drive in collapse
    mode, the window trigger), `ground_truth()` reports the joint state, and the readout emits
    ThreeLevelModel's per-level phasor on the core's ADC."""
    import json
    rate = math.pi / (_CAV_N * _CAV_A)
    spec = {"kind": "cavity", "core": 0, "f_ge": _cav_hz(_CAV_GE), "f_ef": _cav_hz(_CAV_EF),
            "rabi_ge_rad_per_amp": rate, "rabi_ef_rad_per_amp": rate,
            "f_f0g1": _cav_hz(_CAV_F0G1), "f0g1_rad_per_amp": rate,
            "f_ms": _cav_hz(_CAV_MS), "ms_rad_per_amp": rate,
            "chi_ge": _cav_hz(_CAV_CHI_GE), "chi_ef": _cav_hz(_CAV_CHI_EF), "init_level": 1}
    md = models.build_model(spec, MM)
    assert isinstance(md, models.CavityModel)
    assert md.dac_ids() == [_MM_DAC["gate"], _MM_DAC["f0g1"], _MM_DAC["flux"]]
    assert models.build_model({**spec, "collapse": True}, MM).dac_ids()[-1] == _MM_DAC["ro"]

    gt = md.ground_truth()
    assert json.loads(json.dumps(gt)) == gt                      # JSON-serializable ground truth
    assert gt["p_qubit"] == [0.0, 1.0, 0.0] and gt["p_M"] == [1.0, 0.0, 0.0]   # init_level = |e>
    assert gt["p_S"] == [1.0, 0.0]
    assert np.array(gt["populations"]).shape == (3, 3, 2)
    assert len(gt["psi"]) == 18                                  # the 3x3x2 state vector, [re, im]
    assert gt["psi"][6] == [1.0, 0.0]                            # psi[|e>, 0, 0] (C order, 6/level)

    # the swap chain is visible in the marginals the exact-population tests read
    swept = models.build_model(spec, MM)
    swept._psi[:] = 0.0
    swept._psi[0, 0, 0] = 1.0
    _cav_play(swept, _cav_prep_photon(swept), "flux", _CAV_MS)
    st = swept.ground_truth()
    assert st["p_qubit"][0] > 0.99 and st["p_M"][0] > 0.99 and st["p_S"][1] > 0.99

    # readout: one tone per TRANSMON level, at the three default phases (120° apart) — the demod
    # of a silent window, as `_readout_iq` does for the qutrit (the cavity state is invisible to it)
    iq = []
    for level in range(3):
        md._psi[:] = 0.0
        md._psi[level, 0, 0] = 1.0
        acc = 0j
        for t in range(24):
            lanes = md.adc_batch(t, _cav_dac(t))[MM.adc_of(0)].astype(float)
            k = np.arange(ADC_BATCH) + ADC_BATCH * t
            acc += np.sum(lanes * np.exp(-1j * math.pi * md.readout_code * k / (1 << 15)))
        iq.append(complex(acc))
    assert min(abs(z) for z in iq) > 0.5 * max(abs(z) for z in iq)      # similar magnitude
    for a, b in ((0, 1), (1, 2), (0, 2)):
        sep = abs((np.angle(iq[a]) - np.angle(iq[b]) + math.pi) % (2 * math.pi) - math.pi)
        assert sep > math.radians(90), f"levels {a},{b} readout phases too close"


def test_cavity_idle_relax_resets_the_transmon_and_keeps_the_photon():
    """`t1` damps the TRANSMON toward |g> on idle batches — the grid reset every batched sweep leans
    on (ThreeLevelModel's, per (m, s) block) — while the cavity keeps its photon: this model has no
    cavity decay, so a loaded mode survives the idle head and must be emptied by a pulse."""
    md = _cav(t1=5.0)
    md._psi[:] = 0.0
    md._psi[1, 1, 0] = 1.0                                   # |e, 1_M, 0_S>
    for t in range(60):
        md.adc_batch(t, _cav_dac(t))                         # idle: every line silent
    assert md.p_qubit()[0] > 0.999, f"the transmon did not relax to |g>: {md.p_qubit()}"
    assert md.p_m()[1] > 0.999, f"the M photon decayed: p_M={md.p_m()}"
    assert abs(float(np.sum(md.populations())) - 1.0) < 1e-12, "relaxation did not preserve the norm"
