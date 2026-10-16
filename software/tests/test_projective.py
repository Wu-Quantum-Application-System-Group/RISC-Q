"""Acceptance: the projective TwoLevelModel (collapse=True) drives the REAL
readout datapath — readout drive → demod carrier → carrier-triggered decoder → res/real/imag — to a
DEFINITE state per shot. An X90 prepares a bz = 0 superposition every shot, so the shots split into
two antipodal IQ clusters and the hardware `res` bit is the sign of the integrated real.

**L1 smoke**. The 150-shot binomial half of
this test is gone: `p̂ ≈ 1/2 within the binomial CI` is a STATISTICS claim, and statistics belong at
L0 where the model can be driven a million times for free —
`test_models.py::test_twolevel_projective_binomial_statistics` already owns it at n = 6000 (this
file could only afford 150), and its premise — that the collapse leaves a pure pole which the next
X90 maps back to the equator, so every shot re-prepares p = 1/2 with no reset — is
`test_models.py::test_twolevel_projective_reprepares_from_either_pole`. What is genuinely
additional here, and lives nowhere else, is the DATAPATH: that a collapsed shot arrives at the
decoder as a full-scale definite-state phasor rather than the ⟨σz⟩ ≈ 0 smear a soft model would
emit, that the shots really are bimodal through the real integrator, and that `res` is the sign of
the integrated real. Twelve shots pin all three.

The demod-phase calibration is gone with them. It cost two full `rq.run`s of a probe kernel (~17 k
simulated batches, more than the measurement) and bought only the |0>→+real orientation; every
claim below is stated relative to the shots themselves, so the arbitrary pipeline phase drops out
and the test is strictly more robust. The orientation claim — that a CALIBRATED demod phase lands
|0> on +real and |1> on −real — is owned by `test_batch.py::test_applied_demod_phase`, which
applies that phase through the real `write_slot` path.

The soft model (default) is exercised unchanged by the whole existing suite (test_readout/test_cal).
"""

import math

import numpy as np
import pytest

from riscq import run as rq
from riscq.cal.base import SEP, gate_sigma
from riscq.lang import Array, ParamTable, compile_kernel, kernel
from riscq.map import READOUT_LEAD, pack16
from riscq.pulses import Pulse, envelopes, units

pytestmark = pytest.mark.cosim

F_GE = 50e6
RO_CODE = 2048
RO_DUR = 40
GATE_ENV = envelopes.square(16)   # 4-batch square X90
PERIOD = 256                      # ≥ LEAD + SEP + gate, and ≫ READOUT_LEAD: the shot's whole grid slot
NSHOTS = 12


def _tables(m):
    ro_freq = units.demod_code_to_freq(RO_CODE, m.params)   # ~physical readout freq (model ignores ch1 freq)
    gate = ParamTable(0, F_GE, {"x90": Pulse(GATE_ENV, freq_hz=F_GE, amp=0.5)})
    ro = ParamTable(1, ro_freq, {"meas": Pulse(envelopes.square(RO_DUR + 16), freq_hz=ro_freq, amp=0.5)})
    demod = ParamTable(2, 0.0, {"sq": Pulse(envelopes.square(RO_DUR), amp=1.0)})
    return gate, ro, demod


@kernel
def k_shots(gate: ParamTable, ro: ParamTable, demod: ParamTable, res_out: Array, iq: Array,
            code: int, period: int, nshots: int):
    """N shots of [idle → X90 prep → readout drive + demod → read]. The readout drive (ch1) is the
    projective model's window trigger; firing the demod carrier IS the readout. No reset is needed
    between shots: the collapse leaves a PURE pole and the X90 maps either pole to the equator."""
    init_pulse_params(demod.pulses)  # noqa: F821
    set_freq(demod, code)  # noqa: F821
    init_pulse_params(ro.pulses)  # noqa: F821
    set_freq(ro, ro.freq)  # noqa: F821
    init_pulse_params(gate.pulses)  # noqa: F821
    set_freq(gate, gate.freq)  # noqa: F821
    t_ro = now() + period                          # first grid slot
    k = 0
    for s in range(nshots):
        tpi = t_ro - SEP - gate["x90"].dur  # noqa: F821
        play(gate, gate["x90"], tpi)  # noqa: F821  X90 → bz=0 superposition
        play(ro, ro["meas"], t_ro)  # noqa: F821     readout drive (projective window trigger; hardware)
        play(demod, demod["sq"], t_ro)  # noqa: F821  demod carrier IS the readout
        wait_until(t_ro + READOUT_LEAD)  # noqa: F821
        res_out[s] = read_res()  # noqa: F821
        iq[k] = read_real()  # noqa: F821
        iq[k + 1] = read_imag()  # noqa: F821
        k = k + 2
        t_ro = t_ro + period


