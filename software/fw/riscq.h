/* riscq.h — THE hardware op library: static-inline volatile accessors over the generated
 * riscq_map.h (addresses evaluated per build by riscq.map.SocMap.gen_header). Kernels and
 * hand-written C call these by name; hardware growth is a header edit, never a compiler change. */
#ifndef RISCQ_H
#define RISCQ_H

#include <stdint.h>
#include "riscq_map.h"

#define RQ_MMIO(a)   (*(volatile uint32_t *)(uintptr_t)(a))
/* 16-bit pulse-parameter fields live at data[31:16]; the ops write the word RAW. Values arrive
 * pre-seated in [31:16] by their producer — python via riscq.map.pack16 for generated/host-loaded
 * codes (a seated code is a single-`lui` constant), or an on-core Q16 accumulator written raw (its
 * integer code is in [31:16]; the low fraction is ignored by hardware). See specs/software/12. */

/* Host-written parameter globals MUST carry this: a zero-initialized global lands in .bss,
 * and start.S zeroes .bss at boot — wiping anything the host wrote before the reset release.
 * RQ_PARAM pins the variable into .data (part of the flat image), so pre-run host writes
 * survive boot. Usage: `volatile int32_t RQ_PARAM foo = 0;` */
#define RQ_PARAM __attribute__((section(".data")))

/* run-state block (defined in start.S .data; the __rq_ prefix is reserved) */
extern volatile int32_t  __rq_status;   /* 0 = RESET, 1 = RUNNING, 0xD04E0000|exit = DONE */
extern volatile uint32_t __rq_magic;    /* 0x52515121 "RQQ!" */

/* Drive channels are the per-core logical RF sub-window bases RF_CH0/RF_CH1 (riscq_map.h,
 * spec 02 §3.2); a kernel passes them as a ParamTable argument, hand-written C uses the macro. */

/* ── time ── */
static inline uint32_t now(void) { return RQ_MMIO(RQ_CTRL_TIME); }
static inline void wait_until(uint32_t t) {
    RQ_MMIO(RQ_CTRL_TIME_CMP) = t;
    (void)RQ_MMIO(RQ_CTRL_WAIT_TIME_CMP);   /* read HALTS until time + 3 >= timeCmp */
}

/* ── pulse programming (posted writes, ordered; args pre-seated in data[31:16], written raw) ── */
static inline void set_freq(uint32_t ch, int32_t code) {
    RQ_MMIO(ch + RQ_FREQ) = code;
}
static inline void set_phase(uint32_t ch, uint32_t slot, int32_t code) {
    RQ_MMIO(ch + (slot + 1) * RQ_SLOT_STRIDE + 0x0) = code;
}
static inline void set_amp(uint32_t ch, uint32_t slot, int32_t code) {
    RQ_MMIO(ch + (slot + 1) * RQ_SLOT_STRIDE + 0x4) = code;
}
static inline void set_env(uint32_t ch, uint32_t slot, uint32_t line) {
    RQ_MMIO(ch + (slot + 1) * RQ_SLOT_STRIDE + 0x8) = line;
}
static inline void set_dur(uint32_t ch, uint32_t slot, uint32_t dur) {
    RQ_MMIO(ch + (slot + 1) * RQ_SLOT_STRIDE + 0xc) = dur;
}
static inline void set_start(uint32_t ch, uint32_t t) {
    RQ_MMIO(ch + RQ_START_TIME) = t;         /* absolute batch time; wins over auto-advance same beat */
}
/* Fire the slot at the channel's current startTime, then AUTO-ADVANCE startTime by the fired
 * slot's dur — so set_start(t) + N bare fires plays a contiguous, back-to-back train. */
static inline void fire(uint32_t ch, uint32_t slot) {
    RQ_MMIO(ch + RQ_FIRE) = slot;            /* slot index in the low bits */
}
/* Absolute-time fire: the explicit set_start wins over any pending auto-advance (same-beat). */
static inline void play(uint32_t ch, uint32_t slot, uint32_t t) {
    set_start(ch, t);
    fire(ch, slot);
}

