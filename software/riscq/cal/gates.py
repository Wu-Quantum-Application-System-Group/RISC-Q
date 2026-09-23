"""Gates (specs/universal-cal/01 §2): a gate spec → the calibrated pulse on its line.

A spec is a Config path: relative ones are the qubit's own (`'x90'`, `'x'`, `'EF/x90'`, `'EF/x'`
→ `qubit/{q}/…` at the GE / EF carrier, on the qubit's gate channel); `qubit/<j>/<k>` names
ANOTHER qubit's gate (a pair sequence plays both members); `transition/<t>/<k>` and
`mode/<m>/<k>` are absolute, take their carrier and `line` from the parent entry. Every entry has
the one schema `{env, dur, amp, phase, kwargs, vz?}` (base.gate_pulse's). A literal `Pulse` with
a `line` — a line name, or a core index (that core's gate channel: the CZ tones) — is a gate too
(a spectroscopy probe). Tables group a core's gates per channel."""

from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

from riscq.cal.base import GATE_ENV, _channel_env, batches, gate_ch, line as line_of
from riscq.lang import ParamTable
from riscq.map import ChannelInfo
from riscq.pulses import FlatTop, Pulse, flat_top, golden, units


@dataclass(frozen=True)
class ResolvedGate:
    key: str                 # the slot key in its channel's table (the spec path, or a literal id)
    line: ChannelInfo
    pulse: object            # Pulse | FlatTop, BASEBAND (freq_hz=None)
    carrier_hz: float
    vz: tuple                # the virtual-Z bracket (before, after), rad; (0, 0) when absent
    dur: int                 # batches

    @property
    def flat(self) -> bool:
        return isinstance(self.pulse, FlatTop)

    def slot_keys(self) -> list:
        return [f"{self.key}/{p}" for p in ("up", "body", "down")] if self.flat else [self.key]


def _baseband(p: Pulse) -> Pulse:
    return Pulse(p.env, p.amp, None, p.phase)


def resolve(cfg, q, spec, m, line=None) -> ResolvedGate:
    """`spec`: a Config path (see the module docstring) or a `Pulse` (needs `line` — a line name —
    and a `freq_hz` carrier)."""
    if isinstance(spec, (Pulse, FlatTop)):
        first = spec.up if isinstance(spec, FlatTop) else spec
        if first.freq_hz is None:
            raise ValueError("a literal gate Pulse / FlatTop needs freq_hz (its carrier)")
        ch = _line(cfg, q, "qubit" if line is None else line, m)     # line 0 is a core
        key = f"lit/{ch.core}.{ch.name}/{abs(hash((first.env.tobytes(), first.amp, first.phase)))}"
        if isinstance(spec, FlatTop):
            ft = FlatTop(_baseband(spec.up), _baseband(spec.body), _baseband(spec.down), spec.reps)
            return ResolvedGate(key, ch, ft, float(first.freq_hz), (0.0, 0.0), ft.dur_batches(ch))
        return ResolvedGate(key, ch, _baseband(spec), float(first.freq_hz), (0.0, 0.0),
                            spec.dur_batches(m, ch.index, ch.core))
    if not isinstance(spec, str):
        raise TypeError(f"gate spec must be a Config path or a Pulse, got {spec!r}")
    if spec.startswith(("transition/", "mode/")):
        path = spec
        parent = spec.rsplit("/", 1)[0]
        carrier = float(cfg[f"{parent}/freq"])
        ch = _line(cfg, q, cfg.get(f"{parent}/line", "qubit"), m)
    else:
        if spec.startswith("qubit/"):                 # another qubit's gate, spelled absolutely
            _, qj, rel = spec.split("/", 2)
            q = int(qj)
        else:
            rel = spec
        path = f"qubit/{q}/{rel}"
        sub = f"qubit/{q}/EF" if rel.startswith("EF/") else f"qubit/{q}"
        carrier = float(cfg[f"{sub}/freq"])
        ch = gate_ch(m, q) if line is None else _line(cfg, q, line, m)
    entry = cfg[path]
    if not isinstance(entry, dict) or entry.get("env") == "flat_top":
        return _flat_gate(cfg, spec, path, entry, ch, carrier, m)
    env = GATE_ENV if f"{path}/env" not in cfg \
        else _channel_env(cfg, path, batches(cfg[f"{path}/dur"], m), ch, m)
    pulse = Pulse(env, amp=float(cfg[f"{path}/amp"]), phase=float(cfg.get(f"{path}/phase", 0.0)))
    v0, v1 = cfg.get(f"{path}/vz", [0.0, 0.0])
    return ResolvedGate(spec, ch, pulse, carrier, (float(v0), float(v1)),
                        pulse.dur_batches(m, ch.index, ch.core))


