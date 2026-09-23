"""The sequence compiler (specs/universal-cal/01 §3): elements → timing table → per-core C.

A sequence is a list of elements played once per shot, END-anchored at `t_ro − SEP` (R1) and
sequential across lines (R2); `Par` places children simultaneously (end-aligned by default). The
compiler walks it forward with a symbolic cursor (`axes.Lin`: an affine expression over the
on-core axes, the runtime params and a loop index), applying today's kernel rules once:

  R4 pacing — a channel never has more than TRAIN_AHEAD plays pushed ahead of the playing one:
     straight-line play k (k ≥ TRAIN_AHEAD) waits for play k−TRAIN_AHEAD's start; a runtime loop
     (Train / Repeat with a Param count) waits `TRAIN_AHEAD` play-spacings back — exact when the
     loop's plays are uniformly spaced and any plays before it keep that spacing (the RPE block
     + tail); a play after such a loop waits for the loop's play TRAIN_AHEAD back from its end.
     A push must still land a full LEAD before its play (a later one plays the PREVIOUS params,
     SOC_TIPS §5), so a straight-line play whose wait would leave less than LEAD (+ the wait's
     return) is pushed later by an idle gap, and a runtime loop's iteration is padded to the
     train grid (TRAIN_STEP per play), as a Train.
  R5 carriers — a channel with ONE carrier is tuned in seq_init; a channel with several starts
     every shot untuned, so its first play retunes (set_start + set_freq, as k_ef_* / the CZ
     drive form) and any later carrier change does too, a full LEAD after the previous play on
     that channel (the phasor-regen gap). A `freq=` sweep on a single-carrier channel is a
     per-point write (seq_point); on a multi-carrier one it is the retune word.
  R6 frames — one accumulator per (channel, carrier), rebuilt from 0 each shot; every play is
     `set_phase_offset(f + vz0 + extra); fire; f += vz0 + vz1`; `Rz` is `f += phi`. `vz=<axis>`
     replaces a gate's stored pair (vz0 = vz1 = axis, the Phase sweep).
  R7 flat-tops — up / body × reps / down, contiguous; `dur=` sweeps the body slot; the bracket
     opens on the first slot and advances after the last.
  R8 readout — `seq_meas(t)` on a reading core is the ro drive at t + the demod at t + ddly.

The generated header defines `seq_init / seq_point / seq_shot / seq_meas / meas_tail` for ONE
core (R10: only that core's lines play; the timing is the whole sequence's). `SEP`, `LEAD`,
`TRAIN_AHEAD`, `train_step` are `riscq.cal.base`'s."""

from __future__ import annotations

import copy
from dataclasses import dataclass, field

from riscq.cal.axes import Lin, Param, Sym, seated_phase
from riscq.cal.base import SEP, TRAIN_AHEAD, TRAIN_STEP, train_step
from riscq.cal.gates import ResolvedGate, Tables, resolve
from riscq.map import LEAD, READOUT_LEAD, ChannelInfo
from riscq.pulses import Pulse, units


# ── elements ──

@dataclass(frozen=True)
class Gate:
    """A calibrated gate (`spec`: a Config path or a literal Pulse on `line`). `phase` REPLACES the
    pulse's own axis phase (rad, or an axis/param expression in seated words); `amp`/`dur`/`freq`
    bind that field to an Axis/Param; `vz` replaces the stored virtual-Z pair by an axis."""

    spec: object
    line: str | None = None
    phase: object = None
    amp: object = None
    freq: object = None
    dur: object = None
    vz: object = None


@dataclass(frozen=True)
class Rz:
    """A frame advance on the frame of gate `spec` (its channel + carrier): `phi` rad, or an
    axis/param expression in seated words."""

    spec: object
    phi: object


@dataclass(frozen=True)
class Idle:
    """`t` batches (an int, or a wait axis / param expression) on every line."""

    t: object


@dataclass(frozen=True)
class Train:
    """`n` plays of `gate` on a `train_step(dur)` grid (paced); `phases` (rad) repeat per play."""

    gate: Gate
    n: object
    phases: tuple = ()


@dataclass(frozen=True)
class Repeat:
    n: object
    body: tuple


