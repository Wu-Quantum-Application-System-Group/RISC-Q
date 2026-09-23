"""Axes and params (specs/universal-cal/01 §4): the knobs a sequence is swept over.

An `Axis` is an on-core affine sweep — the kernel accumulates `x += dx` per point (`k_batched`'s
`a`/`b`) — plus the EXACT host mirror of the values it realizes. A `Param` is a runtime int32 the
host rewrites per rerun (`r0..r3`). Both are symbols the generated C can use, and `Lin` is the
affine arithmetic over them that every placement needs: a play at `t_ro - 62 - a + 36*k`.

Representations (spec 12): amp / freq axes accumulate a raw Q16 word (the code in data[31:16]),
phase axes a seated word, dur / wait axes a plain batch count; a Param carries whatever word its
consumer needs (the class layer seats phases, converts seconds to batches)."""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from riscq.map import pack16
from riscq.pulses import units


class Sym:
    """A symbol the generated C can name. Arithmetic builds a `Lin`."""

    def __add__(self, o):
        return Lin.of(self) + o

    __radd__ = __add__

    def __sub__(self, o):
        return Lin.of(self) - o

    def __rsub__(self, o):
        return Lin.of(o) - self

    def __mul__(self, k):
        return Lin.of(self) * k

    __rmul__ = __mul__

    def __neg__(self):
        return Lin.of(self) * -1


@dataclass(frozen=True)
class Lin:
    """`const + Σ coef·sym` over ints. `terms` is a tuple of (sym, coef) with no zero coefs."""

    const: int = 0
    terms: tuple = ()

    @staticmethod
    def of(x) -> "Lin":
        if isinstance(x, Lin):
            return x
        if isinstance(x, Sym):
            return Lin(0, ((x, 1),))
        if isinstance(x, (int, np.integer)) and not isinstance(x, bool):
            return Lin(int(x))
        raise TypeError(f"not an affine int expression: {x!r}")

    def _merge(self, other: "Lin", sign: int) -> "Lin":
        d = dict(self.terms)
        for s, c in other.terms:
            d[s] = d.get(s, 0) + sign * c
        return Lin(self.const + sign * other.const, tuple((s, c) for s, c in d.items() if c))

    def __add__(self, o):
        return self._merge(Lin.of(o), 1)

    __radd__ = __add__

    def __sub__(self, o):
        return self._merge(Lin.of(o), -1)

    def __rsub__(self, o):
        return Lin.of(o)._merge(self, -1)

    def __mul__(self, k):
        if isinstance(k, (Sym, Lin)):
            raise TypeError("a product of two symbols is not affine (a runtime repeat of a "
                            "swept idle) — bind one of them at compile time")
        k = int(k)
        return Lin(self.const * k, tuple((s, c * k) for s, c in self.terms if c * k))

    __rmul__ = __mul__

    def __neg__(self):
        return self * -1

    @property
    def is_const(self) -> bool:
        return not self.terms

    def bound(self, ranges: dict, hi: bool = True) -> int:
        """The max (or min) over `ranges[sym] = (lo, hi)` — a Lin is monotone per symbol."""
        v = self.const
        for s, c in self.terms:
            lo, up = ranges[s]
            v += c * (up if (c > 0) == hi else lo)
        return v

    def c(self, names: dict) -> str:
        """Render as C given `names[sym]`, e.g. `t_ro - 62 - a + 36 * k`."""
        parts = [str(self.const)] if self.const or not self.terms else []
        for s, k in self.terms:
            n = names[s]
            if k == 1:
                parts.append(f"+ {n}")
            elif k == -1:
                parts.append(f"- {n}")
            else:
                parts.append(f"{'-' if k < 0 else '+'} {abs(k)} * {n}")
        text = " ".join(parts)
        return text[2:] if text.startswith("+ ") else text


class Coupled(Sym):
    """The seated phase pair coupled to a wait axis (`Axis.wait(detune=…)`): the kernel's second
    axis, named in a sequence as `Rz(spec, w.pair)`."""


