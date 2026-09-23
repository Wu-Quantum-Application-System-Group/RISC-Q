"""The run layer: pure functions over (Driver, SocMap) implementing the boot/run protocol.
Backend-agnostic — the same code drives co-sim and hardware."""

from __future__ import annotations

import time as _time

import numpy as np
import serpent

from riscq.build import Image, Program
from riscq.map import SocMap, pack16

STATUS_DONE_MASK = 0xFFFF0000
STATUS_DONE = 0xD04E0000
STATUS_RUNNING = 1
MAGIC = 0x52515121


def reset(drv, m: SocMap, on: bool) -> None:
    """Assert (on=True) / release the shared core reset. Powers up asserted."""
    drv.write32(m.host_ctrl + m.HOST_RESET, 1 if on else 0)


def set_time_offset(drv, m: SocMap, t: int) -> None:
    drv.write32(m.host_ctrl + m.HOST_TIME_OFF_LO, t & 0xFFFFFFFF)
    drv.write32(m.host_ctrl + m.HOST_TIME_OFF_HI, (t >> 32) & 0xFFFFFFFF)


def set_host_window(drv, m: SocMap, base: int, enable: bool = True) -> None:
    """Point the host-window funnel at the driver's result buffer (specs/software/22 §2.3): a core's
    store to `HOSTWIN + off` then lands at `base + (core << 24) + off`. Write it while the core reset
    is ASSERTED — the funnel is idle then, so the 40-bit base can never be seen torn. `enable` powers
    up low, so until this runs a window store stalls instead of writing DDR address 0."""
    drv.write32(m.host_ctrl + m.HOST_HOSTWIN_LO, base & 0xFFFFFFFF)
    drv.write32(m.host_ctrl + m.HOST_HOSTWIN_HI,
                ((base >> 32) & 0xFF) | (0x80000000 if enable else 0))


def load_program(drv, m: SocMap, core: int, image: Image) -> None:
    """Block-write the flat image into the core's RAM window (load address 0x80000000)."""
    drv.write_block(m.to_host_addr(core, image.entry), image.data)


def write_var(drv, m: SocMap, core: int, program: Program, name: str, value: int) -> None:
    """Write a named int32 global — before or during a run (the RAM host port is live)."""
    drv.write32(m.to_host_addr(core, program.var_addr(name)), int(value) & 0xFFFFFFFF)


def read_var(drv, m: SocMap, core: int, program: Program, name: str) -> int:
    """Read a named global; returns the raw unsigned 32-bit value."""
    return drv.read32(m.to_host_addr(core, program.var_addr(name)))


def read_array(drv, m: SocMap, core: int, program: Program, name: str) -> np.ndarray:
    """Fetch a named array global as int32 (element count from the ELF symbol size)."""
    addr, size = program.var_addr(name), program.var_size(name)
    if size < 4 or size % 4:
        raise ValueError(f"symbol {name!r} has size {size}, not an int32 array")
    return np.frombuffer(drv.read_block(m.to_host_addr(core, addr), size), dtype="<i4").copy()


def read_host_array(drv, m: SocMap, core: int, program: Program, name: str) -> np.ndarray:
    """Fetch a host-window array as int32 — the result never entered RAM, so it is read straight out
    of the driver's result buffer at `hostwin_offset(core) + <the array's window offset>`
    (specs/software/22 §2.6). Valid only after DONE (§2.4: the writes are posted, and the ordering
    contract is that the host reads from python after `poll_done`)."""
    off, count = program.host_arrays[name]
    buf = drv.read_host(m.hostwin_offset(core) + off, 4 * count)
    return np.frombuffer(buf, dtype="<i4").copy()


def write_array(drv, m: SocMap, core: int, program: Program, name: str, values) -> None:
    """Fill a named int32 array global — a host-preloaded input Array (`slots`/`times`) lives in
    .data (RQ_PARAM) so this pre-run write survives boot (spec 02 §3.1)."""
    if name in program.host_arrays:
        raise ValueError(f"array {name!r} lives in the write-only host window — it cannot be "
                         f"host-written (drop host=True to make it an input)")
    addr, size = program.var_addr(name), program.var_size(name)
    buf = np.asarray(values, dtype="<i4").tobytes()
    if len(buf) > size:
        raise ValueError(f"{len(buf)} B into array {name!r} of {size} B")
    drv.write_block(m.to_host_addr(core, addr), buf)


# rq_slot = { int32 phase, amp, env, dur } — the pulse-table entry layout (fw/riscq.h)
_SLOT_BYTES = 16
_SLOT_FIELD_OFF = {"phase": 0, "amp": 4, "env": 8, "dur": 12}


