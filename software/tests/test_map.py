"""SocMap derivations reproduce the elaboration's known numbers (independent literals,
verified against the SpinalHDL SocMemoryMap), and the generators evaluate them."""

from pathlib import Path

from riscq.map import MEM_BASE, SocMap, SocParams, pack16

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


def _map(name: str) -> SocMap:
    return SocMap(SocParams.load(CONFIGS / f"{name}.json"))


def test_pack16_seats_field_at_bit16():
    """pack16 seats a 16-bit code in data[31:16] (spec 12) — the software-side RQ_PACK16, returning
    the SIGNED int32 word a register holds. The low 16 bits are zero (single-`lui` constant / free
    Q16 fraction); a code with bit 15 set seats to a negative word (passes the kernel int32 check)."""
    assert pack16(0) == 0
    assert pack16(1) == 0x00010000
    assert pack16(0x7FFF) == 0x7FFF0000
    assert pack16(-0x8000) == -(1 << 31)                       # bit-15 code -> most-negative int32
    assert pack16(-1) == -0x10000                              # 0xFFFF0000 as signed
    for code in (0, 1, 1234, -1, -2048, 0x7FFF, -0x8000, 0x8000):
        w = pack16(code)
        assert -(1 << 31) <= w <= (1 << 31) - 1 and w & 0xFFFF == 0   # signed int32, low half free
        assert (w >> 16) & 0xFFFF == code & 0xFFFF             # field == code mod 2^16
    assert pack16(0x12345) == 0x23450000                       # wider codes fold mod 2^16


def test_zcu216_14q_numbers():
    m = _map("zcu216-14q")
    assert m.core_stride == 0x10000       # 64 KB
    assert m.pulse_stride == 0x4000       # 16 KB (128-bit gate line x 1024)
    assert m.ro_env_stride == 0x1000      # 4 KB (32-bit ro line x 1024)
    assert m.demod_env_stride == 0x1000   # 4 KB (32-bit demod-carrier line x 1024)
    assert m.rob_stride == 0x8000         # 32 KB (2 x pow2ceil(128*1024/8), rob_depth 1024)
    # pow2ceil(0x10000 * 14 = 0xE0000) = 0x100000 (1 MB) — RTL-verified; CONTRACT.md's 2 MB
    # was an arithmetic slip.
    assert m.region_size == 0x100000
    assert m.core_mem_base == 0x0
    assert m.pulse_mem_base == 0x100000
    assert m.ro_env_base == 0x200000
    assert m.demod_env_base == 0x300000
    assert m.rob_base == 0x400000
    assert m.host_ctrl == 0x500000
    assert m.imem(13) == 13 * 0x10000
    assert m.gate_env(2) == 0x100000 + 2 * 0x4000
    assert m.ro_env(3) == 0x200000 + 3 * 0x1000
    assert m.demod_env(3) == 0x300000 + 3 * 0x1000
    assert m.robs() == 0x400000
    assert m.to_host_addr(13, MEM_BASE + 0x123 * 4) == 13 * 0x10000 + 0x123 * 4
    assert m.mem_bytes == 4096 * 4


def test_sim_2q_numbers():
    m = _map("sim-2q")
    assert m.region_size == 0x20000       # pow2ceil(0x10000 * 2)
    assert [m.core_mem_base, m.pulse_mem_base, m.ro_env_base,
            m.demod_env_base, m.rob_base, m.host_ctrl] == [k * 0x20000 for k in range(6)]
    assert m.imem(1) == 0x10000
    assert m.gate_env(1) == 0x20000 + 0x4000
    assert m.ro_env(1) == 0x40000 + 0x1000
    assert m.demod_env(1) == 0x60000 + 0x1000


