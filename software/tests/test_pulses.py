"""Host-side tests for riscq.pulses: envelope library, unit conversions, SF(16) packing,
the envelope allocator, and golden-model sanity (the bit-exact pin is the cosim test_pulse)."""

from pathlib import Path

import numpy as np
import pytest

from riscq.map import SocMap, SocParams, pack16
from riscq.pulses import EnvelopeAllocator, Pulse
from riscq.pulses import envelopes, golden, units
from riscq.pulses.envelopes import FULL
from riscq.pulses.pack import pack_env, to_sf16, unpack_env

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
M = SocMap(SocParams.load(CONFIGS / "sim-2q.json"))
FS = units.sample_rate(M.params)   # 16 * 100 MHz = 1.6 GHz in the sim build


# ── envelopes ──

def test_gaussian():
    e = envelopes.gaussian(64, 3.0)
    assert e.shape == (64,) and e.dtype == complex
    assert np.abs(e).max() == pytest.approx(FULL)
    assert np.allclose(e, e[::-1])                      # symmetric
    assert not e.imag.any()
    assert abs(e[0]) < 0.02                             # truncated at 3 sigma


def test_drag():
    g = envelopes.gaussian(64, 3.0)
    assert np.allclose(envelopes.drag(64, 3.0, 0.0), g)
    d = envelopes.drag(64, 3.0, 2.0)
    assert d.imag.any()
    assert np.abs(d).max() == pytest.approx(FULL)


def test_cosine_square():
    """qcal's ramp convention: each edge is n_ramp = round(n*ramp_fraction) samples of
    (1 − cos(pi*k/n_ramp))/2 — the rising edge starts at 0, the flat top is FULL, and the falling edge
    is the second half of the same cosine, so its LAST sample sits one step short of zero."""
    e = envelopes.cosine_square(40, 0.25)
    assert e[0] == 0 and e[20] == FULL
    assert 0 < abs(e[-1]) < 0.05 * FULL                        # the edge closes one sample after the end
    assert (np.diff(abs(e[:10])) > 0).all() and (np.diff(abs(e[-10:])) < 0).all()   # ramps up, ramps down
    assert abs(e[10:30]) == pytest.approx(FULL)                # flat top of n − 2*n_ramp samples
    assert np.allclose(envelopes.cosine_square(16, 0.0), envelopes.square(16))
    assert envelopes.cos_edge_square is envelopes.cosine_square     # the old name still resolves
    with pytest.raises(ValueError):
        envelopes.cosine_square(10, 0.6)


def test_square_and_arb():
    assert (envelopes.square(8) == FULL).all()
    a = envelopes.arb([0.1 + 0.2j, -0.3])
    assert a.dtype == complex and a.shape == (2,)
    with pytest.raises(ValueError):
        envelopes.arb([1.0])                            # 1.0 > FULL clips
    with pytest.raises(ValueError):
        envelopes.arb(np.zeros((2, 2)))


# ── units ──

def test_freq_to_code_known_numbers():
    # _freq_code is the PLAIN code = round(f * 2^16 / fs) (fs = 1.6 GHz here); freq_to_code is the
    # SEATED register word = pack16(plain).
    assert units._freq_code(0.0, M.params) == 0
    assert units._freq_code(25e6, M.params) == 1024
    assert units._freq_code(-50e6, M.params) == -2048
    assert units.freq_to_code(25e6, M.params) == pack16(1024)     # seated register word
    assert units.code_to_freq(1024, M.params) == pytest.approx(25e6)
    # a tone above Nyquist folds mod 2^16 to its negative-frequency code (49152 - 65536)
    assert units._freq_code(0.75 * FS, M.params) == -16384
    assert units._freq_code(0.75 * FS, M.params) == units._freq_code(-0.25 * FS, M.params)
    with pytest.raises(ValueError):
        units.freq_to_code(FS, M.params)                # code 65536 == one full turn, still loud


