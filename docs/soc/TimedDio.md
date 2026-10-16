# TimedDio — timed digital I/O

**Source:** `src/riscq/soc/dio/TimedDio.scala` · **Package:** `riscq.soc.dio` · **Type:** `Component`
(implements [`Channel`](RfChannels.md))

A bank of **16 timed output lines** and **16 timestamped input lines** — the first non-pulse channel kind
(`kind: "dio"` in the [`SocSpec`](SocSpec.md)). A TTL train is programmed, scheduled and fired with
exactly the ops that play a pulse train, and every input edge posts an event with the batch time it
happened at.

## Role in the system

```
  Flow(Put) ─▶ PulseParamBuffer ─{mask, value}@startTime─▶ TimedQueue ─▶ (out & ~mask)|(value & mask) ─▶ io.dout → board
  time bcast  ─▶                                                                                          16 lines
  board → io.din ─▶ edge detect ─▶ io.event {changed, levels} + io.eventTime ─▶ EventFifoSink (the core's `fifo` sink)
```

The core shell ([`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md)) instantiates it for every
`dio` channel of a core's list, off that channel's demuxed RF sub-window; the toplevel
([`PulseTableSoc`](PulseTableSoc.md)) wires its bank straight to the board ports — no converter, no
channel→converter map. It has no envelope bank (`env_depth = 0`, `memPort = None`, `envLanes = 0`), so it
contributes no entry to the host map — and a channel slot whose channels are **all** `dio` is a hole with
no region bus at all ([SOC_TIPS](SOC_TIPS.md) §9.1).

## Structure

### The register file is a pulse channel's

`TimedDio` wraps the **unchanged** [`PulseParamBuffer`](PulseParamBuffer.md) (16-bit fields, which are
opaque to it), reading its slot fields as digital ones:

| Buffer field | DIO meaning |
|---|---|
| `phase` (`table[i]+0`) | `mask` — which of the 16 lines this entry drives |
| `amp` (`table[i]+4`) | `value` — the levels to drive on the masked lines |
| `env` (`table[i]+8`) | unused (written 0) |
| `dur` (`table[i]+12`) | how long the entry holds, in batches — the `startTime` auto-advance step |

So `fire`@0, `startTime`@0x4100, `init_pulse_params`, `set_start`, `play` and the auto-advance all drive
it unchanged: a TTL train is one `play` + N−1 bare `fire`s, exactly like a pulse train, and the absolute
schedule follows the same per-buffer `startTime` contract ([ARCH](ARCH.md)).

### The scheduled output update

The fired slot's `{mask, value}` is pushed into a [`TimedQueue`](../dsp/TimedQueue.md) with the buffer's
`startTime` (`leadTime = 1`, so the queue pops one cycle before the scheduled batch and the **registered**
output changes on it). On pop the output register takes `(out & ~mask) | (value & mask)` — untouched lines
hold, so independent lines are driven from independent entries. Placement is at cycle precision; the core
only ever schedules by lead time.

**Two limits, shared with the pulse channels:**

- at most `queueDepth` entries scheduled ahead (the core's `queue_depth`, 4 by default) — a push into a
  full queue is silently **dropped**, so pace long trains (the `riscq.cal.base.TRAIN_AHEAD` idiom);
- the queue pops at most **every other cycle**, so the shortest `dur` of an entry followed by another is
  **2 batches**.

### Inputs → events

The 16 input lines are sampled every cycle; any change raises `io.event` with
`{changed[15:0], levels[15:0]}` and `io.eventTime` = the batch time of the sample that changed. The
channel exports this as its `Channel.event` reporter of kind `fifo`, so it lands in the core's
[`EventFifoSink`](EventLink.md): consume-on-read, with the cause time and a sequence number. "Wait for
exposure-out" and "when exactly did the shutter open" are then one halting read.

### Board ports

`PulseTableSoc` names one port pair per `dio` channel, `<core>_<channel>` in spec order
([`Zcu216Top`](Zcu216Top.md)): `io_dio_<core>_<channel>_out` (16 bits out) and
`io_dio_<core>_<channel>_in` (16 bits in) — e.g. `io_dio_q0_ttl_out` for the `ttl` channel of core `q0`.

## Software

**Firmware** (`software/fw/riscq.h`):

| Op | What |
|---|---|
| `dio_slot(ch, slot, mask, value, dur)` | program a slot (`set_phase(mask<<16)` / `set_amp(value<<16)` / `set_env(0)` / `set_dur(dur<<16)`) |
| `pop_event(sink)` | **HALTS** until an event is queued, returns its first data word and consumes it |
| `event_word(sink, k)` / `event_time(sink)` / `event_seq(sink)` / `event_count(sink)` | the popped event's other words, cause time, sequence number, and the queue occupancy |

Playing is the generic set — `init_pulse_params`, `set_start`, `play`, `fire`.

**Python.** `riscq.lang.DioTable(channel, {name: (mask, value, dur_batches)})` is the channel's slot
table: bound and played exactly like a `ParamTable` (`init_pulse_params(ttl.pulses)`,
`play(ttl, ttl["on"], t)`), and `ttl.sink` folds to the channel's event-sink base
(`pop_event(ttl.sink)`). `SocMap.sinks(core)` lists `(channel, sink kind, base)` per reporting channel
and emits `RQ_SINK_<NAME>` into `riscq_map.h`. On the co-sim bench, `drv.sim.dio_capture_arm(name,
n_batches)` / `dio_capture_get(handle)` capture the output port per batch and `drv.sim.dio_set(name,
value)` drives the input lines; `DIO_PIPE` in `riscq/map.py` is the capture's stamp correction (0 on the
`sim-dio` build).

## Verification

- `riscq.soc.sim.TimedDioSim` — posted writes program two slots (`set`/`clear` of line 0, `dur` 10) and
  play `play(set, t0)`, `fire(clear)`, `fire(set)`, `fire(clear)`: the output rises at `t0` and toggles at
  `t0+10`, `+20`, `+30` **exactly** (the `startTime` auto-advance), a back-to-back pair at the shortest
  hold (`dur` 2) lands two cycles apart, and both input edges post `{changed, levels}` stamped with the
  batch the changed sample was taken.
- `software/tests/test_dio.py` (co-sim, the `sim-dio` build) — a kernel plays a `DioTable` train through
  the generic ops; the board port shows the edges at the scheduled batches, and the halting `pop_event`
  returns the host-driven input edge with its time and sequence number.

```bash
mill runMain riscq.soc.sim.TimedDioSim
cd software && PYTHONPATH=. pytest tests/test_dio.py --cosim -q
```

## Related

- [`PulseParamBuffer`](PulseParamBuffer.md) — the register file it reuses as is.
- [`TimedQueue`](../dsp/TimedQueue.md) — the cycle-precise scheduler behind the output update.
- [`EventLink`](EventLink.md) — the `fifo` sink its input events land in.
- [`RfChannels`](RfChannels.md) — the `Channel` contract and the two pulse kinds.
- [`SocSpec`](SocSpec.md) — the `dio` channel fields · [`PulseTableSoc`](PulseTableSoc.md) — the board ports.
