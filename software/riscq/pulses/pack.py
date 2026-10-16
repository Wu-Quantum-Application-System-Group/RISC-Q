"""float complex <-> SF(16) envelope-RAM packing.

Word layout (both channels): stored sample j is 32-bit word j of its line, re = SF(16) low
half [15:0], im = high half [31:16]. Gate line = 4 samples = 4 words (one 128-bit RAM line,
host addresses line*16 + {0,4,8,12} — contiguous); readout line = 1 sample = 1 word at line*4.
Envelopes are zero-padded up to a whole number of lines."""

from __future__ import annotations

import numpy as np

SF_ONE = 1 << 15          # code for +1.0 (unreachable); value = code / SF_ONE
SF_MAX = SF_ONE - 1       # 32767


def to_sf16(x: np.ndarray) -> np.ndarray:
    """round(x * 32768) -> int16 codes; loud error if any |x| > 32767/32768 (would clip)."""
    codes = np.round(np.asarray(x, dtype=float) * SF_ONE)
    bad = np.abs(codes) > SF_MAX
    if bad.any():
        worst = float(np.abs(np.asarray(x, dtype=float))[bad].max())
        raise ValueError(f"{int(bad.sum())} sample(s) clip: |x| up to {worst} > {SF_MAX}/{SF_ONE}")
    return codes.astype(np.int16)


def from_sf16(codes: np.ndarray) -> np.ndarray:
    return np.asarray(codes, dtype=np.int64) / SF_ONE


def pack_env(env: np.ndarray, samples_per_line: int) -> np.ndarray:
    """Complex envelope -> packed RAM lines, shape (n_lines, samples_per_line) uint32.
    Pads with zeros to a whole number of lines (4 samples/line gate, 1 readout)."""
    env = np.asarray(env, dtype=complex)
    if env.ndim != 1 or env.size == 0:
        raise ValueError(f"envelope must be a non-empty 1-D complex array, got shape {env.shape}")
    pad = -env.size % samples_per_line
    if pad:
        env = np.concatenate([env, np.zeros(pad, dtype=complex)])
    re = to_sf16(env.real).astype(np.uint16).astype(np.uint32)
    im = to_sf16(env.imag).astype(np.uint16).astype(np.uint32)
    words = re | (im << 16)
    return words.reshape(-1, samples_per_line)


def unpack_env(lines: np.ndarray) -> np.ndarray:
    """Inverse of pack_env (including the padding): (n_lines, spl) uint32 -> complex float64."""
    words = np.asarray(lines, dtype=np.uint32).reshape(-1)
    re = (words & 0xFFFF).astype(np.uint16).astype(np.int16)
    im = (words >> 16).astype(np.uint16).astype(np.int16)
    return from_sf16(re) + 1j * from_sf16(im)
