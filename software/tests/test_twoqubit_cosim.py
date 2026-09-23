"""The two-qubit / EF suite's RTL half: what the SoC EMITS on the 3-core `sim-2q1c` build (2 qubits
+ 1 coupler) and the 2-core `sim-2q` one, and what that stimulus does to the qubits.

After the T6 migration (specs/software-test-refactor/02 §3.5) this file is split by tier:

- **L1** (01 §3) — the emitted stimulus, model OFF: the mid-shot GE↔EF retune, the coupler-form and
  drive-form CZ fires, the spectator bracket and the EF-sandwich train, all captured off the real
  DACs and asserted to the batch. Deterministic, and the only tier that can see `LEAD`/`SEP`.
- **L2** (01 §4) — what those circuits do to the qubits, read off `drv.sim.model_state()` through
  `tests.probe.Probe`: one shot per point, an ANALYTIC target, no fit and no shot statistics.
- **L0** — the EF `Phase` write-back, host-pure against the shared `responder`.

Everything the old counts-mode versions inferred through fits — the EF Rabi / Ramsey / crossing
analyses, the CZ 2D argmax and its `CZ/freq` + `CZ/pulse` write-back, the `joint_populations` zip
and the 3-level cluster statistics — is host-pure in `tests/test_twoqubit.py` (against the analytic
responder) and `tests/test_models.py` (models driven directly). The one `--slow` anchor,
`test_jazz_runs_end_to_end_no_spurious_zz`, puts the whole loop back together with real shots and
real noise, and is what notices if an L2 analytic target ever drifts from the hardware.

**Five tests here are over the 20 k per-test cap, and structurally cannot come under it.** Measured
on these builds, one core's `rq.setup` (image + envelopes + tables, all over AXI) costs **9.4–12.1 k
simulated batches** — the image words dominate, the envelopes barely register — while a `rerun` is
~1.8 k and a sized DAC capture ~2.3 k. So the floor for a claim that needs N core images is roughly
N × 10 k, and every multi-core claim needs one image per core by definition:

| test | cores | measured | floor |
|---|---|---|---|
| `test_spectator_ramsey_brackets_the_cz_fire` | 3 | 34.2 k | 3 images + capture |
| `test_cz_3core_drives_coupler_at_fcz_aligned` | 3 | 30.5 k | 3 images + capture |
| `test_cz_sandwich_dac_train_aligned` | 2 | 27.1 k | 2 images + capture |
| `test_cz_drive_form_two_tone_fire_aligned` | 2 | 25.7 k | 2 images + capture |
| `test_cross_core_shot_alignment` | 2 | 21.7 k | 2 images + 2 reruns |

Each is already at that floor: one point, one shot, the minimum legal relax head, and a capture sized
to boot + 2 grid periods. Nothing but the image count is left to cut, and cutting it would delete the
lock-step claim the test exists for. They are the per-file exemption 02 §1 anticipates ("tests that
legitimately need more land around 25–40 k; those are called out per-file rather than forced under
the cap"), and each carries an explicit `@pytest.mark.batch_cap` naming its floor. The whole file is
274 k batches / ~34 s, ~14 % of the 2 M suite budget.
"""

import math
from pathlib import Path

import numpy as np
import pytest

from riscq import run as rq
from riscq.cal import JAZZ, ClassifierN, Config, CZSweep, EFPhase, SpectatorPhase
from riscq.cal.axes import Axis, Param
from riscq.cal.base import GATE_CH, GATE_ENV, SEP, gate_pulse, gate_sigma
from riscq.cal.cz import _cz_dur_batches, _cz_pulse, cz_sandwich
from riscq.cal.experiment import Experiment
from riscq.cal.gates import resolve
from riscq.cal.measure import Measure
from riscq.cal.sequence import Cond, Gate, Idle
from riscq.map import LEAD, SocMap, SocParams, pack16
from riscq.pulses import Pulse, units
from tests.probe import Probe, rabi_for, sigma_z

SIM2Q1C = Path(__file__).resolve().parents[1] / "configs" / "sim-2q1c.json"


def ef_pulse(cfg, q, m, name="x90"):
    """The Config's own EF gate pulse (was `base.ef_pulse`): the resolver's, baseband."""
    return resolve(cfg, q, f"EF/{name}", m).pulse


PREP = Param("prep", (0, 1))
NCAP_RUN = 4000       # a rerun of an already-loaded image: boot-free, one grid period
NCAP_RUN2 = 34000     # a full class run on 2 cores: boot + 2 images over AXI + the shot
NCAP_RUN3 = 46000     # ... and on 3
TIMEOUT = 2_000_000


def probe_progs(cosim, cfg, keys, seqs, axes=None, shots=1, measure=None, params=(),
                label="probe"):
    """The production programs for a `Probe`: the real `Experiment` compile path (sequence
    compiler → `k_batched` → `rq.setup`), returning `{core: Program}`. The probe then reruns them
    with its own runtime words — `x0`/`dx0` for the on-core axis, `r0..r3` for the Params."""
    drv, m = cosim
    axes = axes or {q: () for q in keys}
    exp = Experiment(cfg, keys, seqs, axes, params, measure or Measure.counts(), shots, label=label)
    progs, _, _ = exp.compile(drv)
    return progs

F_GE = 50e6                       # planted qubit frequency (DAC freq code 2048)
# EF cal: GE and EF carriers a full demod-null (4096 codes) apart so the ThreeLevelModel picks the
# right transition cleanly (GE code 6144 = 150 MHz, EF code 2048 = 50 MHz).
EF_F_GE = 150e6
EF_F_EF = 50e6

# The grid's idle head, in batches. `set_model` rebuilds the model — and so re-prepares |0⟩ — in
# ZERO simulated cycles (01 §4.2), and the L1 tests carry no quantum state at all, so nothing here
# needs a T1 relax head: 8 is `grid_period`'s floor. It still leaves every pulse ≥ relax + SEP +
# dur + READOUT_LEAD batches of scheduling lead, i.e. well over `LEAD`.
RELAX = 8

# The CZ kernels need a bigger one, and it is NOT a physical relax head either — it is scheduling
# lead. `grid_period` charges `seq_batches + SEP`, but every CZ caller (here and in `CZSweep` /
# `_cz_cond_period`) declares `seq` WITHOUT the SEP that `k_cz_pop` also puts before its tone, so
# the earliest pulse lands only `relax + delay + dur + READOUT_LEAD` batches after `now()`. Below
# `LEAD` = 96 the core posts the GE prep too late and it DROPS silently — measured here: at
# relax = 8 with an 8-batch window that head is 64 and the |11> prep never plays. 200 leaves
# ~2.5·LEAD of margin on every CZ config in this file.
RELAX_CZ = 200

# Captures are SIZED, not generous (01 §7): `dac_capture_get` BLOCKS until the armed window is full,
# so every armed batch past the last window is simulated for nothing. A capture armed before the
# reset release pays the core's boot + preamble (measured ~790 batches on these builds) and then the
# one grid period the shot lives in; `BOOT_NCAP + 2·period` covers both with a period to spare. Each
# test prints its windows, so an undersized capture fails with a window count, never silently.
BOOT_NCAP = 1400


def _s(n_batches, m):             # batches → seconds (the Config is physical, spec 13 §2)
    return n_batches / m.params.dsp_freq_hz


def _carrier_code(win):
    """The (unsigned) carrier code of a clean tone from its DAC window (TwoLevelModel._carrier_code):
    amplitude- and phase-blind, exact for a square-envelope tone."""
    x = np.asarray(win, float).reshape(-1)
    num = float(np.sum(x[1:-1] * (x[2:] + x[:-2])))
    den = 2.0 * float(np.sum(x[1:-1] ** 2))
    w = math.acos(min(1.0, max(-1.0, num / den))) if den else 0.0
    return round(w / math.pi * (1 << 15))


# ── L1: the 3-core build boots and its host map decodes ──

@pytest.mark.cosim
def test_3core_boots_and_host_map_decodes(cosim_2q1c):
    """The build boots with 3 cores; SocMap read the config's dac_map/adc_map (not the ZCU216 default);
    and every core's RAM window decodes over real AXI."""
    drv, m = cosim_2q1c
    assert m.params.qubit_num == 3
    assert [m.gate_dac(c) for c in range(3)] == [0, 1, 3]     # core 2 = coupler on DAC 3
    assert [m.ro_dac(c) for c in range(3)] == [2, 2, 2]       # readout drives summed on DAC 2
    assert [m.adc_of(c) for c in range(3)] == [0, 0, 0]
    rq.reset(drv, m, on=True)                                  # cores held; RAM host port is independent
    for c in range(3):
        val = (0xA5A50000 + c) & 0xFFFFFFFF
        drv.write_block(m.imem(c), val.to_bytes(4, "little"))
        assert int.from_bytes(drv.read_block(m.imem(c), 4), "little") == val


# ── the readout substrate the L2 / L1 readout probes on this build share ──

# The demod window and the two demod codes, chosen so the summed-ADC readout is EXACT rather than
# 2 % out. Over a `RO_WIN`-batch window (4 ADC samples per batch) a tone at code c1 read against a
# demod at c2 leaves a residual unless (c1 − c2)·4·RO_WIN is a whole number of 2^16 turns; the same
# condition on 2·c kills each tone's own counter-rotating term. At RO_WIN = 8 that makes every code
# a multiple of 1024 and every code DIFFERENCE a multiple of 2048 — so 2048 / 4096 integrate to
# exactly their own tone out of the sum, where 2048 / 1024 on a 40-batch window (the old pair) did
# not: their cross term left 2 % of the partner's tone in each window.
#
# The lengths are also the image-load lever. `rq.setup` writes every envelope word by word over AXI
# (~35 simulated batches per word measured here), so a probe's drive and window lengths dominate what
# its `setup` costs — the 20 k budget for a two-core probe is exactly this (02 §1).
RO_WIN, RO_DRIVE = 8, 16
RO_CODES = {0: 2048, 1: 4096}


