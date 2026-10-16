"""Bit-exact numpy golden of the SoC's DAC pulse composition — a python port of the repo's
Scala golden models (Cordic.rotate, ComplexMul.model, PulseGeneratorSim's composed golden),
evaluated at THE SoC configuration only: dataWidth 16, prescaleAmp=True (=> correctGain=False,
CORDIC round folded into the last micro-rotation), saturate=False (bare two's-complement wrap),
phasorMethod=Cordic, realOutput=True (DAC carries the real lane).

Composition, for output batch at (channel) time T = t_start + i, lane k in [0,16):

    tau        = T - TIME_TO_PULSE                       # carrier absolute-time alignment
    gPhase     = wrap16(wrap16(freq * wrap16(16*tau)) + phase)
    cBase      = cordic_rotate(amp, 0, gPhase)           # amp * exp(i*pi*gPhase)
    phasor[k]  = cordic_rotate(PHASOR_MAG, 0, wrap16(k*freq))
    carrier[k] = cmul(cBase, phasor[k])
    dac[k]     = Re(cmul(carrier[k], env[t_start-env_line0+i][k // interp]))

All constants below are DERIVED from the same formulas as the RTL elaboration (no magic
numbers); the cosim DAC-capture tests assert the whole composition bit-exactly against the
hardware."""

from __future__ import annotations

import math
from functools import lru_cache

import numpy as np

# ── the SoC CORDIC configuration (CordicParams at dataWidth 16, prescaleAmp, no saturate) ──
W = 16
ITERS = W + 1                                   # nIter < 0 => xyWidth + 1
_LOG2UP_ITERS = (ITERS - 1).bit_length()
G = _LOG2UP_ITERS + 3                           # xy fractional guard bits
ZG = _LOG2UP_ITERS + 3                          # z fractional guard bits
Z_INTERNAL = W + ZG
AMAX = (1 << (W - 1)) - 1                       # 32767

K_CORDIC = math.prod(math.sqrt(1 + 2.0 ** (-2 * i)) for i in range(ITERS))  # 1.6467602580571632

# atan table: alpha(i) = round(atan(2^-i)/pi * 2^(zInternal-1)) — scala round = floor(x+0.5)
_ALPHA = [math.floor(math.atan(2.0 ** -i) / math.pi * (1 << (Z_INTERNAL - 1)) + 0.5)
          for i in range(ITERS)]

# analytic worst-case CORDIC output error in codes (Cordic.errorBound, correctGain=False)
_ERR = (math.atan(2.0 ** -(ITERS - 1)) * AMAX
        + ITERS * 0.5 * 2.0 ** -(Z_INTERNAL - 1) * math.pi * AMAX
        + 2.0 * ITERS * 2.0 ** -G * K_CORDIC
        + 0.5)

# PhasorBatchGenerator (no-sat): target |phasor| pulled under full scale by the error bound,
# CORDIC input prescaled by 1/K (prescaleAmp drops the gain stage).
PH_MAG = AMAX - math.ceil(_ERR) - 1                       # 32764
PHASOR_MAG = math.floor(PH_MAG / K_CORDIC + 0.5)          # 19896 — the CORDIC x input

# ONE definition of software amplitude: amp code = round(a * AMP_SCALE). a = 1.0 gives the same
# K-prescaled, error-headroomed full-scale the hardware uses for its own phasor constant, so the
# no-saturate datapath provably never wraps (|K*code| + err <= AMAX).
AMP_SCALE = PHASOR_MAG                                    # 19896

# time -> pulse-output latency (PulseGenerator.timeToPulse, all exported latencies summed):
# ComplexMul no-sat latency 6; Cordic latency 1 + iters + 1 (no gain stage);
# carrier tail = cordic + broadcast + cmul; timeLatency = 3 + tail; + envMul + output gate.
_LM = 6
_CORDIC_LATENCY = 1 + ITERS + 1
_TAIL = _CORDIC_LATENCY + 1 + _LM
TIME_TO_PULSE = (3 + _TAIL) + _LM + 1                     # 36

# the worst timed-queue lead (freq -> phasor regen): regenCycles + phasorLatency + envMul + gate.
# map.LEAD must dominate linkPipe + this (asserted by the M1 lead-margin test).
LEAD_FREQ_P = (16 + _CORDIC_LATENCY + 2) + (2 + _LM) + _LM + 1   # 52

