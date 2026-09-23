# PulseParamBuffer — DSP-side register file for one pulse generator

**Source:** `src/riscq/soc/rf/PulseParamBuffer.scala` · **Package:** `riscq.soc.rf` · **Type:** Component

The converter-edge register file for a single [PulseGenerator](../dsp/PulseGenerator.md): it holds that
generator's pulse table, `freq`, `startTime` and a local `time` copy, all driven by the demuxed **posted**
`Flow(Put)` (no TileLink, no D channel), and emits the parameter `Flow`s plus `time`/`startTime` to the
generator. It is the *only* part of the per-generator control state that must sit at the converter edge
with the generator — its sole CPU-facing input is a narrow `Flow`.

## Role in the system

```
  PutLink demux ── Flow(Put) ──▶ PulseParamBuffer ──▶ PulseGenerator ──▶ DAC
  time broadcast ───────────────▶  (table / freq /        (lead-time
                                    startTime / time)       TimedQueues)
```

It replaces the old per-peripheral TileLink `SlaveFactory` with a register file decoded directly off the
one-way `Put` stream. Packaged with its generator into a [PulseDriveChannel](RfChannels.md) — the box the
floorplan pins.

## What the posted stream writes

The `cmd` address (combinationally decoded; `cmd` is already a registered handoff) selects the target. The
address map follows the RF register layout the control software writes (see `PulseParamBufferParams`):

- `fire`@0x0 — writing the table index `outId` **enqueues** that entry at the current `startTime`, then
  **advances `startTime` by the fired entry's `dur`** (auto-advance, below) so the next fire is back-to-back.
- `freq`@0x4 — the shared carrier frequency (16-bit field at bit 16).
- `dcOffset`@0x8 — DC bias added to the real output lanes downstream in `PulseDriveChannel` (16-bit field at bit 16).
- `phaseOffset`@0xC — virtual-Z phase added to the generator's phase input downstream in `PulseDriveChannel` (16-bit field at bit 16).
- `table[i]` at `(i+1)*0x10` — `+0` phase, `+4` amp, `+8` env (envelope base address), `+12` dur.
- `startTime`@0x4100 — **this buffer's own** `startTime` register (an explicit write is absolute;
  auto-advanced by each fire — below).

