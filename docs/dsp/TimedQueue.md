# TimedQueue — lead-time fire scheduler (`riscq.dsp.pulse`)

**Source:** `src/riscq/dsp/pulse/TimedQueue.scala`, `src/riscq/dsp/pulse/TimedQueueVariants.scala`
· **Package:** `riscq.dsp.pulse` · **Type:** Component(s) over a shared `TimedQueueIo` / `TimedQueueLike`

A queue that **latches `startTime` at enqueue and pops/fires at the exact `dspClk` cycle the head
becomes due** (`time == startTime − leadTime`). This is the primitive that **decouples
enqueue-arrival-time from fire-time** — the property the whole posted-link SoC architecture is built
on.

## Why it matters

The CPU is a *lead-time scheduler, not a real-time driver*: it writes a pulse table entry plus
`startTime = time + lead`, then "fires" by writing an index, and the DSP-side queue emits the pulse at
the precise cycle `time` reaches `startTime`. Because the queue fires on the *value* of `startTime`,
not on when the entry arrived, a **long, pipelined, posted** link from core to DSP costs only a
constant latency that the lead absorbs — nothing on that path is timing-critical. See
[SoC ARCH](../soc/ARCH.md) for the full rationale. The [ReadoutDecoder](ReadoutDecoder.md)'s integration
window is scheduled the same way: it is the valid window of a demod-carrier pulse generator.

## Contract

- **`io.push`** — `Stream(TimedEntry{data, startTime})`. A `Stream` (not a `Flow`) so Tilelink glue
  sees backpressure when the queue is full.
- **`io.pop`** — `Flow(data)`. Fires **once per entry**, at `time = startTime − leadTime`. External
  cmd→pop **latency is 0** for every implementation.
- **`io.time`** — the external SoC batch-time counter (a +1/cycle counter).

`leadTime` is the consumer path's latency to the DAC, so the entry is popped that many cycles early and
the pulse lands at the converter at exactly `startTime`. `timeOffset` is an extra calibration constant
folded into the due test. The compare is **wrap-safe** — it uses the signed difference, correct for any
schedule within `±2^(timeWidth−1)` of now, so the time counter wrapping `2^timeWidth` is a non-event.

`TimedEntry`, `TimedQueueIo`, and the `TimedQueueLike` trait are shared by all implementations, so one
testbench body drives any of them.

## The implementations and why they exist

`TimedQueue` itself carries an **`impl` selector** (`TimedQueueImpl`, default `RegHead`) choosing
one of four microarchitectures behind the same io/contract; the `PulseGenerator`/
`PulseGeneratorAligned` params expose it as `queueImpl`. Two further II=1 variants live in
`TimedQueueVariants.scala` for swap-in by renaming.

| Implementation | II | latency | storage | the trade |
|---|---|---|---|---|
| `impl = RegHead` (default) | 2 | 0 | `StreamFifo` (LUTRAM) | registered compare + m2sPipe head — the deployed queue |
| `impl = Srl` | 2 | 0 | `SrlFifo` (SRL) | RegHead's FSM, deadline precomputed at push; no FIFO pointers ⇒ the tiny (CLB-fragmenting) control sets vanish |
| `impl = Shadow` | 3 | 0 | `StreamFifo` (LUTRAM) | CE-free shadow register of the head's deadline instead of the m2sPipe stage: −12 FF, −1 control set per queue |
| `impl = SrlShadow` | 3 | 0 | `SrlFifo` (SRL) | the congestion-leanest compose: −2.5 control sets and −15 FF per queue vs RegHead, +18 LUT |
| `TimedQueueRegArray` | 1 | 0 | register array | per-slot registered due bit |
| `TimedQueueDeadline` | 1 | 0 | `StreamFifo` | precomputed deadline, combinational compare |

All four `impl` options keep the registered due test (the compare starts at a flip-flop), the lean
2-FF `pop.valid`, and the same `lead + 3` exact-pop push margin; they differ in replication
congestion cost and back-to-back drain rate (II — irrelevant for the well-spaced schedules the
software LEAD contract guarantees; malformed schedules still drain, one entry per II cycles). The
per-queue numbers come from a 48-queue post-route comparison bench (xczu49dr, depth 4); at
depth 32 the LUTRAM is depth-free (RAM32 primitives), so the `Srl*` LUT premium grows (~+50 %)
while their control-set/FF wins persist. The II=1 variants price poorly post-route (combinational
due / per-slot compares) and exist for schedules that genuinely need back-to-back drain.

