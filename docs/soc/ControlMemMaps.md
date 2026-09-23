# ControlMemMaps — the per-core Time / Done control block

**Source:** `src/riscq/soc/rf/ControlMemMaps.scala` · **Package:** `riscq.soc.rf` · **Type:** `Area`s
contributing to a `MemMapFiber` (`TimeMemMap`, `DoneMemMap`)

The small CPU-mapped control surface every qubit core sees: the wall-clock `time`, the wait-compare the
scheduling software spins on, and the completion flag the host polls. These are the RF reads that are kept **core-local**
(no link crossing), the counterpart to the channel reports that do cross ([EventLink](EventLink.md)). Ported from the RISC-Q reference (`riscq.soc.Misc`).

## Role in the system

Both are `Area`s whose `mapping(factory)` is contributed to a core-local [MemMapFiber](MemMapFiber.md) off
the CPU `dBus` decode (see [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md) / `RiscvSoc`). The
batch `time` arrives on the down-link's broadcast (a pipelined copy of the DSP-side `refTime + timeOffset`
counter, [PulseTableSoc](PulseTableSoc.md)); everything the CPU reads here is then a **short local arc**.

## `TimeMemMap` — batch time and the scheduling spin-wait

- `time`@0xbff8 — the current batch time, a registered copy of the external `time` input.
- `timeCmp`@0x4000 — a software-written compare value (read/write).
- `waitTimeCmp`@0x4008 — a read that **halts the bus** until `time + delay ≥ timeCmp` (`delay = 3`). This
  is the spin-wait the control software blocks on: the CPU stalls until the wall clock catches up to a
  scheduled instant, then proceeds to fire pulses.

Why a local halt: the halt arc must be short, so the `time` net is pipelined down to the core region and the
compare is done core-locally — the CPU never blocks on a long bus round-trip. The few cycles of `delay`
slack are harmless because real-time precision lives in the DSP `TimedQueue` (see
[PulseGenerator](../dsp/PulseGenerator.md)), not in the CPU wait. The whole posted-link design relies on
`time` being a pipelined local copy that reads `dspTime − D`; the lead-time contract (program `startTime`
far enough ahead) absorbs the constant `D` — see [PulseParamBuffer](PulseParamBuffer.md) and
[ARCH](ARCH.md) §5.3.

## `DoneMemMap` — the run-completion flag

- `done`@0x4010 — a write of bit 0 publishes "this program has finished"; a read returns it.

The bit is **sticky and has no software clear**: `riscqReset` is its only clear, and that is exactly the
run boundary, so a stale flag from the previous run cannot race the next poll and the driver never has to
zero anything. `RiscvSoc` pipelines it out as a plain `done` port; [PulseTableSoc](PulseTableSoc.md) packs
every core's bit into one read-only word at host-control `0x50`, which is what `riscq.run.poll_done` reads.

Why a register rather than the `__rq_status` word it replaced: polling a word in the core's RAM is a Get
on the RAM port that instruction fetch shares with the host image-load master. One register keeps
completion off that port entirely and covers every core in a single host read — see
[specs/software/23](../../specs/software/23-done-register.md). `__rq_status` remains, carrying RUNNING and
the program's exit code, but the host no longer polls it.

## What was here: `HostMemMap`

There is no host→core mailbox in the tree — `fromHost` is not instantiated. All host→core input is D-RAM
writes to named globals ([01 §2](../../specs/software/01-hardware-contract.md)).

## What is *not* here

- **`startTime`** is no longer a control-block register: under the posted link it is **per-buffer**, written
  down the `RfCmd` stream into each [PulseParamBuffer](PulseParamBuffer.md) (`@0x4100` within the RF
  window). The control-map sim adds a local `startTime` reg only to exercise a write; production
  `startTime` rides the link.
- **`res`/`real`/`imag`** (readout result) are served by one of the core's
  [event sinks](EventLink.md) — the demod channel's `ReadoutResultSink`, fed by the up-`Flow` — not by
  this block. A core's sinks sit at `0x4200 + 0x20·k`, one per reporting channel (`k` = its tag), so a
  `dio` channel's input-edge FIFO shares the same window family.

## Verification

`riscq.soc.sim.ControlMapFiberSim` drives a [MemMapFiber](MemMapFiber.md) carrying `TimeMemMap` +
`DoneMemMap` (+ a `startTime` write) over TileLink with a `MasterAgent`, and asserts: `time` reads the
registered external time; `timeCmp` read/writes; `startTime` write-only; `waitTimeCmp`@0x4008 **halts**
until `time + 3 ≥ timeCmp` (released only once the externally-ramped time catches up); and `done`@0x4010
reads 0 out of reset, is set only by a write of bit 0, and is sticky against a later write of 0.

`done` clearing at the run boundary is a `riscqReset` property, so it is checked against the real reset
network in [HostWindowCpuSim](HostWindow.md): both cores raise the host-control `DONE` word over AXI, and
asserting `riscqReset` clears it.

```bash
mill runMain riscq.soc.sim.ControlMapFiberSim
mill runMain riscq.soc.sim.HostWindowCpuSim
```

## Related

- [MemMapFiber](MemMapFiber.md) — the TileLink slave the mappings are contributed to.
- [EventLink](EventLink.md) — the other (crossing) RF read path: the up-link and its sinks.
- [PulseParamBuffer](PulseParamBuffer.md) — where `startTime` now lives.
- [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md) / [PulseTableSoc](PulseTableSoc.md) — the core and the `time` source.