Both `dcOffset` and `phaseOffset` are plain registers here — the buffer only holds them and re-exports them
on `io`; the actual bias/rotation is applied in `PulseDriveChannel` (`dcOffset` in one `RegNext` on the real
output lanes, `phaseOffset` combinationally on the generator's phase input, wrapping modulo 2^w = a phase turn).

The decode is **parallel per target**, not one address switch: the target windows are disjoint, so each
scalar register (`fire`/`freq`/`startTime`/`dcOffset`/`phaseOffset`) costs one exact-match compare, and the
table write is split by address — slot index `address >> 4` (one range compare over `1..pulseNum`; entry `i`
lives in slot `i+1`) plus field select `address[3:2]` — instead of `4·pulseNum` full-address comparators.
Upstream traffic is word-aligned 4-byte Puts ([PutBridge](PutBridge.md)), so `address[1:0]` is always 0.
Each beat still writes exactly one field of one entry. Reset/init is all zero, so an un-programmed or
spurious-reset fire reads a benign `dur = 0`.

## Design rationale & contracts

- **Per-buffer `startTime`, written on the same posted path as the fire.** `startTime` is *not* broadcast;
  it is a buffer-local register written by this buffer's own `Put` stream. Because `startTime` and the
  `outId` fire share one **ordered** posted path, the fire always enqueues *exactly* the value just
  written — no cross-path race between a separately-broadcast `startTime` and a separately-posted fire
  ([ARCH](ARCH.md) §5.3).
- **`startTime` auto-advances on every fire** (spec 09 B0). A fire adds the fired entry's `dur` to the
  buffer's `startTime` register, applied the **beat after** the fire beat — the fired slot's
  `outId`/`outParam` settle one cycle later, so the fired `dur` is read then
  (`when(RegNext(fireBeat) && !explicitStartWrite) { startTime := startTime + outParam.dur }`). Consecutive
  fires therefore schedule back-to-back: `set_start(t); fire(a); fire(b)` plays `a` at `[t, t+dur_a)` and
  `b` at `[t+dur_a, t+dur_a+dur_b)`, so a contiguous pulse train is one `play` + N−1 bare `fire`s with no
  per-pulse time arithmetic. `startTime` is a live, never-reset register (`init 0`) and the increment
  applies to all instances (gate/ro/demod); existing software is unaffected because every `play()` rewrites
  `startTime` first.
- **An explicit `startTime` write wins over the increment.** All `startTime` updates happen in cmd **beat
  order** — an explicit `set_start` (a `startTimeAddr` write) at its own beat's edge, a fire's increment at
  the following edge. The only collision is an explicit write on the beat right after a fire; the explicit
  (absolute) write wins via the `!explicitStartWrite` guard on the increment, so `…fire(a); set_start(t)…`
  leaves `t`, not `t+dur_a`.
- **The export gained one `RegNext` stage** — `io.startTime := RegNext(startTime)`. A fired pulse reaches
  the timed-queue push 2 cycles after its fire beat, and the queues sample `io.startTime` **at push**, so
  the extra stage makes each fired pulse capture the register **as of its own fire beat** (pre-increment)
  while back-to-back fires each capture the running sum. The stage is identical in every buffer, so
  absolute/relative pulse timing stays bit-identical for all existing `set_start`+`fire` software. The
  `freq` path now queues against a `startTime` sample 1 cycle staler — benign (it activates the new freq
  slightly earlier, the existing direction; programs write freq early against `startTime` 0).
- **The same-cycle-rise contract is now software + one structural rule.** With per-buffer `startTime`, the
  guarantee that two channels given the *same* `startTime` rise on the *same* output cycle moves from a
  shared hardware register to a **software contract**: software must write the *same* `startTime` value to
  both buffers. Hardware backs it with one structural requirement — the local `time` copy is a single
  `RegNext(io.timeBcast)` tagged `EQUIVALENT_REGISTER_REMOVAL=NO`, so every buffer has **equal `time`
  broadcast delay**; equal values then imply the same cycle.
- **The load-bearing software lead-time contract.** Software must program `startTime` far enough ahead that
  the posted write *arrives before* `time` reaches it. The constant link delay `D` makes the core's `time`
  copy read `dspTime − D`, so a CPU `startTime = localTime + lead` fires at `dspTime + (lead − D)` — a
  constant, predictable effective lead. The requirement is `lead − D > down-link latency`. This is the
  invariant the whole posted-link architecture rests on — see [PutBridge](PutBridge.md),
  [ARCH](ARCH.md) §2, and the `TimedQueue` lead-time pop in [PulseGenerator](../dsp/PulseGenerator.md) /
  [TimedQueue](../dsp/TimedQueue.md).
- **Bit-exactness.** The fire path is a `Reg(Flow)` timed by one shared post-fire register (`fired`, which
  also applies the auto-advance increment), and `freq` gets one extra output
  register stage; both keep the generated pulse bit-identical to the standalone `PulseGenerator` golden
  (`freq` is queued against the per-buffer `startTime`, which is not rewritten between the `freq` write and
  the fire, so the extra delay is timing-invisible). Both output `valid`s init `False` for a reset-clean
  start (no X-driven spurious fire at t=0).

## Parameters that matter

`PulseParamBufferParams` — `pulseNum` (table depth; gate drive uses several, readout drive uses 1),
`addrWidth` (the RF sub-window width), and **`useMem`** (default **true**): the table is either a
distributed-RAM `Mem` (async-read; drops the table out of FFs to cut control sets / reset FFs, at the
cost of a per-field read-modify-write — the default, the specs/dsp-fmax.md **B1-alt** lever, now baked
on) **or** a flip-flop `Vec` register file (`useMem = false`; combinational read, direct per-field
write). The two storage styles are bit-identical. `useMem` is clamped to a register file when
`pulseNum = 1` (a depth-1 `Mem` has no address, e.g. the readout/demod buffers), so only multi-slot
tables (the gate drive) land in RAM. The `dcOffset` register carries a baked `MAX_FANOUT = 4` cap
(the **B2** lever) so synthesis replicates it next to the 16 consuming output-lane adders in
`PulseDriveChannel` — zero semantic change. Field/time/address widths are derived from the generator's
ports — see the source for the full list.

## Verification

`riscq.soc.sim.PulseParamBufferSim` drives the buffer's posted `Flow(Put)` directly (write `startTime`,
`freq`, a table entry, then fire by writing `outId`), feeds its output `Flow`s into a real
[PulseGenerator](../dsp/PulseGenerator.md), and checks the pulse **bit-exact vs the existing PulseGenerator
golden** — proving the posted-link register file is value-preserving (offsets, `bitOffset=16` field
packing, fire semantics, per-buffer `startTime`). It also asserts the valid window is exactly `dur` long
and that both pulses share the same bulk latency. The `useMem` table style is A/B'd via
`RISCQ_PARAMBUF_USEMEM`.

```bash
mill runMain riscq.soc.sim.PulseParamBufferSim
```

## Related

- [RfChannels](RfChannels.md) — `PulseDriveChannel` packages this buffer with its generator.
- [PulseGenerator](../dsp/PulseGenerator.md) / [TimedQueue](../dsp/TimedQueue.md) — the consumer and the lead-time pop.
- [PutLink](PutLink.md) / [PutBridge](PutBridge.md) — the posted stream feeding it.
- [ARCH](ARCH.md) §5.2–5.3 — per-buffer `startTime` and the same-cycle-rise contract.