def test_demod_freq_to_code_is_4x_dac():
    # the demod LO is per-ADC-SAMPLE (4 samples/batch) vs the DAC's per-DAC-sample (16/batch),
    # so for one physical frequency the demod code is 4x the DAC's. The absolute factor is PINNED
    # by measurement in the cosim loopback test; this checks the derived relationship + roundtrip.
    for f in (25e6, -50e6, 12.5e6):
        assert units._demod_code(f, M.params) == 4 * units._freq_code(f, M.params)
    assert units._demod_code(25e6, M.params) == 4096
    assert units.demod_freq_to_code(25e6, M.params) == pack16(4096)   # seated register word
    assert units.demod_code_to_freq(4096, M.params) == pytest.approx(25e6)
    # a tone above the demod's own Nyquist still folds mod 2^16 — the on-core set_freq(demod, 4*code)
    # writes the product into the same 16-bit register, so the code is 4*freq_to_code folded there.
    # (the demod band spans BATCH_SIZE/ADC_BATCH turns, so a DAC tone anywhere below fs is legal.)
    f = 0.375 * FS                                      # 1.5 demod turns -> must fold, not raise
    fc = units._freq_code(f, M.params)
    assert units._demod_code(f, M.params) == ((4 * fc + (1 << 15)) & 0xFFFF) - (1 << 15)
    assert units._demod_code(f, M.params) == -(1 << 15)
    with pytest.raises(ValueError):
        units.demod_freq_to_code(4 * FS, M.params)      # past the demod band (16 turns), still loud
    # a frequency where rounding at the ADC rate INDEPENDENTLY lands 2 LSB (61 kHz) off 4x the DAC
    # code: the demod must track the tone the DAC actually synthesizes — its rounded code — or the
    # drive-demod offset rotates the readout phase shot to shot (the hardware iq_scatter ring).
    f = 0.30001 * FS
    fc = units._freq_code(f, M.params)                   # 19661 (4x = 78644)
    assert round(f * (1 << 16) / (FS / 4)) == 78646      # what an independent round would give
    assert units._demod_code(f, M.params) == ((4 * fc + (1 << 15)) & 0xFFFF) - (1 << 15) == 13108


def test_phase_to_code():
    # _phase_code is the PLAIN code; phase_to_code is the SEATED register word
    assert units._phase_code(0.0) == 0
    assert units._phase_code(np.pi / 2) == 1 << 14
    assert units._phase_code(np.pi) == -(1 << 15)       # +pi wraps to the -pi code
    assert units._phase_code(-np.pi) == -(1 << 15)
    assert units._phase_code(2 * np.pi) == 0
    assert units._phase_code(5 * np.pi / 2) == units._phase_code(np.pi / 2)
    assert units.phase_to_code(np.pi / 2) == pack16(1 << 14)   # seated register word


def test_amp_to_code_pinned_scale():
    # AMP_SCALE = round((Amax - ceil(err) - 1) / K_cordic): the K-prescaled, headroomed
    # full scale (the same constant the hardware derives for its phasor magnitude).
    assert golden.AMP_SCALE == 19896 == golden.PHASOR_MAG
    assert golden.K_CORDIC == pytest.approx(1.6467602581, abs=1e-9)
    assert units._amp_code(1.0) == 19896            # the plain code (the scale pin)
    assert units._amp_code(0.5) == 9948
    assert units._amp_code(0.0) == 0
    assert units.amp_to_code(0.5) == pack16(9948)   # the seated register word
    with pytest.raises(ValueError):
        units.amp_to_code(1.01)


def test_batches_ns():
    assert units.batches(100.0, M.params) == 10         # 100 ns at 100 MHz dsp clock
    assert units.ns(10, M.params) == pytest.approx(100.0)


# ── packing ──

def test_pack_word_layout():
    env = np.array([(3 + 5j), (-4 - 6j)]) / 32768       # exact SF(16) codes 3,5 / -4,-6
    lines = pack_env(env, samples_per_line=4)           # gate grid: pads to one 4-word line
    assert lines.shape == (1, 4) and lines.dtype == np.uint32
    assert lines[0, 0] == (5 << 16) | 3                 # sample j = word j, re low, im high
    assert lines[0, 1] == ((-6 & 0xFFFF) << 16) | (-4 & 0xFFFF)
    assert lines[0, 2] == 0 and lines[0, 3] == 0        # zero padding
    ro = pack_env(env, samples_per_line=1)
    assert ro.shape == (2, 1) and ro[0, 0] == (5 << 16) | 3


def test_pack_clip_is_loud():
    with pytest.raises(ValueError, match="clip"):
        to_sf16(np.array([1.0]))
    with pytest.raises(ValueError, match="clip"):
        pack_env(np.array([-1.0 - 0.5j]), 4)            # -1.0 rounds to -32768: clips
    assert to_sf16(np.array([32767 / 32768]))[0] == 32767
    assert to_sf16(np.array([-32767 / 32768]))[0] == -32767


def test_pack_roundtrip():
    rng = np.random.default_rng(7)
    env = (rng.uniform(-0.9, 0.9, 33) + 1j * rng.uniform(-0.9, 0.9, 33))
    lines = pack_env(env, 4)
    back = unpack_env(lines)
    assert back.size == 36                              # padded to 9 lines
    err = back[:33] - env
    assert max(np.abs(err.real).max(), np.abs(err.imag).max()) <= 0.5 / 32768  # one rounding
    assert not back[33:].any()
    assert np.array_equal(pack_env(back, 4), lines)     # codes are a fixed point


