"""M1 acceptance: the pulse path on the verilator co-sim — bit-exact DAC windows vs the numpy
golden, the VNA frequency pin, LEAD validation, allocator round-trip, and the amp-scale pin."""

import numpy as np
import pytest

from riscq import run as rq
from riscq.build import Program, compile_c
from riscq.map import pack16
from riscq.pulses import EnvelopeAllocator, Pulse
from riscq.pulses import envelopes, golden, units
from riscq.pulses.pack import pack_env

pytestmark = pytest.mark.cosim

# Fires gate slot 0 (or the readout-drive slot) from host-written param globals at
# t = now() + lead, recording t in `t_fire` — the self-referential time reference the
# DAC-capture tests align against. Params carry RQ_PARAM (.data): pre-run host writes must
# survive start.S's .bss zeroing.
PULSE_SRC = """
#include "riscq.h"
volatile int32_t RQ_PARAM ch_sel = 0;      /* 0 = gate, 1 = readout drive */
volatile int32_t RQ_PARAM freq_code = 0;
volatile int32_t RQ_PARAM amp_code = 0;
volatile int32_t RQ_PARAM phase_code = 0;
volatile int32_t RQ_PARAM env_line = 0;
volatile int32_t RQ_PARAM dur = 0;
volatile int32_t RQ_PARAM lead = RQ_LEAD;
volatile uint32_t t_fire = 0;
int main(void) {
    uint32_t ch = ch_sel ? RF_CH1 : RF_CH0;
    set_start(ch, 0);   /* startTime is a live, NEVER-RESET buffer register: zero it so the */
                        /* freq push below is due immediately (a stale startTime from an    */
                        /* earlier run would delay the phasor regen past our window)        */
    set_freq(ch, freq_code);
    set_phase(ch, 0, phase_code);
    set_amp(ch, 0, amp_code);
    set_env(ch, 0, (uint32_t)env_line);
    set_dur(ch, 0, (uint32_t)dur);   /* params arrive pre-seated in data[31:16] (spec 12) */
    uint32_t t = now() + (uint32_t)lead;
    t_fire = t;
    play(ch, 0, t);
    wait_until(t + ((uint32_t)dur >> 16) + 8);   /* dur is a seated field; batches = dur >> 16 */
    return 0;
}
"""

# Captures are SIZED, not generous (01 §3.3): `dac_capture_get` BLOCKS until the armed window is
# full, so every armed batch past the pulse is simulated for nothing. From arm (pre-release) the
# window has to cover boot + preamble + the scheduled lead — MEASURED at index 482 for the default
# LEAD — plus the longest `dur` in this file (64 batches, the VNA tone). 1000 leaves ~450 batches
# of margin and still checks ~900 batches of DAC silence around the one window.
N_CAPTURE = 1000

# Same fire as PULSE_SRC, but through the new pulse-table op: init_pulse_params programs slot 0
# from a host-filled `struct rq_slot[]` (spec 02 §3.2) instead of individual set_* calls. tbl is
# RQ_PARAM (.data) so the host's pre-run struct fill survives start.S's .bss zeroing.
INIT_PARAMS_SRC = """
#include "riscq.h"
volatile int32_t RQ_PARAM freq_code = 0;
volatile struct rq_slot RQ_PARAM tbl[1] = {{0, 0, 0, 0}};
volatile uint32_t t_fire = 0;
int main(void) {
    init_pulse_params(RF_CH0, tbl, 1);   /* clears startTime, programs slot 0 from tbl */
    set_freq(RF_CH0, freq_code);
    uint32_t t = now() + RQ_LEAD;
    t_fire = t;
    play(RF_CH0, 0, t);
    wait_until(t + ((uint32_t)tbl[0].dur >> 16) + 8);   /* seated field; batches = dur >> 16 */
    return 0;
}
"""


_PROGS: dict[str, Program] = {}     # each source compiled once
_RESIDENT: dict[int, str] = {}      # driver -> the C source currently loaded on core 0
_ENV: dict[tuple[int, int], bytes] = {}   # (driver, channel) -> the lines currently at env line 0


def _upload_env(drv, m, channel, lines):
    """Put `lines` at envelope line 0 of core 0's `channel` — skipping the write when they are
    already there. A 64-line gate envelope is a 256-word block write ≈ 5.6k simulated batches, so
    re-uploading an identical envelope per trial is this file's second cost after the image load.
    """
    key = (id(drv), channel)
    blob = np.ascontiguousarray(lines, dtype="<u4").tobytes()
    if _ENV.get(key) != blob:
        rq.write_envelope(drv, m, 0, channel, 0, lines)
        _ENV[key] = blob


