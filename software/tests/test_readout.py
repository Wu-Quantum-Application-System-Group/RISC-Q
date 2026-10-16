"""M3 acceptance: readout + feedback on the verilator co-sim.

- Readout integrator golden: a batch-time-locked ADC tone at a known physical frequency (ToneModel)
  is demod'd; the integral peaks when the LO is MATCHED and collapses when detuned (VNA selectivity,
  like PulseTableSocSim), its magnitude matches the analytic value, and the matched code is measured
  to be 4x the DAC's freq code for the same physical tone — which PINS the demod freq<->code factor
  (the ADC is 4 samples/batch vs the DAC's 16). The factor is units.demod_freq_to_code. (A batch-
  time-locked tone is used, not LoopbackModel: re-decimating the DAC every sim cycle jitters the
  tone frequency at co-sim sampling granularity; LoopbackModel is unit-tested in test_models.)
- res-sign convention: documented + asserted (|0> -> real>0 -> res=0, |1> -> real<0 -> res=1),
  established by measurement, not assumed.
- Active reset: a feedback kernel `while read_res()==EXCITED: play(x180)` resets a TwoLevelModel
  qubit prepared in |1> in bounded iterations.
- Demod multi-launch: the demod carrier is re-played (set_start + fire) once per readout within ONE
  program; every shot's integrated |z| stays full and consistent (the decoder window IS each re-played
  carrier's own valid window — carrier-triggered, no arm).

Every model is selected at runtime over Pyro5 (drv.sim.set_model) because the cosim fixture is
session-scoped — one sim process for the whole run.
"""

import math

import numpy as np
import pytest

from riscq import run as rq
from riscq.lang import Array, ParamTable, compile_kernel, kernel
from riscq.map import LEAD, READOUT_LEAD, pack16
from riscq.pulses import Pulse, envelopes, units

pytestmark = pytest.mark.cosim

# One readout tone shared by the res-sign + feedback tests: code 2048 (good selectivity), a 40-batch
# window, amplitude 20000 (< full scale). The ADC->integrator pipeline adds a fixed phase; it is
# removed by a one-shot phase calibration (demod_cal), the miniature of M4's ReadoutCalibration.
RO_CODE = 2048
RO_DUR = 40
RO_AMP = 20000.0

F = 1024        # a DAC freq code; its physical tone matches demod code 4F (measured in the golden)
EXCITED = 1     # |1> -> res=1 under the calibrated demod phase (asserted in test_res_sign_convention)


# ── demod carrier: a plain channel-2 drive pulse (square envelope) fed to the readout decoder ──
# It is programmed / played with the SAME generic ops as any drive (init_pulse_params / set_freq /
# play). Its carrier frequency is set separately via set_freq with an ADC-rate demod_freq_to_code
# code, so the table's own carrier freq is unused (0). The demod carrier can be RE-PLAYED once per shot;
# its valid window (n batches) IS the readout window (carrier-triggered decoder, no arm) — see
# test_demod_replay_within_program. `demod_table(n)` makes an n-batch square carrier (n <= env_depth).
def demod_table(n: int) -> ParamTable:
    return ParamTable(2, 0.0, {"sq": Pulse(envelopes.square(n), amp=1.0)})


# ── kernels ──

@kernel
def k_read_once(demod: ParamTable, out: Array, code: int):
    """Play the demod carrier, arm one readout window, block for the result, record res/real/imag."""
    init_pulse_params(demod.pulses)  # noqa: F821  (program the square demod pulse)
    set_freq(demod, code)  # noqa: F821  (demod carrier freq — ADC-rate code, set once)
    t = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t)  # noqa: F821  (play the demod carrier — its window IS the readout)
    wait_until(t + READOUT_LEAD)  # noqa: F821  (past the window's opening ⇒ read_res returns THIS shot)
    out[0] = read_res()  # noqa: F821  (HALTS until the integral settles)
    out[1] = read_real()  # noqa: F821
    out[2] = read_imag()  # noqa: F821