# ── Pulse hooks ──

def test_pulse_hooks():
    p = Pulse(envelopes.gaussian(64, 3.0), freq_hz=25e6, amp=0.5, phase=np.pi / 8)
    assert p.dur_batches(M, 0) == 16                    # gate: 64 samples / 4 per line
    assert p.dur_batches(M, 1) == 64                    # ro: 1 sample per line
    assert p.packed_lines(M, 0).shape == (16, 4)
    assert p.packed_lines(M, 1).shape == (64, 1)
    assert p.freq_code(M) == 1024
    assert p.amp_code() == 9948
    with pytest.raises(ValueError, match="unknown channel index"):
        p.dur_batches(M, 9)


# ── ParamTable ──

def test_paramtable_slot_of_and_freq():
    from riscq.lang import ParamTable
    t = ParamTable(channel=0, freq_hz=25e6,
                   pulses={"x90": Pulse(envelopes.gaussian(64, 3.0), amp=0.5),
                           "x180": Pulse(envelopes.gaussian(64, 3.0), amp=1.0)})
    assert t.slot_of("x90") == 0 and t.slot_of("x180") == 1   # dict insertion order
    assert t.channel == 0
    # the table carrier folds to the SEATED register word = freq_to_code = pack16(1024)
    assert t.freq_code(M) == units.freq_to_code(25e6, M.params) == pack16(1024)
    with pytest.raises(KeyError):
        t.slot_of("nope")


def test_paramtable_pulse_freq_must_match_carrier():
    from riscq.lang import ParamTable
    # a pulse may inherit the carrier (freq_hz=None) or state the SAME carrier; anything else loud
    ParamTable(0, 25e6, {"x": Pulse(envelopes.square(16), amp=0.5)})                 # inherit
    ParamTable(0, 25e6, {"x": Pulse(envelopes.square(16), amp=0.5, freq_hz=25e6)})   # equal
    with pytest.raises(ValueError, match="!= table carrier"):
        ParamTable(0, 25e6, {"x": Pulse(envelopes.square(16), amp=0.5, freq_hz=30e6)})


def test_paramtable_rejects_empty_and_non_pulse():
    from riscq.lang import ParamTable
    with pytest.raises(ValueError, match="at least one pulse"):
        ParamTable(0, 25e6, {})
    with pytest.raises(ValueError, match="must be a riscq.pulses.Pulse"):
        ParamTable(0, 25e6, {"x": 5})


# ── allocator ──

def test_alloc_dedup_and_sequential():
    a = EnvelopeAllocator(env_depth=32)
    la = a.add(pack_env(envelopes.gaussian(16, 3.0), 4), name="ga")
    lb = a.add(pack_env(envelopes.square(16), 4), name="sq")
    assert (la, lb) == (0, 4)                           # sequential, 4 lines each
    assert a.add(pack_env(envelopes.gaussian(16, 3.0), 4)) == la   # dedup: same content
    assert len(a.image()) == 2                          # one copy only
    lc = a.add(pack_env(envelopes.gaussian(16, 2.0), 4))
    assert lc == 8


def test_alloc_overflow_is_loud():
    a = EnvelopeAllocator(env_depth=4)
    a.add(pack_env(envelopes.square(16), 4))
    with pytest.raises(ValueError, match="my_big_pulse"):
        a.add(pack_env(envelopes.gaussian(16, 3.0), 4), name="my_big_pulse")


# ── golden-model sanity (the bit-exact pin vs RTL is cosim test_pulse) ──

def test_golden_constants():
    assert golden.TIME_TO_PULSE == 36                   # the exported RTL latency sum
    re, im = golden.cordic_rotate(golden.PHASOR_MAG, 0, 0)
    assert abs(re - golden.PH_MAG) <= 2 and abs(im) <= 2   # ~full-scale, tiny residual
    re, im = golden.cordic_rotate(golden.PHASOR_MAG, 0, 1 << 14)   # +pi/2
    assert abs(re) <= 2 and abs(im - golden.PH_MAG) <= 2


def test_golden_zero_amp_is_zero():
    lines = pack_env(envelopes.square(16), 4)
    w = golden.pulse_window(lines, amp_code=0, freq_code=2048, phase_code=0,
                            t_start=1000, dur=4)
    assert w.shape == (4, 16) and not w.any()


def test_golden_rejects_short_envelope():
    lines = pack_env(envelopes.square(16), 4)           # 4 lines
    with pytest.raises(ValueError, match="dur"):
        golden.pulse_window(lines, 9948, 1024, 0, 0, dur=5)
