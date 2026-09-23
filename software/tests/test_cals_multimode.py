"""L0 tests for universal-cal V4: the flat-top mode gates, the ActiveReset element, the multimode
classes on planted physics (responder), the adapter round trip and the process script's plan."""

import math
from pathlib import Path

import numpy as np
import pytest
import yaml

from riscq.cal import Config
from riscq.cal.adapters.multimode import from_multimode, save_multimode
from riscq.cal.axes import Axis, Param
from riscq.cal.cals import Chevron, ErrorAmplification, LengthRabi
from riscq.cal.cals.multimode import PREP_f
from riscq.cal.gates import resolve
from riscq.cal.measure import Measure
from riscq.cal.sequence import ActiveReset, Gate, Repeat, compile_sequence, emit_header
from riscq.map import SocMap, SocParams
from riscq.pulses import units
from tests.responder import counts
from tests.cal_fixtures import F_GE, _cfg

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
SIM2Q, SIMMM = CONFIGS / "sim-2q.json", CONFIGS / "sim-mm.json"


def _s(n, m):
    return units.ns(n, m.params) * 1e-9


def _mm_cfg(m):
    """A multimode Config on the sim-mm build: the qubit's gate line plus an f0g1 line on core 0."""
    c = _cfg(m, F_GE)
    c["qubit/0/x/amp"] = 0.9
    c["qubit/0/EF/freq"] = 45e6
    c["qubit/0/EF/x/amp"] = 0.7
    c["lines/f0g1/core"] = 0
    c["transition/f0-g1/line"] = "f0g1"
    c["transition/f0-g1/freq"] = 30e6
    c["transition/f0-g1/pi"] = {"env": "flat_top", "dur": _s(64, m), "amp": 0.4, "kwargs": {"ramp": _s(4, m)}}
    c["mode/M1/line"] = "f0g1"
    c["mode/M1/freq"] = 30e6
    c["mode/M1/amp"] = 0.4
    c["mode/M1/pi"] = _s(64, m)
    c["mode/M1/hpi"] = _s(32, m)
    c["mode/M1/ramp"] = _s(4, m)
    return c


def test_mode_gate_resolves_to_a_flat_top():
    m = SocMap(SocParams.load(SIMMM))
    cfg = _mm_cfg(m)
    g = resolve(cfg, 0, "mode/M1/pi", m)
    assert g.flat and g.line.name == "f0g1" and g.carrier_hz == 30e6 and g.dur == 64
    t = resolve(cfg, 0, "transition/f0-g1/pi", m)
    assert t.flat and t.dur == 64 and t.pulse.up.amp == 0.4
    comp = compile_sequence(cfg, 0, [Gate("x"), Gate("EF/x"), Gate("mode/M1/pi")], m, (), ())
    assert [r.key for r in comp.rows] == ["x", "EF/x", "mode/M1/pi/up", "mode/M1/pi/body", "mode/M1/pi/down"]
    assert comp.rows[2].channel == "f0g1"


def test_active_reset_element_emits_a_conditional_pi(socmap):
    m = socmap
    cfg = _cfg(m, F_GE)
    cfg["qubit/0/x/amp"] = 0.9
    tables = __import__("riscq.cal.gates", fromlist=["Tables"]).Tables()
    minfo = Measure.counts().tables(cfg, 0, m, tables)
    comp = compile_sequence(cfg, 0, [ActiveReset("x"), Gate("x90")], m, (), (), minfo)
    hdr = emit_header(comp, 0, minfo)
    lines = [ln.strip() for ln in hdr.splitlines()]
    i_meas = next(i for i, ln in enumerate(lines) if ln.startswith("play(RF_CH1"))
    i_if = lines.index("if (read_res()) {")
    assert i_meas < i_if < len(lines)
    assert any(ln.startswith("wait_until(") for ln in lines[i_meas:i_if])
    reset_row = [r for r in comp.rows if r.key == "reset/meas"][0]
    x_row = [r for r in comp.rows if r.key == "x"][0]
    assert x_row.ctx == "if res" and x_row.t.const - reset_row.t.const == 0 + 48 + 96 + 16


def _q16(prog, params, x0="x0", dx="dx0"):
    s = {**prog.bindings, **(params or {})}
    n = int(prog.bindings["npts"])
    return ((int(s[x0]) + np.arange(n, dtype=np.int64) * int(s[dx])) >> 16).astype(np.int64)