def _load(drv, m, src):
    """Compile `src` (once) and make sure it is the image resident on core 0, leaving reset held.

    An image is not altered by a run and every RQ_PARAM global is rewritten before each trial, so
    re-loading it per trial buys nothing and costs ~7k simulated batches — which was this file's
    dominant cost (01 §3.3). `check_magic` still runs on every call, so a stale cache (some other
    file having loaded over core 0) fails loudly rather than running the wrong image.
    """
    prog = _PROGS.get(src)
    if prog is None:
        prog = _PROGS[src] = Program.from_image(compile_c(src, m))
    rq.reset(drv, m, on=True)
    if _RESIDENT.get(id(drv)) != src:
        rq.load_program(drv, m, 0, prog.image)
        for core in range(1, m.params.qubit_num):
            rq.park_core(drv, m, core)
        _RESIDENT[id(drv)] = src
    rq.check_magic(drv, m, 0, prog)
    return prog


def _play(cosim, channel, lines, freq_code, amp_code, phase_code, lead=None,
          n_capture=N_CAPTURE):
    """Make PULSE_SRC resident on core 0, upload `lines` at envelope line 0, arm a full-boot DAC
    capture, run, and return (t_fire, t0, samples) for the channel's DAC. `channel` is the
    logical RF channel index (0 gate / 1 ro)."""
    drv, m = cosim
    prog = _load(drv, m, PULSE_SRC)
    dur = len(lines)
    _upload_env(drv, m, channel, lines)
    # packed fields arrive pre-seated in data[31:16] (spec 12); ch_sel/lead are plain.
    # `lead` is written on EVERY trial: the image is resident across trials, so an omitted param
    # would inherit the previous trial's value rather than the source's RQ_LEAD initialiser.
    params = {"ch_sel": channel, "freq_code": pack16(freq_code),
              "amp_code": pack16(amp_code), "phase_code": pack16(phase_code),
              "env_line": pack16(0), "dur": pack16(dur),
              "lead": m.LEAD if lead is None else lead}
    rq.write_params(drv, m, 0, prog, params)
    dac = m.gate_dac(0) if channel == 0 else m.ro_dac(0)
    handle = drv.sim.dac_capture_arm(dac, n_capture)
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0], timeout=500_000)
    t_fire = rq.read_var(drv, m, 0, prog, "t_fire")
    rq.reset(drv, m, on=True)
    t0, cap = drv.sim.dac_capture_get(handle)
    return t_fire, t0, cap


def _window_ok(t_fire, t0, cap, lines, freq_code, amp_code, phase_code):
    """True iff the capture holds EXACTLY the golden window at [t_fire, t_fire+dur) and is
    zero everywhere else."""
    dur = len(lines)
    idx = t_fire - t0
    if idx < 0 or idx + dur > len(cap):
        return False
    gold = golden.pulse_window(lines, amp_code, freq_code, phase_code, t_fire, dur)
    outside = np.ones(len(cap), dtype=bool)
    outside[idx:idx + dur] = False
    return np.array_equal(cap[idx:idx + dur], gold) and not cap[outside].any()


# ── 1. DAC window bit-exact (gate -> DAC 0 and readout drive -> DAC 14) ──

def test_dac_window_bit_exact_gate(cosim):
    _, m = cosim
    p = Pulse(envelopes.gaussian(64, 3.0), freq_hz=50e6, amp=0.5, phase=np.pi / 8)
    lines = p.packed_lines(m, 0)                   # 16 batches, 4 samples/line
    f, a, ph = p.freq_code(m), p.amp_code(), p.phase_code()
    t_fire, t0, cap = _play(cosim, 0, lines, f, a, ph)

    dur = len(lines)
    idx = t_fire - t0
    assert 0 <= idx and idx + dur <= len(cap), \
        f"window [{t_fire}, {t_fire + dur}) outside capture [{t0}, {t0 + len(cap)})"
    gold = golden.pulse_window(lines, a, f, ph, t_fire, dur)
    inside = cap[idx:idx + dur]
    assert inside.any(), "no DAC output inside the scheduled window"
    np.testing.assert_array_equal(inside, gold, err_msg="DAC window not bit-exact vs golden")
    outside = np.ones(len(cap), dtype=bool)
    outside[idx:idx + dur] = False
    assert not cap[outside].any(), "DAC nonzero outside [t, t+dur)"


def test_dac_window_bit_exact_readout_drive(cosim):
    """Same pin through the readout-drive channel: 1 stored sample per batch (interp 16),
    summed DAC 14 (core 1's parked channel contributes zero; the AdderTree pipe is modeled)."""
    _, m = cosim
    p = Pulse(envelopes.gaussian(12, 3.0), freq_hz=-50e6, amp=0.4, phase=0.0)
    lines = p.packed_lines(m, 1)                        # 12 batches, 1 sample/line
    f, a, ph = p.freq_code(m), p.amp_code(), p.phase_code()
    t_fire, t0, cap = _play(cosim, 1, lines, f, a, ph)
    assert cap.any(), "no DAC output on the readout-drive DAC"
    assert _window_ok(t_fire, t0, cap, lines, f, a, ph), \
        "readout-drive window not bit-exact at [t, t+dur)"