def load_tables(drv, m: SocMap, core: int, program: Program) -> None:
    """Fill each live ParamTable's .data `struct rq_slot[]` by symbol+offset with its compiled
    design-time codes — the kernel's init_pulse_params programs the hardware slots from it. .data
    (RQ_PARAM) survives start.S's .bss zeroing, like a param global (spec 02 §3.2). Every field is
    seated in data[31:16] (pack16, spec 12) so init_pulse_params' set_* write it raw."""
    for name, slot_codes in program.tables.items():
        buf = b"".join((pack16(c) & 0xFFFFFFFF).to_bytes(4, "little")
                       for slot in slot_codes for c in slot)
        drv.write_block(m.to_host_addr(core, program.var_addr(name)), buf)


def write_slot(drv, m: SocMap, core: int, program: Program, table: str, slot: int,
               field: str, value: int) -> None:
    """Retune one field of one ParamTable slot in .data by name (`gate[0].amp`) — no recompile,
    the next init_pulse_params picks it up (spec 02 §3.2). `value` is a plain code; it is seated in
    data[31:16] (pack16, spec 12) to match the compiled table fields."""
    addr = program.var_addr(table) + _SLOT_BYTES * slot + _SLOT_FIELD_OFF[field]
    drv.write32(m.to_host_addr(core, addr), pack16(value) & 0xFFFFFFFF)


def write_params(drv, m: SocMap, core: int, program: Program,
                 values: dict[str, int] | None = None) -> None:
    """Write the program's param globals: any compile-time auto value first, then the caller's
    `values` — an explicit value overrides an auto one (host-side retuning without recompiling).
    Kernel params are all user (auto=None) now; the pulse codes live in the .data tables
    (load_tables) instead of auto-param globals."""
    values = values or {}
    for name, auto in program.params.items():
        if auto is not None and name not in values:
            write_var(drv, m, core, program, name, auto)
    for name, value in values.items():
        write_var(drv, m, core, program, name, value)


def load_envelopes(drv, m: SocMap, core: int, program: Program) -> None:
    """Upload the program's envelope-RAM images (compile_kernel's allocator output)."""
    for channel, image in program.envelopes.items():
        for line0, lines in image:
            write_envelope(drv, m, core, channel, line0, lines)


def park_core(drv, m: SocMap, core: int) -> None:
    """Write a `j .` self-loop (0x6f) at the core's reset vector. Reset release boots ALL cores;
    a core whose RAM holds stale/garbage words would run wild (bus decode errors), so every core
    must hold either a program or this park word before the release."""
    drv.write32(m.imem(core), 0x6F)


def poll_done(drv, m: SocMap, cores, timeout: int = 2_000_000) -> int:
    """Wait until every core in `cores` has raised its hardware DONE bit; returns the DONE word.

    Completion is a register, not a memory word (specs/software/23): ONE read of the host control
    block covers the whole SoC, and it never touches a core's RAM — the port that instruction fetch
    shares with the host image-load master. `riscqReset` clears the bits, so `rerun` needs no
    clearing write and a stale DONE from the previous run cannot race this poll.

    `cores` is an iterable of core indices; parked cores never raise their bit, so pass only the
    cores that were given a program. `timeout` is in sim/host-clock cycles when the driver has sim
    extras, else read iterations. Loud TimeoutError naming the cores still missing."""
    cores = list(cores)
    mask = 0
    for core in cores:
        mask |= 1 << core
    addr = m.host_ctrl + m.HOST_DONE
    sim = getattr(drv, "sim", None)
    chunk = 20_000
    spent = 0
    word = drv.read32(addr)
    while word & mask != mask:
        if spent >= timeout:
            missing = sorted(c for c in cores if not (word >> c) & 1)
            raise TimeoutError(f"cores {missing} not DONE after {timeout} cycles "
                               f"(DONE word = {word:#010x})")
        if sim is not None:
            word = sim.poll_word(addr, not_equal=word, timeout_cycles=min(chunk, timeout - spent))
        else:
            _time.sleep(0.001)
            word = drv.read32(addr)
        spent += chunk if sim is not None else 1
    return word


def check_magic(drv, m: SocMap, core: int, program: Program) -> None:
    magic = read_var(drv, m, core, program, "__rq_magic")
    if magic != MAGIC:
        raise RuntimeError(f"core {core}: __rq_magic = {magic:#010x} != {MAGIC:#010x} "
                           f"— image not loaded / wrong map")