@kernel
def k_feedback(gate: ParamTable, demod: ParamTable, out: Array, code: int, gap: int,
               excited: int, maxit: int):
    """Active reset: while the readout still reads EXCITED, play an x180 and re-measure. Returns the
    number of correction pulses in out[0]. The demod carrier is RE-PLAYED (a short carrier matched to
    the window) before each readout — one launch per shot, so the demod is exercised as a normal
    multi-launch channel (not a single long play-once carrier)."""
    # set up + play the demod and arm the FIRST readout before the gate setup, so the first read has
    # the same instruction timing (hence demod phase) as the k_read_once demod_cal was measured with.
    init_pulse_params(demod.pulses)  # noqa: F821
    set_freq(demod, code)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t)  # noqa: F821  (demod carrier for the first readout — firing it IS the readout)
    init_pulse_params(gate.pulses)  # noqa: F821  (gate setup — does not disturb the scheduled readout)
    set_freq(gate, gate.freq)  # noqa: F821
    wait_until(t + READOUT_LEAD)  # noqa: F821  (past the first window's opening, before read_res)
    count = 0
    while read_res() == excited:  # noqa: F821  (HALTS each iteration until the window settles)
        count += 1
        if count > maxit:
            break
        tp = now() + LEAD  # noqa: F821
        play(gate, gate["x180"], tp)  # noqa: F821
        wait_until(tp + gate["x180"].dur + gap)  # noqa: F821
        tr = now() + LEAD  # noqa: F821
        play(demod, demod["sq"], tr)  # noqa: F821  (RE-PLAY the demod carrier for this shot)
        wait_until(tr + READOUT_LEAD)  # noqa: F821  (past this window's opening, before the next read_res)
    out[0] = count


# ── helpers ──

def _read_once(cosim, spec, code, dur):
    """Set `spec` as the ADC model, run k_read_once, return (res, real, imag) as signed ints."""
    drv, m = cosim
    drv.sim.set_model(spec)
    prog = compile_kernel(k_read_once, m, tables=dict(demod=demod_table(dur)),
                          out=Array(3), code=pack16(code))
    out = rq.run(drv, m, {0: prog}, timeout=1_000_000)[0]["out"]
    return int(out[0]), int(out[1]), int(out[2])


def _mags(cosim, spec, codes, dur):
    """The integrated |z| of one readout per demod `code`, from a SINGLE loaded image.

    `code` is left unbound at compile time, so it is a runtime param and the host-orchestrated
    sweep is one `setup` plus one `rerun` per point instead of a full `run` — which reloads the
    image — per point: ~8.4k simulated batches a point down to ~2k. Each point is still
    its own fixed-frequency readout with the demod carrier set up and played once, which is what
    the sweep is asserting."""
    drv, m = cosim
    drv.sim.set_model(spec)
    prog = compile_kernel(k_read_once, m, tables=dict(demod=demod_table(dur)), out=Array(3))
    rq.setup(drv, m, {0: prog})
    out = [rq.rerun(drv, m, {0: prog}, params={0: {"code": pack16(code)}},
                    timeout=1_000_000)[0]["out"] for code in codes]
    return np.array([math.hypot(int(o[1]), int(o[2])) for o in out])


@pytest.fixture(scope="module")
def demod_cal(cosim):
    """The demod-LO phase (radians) that lands a |0> readout tone on +real — measured once (a
    ReadoutCalibration in miniature). Two reads probe the fixed ADC->integrator pipeline phase and
    its slope, so the cancellation is correct regardless of the demod's sign conventions."""
    base = dict(kind="twolevel", rabi_rad_per_amp=0.0, readout_code=RO_CODE,
                readout_amp=RO_AMP, init_excited=False)
    _, r0, i0 = _read_once(cosim, {**base, "readout_phase": 0.0}, RO_CODE, RO_DUR)
    probe = 0.4
    _, r1, i1 = _read_once(cosim, {**base, "readout_phase": probe}, RO_CODE, RO_DUR)
    a0, a1 = math.atan2(i0, r0), math.atan2(i1, r1)
    s = 1.0 if math.remainder(a1 - a0, 2 * math.pi) >= 0 else -1.0
    return -s * a0


@pytest.fixture(autouse=True)
def _zero_model_after(cosim):
    """Leave the session-scoped sim with the default ZeroModel after each readout test."""
    yield
    cosim[0].sim.set_model({"kind": "zero"})


# ── 1. readout integrator golden + demod freq<->code pin ──