_MASK = (1 << W) - 1


def wrap16(v: int) -> int:
    """Two's-complement wrap to SF(16) — the no-saturate `resize`."""
    v &= _MASK
    return v - (1 << W) if v >= (1 << (W - 1)) else v


def cordic_rotate(x0: int, y0: int, z0: int) -> tuple[int, int]:
    """Cordic.rotate for the SoC params: pi pre-rotation, 17 iterations, round folded at the
    output (+half-ulp then >>G), two's-complement wrap. rsp ~= K * (x0,y0) * exp(i*pi*z0/2^15)."""
    zu = z0 & _MASK
    pre = ((zu >> (W - 1)) & 1) != ((zu >> (W - 2)) & 1)
    x, y, z = x0 << G, y0 << G, z0 << ZG
    if pre:
        x, y = -x, -y
        f = (z & ((1 << Z_INTERNAL) - 1)) ^ (1 << (Z_INTERNAL - 1))   # flip MSB = -+pi
        z = f - (1 << Z_INTERNAL) if f >= (1 << (Z_INTERNAL - 1)) else f
    for i in range(ITERS):
        d = 1 if z >= 0 else -1
        xs, ys = x >> i, y >> i          # python >> on ints is arithmetic (floor), like BigInt
        x, y = x - d * ys, y + d * xs
        z -= d * _ALPHA[i]
    half = 1 << (G - 1)
    return wrap16((x + half) >> G), wrap16((y + half) >> G)


def cmul(ar: int, ai: int, br: int, bi: int) -> tuple[int, int]:
    """ComplexMul.model, saturate=False: Karatsuba product, +half-ulp round on >>(W-1), wrap."""
    m = (ar - ai) * bi
    pr = (br - bi) * ar + m
    pi = (br + bi) * ai + m
    half = 1 << (W - 2)
    return wrap16((pr + half) >> (W - 1)), wrap16((pi + half) >> (W - 1))


@lru_cache(maxsize=None)
def phasors(freq_code: int) -> tuple[tuple[int, int], ...]:
    """The 16 static per-lane phasors: cordic_rotate(PHASOR_MAG, 0, wrap16(k*freq))."""
    return tuple(cordic_rotate(PHASOR_MAG, 0, wrap16(k * freq_code)) for k in range(16))


def pulse_window(lines: np.ndarray, amp_code: int, freq_code: int, phase_code: int,
                 t_start: int, dur: int) -> np.ndarray:
    """The bit-exact DAC window [t_start, t_start+dur): shape (dur, 16) int16 real lanes.

    `lines` is the PACKED envelope RAM content the pulse plays, shape (n_lines, spl) uint32
    (spl = stored samples per line: 4 gate / 1 readout); output lane k of batch i holds stored
    sample k // (16 // spl) of line i (the hardware sample-and-hold)."""
    lines = np.asarray(lines, dtype=np.uint32)
    if lines.ndim != 2:
        raise ValueError(f"lines must be (n_lines, samples_per_line), got shape {lines.shape}")
    n_lines, spl = lines.shape
    if dur > n_lines:
        raise ValueError(f"dur {dur} exceeds the {n_lines} envelope lines given")
    interp = 16 // spl
    env_re = lines.astype(np.uint32) & 0xFFFF
    env_im = lines >> 16
    env_re = np.where(env_re >= 0x8000, env_re.astype(np.int64) - 0x10000, env_re).astype(np.int64)
    env_im = np.where(env_im >= 0x8000, env_im.astype(np.int64) - 0x10000, env_im).astype(np.int64)

    ph = phasors(freq_code)
    out = np.zeros((dur, 16), dtype=np.int16)
    for i in range(dur):
        tau = t_start + i - TIME_TO_PULSE
        g_phase = wrap16(wrap16(freq_code * wrap16((tau * 16))) + phase_code)
        cr, ci = cordic_rotate(amp_code, 0, g_phase)
        for k in range(16):
            car = cmul(cr, ci, *ph[k])
            s = k // interp
            re, _ = cmul(car[0], car[1], int(env_re[i, s]), int(env_im[i, s]))
            out[i, k] = re
    return out