def test_sim_2q1c_converter_map():
    """sim-2q1c carries an explicit dac_map/adc_map (spec two-qubit/01 §1: core 2 = coupler C0_1).
    SocParams retains them and SocMap's gate_dac/ro_dac/adc_of read them, so the co-sim model drives
    and reads the same converters GenPulseTableSocJson wired — not the hardcoded ZCU216 default."""
    m = _map("sim-2q1c")
    assert m.params.qubit_num == 3
    assert m.params.dac_map == ((0, 2), (1, 2), (3, 2))
    assert m.params.adc_map == (0, 0, 0)
    # gate on DAC 0/1/3 (core 2 = coupler on DAC 3), readout drives summed on DAC 2, all demod on ADC 0
    assert [m.gate_dac(c) for c in range(3)] == [0, 1, 3]
    assert [m.ro_dac(c) for c in range(3)] == [2, 2, 2]
    assert [m.adc_of(c) for c in range(3)] == [0, 0, 0]
    # three cores: region sized for 3 (pow2ceil(0x10000 * 3) = 0x40000)
    assert m.region_size == 0x40000
    assert m.imem(2) == 2 * 0x10000


def test_converter_map_round_trips_through_json():
    """The optional maps survive to_json/from_json (the co-sim get_params path serves the raw config,
    but the remote runner ships to_json — either way the client SocMap must reconstruct them)."""
    m = _map("sim-2q1c")
    back = SocParams.from_json(m.params.to_json())
    assert back == m.params
    assert back.dac_map == ((0, 2), (1, 2), (3, 2)) and back.adc_map == (0, 0, 0)


def test_default_converter_map_unchanged_without_maps():
    """A config with no dac_map/adc_map (sim-2q, zcu216-14q) gets the generic ZCU216 layout on
    load: gate on the core's own DAC, readout drive on 14/15, demod on ADC 0/4."""
    m = _map("sim-2q")
    assert m.params.dac_map == ((0, 14), (1, 14)) and m.params.adc_map == (0, 0)
    assert (m.gate_dac(0), m.gate_dac(1)) == (0, 1)
    assert (m.ro_dac(0), m.adc_of(0)) == (14, 0)
    m14 = _map("zcu216-14q")
    assert (m14.gate_dac(13), m14.ro_dac(7), m14.adc_of(7)) == (13, 15, 4)


def test_core_local_constants():
    m = _map("sim-2q")
    assert (m.CTRL_TIME_CMP, m.CTRL_WAIT_TIME_CMP, m.CTRL_TIME) == (0x4000, 0x4008, 0xBFF8)
    assert (m.CTRL_RES, m.CTRL_REAL, m.CTRL_IMAG, m.CTRL_FROM_HOST) == \
        (0x4200, 0x4204, 0x4208, 0x2000)
    assert (m.RF_GATE, m.RF_READOUT, m.RF_DEMOD) == (0x10000, 0x20000, 0x30000)
    assert (m.RF_FIRE, m.RF_FREQ, m.RF_SLOT_STRIDE, m.RF_START_TIME) == (0x0, 0x4, 0x10, 0x4100)
    assert m.RF_DC_OFFSET == 0x8
    assert m.RF_PHASE_OFFSET == 0xC
    assert (m.HOST_RESET, m.HOST_TIME_OFF_LO, m.HOST_TIME_OFF_HI) == (0x0, 0x40, 0x44)
    assert (m.HOST_HOSTWIN_LO, m.HOST_HOSTWIN_HI) == (0x48, 0x4C)
    assert (m.HOSTWIN, m.HOSTWIN_BYTES) == (0x40000000, 1 << 24)
    assert [m.hostwin_offset(c) for c in range(2)] == [0, 1 << 24]
    assert m.hostwin_bytes_total == 2 << 24
    assert m.LEAD == 96   # pinned by the M1 lead-margin test (64 measured insufficient)