# ── remote batching: serpent-safe Program reduction for the server-side runner (spec 08 §5) ──
# Only JSON-ish primitives + `bytes` cross the Pyro5/serpent wire, and `bytes` arrive on the far
# side as a {'data': <base64>, 'encoding': 'base64'} dict — so a Program (bytes image + np.uint32
# envelope arrays) is reduced to bytes + int-lists here and rebuilt with `_prog_from_wire`.

def _wire_bytes(x) -> bytes:
    """Unwrap a bytes field that may have crossed serpent as a base64 dict."""
    return serpent.tobytes(x) if isinstance(x, dict) else bytes(x)


def _params_json(m: SocMap) -> str:
    return m.params.to_json()


def _prog_to_wire(prog: Program) -> dict:
    """Reduce a Program to serpent-safe primitives (bytes + dicts + int lists). Envelope lines
    (np.uint32 (n, wpl)) become bytes + shape; everything else is ints/strings."""
    img = prog.image
    return {
        "data": img.data,
        "entry": int(img.entry),
        "symbols": {name: [int(addr), int(size)] for name, (addr, size) in img.symbols.items()},
        "params": {name: (None if v is None else int(v)) for name, v in prog.params.items()},
        "arrays": {name: int(n) for name, n in prog.arrays.items()},
        "host_arrays": {name: [int(off), int(n)] for name, (off, n) in prog.host_arrays.items()},
        "tables": {name: [[int(c) for c in slot] for slot in slots]
                   for name, slots in prog.tables.items()},
        "envelopes": {int(chan): [_env_to_wire(line0, lines) for line0, lines in image]
                      for chan, image in prog.envelopes.items()},
    }


def _env_to_wire(line0, lines) -> list:
    arr = np.ascontiguousarray(lines, dtype="<u4")
    return [int(line0), arr.tobytes(), int(arr.shape[0]), int(arr.shape[1])]


def _prog_from_wire(wire: dict) -> Program:
    """Inverse of `_prog_to_wire`: rebuild a real Image + Program, unwrapping any serpent
    base64-dict byte fields (the image `data` and each envelope's packed lines)."""
    symbols = {name: (int(pair[0]), int(pair[1])) for name, pair in wire["symbols"].items()}
    image = Image(data=_wire_bytes(wire["data"]), symbols=symbols, entry=int(wire["entry"]))
    params = {name: (None if v is None else int(v)) for name, v in wire["params"].items()}
    arrays = {name: int(n) for name, n in wire["arrays"].items()}
    host_arrays = {name: (int(p[0]), int(p[1])) for name, p in wire.get("host_arrays", {}).items()}
    tables = {name: [tuple(int(c) for c in slot) for slot in slots]
              for name, slots in wire["tables"].items()}
    envelopes = {int(chan): [_env_from_wire(entry) for entry in entries]
                 for chan, entries in wire["envelopes"].items()}
    return Program(image, params=params, arrays=arrays, envelopes=envelopes, tables=tables,
                   host_arrays=host_arrays)


def _env_from_wire(entry) -> tuple:
    line0, data, n_lines, wpl = entry
    arr = np.frombuffer(_wire_bytes(data), dtype="<u4").reshape(int(n_lines), int(wpl))
    return int(line0), arr


def setup(drv, m: SocMap, progs: dict[int, Program]) -> None:
    """Once per session: hold reset, load each core's image + envelopes + tables, and park the
    unassigned cores. Leaves reset ASSERTED so the first `rerun` writes into a quiescent, loaded
    core — the image then stays put across reruns (spec 03 §2, 08 §4). With a `drv.remote` extras
    object present, the whole load runs server-side in one RPC (spec 08 §5)."""
    remote = getattr(drv, "remote", None)
    if remote is not None:
        remote.setup(_params_json(m), {core: _prog_to_wire(prog) for core, prog in progs.items()})
        return
    reset(drv, m, on=True)
    # point the funnel at this driver's result buffer while the reset is held (spec 22 §2.3). A
    # host-pure test double carries no buffer: leave the funnel disabled — correct, since without a
    # buffer there is nowhere for a window store to go — but refuse a program that needs one.
    base = getattr(drv, "host_base", None)
    if base is not None:
        set_host_window(drv, m, int(base))
    elif any(prog.host_arrays for prog in progs.values()):
        raise RuntimeError(f"{type(drv).__name__} has no `host_base`, but "
                           f"{sorted(n for p in progs.values() for n in p.host_arrays)} live in the "
                           f"host window — the driver must allocate/model a result buffer")
    for core, prog in progs.items():
        load_program(drv, m, core, prog.image)
        load_envelopes(drv, m, core, prog)
        load_tables(drv, m, core, prog)
    for core in range(len(m.params.cores)):   # un-programmed cores boot too: park them
        if core not in progs:
            park_core(drv, m, core)