def test_readout_integrator_golden(cosim):
    drv, m = cosim
    f_hz = units.code_to_freq(F, m.params)                       # 25 MHz (DAC code F)
    amp = RO_AMP
    dur = 60
    # 4F is the matched code; the sweep spans its neighbors (2F,6F,8F) to prove it is the peak, plus
    # a far-detuned reference (16F) for the VNA >> check. Detuned magnitudes near the peak are
    # rectangular-window sidelobes; selectivity is measured against the far code (like PulseTableSocSim).
    # A VNA point changes the demod carrier frequency, so each is a separate fixed-freq readout (the
    # demod carrier is set up + played once per shot) — the host orchestrates the sweep.
    codes = [F, 2 * F, 4 * F, 6 * F, 8 * F, 16 * F]
    mags = _mags(cosim, {"kind": "tone", "adc": m.adc_of(0), "freq_hz": f_hz, "amp": amp}, codes, dur)
    peak = int(np.argmax(mags))
    print(f"\n[golden] codes={codes} |z|={mags.astype(int).tolist()} peak@{codes[peak]}")

    # PIN: the matched demod code is 4F = 4x the DAC freq_to_code for the same physical tone.
    assert peak == 2, f"matched demod code index {peak} != 2 (4F); |z|={mags}"
    assert codes[peak] == units._demod_code(f_hz, m.params) == 4 * units._freq_code(f_hz, m.params)
    assert mags[peak] > 4 * mags[-1], f"no selectivity vs far-detuned 16F: {mags}"   # VNA >>

    # magnitude matches the analytic value |z| ~ A*B*Nsamp/2^16 (A = tone amp, B ~ full-scale demod
    # LO ~ 32760, Nsamp = (dur+1)*4 samples integrated).
    z_pred = amp * 32760.0 * ((dur + 1) * 4) / (1 << 16)
    print(f"[golden] |z|_matched={mags[peak]:.0f} analytic~{z_pred:.0f}")
    assert 0.5 * z_pred < mags[peak] < 1.5 * z_pred, \
        f"matched |z| {mags[peak]:.0f} far from analytic {z_pred:.0f}"


# ── 2. res-sign convention ──

def test_res_sign_convention(cosim, demod_cal):
    """res = sign bit of the integrated real part (real<0 => res=1), matching hardware CTRL_RES.
    The TwoLevelModel's |1> readout tone is pi out of phase from |0> (its complex amplitude tracks
    <sigma_z>), so the integrated real (and imag) NEGATE between the states and res flips. With the
    demod phase calibrated so |0> lands on +real, the pinned convention is:
        |0> -> real>0 -> res=0 ;  |1> -> real<0 -> res=1 (= EXCITED)."""
    base = dict(kind="twolevel", rabi_rad_per_amp=0.0, readout_code=RO_CODE,
                readout_amp=RO_AMP, readout_phase=demod_cal)
    resg, rg, ig = _read_once(cosim, {**base, "init_excited": False}, RO_CODE, RO_DUR)
    rese, re, ie = _read_once(cosim, {**base, "init_excited": True}, RO_CODE, RO_DUR)
    print(f"\n[res-sign] |0>: res={resg} real={rg} imag={ig}   |1>: res={rese} real={re} imag={ie}")

    assert rg > 0 and resg == 0, "|0> must give integrated-real>0 -> res=0 after phase calibration"
    assert re < 0 and rese == EXCITED, "|1> must give integrated-real<0 -> res=1"
    assert rese != resg
    # the pi flip: excited negates the integral (same magnitude, opposite sign)
    assert abs(abs(re) - abs(rg)) < 0.3 * abs(rg), \
        f"excited integral {re} is not ~ -ground {rg} (pi-flip broken)"


# ── 4. active reset (feedback) ──

