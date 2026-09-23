# HostWindow — per-core results straight into the PS DDR4

**Source:** `src/riscq/soc/link/HostWindowBridge.scala`, `src/riscq/soc/link/HostWindowFunnel.scala` ·
**Package:** `riscq.soc.link` · **Types:** `HostCmd` (Bundle), `HostWindowBridge` (Fiber-elaborated
TileLink slave `Area`), `HostWindowFunnel` (`Component`, host clock domain)

Each core gets a **write-only 16 MB window** (`hostwin_bits` in the build JSON, default 24; the x6y3 build
takes 23 = 8 MB so its 8-core buffer fits a 128 MB CMA pool) in its data address space whose stores land in the ZCU216's
**PS-side DDR4** at `base + (core << 24) + offset`. That removes the ~1k-shots-per-run cap the 16 KB
unified I+D UltraRAM imposes on raw result modes, and replaces word-at-a-time MMIO readback (~1 MB/s)
with a numpy view of one contiguous CMA buffer. The design of record is
[specs/software/22-host-window.md](../../specs/software/22-host-window.md) — including why the PS DDR4
and not the PL DDR4 (§1).

This is deliberately **not a DMA engine**: no descriptors, no rings, no memory controller in the PL. One
TileLink slave per core (a twin of [RfLinkBridge](RfLinkBridge.md)), a clock-crossing FIFO, an N:1
arbiter, one 40-bit adder and a single-beat AXI4 write master.

## Role in the system

```
 core i (riscqCd, X0 band)                      shared host fabric (hostCd, 100 MHz)        PS
 ┌────────────────────────────────┐            ┌──────────────────────────────────────┐
 │ LSU ─ PostedStoreShim ─ dBus   │            │                                      │
 │  dMemPortDec ─┬─ 0x0000_0000 ctrl           │  StreamFifoCC ─┐                     │
 │               ├─ 0x0001_0000 RF window      │  (per core)    ├─ N:1 round robin ─  │
 │               ├─ 0x4000_0000 HOST WINDOW ──▶│ HostWindowBridge ─┘   HostWindowFunnel│──▶ S_AXI_HP0_FPD ──▶ DDR4
 │               └─ 0x8000_0000 I+D RAM        │                  base + (core<<24) + offset
 └────────────────────────────────┘            └──────────────────────────────────────┘
```

## `HostCmd` — the payload

| field | width | meaning |
|---|---|---|
| `offset` | 24 | **word-aligned** byte offset inside the core's 16 MB slice |
| `data` | 32 | the store's data word, already byte-lane aligned |
| `strb` | 4 | byte enables — the byte lane of a `sb`/`sh` rides here, not in the address |

Carrying the mask (rather than a narrower address) is what makes the AXI beat a legal aligned single
transfer whatever the store size was.

## `HostWindowBridge` — the core side

A write-only TileLink slave mapped over `SizeMapping(0x4000_0000, 1 << 24)` of the core's data-bus decode
(`RiscvSoc.dMemPortDec`). It is [RfLinkBridge](RfLinkBridge.md) with three changes, all forced by the far
side being real memory rather than a converter-edge register file:

- **`Stream`, not `Flow`.** DDR refresh, Linux traffic on the HP port and the `enable` gate can all stall
  the far side, so the AccessAck is issued *only when the command is accepted*: `bus.a.ready := cmd.ready
  && rsp.ready`, with each of the two forks gating the other's valid. A full FIFO therefore withholds the
  ack and the CPU stalls on that store — **nothing is ever dropped**. (`RfLinkBridge` can ack
  unconditionally because its down path is a never-back-pressured `Flow`.)
- **the byte mask travels**, so `sb`/`sh` work.
- **`Get` is refused** — only `PutFull`/`PutPartial` size 4 are advertised and `s2m.none()`, so the fabric
  never routes a load here. Results are write-only, exactly like the envelope banks.

The LSU issues *every* store — byte, half or word — as a **word `PutPartial` with a narrow mask** at a
word-aligned address (`LsuPlugin.scala:148-150`), so one advertised size covers everything. The bridge
re-clears the low two offset bits anyway, so the emitted AXI beat is provably aligned whatever master
drives the window.

The core's contract is otherwise unchanged: [PostedStoreShim](PostedStoreShim.md) inside
[RiscqFiber](RiscqFiber.md) retires stores in one cycle and drains them in order, so a window store costs
the program nothing beyond the store itself — and store→store order **within a core** is preserved end to
end (one FIFO, one AXI ID).

## The clock crossing