def rerun(drv, m: SocMap, progs: dict[int, Program],
          params: dict[int, dict[str, int]] | None = None,
          arrays: dict[int, dict[str, object]] | None = None,
          results: list[str] | None = None,
          timeout: int = 2_000_000) -> dict[int, dict[str, np.ndarray]]:
    """Re-run an already-`setup` batch without any reload: check magic -> write params + host input
    arrays -> one reset release for all cores -> poll the hardware DONE word -> re-assert reset ->
    read results. Reuses the loaded image/envelopes/tables, so a whole sweep costs O(1) driver
    ops (spec 08 §4). Reset is held on entry (`setup` or the previous `rerun` left it asserted), and
    it is re-asserted again after the poll and before the results are read; the hardware DONE bits
    clear under that reset, so no stale-DONE clearing write is needed (specs/software/23). With a
    `drv.remote` extras object present, the whole batch (params + arrays in, poll, results out) runs
    server-side in one RPC (spec 08 §5)."""
    remote = getattr(drv, "remote", None)
    if remote is not None:
        raw = remote.rerun(list(progs), params or {}, arrays or {}, results, timeout)
        return {int(core): {name: np.frombuffer(buf, dtype="<i4").copy() for name, buf in d.items()}
                for core, d in raw.items()}
    params = params or {}
    arrays = arrays or {}
    for core, prog in progs.items():
        check_magic(drv, m, core, prog)                    # guard: image still loaded
        write_params(drv, m, core, prog, params.get(core, {}))
        for name, values in arrays.get(core, {}).items():
            write_array(drv, m, core, prog, name, values)
    reset(drv, m, on=False)
    poll_done(drv, m, progs, timeout=timeout)
    # Reset goes back on BEFORE the results are read, so host reads of a core's RAM never overlap live
    # instruction fetch on the shared RAM port (specs/software/23 §1.2 option B). The RAM keeps its
    # contents through reset, and window beats already accepted drain regardless.
    reset(drv, m, on=True)
    out = {core: {name: (read_host_array(drv, m, core, prog, name) if name in prog.host_arrays
                         else read_array(drv, m, core, prog, name))
                  for name in (list(prog.arrays) if results is None else results)}
           for core, prog in progs.items()}
    return out


def run(drv, m: SocMap, progs: dict[int, Program],
        params: dict[int, dict[str, int]] | None = None,
        arrays: dict[int, dict[str, object]] | None = None,
        results: list[str] | None = None,
        timeout: int = 2_000_000) -> dict[int, dict[str, np.ndarray]]:
    """The one-shot convenience: `setup` (load) then a single `rerun` (spec 08 §4)."""
    setup(drv, m, progs)
    return rerun(drv, m, progs, params, arrays, results, timeout)


def _env_window(m: SocMap, core: int, channel: int):
    """(host base, the ChannelInfo) of one core's envelope RAM for `channel` — the channel's index
    in THAT core's channel list, so a heterogeneous build lands on the right grid."""
    return m.env_base(channel, core), m.channel(channel, core)


def write_envelope(drv, m: SocMap, core: int, channel: int, line0: int, lines) -> None:
    """Upload packed envelope lines ((n_lines, words_per_line) uint32, from riscq.pulses) at
    RAM line `line0`, for the core's channel index `channel`. A gate line is 4 words at host
    line*16 + {0,4,8,12} — contiguous, so this is one block write; readout is 1 word per line at
    line*4. The envelope banks are host WRITE-ONLY (BramWriteFiber — the generator reads them
    internally), so there is no read_envelope; the write→DAC path is verified bit-exact in
    tests/test_pulse.test_dac_window_bit_exact_*."""
    lines = np.ascontiguousarray(lines, dtype="<u4")
    base, ch = _env_window(m, core, channel)
    if lines.ndim != 2 or lines.shape[1] != ch.samples_per_line:
        raise ValueError(f"channel {channel} lines must be (n, {ch.samples_per_line}) words, "
                         f"got shape {lines.shape}")
    if line0 < 0 or line0 + lines.shape[0] > ch.env_depth:
        raise ValueError(f"lines [{line0}, {line0 + lines.shape[0]}) outside envelope RAM "
                         f"depth {ch.env_depth}")
    drv.write_block(base + line0 * ch.line_bytes, lines.tobytes())


def read_robs(drv, m: SocMap, nbytes: int | None = None) -> np.ndarray:
    """Diagnostic fetch of the shared readout trace BRAM (int32 lanes)."""
    nbytes = m.rob_bytes if nbytes is None else nbytes
    return np.frombuffer(drv.read_block(m.robs(), nbytes), dtype="<i4").copy()
