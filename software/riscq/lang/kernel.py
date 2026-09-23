"""The @kernel front end (spec 02 §3-4).

`@kernel` captures a python function; `compile_kernel` parses its source with `ast`, folds
closure constants and compile-time-bound int parameters, resolves constant-conditioned `if`s
(dead branches are skipped BEFORE ParamTable resolution, so they may reference tables unbound on
this specialization), resolves the surviving ParamTables to pulse-table slots + envelope-RAM
addresses, folds the table projections (channel base, slot index, carrier/field codes) to
constants, type-checks the int32/ptr subset, emits C via backend_c, and delegates C -> binary to
riscq.build.compile_c. Everything outside the spec-02 subset raises KernelCompileError with
file:line and the offending source line.
"""

from __future__ import annotations

import ast
import inspect
import textwrap
from dataclasses import dataclass
from pathlib import Path

from riscq import build
from riscq.lang import backend_c, ir
from riscq.map import ChannelInfo, SocMap
from riscq.pulses import EnvelopeAllocator, Pulse, units

# a ParamTable pulse's design-time fields (gate["x90"].amp), by rq_slot tuple position
_SLOT_FIELD_IDX = {"phase": 0, "amp": 1, "env": 2, "dur": 3}
_INT32_MIN, _INT32_MAX = -(1 << 31), (1 << 31) - 1

_BINOPS = {ast.Add: "+", ast.Sub: "-", ast.Mult: "*", ast.FloorDiv: "//", ast.Mod: "%",
           ast.LShift: "<<", ast.RShift: ">>", ast.BitAnd: "&", ast.BitOr: "|",
           ast.BitXor: "^"}
_CMPOPS = {ast.Lt: "<", ast.LtE: "<=", ast.Gt: ">", ast.GtE: ">=", ast.Eq: "==",
           ast.NotEq: "!="}
_CMP_STRS = set(_CMPOPS.values())


class KernelCompileError(Exception):
    """A kernel-language violation; the message carries file:line + the offending source."""


class ParamTable:
    """A per-channel pulse table (spec 02 §3.2): binds a logical RF channel, its carrier, and a
    named, ORDERED set of Pulses ("gates"). Bound to a kernel ParamTable parameter at compile time
    via compile_kernel(tables=dict(name=table)); dict insertion order fixes the slot assignment
    (`"x90"` -> slot 0). A Pulse carries no channel/slot — it belongs to its table.

    `channel` is a `SocMap` ChannelInfo (resolved by name: `m.channel_named("gate", core)`) or the
    bare index it carries. `.channel` is always the int index; `.core` is the core the ChannelInfo
    came from, or None for a bare index — compile_kernel refuses a table from another core."""

    def __init__(self, channel: int | ChannelInfo, freq_hz: float, pulses: dict[str, Pulse]):
        if isinstance(channel, ChannelInfo):
            self.core: int | None = channel.core     # the core this table was resolved on
            channel = channel.index
        elif isinstance(channel, int) and not isinstance(channel, bool):
            self.core = None                          # a bare index belongs to no particular core
        else:
            raise ValueError(f"ParamTable channel must be an int or a ChannelInfo, got {channel!r}")
        if not pulses:
            raise ValueError("ParamTable needs at least one pulse")
        for name, p in pulses.items():
            if not isinstance(p, Pulse):
                raise ValueError(f"pulse {name!r} must be a riscq.pulses.Pulse, "
                                 f"got {type(p).__name__}")
            if p.freq_hz is not None and p.freq_hz != freq_hz:   # one NCO per channel (§3.2)
                raise ValueError(f"pulse {name!r} freq_hz {p.freq_hz} != table carrier {freq_hz}")
        self.channel = channel
        self.freq_hz = freq_hz
        self.pulses = dict(pulses)

    def slot_of(self, name: str) -> int:
        """The slot index of a named pulse (dict insertion order) — the same map the host uses to
        fill a data-driven `slots` array (spec 02 §3.2)."""
        if name not in self.pulses:
            raise KeyError(f"no pulse {name!r} in this table (have {list(self.pulses)})")
        return list(self.pulses).index(name)

    def freq_code(self, m: SocMap) -> int:
        """The table carrier's frequency code (what `gate.freq` folds to)."""
        return units.freq_to_code(self.freq_hz, m.params)


class DioTable(ParamTable):
    """A timed-DIO channel's slot table (universal-control/01 P5): `entries` maps a name to
    `(mask, value, dur_batches)` — the fired entry sets the masked output lines to `value` at its
    scheduled batch and holds `dur` (>= 2 when another entry follows). Bound and played exactly like a
    ParamTable (`init_pulse_params(ttl.pulses)`, `play(ttl, ttl["on"], t)`); `ttl.sink` folds to the
    channel's event-sink base (`pop_event(ttl.sink)` reads an input-edge event)."""

    def __init__(self, channel: int | ChannelInfo, entries: dict[str, tuple[int, int, int]]):
        if isinstance(channel, ChannelInfo):
            self.core = channel.core
            channel = channel.index
        elif isinstance(channel, int) and not isinstance(channel, bool):
            self.core = None
        else:
            raise ValueError(f"DioTable channel must be an int or a ChannelInfo, got {channel!r}")
        if not entries:
            raise ValueError("DioTable needs at least one entry")
        for name, e in entries.items():
            if len(e) != 3 or not all(isinstance(x, int) for x in e):
                raise ValueError(f"entry {name!r} must be (mask, value, dur_batches) ints, got {e!r}")
            mask, value, dur = e
            if not (0 <= mask <= 0xFFFF and 0 <= value <= 0xFFFF and 1 <= dur <= 0xFFFF):
                raise ValueError(f"entry {name!r}: mask/value are 16-bit, dur in [1, 65535]")
        self.channel = channel
        self.freq_hz = 0.0
        self.pulses = dict(entries)


