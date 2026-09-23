"""M2b host acceptance: the @kernel front end + C backend without a simulator — the fail-loud
catalogue (spec 02 §5), dead-branch ParamTable elimination, the D-RAM overflow link error,
#line-mapped gcc diagnostics, and the spec's rabi kernel on the ParamTable model."""

import inspect
import re
from pathlib import Path

import pytest

import riscq.build as build
from riscq.lang import Array, KernelCompileError, ParamTable, compile_kernel, kernel
from riscq.map import LEAD, READOUT_LEAD, SocMap, SocParams
from riscq.pulses import Pulse, envelopes, units

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
RELAX = 2500                # closure constant used by the rabi kernel
BAD_CONST = "not an int"    # closure name that must NOT fold


@pytest.fixture()
def m() -> SocMap:
    return SocMap(SocParams.load(CONFIGS / "sim-2q.json"))


@pytest.fixture(autouse=True)
def _tmp_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)


def _gate_table():
    return ParamTable(0, 50e6,
                      {"x90": Pulse(envelopes.gaussian(64, 3.0), amp=0.5, phase=0.3),
                       "x180": Pulse(envelopes.gaussian(64, 2.0), amp=1.0)})


def _ro_table():
    return ParamTable(1, -40e6, {"probe": Pulse(envelopes.square(24), amp=0.3)})


# ── 8. fail-loud catalogue (spec 02 §5): KernelCompileError with file:line + source ──

@kernel
def k_unsupported(out: Array):
    out[0] = (1, 2)


@kernel
def k_float(out: Array):
    out[0] = 1.5


@kernel
def k_unknown(out: Array):
    out[0] = undefined_name_xyz  # noqa: F821


@kernel
def k_nonint_fold(out: Array):
    out[0] = BAD_CONST


@kernel
def k_too_many(ro: ParamTable):
    init_pulse_params(ro.pulses)  # noqa: F821


@kernel
def k_bad_key(gate: ParamTable):
    play(gate, gate["nope"], 0)  # noqa: F821


@kernel
def k_runtime_key(gate: ParamTable, i: int):
    play(gate, gate[i], 0)  # noqa: F821


@kernel
def k_pulses_value(gate: ParamTable, out: Array):
    out[0] = gate.pulses  # noqa: F821


@kernel
def k_ptr_of_int(out: Array, n: int):
    p = ptr(n)  # noqa: F821
    p[0] = 1


@kernel
def k_ptr_where_int(out: Array):
    p = ptr(out)  # noqa: F821
    out[0] = p * 2


@kernel
def k_subscript_int(out: Array, n: int):
    out[0] = n[0]


@kernel
def k_const_oob(out: Array):
    out[9] = 1


@kernel
def k_reserved(__rq_bad: int):
    pass


OUT4 = dict(out=Array(4))
_TWO_RO = dict(tables=dict(ro=ParamTable(1, 40e6,
              {"a": Pulse(envelopes.square(8), amp=0.5),
               "b": Pulse(envelopes.square(8), amp=0.5)})))
_GATE = dict(tables=dict(gate=ParamTable(0, 50e6, {"x90": Pulse(envelopes.square(8), amp=0.5)})))
FAIL_CASES = [
    ("unsupported-node", k_unsupported, OUT4, r"unsupported expression"),
    ("float-literal", k_float, OUT4, r"float literal"),
    ("unknown-name", k_unknown, OUT4, r"unknown name"),
    ("non-int-fold", k_nonint_fold, OUT4, r"does not fold to an int"),
    ("too-many-pulses", k_too_many, _TWO_RO, r"only 1 slot"),
    ("undeclared-gate-key", k_bad_key, _GATE, r"no gate 'nope'"),
    ("non-literal-gate-key", k_runtime_key, _GATE, r"string-literal gate name"),
    ("pulses-as-bare-value", k_pulses_value, dict(out=Array(1), **_GATE),
     r"only valid as a call argument"),
    ("ptr-of-non-array", k_ptr_of_int, OUT4, r"ptr\(\) of a non-Array"),
    ("ptr-where-int", k_ptr_where_int, OUT4, r"a ptr where an int is expected"),
    ("subscript-plain-int", k_subscript_int, OUT4, r"subscripting a plain int"),
    ("const-index-oob", k_const_oob, OUT4, r"constant index 9 outside"),
    ("reserved-rq-param", k_reserved, {}, r"reserved '__rq_' prefix"),
]


@pytest.mark.parametrize("case", FAIL_CASES, ids=[c[0] for c in FAIL_CASES])
def test_fail_loud(case, m):
    _, k, kwargs, pattern = case
    with pytest.raises(KernelCompileError, match=pattern) as ei:
        compile_kernel(k, m, **kwargs)
    msg = str(ei.value)
    assert re.search(r"test_lang\.py:\d+", msg), f"no file:line in: {msg}"


# ── host-side ValueErrors when a ParamTable is built or bound (spec 02 §5) ──

