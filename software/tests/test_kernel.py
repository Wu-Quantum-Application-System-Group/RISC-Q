"""M2 cosim acceptance: kernels compiled by riscq.lang executed on the verilator SoC —
language-construct goldens vs the python interpretation of the same kernel, the amplitude
staircase without recompiling, compile-time specialization + build-cache behavior, and
user-header ops."""

import numpy as np
import pytest

import riscq.build as build
from riscq import run as rq
from riscq.lang import Array, ParamTable, compile_kernel, kernel
from riscq.map import LEAD, pack16
from riscq.pulses import Pulse, envelopes, golden, units

pytestmark = pytest.mark.cosim


# ── python interpretation helpers: the golden is the ORIGINAL function run as python ──

class _Ptr:
    """Python stand-in for the kernel's ptr type (list + element offset)."""

    def __init__(self, buf, k=0):
        self.buf, self.k = buf, k

    def __getitem__(self, i):
        return self.buf[self.k + i]

    def __setitem__(self, i, v):
        self.buf[self.k + i] = v

    def __add__(self, n):
        return _Ptr(self.buf, self.k + n)

    def __sub__(self, n):
        return _Ptr(self.buf, self.k - n)

    def __lt__(self, o):
        return self.k < o.k

    def __le__(self, o):
        return self.k <= o.k

    def __gt__(self, o):
        return self.k > o.k

    def __ge__(self, o):
        return self.k >= o.k

    def __eq__(self, o):
        return isinstance(o, _Ptr) and self.k == o.k

    def __ne__(self, o):
        return not self == o


def ptr(buf, k=0):
    """The kernels' ptr() when run as plain python (the compiler treats ptr syntactically)."""
    return _Ptr(buf, k)


def wrap32(x):
    """int32 wrap for python goldens that exercise -fwrapv wraparound."""
    return ((np.asarray(x, dtype=np.int64) + 2**31) % 2**32 - 2**31).astype(np.int32)


def _run0(cosim, prog, params, timeout=2_000_000):
    drv, m = cosim
    return rq.run(drv, m, {0: prog}, params={0: params}, timeout=timeout)[0]


def _check_golden(cosim, k, arrays, params):
    """Compile+run `k` on core 0, run the same function as plain python; every Array must
    match the (int32-wrapped) python result."""
    _, m = cosim
    prog = compile_kernel(k, m, **{n: Array(sz) for n, sz in arrays.items()})
    res = _run0(cosim, prog, params)
    pyarrays = {n: [0] * sz for n, sz in arrays.items()}
    k.fn(**pyarrays, **params)
    for n in arrays:
        np.testing.assert_array_equal(
            res[n], wrap32(pyarrays[n]), err_msg=f"array {n!r} differs from the python golden")
    return res


def _windows(cap):
    """Contiguous nonzero-row runs of a DAC capture: [(start_row, n_rows)]."""
    nz = cap.any(axis=1)
    runs, start = [], None
    for i, v in enumerate(nz):
        if v and start is None:
            start = i
        if not v and start is not None:
            runs.append((start, i - start))
            start = None
    if start is not None:
        runs.append((start, len(nz) - start))
    return runs


# ── 1. language goldens ──

@kernel
def k_for(out: Array, n: int, a: int, b: int, s: int):
    acc = 0
    for i in range(n):                  # 1-arg range
        acc += i * i
    out[0] = acc
    acc = 0
    for i in range(a, b):               # 2-arg range
        acc += i
    out[1] = acc
    acc = 0
    for i in range(a, b, 3):            # 3-arg range, constant step
        acc += i
    out[2] = acc
    acc = 0
    for i in range(b, a, s):            # 3-arg range, runtime (negative) step
        acc += i
    out[3] = acc
    for i in range(n):
        out[4 + i] = i * 7              # computed-index array stores


def test_golden_for_and_array_stores(cosim):
    _check_golden(cosim, k_for, {"out": 10}, dict(n=6, a=3, b=12, s=-2))