def _ro_cfg(m, qubits, code, x90_amp=0.5):
    """A minimal readout Config for `qubits`, each on its own demod code — the frequency-multiplexed
    layout of the 3-core build (all cores share readout DAC 2 and ADC 0, spec 13 §8). Envelopes are
    kept short: `rq.setup` writes them word by word over AXI, so the drive/window lengths are most of
    what an L2 probe's image load costs (02 §1)."""
    cfg = Config()
    for q in qubits:
        cfg[f"qubit/{q}/freq"] = F_GE
        cfg[f"qubit/{q}/x90/amp"] = float(x90_amp)
        cfg[f"readout/{q}/freq"] = float(units.demod_code_to_freq(code[q], m.params))
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = _s(RO_DRIVE, m)              # drive covers the demod window
        cfg[f"readout/{q}/demod/dur"] = _s(RO_WIN, m)
    cfg["reset/relax"] = _s(RELAX, m)
    return cfg


@pytest.mark.cosim
@pytest.mark.batch_cap(40_000)
def test_cross_core_shot_alignment(cosim_2q1c):
    """L2 — ONE run drives TWO cores, and each core's own qubit responds to its OWN gate DAC and is
    read back out of the SHARED, frequency-multiplexed readout.

    FLOOR: ~22 k = 2 core images (~10 k each over AXI) + 2 reruns. A cross-core claim needs one
    image per core by definition; the module docstring's table has the full accounting.

    Two production raw-capture images (one per core, through `Experiment`) are loaded once and run
    together under the SAME `prep = 1`. The two models are planted with DIFFERENT rates — `rabi_for` π/2 per X90 on
    core 0, so its two-X90 prep is an exact π, and 0 on core 1 — so one stimulus must produce two
    DIFFERENT textbook states in one run. Both halves are asserted:

    - the STATE, off `model_state()["models"]`: core 0 at |1⟩, core 1 still at |0⟩, to 0.02. A
      crossed gate DAC, a core that never released, or a shared-time slip all break it.
    - the READOUT, through the real demod/decoder: the two tones ride distinct demod codes and SUM
      on the one converter, so recovering ⟨σz⟩ = −1 on core 0 and +1 on core 1 out of that sum is
      exactly the statement that each core's demod integrates out its OWN tone — i.e. that the two
      per-shot streams are genuinely separate lanes, which is what the joint two-qubit populations
      rest on.

    What this replaces: 32 projective shots per core on a 3200-batch relax grid, asserting
    P1 > 0.9 / < 0.1 and a dominant joint P(10). The zip itself (`joint_populations`, and that a
    shot-count mismatch is loud) is host-pure in test_twoqubit.py; the binomial statistics of the
    clusters are in test_models.py. What is left is the per-core separation, and one noiseless shot
    measures it exactly — 0.02 where the old bound was 0.1."""
    _, m = cosim_2q1c
    code = RO_CODES                                            # distinct demod codes → freq-multiplexed
    cfg = _ro_cfg(m, (0, 1), code)
    progs = probe_progs(cosim_2q1c, cfg, [0, 1],
                        {q: [Cond(PREP, [Gate("x90"), Gate("x90")])] for q in (0, 1)},
                        measure=Measure.raw(host=False), params=(PREP,), label="rawiq")
    turn = {0: math.pi / 2, 1: 0.0}                            # core 0 an exact π/2 per X90; core 1 nothing

    def spec(scale):
        # the two tones SUM on the shared ADC, so each is halved to keep the sum in converter range
        return {"kind": "multi", "models": [
            {"kind": "twolevel", "core": q, "f_ge": F_GE, "readout_code": code[q],
             "readout_amp": 9000.0, "readout_phase": 0.0, "noise_scale": 0.0, "collapse": False,
             "rabi_rad_per_amp": scale * rabi_for(m, gate_pulse(cfg, q, m), F_GE, turn[q])}
            for q in (0, 1)]}

    params = {q: {"r0": 1} for q in (0, 1)}                    # the SAME prep on both cores
    p = Probe(cosim_2q1c, progs)
    ref = p.iq(spec(0.0), params)                              # every gate a no-op ⇒ both qubits |0⟩
    z = p.iq(spec(1.0), params)
    b0, b1 = (mo["bloch"] for mo in p.drv.sim.model_state()["models"])
    sz = {q: float(sigma_z(z[q], ref[q])[0]) for q in (0, 1)}
    print(f"\n[cross-core] core0 bloch={np.round(b0, 4).tolist()} <sz>={sz[0]:+.4f}\n"
          f"             core1 bloch={np.round(b1, 4).tolist()} <sz>={sz[1]:+.4f}")

    assert b0 == pytest.approx([0.0, 0.0, -1.0], abs=0.02), f"core 0's pi prep landed at {b0}, not |1>"
    assert b1 == pytest.approx([0.0, 0.0, 1.0], abs=0.02), f"core 1 was not driven but sits at {b1}"
    assert sz[0] == pytest.approx(-1.0, abs=0.02), \
        f"core 0 reads <sz> = {sz[0]:+.4f} out of the summed ADC, not the |1> it is in"
    assert sz[1] == pytest.approx(+1.0, abs=0.02), \
        f"core 1 reads <sz> = {sz[1]:+.4f} — its demod did not integrate out its own tone"


# ── L1: the three readout levels land as three distinct demod frames ──

LEVEL_PHASES = (0.0, 2 * math.pi / 3, 4 * math.pi / 3)     # the qutrit's three readout-tone phases


@pytest.mark.cosim
def test_three_level_clusters_separate(cosim_2q1c):
    """L1 (spec 01 §5) — the readout datapath resolves THREE demod frames, not two.

    A `ThreeLevelModel` with `init_level` planted and no drive carries no quantum evolution at all:
    it is a deterministic tone source whose phase is the planted `level_phases[level]`, the
    `ToneModel` role 01 §3.3 permits at L1. So one noiseless readout per level, through the real
    demod carrier and decoder, must return three phasors of EQUAL magnitude whose relative angles
    are exactly the planted 120° — the geometry a `ClassifierN` then tells apart.

    The datapath reports the CONJUGATE frame: an emitted tone phase +ψ comes back as −ψ in the
    decoder's IQ. That is the one sign convention in the readout chain, the same one
    `ReadoutCalibration`'s `−atan2(m0 − m1)` proposal is built on and the one
    test_batch::test_applied_demod_phase pins from the demod-slot side, so it is asserted here
    rather than absorbed into an absolute-value.

    The old version measured that as `separation > 1.0` and a confusion diagonal over 3 × 48 noisy
    projective shots on a 3200-batch relax grid, re-`setup`ping the image once per level. Cluster
    separation and the confusion matrix are STATISTICS of the model, and are host-pure in
    test_models.py::test_threelevel_projective_clusters_classify (where the model can be driven a
    million times for free); what needs the RTL is that the three frames survive the datapath, and
    that is exact off one shot each."""
    _, m = cosim_2q1c
    q, code = 0, RO_CODES[0]
    cfg = _ro_cfg(m, (q,), RO_CODES)
    progs = probe_progs(cosim_2q1c, cfg, [q],
                        {q: [Cond(PREP, [Gate("x90"), Gate("x90")])]},
                        measure=Measure.raw(host=False), params=(PREP,), label="rawiq")
    p = Probe(cosim_2q1c, progs)

    z = [p.iq({"kind": "threelevel", "core": q, "readout_code": code, "readout_amp": 18000.0,
               "readout_phase": 0.0, "level_phases": list(LEVEL_PHASES), "init_level": level,
               "collapse": False, "noise_scale": 0.0}, {q: {"r0": 0}})[q][0]
         for level in range(3)]
    mag = [abs(v) for v in z]
    rel = [float(np.angle(v / z[0])) for v in z]
    print(f"\n[3-level] |z|={[f'{v:.4e}' for v in mag]}\n"
          f"          dphase={np.round(rel, 4).tolist()} want={np.round(LEVEL_PHASES, 4).tolist()}")

    assert min(mag) > 0, "a level emitted no readout tone at all"
    assert max(mag) - min(mag) < 0.005 * max(mag), \
        f"the three levels' tones differ in magnitude ({mag}) — the demod is not level-blind"
    for level in (1, 2):
        want = math.remainder(-LEVEL_PHASES[level], 2 * math.pi)     # the chain's conjugate frame
        assert math.remainder(rel[level] - want, 2 * math.pi) == pytest.approx(0.0, abs=0.02), \
            f"level {level} came back {rel[level]:+.4f} rad off |0>, the planted frame is {want:+.4f}"


# ── EF calibration (spec two-qubit/01 §4.1 / Q1): the mid-shot retune and what it drives ──

def _ef_cfg(m, q, ge_amp=0.5, ef_amp=0.4):
    """The EF co-sim Config: GE at EF_F_GE, EF at EF_F_EF, a 3-level readout tone at demod code 2048.
    None of the EF tests below READS that readout — the L2 probes take the answer off
    `model_state()` and the L1 capture watches the gate DAC — so the drive/window sit at the
    `_ro_cfg` floor, where they are pure image-load cost."""
    cfg = Config()
    cfg[f"qubit/{q}/freq"] = EF_F_GE
    cfg[f"qubit/{q}/x90/amp"] = ge_amp
    cfg[f"qubit/{q}/EF/freq"] = EF_F_EF
    cfg[f"qubit/{q}/EF/x90/amp"] = ef_amp
    cfg[f"readout/{q}/freq"] = float(units.demod_code_to_freq(RO_CODES[0], m.params))
    cfg[f"readout/{q}/amp"] = 0.5
    cfg[f"readout/{q}/dur"] = _s(RO_DRIVE, m)
    cfg[f"readout/{q}/demod/dur"] = _s(RO_WIN, m)
    cfg["reset/relax"] = _s(RELAX, m)
    return cfg