def test_pulse_freq_must_match_table_carrier(m):
    with pytest.raises(ValueError, match="!= table carrier"):
        ParamTable(0, 50e6, {"x": Pulse(envelopes.square(8), amp=0.5, freq_hz=30e6)})


def test_unknown_channel_index(m):
    @kernel
    def k(bad: ParamTable):
        init_pulse_params(bad.pulses)  # noqa: F821

    table = ParamTable(5, 50e6, {"x": Pulse(envelopes.square(8), amp=0.5)})
    with pytest.raises(ValueError, match="unknown channel index"):
        compile_kernel(k, m, tables=dict(bad=table))


def test_two_tables_one_channel(m):
    @kernel
    def k(a: ParamTable, b: ParamTable):
        init_pulse_params(a.pulses)  # noqa: F821
        init_pulse_params(b.pulses)  # noqa: F821

    t0 = ParamTable(0, 50e6, {"x": Pulse(envelopes.square(8), amp=0.5)})
    t1 = ParamTable(0, 60e6, {"y": Pulse(envelopes.square(8), amp=0.5)})
    with pytest.raises(ValueError, match="both bind channel 0"):
        compile_kernel(k, m, tables=dict(a=t0, b=t1))


# ── 2. an Array exceeding D-RAM is a loud link error at compile_kernel time ──

def test_array_exceeding_dram_is_link_error(m):
    @kernel
    def big(buf: Array):
        buf[0] = 1

    with pytest.raises(RuntimeError, match="overflows RAM"):
        compile_kernel(big, m, buf=Array(8192))   # 32 KB > 16 KB RAM


# ── 5. a ParamTable used only in a dead branch needs no binding on that specialization ──

def test_dead_branch_table(m):
    @kernel
    def k(core: int, gate: ParamTable, extra: ParamTable, gap: int):
        init_pulse_params(gate.pulses)  # noqa: F821
        set_freq(gate, gate.freq)  # noqa: F821
        t = now() + LEAD  # noqa: F821
        play(gate, gate["x90"], t)  # noqa: F821
        if core == 1:
            play(extra, extra["y"], t + gap)  # noqa: F821
        wait_until(t + gap)  # noqa: F821

    gate_t = ParamTable(0, 50e6, {"x90": Pulse(envelopes.gaussian(64, 3.0), amp=0.5)})
    p0 = compile_kernel(k, m, tables=dict(gate=gate_t), core=0)
    assert set(p0.tables) == {"gate"}
    assert "extra" not in p0.tables and "extra" not in p0.c_source, "dead table leaked"
    with pytest.raises(KernelCompileError, match="'extra'"):
        compile_kernel(k, m, tables=dict(gate=gate_t), core=1)


# ── 6. undefined function: the gcc error points at the kernel's PYTHON file:line ──

def test_undefined_function_error_points_at_kernel_line(m):
    @kernel
    def k(out: Array):
        out[0] = definitely_not_an_op(3)  # noqa: F821

    src, first = inspect.getsourcelines(k.fn)
    line = first + next(i for i, s in enumerate(src) if "definitely_not_an_op" in s)
    with pytest.raises(RuntimeError) as ei:
        compile_kernel(k, m, out=Array(1))
    msg = str(ei.value)
    assert "test_lang.py" in msg and f":{line}:" in msg, f"gcc error not at python line: {msg}"
    assert "definitely_not_an_op" in msg


# ── 9. the spec's rabi kernel (02 §3.1) compiles; the generated C matches §4's worked example ──

