"""The kernel-language IR (spec 02 §4): flat typed dataclasses, no behavior.

kernel.py produces it (validated, constants folded, dead branches eliminated, ParamTables
resolved to pulse-table slots + envelope addresses); backend_c.py consumes it. It is the stable
midpoint for tests and the seam for any future second backend. Statements carry the kernel's
python source line so the backend can emit #line directives.
"""

from __future__ import annotations

from dataclasses import dataclass, field

# ── expressions ──


@dataclass
class Const:
    """A folded int32 constant. cname carries a symbolic C name (a channel macro RF_CHi — riscq.h
    #defines it to the same value via riscq_map.h) purely for readable output."""

    value: int
    cname: str | None = None


@dataclass
class Var:
    """A local variable; ty is "int" (int32_t) or "ptr" (volatile int32_t *)."""

    name: str
    ty: str


@dataclass
class Param:
    """A volatile int32 global read: an unbound kernel parameter."""

    name: str


@dataclass
class ArrayRef:
    """An Array parameter, as an Index base or a call argument (C pointer decay)."""

    name: str


@dataclass
class TableRef:
    """A live ParamTable's .data struct, as a call argument (table.pulses decay): emits the
    bare symbol name — C decays the array to a `struct rq_slot *`."""

    name: str


@dataclass
class BinOp:
    """op in  + - * // % << >> & | ^  < <= > >= == !=  and or.
    // and % emit as C / and % (truncation toward zero — the documented deviation from
    python for negative operands); and/or emit as && / || (short-circuit, 0/1 result)."""

    op: str
    lhs: object
    rhs: object


@dataclass
class Not:
    """`not x` — C `!x`, yields 0/1."""

    operand: object


@dataclass
class Index:
    """buf[i] / p[k] — an element load, or the target of an element store."""

    base: object  # ArrayRef | Var(ty="ptr")
    index: object


@dataclass
class PtrOf:
    """ptr(buf) / ptr(buf, k) — only ever the RHS of an assignment to a ptr local."""

    array: str
    offset: object | None = None


@dataclass
class Call:
    """A by-name call. ParamTable arguments are already folded (channel base Const, slot-index
    Const, or the table.pulses (base, TableRef, count) triple), Array arguments to ArrayRefs;
    everything else is an int32/ptr expression."""

    name: str
    args: list


# ── statements (line = the kernel's python source line) ──


@dataclass
class Assign:
    target: object  # Var | Index
    value: object
    line: int


@dataclass
class For:
    """for var in range(start, stop, step) — emitted as the C for-loop of spec 02 §4."""

    var: Var
    start: object
    stop: object
    step: object
    body: list
    line: int


@dataclass
class While:
    cond: object
    body: list
    line: int


@dataclass
class If:
    """A RUNTIME if — constant-conditioned ifs were resolved by the front end."""

    cond: object
    then: list
    orelse: list
    line: int


@dataclass
class Break:
    line: int


@dataclass
class Continue:
    line: int


@dataclass
class CallStmt:
    call: Call
    line: int


# ── per-kernel container ──


@dataclass
class Table:
    """One live ParamTable (spec 02 §3.2): the backend materializes it as a host-retunable .data
    `volatile struct rq_slot name[count]`; riscq.run fills the struct from `slot_codes`. cname is
    the channel macro (RF_CHi) and carrier_code the folded `table.freq` — carried for the runner /
    tests (the body already holds them as folded Consts, so the backend needs only name+count)."""

    name: str
    cname: str
    count: int
    carrier_code: int
    slot_codes: list  # [(phase, amp, env, dur), ...] in slot order


@dataclass
class KernelIR:
    name: str
    src_file: str
    def_line: int
    params: list[str]  # unbound int params, signature order
    arrays: dict[str, int]  # Array name -> element count
    input_arrays: set[str]  # Array names that are host-preloaded inputs (.data, not .bss)
    host_arrays: dict  # Array name -> (byte offset in the core's host window, element count)
    locals: dict[str, str]  # local name -> "int" | "ptr"
    tables: list[Table]  # live ParamTables, resolution order
    body: list
    includes: list[str] = field(default_factory=list)
