"""Host-tier checks of the multi-channel pieces (universal-control/01 P3 / P4): the three-slot
flat-top builder, the `lines/` resolver, and the map's sink table."""

from pathlib import Path

import numpy as np
import pytest

from riscq.cal import base
from riscq.cal.config import Config
from riscq.map import SocMap
from riscq.lang import Array, DioTable, ParamTable, compile_kernel, kernel
from riscq.pulses import flat_top
from riscq.spec import ChannelSpec, SocSpec

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


@pytest.fixture(scope="module")
def mm():
    return SocMap(SocSpec.load(CONFIGS / "sim-mm.json"))


def test_flat_top_splits_the_flat_into_pieces(mm):
    ch = mm.channel_named("f0g1", 0)               # env_depth 1024, 4 samples/line -> pieces <= 512 lines
    ft = flat_top(ch, ramp_batches=16, flat_batches=1000, amp=0.5)
    assert ft.reps == 2 and len(ft.body.env) == 500 * 4
    assert ft.flat_batches(ch) == 1000 and ft.dur_batches(ch) == 16 + 1000 + 16
    assert len(ft.envelope()) == (16 + 1000 + 16) * 4
    assert ft.up.env[0] < ft.up.env[-1] and ft.down.env[0] > ft.down.env[-1]   # rise then fall
    assert np.all(ft.body.env == ft.body.env[0])                               # flat
    assert all(p.amp == 0.5 for p in ft.pulses().values())
    short = flat_top(ch, ramp_batches=4, flat_batches=10, amp=1.0)
    assert short.reps == 1 and short.flat_batches(ch) == 10
    with pytest.raises(ValueError):
        flat_top(ch, ramp_batches=0, flat_batches=10, amp=1.0)


def test_line_resolves_the_qubit_and_named_lines(mm):
    cfg = Config({"lines": {"f0g1": {"core": 0}, "flux_lo": {"core": 0, "channel": "flux"},
                            "aux": {"core": 1, "channel": "gate"}}})
    assert base.line(cfg, 1, "qubit", mm) == mm.channel_named("gate", 1)
    assert base.line(cfg, 0, "f0g1", mm).name == "f0g1"
    assert base.line(cfg, 0, "flux_lo", mm) == mm.channel_named("flux", 0)
    assert base.line(cfg, 0, "aux", mm) == mm.channel_named("gate", 1)
    with pytest.raises(KeyError, match="no line"):
        base.line(cfg, 0, "manipulate", mm)


def test_sink_table_and_header(mm):
    assert mm.sinks(0)[:1] == [("demod", "result", 0x4200)]     # then the cross-core inbox registers
    assert mm.sinks(1)[:1] == [("demod", "result", 0x4200)]
    assert [s[0] for s in mm.sinks(0)[1:]] == ["board", "release", "mbox0", "mbox1"]
    assert mm.CTRL_RES == mm.sinks(0)[0][2]
    h = mm.gen_header(0)
    assert "#define RQ_SINK_DEMOD 0x4200" in h
    assert "#define RQ_CH_F0G1 0x20000" in h and "#define RQ_CH_FLUX 0x30000" in h
    assert mm.put_addr_width(0) == 28 and mm.put_addr_width(1) == 28   # the put window, fixed


@pytest.fixture(scope="module")
def dio():
    return SocMap(SocSpec.load(CONFIGS / "sim-dio.json"))


def test_dio_channel_in_spec_and_map(dio):
    ttl = dio.channel_named("ttl", 0)
    assert (ttl.kind, ttl.index, ttl.base, ttl.env_depth, ttl.dac, ttl.adc) == ("dio", 3, 0x40000, 0, None, None)
    assert dio.sinks(0)[:2] == [("demod", "result", 0x4200), ("ttl", "fifo", 0x4220)]
    assert dio.slot_strides[3] == 1 and dio.put_addr_width(0) == 28       # a bank-less hole; 4 channels
    assert not [e for e in dio.entries() if "ttl" in e.name]              # no host window for it
    h = dio.gen_header(0)
    assert "#define RQ_CH_TTL 0x40000" in h and "#define RQ_SINK_TTL 0x4220" in h
    with pytest.raises(ValueError, match="neither a dac nor an adc"):
        ChannelSpec("x", "dio", 4, 0, 1, dac=0)
    with pytest.raises(ValueError, match="env_depth is 0 exactly"):
        ChannelSpec("x", "dio", 4, 16, 1)


def test_dio_table_compiles_to_the_generic_ops(dio):
    @kernel
    def k(ttl: ParamTable, out: Array, lead: int):
        init_pulse_params(ttl.pulses)
        t = now() + lead
        play(ttl, ttl["on"], t)
        fire(ttl, ttl["off"])
        w = pop_event(ttl.sink)
        out[0] = w
        out[1] = event_time(ttl.sink)
        out[2] = event_seq(ttl.sink)

    table = DioTable(dio.channel_named("ttl", 0), {"on": (0x1, 0x1, 10), "off": (0x1, 0x0, 10)})
    prog = compile_kernel(k, dio, tables={"ttl": table}, out=Array(3), lead=96)
    src = prog.c_source
    assert "init_pulse_params(RF_CH3, ttl, 2);" in src
    assert "play(RF_CH3, 0, t);" in src and "fire(RF_CH3, 1);" in src
    assert "pop_event(RQ_SINK_TTL)" in src and "event_time(RQ_SINK_TTL)" in src
    assert prog.tables["ttl"] == [(1, 1, 0, 10), (1, 0, 0, 10)]
    assert prog.envelopes == {}                                            # no bank, no image
    with pytest.raises(Exception, match="not dio"):
        compile_kernel(k, dio, tables={"ttl": DioTable(dio.channel_named("gate", 0), {"on": (1, 1, 2)})},
                       out=Array(3), lead=96)
    with pytest.raises(ValueError, match="16-bit"):
        DioTable(dio.channel_named("ttl", 0), {"on": (0x10000, 1, 2)})