def _line(cfg, q, line, m):
    """A line by name (base.line) or by core index (that core's gate channel)."""
    return gate_ch(m, int(line)) if isinstance(line, int) else line_of(cfg, q, line, m)


MIN_HOLD = 2             # the timed queue pops every other batch: a slot followed by another holds ≥ 2
DEFAULT_RAMP = 5e-9      # the multimode reference's flat-top ramp sigma (spec 24 §4.5)


def _flat_gate(cfg, spec, path, entry, ch, carrier, m) -> ResolvedGate:
    """A long flat-top gate (spec 24 §4.3 / §4.5): either an entry `{env: flat_top, dur, amp,
    kwargs: {ramp}}` or — the swap-table form — a bare LENGTH in seconds at `mode/<m>/pi|hpi`
    with the amplitude and ramp on the parent (`mode/<m>/{amp, ramp}`)."""
    parent = path.rsplit("/", 1)[0]
    if isinstance(entry, dict):
        dur, amp = float(entry["dur"]), float(entry["amp"])
        ramp = float(entry.get("kwargs", {}).get("ramp", DEFAULT_RAMP))
        phase = float(entry.get("phase", 0.0))
    else:
        dur, amp = float(entry), float(cfg[f"{parent}/amp"])
        ramp, phase = float(cfg.get(f"{parent}/ramp", DEFAULT_RAMP)), 0.0
    ramp_b = max(MIN_HOLD, batches(ramp, m))                 # every slot but the last is followed
    flat_b = max(MIN_HOLD, batches(dur, m) - 2 * ramp_b)      # by another (SOC_TIPS §9.4)
    ft = flat_top(ch, ramp_b, flat_b, amp, phase=phase)
    return ResolvedGate(spec, ch, ft, carrier, (0.0, 0.0), ft.dur_batches(ch))


def drive_sigma(m, g: ResolvedGate, amp_code: int) -> float:
    """Σ over the gate of the per-batch drive estimate on the bit-exact DAC golden (base.gate_sigma
    on the gate's own line): the fit x-axis of a Rabi sweep, linear in `amp_code`."""
    lines = g.pulse.packed_lines(m, g.line.index, g.line.core)
    w = golden.pulse_window(lines, int(amp_code), units._freq_code(g.carrier_hz, m.params),
                            0, 0, len(lines))
    return float(sum(math.sqrt(2 * np.mean(row.astype(float) ** 2)) for row in w))


class Tables:
    """A core set's ParamTables, one per (core, channel), built from the resolved gates in first-use
    order; `slot(g, part)` → (table symbol, slot index). Table symbols are `t_<channel name>`."""

    def __init__(self):
        self._pulses: dict = {}      # (core, channel index) -> {slot key: Pulse}
        self._info: dict = {}        # (core, channel index) -> ChannelInfo
        self._carrier: dict = {}     # (core, channel index) -> first carrier (the init one)

    def add(self, g: ResolvedGate) -> None:
        k = (g.line.core, g.line.index)
        d = self._pulses.setdefault(k, {})
        self._info.setdefault(k, g.line)
        self._carrier.setdefault(k, g.carrier_hz)
        parts = (("up", g.pulse.up), ("body", g.pulse.body), ("down", g.pulse.down)) \
            if g.flat else ((None, g.pulse),)
        for part, p in parts:
            key = g.key if part is None else f"{g.key}/{part}"
            if key not in d:
                d[key] = _baseband(p)
        if len(d) > g.line.slot_count:
            raise ValueError(f"channel {g.line.name} of core {g.line.core} needs {len(d)} slots, "
                             f"has {g.line.slot_count}")

    def add_table(self, ch: ChannelInfo, name: str, pulse: Pulse, carrier_hz: float) -> None:
        """A non-gate slot (the readout drive / demod window) on `ch`."""
        k = (ch.core, ch.index)
        self._pulses.setdefault(k, {})[name] = pulse
        self._info.setdefault(k, ch)
        self._carrier.setdefault(k, carrier_hz)

    @staticmethod
    def symbol(ch: ChannelInfo) -> str:
        return f"tbl_{ch.name}"

    def slot(self, ch: ChannelInfo, key: str) -> tuple[str, int]:
        return self.symbol(ch), list(self._pulses[(ch.core, ch.index)]).index(key)

    def carrier(self, ch: ChannelInfo) -> float:
        return self._carrier[(ch.core, ch.index)]

    def channels(self, core: int) -> list:
        return [info for (c, _), info in self._info.items() if c == core]

    def for_core(self, core: int) -> dict:
        """`tables=` for compile_kernel: symbol -> ParamTable, this core only."""
        out = {}
        for (c, idx), pulses in self._pulses.items():
            if c == core:
                info = self._info[(c, idx)]
                out[self.symbol(info)] = ParamTable(info, self._carrier[(c, idx)], pulses)
        return out