def test_init_pulse_params_dac_window(cosim):
    """Step 2 gate: init_pulse_params programs a channel's slots from a host-filled
    struct rq_slot[] (the new pulse-table op) — DAC window bit-exact vs the numpy golden."""
    drv, m = cosim
    p = Pulse(envelopes.gaussian(64, 3.0), freq_hz=40e6, amp=0.45, phase=np.pi / 5)
    lines = p.packed_lines(m, 0)
    f, a, ph = p.freq_code(m), p.amp_code(), p.phase_code()
    dur = len(lines)

    prog = _load(drv, m, INIT_PARAMS_SRC)
    _upload_env(drv, m, 0, lines)               # core 0, gate channel, line 0
    rq.write_var(drv, m, 0, prog, "freq_code", pack16(f))
    base = prog.var_addr("tbl")                          # fill tbl[0] = {phase, amp, env, dur}
    for off, val in ((0, ph), (4, a), (8, 0), (12, dur)):   # seated like load_tables (spec 12)
        drv.write32(m.to_host_addr(0, base + off), pack16(val))
    handle = drv.sim.dac_capture_arm(m.gate_dac(0), N_CAPTURE)
    rq.reset(drv, m, on=False)
    rq.poll_done(drv, m, [0], timeout=500_000)
    t_fire = rq.read_var(drv, m, 0, prog, "t_fire")
    rq.reset(drv, m, on=True)
    t0, cap = drv.sim.dac_capture_get(handle)
    assert _window_ok(t_fire, t0, cap, lines, f, a, ph), \
        "init_pulse_params window not bit-exact at [t, t+dur)"


# ── 2. VNA frequency pin ──

@pytest.mark.parametrize("f_code", [1024, 3072, -2048])
def test_vna_freq_pin(cosim, f_code):
    """Play a long square pulse at freq code F, FFT the captured 16*f_dsp sample stream, and
    assert the dominant tone sits at |F| * fs / 2^16 — this pins freq_to_code by measurement.
    (The DAC carries only the real lane, so the tone's SIGN is unobservable — but a
    sign-mishandled negative code would alias near Nyquist, which the check catches.)"""
    _, m = cosim
    lines = pack_env(envelopes.square(256), m.gate_samples_per_line)   # dur 64 batches
    amp = units._amp_code(0.5)
    t_fire, t0, cap = _play(cosim, 0, lines, f_code, amp, 0)

    dur, idx = len(lines), t_fire - t0
    x = cap[idx:idx + dur].astype(float).ravel()        # 1024 samples at fs = 16 * f_dsp
    assert x.any(), "no tone captured"
    spec = np.abs(np.fft.rfft(x * np.hanning(x.size)))
    k = int(np.argmax(spec[1:-1])) + 1
    den = spec[k - 1] - 2 * spec[k] + spec[k + 1]
    kf = k + (0.5 * (spec[k - 1] - spec[k + 1]) / den if den else 0.0)

    fs = units.sample_rate(m.params)
    f_meas = kf * fs / x.size
    f_expect = abs(f_code) * fs / (1 << 16)
    tol = 2 * fs / x.size                               # 2 FFT bins
    assert abs(f_meas - f_expect) <= tol, \
        f"measured {f_meas / 1e6:.3f} MHz != |{f_code}| -> {f_expect / 1e6:.3f} MHz (+-{tol / 1e6:.3f})"


# ── 3. LEAD validation ──

def test_lead_too_late_fails_loudly(cosim):
    """Scheduling at now()+4 (far below every queue lead) must NOT produce the exact window."""
    _, m = cosim
    lines = pack_env(envelopes.square(64), m.gate_samples_per_line)    # dur 16
    f, a = 2048, units._amp_code(0.5)
    t_fire, t0, cap = _play(cosim, 0, lines, f, a, 0, lead=4)
    assert not _window_ok(t_fire, t0, cap, lines, f, a, 0), \
        "a lead of 4 batches produced a correctly-placed bit-exact window — LEAD test broken"