@kernel
def k_while(out: Array, n: int):
    i = 0
    acc = 0
    while True:
        i += 1
        if i > n:
            break
        if i % 3 == 0:
            continue
        acc += i
    out[0] = acc
    j = n
    total = 0
    while j > 0:
        total += j
        j -= 2
    out[1] = total


def test_golden_while_break_continue(cosim):
    _check_golden(cosim, k_while, {"out": 2}, dict(n=17))


@kernel
def k_if(out: Array, lo: int, hi: int):
    for v in range(lo, hi):
        c = 0
        if v > 12:
            c = 3
        elif v > 6:
            c = 2
        elif v == 5:
            c = 9
        else:
            c = 1
        out[v - lo] = c


def test_golden_if_elif_else(cosim):
    _check_golden(cosim, k_if, {"out": 16}, dict(lo=0, hi=16))


@kernel
def k_arith(out: Array, a: int, b: int):
    out[0] = a + b
    out[1] = a - b
    out[2] = a * b
    out[3] = a // b
    out[4] = a % b
    out[5] = a << 3
    out[6] = a >> 2
    out[7] = a & b
    out[8] = a | b
    out[9] = a ^ b
    out[10] = (a > b) + 2 * (a >= b) + 4 * (a == b) + 8 * (a != b) + 16 * (a < b) + 32 * (a <= b)
    c = 0
    if a > 0 and b > 0:
        c = c + 1
    if a > 0 or b > 1000:
        c = c + 2
    if not a == b:
        c = c + 4
    out[11] = c
    out[12] = (a + 100000) * (b + 30000)    # wraps int32 (-fwrapv); golden is wrap32'd


def test_golden_arithmetic_and_boolops(cosim):
    _check_golden(cosim, k_arith, {"out": 13}, dict(a=97, b=13))


@kernel
def k_divmod(out: Array, a: int, b: int):
    out[0] = a // b
    out[1] = a % b


def test_divmod_truncates_toward_zero(cosim):
    """`//` and `%` compile to C / and % — truncation toward zero, the DOCUMENTED deviation
    from python's floor semantics for negative operands (python: -7 // 3 == -3, -7 % 3 == 2;
    the kernel gives -2 and -1). Asserted against the C values, not the python golden."""
    drv, m = cosim
    prog = compile_kernel(k_divmod, m, out=Array(2))
    # a/b are runtime params of ONE image, so the four sign combinations are `setup` once + four
    # `rerun`s, not four `run`s: the image reload dominates a run (~6.6k batches vs ~2k, 01 §4.5).
    rq.setup(drv, m, {0: prog})

    def quotrem(a, b):
        return list(rq.rerun(drv, m, {0: prog}, params={0: dict(a=a, b=b)},
                             timeout=2_000_000)[0]["out"])

    assert quotrem(-7, 3) == [-2, -1]
    assert quotrem(7, -3) == [-2, 1]
    assert quotrem(-7, -3) == [2, -1]
    assert quotrem(7, 3) == [2, 1]


@kernel
def k_ptrwalk(mat: Array, out: Array, nrows: int, ncols: int):
    p = ptr(mat)
    for i in range(nrows * ncols):      # fill through a walking pointer
        p[0] = i * 3 + 1
        p += 1
    q = ptr(mat)
    for r in range(nrows):              # 2D-style row walk: p += ncols per row
        acc = 0
        for c in range(ncols):
            acc += q[c]
        out[r] = acc
        q += ncols
    s = ptr(mat, 0)
    e = ptr(mat, nrows * ncols)         # one-past-end pointer
    odd = 0
    while s < e:                        # pointer-pointer comparison
        odd += s[0] & 1
        s += 1
    out[nrows] = odd


def test_golden_pointer_walk(cosim):
    _check_golden(cosim, k_ptrwalk, {"mat": 12, "out": 4}, dict(nrows=3, ncols=4))