def _ef_spec(m, cfg, q, rabi_ef, f_ef=EF_F_EF):
    """The planted qutrit for an L2 EF probe (01 §4.6): the GE prep is an exact π (two X90s at the
    `rabi_for` π/2 rate), the EF gate an exact rotation at the planted rate, and nothing decays —
    `set_model` resets the state between points, so the model needs no t1 head, and the readout is
    never collapsed so `model_state()` still sees the state the circuit left."""
    return {"kind": "threelevel", "core": q, "f_ge": EF_F_GE, "f_ef": float(f_ef),
            "rabi_ge_rad_per_amp": rabi_for(m, gate_pulse(cfg, q, m), EF_F_GE, math.pi / 2),
            "rabi_ef_rad_per_amp": float(rabi_ef),
            "readout_code": 2048, "readout_amp": 18000.0, "readout_phase": 0.0,
            "init_level": 0, "collapse": False, "noise_scale": 0.0}


@pytest.mark.cosim
def test_ef_drive_carriers_in_rtl(cosim_2q1c):
    """L1 — the mid-shot freq switch (spec 01 §4.1): capture the gate DAC across one EF-amplitude
    shot and prove the RTL retunes the ONE gate NCO between segments — the GE pi prep comes out at f_GE (2 X90s)
    and the EF drive at f_EF, each at the programmed amplitude. This is the bit-exact readback under the
    novel per-shot double set_freq (the drive whose end-to-end effect the EF probes below assert)."""
    drv, m = cosim_2q1c
    q = 0
    cfg = _ef_cfg(m, q)
    drv.sim.set_model({"kind": "zero"})
    ge = resolve(cfg, q, "x90", m).dur
    ef = resolve(cfg, q, "EF/x90", m).dur
    ax = Axis.amp(0.5, 0.5, 1)                               # one point, at the same 0.5 as before
    seq = [Gate("x90"), Gate("x90"), Gate("EF/x90", amp=ax)]
    progs = probe_progs(cosim_2q1c, cfg, [q], {q: seq}, {q: (ax,)}, label="efcarr")
    handle = drv.sim.dac_capture_arm(m.gate_dac(0), NCAP_RUN)   # armed before the rerun
    rq.rerun(drv, m, progs, params={q: {"x0": ax.x0, "dx0": 0}}, results=["out"], timeout=TIMEOUT)
    t0, cap = drv.sim.dac_capture_get(handle)

    active = cap.any(axis=1)
    starts = [i for i in range(len(active)) if active[i] and (i == 0 or not active[i - 1])]
    ends = [i for i in range(len(active)) if active[i] and (i == len(active) - 1 or not active[i + 1])]
    wins = [(s, e) for s, e in zip(starts, ends)]
    ge_code = units._freq_code(EF_F_GE, m.params)
    ef_code = units._freq_code(EF_F_EF, m.params)
    print(f"\n[ef-carriers] ge={ge} ef={ef} "
          f"windows={[(s, e - s + 1, _carrier_code(cap[s:e + 1])) for s, e in wins]}")
    assert len(wins) == 2, f"expected GE-prep + EF windows, got {len(wins)}"
    (gs, gee), (es, ee) = wins
    assert ee - es + 1 == ef, "EF drive should be one EF X90 window"
    assert gee - gs + 1 == 2 * ge, "GE prep should be two back-to-back X90s"
    assert abs(_carrier_code(cap[gs:gee + 1]) - ge_code) < 40, "GE prep not at f_GE"
    assert abs(_carrier_code(cap[es:ee + 1]) - ef_code) < 40, "EF drive not at f_EF"


@pytest.mark.cosim
def test_ef_amplitude_recovers_the_ef_rabi(cosim_2q1c):
    """L2 (spec 01 §4.1 / Q1) — what the EF amplitude sweep MEASURES: the swept EF drive really
    rotates {|1>, |2>}, by an angle exactly linear in the swept amplitude code.

    The on-core Q16 amplitude sweep writes the code raw and the drive integral σ is linear in it, so
    a code k·A rotates by k·θ(A). Plant the rate that makes code A an exact EF X90 (`gate_sigma` at
    that code — the `probe.rabi_for` idiom) after a GE-π prep, and the rungs are the textbook
    quarter, half and full turns INSIDE the EF subspace, |0> untouched throughout:

        k = 1 -> (0, 1/2, 1/2)    k = 2 -> (0, 0, 1)  the EF pi, |1> -> |2>    k = 4 -> (0, 1, 0)  2pi

    which is the P(|2>) cosine the counts version fitted (recovered/planted to 15 %), read directly
    off `model_state()["populations"]` at three points to 0.02. |2> is invisible to the hardware
    `res` bit, so the state is the only exact readout of it; the fit and the `EF/x90/amp` proposal
    that turn this curve into a number are host-pure in test_twoqubit.py, which is also where the
    trained 3-level classifier the old version needed now lives.

    The base amplitude is 0.2 so the k = 4 rung (4 × 3979 = 15916) still fits under `units.AMP_SCALE`
    = 19896 — a hand-written "half scale" code would silently mis-scale every angle (01 §4.6). The GE
    and EF carriers are a full demod null apart (codes 6144 / 2048, Δ = 4096) so the model's per-batch
    carrier argmax picks the intended transition and the "EF π" does not also drive GE."""
    _, m = cosim_2q1c
    q = 0
    cfg = _ef_cfg(m, q, ef_amp=0.2)
    efp = ef_pulse(cfg, q, m)
    a0 = efp.amp_code()                                   # 0.2 · AMP_SCALE — never hand-written
    rabi_ef = (math.pi / 2) / gate_sigma(m, efp, EF_F_EF, a0)     # code a0 = an exact EF X90

    ax = Axis.amp(float(a0) / units.AMP_SCALE, float(a0) / units.AMP_SCALE, 1)
    seq = [Gate("x90"), Gate("x90"), Gate("EF/x90", amp=ax)]
    progs = probe_progs(cosim_2q1c, cfg, [q], {q: seq}, {q: (ax,)}, label="efamp")
    spec = _ef_spec(m, cfg, q, rabi_ef)
    p = Probe(cosim_2q1c, progs)

    for k in (1, 2, 4):
        assert k * a0 <= units.AMP_SCALE, "the swept code must stay on scale"
        pops = p.state(spec, {q: {"x0": (k * a0) << 16}})["populations"]
        theta = rabi_ef * gate_sigma(m, efp, EF_F_EF, k * a0)     # exact by construction
        want = [0.0, math.cos(theta / 2) ** 2, math.sin(theta / 2) ** 2]
        print(f"\n[ef-amp k={k}] code={k * a0} theta={theta:.4f} pops={np.round(pops, 4).tolist()} "
              f"want={np.round(want, 4).tolist()}")
        assert pops == pytest.approx(want, abs=0.02), \
            f"the swept code {k * a0} turned the EF subspace to {pops}, not the {k}*pi/2 rotation {want}"


@pytest.mark.cosim
@pytest.mark.parametrize("d0_code", [60, -60])
def test_ef_frequency_recovers_the_ef_detuning(cosim_2q1c, d0_code):
    """L2 (spec 01 §4.1 / Q1) — the RTL half of the EF Ramsey: an EF carrier detuned by δ really does
    RAMP THE EF DRIVE AXIS, so a pair of EF X90s separated by a wait accumulates exactly the phase δ
    says it should — inside {|1>, |2>}, after the GE π prep and the mid-shot retune.

    The model takes each drive's axis by demodulating the gate DAC against `f_ef`, so a carrier δ
    codes away ramps that axis by 2π·δ/2^12 rad per batch (16 DAC samples of δ/2^16 turns each). Two
    EF X90s whose axes differ by φ leave the pseudo-qubit at, exactly (Rodrigues, both axes in the
    pseudo-equator, starting from the |1> pole),

        <sz> = P(|1>) - P(|2>) = -cos(phi)   =>   P(|1>) = (1 - cos phi)/2,  P(|2>) = (1 + cos phi)/2

    with φ = 2π·δ·Δt/2^12 and Δt the two EF pulses' start-to-start separation (`k_ef_ramsey` places
    them `wait + ef` apart). φ = 0 is the aligned pair, i.e. a full EF π. Three waits pin the RATE —
    no fit, no fringe envelope, no 4 × 11 × 36-shot sweep through a 3-level classifier.

    The EF X90 is ONE batch long on purpose. The model applies one rotation per batch about that
    batch's axis, so a single-batch pulse is exactly a fixed-axis rotation and the two-axis formula
    above is exact; the default 4-batch gate would ramp its own axis mid-pulse (the detuned Rabi
    tilt), which no fixed-axis target can describe. The GE prep stays 4 batches — its carrier is
    resonant, so it has no ramp.

    What the populations CANNOT see is the ramp's SIGN: reflecting through the pseudo-xz-plane maps
    φ → −φ and leaves every population of a sequence starting at a pole invariant. So ±60 pins the
    rate for a carrier above AND below f_ef, while the sign lock-step the class turns it into (the
    V-fit vertex at applied = −δ, and the `EF/freq` proposal moving toward the true carrier) is
    arithmetic — the GE Frequency code, gated host-pure in test_cal_host.py.

    Tolerance 0.025, not 0.02: the model's per-batch demod recovers the drive axis from 16 samples,
    so the counter-rotating term at f_drive + f_ef leaves ~0.015 rad of axis residual per pulse
    (measured worst case here: 0.007 in population). That is deterministic, not statistical, and it
    is exactly the bound test_cal::test_frequency_recovers_detuning carries — 0.05 on <sz>, which is
    0.025 on a population."""
    _, m = cosim_2q1c
    q = 0
    detuned = float(units.code_to_freq(units._freq_code(EF_F_EF, m.params) + d0_code, m.params))
    cfg = _ef_cfg(m, q)
    cfg[f"qubit/{q}/EF/freq"] = detuned                   # config EF carrier off the true f_ef by δ
    cfg[f"qubit/{q}/EF/x90/env"] = "square"               # ONE batch — see the docstring
    cfg[f"qubit/{q}/EF/x90/dur"] = _s(1, m)
    efp = ef_pulse(cfg, q, m)
    rabi_ef = (math.pi / 2) / gate_sigma(m, efp, detuned, efp.amp_code())    # exact EF X90s

    ef = resolve(cfg, q, "EF/x90", m).dur
    waits = (7, 16, 33)                                  # phi ~ 0.74, 1.56, 3.13 rad at |delta| = 60
    ax = Axis.wait_batches(max(waits), 0, 1, m)          # one point; the probe rewrites x0 per wait
    seq = [Gate("x90"), Gate("x90"), Gate("EF/x90"), Idle(ax), Gate("EF/x90")]
    progs = probe_progs(cosim_2q1c, cfg, [q], {q: seq}, {q: (ax,)}, label="efram")
    spec = _ef_spec(m, cfg, q, rabi_ef)                  # the model's f_ef is the TRUE one
    p = Probe(cosim_2q1c, progs)

    for w in waits:
        pops = p.state(spec, {q: {"x0": w}})["populations"]
        phi = 2 * math.pi * d0_code * (w + ef) / (1 << 12)
        want = [0.0, (1 - math.cos(phi)) / 2, (1 + math.cos(phi)) / 2]
        print(f"\n[ef-freq d={d0_code:+d} dt={w + ef}] phi={phi:+.4f} pops={np.round(pops, 4).tolist()} "
              f"want={np.round(want, 4).tolist()}")
        assert pops == pytest.approx(want, abs=0.025), \
            f"d={d0_code:+d}, dt={w + ef}: the EF X90 pair landed at {pops}, a ramped axis wants {want}"