@dataclass(frozen=True)
class Cond:
    param: Param
    body: tuple


@dataclass(frozen=True)
class Par:
    children: tuple
    align: str = "end"


@dataclass(frozen=True)
class ActiveReset:
    """A mid-sequence readout whose result gates a π (spec 24 §3.14): read out, and if the qubit is
    found in |1> play `gate`. `ef=True` first plays an EF π so |2> is reset too (QICK's `ef_reset`).
    The conditional π is posted after the read returns, so it sits LEAD (+ a margin) after the
    readout window closes; the sequence then continues (the herald read is the kernel's own)."""

    gate: str = "x"
    ef: bool = False


POST_MARGIN = 16       # batches past LEAD for a wait / read to return + the stores (SOC_TIPS §5)


@dataclass(frozen=True)
class Meas:
    """The readout knobs a sweep may bind: `freq`/`amp` of the drive (per-point writes; `freq`
    retunes the demod as the matched 4× pair), `dur` of the demod window and `delay` (params)."""

    freq: object = None
    amp: object = None
    dur: object = None
    delay: object = None
    drive_dur: object = None       # the readout DRIVE length (its slot's dur), seated


@dataclass
class MeasInfo:
    """What `seq_meas` needs on a reading core (built by riscq.cal.measure)."""

    ro: ChannelInfo
    demod: ChannelInfo
    demod_code: int         # seated demod-LO word
    win: int                # the demod integration window (batches)
    ddly: int
    meas: Meas = field(default_factory=Meas)


# ── the timing table ──

@dataclass
class Row:
    core: int
    channel: str
    key: str
    t: Lin                  # start, relative to t_ro (negative)
    dur: object             # int or Lin
    carrier: object         # Hz, or the swept expression
    retune: bool
    ctx: str                # "" | "for k<r0" | "if r1" …


class _Loop(Sym):
    """A loop index symbol (`k`)."""


@dataclass
class _Chan:
    """Per-channel walk state."""

    info: ChannelInfo
    carriers: set = field(default_factory=set)
    multi: bool = False
    cur: object = None
    history: list = field(default_factory=list)    # ("play", t) | ("loop", t_last, spacing)
    frames: dict = field(default_factory=dict)     # carrier key -> frame var name
    pending: bool = False                          # a runtime loop's entry retune, still to emit

    def copy(self) -> "_Chan":
        return _Chan(self.info, set(self.carriers), self.multi, self.cur, list(self.history),
                     dict(self.frames), self.pending)


def _ckey(carrier) -> object:
    return carrier if isinstance(carrier, float) else "var"


class Compiled:
    def __init__(self, m):
        self.m = m
        self.rows: list[Row] = []
        self.ops: list = []            # per shot, in emission order (all cores)
        self.point_ops: list = []      # per point: (field, ChannelInfo, slot, Lin)
        self.frames: list = []         # (core, name)
        self.length: Lin = Lin(0)
        self.tables: Tables = Tables()
        self.ranges: dict = {}
        self.symbols: dict = {}        # Sym -> C name

    @property
    def seq_len(self) -> int:
        """The longest point's prelude (earliest play start → t_ro − SEP), for grid_period."""
        return self.length.bound(self.ranges, hi=True)

    def rows_for(self, core: int) -> list:
        return [r for r in self.rows if r.core == core]

    def cores(self) -> list:
        return sorted({c for (c, _) in self.tables._info})