/* virtual-Z frame rotation: added (mod 2^16 = one turn) to the fired slot's phase, CAPTURED AT
 * FIRE — write it before the fire it should apply to; it persists until rewritten. */
static inline void set_phase_offset(uint32_t ch, int32_t code) {
    RQ_MMIO(ch + RQ_PHASE_OFFSET) = code;
}
/* quasi-static DC bias on the real output lanes (drive channels; no-op on demod). Applied
 * continuously, NOT captured at fire — do not toggle it mid-pulse. */
static inline void set_dc_offset(uint32_t ch, int32_t code) {
    RQ_MMIO(ch + RQ_DC_OFFSET) = code;
}

/* ── pulse table (spec 02 §3.2): a host-retunable rq_slot[] programs a channel's slots ── */
struct rq_slot { int32_t phase, amp, env, dur; };
static inline void init_pulse_params(uint32_t ch, volatile struct rq_slot *tbl, uint32_t n) {
    /* Reset the three live, NEVER-RESET registers (else a stale virtual-Z / DC bias, or a stale
     * startTime from an earlier run, survives into the next run and rotates/shifts every gate).
     * startTime is anchored to now(), NOT 0: a set_freq issued right after init schedules its
     * phasor regen against this startTime, and the timed queue's due test is only wrap-safe within
     * ±2^31 of the free-running io.time (never reset — PulseTableSoc.refTime). now() is always
     * in-window, so the regen is due immediately; an absolute 0 sits ~2^31 away whenever io.time is
     * in the upper half of its range, so the regen never fires and the carrier "sticks" at its old
     * frequency — a hardware-only bug (io.time ≈ 0 in co-sim). See docs/soc/SOC_TIPS.md §5. */
    set_start(ch, now());
    set_phase_offset(ch, 0);
    set_dc_offset(ch, 0);
    for (uint32_t i = 0; i < n; i++) {
        set_phase(ch, i, tbl[i].phase);
        set_amp(ch, i, tbl[i].amp);
        set_env(ch, i, (uint32_t)tbl[i].env);
        set_dur(ch, i, (uint32_t)tbl[i].dur);
    }
}

/* ── readout (carrier-triggered decoder) ──
 * The demod carrier is a scheduled, envelope-shaped pulse on the RQ_DEMOD channel — a plain drive
 * channel pointed at the decoder. It is programmed and fired with the SAME generic ops as any drive:
 *   init_pulse_params(demod.pulses) / set_freq(demod, code) / play(demod, slot, t).
 * The demod carrier frequency is set separately (typically once — it rarely changes across a
 * measurement) with the ADC-rate code (units.demod_freq_to_code, 4x the DAC's freq_to_code).
 *
 * FIRING THE DEMOD IS THE READOUT: the carrier's valid window (the demod slot's `dur`) triggers the
 * decoder, which integrates Sum adc*(env*carrier) over the window and latches {res, real, imag}. There
 * is no separate arm — the integration window length is programmed once in the demod pulse table.
 *
 * read_res HALTS until the integral settles and is IDEMPOTENT (re-reading returns the same result).
 * Freshness is a timing contract: the result latch holds the PREVIOUS shot until this window opens, so
 * wait past the window's opening before reading —
 *   play(demod, slot, t);  wait_until(t + RQ_RO_LEAD);  res = read_res();
 * RQ_RO_LEAD covers the decoder+link latency; reading earlier returns the previous shot. */
static inline int32_t read_res(void)  { return (int32_t)RQ_MMIO(RQ_CTRL_RES); }  /* HALTS; idempotent */
static inline int32_t read_real(void) { return (int32_t)RQ_MMIO(RQ_CTRL_REAL); }
static inline int32_t read_imag(void) { return (int32_t)RQ_MMIO(RQ_CTRL_IMAG); }

static inline uint32_t from_host(void) { return RQ_MMIO(RQ_CTRL_FROM_HOST); }

/* ── timed digital I/O (a `dio` channel, universal-control/01 P5) ──
 * The same register file as a pulse channel: slot = {mask (the phase word), value (the amp word),
 * dur}; fire/play/set_start/init_pulse_params drive it unchanged. At the scheduled batch the bank
 * takes (out & ~mask) | (value & mask). Shortest hold of an entry followed by another: 2 batches. */