class Group:
    """A cross-core group (specs/cross-core/02 §8.1): one shared 32-bit word every core holds as
    `board[id]`, and the counted barrier of the same `id`. `members` are core indices in slot order —
    a member's slot is its position, so `publish(g, bit)` on core `q` sets bit `members.index(q)` and
    `remote(g, q)` reads it back; `barrier(g)` arrives at barrier `id` with `count = len(members)` and
    returns the released time. Bind it: `compile_kernel(..., pair=Group([q0, q1], id=0))`."""

    def __init__(self, members, id: int = 0):
        self.members = [int(q) for q in members]
        if len(set(self.members)) != len(self.members):
            raise ValueError(f"Group members must be distinct, got {self.members}")
        if not 0 <= id < 4:
            raise ValueError(f"Group id {id} out of range (RQ_GROUPS = 4)")
        self.id = int(id)

    def slot_of(self, core: int) -> int:
        return self.members.index(core)


class Mailbox:
    """A one-sender signal mailbox (specs/cross-core/02 §8.1): `sender` may `signal(m, x)`, `receiver`
    may `wait_signal(m)` (halting, consume-on-read); `index` picks the receiver's mailbox register
    (`RQ_INBOX_MAILBOX0 + index·0x20`). The address says who sent it, so a kernel that signals from
    the wrong core is a compile error."""

    def __init__(self, sender: int, receiver: int, index: int = 0, receiver_board: int = 0):
        if not 0 <= index < 2:
            raise ValueError(f"Mailbox index {index} out of range (RQ_MAILBOX_NUM = 2)")
        self.sender, self.receiver, self.index = int(sender), int(receiver), int(index)
        self.receiver_board = int(receiver_board)     # the receiver's board (multi-board systems)


class Array:
    """Parameter annotation AND size binding: `counts: Array` in the signature,
    `compile_kernel(k, counts=Array(31))` to size the on-core int32 buffer. `input=True` marks a
    host-preloaded buffer (a slot/time schedule) — it lands in .data instead of .bss so the host
    write survives boot (spec 02 §3.1); a plain (output) Array stays in .bss.

    `host=True` puts an OUTPUT array in the core's write-only host window instead of RAM
    (specs/software/22): the kernel body is identical — `out[k] = ...` is a plain volatile store
    either way — but the store lands in PS DDR4, so the array is not bounded by the 16 KB unified
    I+D RAM (2 M shots per core per run instead of ~1k). The placement is a call-site choice; no
    kernel changes."""

    def __init__(self, n: int, input: bool = False, host: bool = False):
        if not isinstance(n, int) or isinstance(n, bool) or n <= 0:
            raise ValueError(f"Array size must be a positive int, got {n!r}")
        if input and host:
            raise ValueError("Array(input=True, host=True) is not possible — the host window is "
                             "write-only, so a host-preloaded input cannot live there")
        self.n = n
        self.input = bool(input)
        self.host = bool(host)


class Kernel:
    """The @kernel wrapper: stays callable as plain python (host-side golden interpretation)
    and hands its source to compile_kernel."""

    def __init__(self, fn):
        self.fn = fn
        self.__name__ = fn.__name__
        self.__doc__ = fn.__doc__

    def __call__(self, *args, **kwargs):
        return self.fn(*args, **kwargs)


def kernel(fn) -> Kernel:
    return Kernel(fn)


@dataclass
class _RTable:
    """A ParamTable resolved on this specialization: the channel it folds to (base/cname), the
    per-slot design-time codes, and its carrier code."""

    name: str          # the C .data symbol / kernel parameter name
    table: ParamTable
    cname: str         # channel macro RF_CHi
    base: int          # channel RF sub-window base
    count: int         # number of slots (= len(pulses))
    carrier_code: int
    slot_codes: list   # [(phase, amp, env, dur), ...] in dict order


def compile_kernel(k: Kernel, soc_map: SocMap, tables: dict[str, ParamTable] | None = None,
                   include=(), core: int = 0, **bindings) -> build.Program:
    """Compile one specialization of `k` for `core`: python -> IR -> C -> flat image.

    `core` picks the core whose channel geometry (`soc_map.channel(index, core)`) and header
    (`soc_map.gen_header(core)`) the program is compiled against, and folds as the kernel's own
    `core` parameter where it has one (`if core == 1: ...`). A bound ParamTable resolved on a
    DIFFERENT core is an error.

    `tables` binds ParamTable parameters (a table reached only from a dead branch needs no
    binding); every bound table is emitted into the Program whether or not the body references
    it (specs/universal-cal/01 §6.3: a generated sequence header may be the only user).
    `include` lists user headers — file paths, or `(name, text)` pairs of generated headers —
    #included after riscq.h and hashed into the build-cache key; `bindings` bake int parameters
    as constants and size Array parameters (`counts=Array(31)`). Returns a Program carrying the
    params layout, the array table, the per-channel envelope images, and each live table's slot
    codes for riscq.run to upload."""
    fe = _FrontEnd(k, soc_map, dict(tables or {}), dict(bindings), int(core))
    kir = fe.compile()
    extra_headers = {}
    for inc in include:
        if isinstance(inc, tuple):
            name, text = inc
        else:
            name, text = Path(inc).name, Path(inc).read_text()
        kir.includes.append(name)
        extra_headers[name] = text
    c_source = backend_c.emit(kir)
    image = build.compile_c(c_source, soc_map, extra_headers=extra_headers or None, core=fe.core)
    params: dict[str, int | None] = {name: None for name in kir.params}
    envelopes = {ch: alloc.image() for ch, alloc in fe.allocs.items() if alloc.image()}
    prog_tables = {t.name: list(t.slot_codes) for t in kir.tables}
    return build.Program(image, params=params, arrays=dict(kir.arrays), envelopes=envelopes,
                         tables=prog_tables, c_source=c_source, bindings=dict(fe.bound),
                         host_arrays=dict(kir.host_arrays), core=fe.core)