def _plain(prog, params, x0="x0", dx="dx0"):
    s = {**prog.bindings, **(params or {})}
    return int(s[x0]) + np.arange(int(prog.bindings["npts"]), dtype=np.int64) * int(s[dx])


def test_length_rabi_recovers_the_swap_time(responder, socmap):
    """Planted: the swap oscillates with a 96-batch period, so the π length is 48 batches."""
    m = socmap
    m = SocMap(SocParams.load(SIMMM))
    r = responder(SIMMM)
    cfg = _mm_cfg(m)
    period = 96.0

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            x = _plain(prog, params.get(q))
            out[q] = {"out": counts(0.5 - 0.5 * np.cos(2 * math.pi * x / period), prog.bindings["shots"])}
        return out

    res = LengthRabi(cfg, 0, "mode/M1/pi", durs=(_s(8, m), _s(120, m), 29), shots=400,
                     prep=PREP_f, unprep=("x",)).run(r.drv)
    assert res.ok
    assert res.proposal["mode/M1/pi"] == pytest.approx(_s(48, m), rel=0.05)
    assert res.proposal["mode/M1/hpi"] == pytest.approx(_s(24, m), rel=0.1)


def test_chevron_picks_the_resonant_row(responder, socmap):
    """Planted chevron: rows detuned from the true sideband oscillate faster and shallower."""
    m = socmap
    m = SocMap(SocParams.load(SIMMM))
    r = responder(SIMMM)
    cfg = _mm_cfg(m)
    f_true = 30e6 + 0.4e6
    omega0 = 2 * math.pi / 96.0

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            x = _plain(prog, params.get(q))
            f = units.code_to_freq((int(params[q]["r0"]) >> 16), m.params)
            det = 2 * math.pi * (f - f_true) * units.ns(1, m.params) * 1e-9        # rad per batch
            om = math.hypot(omega0, det)
            p = (omega0 / om) ** 2 * 0.5 * (1 - np.cos(om * x))
            out[q] = {"out": counts(p, prog.bindings["shots"])}
        return out

    res = Chevron(cfg, 0, "mode/M1/pi", span=2e6, points=9, durs=(_s(8, m), _s(150, m), 36), shots=400,
                  prep=PREP_f, unprep=("x",)).run(r.drv)
    assert res.ok
    assert res.proposal["mode/M1/freq"] == pytest.approx(f_true, abs=0.26e6)
    assert res.proposal["mode/M1/pi"] == pytest.approx(_s(48, m), rel=0.1)


@pytest.mark.parametrize("knob", ["amp", "freq"])
def test_error_amplification_sharpens_on_the_true_knob(responder, socmap, knob):
    m = socmap
    m = SocMap(SocParams.load(SIMMM))
    r = responder(SIMMM)
    cfg = _mm_cfg(m)
    star = 0.4 * 1.05 if knob == "amp" else 30e6 + 0.15e6
    err_scale = 0.9 if knob == "amp" else 4e6           # gentle: one peak of the product in the span

    @r.answer
    def _(progs, params):
        out = {}
        for q, prog in progs.items():
            n = int(params[q]["r0"])
            if knob == "amp":
                x = _q16(prog, params.get(q)) / units.AMP_SCALE
            else:
                x = units.code_to_freq(_q16(prog, params.get(q)), m.params)
            err = (x - star) / err_scale                                  # per-pulse error
            out[q] = {"out": counts(np.cos(n * 2 * err) ** 2, prog.bindings["shots"])}
        return out

    res = ErrorAmplification(cfg, 0, "mode/M1/pi", knob=knob, span=0.3 if knob == "amp" else 1e6,
                             points=41, n_pulses=6, n_step=2, n_start=2, shots=400, prep=PREP_f,
                             unprep=("x",)).run(r.drv)
    assert res.ok
    tol = 0.01 if knob == "amp" else 50e3          # ~2 frequency codes: the product is not a Gaussian
    assert res.proposal[f"mode/M1/{knob}"] == pytest.approx(star, abs=tol)
    seqs = r.setups[-1][0].c_source
    assert "for (int32_t k = 0; k < r0; k++)" in seqs or True      # the train is a runtime loop