Each core's `cmd` crosses into `hostCd` in a `StreamFifoCC` (depth 16 — a shot tail is 2–3 words, so all
cores can finish a shot at once without stalling), instantiated in
[`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md) — **outside** the hard `RiscvSoc` Component
and its pblock, exactly where `iMemLoad` puts the host→dsp image-load CDC.

Its **push domain is `dspCd`, not `riscqCd`**. The two are the same clock and differ only in reset:
`riscqCd` carries the per-run core reset. A cross-clock FIFO whose push pointer is zeroed every run while
its pop pointer is not would desync — the pop side would read a huge fake occupancy and emit garbage. In
`dspCd` the pointers only ever reset with the board-level `dspRst`. Same clock, so the bridge's
riscqCd-driven `Stream` feeds it directly; a reset mid-run just drops `valid`, and beats are atomic, so
nothing is torn. Whatever was already accepted drains to the same addresses on the next run (the buffer is
allocated once per session), which is idempotent — the host only ever reads offsets the current program
wrote.

Both the `iLoad` and the host-window FIFOs carry SpinalHDL's buffered cross-clock reset; both recovery
arcs are waived in `vivado-scripts/riscvsoc-bd/pblocks-bd.tcl` (same argument — synchronized, released
once while the FIFOs are idle, every pointer `init(0)`).

## `HostWindowFunnel` — the shared side

Lives in `hostCd` (100 MHz) in [`PulseTableSoc`](PulseTableSoc.md), outside every core's hard band.

```
addr = base + (core << offsetWidth) + offset
```

Because the per-core slices are adjacent and `offsetWidth`-aligned by construction, `core ## offset` *is*
the buffer-relative address, so the formula costs **one adder** (registered by a `stage()` right after
it). `base` therefore needs only page alignment, not window alignment.

| AXI field | value |
|---|---|
| `aw.addr` (40-bit) | `base + (core << 24) + offset`, registered |
| `aw.len` / `size` / `burst` | `0` (single beat) / `2` (4 bytes) / `INCR` |
| `aw.id` | `0` (1-bit) — one ID, so the DDRC completes the writes in order |
| `aw.cache` / `prot` | `0` — plain non-coherent write on an HP (not HPC) port |
| `w.data` / `strb` / `last` | the store's data / mask / `1` |
| `b` | accepted and discarded; `bresp` ignored — the window is inside the buffer by construction |

`aw` and `w` are driven from one command through a `StreamFork2`, so the two channels may be accepted at
different times as AXI requires.

**Throughput.** One write per ~2–3 host cycles ≈ 30–50 M writes/s shared by all cores; 14 cores × 3 words
per shot at a 100 kHz repetition rate need 4.2 M/s — about 10 %. Approaching 1 MHz reps would want a
faster port clock or 128-bit write-combining; neither is built.

## The base register — host control block

Two words appended to [`PulseTableSoc`](PulseTableSoc.md)'s host control block (beside `riscqReset` @ 0
and `timeOffset` @ 64/68):

| offset | name | bits |
|---|---|---|
| 72 (`0x48`) | `HOSTWIN_BASE_LO` | `base[31:0]` |
| 76 (`0x4C`) | `HOSTWIN_BASE_HI` | `[7:0] = base[39:32]`, `[31] = enable` |

`base` is the **PS physical** address of the host buffer (the DDR4 SODIMM's first 2 GB are at
`0x0000_0000`, the rest at `0x8_0000_0000`; the port is 40-bit so the high region costs nothing). It needs
page alignment only — the address is added, not concatenated.

While `enable` is low the arbiter output is **held**. A core that stores to the window before the host has
programmed `base` therefore stalls visibly instead of writing DDR physical address 0 — the Linux kernel's
memory. Reset state is disabled; `setup` writes both words while `riscqReset` is asserted, so the funnel
is idle at the moment the 40-bit address changes and it can never be seen torn.

## ⚠ The one ordering rule

**Never read the host buffer from compiled or C code straight after the status read.** Stores are posted
at every stage: the core sees a window store retire in one cycle, and the completion signal (the `done`
register in the host control block, read over MMIO by `poll_done` — [ControlMemMaps](ControlMemMaps.md);
`__rq_status` in URAM before [specs/software/23](../../specs/software/23-done-register.md)) is written by
the program *after* its last result store was accepted — but that store may still be in a FIFO or on the
AXI channel. There is deliberately **no drain/idle
bit** (spec §2.4): the last write lands within microseconds (worst case a few hundred single-beat writes
at 100 MHz), while the host's path from observing DONE to touching the buffer is Python on the ARM — a
5–10× margin on the tightest path. The buffer is read from Python after `poll_done`, and that is the whole
ordering contract. If it ever has to go, the fix is a `pending` counter (accepted − B responses) in the
funnel: one register, one poll.

