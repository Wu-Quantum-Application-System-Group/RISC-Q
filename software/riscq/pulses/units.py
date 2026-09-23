"""Physical-unit <-> hardware-code conversions. THE definitions — nothing else in the stack
converts units. Pinned by measurement in the M1 cosim tests (VNA frequency test, DAC amp test),
not by RTL reading."""

from __future__ import annotations

import math

from riscq.map import ADC_BATCH, BATCH_SIZE, SocParams, pack16
from riscq.pulses.golden import AMP_SCALE  # noqa: F401  (re-exported: THE amplitude scale)

_PERIOD = 1 << 16    # SF(16) phase wraps mod 2^16 (one full turn per sample == the sample rate)

# The public *_to_code return the SEATED register word — the 16-bit code in data[31:16] (map.pack16,
# spec 12) — so software writes them straight to a pulse-parameter register with no re-pack:
# `set_freq(ch, freq_to_code(f))`, `set_amp(ch, s, amp_to_code(a))`, `set_freq(demod,
# demod_freq_to_code(f))`. The private _*_code return the PLAIN signed 16-bit code, for the internals
# that run the SoC's 16-bit datapaths and can't take a seated value: the bit-exact golden
# (golden.pulse_window feeds a 16-bit CORDIC), the ADC model (a `% 2^16` phase accumulator), the
# pulse-table slot codes (Pulse.*_code, seated once by riscq.run.load_tables), and the freq-sweep
# centre fed to sweep_q16. code_to_freq/demod_code_to_freq take that same plain code (a realized
# fit/sweep x-axis), so freq_to_code and code_to_freq are NOT literal inverses — one programs a
# register, the other reports a measured code.


def _wrap_sf16(f_hz: float, code: int, rate: float) -> int:
    """Fold a raw per-sample phase-advance code into signed SF(16) [-2^15, 2^15). The hardware
    phase accumulator wraps mod 2^16, so a positive code above Nyquist (2^15) is bit-for-bit the
    same tone as code - 2^16 below zero; fold it there rather than rejecting it. Only a code a
    full turn or more from DC (a tone at or past the sample rate) is a genuine out-of-range error
    and still fails loud."""
    if not -_PERIOD < code < _PERIOD:
        raise ValueError(f"freq {f_hz} Hz -> code {code} exceeds one full turn "
                         f"[{-_PERIOD + 1}, {_PERIOD - 1}] (rate {rate:g} Hz)")
    return ((code + (1 << 15)) & 0xFFFF) - (1 << 15)


def sample_rate(params: SocParams) -> float:
    """DAC sample rate: 16 samples per batch, one batch per dsp cycle."""
    return BATCH_SIZE * params.dsp_freq_hz


def channel_rate(params_or_map, channel) -> float:
    """One channel's own converter sample rate: its lanes per batch, one batch per dsp cycle — 16
    on a DAC-bound pulse channel (= sample_rate), 4 on the ADC-bound demod. `channel` is a
    ChannelInfo (riscq.map) or a ChannelSpec; `params_or_map` a SocParams or a SocMap."""
    params = getattr(params_or_map, "params", params_or_map)
    return channel.lanes * params.dsp_freq_hz


def _freq_code(f_hz: float, params: SocParams) -> int:
    """Plain carrier/demod frequency code = per-SAMPLE phase advance in pi units, SF(16):
    code = round(f_hz * 2^16 / (16 * dsp_freq_hz)), folded mod 2^16 into signed SF(16) so a tone
    above Nyquist aliases to its negative-frequency code (the phase accumulator wraps the same way).
    Fails loud only past one full turn."""
    fs = sample_rate(params)
    return _wrap_sf16(f_hz, round(f_hz * (1 << 16) / fs), fs)


def freq_to_code(f_hz: float, params: SocParams) -> int:
    """The carrier frequency as a SEATED register word (spec 12): `set_freq(ch, freq_to_code(f))`."""
    return pack16(_freq_code(f_hz, params))


def code_to_freq(code: int, params: SocParams) -> float:
    """Inverse of _freq_code: Hz from a PLAIN 16-bit code (a realized fit/sweep code, not a word)."""
    return code * sample_rate(params) / (1 << 16)


def _demod_code(f_hz: float, params: SocParams) -> int:
    """Plain demod-LO frequency code = per-ADC-SAMPLE phase advance in pi units, SF(16), DERIVED from
    the DAC code: BATCH_SIZE/ADC_BATCH (4x) _freq_code, folded mod 2^16 — exactly what the
    on-core set_freq(demod, 4 * dac_code) truncates into the same 16-bit register. It must NOT be
    rounded independently from f_hz: the demod has to track the tone the DAC actually synthesizes
    (its rounded code), and an independent round(f * 2^16 / adc_rate) lands 1-2 LSB (30-61 kHz at
    2 GS/s) off 4x the DAC code at most frequencies — a drive-vs-demod offset that rotates the
    readout phase shot to shot (the hardware iq_scatter ring, 2026-07). Accepts any tone
    _freq_code accepts (the full DAC band = BATCH_SIZE/ADC_BATCH demod turns); fails loud past
    it. The 4x relation itself is pinned by measurement in the M3 loopback test."""
    code = (BATCH_SIZE // ADC_BATCH) * _freq_code(f_hz, params)
    return ((code + (1 << 15)) & 0xFFFF) - (1 << 15)


def demod_freq_to_code(f_hz: float, params: SocParams) -> int:
    """The demod-LO frequency as a SEATED register word (spec 12): `set_freq(demod, demod_freq_to_code(f))`."""
    return pack16(_demod_code(f_hz, params))


def demod_code_to_freq(code: int, params: SocParams) -> float:
    """Inverse of _demod_code: Hz from a PLAIN 16-bit demod code (a realized code, not a word)."""
    return code * (ADC_BATCH * params.dsp_freq_hz) / (1 << 16)


def _phase_code(rad: float) -> int:
    """Plain phase code in pi units SF(16); phase wraps by design (two's-complement wrap = exact
    2*pi wrap), so any angle is reduced mod 2*pi first."""
    x = math.remainder(rad, 2 * math.pi)          # [-pi, pi]
    code = round(x / math.pi * (1 << 15))
    return ((code + (1 << 15)) & 0xFFFF) - (1 << 15)   # +pi and -pi are the same angle


def phase_to_code(rad: float) -> int:
    """The phase as a SEATED register word (spec 12): `set_phase`/`set_phase_offset`/`write_slot`."""
    return pack16(_phase_code(rad))


def _amp_code(a: float) -> int:
    """Plain amplitude code = round(a * AMP_SCALE), a in [-1, 1]. AMP_SCALE (= 19896) is the max safe
    code under prescaleAmp: software pre-multiplies by 1/K_cordic (K = prod sqrt(1+2^-2i),
    ~1.64676) with the same error headroom the hardware gives its own phasor constant, so the
    no-saturate datapath never wraps. Pinned empirically by the M1 DAC amp test."""
    if not -1.0 <= a <= 1.0:
        raise ValueError(f"amplitude {a} outside [-1, 1]")
    return round(a * AMP_SCALE)


def amp_to_code(a: float) -> int:
    """The amplitude as a SEATED register word (spec 12): `set_amp(ch, slot, amp_to_code(a))`."""
    return pack16(_amp_code(a))


def batches(t_ns: float, params: SocParams) -> int:
    """Duration in batches (dsp cycles) nearest to t_ns."""
    return round(t_ns * 1e-9 * params.dsp_freq_hz)


def ns(n_batches: int, params: SocParams) -> float:
    return n_batches / params.dsp_freq_hz * 1e9