# ── L0: the EF Phase write-back (spec 04 §5 / X4) ──

_EF_MEANS = np.array([[10.0, 0.0], [-5.0, 8.66], [-5.0, -8.66]])   # |0>/|1>/|2> IQ centroids


def _ef_clf(seed=3):
    """A 3-level ClassifierN on well-separated synthetic clusters (test_twoqubit's geometry)."""
    rng = np.random.default_rng(seed)
    return ClassifierN([_EF_MEANS[k] + 0.1 * rng.standard_normal((30, 2)) for k in range(3)])


def _levels_iq(P, shots):
    """A RAW `out` array whose per-point P(|2>) is exactly `P`: round(p·shots) shots on the |2>
    centroid and the rest on |1> (the kernels' point-major 2·npts·shots cursor layout). After a GE π
    the population lives in {|1>, |2>}, so those are the only two clusters a shot can land in."""
    iq = np.zeros((len(P), shots, 2))
    for i, p in enumerate(np.clip(P, 0.0, 1.0)):
        n2 = int(round(float(p) * shots))
        iq[i, :n2] = _EF_MEANS[2]
        iq[i, n2:] = _EF_MEANS[1]
    return iq.reshape(-1)


def test_ef_phase_writes_the_vz_pair(responder):
    """L0 (spec 04 §5 / X4) — the EF keys land: one `EFPhase` run on this file's EF Config drives
    both circuits end-to-end (GE π prep → retune to f_EF → the two Rz(±π/2)-decorated 3-EF-X90
    sequences → RAW P(|2>) through the 3-level classifier) and writes
    `qubit/0/EF/x90/vz` as ONE crossing in BOTH slots.

    Host-pure through the shared `responder` (01 §2.2): the real two-sequence `compile_kernel`, the
    real `sweep_levels` classifier decode and the real `_line_crossing` all run; only the RISC-V
    execution is replaced. The answer is the pair of sequences from first principles — both are
    three-EF-X90 composites that sit on the pseudo-equator when the frame is right, so their P(|2>)
    are 1/2 at the calibrated phase and depart with OPPOSITE sign, i.e. (1 ∓ sin(φ − φ*))/2 in the
    swept virtual-Z.

    The co-sim version planted NOTHING (a shift-free model) and could only say the crossing sat
    within 0.15 rad of 0 through 2 × 7 × 24 projective shots; with no shot noise it is exactly 0,
    which is the tightening. That the crossing TRACKS a planted φ*, and that `relative_phase`
    re-centres the sweep on the stored pair, are owned by
    test_twoqubit.py::test_ef_phase_recovers_planted_vz. What the co-sim run added over that was the
    RTL frame slip of the GE→EF retune — which conjugates out of a z-basis population (the kernel
    docstring) and so was never observable in these counts at all; the honest home for the retune's
    effect on the state is the L2 EF probes above."""
    m = SocMap(SocParams.load(SIM2Q1C))
    q, points, shots, span = 0, 7, 24, 0.4
    cfg = _ef_cfg(m, q)
    x = Axis.phase(-span, span, points).values               # the class's own axis
    r = responder(SIM2Q1C)
    runs = []

    @r.answer
    def _(progs, params):
        seq = len(r.setups)                                  # one setup per circuit, in order
        runs.append(seq)
        slope = -1.0 if seq == 1 else +1.0                   # Y180_X90 first, then X180_Y90
        return {q: {"out": _levels_iq(0.5 * (1 + slope * np.sin(x)), shots)}}   # phi* = 0

    cal = EFPhase(cfg, q, _ef_clf(), points=points, span=span, shots=shots)
    res = cal.run(r.drv)
    print(f"\n[ef-phase] seqs={runs} recovered={cal.recovered_vz[q]:+.6f} "
          f"proposal={res.proposal.get(f'qubit/{q}/EF/x90/vz')}")

    assert res.ok and not cal.fallback[q], "the EF line crossing failed"
    assert runs == [1, 2], "both qcal sequences must compile and run, in order"
    assert len(r.setups) == 2, "one compile + setup per sequence"
    v = res.proposal[f"qubit/{q}/EF/x90/vz"]
    assert v[0] == v[1] == cal.recovered_vz[q]               # ONE crossing, BOTH slots
    # the only residue left is the RAW cursor's integer-shot quantization — P lands on a 1/24 grid,
    # so the two fitted lines cross ~1e-4 off zero. 1e-3 is 150x tighter than the co-sim 0.15.
    assert abs(v[0]) < 1e-3, f"an unbiased pair of lines crosses at 0, got {v[0]:+.6f}"


@pytest.mark.cosim
@pytest.mark.parametrize("planted", [0.0, 0.6])
def test_ef_phase_x_gate_recovers_the_planted_axis(cosim_2q1c, planted):
    """L2 (spec 14 F1) — `EFPhase(gate='X')`'s circuit, on the state. After the GE π prep the circuit
    is EF-X90 · EF-X · EF-X90, a 2π rotation inside {|1>, |2>} that returns to |1> only when the EF
    X's own AXIS matches the EF X90s'. Plant that axis by giving the EF X90s a phase of `planted`
    and sweep the EF X's own axis around it — exactly the config the counts version used.

    Analytic, and exact here: every carrier is resonant so each pulse has ONE fixed pseudo-equatorial
    axis, and the EF X is the X6Y3 shape (double LENGTH, same amplitude) so its drive integral is 2σ,
    i.e. exactly π when the EF X90 is π/2. Rodrigues through R_a(π/2) · R_{a+δ}(π) · R_a(π/2) from
    |1> gives the composite −(cos δ · I + i sin δ · σ_y) on {|1>, |2>}, so

        P(|2>) = sin^2(delta),   P(|1>) = cos^2(delta),   delta = phi_X - phi_X90

    — MINIMAL on alignment with period π, which is the fringe the class's cosine fit locates. Three
    points at δ = 0, π/4, π/2 pin the whole fringe (its zero, its half and its peak) to 0.02, where
    the counts version placed the recovered axis to 0.3 rad mod π through 13 × 32 projective shots
    and needed a trained 3-level classifier to see P(|2>) at all. The fit that turns this fringe into
    `qubit/0/EF/x/phase` — and that the X mode writes an axis, NOT the virtual-Z pair — is host-pure
    in test_twoqubit.py."""
    _, m = cosim_2q1c
    q = 0
    cfg = _ef_cfg(m, q)
    cfg[f"qubit/{q}/EF/x90/phase"] = planted       # the reference axis both EF X90s sit on
    cfg[f"qubit/{q}/EF/x/env"] = "square"          # the EF X: double LENGTH, same amp → π
    cfg[f"qubit/{q}/EF/x/dur"] = _s(8, m)
    cfg[f"qubit/{q}/EF/x/amp"] = float(cfg[f"qubit/{q}/EF/x90/amp"])
    cfg[f"qubit/{q}/EF/x/phase"] = 0.0             # the swept offset carries the whole axis

    efp = ef_pulse(cfg, q, m)
    rabi_ef = (math.pi / 2) / gate_sigma(m, efp, EF_F_EF, efp.amp_code())
    ax = Axis.phase(0.0, 0.0, 1)                          # one point; the probe rewrites x0 per δ
    seq = [Gate("x90"), Gate("x90"), Gate("EF/x90"), Gate("EF/x", phase=ax), Gate("EF/x90")]
    progs = probe_progs(cosim_2q1c, cfg, [q], {q: seq}, {q: (ax,)}, label="efphase")
    spec = _ef_spec(m, cfg, q, rabi_ef)
    p = Probe(cosim_2q1c, progs)

    for d in (0.0, math.pi / 4, math.pi / 2):
        pops = p.state(spec, {q: {"x0": pack16(units._phase_code(planted + d))}})["populations"]
        want = [0.0, math.cos(d) ** 2, math.sin(d) ** 2]
        print(f"\n[ef-phase-X planted={planted:+.2f} delta={d:+.4f}] pops={np.round(pops, 4).tolist()} "
              f"want={np.round(want, 4).tolist()}")
        assert pops == pytest.approx(want, abs=0.02), \
            f"the EF X {d:+.4f} rad off the EF X90s left {pops}, not the sin^2 fringe {want}"