# ── 3. amplitude staircase: per-step DAC amplitudes, rerun without recompiling ──

@kernel
def k_staircase(gate: ParamTable, ts: Array, n: int, a0: int, da: int, gap: int):
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    for i in range(n):
        set_amp(gate, gate["x90"], a0 + i * da)  # noqa: F821
        t = now() + LEAD  # noqa: F821
        ts[i] = t
        play(gate, gate["x90"], t)  # noqa: F821
        wait_until(t + gate["x90"].dur + gap)  # noqa: F821


def test_amplitude_staircase_no_recompile(cosim):
    drv, m = cosim
    pulse = Pulse(envelopes.square(64), freq_hz=3e6, amp=0.5)
    gate = ParamTable(0, 3e6, {"x90": pulse})
    prog = compile_kernel(k_staircase, m, tables=dict(gate=gate), ts=Array(8))
    lines = pulse.packed_lines(m, 0)
    dur = len(lines)
    f, ph = pulse.freq_code(m), units._phase_code(pulse.phase)

    def once(n, a0, da, gap=48, load=True):
        rq.reset(drv, m, on=True)
        if load:                    # the image/envelopes/tables are not altered by a run, so the
            rq.load_program(drv, m, 0, prog.image)     # second call rewrites ONLY params — which
            rq.load_envelopes(drv, m, 0, prog)         # is exactly the claim being made
            rq.load_tables(drv, m, 0, prog)
            rq.park_core(drv, m, 1)
        rq.check_magic(drv, m, 0, prog)
        # a0/da are amp codes accumulated on-core (a0 + i·da); seat them so the sum lands in
        # data[31:16] (spec 12) — the seated pair accumulates in the seated domain.
        rq.write_params(drv, m, 0, prog, dict(n=n, a0=pack16(a0), da=pack16(da), gap=gap))
        # sized capture (01 §3.3): boot + preamble (~500 batches) then n steps of LEAD + dur + gap
        handle = drv.sim.dac_capture_arm(m.gate_dac(0), 1000 + n * (LEAD + dur + gap))
        rq.reset(drv, m, on=False)
        rq.poll_done(drv, m, [0], timeout=1_000_000)
        ts = [int(t) for t in rq.read_array(drv, m, 0, prog, "ts")[:n]]
        rq.reset(drv, m, on=True)
        t0, cap = drv.sim.dac_capture_get(handle)
        outside = np.ones(len(cap), dtype=bool)
        for i, t in enumerate(ts):
            idx = t - t0
            assert 0 <= idx and idx + dur <= len(cap), f"step {i} window outside the capture"
            gold = golden.pulse_window(lines, a0 + i * da, f, ph, t, dur)
            np.testing.assert_array_equal(cap[idx:idx + dur], gold,
                                          err_msg=f"step {i} (amp {a0 + i * da}) not exact")
            outside[idx:idx + dur] = False
        assert not cap[outside].any(), "DAC nonzero outside the staircase windows"

    runs = build.CC_RUNS
    once(4, 6000, 2500)
    assert build.CC_RUNS == runs, "first staircase run must not recompile"
    once(3, 12000, -3000, load=False)   # rewrite a0/da/n via write_params, rerun — no reload
    assert build.CC_RUNS == runs, "param rewrite must not recompile"


# ── 4. specialization: compile-time `if core == …` + build-cache dedup ──

@kernel
def k_spec(core: int, gate: ParamTable, gap: int):
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    play(gate, gate["x90"], t)  # noqa: F821
    if core == 1:
        play(gate, gate["x90"], t + gate["x90"].dur + gap)  # noqa: F821
    wait_until(t + 2 * gate["x90"].dur + 2 * gap)  # noqa: F821


@kernel
def k_nobranch(core: int, out: Array, x: int):
    out[0] = x + 1