def test_generated_header_values():
    h = _map("sim-2q").gen_header()
    assert "#define RQ_MEM_BASE 0x80000000" in h
    assert "#define RQ_MEM_BYTES 0x4000" in h
    assert "#define RQ_CTRL_TIME 0xbff8" in h
    assert "#define RQ_GATE 0x10000" in h
    assert "#define RQ_HOSTWIN 0x40000000" in h
    assert "#define RQ_HOSTWIN_BYTES 0x1000000" in h
    assert "#define RQ_DEMOD 0x30000" in h
    assert "RQ_DECODER" not in h                 # decoder RF window removed (carrier-triggered)
    assert "#define RQ_RO_MAX_WIN 0x4000" in h   # 2^14 window cap
    assert "#define RQ_RO_LEAD 0x30" in h        # 48-batch readout freshness wait
    assert "#define RQ_START_TIME 0x4100" in h
    assert "#define RQ_DC_OFFSET 0x8" in h
    assert "#define RQ_PHASE_OFFSET 0xc" in h
    assert "#define RQ_GATE_PULSE_NUM 0x8" in h


def test_channel_table():
    import pytest

    from riscq.map import ChannelInfo

    m = _map("sim-2q")
    head = [(c.index, c.cname, c.base, c.slot_count, c.samples_per_line, c.line_bytes)
            for c in m.channels()]
    assert head == [
        (0, "RF_CH0", 0x10000, 8, 4, 16),   # gate drive: 8 slots, 4 samples/line
        (1, "RF_CH1", 0x20000, 1, 1, 4),    # readout drive: 1 slot, 1 sample/line
        (2, "RF_CH2", 0x30000, 1, 1, 4),    # demod carrier: 1 slot, 1 ADC-batch sample/line
    ]
    assert [(c.name, c.kind, c.lanes, c.env_depth) for c in m.channels()] == [
        ("gate", "pulse", 16, 1024), ("ro", "pulse", 16, 1024), ("demod", "demod", 4, 1024)]
    assert [(c.dac, c.adc) for c in m.channels(1)] == [(1, None), (14, None), (None, 0)]
    assert m.channel(0) == m.channels()[0]
    assert m.channel(2) == m.channels()[2]
    assert m.channel_named("ro") == m.channels()[1]
    assert isinstance(m.channel(0), ChannelInfo)
    with pytest.raises(ValueError, match="unknown channel index"):
        m.channel(3)
    assert m.rf_addr_width() == 28


def test_channel_defines_in_header():
    h = _map("sim-2q").gen_header()
    assert "#define RF_CH0 0x10000" in h
    assert "#define RF_CH1 0x20000" in h
    assert "#define RF_CH2 0x30000" in h
    assert "#define RQ_CH_GATE 0x10000" in h and "#define RQ_CH_DEMOD 0x30000" in h


def test_generated_linker_values():
    ld = _map("sim-2q").gen_linker()
    assert "ORIGIN = 0x80000000, LENGTH = 16384" in ld
    assert ".text" in ld and "KEEP(*(.text.init))" in ld
    assert "__stack_top" in ld and "ASSERT" in ld


def test_entries_cover_every_region():
    m = _map("sim-2q")
    entries = m.entries()
    kinds = [e.kind for e in entries]
    assert kinds.count("ram_rw") == 2       # one per core
    assert kinds.count("env_gate") == 2
    assert kinds.count("env_ro") == 2
    assert kinds.count("env_demod") == 2
    assert kinds.count("robs_ro") == 1      # shared
    assert kinds.count("ctrl_wo") == 1
    by_name = {e.name: e for e in entries}
    assert by_name["core1_ram"].host_addr == 0x10000
    assert by_name["robs"].host_addr == m.robs()
    assert by_name["host_ctrl"].host_addr == m.host_ctrl
    # every region base is claimed by exactly the right window
    for e in entries:
        assert e.nbytes > 0


def test_fixed_constants_rejected():
    import json

    import pytest

    raw = json.loads((CONFIGS / "sim-2q.json").read_text())
    raw["batch_size"] = 8
    with pytest.raises(ValueError, match="fixed by architecture"):
        SocParams.from_json(json.dumps(raw))