**`RegHead` (default).** A `StreamFifo` of `{data, startTime}` whose wrap-safe due test is
**registered** and then edge-detected to fire once:

```
due    = !((time + (leadTime + timeOffset + 1) − startTime).msb)   // wrap-safe geq
timeUp = RegNext(head.valid && due)                                // the 32-bit compare, registered
blank  = RegNext(pop.valid)                                        // fire-once / drain blank
pop    = timeUp && !blank                                          // lean 2-FF form (below)
```

Putting the 32-bit compare *between registers* keeps it off the combinational `pop.valid → consumer`
critical path, at roughly half the LUTs of a per-slot-registered queue — this is how the RISC-Q design
reaches its high fmax. The `+1` inside the compare cancels the compare register's cycle, so the pop
still asserts at exactly `time = startTime − leadTime` (latency stays 0). The cost is **no back-to-back
II=1**: the `blank` cycle that makes the edge-detect fire once also means several already-due entries
drain one every two cycles. This never binds in practice — the pulse generator spaces each parameter's
updates far apart; II=1 only matters for malformed/overlapping schedules. When it *is* needed, swap in
a variant.

**`Srl`.** RegHead's exact pop FSM over an `SrlFifo` — a shift array (slot 0 takes each push, the
oldest entry sits at the dynamic tap `slots(count − 1)`) whose slots carry no reset and share one
write enable, the Xilinx dynamic-SRL idiom (SRL16E/SRLC32E, `depth ≤ 32`). Storage then costs no
flip-flops and there are **no FIFO pointers**, only a CE-free occupancy count — the per-queue
pointer clock-enables, the tiny control sets that fragment CLB packing when the queue is
replicated per channel, vanish. The FIFO payload is `{data, deadline}` with
`deadline = startTime − (lead + offset + 1)` folded in at push, so the per-cycle due test is a
single 2-operand subtract. The SRL tap read (A→Q) into the head stage is the one
placement-sensitive path at very high clocks.

**`Shadow`.** Drops the m2sPipe head stage: a **CE-free, reset-free shadow register** tracks the
FIFO head's precomputed deadline one cycle behind (`shadowDl := RegNext(head.deadline)`,
unconditional — no control set), and the registered due compare reads the shadow, so the
`ptr → LUTRAM → compare` cone is still cut at a register at a fraction of the m2sPipe's area. The
FIFO head is consumed at pop (pop data reads the LUTRAM directly), and after a pop the shadow is
stale one extra cycle — the fire-once blank stretches to **two** cycles, hence II=3.

**`SrlShadow`.** `Shadow`'s FSM over `SrlFifo` storage — the congestion-leanest compose: every
flip-flop in the queue is either CE-free (shadow, compare, blanks, count) or reset-free (the SRL
slots), so the queue contributes no unique control set beyond the module-shared reset.

**`TimedQueueRegArray`.** A register buffer that stores each slot's data, precomputed
`deadline = startTime − lead`, and a **registered due bit** maintained *every cycle for every slot* via
the identity `RegNext(time+1 ≥ d) ≡ (time ≥ d)` (valid because `time` is a +1/cycle counter). Because
the next head's due bit is already correct when the current entry pops, it drains consecutive due
entries **one-per-cycle (II=1)** with the 32-bit compares register-to-register. Cost: one comparator
per slot instead of the active queue's single one.

**`TimedQueueDeadline`.** Keeps the `StreamFifo` but stores the precomputed `deadline = startTime −
lead` (a constant subtract registered at push) instead of `startTime`, so the per-cycle pop test is a
single subtract `time − deadline` rather than add-then-subtract. **II=1, latency 0**, leanest of the
II=1 options — but the compare is still *combinational* on the `pop.valid` path (the active queue's
registered compare is what it gives up for II=1).

## Storage levers on the active queue

`TimedQueue` exposes two flags that trade fmax against routing congestion (FPGA control-set count):

- `useVec` — `false` (default) puts the payload in a distributed-RAM `Mem` (one write port ⇒ per-slot
  control sets vanish); `true` is a register array giving every slot its own write-enable (higher
  fmax, the GShare-like per-slot control-set pattern).
- `forFMax` — `false` (default) uses plain binary FIFO pointers; `true` adds the fmax-tuned gray /
  empty-tracker pointer machinery.