def test_spec_rabi_kernel_compiles(m):
    @kernel
    def rabi(gate: ParamTable, ro: ParamTable, counts: Array, n_amps: int, amp0: int, damp: int,
             shots: int, t_gate: int, t_ro: int, dur_ro: int):
        init_pulse_params(gate.pulses)  # noqa: F821
        init_pulse_params(ro.pulses)  # noqa: F821
        set_freq(gate, gate.freq)  # noqa: F821
        set_freq(ro, ro.freq)  # noqa: F821
        amp = amp0
        for i in range(n_amps):
            set_amp(gate, gate["x90"], amp)  # noqa: F821
            n1 = 0
            for s in range(shots):
                t = now() + LEAD  # noqa: F821
                play(gate, gate["x90"], t)  # noqa: F821
                play(ro, ro["probe"], t + t_gate + t_ro)  # noqa: F821  firing the readout IS the readout
                wait_until(t + t_gate + t_ro + READOUT_LEAD)  # noqa: F821  past the window's opening
                n1 = n1 + read_res()  # noqa: F821
                wait_until(t + t_gate + t_ro + dur_ro + RELAX)  # noqa: F821
            counts[i] = n1
            amp = amp + damp

    gate_t, ro_t = _gate_table(), _ro_table()
    prog = compile_kernel(rabi, m, tables=dict(gate=gate_t, ro=ro_t), counts=Array(31))
    c = prog.c_source

    # params ABI: user params as RQ_PARAM .data globals, the sized (.bss) result array
    for p in ("n_amps", "amp0", "damp", "shots", "t_gate", "t_ro", "dur_ro"):
        assert f"volatile int32_t RQ_PARAM {p} = 0;" in c
        assert prog.params[p] is None
    assert "volatile int32_t counts[31];" in c
    assert prog.arrays == {"counts": 31}

    # each live table -> a .data struct rq_slot[]; NO compiler prologue (main opens with the body)
    assert "volatile struct rq_slot gate[2] RQ_PARAM = {0};" in c
    assert "volatile struct rq_slot ro[1] RQ_PARAM = {0};" in c
    assert "set_start(" not in c, "no implicit prologue — the kernel programs the table itself"

    # the four table surfaces fold: .pulses -> triple, table -> RF_CHi base, .freq -> code,
    # ["x90"] -> slot 0
    assert "init_pulse_params(RF_CH0, gate, 2);" in c
    assert "init_pulse_params(RF_CH1, ro, 1);" in c
    # .freq folds to the carrier as the SEATED register word (ParamTable.freq_code = freq_to_code,
    # spec 12) — emitted raw, a one-`lui` load
    assert f"set_freq(RF_CH0, {gate_t.freq_code(m)});" in c
    assert f"set_freq(RF_CH1, {ro_t.freq_code(m)});" in c
    assert "set_amp(RF_CH0, 0, amp);" in c   # a param name emits verbatim; the host seats its value
    assert "play(RF_CH0, 0, t);" in c and "play(RF_CH1, 0," in c

    # both loops; #line mapping back to this file
    assert c.count("for (") == 2
    assert "#line" in c and "test_lang.py" in c

    # per-slot design-time codes for riscq.run + the per-channel envelope images
    assert set(prog.tables) == {"gate", "ro"}
    # slot codes are stored PLAIN (load_tables seats them for the register, spec 12)
    assert prog.tables["gate"] == [
        (units._phase_code(0.3), units._amp_code(0.5), 0, 16),   # x90: gaussian(64) -> 16 lines
        (0, units._amp_code(1.0), 16, 16),                       # x180: distinct env at line 16
    ]
    assert prog.tables["ro"] == [(0, units._amp_code(0.3), 0, 24)]  # probe: square(24), 1 spl
    assert set(prog.envelopes) == {0, 1}   # gate + ro channel indices


# ── 10. channels by name, per core (specs/universal-control/01 P2) ──

def test_param_table_from_channel_info_compiles_the_same(m):
    @kernel
    def k(gate: ParamTable):
        init_pulse_params(gate.pulses)  # noqa: F821
        play(gate, gate["x90"], now() + LEAD)  # noqa: F821

    pulses = {"x90": Pulse(envelopes.square(16), amp=0.5)}
    by_index = compile_kernel(k, m, tables=dict(gate=ParamTable(0, 50e6, pulses)))
    by_name = compile_kernel(k, m, tables=dict(gate=ParamTable(m.channel_named("gate"), 50e6,
                                                               pulses)))
    assert by_name.c_source == by_index.c_source and "RF_CH0" in by_name.c_source
    assert by_name.image.data == by_index.image.data
    assert (by_name.core, by_index.core) == (0, 0)


def _hetero_map() -> SocMap:
    """A build whose two cores have DIFFERENT channel lists (tests/test_spec.py::_hetero)."""
    from riscq.spec import ChannelSpec, CoreSpec, SocSpec
    q = CoreSpec("q0", (ChannelSpec("gate", "pulse", 8, 1024, 4, dac=0),
                        ChannelSpec("ro", "pulse", 1, 1024, 16, dac=8, trace=True),
                        ChannelSpec("demod", "demod", 1, 1024, 4, adc=12)))
    p = CoreSpec("loader", (ChannelSpec("aom", "pulse", 8, 1024, 4, dac=3),), role="process")
    return SocMap(SocSpec("hetero", (q, p), 5e8))


def test_compile_for_another_core_uses_that_cores_channels():
    hm = _hetero_map()

    @kernel
    def k(aom: ParamTable):
        init_pulse_params(aom.pulses)  # noqa: F821
        play(aom, aom["sq"], now() + LEAD)  # noqa: F821

    table = ParamTable(hm.channel_named("aom", 1), 20e6,
                       {"sq": Pulse(envelopes.square(16), amp=0.5)})
    prog = compile_kernel(k, hm, tables=dict(aom=table), core=1)
    assert prog.core == 1
    header = hm.gen_header(1)
    assert "#define RQ_CH_AOM 0x10000" in header and "RQ_GATE" not in header

    # core 0's gate ChannelInfo is not this core's channel — fail loud, never silently retarget
    wrong = ParamTable(hm.channel_named("gate", 0), 20e6,
                       {"sq": Pulse(envelopes.square(16), amp=0.5)})
    with pytest.raises(ValueError, match="resolved on core 0"):
        compile_kernel(k, hm, tables=dict(aom=wrong), core=1)