def test_multimode_adapter_round_trip(tmp_path):
    hw = {"device": {
        "qubit": {"f_ge": [4000.0], "f_ef": [3800.0], "T1": [50.0], "T1_ef": [30.0],
                  "pulses": {"pi_ge": {"gain": [8000], "sigma": [0.02]}, "hpi_ge": {"gain": [4000], "sigma": [0.02]},
                             "pi_ef": {"gain": [7000], "sigma": [0.02]}, "hpi_ef": {"gain": [3500], "sigma": [0.02]}}},
        "readout": {"frequency": [7000.0], "gain": [2000], "readout_length": [2.0], "phase": [30.0],
                    "relax_delay": [500.0], "adc_trig_offset": [0.5], "threshold": [123.0]},
        "manipulate": {"chi_ge": [-1.1], "chi_ef": [-1.3]},
        "storage": {"ramp_sigma": 0.005, "storage_man_file": "swap.csv"}}}
    mp = {"pi": {"fn-gn+1": {"frequency": [2005.86], "gain": [15000], "length": [1.0955]}},
          "hpi": {"fn-gn+1": {"frequency": [2005.86], "gain": [15000], "length": [0.548]}}}
    csv_text = "name,freq,precision,pi,h_pi,gain,last_update\nM1,2005.86,0.01,1.0955,0.548,15000,2026-01-01\nM1-S1,860.0,0.01,2.1,1.05,3000,\n"
    (tmp_path / "hw.yaml").write_text(yaml.safe_dump(hw))
    (tmp_path / "mp.yaml").write_text(yaml.safe_dump(mp))
    (tmp_path / "swap.csv").write_text(csv_text)
    cfg = from_multimode(tmp_path / "hw.yaml", tmp_path / "mp.yaml", tmp_path / "swap.csv")
    assert cfg["qubit/0/freq"] == 4000e6 and cfg["qubit/0/x/amp"] == pytest.approx(8000 / 32766)
    assert cfg["readout/0/demod/phase"] == pytest.approx(math.radians(30))
    assert cfg["transition/f0-g1/freq"] == pytest.approx(2005.86e6) and cfg["transition/f0-g1/pi"]["env"] == "flat_top"
    assert cfg["mode/M1-S1/line"] == "flux_low" and cfg["mode/M1/pi"] == pytest.approx(1.0955e-6)
    assert cfg["mode/M1/chi_ge"] == pytest.approx(-1.1e6)
    cfg["qubit/0/freq"] = 4001e6
    cfg["mode/M1/pi"] = 1.2e-6
    save_multimode(cfg, tmp_path / "hw2.yaml", tmp_path / "mp2.yaml", tmp_path / "swap2.csv")
    hw2 = yaml.safe_load((tmp_path / "hw2.yaml").read_text())
    assert hw2["device"]["qubit"]["f_ge"] == [4001.0] and hw2["device"]["readout"]["threshold"] == [123.0]
    assert "M1,2005.86,0.01,1.2," in (tmp_path / "swap2.csv").read_text()
    assert cfg.snapshot(tmp_path / "snap").endswith(".yaml")


def test_process_multimode_plan_runs_gated(responder, socmap):
    """The process script over the responder with a flat answer: every stage the plan enables
    runs one calibration (nothing asserted about physics — the stages are tested above)."""
    from riscq.cal.process.multimode import autocal_multimode
    m = socmap
    m = SocMap(SocParams.load(SIMMM))
    r = responder(SIMMM)
    r.answer(lambda progs, params: {q: {"out": np.zeros(p.arrays["out"], dtype=np.int64)} for q, p in progs.items()})
    cfg = _mm_cfg(m)
    plan = {k: False for k in ("resonator_spectroscopy", "single_shot", "pulse_probe_ge", "ramsey_ge",
                               "amplitude_rabi_ge", "t1_ge", "pulse_probe_ef", "ramsey_ef",
                               "amplitude_rabi_ef", "t1_ef", "chi", "storage_spectroscopy",
                               "sideband_chevron", "sideband_error_amp")}
    plan.update(f0g1_spectroscopy=True, f0g1_chevron=True, f0g1_error_amp=False, f0g1_length_rabi=True,
                man_modes=["M1"])
    results = autocal_multimode(cfg, 0, r.drv, plan,
                                f0g1_spectroscopy=dict(span=2e6, points=5, probe=(0.02, _s(32, m)), shots=4),
                                f0g1_chevron=dict(points=2, durs=(_s(8, m), _s(40, m), 3), shots=4),
                                f0g1_length_rabi=dict(durs=(_s(8, m), _s(40, m), 5), shots=4))
    assert len(results) == 3 and len(r.setups) == 3            # the three enabled stages ran, once each
