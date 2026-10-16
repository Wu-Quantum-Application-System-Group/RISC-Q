"""The host-pure calibration harness.

A calibration is `compile → run → decode → fit → propose → apply`. Only `run` needs hardware.
`Responder` replaces exactly that step: it patches the four `riscq.run` entry points a
calibration reaches, so the class itself — the real `compile_kernel`, the real per-slot retunes,
the real fits, the real `Config` write-back — executes against an analytic population model in
microseconds.

Why `riscq.run` and not `riscq.cal.base`: the five batched helpers in `base` are not the only
path to hardware. `qubit.py`, `readout.py`, `rpe.py` and `twoqubit.py` all call
`rq.setup`/`rq.rerun`/`rq.run`/`rq.write_slot` directly, so those four are the complete seam.
Patching there also keeps `compile_kernel` real, so C emission, compile-time folding, envelope
allocation, ParamTable slot codes and grid arithmetic are all still exercised — only the RISC-V
execution is replaced.

**The rule**: an answer function models the *intended physics*, from first principles.
It must never import from `riscq.cal` to decide what to return, and must never be tuned until a
test passes. If the answer and the class disagree, one of them is wrong.

Usage:

    def test_amplitude_recovers_the_rabi_rate(responder):
        r = responder(SIM2Q_JSON)

        @r.answer
        def _(progs, params):
            out = {}
            for q, prog in progs.items():
                xs = q16_axis(prog, params.get(q))         # the codes the kernel realizes
                p1 = (1 - np.cos(RABI * SIGMA_PER_CODE * xs)) / 2
                out[q] = {"out": counts(p1, prog.bindings["shots"])}
            return out

        result = Amplitude(cfg, 0).run(r.drv)
        assert result.proposal["qubit/0/rabi"] == pytest.approx(RABI, rel=0.01)
"""

from __future__ import annotations

import numpy as np

from riscq import run as rq


# ── `out` encoders: population → the array the kernel would have written ──

def counts(p1, shots: int) -> np.ndarray:
    """COUNTS mode: one classified-|1> count per point (`P = counts/shots`)."""
    return np.rint(np.asarray(p1, dtype=float) * int(shots)).astype(np.int64)


def counts_heralded(p1, shots: int, kept=None) -> np.ndarray:
    """Heralded COUNTS: interleaved (count, kept) pairs, `P = count/kept`. `kept`
    defaults to every shot passing the herald — the clean-|0> case."""
    p1 = np.asarray(p1, dtype=float)
    kept = np.full(p1.shape, int(shots)) if kept is None else np.asarray(kept, dtype=np.int64)
    return np.stack([np.rint(p1 * kept), kept], axis=1).reshape(-1).astype(np.int64)


def raw_iq(z) -> np.ndarray:
    """RAW mode: point-major flat (real, imag) pairs — the cursor layout the RAW kernels write
    (`out` sized 2·npts·shots)."""
    z = np.asarray(z, dtype=complex).reshape(-1)
    return np.stack([z.real, z.imag], axis=1).reshape(-1).astype(np.int64)


def iq_sum(z, shots: int, sh: int) -> np.ndarray:
    """IQSUM mode: one (Σreal, Σimag) pair per point — `shots` identical integrals, each shifted
    right by `sh` before it is accumulated, exactly as k_vna's `out[2i] += read_real() >> sh`
    (`out` sized 2·npts)."""
    z = np.asarray(z, dtype=complex).reshape(-1)
    pair = np.stack([np.rint(z.real), np.rint(z.imag)], axis=1).astype(np.int64) >> int(sh)
    return (pair * int(shots)).reshape(-1)


# ── point axes: the exact integers the on-core sweep realizes ──

def _descriptor(prog, params):
    """Where a computed sweep's descriptor actually lives.

    It is split, and which half depends on the calibration: `Amplitude` passes `a0q`/`daq` as
    RUNTIME params (one compile, the sweep rewritten per run), while `Frequency` bakes `w0`/`dw`
    as compile-time BINDINGS and varies only the virtual-Z pair per rerun. So look in both, with
    the runtime value winning — a param, when present, is what that particular rerun realized.
    """
    return {**prog.bindings, **(params or {})}