def test_specialization_and_build_cache(cosim, tmp_path, monkeypatch):
    drv, m = cosim
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)   # fresh cache for exact gcc counts
    pulse = Pulse(envelopes.square(64), freq_hz=5e6, amp=0.4)
    gate = ParamTable(0, 5e6, {"x90": pulse})

    runs = build.CC_RUNS
    p0 = compile_kernel(k_spec, m, tables=dict(gate=gate), core=0)
    p1 = compile_kernel(k_spec, m, tables=dict(gate=gate), core=1)
    assert build.CC_RUNS == runs + 2, "core-branching kernel: one compiler run per specialization"
    assert p0.image.data != p1.image.data

    runs = build.CC_RUNS
    q0 = compile_kernel(k_nobranch, m, out=Array(1), core=0)
    q1 = compile_kernel(k_nobranch, m, out=Array(1), core=1)
    assert build.CC_RUNS == runs + 1, "non-branching binding must hit the build cache"
    assert q0.image.data == q1.image.data

    # run the two specializations on the two sim cores: the DAC streams diverge as coded
    gap = 40
    rq.reset(drv, m, on=True)
    for core, prog in ((0, p0), (1, p1)):
        rq.load_program(drv, m, core, prog.image)
        rq.check_magic(drv, m, core, prog)
        rq.load_envelopes(drv, m, core, prog)
        rq.load_tables(drv, m, core, prog)
        rq.write_params(drv, m, core, prog, dict(gap=gap))
    h0 = drv.sim.dac_capture_arm(m.gate_dac(0), 2400)
    h1 = drv.sim.dac_capture_arm(m.gate_dac(1), 2400)
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0, 1], timeout=1_000_000)
    rq.reset(drv, m, on=True)
    _, cap0 = drv.sim.dac_capture_get(h0)
    _, cap1 = drv.sim.dac_capture_get(h1)

    dur = len(pulse.packed_lines(m, 0))
    w0, w1 = _windows(cap0), _windows(cap1)
    assert [n for _, n in w0] == [dur], f"core 0: expected one {dur}-batch window, got {w0}"
    assert [n for _, n in w1] == [dur, dur], f"core 1: expected two windows, got {w1}"
    assert w1[1][0] - w1[0][0] == dur + gap, f"core 1 window spacing wrong: {w1}"


# ── 7. user-header ops (include=) are callable by name ──

def test_user_header_op(cosim, tmp_path):
    _, m = cosim
    hdr = tmp_path / "my_ops.h"
    hdr.write_text("static inline int32_t my_double(int32_t x) { return x << 1; }\n")

    @kernel
    def k(out: Array, y: int):
        out[0] = my_double(y)  # noqa: F821
        out[1] = my_double(y + 3)  # noqa: F821

    prog = compile_kernel(k, m, include=[hdr], out=Array(2))
    assert '#include "my_ops.h"' in prog.c_source
    res = _run0(cosim, prog, dict(y=21))
    assert list(res["out"]) == [42, 48]


# ── M2b: retune one gate[k].amp by name, zero recompiles (spec 02 §3.2) ──

@kernel
def k_fire(gate: ParamTable, ts: Array, gap: int):
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    ts[0] = t
    play(gate, gate["x90"], t)  # noqa: F821
    wait_until(t + gate["x90"].dur + gap)  # noqa: F821


