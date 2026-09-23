"""Co-sim gates of universal-control/01 P3 on the sim-mm build (core 0: gate / f0g1 / flux / ro /
demod): two pulse channels of ONE core fire at one absolute slot with bit-exact windows, and a
flat-top played as three contiguous slots is bit-exact against the golden of one long pulse (the
carrier stays phase-continuous across the slot seams)."""

import numpy as np
import pytest

from riscq import run as rq
from riscq.build import Program, compile_c
from riscq.map import pack16
from riscq.pulses import Pulse, envelopes, flat_top, golden, units
from riscq.pulses.pack import pack_env

pytestmark = pytest.mark.cosim

# Two channels of core 0 (gate and f0g1) play slot 0 at the same absolute time; then the f0g1
# channel plays a flat-top as up / body×2 / down (four fires: within the depth-4 param queues).
SRC = """
#include "riscq.h"
volatile int32_t RQ_PARAM freq_code = 0;
volatile int32_t RQ_PARAM lead = RQ_LEAD;
volatile int32_t RQ_PARAM ft_gap = 0;    /* batches from the pair's start to the flat-top's start */
volatile struct rq_slot RQ_PARAM pair[1] = {{0, 0, 0, 0}};                        /* gate: slot 0 */
volatile struct rq_slot RQ_PARAM f0[4] = {{0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 0, 0}};
                                         /* f0g1: slot 0 = the pair, 1 = up, 2 = body, 3 = down */
volatile uint32_t t_pair = 0;
volatile uint32_t t_ft = 0;
int main(void) {
    init_pulse_params(RQ_CH_GATE, pair, 1);
    init_pulse_params(RQ_CH_F0G1, f0, 4);
    set_freq(RQ_CH_GATE, freq_code);
    set_freq(RQ_CH_F0G1, freq_code);
    uint32_t t = now() + (uint32_t)lead;
    t_pair = t;
    play(RQ_CH_GATE, 0, t);
    play(RQ_CH_F0G1, 0, t);                       /* the same absolute slot on a second channel */
    uint32_t t2 = t + (uint32_t)ft_gap;
    t_ft = t2;
    play(RQ_CH_F0G1, 1, t2);                      /* up   */
    fire(RQ_CH_F0G1, 2);                          /* body, contiguous through startTime auto-advance */
    fire(RQ_CH_F0G1, 2);                          /* body */
    fire(RQ_CH_F0G1, 3);                          /* down */
    uint32_t total = ((uint32_t)f0[1].dur >> 16) + 2 * ((uint32_t)f0[2].dur >> 16) + ((uint32_t)f0[3].dur >> 16);
    wait_until(t2 + total + 8);
    return 0;
}
"""


def _fill(drv, m, prog, name, slots):
    """Fill a host-written `struct rq_slot NAME[]` by address, every field seated (spec 12)."""
    base = prog.var_addr(name)
    for i, (phase, amp, env_line, dur) in enumerate(slots):
        for off, val in ((0, phase), (4, amp), (8, env_line), (12, dur)):
            drv.write32(m.to_host_addr(0, base + 16 * i + off), pack16(val))


