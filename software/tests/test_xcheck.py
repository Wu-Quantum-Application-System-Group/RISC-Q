"""Unit gates for the spec-15 cross-check harness (`xcheck/`).

Skipped whole-file when the reference packages aren't installed, so the default suite is untouched
on a machine without qcal/QubiC. The EXPERIMENTS are scripts under `xcheck/runs/`, not tests — they
are characterization runs, not gates.
"""

from __future__ import annotations

import pytest

pytest.importorskip("qubic")
pytest.importorskip("qcal")

import numpy as np                                                     # noqa: E402

from riscq.cal.config import Config                                    # noqa: E402
from riscq.map import ADC_BATCH, BATCH_SIZE, SocMap, SocParams         # noqa: E402
from riscq.pulses import units                                         # noqa: E402
from xcheck import qcal_harness                                        # noqa: E402

CONFIGS = qcal_harness.CONFIGS


@pytest.fixture(scope="module")
def m() -> SocMap:
    return SocMap(SocParams.load(CONFIGS / "xcheck-2q.json"))


def test_twin_config_is_the_same_hardware_for_both_stacks(m):
    """The twin `config.yaml` must land on the SAME codes and batch counts on both sides — that is
    what makes every later difference attributable to the stacks and not to the setup (spec 15 §1.5).
    """
    cfg = Config.from_qcal(CONFIGS / "config.yaml")
    chan = qcal_harness.channel_configs()

    # the rate coincidence: one qubic fabric clock == one riscq batch, same converter geometry
    assert chan["fpga_clk_freq"] == m.params.dsp_freq_hz
    assert chan["Q0.qdrv"]["elem_params"]["samples_per_clk"] == BATCH_SIZE
    assert chan["Q0.rdlo"]["elem_params"]["samples_per_clk"] == ADC_BATCH
    assert units.sample_rate(m.params) == 8e9                      # DAC
    assert ADC_BATCH * m.params.dsp_freq_hz == 2e9                 # ADC

    # the readout slots land on exact DAC codes on both sides (and deliberately not on a power of
    # two — see the config header and spec 15 §9 on the CORDIC half-turn boundary)
    assert units._freq_code(cfg["readout/0/freq"], m.params) == 509
    assert units._demod_code(cfg["readout/0/freq"], m.params) == 4 * 509
    assert units._freq_code(cfg["readout/1/freq"], m.params) == 253
    assert units._demod_code(cfg["readout/1/freq"], m.params) == 4 * 253
    assert units._freq_code(cfg["qubit/0/freq"], m.params) == 2048

    # and the batch-domain sizes the co-sim config was validated at
    def nb(seconds):
        return units.batches(seconds * 1e9, m.params)

    assert nb(cfg["readout/0/demod/dur"]) == 40
    assert nb(cfg["readout/0/dur"]) == 56
    assert nb(cfg["qubit/0/x90/dur"]) == 4
    assert nb(cfg["reset/relax"]) == 3200
    assert nb(cfg["qubit/0/T1"]) == 600

    # the qcal hardware block must describe THIS build (rates + interpolation ratios)
    cfg.check_hardware(m.params)


def test_qcal_session_is_hermetic_and_restores_globals(tmp_path):
    """Every run works in its own temp copy of `configs/`: qcal auto-loads `ClassificationManager.pkl`
    from `Settings.config_path`, so a shared directory would leak a classifier between runs."""
    from qcal import settings

    before = (settings.Settings.config_path, settings.Settings.save_data)
    with qcal_harness.QcalSession() as s:
        assert settings.Settings.config_path.endswith("/")      # qcal concatenates, never joins
        assert settings.Settings.config_path.startswith(str(s.path))
        for name in ("config.yaml", "channel_config.json", "qubic_cfg.json"):
            assert (s.path / name).exists()
        assert s.path != CONFIGS
        cfg = s.config()
        assert cfg.qubits == (0, 1)
    assert (settings.Settings.config_path, settings.Settings.save_data) == before
    assert not hasattr(settings.Settings, "data_path")


