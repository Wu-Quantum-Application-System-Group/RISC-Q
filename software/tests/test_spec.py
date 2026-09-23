"""SocSpec: the channel-list build description (specs/universal-control/01 P0). The legacy
SocParams JSON converts to it, the canonical form round-trips, heterogeneous cores derive a host
map with the same stride algebra, and the uniform legacy views refuse non-uniform builds."""

import json
from pathlib import Path

import pytest

from riscq.map import SocMap
from riscq.spec import ChannelSpec, CoreSpec, SocSpec

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


def test_legacy_json_converts_to_gate_ro_demod():
    s = SocSpec.load(CONFIGS / "sim-2q.json")
    assert [c.name for c in s.cores] == ["q0", "q1"]
    assert [ch.name for ch in s.cores[0].channels] == ["gate", "ro", "demod"]
    gate, ro, demod = s.cores[1].channels
    assert (gate.kind, gate.slots, gate.interp, gate.dac, gate.trace) == ("pulse", 8, 4, 1, False)
    assert (ro.kind, ro.slots, ro.interp, ro.dac, ro.trace) == ("pulse", 1, 16, 14, True)
    assert (demod.kind, demod.slots, demod.interp, demod.adc) == ("demod", 1, 4, 0)
    assert (gate.lanes, demod.lanes) == (16, 4)
    assert (gate.samples_per_line, gate.line_bytes, gate.env_bytes) == (4, 16, 16 * 1024)
    assert (demod.samples_per_line, demod.line_bytes) == (1, 4)
    # the uniform legacy views every consumer still reads
    assert (s.qubit_num, s.mem_depth, s.env_depth, s.gate_pulse_num) == (2, 4096, 1024, 8)
    assert (s.gate_interp, s.readout_interp, s.demod_interp) == (4, 16, 4)
    assert s.with_mul == json.loads((CONFIGS / "sim-2q.json").read_text())["with_mul"]


def test_canonical_json_round_trips():
    s = SocSpec.load(CONFIGS / "sim-2q1c.json")
    back = SocSpec.from_json(s.to_json())
    assert back == s
    assert "cores" in json.loads(s.to_json())
    assert SocMap(back).entries() == SocMap(s).entries()


def _hetero() -> SocSpec:
    q = CoreSpec("q0", (
        ChannelSpec("qubit", "pulse", 32, 4096, 4, dac=0),
        ChannelSpec("f0g1", "pulse", 8, 4096, 4, dac=1),
        ChannelSpec("flux", "pulse", 8, 4096, 4, dac=2),
        ChannelSpec("ro", "pulse", 1, 4096, 16, dac=8, trace=True),
        ChannelSpec("demod", "demod", 1, 4096, 4, adc=12)))
    p = CoreSpec("loader", (ChannelSpec("aom", "pulse", 8, 1024, 4, dac=3),), role="process")
    return SocSpec("hetero", (q, p), 5e8)


def test_channel_list_form_parses():
    s = SocSpec.from_json(_hetero().to_json())
    assert s == _hetero()
    assert s.max_channels == 5
    assert s.cores[1].role == "process"
    assert s.dac_map == ((0, 1, 2, 8), (3,))
    with pytest.raises(ValueError, match="no ADC-bound channel"):
        s.adc_map   # the process core has no ADC-bound channel


def test_heterogeneous_host_map_uses_the_same_algebra():
    m = SocMap(_hetero())
    # region 0 = core RAMs, regions 1..5 = channel slots 0..4, then robs, host ctrl
    assert m.n_slots == 5
    assert m.slot_strides == [0x10000, 0x10000, 0x10000, 0x4000, 0x4000]   # widest bank per slot
    assert m.region_size == 0x20000                                          # pow2ceil(0x10000 * 2)
    assert m.slot_bases == [k * 0x20000 for k in range(1, 6)]
    assert (m.rob_base, m.host_ctrl) == (6 * 0x20000, 7 * 0x20000)
    assert m.env_base(0, 1) == 0x20000 + 0x10000      # the loader's aom bank in slot 0
    with pytest.raises(ValueError, match="unknown channel index"):
        m.env_base(1, 1)                              # the loader has one channel
    assert [c.base for c in m.channels(0)] == [0x10000 + k * 0x10000 for k in range(5)]
    assert m.put_addr_width(0) == 28 and m.put_addr_width(1) == 28   # the put window, fixed
    assert m.dac_of(0, 1) == 1 and m.adc_of(0) == 12
    kinds = [e.kind for e in m.entries()]
    assert kinds.count("env_f0g1") == 1 and kinds.count("env_aom") == 1
    h = m.gen_header(core=0)
    assert "#define RQ_CH_F0G1 0x20000" in h and "RQ_GATE" not in h
    assert "#define RQ_CH_AOM 0x10000" in m.gen_header(core=1)


def test_uniform_views_refuse_non_uniform_builds():
    s = _hetero()
    with pytest.raises(ValueError, match="differs across"):
        s.env_depth
    with pytest.raises(KeyError):
        s.gate_pulse_num   # no channel named gate on this build


def test_channel_spec_validation():
    with pytest.raises(ValueError, match="unknown kind"):
        ChannelSpec("x", "laser", 1, 1024, 4, dac=0)
    with pytest.raises(ValueError, match="must divide"):
        ChannelSpec("x", "pulse", 1, 1024, 3, dac=0)
    with pytest.raises(ValueError, match="names a dac"):
        ChannelSpec("x", "pulse", 1, 1024, 4, adc=0)
    with pytest.raises(ValueError, match="names an adc"):
        ChannelSpec("x", "demod", 1, 1024, 4, dac=0)
    with pytest.raises(ValueError, match="duplicate channel"):
        CoreSpec("c", (ChannelSpec("a", "pulse", 1, 8, 4, dac=0), ChannelSpec("a", "pulse", 1, 8, 4, dac=1)))
    with pytest.raises(ValueError, match="outside dac_num"):
        SocSpec("s", (CoreSpec("c", (ChannelSpec("a", "pulse", 1, 8, 4, dac=16),)),), 5e8)