@dataclass(frozen=True, eq=False)
class Axis(Sym):
    """One on-core sweep: `kind` in amp | freq | dur | wait | phase, the (x0, dx) pair the kernel
    accumulates, the exact int `codes` it realizes and their physical `values`. `coupled` (wait
    only) is a detuning — Hz, or a Param carrying a DAC-rate code — whose phase pair
    `16·dc·w` rides on the SECOND kernel axis (`Frequency`'s virtual-Z, k_ramsey's p0/dp)."""

    kind: str
    x0: int
    dx: int
    codes: np.ndarray
    values: np.ndarray
    coupled: object = None
    pair: object = None            # the Coupled symbol when `coupled` is set

    @property
    def n(self) -> int:
        return len(self.codes)

    @property
    def plain(self) -> bool:
        """Whether the accumulator is a plain batch count (enters time placement)."""
        return self.kind in ("dur", "wait")

    @property
    def range(self) -> tuple[int, int]:
        return int(self.codes.min()), int(self.codes.max())

    # ── constructors ──

    @staticmethod
    def amp(lo: float, hi: float, n: int) -> "Axis":
        from riscq.cal.base import sweep_q16
        x0, dx, codes = sweep_q16(units._amp_code(lo), units._amp_code(hi), n)
        return Axis("amp", x0, dx, codes, codes / units.AMP_SCALE)

    @staticmethod
    def freq(lo_hz: float, hi_hz: float, n: int, m, fold: bool = False) -> "Axis":
        """A carrier sweep from `lo_hz` (its plain code) over `hi_hz − lo_hz` taken UNFOLDED — the
        ramp stays monotone past Nyquist on the host and the kernel's int32 wrap folds it the way
        the converter does (`fold=True`, spec 09). Values are delta-based Hz from `lo_hz`."""
        c0 = units._freq_code(lo_hz, m.params)
        c1 = c0 + round((hi_hz - lo_hz) * (1 << 16) / units.sample_rate(m.params))
        return Axis.freq_codes(c0, c1, n, m, fold, ref=(lo_hz, c0))

    @staticmethod
    def freq_codes(c0: int, c1: int, n: int, m, fold: bool = False, ref=None) -> "Axis":
        """A carrier sweep between two PLAIN codes; `ref=(f_hz, code)` anchors the physical axis
        (`f + code_to_freq(codes − code)`, the readout cals' delta-based Hz — never the alias)."""
        from riscq.cal.base import sweep_q16
        x0, dx, codes = sweep_q16(int(c0), int(c1), n, fold=fold)
        f_ref, c_ref = ref if ref is not None else (units.code_to_freq(c0, m.params), c0)
        return Axis("freq", x0, dx, codes, f_ref + units.code_to_freq(codes - c_ref, m.params))

    @staticmethod
    def _plain(kind, lo_s, hi_s, n, m, coupled=None) -> "Axis":
        from riscq.cal.base import batches
        x0 = batches(lo_s, m)
        dx = 0 if n <= 1 else round((batches(hi_s, m) - x0) / (n - 1))
        codes = x0 + dx * np.arange(n, dtype=np.int64)
        return Axis(kind, x0, dx, codes, codes / m.params.dsp_freq_hz, coupled,
                    Coupled() if coupled is not None else None)

    @staticmethod
    def dur(lo_s: float, hi_s: float, n: int, m) -> "Axis":
        return Axis._plain("dur", lo_s, hi_s, n, m)

    @staticmethod
    def wait(lo_s: float, hi_s: float, n: int, m, detune=None) -> "Axis":
        return Axis._plain("wait", lo_s, hi_s, n, m, detune)

    @staticmethod
    def wait_batches(w0: int, dw: int, n: int, m, detune=None) -> "Axis":
        """The wait axis from an exact batch pair (the T1 default grid is derived in batches)."""
        codes = int(w0) + int(dw) * np.arange(n, dtype=np.int64)
        return Axis("wait", int(w0), int(dw), codes, codes / m.params.dsp_freq_hz, detune,
                    Coupled() if detune is not None else None)

    @staticmethod
    def phase(lo: float, hi: float, n: int) -> "Axis":
        """A monotone (unwrapped) phase axis in radians; the pair is seated (spec 12)."""
        c0 = round(lo / math.pi * (1 << 15))
        c1 = round(hi / math.pi * (1 << 15))
        dc = 0 if n <= 1 else round((c1 - c0) / (n - 1))
        codes = c0 + dc * np.arange(n, dtype=np.int64)
        return Axis("phase", pack16(c0), pack16(dc), codes, codes * math.pi / (1 << 15))

    def phase_pair(self, dc: int) -> tuple[int, int]:
        """The seated virtual-Z pair coupled to this wait axis for a DAC-rate detuning code `dc`
        (k_ramsey: phi at wait w is 16·dc·w)."""
        assert self.kind == "wait"
        return pack16(16 * dc * self.x0), pack16(16 * dc * self.dx)


@dataclass(frozen=True, eq=False)
class Param(Sym):
    """A runtime int32 the host rewrites per rerun; `values` are the words the C consumes, in
    run order — one tuple shared by every key, or `{key: tuple}` of EQUAL length when each qubit
    steps through its own list (Window's per-qubit timings). Every rerun of an experiment is one
    index of the Params' cartesian product."""

    name: str
    values: object

    @property
    def n(self) -> int:
        v = self.values
        return len(next(iter(v.values()))) if isinstance(v, dict) else len(v)

    def value(self, key, i: int) -> int:
        v = self.values
        return int((v[key] if isinstance(v, dict) else v)[i])

    def all_values(self) -> list:
        v = self.values
        return [x for t in v.values() for x in t] if isinstance(v, dict) else list(v)

    @property
    def range(self) -> tuple[int, int]:
        vals = self.all_values()
        return int(min(vals)), int(max(vals))


def seated_phase(rad: float) -> int:
    """A constant phase as the seated word the frame arithmetic adds (spec 12)."""
    return pack16(units._phase_code(rad))