def test_feedback_active_reset(cosim, demod_cal):
    drv, m = cosim
    f_ge = units.code_to_freq(RO_CODE, m.params)              # qubit drive carrier (arbitrary)
    x180 = Pulse(envelopes.square(64), freq_hz=f_ge, amp=0.9)  # 16-batch pi pulse
    gate = ParamTable(0, f_ge, {"x180": x180})

    # calibrate the model's Rabi rate so THIS x180 rotates by pi: sum the per-batch drive-amplitude
    # estimate over the bit-exact DAC golden (the model sees the same samples) and set rate = pi/sum.
    from riscq.pulses import golden
    lines = x180.packed_lines(m, 0)
    w = golden.pulse_window(lines, x180.amp_code(), gate.freq_code(m), 0, 1500, len(lines))
    sigma = sum(math.sqrt(2 * np.mean(row.astype(float) ** 2)) for row in w)
    rabi = math.pi / sigma

    # f_ge = the x180 carrier => the drive is resonant, so its rotation axis is fixed and the
    # calibrated π flips |1>→|0> exactly (the extended model takes the axis from the DAC demodulated
    # against f_ge; leaving f_ge=0 here would make this drive look hugely detuned and smear the flip).
    drv.sim.set_model(dict(kind="twolevel", core=0, rabi_rad_per_amp=rabi, readout_code=RO_CODE,
                           readout_amp=RO_AMP, readout_phase=demod_cal, f_ge=f_ge, init_excited=True))
    # the demod carrier is re-played per shot: a short square pulse matched to the window (dur+8).
    # maxit=8, but the reset converges in 1-2, well within this.
    prog = compile_kernel(k_feedback, m, tables=dict(gate=gate, demod=demod_table(RO_DUR)),
                          out=Array(1), code=pack16(RO_CODE), gap=48, excited=EXCITED, maxit=8)
    count = int(rq.run(drv, m, {0: prog}, timeout=2_000_000)[0]["out"][0])
    print(f"\n[feedback] active reset completed in {count} iteration(s)")
    assert 1 <= count <= 4, f"active reset took {count} iterations (not bounded/small)"


# ── 5. demod multi-launch: re-play the demod carrier once per shot within one program ──

@kernel
def k_read_five(demod: ParamTable, out: Array, code: int):
    """5 readouts in one program, the demod carrier re-played (set_start+fire) before each."""
    init_pulse_params(demod.pulses)  # noqa: F821
    set_freq(demod, code)  # noqa: F821
    # 5 measured shots, demod re-played before each
    t0 = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t0); wait_until(t0 + READOUT_LEAD); read_res()  # noqa: F821
    out[0] = read_real(); out[1] = read_imag()  # noqa: F821
    t1 = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t1); wait_until(t1 + READOUT_LEAD); read_res()  # noqa: F821
    out[2] = read_real(); out[3] = read_imag()  # noqa: F821
    t2 = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t2); wait_until(t2 + READOUT_LEAD); read_res()  # noqa: F821
    out[4] = read_real(); out[5] = read_imag()  # noqa: F821
    t3 = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t3); wait_until(t3 + READOUT_LEAD); read_res()  # noqa: F821
    out[6] = read_real(); out[7] = read_imag()  # noqa: F821
    t4 = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t4); wait_until(t4 + READOUT_LEAD); read_res()  # noqa: F821
    out[8] = read_real(); out[9] = read_imag()  # noqa: F821


def test_demod_replay_within_program(cosim):
    """Re-launching the demod carrier per shot within ONE program yields a full, consistent readout
    every shot. With the carrier-triggered decoder each re-played carrier's own valid window IS the
    integration window, so re-launching cannot desync the window from the carrier (the arm-ordering
    bug class this used to guard is gone — there is no arm)."""
    drv, m = cosim
    dur = 40
    code = 4 * F  # matched demod code (4x the DAC code, per the golden)
    drv.sim.set_model({"kind": "tone", "adc": m.adc_of(0),
                       "freq_hz": units.code_to_freq(F, m.params), "amp": RO_AMP})
    prog = compile_kernel(k_read_five, m, tables=dict(demod=demod_table(dur)),
                          out=Array(10), code=pack16(code))
    out = rq.run(drv, m, {0: prog}, timeout=4_000_000)[0]["out"]

    def s32(x):
        return x - (1 << 32) if x >= (1 << 31) else x
    mags = [math.hypot(s32(int(out[2 * k])), s32(int(out[2 * k + 1]))) for k in range(5)]
    print(f"\n[replay] |z| per shot = {[int(x) for x in mags]}")
    drv.sim.set_model({"kind": "zero"})
    assert all(mg > 1_000_000 for mg in mags), f"a re-played demod shot collapsed: {[int(x) for x in mags]}"
    # every shot within 15% of the first — no per-shot degradation.
    assert max(mags) < 1.15 * min(mags), f"re-played readouts not consistent: {[int(x) for x in mags]}"