def test_retune_slot_amp_no_recompile(cosim):
    drv, m = cosim
    pulse = Pulse(envelopes.square(64), freq_hz=3e6, amp=0.5)
    gate = ParamTable(0, 3e6, {"x90": pulse})
    prog = compile_kernel(k_fire, m, tables=dict(gate=gate), ts=Array(1))
    lines = pulse.packed_lines(m, 0)
    dur, f, ph = len(lines), pulse.freq_code(m), units._phase_code(pulse.phase)

    def once(amp_override):
        rq.reset(drv, m, on=True)
        rq.load_program(drv, m, 0, prog.image)
        rq.check_magic(drv, m, 0, prog)
        rq.load_envelopes(drv, m, 0, prog)
        rq.load_tables(drv, m, 0, prog)                 # design codes into the .data table
        if amp_override is not None:                    # then retune slot 0 amp by name
            rq.write_slot(drv, m, 0, prog, "gate", 0, "amp", amp_override)
        rq.write_params(drv, m, 0, prog, dict(gap=48))
        rq.park_core(drv, m, 1)
        handle = drv.sim.dac_capture_arm(m.gate_dac(0), 2400)
        rq.reset(drv, m, on=False)
        rq.poll_done(drv, m, [0], timeout=1_000_000)
        t_fire = int(rq.read_array(drv, m, 0, prog, "ts")[0])
        rq.reset(drv, m, on=True)
        t0, cap = drv.sim.dac_capture_get(handle)
        return t_fire, t0, cap

    runs = build.CC_RUNS
    for amp in (None, units._amp_code(0.25)):         # design 0.5, then retuned 0.25
        want = units._amp_code(0.5) if amp is None else amp
        tf, t0, cap = once(amp)
        idx = tf - t0
        gold = golden.pulse_window(lines, want, f, ph, tf, dur)
        np.testing.assert_array_equal(cap[idx:idx + dur], gold,
                                      err_msg=f"amp {want} window not bit-exact")
    assert build.CC_RUNS == runs, "retuning gate[0].amp by name must not recompile"


# ── M2b: a host-preloaded slots (.data) circuit plays the expected DAC sequence ──

@kernel
def k_circuit(gate: ParamTable, slots: Array, ts: Array, n: int, spacing: int):
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    for i in range(n):
        t = now() + LEAD  # noqa: F821
        ts[i] = t
        play(gate, slots[i], t)  # noqa: F821  (runtime slot from a host-filled input array)
        wait_until(t + spacing)  # noqa: F821


def test_data_driven_slots_circuit(cosim):
    drv, m = cosim
    x90 = Pulse(envelopes.square(48), freq_hz=4e6, amp=0.5)
    x180 = Pulse(envelopes.gaussian(64, 3.0), freq_hz=4e6, amp=0.9)
    gate = ParamTable(0, 4e6, {"x90": x90, "x180": x180})
    seq = ["x90", "x180", "x90"]
    n = len(seq)
    prog = compile_kernel(k_circuit, m, tables=dict(gate=gate),
                          slots=Array(n, input=True), ts=Array(n))
    f = x90.freq_code(m)          # both slots share the table's 4 MHz carrier

    rq.reset(drv, m, on=True)
    rq.load_program(drv, m, 0, prog.image)
    rq.check_magic(drv, m, 0, prog)
    rq.load_envelopes(drv, m, 0, prog)
    rq.load_tables(drv, m, 0, prog)
    rq.write_array(drv, m, 0, prog, "slots", [gate.slot_of(s) for s in seq])
    rq.write_params(drv, m, 0, prog, dict(n=n, spacing=64))
    rq.park_core(drv, m, 1)
    handle = drv.sim.dac_capture_arm(m.gate_dac(0), 3600)
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0], timeout=1_000_000)
    ts = [int(t) for t in rq.read_array(drv, m, 0, prog, "ts")[:n]]
    rq.reset(drv, m, on=True)
    t0, cap = drv.sim.dac_capture_get(handle)

    outside = np.ones(len(cap), dtype=bool)
    for i, name in enumerate(seq):
        p = gate.pulses[name]
        lines = p.packed_lines(m, 0)
        dur, idx = len(lines), ts[i] - t0
        assert 0 <= idx and idx + dur <= len(cap), f"step {i} window outside the capture"
        gold = golden.pulse_window(lines, units._amp_code(p.amp), f,
                                   units._phase_code(p.phase), ts[i], dur)
        np.testing.assert_array_equal(cap[idx:idx + dur], gold,
                                      err_msg=f"step {i} ({name}) not bit-exact")
        outside[idx:idx + dur] = False
    assert not cap[outside].any(), "DAC nonzero outside the scheduled slot windows"