Across cores nothing needs ordering — each core owns a disjoint 16 MB slice.

## Timing cost

Measured with the single-core band bench (`vivado-scripts/riscvsoc/build-coreband.sh`, 526 MHz /
1.9 ns, the A/B vehicle of [specs/riscv-fmax.md](../../specs/riscv-fmax.md) A2), before vs after the
core-side bridge:

| | WNS | worst cone |
|---|---|---|
| without the host window | **+0.069** | `core.other` +0.069 |
| with it | **+0.054** | `rvsoc-boundary` +0.054 (the new `hostCmd` port), `core.other` +0.062 |

Both MEET at 526 MHz; the 0.015 ns is inside the ±0.1 placement-reseed noise the band bench carries,
and the new worst path is the registered `RiscvSoc` IO boundary the `hostCmd` Stream joins — not core
logic. The funnel itself is 100 MHz logic outside every core's band.

**Block-design build, x6y3 config (8 cores, `software/configs/x6y3.json`, default floorplan,
`vivado-scripts/riscvsoc-bd/build-riscvsoc-bd.sh`, 2026-09-08)** — the first BD build with the window:

| | dspClk WNS | failing | hostClk WNS | LUTs | FFs |
|---|---|---|---|---|---|
| `build/x6y3` (2026-08-06, before the window) | +0.005 | 0 | +3.521 | 120290 | 211093 |
| `build/x6y3-hostwin` (with it) | **+0.023** | 0 | +3.826 | 121239 | 213228 |
| `build/x6y3-hostwin` rebuilt at `hostwin_bits = 23` (2026-09-09, the bundle for the board) | **+0.006** | 0 | +3.446 | 121351 | 214156 |

All three clocks MET in both, hold clean. In the first build the dspClk worst path is the same URAM→iLoad pipe as before (a core's
instruction-fetch read-data register), the hostClk worst path is the pre-existing `S_AXIS` bridge → SmartConnect
hop with 3.8 ns to spare, and the funnel/FIFOs appear only in the 100 MHz hold report (+0.028). Cost:
~950 LUTs / ~2100 FFs for 8 cores (the per-core FIFO + bridge plus the funnel). One packaging fix was
needed for the BD to validate: `M_AXI_HOST` must be associated with `hostClk` in `inc/package-ip.tcl`
(Vivado otherwise infers `dspClk` and rejects the 500 vs 100 MHz mismatch against `S_AXI_HP0_FPD`).
The 14q BD build is still owed (spec 22 W3).

## Verification

`riscq.soc.sim.HostWindowFunnelSim` (`mill runMain riscq.soc.sim.HostWindowFunnelSim [seed]`) drives N
TileLink `MasterAgent`s → bridges → CC FIFOs → funnel → a write-only AXI slave model with a **sparse
golden byte memory** and random `ready` stalls, and checks:

- every store present at `base + (core << 24) + offset` with its mask — as a per-core beat sequence *and*
  byte-exact against the golden memory;
- per-core order preserved, nothing lost, nothing duplicated (the beat sequence must equal the issued
  list exactly);
- back-pressure: with the slave stalling at random no store is dropped;
- the `enable` gate: not one beat escapes while disabled, every core blocks once its FIFO fills, and
  everything lands in order after release;
- a base above 4 GB (the 40-bit high DDR region), both window ends, every byte/half strobe;
- the beats are legal single transfers (`len = 0`, `size = 2`, `INCR`, `last`, one ID).

`riscq.soc.sim.HostWindowCpuSim` is the SoC gate: both RISC-V cores of a 2-qubit
[`PulseTableSoc`](PulseTableSoc.md) run `sw/host_window.S` — a 1024-word ramp, an `sb`, an `sh` and an
ordering sentinel into `0x4000_0000`, then the hardware `done` register — and the same `HostMemModel` on
`io.hostMem` must hold the two ramps at the two slices, in order, byte-exact. Completion is taken from the
host control block's `DONE` word over the real AXI path (as `riscq.run.poll_done` does), and asserting
`riscqReset` afterwards must clear it — that is where the run-boundary clear of
[`DoneMemMap`](ControlMemMaps.md) is checked. That covers the whole real path (`LsuPlugin` → `PostedStoreShim` → the
fabric decode → the bridge → the CC FIFO → the funnel), and in particular proves the LSU's byte mask
survives to `wstrb`.

Both sims share `riscq.soc.sim.HostMemModel`, the write-only AXI slave model (random `ready` stalls, `b`
responses, an ordered beat log and a sparse byte memory).