# ── JAZZ: residual-ZZ Ramsey (spec two-qubit/01 §4.3 / Q3) — the --slow two-qubit anchor ──

def _rabi_pi(m):
    """The Rabi rate that makes the default X90·X90 |1> prep a full π (mirrors test_cal._rabi_pi)."""
    return float(math.pi / gate_sigma(m, Pulse(GATE_ENV, freq_hz=F_GE, amp=0.99), F_GE,
                                      units._amp_code(0.99)))


@pytest.mark.cosim
@pytest.mark.slow
def test_jazz_runs_end_to_end_no_spurious_zz(cosim_2q1c):
    """Q3 light gate (spec 01 §4.3): the `JAZZ` cal runs end-to-end on the real 3-core build — `k_jazz`
    compiled for BOTH roles (control / target) walking the shared grid, the four sequences rerun over
    one resident image, both fringes fit — and with NO planted ZZ (ζ=0) the two control states give the
    SAME fringe (the applied detuning), i.e. no spurious control-conditional split. This pins the
    kernel + cal + two-qubit-model integration and the absence of a readout/timing artifact.

    That the ZZ split TRACKS a planted ζ (sign and non-vanishing) is the model physics, verified
    deterministically host-side in test_models.test_twoqubit_jazz_zz_physics. Recovering the ζ MAGNITUDE
    to fit tolerance end-to-end in co-sim is relaxation-SNR-limited (the model's only reset is
    relaxation, which also decoheres the multi-pulse Ramsey) and is deferred (spec 01 §4.3 note)."""
    drv, m = cosim_2q1c
    ctrl, tgt = 0, 1
    code = {0: 2048, 1: 1024}
    detune = 1.5e6
    cfg = Config()
    for q in (ctrl, tgt):
        cfg[f"qubit/{q}/freq"] = F_GE
        cfg[f"qubit/{q}/x90/amp"] = 0.5
        cfg[f"readout/{q}/freq"] = float(units.demod_code_to_freq(code[q], m.params))
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = _s(56, m)
        cfg[f"readout/{q}/demod/dur"] = _s(40, m)
    cfg["reset/relax"] = _s(800, m)
    cfg["two_qubit/(0, 1)/core"] = 2

    rabi = _rabi_pi(m)                                               # 2 X90 = π, one X90 ≈ π/2
    drv.sim.set_model({"kind": "twoqubit", "control": ctrl, "target": tgt, "coupler": 2,
                       "f_ge": [F_GE, F_GE], "f_ef": [F_GE / 2, F_GE / 2],
                       "rabi_ge": [rabi, rabi], "rabi_ef": [0.0, 0.0],
                       "zz_rad_per_batch": 0.0,                      # NO planted ZZ ⇒ no split expected
                       "readout_code": [code[ctrl], code[tgt]], "readout_amp": [0.0, 16000.0],
                       "level_phases": [0.0, math.pi, math.pi / 2],  # 2-level target: |0>/|1> π apart
                       "collapse": True, "t1": 250, "noise_scale": 250.0, "noise_seed": 4})

    cal = JAZZ(cfg, (ctrl, tgt), detune=detune, points=10, t0=40e-9, dt=80e-9, shots=40)
    r = cal.run(drv)
    d = r.data[(ctrl, tgt)]
    zz = r.proposal.get("two_qubit/(0, 1)/ZZ11", float("nan"))
    print(f"\n[jazz z=0] f0={d['f0'] / 1e6:+.3f}MHz f1={d['f1'] / 1e6:+.3f}MHz ZZ={zz / 1e6:+.3f}MHz ok={r.ok}")
    assert r.ok, "JAZZ fringe fits failed"
    # both control states see only the applied detuning (no ZZ) ⇒ same fringe, no spurious split
    assert abs(abs(d["f0"]) - detune) < 0.4e6, f"control-|0> fringe not at the detuning: {d['f0'] / 1e6:.3f} MHz"
    assert abs(abs(d["f1"]) - detune) < 0.4e6, f"control-|1> fringe not at the detuning: {d['f1'] / 1e6:.3f} MHz"
    assert abs(zz) < 0.4e6, f"spurious ZZ split with no planted ζ: {zz / 1e6:.3f} MHz"


# ── L1: CZ resonance & conditionality (spec two-qubit/01 §4.4-4.5 / Q4) ──

F_CZ = 25e6                       # the model's |11>-|02> resonance = |f_ef[1] − f_ge[0]| (code 1024)
CZ_BATCHES = 30                   # the coupler drive length in batches


def _cz_cfg(m, ctrl=0, tgt=1, cz_amp=0.35):
    """The two-qubit co-sim Config: two qubits + a coupler-drive CZ entry at F_CZ (spec 01 §2), the
    coupler on core 2 of sim-2q1c. Two demod codes so the qubits' readouts are frequency-multiplexed."""
    code = {ctrl: 2048, tgt: 1024}
    cfg = Config()
    for q in (ctrl, tgt):
        cfg[f"qubit/{q}/freq"] = F_GE
        cfg[f"qubit/{q}/x90/amp"] = 0.5
        cfg[f"readout/{q}/freq"] = float(units.demod_code_to_freq(code[q], m.params))
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = _s(56, m)
        cfg[f"readout/{q}/demod/dur"] = _s(40, m)
    cfg["reset/relax"] = _s(RELAX_CZ, m)
    cfg["two_qubit/(0, 1)/core"] = 2
    cfg["two_qubit/(0, 1)/CZ/freq"] = F_CZ
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "C0_1", "time": _s(CZ_BATCHES, m), "kwargs": {"amp": cz_amp, "phase": 0.0},
         "env": "square"},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.0}},
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": 0.0}},
    ]
    return cfg, code


def _abs_window(drv, dac, handle):
    """(absolute batch start, length, carrier code) of a captured DAC's single active window."""
    t0, cap = drv.sim.dac_capture_get(handle)
    act = np.abs(cap).sum(axis=1) > 0
    if not act.any():
        return None
    s = int(np.argmax(act))
    e = len(act) - 1 - int(np.argmax(act[::-1]))
    return t0 + s, e - s + 1, _carrier_code(cap[s:e + 1])


@pytest.mark.cosim
@pytest.mark.batch_cap(52_000)
def test_cz_3core_drives_coupler_at_fcz_aligned(cosim_2q1c):
    """FLOOR: ~31 k = 3 core images (~10 k each over AXI) + one sized capture. The claim is a
    3-core lock-step alignment, so all three images are the claim; the module docstring's table has
    the full accounting.

    Q4 integration gate (spec 01 §4.4 / §7): the 3-core `k_cz_pop` build drives the COUPLER at f_CZ,
    for the CZ length, in a window that ABUTS the |11> prep and ends exactly SEP before the readout —
    the novel 3-core-timed coupler-in-lockstep path (JAZZ only used two cores). Captured off the real
    DACs with the model OFF, so it is deterministic and NOT relaxation-SNR-limited.

    This pins the kernel + fabric integration (the hard, novel part). The full P(11)-dip / conditionality
    R RECOVERY through the two-qubit COLLAPSE readout is relaxation-SNR-sensitive on this model (its only
    reset is relaxation, which also decoheres the multi-pulse sequence — the JAZZ ζ-magnitude precedent,
    spec 01 §4.3), and the recovery PHYSICS is proven deterministically host-side (test_twoqubit's
    resonance-dip fit + test_models.test_twoqubit_cz_conditionality_R_peaks_at_the_cz); a tight cosim
    recovery gate wants a long non-decohering grid and is deferred (spec 01 §4.4 note)."""
    drv, m = cosim_2q1c
    cfg, _ = _cz_cfg(m)
    drv.sim.set_model({"kind": "zero"})
    czd = _cz_dur_batches(cfg, (0, 1), m)
    xd = gate_pulse(cfg, 0, m).dur_batches(m, GATE_CH)
    caps = {d: drv.sim.dac_capture_arm(d, NCAP_RUN3) for d in (0, 3, 2)}  # control gate, coupler, readout
    CZSweep(cfg, (0, 1), "freq", span=0.0, points=1, shots=1).run(drv)
    prep = _abs_window(drv, 0, caps[0])
    cz = _abs_window(drv, 3, caps[3])
    ro = _abs_window(drv, 2, caps[2])
    f_cz_code = units._freq_code(F_CZ, m.params)
    print(f"\n[cz-3core] czd={czd} xd={xd} f_cz_code={f_cz_code}\n"
          f"  prep(DAC0)={prep} coupler(DAC3)={cz} readout(DAC2)={ro}")
    assert prep and cz and ro, "a prep / coupler / readout window was silent"
    assert cz[1] == czd, f"coupler window {cz[1]} != CZ length {czd}"
    assert abs(cz[2] - f_cz_code) < 40, f"coupler not at f_CZ: code {cz[2]} vs {f_cz_code}"
    assert prep[1] == 2 * xd, f"prep {prep[1]} != two X90s ({2 * xd})"
    assert 0 <= cz[0] - (prep[0] + prep[1]) <= 2, \
        f"coupler must abut the |11> prep end: prep_end={prep[0] + prep[1]} cz_start={cz[0]}"
    assert abs((ro[0] - (cz[0] + cz[1])) - SEP) <= 1, \
        f"coupler must end SEP before readout: gap={ro[0] - (cz[0] + cz[1])} vs SEP={SEP}"


# ── L1: two-qubit-drive CZ (spec 04 §4.1 / X2): the 2-core drive-form fire off the real DACs ──