@pytest.fixture(autouse=True)
def _zero_after(cosim):
    yield
    cosim[0].sim.set_model({"kind": "zero"})


def test_projective_raw_path_is_bimodal(cosim):
    drv, m = cosim
    gate, ro, demod = _tables(m)

    # calibrate the model so the X90 rotates by exactly π/2 (→ bz = 0, an even coin every shot)
    x90 = gate.pulses["x90"]
    rabi = (math.pi / 2) / gate_sigma(m, x90, F_GE, x90.amp_code())
    # t1 ≫ the grid period so the equator survives the idle before the readout (a short t1 would
    # relax bz → +1 and bias the coin toward 0). `readout_phase` is left at 0: the demod pipeline
    # sits at an arbitrary absolute angle and every claim below is stated relative to the shots.
    drv.sim.set_model(dict(kind="twolevel", core=0, collapse=True, rabi_rad_per_amp=rabi,
                           readout_code=RO_CODE, readout_amp=20000.0, readout_phase=0.0, f_ge=F_GE,
                           t1=4000, t2=6000, noise_scale=300.0, noise_seed=2))
    prog = compile_kernel(k_shots, m, tables=dict(gate=gate, ro=ro, demod=demod),
                          res_out=Array(NSHOTS), iq=Array(2 * NSHOTS),
                          code=pack16(RO_CODE), period=PERIOD, nshots=NSHOTS)
    out = rq.run(drv, m, {0: prog}, timeout=4 * NSHOTS * PERIOD + 20_000_000)[0]

    res = out["res_out"].astype(int)
    iq = out["iq"].astype(np.int64).reshape(NSHOTS, 2)
    z = iq[:, 0] + 1j * iq[:, 1]

    # 1. a DEFINITE state every shot: |z| is full-scale and the SAME on every shot (collapse ⇒ ±amp,
    #    not the soft model's continuous ⟨σz⟩·amp — a smeared read would spread |z| over [0, max]).
    mag = np.abs(z)
    print(f"\n[projective] res={res.tolist()}\n  |z|={np.round(mag / 1e6, 3).tolist()}e6 "
          f"spread={(mag.max() - mag.min()) / mag.mean():.3%}")
    assert mag.min() > 1_000_000, "projective |z| too small — not collapsing to a definite state"
    assert (mag.max() - mag.min()) < 0.05 * mag.mean(), \
        f"|z| varies {(mag.max() - mag.min()) / mag.mean():.1%} across shots — not a definite state"

    # 2. the hardware discriminator IS sign(integrated real) — the contract counts mode is built on
    assert np.array_equal(res, (z.real < 0).astype(int)), \
        f"res disagrees with sign(real): res={res.tolist()} real={np.sign(z.real).tolist()}"

    # 3. BIMODAL and antipodal, stated without a calibrated demod phase: split the shots by which
    #    side of shot 0 they land on (a phase-free grouping — the pipeline angle cancels in
    #    z·conj(z[0])), and the two group means must be the SAME phasor negated, because |0> and |1>
    #    emit the same tone π out of phase.
    same = (z * z[0].conjugate()).real > 0
    assert same.any() and (~same).any(), f"only one outcome was sampled: res={res.tolist()}"
    a, b = z[same].mean(), z[~same].mean()
    print(f"  clusters: {a / 1e6:.3f}e6 and {b / 1e6:.3f}e6  (n={same.sum()}/{(~same).sum()})  "
          f"|a+b|/|a|={abs(a + b) / abs(a):.3f}")
    assert abs(a + b) < 0.05 * abs(a), \
        f"the two clusters are not antipodal: {a:.3e} and {b:.3e}"
    assert res[same][0] != res[~same][0] and len(set(res[same])) == 1 == len(set(res[~same])), \
        "res does not label the two clusters — the discriminator does not separate them"