The default `useVec = false, forFMax = false` is the **congestion-best** config. The FIFO uses
`withAsyncRead = true` so the head is combinationally available for the registered due test, keeping
external latency at 0.

## In-SoC timing levers

The **lean pop is baked in** — `pop.valid := timeUp && !blank`, with the `head.valid` term dropped.
The term is redundant: `timeUp` already embeds `head.valid` from the previous cycle, `head.valid` can
only fall via a pop (there is no flush), and `blank` masks exactly that cycle. `pop.valid` is thus a
2-FF product, taking the FIFO pointer-compare (occupancy) off every consumer clock-enable — the in-SoC
`pop.valid → cnt/CE` broadcast family. It is **bit-exact at the pop `Flow`** (see Verification below).

One remaining flag, also bit-exact at the pop `Flow` (**default on**), a **TimedQueue-level option**
applying to `impl = RegHead` only:

- `regHead` — an `m2sPipe` head stage between the async-read FIFO and the due test, so the
  32-bit compare reads `startTime` from a **register** instead of pointer→LUTRAM (the one real
  6-level cone in the 14q SoC → ~3 levels). The stage refills combinationally from the async read
  in the same cycle it is popped, so back-to-back drain **stays one entry per 2 cycles**; the one
  real cost is +1 cycle push→head visibility — an entry must be pushed ≥ `leadTime + 3` (was `+ 2`)
  cycles before its `startTime` to pop at exactly `startTime − leadTime`, inside the software LEAD
  contract's margin. It also adds one slot of effective capacity (`depth + 1`).

## Usage

```bash
mill runMain riscq.dsp.pulse.sim.TimedQueueSim          # the default RegHead impl (both regHead values)
mill runMain riscq.dsp.pulse.sim.TimedQueueImplSim      # the Srl / Shadow / SrlShadow impls (RISCQ_TQ_DEPTH=32 for deep queues)
mill runMain riscq.dsp.pulse.sim.TimedQueueVariantsSim  # the II=1 variants
```

`PulseGeneratorSim` takes `RISCQ_QUEUE_IMPL=Srl|Shadow|SrlShadow` to run the end-to-end
exact-window golden over a non-default impl (all pass bit-exact).

## Verification

`riscq.dsp.pulse.sim.TimedQueueSim` runs a **cycle-accurate mirror** of the hardware FSM in lock-step
— it models the registered compare, the fire-once `blank`, and (under `regHead`) the m2sPipe head
stage exactly, and asserts `pop` equal every cycle — over scenarios: clean well-spaced timing,
past-due (drains II=2), full-queue backpressure + drain/refill, time wrap-around, a calibration
`timeOffset`, the tightest exact-pop push margin (`lead + 3`), push-when-due, and a long randomized
near-future schedule, checking the precise pop cycle, the wrap-safe geq, and in-order completeness.
Every scenario runs over **both `regHead` values**, and the model always evaluates the *original* pop
expression `head.valid && timeUp && !blank` — the baked-in lean DUT matching it every cycle is the
standing bit-exactness proof for dropping the `head.valid` term.

`riscq.dsp.pulse.sim.TimedQueueImplSim` gives the `Srl` / `Shadow` / `SrlShadow` impls the same
treatment — a cycle-accurate mirror per FSM (Srl reuses the regHead mirror; Shadow/SrlShadow get
the shadow/2-cycle-blank mirror), always evaluating the ORIGINAL pop expression against the DUT's
lean form, over the same scenario set (incl. an overfill/backpressure corner that adapts to
`RISCQ_TQ_DEPTH`, and the `lead + 3` tight-margin exact-pop bound shared by every impl).

`riscq.dsp.pulse.sim.TimedQueueVariantsSim` drives both variants through one body against a
*combinational* due model (`pop == (time + lead ≥ startTime)` for the head every cycle), confirming
they drain back-to-back due entries **one-per-cycle (II=1)** while passing the same exact-cycle,
backpressure, wrap, and in-order checks.

## Related

- [PulseGenerator](PulseGenerator.md) — the main consumer (fires parameter updates to the carrier /
  [EnvelopeReader](EnvelopeReader.md) paths).
- [ReadoutDecoder](ReadoutDecoder.md) — integrates over a demod-carrier pulse window scheduled by these queues.
- [SoC ARCH](../soc/ARCH.md) — the lead-time scheduler rationale this enables;
  [PulseParamBuffer](../soc/PulseParamBuffer.md) — the DSP-side register file that drives the queue.