DRIVE_F_GE = {0: 50e6, 1: 75e6}   # DISTINCT GE carriers (codes 2048/3072) — the retune is per core
DRIVE_F_CZ = 25e6                 # the shared in-band CZ tone (code 1024)
DRIVE_REL_PHASE = 0.267           # the target line's calibrated relative phase (rad)


def _cz_drive_cfg(m):
    """The two-qubit-drive co-sim Config (spec 04 §1): NO coupler core — both CZ lines on the pair's
    own gate channels at `CZ/freq`, equal amps, the TARGET line carrying the relative phase."""
    code = {0: 2048, 1: 1024}
    cfg = Config()
    for q in (0, 1):
        cfg[f"qubit/{q}/freq"] = DRIVE_F_GE[q]
        cfg[f"qubit/{q}/x90/amp"] = 0.5
        cfg[f"readout/{q}/freq"] = float(units.demod_code_to_freq(code[q], m.params))
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = _s(56, m)
        cfg[f"readout/{q}/demod/dur"] = _s(40, m)
    cfg["reset/relax"] = _s(RELAX_CZ, m)
    cfg["two_qubit/(0, 1)/CZ/freq"] = DRIVE_F_CZ
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "Q0", "time": _s(CZ_BATCHES, m), "env": "square",
         "kwargs": {"amp": 0.35, "phase": 0.0}},
        {"channel": "Q1", "time": _s(CZ_BATCHES, m), "env": "square",
         "kwargs": {"amp": 0.35, "phase": DRIVE_REL_PHASE}},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.0}},
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": 0.0}},
    ]
    return cfg


def _abs_windows(drv, handle):
    """[(absolute batch start, length, carrier code, samples)] of EVERY active window of a captured
    DAC — the multi-window version of `_abs_window` (a drive-form gate DAC fires prep AND cz)."""
    t0, cap = drv.sim.dac_capture_get(handle)
    act = np.abs(cap).sum(axis=1) > 0
    wins, s = [], None
    for i, a in enumerate(act):
        if a and s is None:
            s = i
        if not a and s is not None:
            wins.append((t0 + s, i - s, _carrier_code(cap[s:i]), cap[s:i]))
            s = None
    if s is not None:
        wins.append((t0 + s, len(act) - s, _carrier_code(cap[s:]), cap[s:]))
    return wins


@pytest.mark.cosim
@pytest.mark.batch_cap(40_000)
def test_cz_drive_form_two_tone_fire_aligned(cosim):
    """FLOOR: ~26 k = 2 core images (~10 k each over AXI) + sized captures — a two-core lock-step
    claim needs both images. The module docstring's table has the full accounting.

    X2 integration gate (spec 04 §4.1 / §5, the drive-form mirror of
    test_cz_3core_drives_coupler_at_fcz_aligned): ONE drive-form `k_cz_pop` point on the 2-core
    sim-2q build, captured off the real DACs with the model OFF (deterministic). BOTH gate DACs fire
    the CZ tone at the f_CZ code for the CZ length, in LOCK-STEP (same absolute start); each core's
    GE prep before it is at its OWN GE code (the f_GE → f_CZ retune round-trip is real, not a
    carrier that was parked at f_CZ); the prep ends exactly LEAD before the tone (the retune's
    phasor-regen gap, the k_ef precedent — the drive form's 'abutting', spec 04 §1) and the tone
    ends SEP before the shared readout. The raw samples also expose the two lines' RELATIVE phase:
    the target tone leads the control's by the configured `kwargs/phase` (both NCOs retuned to the
    same f_CZ at the same pinned batch, so the slot phases are the only difference)."""
    drv, m = cosim
    cfg = _cz_drive_cfg(m)
    drv.sim.set_model({"kind": "zero"})
    czd = _cz_dur_batches(cfg, (0, 1), m)
    xd = gate_pulse(cfg, 0, m).dur_batches(m, GATE_CH)
    # sim-2q has no dac_map → SocMap defaults: gate DACs 0/1, both readout drives summed on DAC 14
    rd = m.ro_dac(0)
    caps = {d: drv.sim.dac_capture_arm(d, NCAP_RUN2)
            for d in (m.gate_dac(0), m.gate_dac(1), rd)}
    CZSweep(cfg, (0, 1), "freq", span=0.0, points=1, shots=1).run(drv)
    wins = {d: _abs_windows(drv, caps[d]) for d in caps}

    ge_code = {q: units._freq_code(DRIVE_F_GE[q], m.params) for q in (0, 1)}
    fcz_code = units._freq_code(DRIVE_F_CZ, m.params)
    print(f"\n[cz-drive] czd={czd} xd={xd} f_cz_code={fcz_code}\n"
          f"  DAC0={[(s, n, c) for s, n, c, _ in wins[0]]}\n"
          f"  DAC1={[(s, n, c) for s, n, c, _ in wins[1]]}\n"
          f"  RO(DAC{rd})={[(s, n, c) for s, n, c, _ in wins[rd]]}")
    for q in (0, 1):
        assert len(wins[q]) == 2, f"core {q}: expected GE-prep + CZ windows, got {len(wins[q])}"
    (ps0, pl0, pc0, _), (cs0, cl0, cc0, w0) = wins[0]
    (ps1, pl1, pc1, _), (cs1, cl1, cc1, w1) = wins[1]
    # each core's GE prep at its OWN carrier — proves the retune starts from f_GE every shot
    assert pl0 == 2 * xd == pl1, f"preps {pl0}/{pl1} != two X90s ({2 * xd})"
    assert abs(pc0 - ge_code[0]) < 40, f"core 0 prep not at its f_GE: {pc0} vs {ge_code[0]}"
    assert abs(pc1 - ge_code[1]) < 40, f"core 1 prep not at its f_GE: {pc1} vs {ge_code[1]}"
    # both CZ tones: the f_CZ code, the CZ length, the same absolute start (lock-step)
    assert cl0 == czd == cl1, f"CZ windows {cl0}/{cl1} != CZ length {czd}"
    assert abs(cc0 - fcz_code) < 40, f"control line not at f_CZ: {cc0} vs {fcz_code}"
    assert abs(cc1 - fcz_code) < 40, f"target line not at f_CZ: {cc1} vs {fcz_code}"
    # every core's grid starts from the put network's barrier release (k_batched), so the two lines
    # are aligned EXACTLY — no `now()`-per-core skew to tolerate (03-plan §3.3)
    assert cs0 == cs1, f"the two CZ lines must fire in lock-step: {cs0} vs {cs1}"
    # the prep ends exactly LEAD before the tone; the tone ends SEP before the shared readout
    assert cs0 - (ps0 + pl0) == LEAD == cs1 - (ps1 + pl1), \
        f"prep→tone gap must be the LEAD phasor-regen gap: {cs0 - (ps0 + pl0)} vs {LEAD}"
    assert len(wins[rd]) == 1, "expected ONE summed readout window"
    ro_start = wins[rd][0][0]
    assert abs((ro_start - (cs0 + cl0)) - SEP) <= 1, \
        f"CZ must end SEP before readout: gap={ro_start - (cs0 + cl0)} vs SEP={SEP}"
    # the relative phase off the raw samples: project each tone onto ONE ABSOLUTE-TIME carrier frame
    # (the NCO phase is time-referenced — a start skew windows the same free-running carrier), so
    # the projected difference is exactly the slot-phase difference = the target line's phase
    w = math.pi * fcz_code / (1 << 15)                          # rad per DAC sample

    def tone_phase(samples, start_batch):
        x = np.asarray(samples, float).reshape(-1)
        n = start_batch * 16 + np.arange(len(x))                # absolute DAC sample index
        z = complex(np.sum(x * np.exp(-1j * w * n)))
        return math.atan2(z.imag, z.real)

    dphi = (tone_phase(w1, cs1) - tone_phase(w0, cs0) + math.pi) % (2 * math.pi) - math.pi
    print(f"  [cz-drive] relative phase dphi={dphi:+.3f} rad (configured {DRIVE_REL_PHASE})")
    assert dphi == pytest.approx(DRIVE_REL_PHASE, abs=0.05), \
        f"target−control tone phase {dphi:+.3f} != configured {DRIVE_REL_PHASE}"


# ── L1: SpectatorPhase geometry (spec 04 §4.5 / X3): the 3-core drive-form bracket ──

SPECT_F_GE = 100e6                # the spectator's own GE carrier (code 4096, distinct from the pair)


