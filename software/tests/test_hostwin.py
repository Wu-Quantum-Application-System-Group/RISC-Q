"""W2 acceptance (specs/software/22 §4): an `Array(host=True)` output lands in the PS DDR4 result
buffer instead of the core's 16 KB unified RAM, and `rerun` returns it exactly as it returns a RAM
array. Deliberately a LIGHT co-sim gate — `k_echo`-class, seconds, pulse-free: it covers the code
path (kernel → C pointer into the window → the funnel → `read_host`), not the physics."""

import pytest

from riscq import run as rq
from riscq.lang import Array, compile_kernel, kernel
from riscq.lang.kernel import KernelCompileError
from tests.test_rerun import CountingDriver, k_echo

N = 8


@kernel
def k_both(offset: int, xs: Array, ram: Array, win: Array, n: int):
    """The same values into a RAM array and a host-window array. The kernel body is identical for
    both — `out[i] = ...` is a plain volatile store either way; only the binding differs."""
    for i in range(n):
        ram[i] = xs[i] + offset
        win[i] = xs[i] + offset


def _bind(m, **extra):
    return compile_kernel(k_both, m, xs=Array(N, input=True), ram=Array(N),
                          win=Array(N, host=True), **extra)


# ── host-pure: the placement is a compile-time decision, visible in the Program and the C ──

def test_host_array_has_no_ram_object(socmap):
    """A `host=True` array is a pointer into the window, not a `.bss` object: it carries a window
    offset on the Program and emits no array definition (so it costs zero RAM)."""
    prog = _bind(socmap)
    assert prog.host_arrays == {"win": (0, N)}, prog.host_arrays
    assert "win" in prog.arrays and "ram" in prog.arrays      # both are still results
    assert "RQ_HOSTWIN + 0" in prog.c_source
    assert f"int32_t win[{N}]" not in prog.c_source           # no RAM object
    assert f"int32_t ram[{N}]" in prog.c_source
    with pytest.raises(KeyError):                             # and no ELF symbol to read
        prog.var_addr("win")


def test_host_arrays_pack_from_zero_in_declaration_order(socmap):
    @kernel
    def k_two(a: Array, b: Array, n: int):
        for i in range(n):
            a[i] = i
            b[i] = i

    prog = compile_kernel(k_two, socmap, a=Array(3, host=True), b=Array(5, host=True))
    assert prog.host_arrays == {"a": (0, 3), "b": (12, 5)}, prog.host_arrays


def test_input_arrays_cannot_live_in_the_window():
    with pytest.raises(ValueError, match="write-only"):
        Array(4, input=True, host=True)


def test_a_window_array_is_not_host_writable(socmap):
    prog = _bind(socmap)
    with pytest.raises(ValueError, match="write-only host window"):
        rq.write_array(object(), socmap, 0, prog, "win", [1, 2, 3])


def test_a_window_array_can_exceed_the_whole_core_ram(socmap):
    """The point of the window. The same array without `host=True` overflows the 16 KB unified RAM
    and fails to link — that is the cap spec 08 §2.3 recorded, and this is it lifted."""
    @kernel
    def k_big(a: Array, n: int):
        for i in range(n):
            a[i] = i

    n = socmap.mem_bytes           # 4x the RAM in words
    prog = compile_kernel(k_big, socmap, a=Array(n, host=True))
    assert prog.host_arrays == {"a": (0, n)}
    with pytest.raises(RuntimeError, match="clang failed"):
        compile_kernel(k_big, socmap, a=Array(n))


def test_cal_raw_iq_captures_live_in_the_window(responder, socmap):
    """W4 cut-over: `riscq.cal`'s raw-IQ programs place their capture in the window, so the shot
    count is bounded by run time, not by the core's RAM. Compiled through the production runner
    (the responder stands in for the driver) so the array really is the one a cal would run."""
    import numpy as np

    from riscq.cal.experiment import Experiment
    from riscq.cal.measure import Measure
    from riscq.cal.sequence import Gate
    from tests.cal_fixtures import _cfg

    shots = 4000                   # 32 KB of IQ pairs — twice the whole core RAM
    from pathlib import Path

    cfgs = Path(__file__).resolve().parents[1] / "configs"
    r = responder(cfgs / "sim-2q.json")
    r.answer(lambda progs, params: {c: {"out": np.zeros(2 * shots, int)} for c in progs})
    Experiment(_cfg(socmap), [0], {0: [Gate("x90")]}, {0: ()}, (), Measure.raw(), shots,
               label="rawiq").run(r.drv)
    prog = r.setups[0][0]
    assert prog.host_arrays == {"out": (0, 2 * shots)}, prog.host_arrays
    with pytest.raises(KeyError):
        prog.var_addr("out")       # no RAM object at all