def test_lead_margin(cosim):
    """LEAD (map.py) must work exactly, and dominate both the measured minimum and the
    worst-queue bound linkPipe + leadFreqP. Records the empirical minimum working lead.

    Each trial uses DIFFERENT amp/phase codes: the RF channels live in dspCd and are NOT
    reset between runs, so a late-landing parameter would otherwise be masked by a stale
    register holding the same value (how the too-small M0 LEAD=64 initially hid)."""
    _, m = cosim
    lines = pack_env(envelopes.square(64), m.gate_samples_per_line)    # dur 16
    f = 2048

    results = {}
    for i, lead in enumerate((m.LEAD, 88, 80, 72, 64)):
        a, ph = units._amp_code(0.3 + 0.05 * i), 1000 * (i + 1)
        t_fire, t0, cap = _play(cosim, 0, lines, f, a, ph, lead=lead)
        results[lead] = _window_ok(t_fire, t0, cap, lines, f, a, ph)
    assert results[m.LEAD], f"LEAD={m.LEAD} did not produce an exact window: {results}"

    working = [lead for lead, ok in results.items() if ok]
    print(f"\n[lead scan] exact-window results: {results}; min tested working = {min(working)}")
    assert m.LEAD >= min(working)
    assert m.LEAD >= m.params.link_pipe + golden.LEAD_FREQ_P, \
        f"LEAD {m.LEAD} below linkPipe+leadFreqP = {m.params.link_pipe + golden.LEAD_FREQ_P}"


# ── 4. Allocator upload into the (write-only) banks (host-side allocator tests live in test_pulses) ──

def test_allocator_upload(cosim):
    """The EnvelopeAllocator dedups + lays out packed lines; write_envelope uploads its whole image
    into the write-only banks (every block in-range and accepted). Content correctness is checked
    bit-exact via the DAC in test_dac_window_bit_exact_* — the banks have no host read port."""
    drv, m = cosim
    rq.reset(drv, m, on=True)
    for channel, spl in ((0, m.gate_samples_per_line), (1, m.ro_samples_per_line)):
        alloc = EnvelopeAllocator(m.params.env_depth)
        la = alloc.add(pack_env(envelopes.gaussian(32, 3.0), spl), name="gauss")
        lb = alloc.add(pack_env(envelopes.cos_edge_square(32, 0.25), spl), name="edge")
        assert alloc.add(pack_env(envelopes.gaussian(32, 3.0), spl)) == la   # dedup
        for line0, lines in alloc.image():
            rq.write_envelope(drv, m, 0, channel, line0, lines)             # upload accepted (in-range)
        assert lb == len(pack_env(envelopes.gaussian(32, 3.0), spl))
    _ENV.clear()        # this wrote over envelope line 0 on both channels: nothing cached is valid


def test_timed_capture_start_batch(cosim):
    """dac_capture_arm(start_batch=...) starts the capture at an absolute batch time: the
    returned t0 equals the requested start (nothing plays here, so the samples are zero).
    Batch time is monotonic across runs (refTime free-runs in dspCd), so the target must be
    picked ahead of the live `now` rather than a fixed absolute value."""
    drv, m = cosim
    rq.reset(drv, m, on=True)
    for core in range(m.params.qubit_num):
        rq.park_core(drv, m, core)
    _RESIDENT.clear()          # the park word overwrote core 0's reset vector: no image is resident
    rq.reset(drv, m, on=False)
    drv.sim.advance(200)                                # batch time is running
    start = drv.sim.batch_time() + 2000                 # a target comfortably in the future
    handle = drv.sim.dac_capture_arm(0, 8, start_batch=start)
    t0, cap = drv.sim.dac_capture_get(handle)
    rq.reset(drv, m, on=True)
    assert t0 == start
    assert cap.shape == (8, 16) and not cap.any()


# ── 5. Amp scaling pin ──

def test_amp_scaling_pin(cosim):
    """Two amplitudes: each bit-exact vs the golden (absolute pin of amp_to_code and the
    1/K prescale), and the captured peaks scale by ~2x (the physics-level check)."""
    _, m = cosim
    lines = pack_env(envelopes.square(64), m.gate_samples_per_line)    # dur 16
    f = 1024
    peaks = {}
    for amp in (0.25, 0.5):
        a = units._amp_code(amp)
        t_fire, t0, cap = _play(cosim, 0, lines, f, a, 0)
        assert _window_ok(t_fire, t0, cap, lines, f, a, 0), f"amp {amp} window not bit-exact"
        peaks[amp] = int(np.abs(cap.astype(int)).max())
    ratio = peaks[0.5] / peaks[0.25]
    assert 1.9 < ratio < 2.1, f"peak ratio {ratio} (peaks {peaks}) not ~2"
    # absolute scale: peak ~= 0.5 * AMP_SCALE * K * FULL^2-ish; the golden equality above is
    # the exact pin, this bounds gross mis-scaling of the analog amplitude.
    assert 14000 < peaks[0.5] < 17000, f"0.5 amp peak {peaks[0.5]} outside the expected band"