@pytest.mark.cosim
@pytest.mark.batch_cap(52_000)
def test_spectator_ramsey_brackets_the_cz_fire(cosim_2q1c):
    """FLOOR: ~35 k = 3 core images (~10 k each over AXI) + sized captures on 4 DACs. The bracket is
    a 3-core geometry, so all three images are the claim; the module docstring's table has the full
    accounting.

    X3 optional gate (spec 04 §5): SpectatorPhase's 3-core geometry off the real DACs, model OFF
    (deterministic) — ONE point/shot of the class's exact compile recipe on sim-2q1c (cores 0/1 =
    the drive-form pair on DACs 0/1, core 2 = the spectator on DAC 3). The pair fires its two CZ
    lines at the f_CZ code in lock-step with the conditional |1> prep (sp=1 bound) ending LEAD
    before the tone; the SPECTATOR — the COUPLER_FORM ACTIVE Ramsey, window czd + 2·LEAD — plays
    its two Y90s at its OWN GE code so they BRACKET the tone with ~LEAD margin each side. The
    margins are the design (SpectatorPhase docstring): the spectator's 1-slot gate table makes its
    init preamble shorter than the pair's 2-slot ones, so the old kernel's `now() + period` grid ran
    tens of batches EARLY (~58 measured). The universal kernel removes both halves of that: every
    core's tables are padded to equal slot counts AND the grid starts from the put network's
    barrier release, so the bracket's two margins are the compiler's, exactly. Only the spectator
    fires the readout, SEP after its close."""
    drv, m = cosim_2q1c
    cfg = _cz_drive_cfg(m)
    cfg["qubit/2/freq"] = SPECT_F_GE
    cfg["qubit/2/x90/amp"] = 0.5
    cfg["readout/2/freq"] = float(units.demod_code_to_freq(3072, m.params))
    cfg["readout/2/amp"] = 0.5
    cfg["readout/2/dur"] = _s(56, m)
    cfg["readout/2/demod/dur"] = _s(40, m)
    drv.sim.set_model({"kind": "zero"})
    czd = _cz_dur_batches(cfg, (0, 1), m)
    xd = gate_pulse(cfg, 2, m).dur_batches(m, GATE_CH)
    cfg["two_qubit/(0, 1)/CZ/pulse"].append(              # the spectator's own vz entry in the list
        {"channel": "Q2", "env": "virtualz", "kwargs": {"phase": 0.0}})
    caps = {d: drv.sim.dac_capture_arm(d, NCAP_RUN3) for d in (0, 1, 3, 2)}
    SpectatorPhase(cfg, (0, 1), spectator=2, conditional=0, points=1, shots=1).run(drv)   # sp = 0, then 1
    wins = {d: _abs_windows(drv, caps[d]) for d in caps}
    # the class reruns the conditional prep off (sp = 0) then on (sp = 1); the geometry claim is
    # about the CONDITIONAL shot, so keep the windows after the first shot's readout
    split = wins[2][0][0]                        # the first shot's readout (a handle fetches once)
    wins = {d: [w for w in v if w[0] > split] for d, v in wins.items()}

    fcz_code = units._freq_code(DRIVE_F_CZ, m.params)
    sge_code = units._freq_code(SPECT_F_GE, m.params)
    print(f"\n[spectator] czd={czd} xd={xd} f_cz_code={fcz_code}\n"
          f"  cond(DAC0)={[(s, n, c) for s, n, c, _ in wins[0]]}\n"
          f"  pair(DAC1)={[(s, n, c) for s, n, c, _ in wins[1]]}\n"
          f"  spect(DAC3)={[(s, n, c) for s, n, c, _ in wins[3]]}\n"
          f"  RO(DAC2)={[(s, n, c) for s, n, c, _ in wins[2]]}")
    # the pair: conditional = |1> prep (2 X90s at its GE code) + its CZ line; the other = CZ only
    assert len(wins[0]) == 2 and len(wins[1]) == 1
    (pps, ppl, ppc, _), (cs0, cl0, cc0, _) = wins[0]
    (cs1, cl1, cc1, _) = wins[1][0]
    assert ppl == 2 * xd and abs(ppc - units._freq_code(DRIVE_F_GE[0], m.params)) < 40
    assert cl0 == czd == cl1
    assert abs(cc0 - fcz_code) < 40 and abs(cc1 - fcz_code) < 40
    assert cs0 == cs1, f"CZ lines not in lock-step: {cs0} vs {cs1}"
    assert cs0 - (pps + ppl) == LEAD, f"cond prep→tone gap {cs0 - (pps + ppl)} != LEAD {LEAD}"
    # the spectator: two GE-code Y90s that BRACKET the pair's tone (sub-2·LEAD margins each side —
    # nominal LEAD ± the deterministic preamble-offset the docstring pins)
    assert len(wins[3]) == 2, f"spectator: expected prep + close Y90s, got {len(wins[3])}"
    (sps, spl, spc, _), (scs, scl, scc, _) = wins[3]
    assert spl == xd == scl
    assert abs(spc - sge_code) < 40 and abs(scc - sge_code) < 40, "spectator not at its OWN GE code"
    head, tail = cs0 - (sps + spl), scs - (cs0 + czd)
    print(f"  [spectator] bracket margins: head={head} tail={tail} (window czd + 2*LEAD = {czd + 2 * LEAD})")
    assert 0 < head < 2 * LEAD, \
        f"spectator prep must end BEFORE the tone starts (margin < 2*LEAD): head={head}"
    assert 0 < tail < 2 * LEAD, \
        f"spectator close must fire AFTER the tone ends (margin < 2*LEAD): tail={tail}"
    assert head + tail == 2 * LEAD, \
        f"bracket margins must sum to the 2*LEAD slack exactly: {head} + {tail}"
    # only the spectator reads out, SEP after its close
    assert len(wins[2]) == 1, "expected ONE readout window (the spectator's)"
    assert abs((wins[2][0][0] - (scs + scl)) - SEP) <= 1, \
        f"readout must open SEP after the close: gap={wins[2][0][0] - (scs + scl)} vs SEP={SEP}"


# ── L1: EF-sandwich CZ playback (spec 04 §1 / X4): the shelved train off the real DACs ──

SAND_F_EF = 125e6                 # the shelf's EF carrier (code 5120, distinct from the GE/CZ codes)


def _cz_sandwich_cfg(m):
    """`_cz_drive_cfg` rebuilt as an EF-sandwich pair (the X6Y3 (5,6)/(6,7) layout on the sim-2q
    labels): qubit 1 is the SHELF — its EF keys land in the config and the pulse list brackets the
    two drives with the identical `single_qubit/1/EF/X/pulse` string references."""
    cfg = _cz_drive_cfg(m)
    cfg["qubit/1/EF/freq"] = SAND_F_EF
    cfg["qubit/1/EF/x/amp"] = 0.6
    pl = cfg["two_qubit/(0, 1)/CZ/pulse"]
    cfg["two_qubit/(0, 1)/CZ/pulse"] = (["single_qubit/1/EF/X/pulse"] + pl[:2]
                                        + ["single_qubit/1/EF/X/pulse"] + pl[2:])
    return cfg


@pytest.mark.cosim
@pytest.mark.batch_cap(40_000)
def test_cz_sandwich_dac_train_aligned(cosim):
    """FLOOR: ~27 k = 2 core images (~10 k each over AXI) + sized captures — the shelf/partner
    lock-step claim needs both. The module docstring's table has the full accounting.

    X4 co-sim gate (spec 04 §5): the sandwich DAC capture — ONE drive-form `k_cz_pop` point on
    sim-2q with a synthetic sandwich config, model OFF (deterministic, seconds). The SHELF core's
    gate DAC shows the full window train prep(f_GE) → EF-X(f_EF) → cz(f_CZ) → EF-X(f_EF), each
    segment at its carrier code with exactly the LEAD retune gap between segments and SEP before
    the readout; the PARTNER core plays prep(f_GE) → cz(f_CZ) with its cz tone in LOCK-STEP with
    the shelf's — the padded 3-slot gate table keeps the two boot preambles equal (the X3
    unequal-table grid finding), so the ≤2-batch equal-table skew still holds."""
    drv, m = cosim
    cfg = _cz_sandwich_cfg(m)
    drv.sim.set_model({"kind": "zero"})
    czd = _cz_dur_batches(cfg, (0, 1), m)
    shelf = cz_sandwich(cfg, (0, 1))
    assert shelf == 1
    efd = resolve(cfg, shelf, "EF/x", m).dur
    xd = gate_pulse(cfg, 0, m).dur_batches(m, GATE_CH)
    rd = m.ro_dac(0)
    caps = {d: drv.sim.dac_capture_arm(d, NCAP_RUN2)
            for d in (m.gate_dac(0), m.gate_dac(1), rd)}
    CZSweep(cfg, (0, 1), "freq", span=0.0, points=1, shots=1).run(drv)
    wins = {d: _abs_windows(drv, caps[d]) for d in caps}

    ge_code = {q: units._freq_code(DRIVE_F_GE[q], m.params) for q in (0, 1)}
    ef_code = units._freq_code(SAND_F_EF, m.params)
    fcz_code = units._freq_code(DRIVE_F_CZ, m.params)
    print(f"\n[cz-sandwich] czd={czd} xd={xd} efd={efd}\n"
          f"  partner(DAC0)={[(s, n, c) for s, n, c, _ in wins[0]]}\n"
          f"  shelf(DAC1)={[(s, n, c) for s, n, c, _ in wins[1]]}\n"
          f"  RO(DAC{rd})={[(s, n, c) for s, n, c, _ in wins[rd]]}")
    # the shelf: prep(f_GE) → EF-X(f_EF) → cz(f_CZ) → EF-X(f_EF), LEAD gaps between segments
    assert len(wins[1]) == 4, f"shelf: expected prep+ef+cz+ef windows, got {len(wins[1])}"
    (ps, pl, pc, _), (e1s, e1l, e1c, _), (czs, czl, czc, _), (e2s, e2l, e2c, _) = wins[1]
    assert pl == 2 * xd and abs(pc - ge_code[1]) < 40, "shelf prep not two X90s at its OWN f_GE"
    assert e1l == efd == e2l, f"EF-X windows {e1l}/{e2l} != EF X length {efd}"
    assert abs(e1c - ef_code) < 40 and abs(e2c - ef_code) < 40, "EF-X not at f_EF"
    assert czl == czd and abs(czc - fcz_code) < 40, "shelf cz tone not at f_CZ for the CZ length"
    assert e1s - (ps + pl) == LEAD, f"prep→EF-X gap {e1s - (ps + pl)} != LEAD {LEAD}"
    assert czs - (e1s + e1l) == LEAD, f"EF-X→cz gap {czs - (e1s + e1l)} != LEAD {LEAD}"
    assert e2s - (czs + czl) == LEAD, f"cz→EF-X gap {e2s - (czs + czl)} != LEAD {LEAD}"
    # the partner: its own prep + cz line, the tone in LOCK-STEP with the shelf's
    assert len(wins[0]) == 2, f"partner: expected prep + cz windows, got {len(wins[0])}"
    (pps, ppl, ppc, _), (cs0, cl0, cc0, _) = wins[0]
    assert ppl == 2 * xd and abs(ppc - ge_code[0]) < 40, "partner prep not at its OWN f_GE"
    assert cl0 == czd and abs(cc0 - fcz_code) < 40, "partner cz tone not at f_CZ for the CZ length"
    assert cs0 == czs, f"cz tones must fire in lock-step: partner {cs0} vs shelf {czs}"
    # the summed readout opens SEP after the shelf's un-shelving EF X ends (± the boot skew)
    assert len(wins[rd]) == 1, "expected ONE summed readout window"
    gap = wins[rd][0][0] - (e2s + e2l)
    assert abs(gap - SEP) <= 3, f"readout must open SEP after the post EF-X: gap={gap} vs SEP={SEP}"


