"""Experiment: can the demod carrier be re-tuned + re-played ON-CORE?

The folklore (`riscq/cal/readout.py`: "the demod carrier can't be re-tuned + re-played on-core") had
no recorded root cause and forced the VNA (Separation) into a host loop. This runs the deciding
experiment: one program that `set_freq(demod, code)` + re-plays per point on a fixed grid (each retune
scheduled `period` ≫ LEAD ahead of its play, so the phasor-regen lead is covered — map.py LEAD note),
against a fixed ADC tone. The on-core sweep must resolve the matched code the same as the host-loop VNA
(a peak at 4×the DAC code, per test_readout); then the demod retunes on-core and the VNA can batch in
iqsum mode.

Verdict: PASSES — the retune+replay works on-core with adequate lead (no folklore limitation), so the
VNA batches on-core. The old claim was insufficient-lead folklore, not a HW limit."""

import math

import numpy as np
import pytest

from riscq import run as rq
from riscq.lang import Array, ParamTable, compile_kernel, kernel
from riscq.map import LEAD, READOUT_LEAD, pack16
from riscq.pulses import Pulse, envelopes, units

pytestmark = pytest.mark.cosim

F = 1024          # a DAC freq code; its physical tone matches demod code 4F (test_readout golden)
RO_DUR = 40
RO_AMP = 20000.0


def _demod():
    return ParamTable(2, 0.0, {"sq": Pulse(envelopes.square(RO_DUR), amp=1.0)})


@kernel
def k_vna_sweep(demod: ParamTable, iq: Array, base: int, step: int, npts: int, period: int):
    """On-core demod-frequency sweep: retune (`code += step`) + re-play per point on a fixed grid; each
    retune is scheduled a full `period` ahead of its play, covering the phasor-regen lead."""
    init_pulse_params(demod.pulses)  # noqa: F821
    t = now() + period  # noqa: F821
    code = base
    j = 0
    for i in range(npts):
        set_freq(demod, code)  # noqa: F821  retune the demod carrier
        play(demod, demod["sq"], t)  # noqa: F821  re-play at the new code
        wait_until(t + READOUT_LEAD)  # noqa: F821
        read_res()  # noqa: F821
        iq[j] = read_real()  # noqa: F821
        iq[j + 1] = read_imag()  # noqa: F821
        j = j + 2
        code = code + step
        t = t + period


@kernel
def k_vna_one(demod: ParamTable, out: Array, code: int):   # `code` stays a RUNTIME param — see below
    """One host-loop VNA point (the pre-batch per-point shape): a single |0> read at demod `code`."""
    init_pulse_params(demod.pulses)  # noqa: F821
    set_freq(demod, code)  # noqa: F821
    t = now() + LEAD  # noqa: F821
    play(demod, demod["sq"], t)  # noqa: F821
    wait_until(t + READOUT_LEAD)  # noqa: F821
    read_res()  # noqa: F821
    out[0] = read_real()  # noqa: F821
    out[1] = read_imag()  # noqa: F821


@pytest.fixture(autouse=True)
def _zero_after(cosim):
    yield
    cosim[0].sim.set_model({"kind": "zero"})


def _mags(iq):
    iq = np.asarray(iq, dtype=np.int64)
    return np.array([math.hypot(int(iq[2 * k]), int(iq[2 * k + 1])) for k in range(len(iq) // 2)])


@pytest.mark.batch_cap(33_000)
def test_demod_retunes_on_core(cosim):
    """FLOOR: ~30 k = TWO program images (~6.6 k each) + the 7-point on-core sweep + 7 host-loop
    reruns (~2 k each). The claim is a point-for-point comparison of two routes over the same 7
    demod codes, so neither route's image nor its 7 points can go: dropping the host loop deletes
    the baseline the on-core sweep is being compared against. The per-point image reload is already
    gone (`code` is a runtime param, `setup` + `rerun` per point)."""
    drv, m = cosim
    npts = 7
    codes = [(i + 1) * F for i in range(npts)]         # F,2F,…,7F — matched 4F at index 3
    drv.sim.set_model({"kind": "tone", "adc": m.adc_of(0),
                       "freq_hz": units.code_to_freq(F, m.params), "amp": RO_AMP})

    # on-core: ONE program retunes + re-plays every code
    prog = compile_kernel(k_vna_sweep, m, tables=dict(demod=_demod()),
                          iq=Array(2 * npts), base=pack16(F), step=pack16(F),   # on-core seated pair
                          npts=npts, period=400)
    on = _mags(rq.run(drv, m, {0: prog}, timeout=8_000_000)[0]["iq"])

    # host loop: one run per code (the current Separation path), same tone. `code` is left unbound
    # so it is a runtime param: the baseline is ONE loaded image + a `rerun` per point rather than
    # a fresh `run` (image reload included) per point — same one-readout-per-run shape.
    one = compile_kernel(k_vna_one, m, tables=dict(demod=_demod()), out=Array(2))
    rq.setup(drv, m, {0: one})
    host = np.array([_mags(rq.rerun(drv, m, {0: one}, params={0: {"code": pack16(c)}},
                                    timeout=2_000_000)[0]["out"])[0] for c in codes])

    print(f"\n[vna-retune] codes/F={[c // F for c in codes]}\n"
          f"  on-core |z|={[int(x) for x in on]}\n  host    |z|={[int(x) for x in host]}")
    assert int(np.argmax(host)) == 3, "host-loop VNA peak not at 4F (baseline broken)"
    assert int(np.argmax(on)) == 3, f"on-core VNA peak not at 4F: {[int(x) for x in on]}"
    assert on[3] > 4 * min(on[0], on[-1]), "on-core VNA has no selectivity vs the detuned codes"