def q16_axis(prog, params=None, x0: str = "a0q", dx: str = "daq", n: str = "npts") -> np.ndarray:
    """The realized code axis of a Q16 computed sweep: the kernel accumulates
    `xq += dxq` and writes `xq` raw, so the code is `xq >> 16`. Reproduces exactly what
    `base.sweep_q16` handed the kernel — same integer arithmetic, so the responder's populations
    land on the same x-axis the class will fit against."""
    src = _descriptor(prog, params)
    npts = int(prog.bindings[n])
    return ((int(src[x0]) + np.arange(npts, dtype=np.int64) * int(src[dx])) >> 16).astype(np.int64)


def int_axis(prog, params=None, x0: str = "w0", dx: str = "dw", n: str = "npts") -> np.ndarray:
    """The realized axis of a plain int computed sweep (waits, phases): `x_i = x0 + i·dx`."""
    src = _descriptor(prog, params)
    return int(src[x0]) + np.arange(int(prog.bindings[n]), dtype=np.int64) * int(src[dx])


# ── the harness ──

class _Sim:
    def __init__(self, params_json: str):
        self._params_json = params_json

    def get_params(self) -> str:
        return self._params_json


class _Drv:
    """Stands in for a Driver. `socmap(drv)` reads `drv.sim.get_params()`; nothing else on the
    seam is reachable, because every path to it is patched."""

    def __init__(self, params_json: str):
        self.sim = _Sim(params_json)


class Responder:
    """Runs a calibration for real against an analytic population model.

    `answer(progs, params) -> {core: {name: ndarray}}` supplies what each core would have
    written. `progs` are the REAL compiled `Program`s — read the sweep off `prog.bindings`
    (`npts`, `shots`, the sweep descriptors) rather than closing over it — and `params` is the
    per-core runtime scalar dict of this particular rerun.
    """

    def __init__(self, monkeypatch, params_json: str):
        self.drv = _Drv(params_json)
        self.setups: list[dict] = []       # every `setup`: {core: Program}
        self.reruns: list[tuple] = []      # every `rerun`: (progs, params)
        self.slot_writes: list[tuple] = []  # every `write_slot`: (core, table, slot, field, value)
        self._answer = None
        for name, fn in (("setup", self._setup), ("rerun", self._rerun),
                         ("run", self._run), ("write_slot", self._write_slot)):
            monkeypatch.setattr(rq, name, fn)

    def answer(self, fn):
        """Register the analytic model. Usable as a decorator."""
        self._answer = fn
        return fn

    # ── the patched riscq.run surface ──

    def _setup(self, drv, m, progs):
        self.setups.append(dict(progs))

    def _rerun(self, drv, m, progs, params=None, arrays=None, results=None, timeout=0):
        params = dict(params or {})
        self.reruns.append((dict(progs), params))
        if self._answer is None:
            raise AssertionError("Responder has no answer function — call responder.answer(fn)")
        out = self._answer(progs, params)
        missing = set(progs) - set(out)
        if missing:
            raise AssertionError(f"answer returned no data for core(s) {sorted(missing)}; "
                                 f"a cal reads every core it programmed")
        return {core: {k: np.asarray(v) for k, v in d.items()} for core, d in out.items()}

    def _run(self, drv, m, progs, params=None, arrays=None, results=None, timeout=0):
        self._setup(drv, m, progs)
        return self._rerun(drv, m, progs, params, arrays, results, timeout)

    def _write_slot(self, drv, m, core, program, table, slot, field, value):
        self.slot_writes.append((core, table, slot, field, value))

    # ── convenience for assertions ──

    def slot(self, core: int, table: str, slot: int, field: str):
        """The most recent value written to one slot field, or None if it was never written."""
        hits = [v for (c, t, s, f, v) in self.slot_writes
                if (c, t, s, f) == (core, table, slot, field)]
        return hits[-1] if hits else None

    @property
    def sources(self) -> list[str]:
        """The generated C of every program that was ever set up (the kernel-shape gates
        read the emitted source: `assert 'set_phase_offset' in src`)."""
        return [p.c_source for setup in self.setups for p in setup.values()]