def _one_pulse_board(freq_hz, phase, amp, env, spc, interp, elem_ind, kind="rf"):
    """Encode ONE pulse with qubic's own assembler pieces and hand back the memories a board would
    have been given — the decoder's input contract, built by the reference encoder."""
    import distproc.command_gen as cg
    import qubic.rfsoc.hwconfig as hw
    from distproc.hwconfig import ChannelConfig

    elem = hw.elemconfig_classfactory(kind)(samples_per_clk=spc, interp_ratio=interp)
    elem.add_env(np.asarray(env, complex))
    elem.add_freq(freq_hz)
    env_buf, env_map = elem.compile_envs()
    freq_buf, freq_map = elem.compile_freqs()
    # `save_result` only exists on rf_mix; passing it to a plain rf element raises upstream
    cfg = elem.get_cfg_word(elem_ind, True) if kind == "rf_mix" else elem.get_cfg_word(elem_ind)
    word = cg.pulse_cmd(cfg_word=cfg, amp_word=cg.get_amp_word(amp),
                        freq_word=freq_map[freq_hz], phase_word=cg.get_phase_word(phase),
                        env_word=env_map[next(iter(env_map))], cmd_time=100)
    mems = {"command0": word.to_bytes(16, "little") + cg.done_cmd().to_bytes(16, "little"),
            "env0": env_buf, "freq0": freq_buf}
    chan = {"fpga_clk_freq": 500e6,
            "Q0.x": ChannelConfig(core_ind=0, elem_ind=elem_ind, elem_type=kind,
                                  elem_params={"samples_per_clk": spc, "interp_ratio": interp},
                                  env_mem_name="env0", freq_mem_name="freq0")}
    return chan, mems


@pytest.mark.parametrize("spc,interp,elem_ind,kind",
                         [(16, 4, 0, "rf"), (16, 16, 1, "rf"), (4, 4, 2, "rf_mix")])
def test_decode_round_trips_the_reference_encoding(spc, interp, elem_ind, kind):
    """C1's contract at the unit tier: the board reconstructs freq/phase/amp/envelope/save_result
    from the BYTES, to the resolution the 128-bit instruction word and the buffers actually carry."""
    from xcheck.decode import Decoder

    n_env = 3 * (spc // interp)
    env = 0.8 * np.exp(1j * np.linspace(0, 1.0, n_env))
    freq, phase, amp = 62.5e6, 0.7, 0.5
    chan, mems = _one_pulse_board(freq, phase, amp, env, spc, interp, elem_ind, kind)

    (p,) = Decoder(chan, mems).core_program(0, "command0").pulses
    assert p.dest == "Q0.x" and p.start_time == 100
    assert p.freq == pytest.approx(freq, abs=500e6 / 2 ** 32)     # one phase-increment LSB
    assert p.phase == pytest.approx(phase, abs=2 * np.pi / 2 ** 17)
    assert p.amp == pytest.approx(amp, abs=1.0 / (2 ** 15 - 1))
    assert len(p.env) == n_env
    # hwconfig scales by 2**15 - 1 and TRUNCATES toward zero, so each quadrature is within one LSB
    d = p.env - env
    assert max(np.max(np.abs(d.real)), np.max(np.abs(d.imag))) <= 1.0 / (2 ** 15 - 1)
    assert p.save_result is (kind == "rf_mix")                     # only rf_mix carries the bit


def test_decode_fails_loud_on_an_undecoded_opcode():
    """A silently skipped instruction would fake coverage of a path the board does not model."""
    import distproc.command_gen as cg

    from xcheck.decode import Decoder

    chan, mems = _one_pulse_board(62.5e6, 0.0, 0.5, np.ones(4), 16, 4, 0)
    mems["command0"] = cg.jump_i(3).to_bytes(16, "little")
    with pytest.raises(NotImplementedError, match="not decoded"):
        Decoder(chan, mems).core_program(0, "command0")


def test_model_fast_forward_matches_stepwise_relaxation(m):
    """`TwoLevelModel.fast_forward` is what lets the virtual board skip a 3200-batch passive-reset
    gap per shot instead of making 3200 Python calls (spec 15 §3.3). Gated in full in
    tests/test_models.py; asserted here because the harness depends on it."""
    from riscq.sim import models

    kw = dict(core=0, t1=600.0, t2=3000.0)
    ff, step = models.TwoLevelModel(m, **kw), models.TwoLevelModel(m, **kw)
    for mod in (ff, step):
        mod._b[:] = (0.2, 0.3, -1.0)
    ff.fast_forward(3200)
    for _ in range(3200):
        step._relax()
    assert np.allclose(ff._b, step._b, rtol=1e-12, atol=1e-15)