def _wrap32(v: int) -> int:
    return (v + (1 << 31)) % (1 << 32) - (1 << 31)


_XCORE_OPS = ("publish", "remote", "barrier", "signal", "wait_signal")


class _FrontEnd:
    def __init__(self, k: Kernel, soc_map: SocMap, tables: dict, bindings: dict, core: int):
        if not isinstance(k, Kernel):
            raise KernelCompileError(
                f"compile_kernel needs an @kernel function, got {type(k).__name__}")
        self.fn = k.fn
        self.m = soc_map
        self.core = core              # the core this specialization is compiled for
        self.tables = tables          # name -> ParamTable, from tables=
        self.bindings = bindings

        self.file = inspect.getsourcefile(self.fn) or "<unknown>"
        self._src_lines, self._first_line = inspect.getsourcelines(self.fn)
        tree = ast.parse(textwrap.dedent("".join(self._src_lines)))
        ast.increment_lineno(tree, self._first_line - 1)
        self.fdef = tree.body[0]

        # parameter classification (filled by _classify_params)
        self.bound: dict[str, int] = {}
        self.uparams: list[str] = []
        self.arrays: dict[str, int] = {}
        self.xcore = {}   # Group / Mailbox bindings by parameter name
        self.input_arrays: set[str] = set()
        self.host_arrays: set[str] = set()
        self.table_params: list[str] = []
        self.param_names: set[str] = set()

        self.locals: dict[str, str] = {}         # name -> "int" | "ptr"
        self.allocs: dict[int, EnvelopeAllocator] = {}   # channel index -> allocator
        self._resolved: dict[str, _RTable] = {}          # live tables, resolution order
        self._channel_used: dict[int, str] = {}          # channel index -> table name

    # ── driver ──

    def compile(self) -> ir.KernelIR:
        self._classify_params()
        body = self._block(self.fdef.body, drop_docstring=True)
        for tname in self.tables:            # bound but unreferenced tables are emitted too
            self._resolve_table(self.fdef, tname)
        tables = [ir.Table(rt.name, rt.cname, rt.count, rt.carrier_code, list(rt.slot_codes))
                  for rt in self._resolved.values()]
        return ir.KernelIR(
            name=self.fdef.name, src_file=self.file, def_line=self.fdef.lineno,
            params=list(self.uparams), arrays=dict(self.arrays),
            input_arrays=set(self.input_arrays), host_arrays=self._host_offsets(),
            locals=dict(self.locals),
            tables=tables, body=body)

    def _host_offsets(self) -> dict:
        """Host-window arrays packed from offset 0 in declaration order: name -> (byte offset, n).
        Every core has its own 16 MB slice, so the offsets are the same on every core and the
        funnel adds `core << 24`."""
        out, off = {}, 0
        for name, n in self.arrays.items():
            if name in self.host_arrays:
                out[name] = (off, n)
                off += 4 * n
        if off > self.m.HOSTWIN_BYTES:
            self._err(self.fdef, f"host-window arrays need {off} B, window is "
                                 f"{self.m.HOSTWIN_BYTES} B per core")
        return out

    def _err(self, node, msg):
        line = getattr(node, "lineno", None) or self.fdef.lineno
        i = line - self._first_line
        text = self._src_lines[i].strip() if 0 <= i < len(self._src_lines) else ""
        raise KernelCompileError(f"{self.file}:{line}: {msg}\n    {text}")

    # ── parameters and bindings ──

    def _classify_params(self):
        a = self.fdef.args
        if a.posonlyargs or a.kwonlyargs or a.vararg or a.kwarg or a.defaults:
            self._err(self.fdef,
                      "kernel parameters must be plain positional names without defaults")
        anns = inspect.get_annotations(self.fn, eval_str=True)
        for arg in a.args:
            name = arg.arg
            if name.startswith("__rq_"):
                self._err(arg, f"parameter name {name!r} uses the reserved '__rq_' prefix")
            ann = anns.get(name)
            if ann is int:
                if name == "core":                    # the compile target, folded (spec 01 §2.5)
                    self.bindings.setdefault(name, self.core)
                if name in self.bindings:
                    v = self.bindings.pop(name)
                    if isinstance(v, bool):
                        v = int(v)
                    if not isinstance(v, int):
                        self._err(arg, f"binding for int parameter {name!r} must be an int, "
                                       f"got {type(v).__name__}")
                    if not _INT32_MIN <= v <= _INT32_MAX:
                        self._err(arg, f"binding {name}={v} outside int32 range")
                    self.bound[name] = v
                else:
                    self.uparams.append(name)
            elif ann is ParamTable:
                if name in self.bindings:
                    self._err(arg, f"ParamTable parameter {name!r} is bound via "
                                   f"tables=dict({name}=<ParamTable>), not a binding")
                self.table_params.append(name)
            elif ann is Group or ann is Mailbox:
                if name not in self.bindings:
                    self._err(arg, f"{ann.__name__} parameter {name!r} needs a binding: "
                                   f"compile_kernel(..., {name}={ann.__name__}(...))")
                v = self.bindings.pop(name)
                if not isinstance(v, ann):
                    self._err(arg, f"binding for {name!r} must be a {ann.__name__}, "
                                   f"got {type(v).__name__}")
                self.xcore[name] = v
            elif ann is Array:
                if name not in self.bindings:
                    self._err(arg, f"Array parameter {name!r} needs a size binding: "
                                   f"compile_kernel(..., {name}=Array(n))")
                v = self.bindings.pop(name)
                if not isinstance(v, Array):
                    self._err(arg, f"binding for Array parameter {name!r} must be Array(n), "
                                   f"got {type(v).__name__}")
                self.arrays[name] = v.n
                if v.input:
                    self.input_arrays.add(name)
                if v.host:
                    self.host_arrays.add(name)
            else:
                self._err(arg, f"parameter {name!r} must be annotated int, ParamTable, Array, "
                               f"Group, or Mailbox")
        self.param_names = set(self.bound) | set(self.uparams) | set(self.arrays) \
            | set(self.table_params) | set(self.xcore)
        if self.bindings:
            self._err(self.fdef, f"bindings for unknown parameters: {sorted(self.bindings)}")
        bad_tables = set(self.tables) - set(self.table_params)
        if bad_tables and self.table_params:
            self._err(self.fdef, f"tables for non-ParamTable parameters: {sorted(bad_tables)}")
        # a kernel with NO ParamTable parameters takes tables by name for its generated
        # sequence header (specs/universal-cal/01 §6.3): every one is emitted (compile()).
        for tname, table in self.tables.items():
            if not isinstance(table, ParamTable):
                self._err(self.fdef, f"tables[{tname!r}] must be a ParamTable, "
                                     f"got {type(table).__name__}")

    # ── ParamTables ──

    def _resolve_table(self, node, tname) -> _RTable:
        """Bind a live ParamTable on first reference: assign slots (dict order), allocate every
        pulse's envelope, compute the per-slot codes, and fold its channel. Dead branches never
        reach here (they were eliminated before), so an unbound table there costs nothing."""
        if tname in self._resolved:
            return self._resolved[tname]
        if tname not in self.tables:
            self._err(node, f"ParamTable {tname!r} has no binding on this specialization — "
                            f"pass tables=dict({tname}=<ParamTable>)")
        table = self.tables[tname]
        if table.core is not None and table.core != self.core:
            raise ValueError(f"ParamTable {tname!r} was resolved on core {table.core}, but this "
                             f"kernel is compiled for core {self.core}")
        try:
            chinfo = self.m.channel(table.channel, self.core)  # ValueError on unknown index (§5)
        except ValueError as e:
            raise ValueError(f"ParamTable {tname!r}: {e}") from None
        prev = self._channel_used.get(table.channel)
        if prev is not None:
            raise ValueError(f"ParamTable {tname!r} and {prev!r} both bind channel "
                             f"{table.channel} — one table per channel")
        if len(table.pulses) > chinfo.slot_count:
            self._err(node, f"ParamTable {tname!r} has {len(table.pulses)} pulses but channel "
                            f"{table.channel} ({chinfo.cname}) has only {chinfo.slot_count} "
                            f"slot(s)")
        self._channel_used[table.channel] = tname
        if isinstance(table, DioTable):
            if chinfo.kind != "dio":
                self._err(node, f"DioTable {tname!r} bound to channel {table.channel} "
                                f"({chinfo.name}), which is a {chinfo.kind} channel, not dio")
            slot_codes = [(mask, value, 0, dur) for mask, value, dur in table.pulses.values()]
            rt = _RTable(name=tname, table=table, cname=chinfo.cname, base=chinfo.base,
                         count=len(table.pulses), carrier_code=0, slot_codes=slot_codes)
            self._resolved[tname] = rt
            return rt
        if chinfo.kind == "dio":
            self._err(node, f"ParamTable {tname!r} bound to dio channel {chinfo.name}: use a DioTable")
        alloc = self.allocs.setdefault(chinfo.index, EnvelopeAllocator(chinfo.env_depth))
        slot_codes = []
        for pname, pulse in table.pulses.items():
            lines = pulse.packed_lines(self.m, chinfo.index, self.core)
            line0 = alloc.add(lines, name=f"{tname}[{pname!r}]")
            slot_codes.append((pulse.phase_code(), pulse.amp_code(), line0, len(lines)))
        rt = _RTable(name=tname, table=table, cname=chinfo.cname, base=chinfo.base,
                     count=len(table.pulses), carrier_code=table.freq_code(self.m),
                     slot_codes=slot_codes)
        self._resolved[tname] = rt
        return rt

    def _table_key(self, node, tname) -> tuple[_RTable, str]:
        """The string-literal gate name of a `gate["x90"]` subscript (spec 02 §5: literal only)."""
        sl = node.slice
        if not (isinstance(sl, ast.Constant) and isinstance(sl.value, str)):
            self._err(node, f"ParamTable {tname!r} needs a string-literal gate name "
                            f"({tname}[\"x90\"]), not a runtime index")
        rt = self._resolve_table(node, tname)
        if sl.value not in rt.table.pulses:
            self._err(node, f"no gate {sl.value!r} in ParamTable {tname!r} "
                            f"(have {list(rt.table.pulses)})")
        return rt, sl.value

    # ── statements ──

    def _block(self, stmts, drop_docstring=False):
        out = []
        for i, s in enumerate(stmts):
            if (drop_docstring and i == 0 and isinstance(s, ast.Expr)
                    and isinstance(s.value, ast.Constant) and isinstance(s.value.value, str)):
                continue
            out.extend(self._stmt(s))
        return out

    def _stmt(self, node):
        if isinstance(node, ast.Assign):
            return self._assign(node)
        if isinstance(node, ast.AugAssign):
            return self._aug_assign(node)
        if isinstance(node, ast.Expr):
            if isinstance(node.value, ast.Call):
                return [ir.CallStmt(self._call(node.value), node.lineno)]
            self._err(node, "an expression statement must be a call")
        if isinstance(node, ast.If):
            return self._if(node)
        if isinstance(node, ast.While):
            return self._while(node)
        if isinstance(node, ast.For):
            return self._for(node)
        if isinstance(node, ast.Break):
            return [ir.Break(node.lineno)]
        if isinstance(node, ast.Continue):
            return [ir.Continue(node.lineno)]
        if isinstance(node, ast.Pass):
            return []
        if isinstance(node, ast.Return):
            self._err(node, "'return' is not supported — kernels return data via Array "
                            "parameters")
        self._err(node, f"unsupported statement: {type(node).__name__}")

    def _assign(self, node):
        if len(node.targets) != 1:
            self._err(node, "chained assignment (a = b = ...) is not supported")
        tgt = node.targets[0]
        if isinstance(tgt, ast.Name):
            return [self._assign_name(node, tgt.id, node.value)]
        if isinstance(tgt, ast.Subscript):
            if isinstance(tgt.value, ast.Name) and tgt.value.id in self.table_params:
                self._err(node, f"cannot assign to a ParamTable slot {tgt.value.id!r} — "
                                f"retune the table on the host, not in the kernel")
            target = self._subscript(tgt)
            value, ty = self._expr(node.value)
            if ty != "int":
                self._err(node, "a ptr where an int is expected: array/pointer elements "
                                "hold int32")
            return [ir.Assign(target, value, node.lineno)]
        if isinstance(tgt, ast.Tuple):
            self._err(node, "tuple assignment is not supported")
        self._err(node, f"unsupported assignment target: {type(tgt).__name__}")

    def _assign_name(self, node, name, value_node):
        if name in self.param_names:
            self._err(node, f"assignment to kernel parameter {name!r} — copy it to a local "
                            f"first")
        # p = ptr(buf[, k]) is type syntax, not a call (§3.3)
        if (isinstance(value_node, ast.Call) and isinstance(value_node.func, ast.Name)
                and value_node.func.id == "ptr"):
            value, ty = self._ptr_of(value_node), "ptr"
        else:
            value, ty = self._expr(value_node)
        old = self.locals.get(name)
        if old is not None and old != ty:
            self._err(node, f"{name!r} is a {old} but is assigned a {ty}")
        self.locals[name] = ty
        return ir.Assign(ir.Var(name, ty), value, node.lineno)

    def _ptr_of(self, call):
        if call.keywords:
            self._err(call, "keyword arguments are not supported")
        if not 1 <= len(call.args) <= 2:
            self._err(call, "ptr() takes ptr(buf) or ptr(buf, k)")
        base = call.args[0]
        if not (isinstance(base, ast.Name) and base.id in self.arrays):
            self._err(call, "ptr() of a non-Array — pointers only point into Array "
                            "parameters")
        offset = None
        if len(call.args) == 2:
            offset, ty = self._expr(call.args[1])
            if ty != "int":
                self._err(call, "ptr() offset must be an int")
        return ir.PtrOf(base.id, offset)

    def _aug_assign(self, node):
        op = _BINOPS.get(type(node.op))
        if op is None:
            if isinstance(node.op, ast.Div):
                self._err(node, "'/' is float division — use '//'")
            self._err(node, f"unsupported operator: {type(node.op).__name__}")
        value, vty = self._expr(node.value)
        if isinstance(node.target, ast.Name):
            name = node.target.id
            if name in self.param_names:
                self._err(node, f"assignment to kernel parameter {name!r} — copy it to a "
                                f"local first")
            if name not in self.locals:
                self._err(node, f"unknown name {name!r}")
            lty = self.locals[name]
            combined, _ = self._binop(node, op, ir.Var(name, lty), lty, value, vty)
            return [ir.Assign(ir.Var(name, lty), combined, node.lineno)]
        if isinstance(node.target, ast.Subscript):
            if (isinstance(node.target.value, ast.Name)
                    and node.target.value.id in self.table_params):
                self._err(node, f"cannot assign to a ParamTable slot "
                                f"{node.target.value.id!r} — retune the table on the host")
            load = self._subscript(node.target)
            store = self._subscript(node.target)
            combined, _ = self._binop(node, op, load, "int", value, vty)
            return [ir.Assign(store, combined, node.lineno)]
        self._err(node, f"unsupported assignment target: {type(node.target).__name__}")

    def _if(self, node):
        cond, ty = self._expr(node.test)
        if ty != "int":
            self._err(node, "an if condition must be an int")
        if isinstance(cond, ir.Const):
            # compile-time if (§3.4): the dead branch is skipped BEFORE table resolution
            return self._block(node.body if cond.value else node.orelse)
        return [ir.If(cond, self._block(node.body), self._block(node.orelse), node.lineno)]

    def _while(self, node):
        if node.orelse:
            self._err(node, "while-else is not supported")
        cond, ty = self._expr(node.test)
        if ty != "int":
            self._err(node, "a while condition must be an int")
        return [ir.While(cond, self._block(node.body), node.lineno)]

    def _for(self, node):
        if node.orelse:
            self._err(node, "for-else is not supported")
        if not isinstance(node.target, ast.Name):
            self._err(node, "the for target must be a plain name")
        it = node.iter
        if not (isinstance(it, ast.Call) and isinstance(it.func, ast.Name)
                and it.func.id == "range"):
            self._err(node, "for only iterates range(stop | start, stop[, step])")
        if it.keywords:
            self._err(node, "keyword arguments are not supported")
        if not 1 <= len(it.args) <= 3:
            self._err(node, f"range() takes 1-3 arguments, got {len(it.args)}")
        exprs = []
        for a in it.args:
            e, ty = self._expr(a)
            if ty != "int":
                self._err(node, "range() bounds must be ints")
            exprs.append(e)
        if len(exprs) == 1:
            start, stop, step = ir.Const(0), exprs[0], ir.Const(1)
        elif len(exprs) == 2:
            start, stop, step = exprs[0], exprs[1], ir.Const(1)
        else:
            start, stop, step = exprs
        if isinstance(step, ir.Const) and step.value == 0:
            self._err(node, "range() step must not be zero")
        name = node.target.id
        if name in self.param_names:
            self._err(node, f"assignment to kernel parameter {name!r} — the for target "
                            f"must be a local")
        old = self.locals.get(name)
        if old is not None and old != "int":
            self._err(node, f"{name!r} is a {old} but is used as a for counter")
        self.locals[name] = "int"
        return [ir.For(ir.Var(name, "int"), start, stop, step, self._block(node.body),
                       node.lineno)]

    # ── expressions: every path returns (ir_node, "int" | "ptr") ──

    def _expr(self, node):
        if isinstance(node, ast.Constant):
            return self._constant(node)
        if isinstance(node, ast.Name):
            return self._name(node)
        if isinstance(node, ast.BinOp):
            op = _BINOPS.get(type(node.op))
            if op is None:
                if isinstance(node.op, ast.Div):
                    self._err(node, "'/' is float division — use '//'")
                self._err(node, f"unsupported operator: {type(node.op).__name__}")
            lhs, lt = self._expr(node.left)
            rhs, rt = self._expr(node.right)
            return self._binop(node, op, lhs, lt, rhs, rt)
        if isinstance(node, ast.Compare):
            if len(node.ops) != 1:
                self._err(node, "chained comparisons are not supported — split with 'and'")
            op = _CMPOPS.get(type(node.ops[0]))
            if op is None:
                self._err(node, f"unsupported comparison: {type(node.ops[0]).__name__}")
            lhs, lt = self._expr(node.left)
            rhs, rt = self._expr(node.comparators[0])
            return self._binop(node, op, lhs, lt, rhs, rt)
        if isinstance(node, ast.BoolOp):
            return self._boolop(node)
        if isinstance(node, ast.UnaryOp):
            return self._unary(node)
        if isinstance(node, ast.Subscript):
            return self._subscript(node), "int"
        if isinstance(node, ast.Call):
            return self._call(node), "int"
        if isinstance(node, ast.Attribute):
            return self._attribute(node)
        self._err(node, f"unsupported expression: {type(node).__name__}")

    def _constant(self, node):
        v = node.value
        if isinstance(v, bool):
            v = int(v)
        if isinstance(v, int):
            if not _INT32_MIN <= v <= _INT32_MAX:
                self._err(node, f"constant {v} outside int32 range")
            return ir.Const(v), "int"
        if isinstance(v, float):
            self._err(node, f"float literal {v!r} — kernels are int32-only (convert on the "
                            f"host with riscq.pulses.units)")
        self._err(node, f"unsupported literal: {v!r}")

    def _name(self, node):
        n = node.id
        if n in self.locals:
            return ir.Var(n, self.locals[n]), self.locals[n]
        if n in self.bound:
            return ir.Const(self.bound[n]), "int"
        if n in self.uparams:
            return ir.Param(n), "int"
        if n in self.table_params:
            self._err(node, f"ParamTable {n!r} can only be a call argument, indexed "
                            f"({n}[\"x90\"]), or read as {n}.freq / {n}.pulses")
        if n in self.arrays:
            self._err(node, f"Array {n!r} can only be indexed, passed to a call, or given "
                            f"to ptr()")
        if n in self.xcore:
            self._err(node, f"{type(self.xcore[n]).__name__} {n!r} is only a call argument "
                            f"(publish/remote/barrier/signal/wait_signal)")
        found, v = self._lookup_closure(n)
        if found:
            if isinstance(v, bool):
                v = int(v)
            if not isinstance(v, int):
                self._err(node, f"{n!r} does not fold to an int constant "
                                f"(it is a {type(v).__name__})")
            if not _INT32_MIN <= v <= _INT32_MAX:
                self._err(node, f"constant {n} = {v} outside int32 range")
            return ir.Const(v), "int"
        self._err(node, f"unknown name {n!r}")

    def _lookup_closure(self, name):
        code = self.fn.__code__
        if self.fn.__closure__ and name in code.co_freevars:
            return True, self.fn.__closure__[code.co_freevars.index(name)].cell_contents
        if name in self.fn.__globals__:
            return True, self.fn.__globals__[name]
        return False, None

    def _binop(self, node, op, lhs, lt, rhs, rt):
        if lt == "int" and rt == "int":
            if isinstance(lhs, ir.Const) and isinstance(rhs, ir.Const):
                return ir.Const(self._fold_binop(node, op, lhs.value, rhs.value)), "int"
            return ir.BinOp(op, lhs, rhs), "int"
        if op in ("+", "-") and lt == "ptr" and rt == "int":
            return ir.BinOp(op, lhs, rhs), "ptr"
        if op in _CMP_STRS and lt == "ptr" and rt == "ptr":
            return ir.BinOp(op, lhs, rhs), "int"
        self._err(node, f"a ptr where an int is expected: pointers support only p ± int and "
                        f"pointer-pointer comparison (got {lt} {op} {rt})")

    def _fold_binop(self, node, op, a, b):
        """Compile-time arithmetic with the RUNTIME's semantics: int32 wrap (-fwrapv) and C
        truncation-toward-zero for // and %."""
        if op == "+":
            r = a + b
        elif op == "-":
            r = a - b
        elif op == "*":
            r = a * b
        elif op in ("//", "%"):
            if b == 0:
                self._err(node, "division by zero in a constant expression")
            q = abs(a) // abs(b)
            if (a < 0) != (b < 0):
                q = -q
            r = q if op == "//" else a - q * b
        elif op in ("<<", ">>"):
            if not 0 <= b < 32:
                self._err(node, f"constant shift by {b} (must be 0..31)")
            r = a << b if op == "<<" else a >> b
        elif op == "&":
            r = a & b
        elif op == "|":
            r = a | b
        elif op == "^":
            r = a ^ b
        elif op in _CMP_STRS:
            r = int({"<": a < b, "<=": a <= b, ">": a > b, ">=": a >= b,
                     "==": a == b, "!=": a != b}[op])
        else:
            raise AssertionError(op)
        return _wrap32(r)

    def _boolop(self, node):
        op = "and" if isinstance(node.op, ast.And) else "or"
        parts = []
        for v in node.values:
            e, ty = self._expr(v)
            if ty != "int":
                self._err(node, f"'{op}' operands must be int (comparisons yield 0/1), "
                                f"got ptr")
            parts.append(e)
        if all(isinstance(p, ir.Const) for p in parts):
            if op == "and":
                return ir.Const(int(all(p.value != 0 for p in parts))), "int"
            return ir.Const(int(any(p.value != 0 for p in parts))), "int"
        out = parts[0]
        for p in parts[1:]:
            out = ir.BinOp(op, out, p)
        return out, "int"

    def _unary(self, node):
        e, ty = self._expr(node.operand)
        if isinstance(node.op, ast.Not):
            if ty != "int":
                self._err(node, "'not' needs an int operand")
            if isinstance(e, ir.Const):
                return ir.Const(int(e.value == 0)), "int"
            return ir.Not(e), "int"
        if isinstance(node.op, ast.USub):
            if isinstance(e, ir.Const):
                v = -e.value
                if not _INT32_MIN <= v <= _INT32_MAX:
                    self._err(node, f"constant {v} outside int32 range")
                return ir.Const(v), "int"
            self._err(node, "unary '-' only applies to constants — write 0 - x")
        if isinstance(node.op, ast.UAdd):
            if isinstance(e, ir.Const):
                return e, "int"
            self._err(node, "unary '+' only applies to constants")
        self._err(node, f"unsupported operator: {type(node.op).__name__}")

    def _subscript(self, node):
        """buf[i] / p[k] (load or store target), or gate["x90"] -> a folded slot Const."""
        if isinstance(node.slice, (ast.Slice, ast.Tuple)):
            self._err(node, "slicing / multi-dim indexing is not supported — index elements")
        base = node.value
        if isinstance(base, ast.Name) and base.id in self.table_params:
            rt, key = self._table_key(node, base.id)
            return ir.Const(rt.table.slot_of(key))
        idx, ity = self._expr(node.slice)
        if ity != "int":
            self._err(node, "an index must be an int")
        if isinstance(base, ast.Name):
            n = base.id
            if n in self.arrays:
                if isinstance(idx, ir.Const) and not 0 <= idx.value < self.arrays[n]:
                    self._err(node, f"constant index {idx.value} outside array {n!r} "
                                    f"[0, {self.arrays[n]})")
                return ir.Index(ir.ArrayRef(n), idx)
            if self.locals.get(n) == "ptr":
                return ir.Index(ir.Var(n, "ptr"), idx)
        self._expr(base)  # raises for unknown names / unsupported bases
        self._err(node, "subscripting a plain int — only Arrays and ptr locals can be "
                        "indexed")

    # ── the cross-core ops (specs/cross-core/02 §8.1): every constant folds from the binding ──

    def _xcore_call(self, node, name):
        args = node.args
        if not args or not (isinstance(args[0], ast.Name) and args[0].id in self.xcore):
            self._err(node, f"{name}() takes a Group/Mailbox parameter first")
        obj = self.xcore[args[0].id]
        want = {"publish": (Group, 2), "remote": (Group, 2), "barrier": (Group, 1),
                "signal": (Mailbox, 2), "wait_signal": (Mailbox, 1)}[name]
        if not isinstance(obj, want[0]) or len(args) != want[1]:
            self._err(node, f"{name}() takes ({want[0].__name__}{', x' if want[1] == 2 else ''})")
        if isinstance(obj, Group):
            if name == "barrier":
                return ir.Call("barrier", [ir.Const(obj.id), ir.Const(len(obj.members))])
            if name == "publish":
                if self.core not in obj.members:
                    self._err(node, f"core {self.core} is not a member of group {args[0].id!r} "
                                    f"{obj.members} — it cannot publish to it")
                bit, _ = self._expr(args[1])
                return ir.Call("publish", [ir.Const(obj.id), ir.Const(obj.slot_of(self.core)), bit])
            # remote(g, q): q must fold to a member's core index
            q, _ = self._expr(args[1])
            if not isinstance(q, ir.Const) or q.value not in obj.members:
                self._err(node, f"remote({args[0].id}, q): q must be a constant member of "
                                f"{obj.members}")
            return ir.Call("remote", [ir.Const(obj.id), ir.Const(obj.slot_of(q.value))])
        if name == "signal":
            if self.core != obj.sender:
                self._err(node, f"only core {obj.sender} (the sender) may signal {args[0].id!r}; "
                                f"this is core {self.core}")
            x, _ = self._expr(args[1])
            node = self.m.inbox_node(obj.receiver, obj.receiver_board)
            return ir.Call("signal", [ir.Const(node, f"RQ_INBOX_NODE({obj.receiver_board}, {obj.receiver})"),
                                      ir.Const(obj.index), x])
        if self.core != obj.receiver:
            self._err(node, f"only core {obj.receiver} (the receiver) may wait on {args[0].id!r}; "
                            f"this is core {self.core}")
        return ir.Call("wait_signal", [ir.Const(obj.index)])

    def _attribute(self, node):
        v = node.value
        # gate.freq -> carrier code ; gate.pulses is a call-only projection
        if isinstance(v, ast.Name) and v.id in self.table_params:
            tname = v.id
            if node.attr == "freq":
                # carrier_code is already the seated register word (units.freq_to_code, spec 12), so
                # set_freq(ch, ch.freq) emits it raw — and it loads in one `lui` (low 16 bits zero).
                return ir.Const(self._resolve_table(node, tname).carrier_code), "int"
            if node.attr == "sink":
                rt = self._resolve_table(node, tname)
                chname = self.m.channel(rt.table.channel, self.core).name
                sinks = {name: base for name, _, base in self.m.sinks(self.core)}
                if chname not in sinks:
                    self._err(node, f"channel {chname!r} reports no events — it has no sink")
                return ir.Const(sinks[chname], f"RQ_SINK_{chname.upper()}"), "int"
            if node.attr == "pulses":
                self._err(node, f"{tname}.pulses is only valid as a call argument "
                                f"(init_pulse_params({tname}.pulses)), not a bare value")
            self._err(node, f"unknown ParamTable attribute '.{node.attr}' — "
                            f"one of .freq / .pulses / .sink (a gate field is {tname}[\"x90\"].amp)")
        # gate["x90"].amp (.phase/.env/.dur/.freq) -> that pulse's design-time code
        if (isinstance(v, ast.Subscript) and isinstance(v.value, ast.Name)
                and v.value.id in self.table_params):
            rt, key = self._table_key(v, v.value.id)
            if node.attr == "freq":
                return ir.Const(rt.carrier_code), "int"   # carrier: already the seated word (spec 12)
            idx = _SLOT_FIELD_IDX.get(node.attr)
            if idx is None:
                self._err(node, f"unknown gate field '.{node.attr}' — one of "
                                f".phase/.amp/.env/.dur/.freq")
            return ir.Const(rt.slot_codes[rt.table.slot_of(key)][idx]), "int"
        self._err(node, "attribute access is ParamTable surfaces only (table.freq, "
                        "table.pulses, table[\"x90\"].amp/.phase/.env/.dur/.freq)")

    def _call(self, node):
        if not isinstance(node.func, ast.Name):
            self._err(node, "calls are by plain name only (f(a, b))")
        if node.keywords:
            self._err(node, "keyword arguments are not supported — C calls are positional")
        name = node.func.id
        if name in _XCORE_OPS:
            return self._xcore_call(node, name)
        if name == "ptr":
            self._err(node, "ptr() can only appear as `p = ptr(buf)` or `p = ptr(buf, k)`")
        if name == "range":
            self._err(node, "range() can only be the iterator of a for loop")
        args = []
        for a in node.args:
            if isinstance(a, ast.Starred):
                self._err(node, "*args is not supported")
            # a ParamTable channel argument -> its RF channel base (RF_CHi)
            if isinstance(a, ast.Name) and a.id in self.table_params:
                rt = self._resolve_table(a, a.id)
                args.append(ir.Const(rt.base, rt.cname))
                continue
            # table.pulses decays to (RF_CHi, table_symbol, count) (spec 02 §3.5)
            if (isinstance(a, ast.Attribute) and isinstance(a.value, ast.Name)
                    and a.value.id in self.table_params and a.attr == "pulses"):
                rt = self._resolve_table(a, a.value.id)
                args.append(ir.Const(rt.base, rt.cname))
                args.append(ir.TableRef(rt.name))
                args.append(ir.Const(rt.count))
                continue
            # an Array decays to a pointer
            if isinstance(a, ast.Name) and a.id in self.arrays:
                args.append(ir.ArrayRef(a.id))
                continue
            e, _ty = self._expr(a)  # int and ptr both pass by value
            args.append(e)
        return ir.Call(name, args)