def test_two_channels_one_slot_and_flat_top_seams(cosim_mm):
    drv, m = cosim_mm
    gate, f0g1 = m.channel_named("gate", 0), m.channel_named("f0g1", 0)
    freq_hz = 20e6
    fcode = units._freq_code(freq_hz, m.params)
    spl = gate.samples_per_line

    # the pair: one 32-batch gaussian on both channels at envelope line 0
    pair = Pulse(envelopes.gaussian(32 * spl, 2.0), 0.6)
    pair_lines = pack_env(pair.env, spl)
    # the flat-top on f0g1: 16-batch ramps and a 600-batch flat, which the 1024-line RAM splits
    # into 2 x 300-batch body pieces — so the play crosses three slot seams
    ft = flat_top(f0g1, ramp_batches=16, flat_batches=600, amp=0.4, freq_hz=freq_hz)
    assert ft.reps == 2
    up_lines, body_lines, down_lines = (pack_env(p.env, spl) for p in (ft.up, ft.body, ft.down))
    line_up = len(pair_lines)
    line_body = line_up + len(up_lines)
    line_down = line_body + len(body_lines)
    for ch, lines, l0 in ((gate, pair_lines, 0), (f0g1, pair_lines, 0), (f0g1, up_lines, line_up),
                          (f0g1, body_lines, line_body), (f0g1, down_lines, line_down)):
        rq.write_envelope(drv, m, 0, ch.index, l0, lines)

    prog = Program.from_image(compile_c(SRC, m, core=0))
    rq.reset(drv, m, on=True)
    rq.load_program(drv, m, 0, prog.image)
    rq.park_core(drv, m, 1)
    rq.check_magic(drv, m, 0, prog)
    ft_gap = 64
    rq.write_params(drv, m, 0, prog, {"freq_code": pack16(fcode), "lead": m.LEAD, "ft_gap": ft_gap})
    pair_slot = (pair.phase_code(), pair.amp_code(), 0, 32)
    _fill(drv, m, prog, "pair", [pair_slot])
    _fill(drv, m, prog, "f0", [pair_slot] + [
        (p.phase_code(), p.amp_code(), l0, p.dur_batches(m, f0g1.index))
        for p, l0 in ((ft.up, line_up), (ft.body, line_body), (ft.down, line_down))])
    n_capture = 1800
    h_gate = drv.sim.dac_capture_arm(gate.dac, n_capture)
    h_f0g1 = drv.sim.dac_capture_arm(f0g1.dac, n_capture)
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0], timeout=4_000_000)
    t_pair = rq.read_var(drv, m, 0, prog, "t_pair") & 0xFFFFFFFF
    t_ft = rq.read_var(drv, m, 0, prog, "t_ft") & 0xFFFFFFFF
    rq.reset(drv, m, on=True)
    t0g, cap_gate = drv.sim.dac_capture_get(h_gate)
    t0f, cap_f0g1 = drv.sim.dac_capture_get(h_f0g1)

    def window(cap, t0, t, n):
        i = t - t0
        assert 0 <= i and i + n <= cap.shape[0], f"window [{t}, {t + n}) outside capture [{t0}, {t0 + cap.shape[0]})"
        return cap[i:i + n]

    # (1) the pair: both channels' windows equal the golden at the SAME absolute slot, bit-exact
    exp_pair = golden.pulse_window(pair_lines, pair.amp_code(), fcode, pair.phase_code(), t_pair, 32)
    for name, cap, t0 in (("gate", cap_gate, t0g), ("f0g1", cap_f0g1, t0f)):
        got = window(cap, t0, t_pair, 32)
        assert np.array_equal(got, exp_pair), f"{name}: pair window differs from the golden"

    # (2) the flat-top: three slots fired back to back equal ONE long pulse's golden, bit-exact —
    # the carrier is phase-continuous across the up|body, body|body and body|down seams
    long_lines = pack_env(ft.envelope(), spl)
    n_ft = ft.dur_batches(f0g1)
    exp_ft = golden.pulse_window(long_lines, ft.up.amp_code(), fcode, ft.up.phase_code(), t_ft, n_ft)
    got_ft = window(cap_f0g1, t0f, t_ft, n_ft)
    for s in (16, 16 + 300, 16 + 600):
        assert np.array_equal(got_ft[s - 2:s + 2], exp_ft[s - 2:s + 2]), f"seam at batch {s} is not continuous"
    assert np.array_equal(got_ft, exp_ft), "flat-top differs from the single-long-pulse golden"
    # and the DAC is silent between the pair and the flat-top on f0g1 (nothing leaked)
    assert not window(cap_f0g1, t0f, t_pair + 32, ft_gap - 32).any()