class _Compiler:
    def __init__(self, cfg, q, m, axes: tuple, params: tuple, meas: MeasInfo | None = None):
        self.cfg, self.q, self.m = cfg, q, m
        self.meas = meas
        self.axes, self.params = tuple(axes), tuple(params)
        self.out = Compiled(m)
        self.chans: dict = {}          # (core, index) -> _Chan
        self.gates: dict = {}          # spec key -> ResolvedGate
        self.ctx: list = []            # nested ("for", k, n) / ("if", p)
        self.loop_spacing: dict = {}   # (core, channel name) -> play spacing inside the open loop
        for i, a in enumerate(self.axes):
            self.out.symbols[a] = "ab"[i]
            if a.pair is not None:
                self.out.symbols[a.pair] = "b"
        for i, p in enumerate(self.params):
            self.out.symbols[p] = f"r{i}"
        self.out.ranges = ({a: a.range for a in self.axes if a.plain}
                           | {p: p.range for p in self.params})

    # ── resolution ──

    def gate(self, g: Gate) -> ResolvedGate:
        """The resolved gate; with `phase=` the slot is built at phase 0 (the frame offset carries
        the whole axis — the swept phi REPLACES the stored one, qcal's Phase(gate='X'))."""
        zero = g.phase is not None
        k = (g.spec if isinstance(g.spec, str) else id(g.spec), g.line, zero)
        if k not in self.gates:
            rg = resolve(self.cfg, self.q, g.spec, self.m, g.line)
            if zero and not rg.flat and rg.pulse.phase != 0.0:   # a phase-0 slot IS the stored one
                rg = ResolvedGate(rg.key + "/p0", rg.line, Pulse(rg.pulse.env, rg.pulse.amp),
                                  rg.carrier_hz, rg.vz, rg.dur)
            self.gates[k] = rg
        return self.gates[k]

    def chan(self, ch: ChannelInfo) -> _Chan:
        return self.chans.setdefault((ch.core, ch.index), _Chan(ch))

    @staticmethod
    def _carrier(g: Gate, rg: ResolvedGate):
        return rg.carrier_hz if g.freq is None else Lin.of(g.freq)

    def _scan(self, elems) -> None:
        """Pre-pass: every gate's channel + carrier set (decides R5's multi-carrier channels)."""
        for e in elems:
            if isinstance(e, Gate):
                rg = self.gate(e)
                self.out.tables.add(rg)
                self.chan(rg.line).carriers.add(_ckey(self._carrier(e, rg)))
            elif isinstance(e, Train):
                self._scan([e.gate])
            elif isinstance(e, Rz):
                self._scan([Gate(e.spec)])
            elif isinstance(e, (Repeat, Cond)):
                self._scan(e.body)
            elif isinstance(e, Par):
                for c in e.children:
                    self._scan(_aslist(c))
            elif isinstance(e, ActiveReset):
                self._scan([Gate(e.gate)] + ([Gate("EF/x")] if e.ef else []))

    # ── helpers ──

    @staticmethod
    def _phase(x) -> Lin:
        """A phase: rad (float) → seated const; an Axis/Param/Lin → itself (already seated)."""
        return Lin(seated_phase(x)) if isinstance(x, float) else Lin.of(x)

    def _frame(self, ch: _Chan, carrier) -> str:
        key = _ckey(carrier)
        if key not in ch.frames:
            ch.frames[key] = f"f{len(self.out.frames)}"
            self.out.frames.append((ch.info.core, ch.frames[key]))
        return ch.frames[key]

    def _ctx(self) -> str:
        return " ".join(f"for k<{self.out.symbols[c[2]]}" if c[0] == "for"
                        else f"if {self.out.symbols.get(c[1], c[1])}" for c in self.ctx)

    def _in_loop(self) -> bool:
        return any(c[0] == "for" for c in self.ctx)

    def _pace(self, ch: _Chan, t: Lin, spacing: int | None) -> None:
        """R4: the wait before pushing a play at `t` on `ch`."""
        target = self._wait_target(ch, t, spacing)
        if target is not None:
            self.out.ops.append(("wait", target, ch.info.core))   # this core's push waits

    def _wait_target(self, ch: _Chan, t: Lin, spacing: int | None):
        """R4: when the push of a play at `t` on `ch` happens (None: no wait, pushed at once)."""
        if self._in_loop():
            if spacing is None:
                spacing = self.loop_spacing[(ch.info.core, ch.info.name)]
            return t - TRAIN_AHEAD * spacing
        k = len(ch.history)
        if k < TRAIN_AHEAD:
            return None
        window = ch.history[k - TRAIN_AHEAD:]
        loops = [i for i, h in enumerate(window) if h[0] == "loop"]
        if loops:
            _, t_last, sp = window[loops[-1]]
            since = len(window) - 1 - loops[-1]           # plain plays since that loop
            return t_last - (TRAIN_AHEAD - 1 - since) * sp
        return window[0][1]

    def _feasible(self, ch: _Chan, t: Lin) -> Lin:
        """R4's LEAD margin: the earliest start ≥ `t` whose push (the wait target) lands a full
        LEAD ahead of it — an idle gap before a play that would otherwise be pushed too late."""
        if self._in_loop():
            return t                                      # the loop's spacing is padded instead
        target = self._wait_target(ch, t, None)
        if target is None:
            return t
        short = target + LEAD + POST_MARGIN - t
        try:
            gap = short.bound(self.out.ranges)             # the worst case over the sweep
        except KeyError:                                  # a symbol without a range: leave it
            return t
        return t + gap if gap > 0 else t

    def _needs_gap(self, g: Gate, rg: ResolvedGate) -> bool:
        ch = self.chan(rg.line)
        return ch.multi and _ckey(self._carrier(g, rg)) != ch.cur and bool(ch.history)

    def _play(self, g: Gate, rg: ResolvedGate, part: str | None, t: Lin, dur, extra: Lin | None,
              spacing: int | None) -> None:
        ch = self.chan(rg.line)
        carrier = self._carrier(g, rg)
        key = _ckey(carrier)
        retune = ch.multi and key != ch.cur
        ch.cur = key
        frame = self._frame(ch, carrier)
        if ch.pending and self._in_loop():           # R5 in a runtime loop: ONE retune, before it
            ch.pending = False
            k = self.ctx[-1][1]
            t0 = Lin(t.const, tuple((s, c) for s, c in t.terms if s is not k))
            code = carrier if isinstance(carrier, Lin) else \
                Lin(units.freq_to_code(carrier, self.m.params))
            self.out.ops.insert(self.loop_at, ("retune", rg.line, t0, code))
            self.out.rows.append(Row(rg.line.core, rg.line.name, "retune", t0, 0, carrier, True,
                                     self._ctx()))
        if g.vz is not None:
            vz0, vzsum = Lin.of(g.vz), Lin.of(g.vz) * 2
        else:
            vz0, vzsum = Lin(seated_phase(rg.vz[0])), Lin(seated_phase(rg.vz[0] + rg.vz[1]))
        if part in ("up", "body"):
            vzsum = Lin(0)                                # the bracket closes after the last slot
        self._pace(ch, t, spacing)
        code = None
        if retune:
            code = carrier if isinstance(carrier, Lin) else \
                Lin(units.freq_to_code(carrier, self.m.params))
        slot_key = rg.key if part is None else f"{rg.key}/{part}"
        _, slot = self.out.tables.slot(rg.line, slot_key)
        self.out.ops.append(("play", rg.line, slot, t, code, frame, vz0, extra, vzsum))
        self.out.rows.append(Row(rg.line.core, rg.line.name, slot_key, t, dur, carrier, retune,
                                 self._ctx()))
        if self._in_loop():
            last = ch.history[-1] if ch.history else None
            if last is None or last[0] != "loop" or last[1] is not None:   # a closed loop's entry
                ch.history.append(("loop", None, spacing))   # fixed up when the loop closes
        else:
            ch.history.append(("play", t))

    def _flat_parts(self, g: Gate, rg: ResolvedGate) -> tuple:
        spl = rg.line.samples_per_line
        up = -(-len(rg.pulse.up.env) // spl)
        down = -(-len(rg.pulse.down.env) // spl)
        body = Lin.of(g.dur) if g.dur is not None else Lin(-(-len(rg.pulse.body.env) // spl))
        return up, body, down

    def _gate_len(self, g: Gate, rg: ResolvedGate) -> Lin:
        if rg.flat:
            up, body, down = self._flat_parts(g, rg)
            return up + body * rg.pulse.reps + down
        return Lin.of(g.dur) if g.dur is not None else Lin(rg.dur)

    def _emit_gate(self, g: Gate, rg: ResolvedGate, cursor: Lin, extra: Lin | None,
                   spacing: int | None = None) -> Lin:
        """Place one gate starting at `cursor` (plus R5's gap when it retunes); returns its end."""
        if self._needs_gap(g, rg):
            cursor = cursor + LEAD
        ch = self.chan(rg.line)
        if rg.flat:
            up, body, down = self._flat_parts(g, rg)
            t = self._feasible(ch, cursor)
            self._play(g, rg, "up", t, up, extra, spacing)
            t = t + up
            for _ in range(rg.pulse.reps):
                t = self._feasible(ch, t)
                self._play(g, rg, "body", t, body, extra, spacing)
                t = t + body
            t = self._feasible(ch, t)
            self._play(g, rg, "down", t, down, extra, spacing)
            return t + down
        d = self._gate_len(g, rg)
        cursor = self._feasible(ch, cursor)
        self._play(g, rg, None, cursor, d, extra, spacing)
        return cursor + d

    def _dry(self, elems) -> tuple:
        """A block's length and per-channel play counts, by a walk on copied state."""
        saved = (self.out, self.chans, self.ctx)
        self.out = copy.copy(saved[0])
        self.out.ops, self.out.rows, self.out.frames = [], [], list(saved[0].frames)
        self.chans = {k: c.copy() for k, c in saved[1].items()}
        self.ctx = list(saved[2])
        try:
            end = self._walk(elems, Lin(0))
            counts = {}
            for r in self.out.rows:
                counts[(r.core, r.channel)] = counts.get((r.core, r.channel), 0) + 1
        finally:
            self.out, self.chans, self.ctx = saved
        return end, counts

    # ── the walk ──

    def _walk(self, elems, cursor: Lin) -> Lin:
        for e in elems:
            cursor = self._element(e, cursor)
        return cursor

    def _element(self, e, cursor: Lin) -> Lin:
        if isinstance(e, Gate):
            rg = self.gate(e)
            extra = None if e.phase is None else self._phase(e.phase)
            return self._emit_gate(e, rg, cursor, extra)
        if isinstance(e, Rz):
            rg = self.gate(Gate(e.spec))
            self.out.ops.append(("rz", self._frame(self.chan(rg.line), rg.carrier_hz),
                                 self._phase(e.phi)))
            return cursor
        if isinstance(e, Idle):
            return cursor + Lin.of(e.t)
        if isinstance(e, Train):
            return self._train(e, cursor)
        if isinstance(e, Repeat):
            if isinstance(e.n, Param):
                return self._loop(e.n, list(e.body), cursor)
            for _ in range(int(e.n)):
                cursor = self._walk(e.body, cursor)
            return cursor
        if isinstance(e, Cond):
            self.out.ops.append(("if", e.param))
            self.ctx.append(("if", e.param))
            end = self._walk(e.body, cursor)
            self.ctx.pop()
            self.out.ops.append(("endif",))
            return end
        if isinstance(e, Par):
            return self._par(e, cursor)
        if isinstance(e, ActiveReset):
            return self._reset(e, cursor)
        raise TypeError(f"not a sequence element: {e!r}")

    def _reset(self, e: ActiveReset, cursor: Lin) -> Lin:
        if self.meas is None:
            raise ValueError("ActiveReset needs the reading core's readout (compile with meas=)")
        if e.ef:
            cursor = self._emit_gate(Gate("EF/x"), self.gate(Gate("EF/x")), cursor, None)
        cursor = cursor + SEP                            # the π-to-readout gap, as every readout
        self.out.ops.append(("reset_meas", cursor))
        self.out.rows.append(Row(self.meas.ro.core, self.meas.ro.name, "reset/meas", cursor,
                                 self.meas.win, None, False, self._ctx()))
        cursor = cursor + self.meas.ddly + READOUT_LEAD + LEAD + POST_MARGIN
        self.out.ops.append(("if_res",))
        self.ctx.append(("if", "res"))
        end = self._emit_gate(Gate(e.gate), self.gate(Gate(e.gate)), cursor, None)
        self.ctx.pop()
        self.out.ops.append(("endif",))
        return end

    def _open_loop(self, n: Param) -> _Loop:
        if self._in_loop():
            raise ValueError("nested runtime loops are not supported")
        k = _Loop()
        self.out.symbols[k] = "k"
        self.loop_at = len(self.out.ops)
        self.out.ops.append(("for", k, n))
        self.ctx.append(("for", k, n))
        return k

    def _enter_loop(self, elems, cursor: Lin) -> Lin:
        """R5 at a runtime loop: a channel whose first play in the body retunes gets ONE gap
        before the loop and one retune (emitted ahead of the loop, `_play`), not one per
        iteration — the CZ train retunes to f_CZ once (k_cz_cond)."""
        gap = False
        for g in _flat(elems):
            if not isinstance(g, Gate):
                continue
            rg = self.gate(g)
            ch = self.chan(rg.line)
            if ch.pending or ch.cur == _ckey(self._carrier(g, rg)):
                continue                                  # this channel's entry is settled
            if self._needs_gap(g, rg):
                gap = True
            if ch.multi:
                ch.cur, ch.pending = _ckey(self._carrier(g, rg)), True
        return cursor + LEAD if gap else cursor

    def _close_loop(self, n: Param, t0: Lin, per_iter: int, counts: dict) -> None:
        """Fix up every channel's loop history entry: its last play's start and play spacing."""
        self.ctx.pop()
        self.out.ops.append(("endfor",))
        for key, c in self.chans.items():
            if c.history and c.history[-1][0] == "loop" and c.history[-1][1] is None:
                plays = counts.get((c.info.core, c.info.name), 1)
                spacing = per_iter // plays
                c.history[-1] = ("loop", t0 + Lin.of(n) * per_iter - spacing, spacing)

    def _train(self, e: Train, cursor: Lin) -> Lin:
        rg = self.gate(e.gate)
        if rg.flat:
            raise ValueError("Train of a flat-top is not supported — use Repeat")
        d = self._gate_len(e.gate, rg)
        if not d.is_const:
            raise ValueError("a Train's gate length must be constant (sweep amp/freq/phase, not dur)")
        step = train_step(d.const)
        phases = [self._phase(p) for p in e.phases] or [None]
        if isinstance(e.n, Param):
            if len(phases) > 1:
                raise ValueError("a phase pattern on a runtime-length Train is not supported")
            cursor = self._enter_loop([e.gate], cursor)
            k = self._open_loop(e.n)
            self._emit_gate(e.gate, rg, cursor + k * step, phases[0], spacing=step)
            self._close_loop(e.n, cursor, step, {(rg.line.core, rg.line.name): 1})
            return cursor + Lin.of(e.n) * step - step + d.const
        t = cursor
        for i in range(int(e.n)):
            t = self._emit_gate(e.gate, rg, t, phases[i % len(phases)], spacing=step)
            if i < int(e.n) - 1:
                t = t + (step - d.const)
        return t

    def _loop(self, n: Param, body: list, cursor: Lin) -> Lin:
        cursor = self._enter_loop(body, cursor)
        L, counts = self._dry(body)
        if not L.is_const:
            raise ValueError("a runtime Repeat body must have constant length")
        per = max(L.const, TRAIN_STEP * max(counts.values(), default=1))  # R4: the train grid
        self.loop_spacing = {key: per // c for key, c in counts.items()}
        k = self._open_loop(n)
        self._walk(body, cursor + k * per)
        self._close_loop(n, cursor, per, counts)
        return cursor + Lin.of(n) * per

    def _par(self, e: Par, cursor: Lin) -> Lin:
        kids = [_aslist(c) for c in e.children]
        lens = [self._dry(c)[0] for c in kids]           # a retuning child's length has its R5 gap
        diffs = [L - lens[0] for L in lens]
        if any(not d.is_const for d in diffs):
            raise ValueError("Par children must have constant lengths (or the same swept one)")
        par_len = lens[0] + max(d.const for d in diffs)
        used = set()
        for c, L in zip(kids, lens):
            lines = {(self.gate(x).line.core, self.gate(x).line.name)
                     for x in _flat(c) if isinstance(x, Gate)}
            if lines & used:
                raise ValueError(f"Par children share a line: {sorted(lines & used)}")
            used |= lines
            self._walk(c, cursor + (par_len - L) if e.align == "end" else cursor)
        return cursor + par_len

    # ── entry ──

    def compile(self, elems: list) -> Compiled:
        self._scan(elems)
        for c in self.chans.values():
            c.multi = len(c.carriers) > 1
            c.cur = None if c.multi else next(iter(c.carriers), None)
        for e in _flat(elems):                       # per-point writes
            if isinstance(e, Gate):
                rg = self.gate(e)
                for fld in ("amp", "dur"):
                    v = getattr(e, fld)
                    if v is not None:
                        key = f"{rg.key}/body" if rg.flat else rg.key
                        _, slot = self.out.tables.slot(rg.line, key)
                        self.out.point_ops.append((fld, rg.line, slot, Lin.of(v)))
                if e.freq is not None and not self.chan(rg.line).multi:
                    self.out.point_ops.append(("freq", rg.line, None, Lin.of(e.freq)))
        end = self._walk(elems, Lin(0))
        self.out.length = end
        shift = -(end + SEP)                         # offset o → t_ro − SEP − length + o
        self.out.ops = [_shift(op, shift) for op in self.out.ops]
        for r in self.out.rows:
            r.t = r.t + shift
        return self.out


def _aslist(c) -> list:
    return list(c) if isinstance(c, (list, tuple)) else [c]


def _flat(elems):
    for e in elems:
        if isinstance(e, Train):
            yield e.gate
        elif isinstance(e, (Repeat, Cond)):
            yield from _flat(e.body)
        elif isinstance(e, Par):
            for c in e.children:
                yield from _flat(_aslist(c))
        else:
            yield e


def _shift(op, s: Lin):
    if op[0] == "play":
        return op[:3] + (op[3] + s,) + op[4:]
    if op[0] in ("wait", "reset_meas"):
        return (op[0], op[1] + s) + op[2:]
    if op[0] == "retune":
        return op[:2] + (op[2] + s,) + op[3:]
    return op


def compile_sequence(cfg, q, elems, m, axes=(), params=(), meas: MeasInfo | None = None) -> Compiled:
    return _Compiler(cfg, q, m, axes, params, meas).compile(list(elems))


# ── C emission ──

def _hex(v: int) -> str:
    v = int(v)
    return f"{'-' if v < 0 else ''}0x{abs(v):X}" if abs(v) > 4096 else str(v)


def _c(x, names) -> str:
    x = Lin.of(x)
    return _hex(x.const) if x.is_const else x.c(names)


def _t(t: Lin, names) -> str:
    """A time relative to t_ro as C."""
    if t.is_const:
        return f"t_ro - {-t.const}" if t.const < 0 else f"t_ro + {t.const}"
    return f"t_ro + ({t.c(names)})"


def emit_header(comp: Compiled, core: int, meas: MeasInfo | None, label: str = "") -> str:
    """The header for `core`: seq_init / seq_point / seq_shot / seq_meas / meas_tail."""
    names = comp.symbols
    tables = comp.tables.for_core(core)
    w = [f"/* riscq.cal.sequence — {label} core {core} — GENERATED, do not edit */"]
    for sym in tables:
        w.append(f"extern volatile struct rq_slot {sym}[];")
    w.append("static inline void seq_init(void) {")
    for sym, t in tables.items():
        ch = comp.tables._info[(core, t.channel)]
        w.append(f"    init_pulse_params({ch.cname}, {sym}, {len(t.pulses)});")
        if meas is not None and ch.index == meas.demod.index:
            w.append(f"    set_freq({ch.cname}, {_hex(meas.demod_code)});")
        else:
            w.append(f"    set_freq({ch.cname}, {_hex(t.freq_code(comp.m))});")
    w.append("}")
    w.append("static inline void seq_point(int32_t a, int32_t b, int32_t r0, int32_t r1, "
             "int32_t r2, int32_t r3) {")
    for fld, ch, slot, v in comp.point_ops:
        if ch.core != core:
            continue
        if fld == "freq":
            w.append(f"    set_freq({ch.cname}, {_c(v, names)});")
        else:
            w.append(f"    set_{fld}({ch.cname}, {slot}, {_c(v, names)});")
    if meas is not None:
        mm = meas.meas
        if mm.freq is not None:
            v = _c(mm.freq, names)
            w.append(f"    set_freq({meas.ro.cname}, {v});")
            w.append(f"    set_freq({meas.demod.cname}, (4 * (({v}) >> 16)) << 16);")
        if mm.amp is not None:
            w.append(f"    set_amp({meas.ro.cname}, 0, {_c(mm.amp, names)});")
        if mm.dur is not None:
            w.append(f"    set_dur({meas.demod.cname}, 0, {_c(mm.dur, names)});")
        if mm.drive_dur is not None:
            w.append(f"    set_dur({meas.ro.cname}, 0, {_c(mm.drive_dur, names)});")
    w.append("    (void)a; (void)b; (void)r0; (void)r1; (void)r2; (void)r3;")
    w.append("}")
    w.append("static inline void seq_shot(uint32_t t_ro, int32_t a, int32_t b, int32_t r0, "
             "int32_t r1, int32_t r2, int32_t r3) {")
    for c, name in comp.frames:
        if c == core:
            w.append(f"    int32_t {name} = 0;")
    ind = 1
    for op in comp.ops:
        kind, pre = op[0], "    " * ind
        if kind == "for":
            _, k, n = op
            w.append(pre + f"for (int32_t {names[k]} = 0; {names[k]} < {names[n]}; {names[k]}++) {{")
            ind += 1
        elif kind in ("endfor", "endif"):
            ind -= 1
            w.append("    " * ind + "}")
        elif kind == "if":
            w.append(pre + f"if ({names[op[1]]}) {{")
            ind += 1
        elif kind == "reset_meas":
            if meas is not None:
                t = _t(op[1], names)
                w.append(pre + f"play({meas.ro.cname}, 0, {t});")
                w.append(pre + f"play({meas.demod.cname}, 0, {t} + {meas.ddly});")
                w.append(pre + f"wait_until({t} + {meas.ddly + READOUT_LEAD});")
        elif kind == "if_res":
            w.append(pre + ("if (read_res()) {" if meas is not None else "if (0) {"))
            ind += 1
        elif kind == "wait":
            if op[2] == core:
                w.append(pre + f"wait_until({_t(op[1], names)});")
        elif kind == "retune":
            _, ch, t, code = op
            if ch.core == core:
                w.append(pre + f"set_start({ch.cname}, {_t(t, names)});")
                w.append(pre + f"set_freq({ch.cname}, {_c(code, names)});")
        elif kind == "rz":
            _, frame, phi = op
            if _owner(comp, frame) == core:
                w.append(pre + f"{frame} += {_c(phi, names)};")
        elif kind == "play":
            _, ch, slot, t, code, frame, vz0, extra, vzsum = op
            if ch.core != core:
                continue
            off = frame if vz0 == Lin(0) else f"{frame} + {_c(vz0, names)}"
            if extra is not None:
                off = f"{off} + {_c(extra, names)}"
            if code is not None:
                w.append(pre + f"set_start({ch.cname}, {_t(t, names)});")
                w.append(pre + f"set_freq({ch.cname}, {_c(code, names)});")
                w.append(pre + f"set_phase_offset({ch.cname}, {off});")
                w.append(pre + f"fire({ch.cname}, {slot});")
            else:
                w.append(pre + f"set_phase_offset({ch.cname}, {off});")
                w.append(pre + f"play({ch.cname}, {slot}, {_t(t, names)});")
            if vzsum != Lin(0):
                w.append(pre + f"{frame} += {_c(vzsum, names)};")
    w.append("    (void)a; (void)b; (void)r0; (void)r1; (void)r2; (void)r3;")
    w.append("}")
    w.append("static inline void seq_meas(uint32_t t, int32_t r0, int32_t r1, int32_t r2, "
             "int32_t r3) {")
    if meas is not None:
        d = str(meas.ddly) if meas.meas.delay is None else _c(meas.meas.delay, names)
        w.append(f"    play({meas.ro.cname}, 0, t);")
        w.append(f"    play({meas.demod.cname}, 0, t + {d});")
    w.append("    (void)t; (void)r0; (void)r1; (void)r2; (void)r3;")
    w.append("}")
    tail = (meas.ddly if meas is not None else 0) + READOUT_LEAD
    w.append(f"static inline uint32_t meas_tail(void) {{ return {tail}; }}")
    return "\n".join(w) + "\n"


def _owner(comp: Compiled, frame: str) -> int:
    for c, name in comp.frames:
        if name == frame:
            return c
    raise KeyError(frame)