def test_oversize_window_arrays_are_refused(socmap):
    @kernel
    def k_big(a: Array, n: int):
        for i in range(n):
            a[i] = i

    with pytest.raises(KernelCompileError, match="host-window arrays need"):
        compile_kernel(k_big, socmap, a=Array(socmap.HOSTWIN_BYTES // 4 + 1, host=True))


# ── co-sim: the window really carries the results, run after run ──

@pytest.mark.cosim
def test_window_array_matches_the_ram_array(cosim):
    """One kernel writes both; `rerun` returns both and they are identical."""
    drv, m = cosim
    prog = _bind(m)
    rq.setup(drv, m, {0: prog})
    xs = list(range(1, N + 1))
    out = rq.rerun(drv, m, {0: prog}, params={0: {"offset": 10, "n": N}},
                   arrays={0: {"xs": xs}})[0]
    want = [x + 10 for x in xs]
    assert list(out["ram"]) == want, f"RAM array wrong: {list(out['ram'])}"
    assert list(out["win"]) == want, f"host-window array wrong: {list(out['win'])}"


@pytest.mark.cosim
def test_second_rerun_overwrites_in_place(cosim):
    """The window is not re-zeroed by `.bss` clearing (it is not RAM), so a second run must
    overwrite every element it reports — a stale value would show up here."""
    drv, m = cosim
    prog = _bind(m)
    rq.setup(drv, m, {0: prog})
    xs = list(range(1, N + 1))
    rq.rerun(drv, m, {0: prog}, params={0: {"offset": 10, "n": N}}, arrays={0: {"xs": xs}})
    out = rq.rerun(drv, m, {0: prog}, params={0: {"offset": 1000, "n": N}},
                   arrays={0: {"xs": xs}})[0]
    assert list(out["win"]) == [x + 1000 for x in xs], f"stale window data: {list(out['win'])}"


@pytest.mark.cosim
def test_per_core_slices_are_disjoint(cosim):
    """Both cores run the same program into the same window offsets; each must come back with its
    own data — that is the `core << 24` term of the funnel's address."""
    drv, m = cosim
    prog = _bind(m)
    progs = {0: prog, 1: prog}
    rq.setup(drv, m, progs)
    out = rq.rerun(drv, m, progs,
                   params={0: {"offset": 10, "n": N}, 1: {"offset": 20, "n": N}},
                   arrays={0: {"xs": [1] * N}, 1: {"xs": [2] * N}})
    assert list(out[0]["win"]) == [11] * N, f"core 0 slice: {list(out[0]['win'])}"
    assert list(out[1]["win"]) == [22] * N, f"core 1 slice: {list(out[1]['win'])}"


@pytest.mark.cosim
def test_seam_op_budget_grows_by_exactly_one_read(cosim):
    """A `host=True` array costs exactly ONE extra seam op per rerun — the `read_host` — over the
    same batch without it (spec 08 §7's budget is per-array, not per-byte)."""
    drv, m = cosim
    ram_only = compile_kernel(k_echo, m, xs=Array(N, input=True), out=Array(N))
    both = _bind(m)
    xs = list(range(N))

    rq.setup(drv, m, {0: ram_only})
    cd = CountingDriver(drv)
    cd.ops = 0
    rq.rerun(cd, m, {0: ram_only}, params={0: {"offset": 1, "n": N}}, arrays={0: {"xs": xs}})
    base_ops = cd.ops

    rq.setup(drv, m, {0: both})
    cd.ops = 0
    rq.rerun(cd, m, {0: both}, params={0: {"offset": 1, "n": N}}, arrays={0: {"xs": xs}})
    print(f"\n[budget] rerun {base_ops} ops without the window array, {cd.ops} with it")
    assert cd.ops == base_ops + 1, f"{cd.ops} ops vs {base_ops} + 1 expected"