# ── L2: the CZ amp x freq seed landscape, on the joint state (spec 14 F4) ──

AF_F_GE = {0: 50e6, 1: 150e6}     # planted GE carriers (codes 2048 / 6144)
AF_F_EF = {0: 450e6, 1: 650e6}    # planted EF carriers (codes 18432 / 26624)
AF_F_CZ = (AF_F_GE[0] + 2 * AF_F_GE[1] + AF_F_EF[1]) / 4   # the model's OWN (f11+f02)/4 = 250 MHz
AF_AMP = 0.35                     # the per-line amp planted for a FULL 2*pi round trip (the CZ)
# 32 batches makes the frequency axis exact: a detuning of 128 codes ramps the drive axis by
# 2*pi*128/4096 = pi/16 per batch, i.e. EXACTLY one full turn over the tone.
AF_CZ_BATCHES = 32
AF_DETUNE_CODE = 128


def _amp_freq_cfg(m):
    """The drive-form Config for the seed landscape: `_cz_drive_cfg`'s layout retuned to the MODEL's
    own in-band resonance, with the CZ lines ALIGNED (relative phase 0) so the amp axis alone sets
    the round-trip angle. The readout is never read here (the answer comes off `model_state()`), so
    its drive/window are at the `_ro_cfg` floor — the envelopes are image-load cost, nothing else."""
    code = RO_CODES
    cfg = Config()
    for q in (0, 1):
        cfg[f"qubit/{q}/freq"] = AF_F_GE[q]
        cfg[f"qubit/{q}/x90/amp"] = 0.5
        cfg[f"readout/{q}/freq"] = float(units.demod_code_to_freq(code[q], m.params))
        cfg[f"readout/{q}/amp"] = 0.5
        cfg[f"readout/{q}/dur"] = _s(RO_DRIVE, m)
        cfg[f"readout/{q}/demod/dur"] = _s(RO_WIN, m)
    cfg["reset/relax"] = _s(RELAX_CZ, m)
    cfg["two_qubit/(0, 1)/CZ/freq"] = AF_F_CZ
    cfg["two_qubit/(0, 1)/CZ/pulse"] = [
        {"channel": "Q0", "time": _s(AF_CZ_BATCHES, m), "env": "square",
         "kwargs": {"amp": AF_AMP, "phase": 0.0}},
        {"channel": "Q1", "time": _s(AF_CZ_BATCHES, m), "env": "square",
         "kwargs": {"amp": AF_AMP, "phase": 0.0}},
        {"channel": "Q0", "env": "virtualz", "kwargs": {"phase": 0.0}},
        {"channel": "Q1", "env": "virtualz", "kwargs": {"phase": 0.0}},
    ]
    return cfg


@pytest.mark.cosim
def test_cz_amp_freq_sweep_peaks_at_the_planted_cz(cosim):
    """L2 (spec 14 F4) — the physics the `CZAmpFreqSweep` landscape is MADE of, on the joint state:
    the drive-form CZ tone rotates the {|11>, |02>} pseudo-qubit by an angle linear in the swept
    amplitude, and a detuned carrier starves that rotation by exactly the off-resonant Rabi law.

    Both axes are the class's own — the amp is the HOST knob (`rq.write_slot('amp')` on the cz slot
    of the ONE resident image, no recompile) and the frequency is the ON-CORE `k_cz_pop` FREQ sweep
    word — so three cells of the landscape are walked exactly as the class walks it:

        amp = A*/2, on resonance   theta = pi   ->  P(|02>) = 1   the activation, full transfer
        amp = A*,   on resonance   theta = 2pi  ->  P(|11>) = 1   the round trip that IS the CZ
        amp = A*/2, detuned        the chevron arm: P(|02>) = (W^2/Wg^2)*sin^2(Wg*n/2)

    with W = theta/n the per-batch on-resonance rate, D = 2*pi*dcode/2^12 the per-batch axis ramp and
    Wg = hypot(W, D). A tone of AF_CZ_BATCHES = 32 batches detuned by 128 codes ramps its axis by
    exactly one full turn, putting that cell at 0.026 — a 38x starvation, and the analytic value to
    four decimals (the model steps the ramping axis batch by batch, which is the continuum law's own
    discretization; the Trotter residue at these knobs is 1e-4).

    Two deliberate reductions, both forced by the 20 k budget (02 §1) and both with an owner
    elsewhere:

    - only the CONTROL core is loaded, and the TARGET is planted in |1> (`init = [0, 1]`) instead of
      playing its own prep — a second `k_cz_pop` image costs another ~10 k simulated batches, which
      alone would blow the cap. So the CZ runs on ONE line and `rabi_cz` is planted for |E| = A
      rather than 2A. That the two lines fire in LOCK-STEP at the same carrier with the calibrated
      relative phase is L1, in test_cz_drive_form_two_tone_fire_aligned above; that they combine as
      the COHERENT SUM E = A_c*exp(i*phi_c) + A_t*exp(i*phi_t) is host-pure in
      test_models::test_twoqubit_drive_form_rate_is_the_coherent_two_line_sum; that a GE prep really
      reaches |1> is L2 in test_cal::test_prep_gate_x90_and_x_agree.
    - the metric is the {|11>, |02>} POPULATION, not the conditionality R. R needs four tomography
      reruns per cell (36 for a 3x3 grid, ~90 k batches) and it measures the conditional PHASE, which
      no population can see. The 2D argmax, the `R > 0.5` acceptance and the `CZ/freq` + `CZ/pulse`
      write-back are gated host-pure against this same ramping-axis physics in
      test_twoqubit::test_cz_amp_freq_sweep_seeds_the_argmax."""
    drv, m = cosim
    cfg = _amp_freq_cfg(m)
    czd = _cz_dur_batches(cfg, (0, 1), m)
    a_star = units._amp_code(AF_AMP)
    cz_pulse = _cz_pulse(cfg, (0, 1), m, czd, 0)
    # ONE line fires, so |E| = that line's amp_est: plant the rate that closes 2*pi at A*
    rabi_cz = 2 * math.pi / gate_sigma(m, cz_pulse, AF_F_CZ, a_star)

    # core 0 alone: the |11> prep's own half (the target is planted |1>) then its CZ line, with the
    # amp on the first on-core axis and the carrier on the second — the two knobs the landscape
    # sweeps, here as one-point axes the probe rewrites per cell
    a_ax, f_ax = Axis.amp(AF_AMP, AF_AMP, 1), Axis.freq_codes(
        units._freq_code(AF_F_CZ, m.params), units._freq_code(AF_F_CZ, m.params), 1, m)
    tone = Pulse(cz_pulse.env, cz_pulse.amp, AF_F_CZ, cz_pulse.phase)
    seq = [Gate("x90"), Gate("x90"), Gate(tone, line=0, amp=a_ax, freq=f_ax)]
    progs = probe_progs(cosim, cfg, [0], {0: seq}, {0: (a_ax, f_ax)}, label="ampfreq")
    spec = {"kind": "twoqubit", "control": 0, "target": 1,     # no "coupler" key ⇒ the drive form
            "f_ge": [AF_F_GE[0], AF_F_GE[1]], "f_ef": [AF_F_EF[0], AF_F_EF[1]],
            "rabi_ge": [rabi_for(m, gate_pulse(cfg, 0, m), AF_F_GE[0], math.pi / 2), 0.0],
            "rabi_ef": [0.0, 0.0], "rabi_cz_rad_per_amp": rabi_cz, "zz_rad_per_batch": 0.0,
            "readout_code": [2048, 1024], "readout_amp": [0.0, 16000.0],
            "level_phases": [0.0, math.pi, math.pi / 2],
            "init": [0, 1],                                    # the target is planted |1>
            "collapse": False, "noise_scale": 0.0}
    f_code = units._freq_code(AF_F_CZ, m.params)
    p = Probe(cosim, progs)

    for amp_code, dcode in ((a_star // 2, 0), (a_star, 0), (a_star // 2, AF_DETUNE_CODE)):
        pops = np.asarray(p.state(spec, {0: {"x0": int(amp_code) << 16, "dx0": 0,
                                             "x1": pack16(f_code + dcode), "dx1": 0}})["populations"])
        theta = rabi_cz * gate_sigma(m, cz_pulse, AF_F_CZ, int(amp_code))
        om, delta = theta / czd, 2 * math.pi * dcode / (1 << 12)
        om_g = math.hypot(om, delta)
        want = (om ** 2 / om_g ** 2) * math.sin(om_g * czd / 2) ** 2   # off-resonant Rabi (D=0: sin^2)
        print(f"\n[cz-ampfreq amp={amp_code} d={dcode}] theta={theta:.4f} Delta={delta:.4f}\n"
              f"  P(|11>)={pops[1, 1]:.4f} P(|02>)={pops[0, 2]:.4f} want P(|02>)={want:.4f}")
        assert pops[0, 2] == pytest.approx(want, abs=0.02), \
            f"amp {amp_code} at d={dcode}: the CZ transferred {pops[0, 2]:.4f} of |11>, not {want:.4f}"
        assert pops[1, 1] == pytest.approx(1.0 - want, abs=0.02), \
            f"the {{|11>, |02>}} subspace did not stay closed: {pops[1, 1]:.4f} + {pops[0, 2]:.4f}"