static inline void dio_slot(uint32_t ch, uint32_t slot, uint32_t mask, uint32_t value, uint32_t dur) {
    set_phase(ch, slot, (int32_t)(mask << 16));
    set_amp(ch, slot, (int32_t)(value << 16));
    set_env(ch, slot, 0);
    set_dur(ch, slot, dur << 16);
}

/* ── event sinks (universal-control/01 §2.4): the core's reporting channels each own a sink at
 * RQ_SINK_<NAME>. A `result` sink (the demod) is read_res/read_real/read_imag. A `fifo` sink (dio
 * input edges, …): pop_event HALTS until an event is queued, returns its first data word and
 * consumes it; the popped event's other words, cause time and sequence number stay readable until
 * the next pop; event_count is the queue occupancy (non-halting). A gap in event_seq means the
 * queue overflowed. */
/* ── the put network (specs/cross-core/02 §8.1): every op is one or two memory accesses ──
 * A store to RQ_NODE_ADDR(node) + offset is a posted put the board hub dispatches by node:
 *   publish(g, slot, bit)  sets bit `slot` of shared word g on every core's board[g] (the compiler
 *                          assigns the slot per group member);
 *   barrier(id, count)     arrives at barrier `id` (count = the member count), then HALTS on the release
 *                          mailbox and returns the released time t0 — identical on every member;
 *                          schedule from it: play(ch, slot, t0 + RQ_LEAD). Rendezvous only: every
 *                          member arrives and waits;
 *   remote(g, slot)        the last bit published to slot `slot` of group g — read it right after the
 *                          barrier that ordered it (fresh by publish-then-barrier ordering);
 *   signal(core, m, x)     a unicast put into `core`'s mailbox m; wait_signal(m) HALTS until it lands
 *                          and consumes it. One sender per mailbox: the address says who. */
#define RQ_NODE_ADDR(node)      (RQ_RF_WINDOW + ((uint32_t)(node) << 16))
#define RQ_INBOX_OFF(cpu_addr)  ((cpu_addr) - RQ_SINK_BASE)
static inline void publish(uint32_t g, uint32_t slot, uint32_t bit) {
    RQ_MMIO(RQ_NODE_ADDR(RQ_GROUP_NODE0 + g)) = (slot << 1) | (bit & 1);
}
static inline uint32_t remote(uint32_t g, uint32_t slot) {
    return (RQ_MMIO(RQ_INBOX_BOARD + 4 * g) >> slot) & 1;
}
static inline uint32_t barrier(uint32_t id, uint32_t count) {
    RQ_MMIO(RQ_NODE_ADDR(RQ_BARRIER_NODE0 + id)) = count;
    return RQ_MMIO(RQ_INBOX_RELEASE);                        /* HALTS until released; returns t0 */
}
#define RQ_INBOX_NODE(board, core) (((uint32_t)(board) << 8) | (RQ_INBOX_NODE0 + (core)))
static inline void signal(uint32_t node, uint32_t m, uint32_t x) {   /* node = RQ_INBOX_NODE(board, core) */
    RQ_MMIO(RQ_NODE_ADDR(node) + RQ_INBOX_OFF(RQ_INBOX_MAILBOX0 + m * RQ_SINK_STRIDE)) = x;
}
static inline uint32_t wait_signal(uint32_t m) {
    return RQ_MMIO(RQ_INBOX_MAILBOX0 + m * RQ_SINK_STRIDE); /* HALTS until a signal lands; consumes */
}

static inline uint32_t pop_event(uint32_t sink)          { return RQ_MMIO(sink + 0x00); }  /* HALTS */
static inline uint32_t event_word(uint32_t sink, uint32_t k) { return RQ_MMIO(sink + 4 * k); }
static inline uint32_t event_time(uint32_t sink)         { return RQ_MMIO(sink + 0x10); }
static inline uint32_t event_seq(uint32_t sink)          { return RQ_MMIO(sink + 0x14); }
static inline uint32_t event_count(uint32_t sink)        { return RQ_MMIO(sink + 0x18); }

#endif
