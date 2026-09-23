# PulseTableSoc — multi-qubit control SoC toplevel

**Source:** `src/riscq/soc/PulseTableSoc.scala` · **Package:** `riscq.soc` · **Type:** Component
(`extends Zcu216Top`)

The multi-core control SoC for the Xilinx ZCU216/RFSoC (`xczu49dr-ffvf1760-2-e`). One shared 32-bit
batch-time counter drives one [`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md) core per
`CoreSpec`; a 100 MHz host AXI bus loads programs / pulse tables / control while the faster `dspClk`
domain runs the real-time signal datapath. The RF tree inside each core is the **narrow posted link** —
read [`ARCH.md`](ARCH.md) first for why.

**The whole build comes from one [`SocSpec`](SocSpec.md)**: `class PulseTableSoc(spec, withTest, vivado,
coreParam, hostMemAddrWidth)` reads the cores and their channel lists, and the companion
`PulseTableSoc(qubitNum, dacMap, adcMap, …)` `apply` is the legacy qubit-build signature over
`SocSpec.qubits` (what the sims, benches and `GenPulseTableSoc*` generators still call).

This doc covers the toplevel assembly. The per-qubit internals are in
[`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md); the board wrapper that drives the real
device ports is in [`Zcu216Top`](Zcu216Top.md).

## Role in the system

`PulseTableSoc` is the elaboration unit the Vivado flows synthesise. It owns nothing real itself beyond
the host bridge, the batch clock, the channel→converter maps, the DIO ports and the readout trace — the
cores and the DSP datapath are inside the per-core fibers, and the converter wiring is in the board
wrapper.

```
  io.axi (100 MHz host) ──▶ Axi4ToTilelinkFiber ──▶ hostBus
                                                       ├─ per-core instruction RAM (direct)
                                                       ├─ one region per CHANNEL SLOT j: every core's
                                                       │  j-th envelope RAM (write-only, direct)
                                                       ├─ robs readout buffers (WidthAdapter)
                                                       └─ host control block (reset / timeOffset / DONE / hostwin base)

  refTime+timeOffset ─▶ syncTime ─▶ time + per-core coreTime_i replicas ─▶ cores (dspClk)

  cores ──▶ channels' `dac` (sum co-mapped, AdderTree) ──▶ io.dac       dio channels ──▶ io.dio*_out/in
  io.adc ──▶ channels' `adc` (fan-in) ──▶ cores ;  traced fire ──▶ robs trace
```

## Structure

**Host AXI bridge.** `Axi4ToTilelinkFiber(blockSize = 64)` converts `io.axi` to Tilelink and fans it to a
`hostBus`. `blockSize` covers the widest full-word transfer any slave negotiates (the `robs`
`WidthAdapter`'s 128-bit / 16-byte line); each fiber's decoder restricts the size down to what it supports.
`slotsCount` also caps the host reads in flight toward a core's RAM, which is what keeps the stripped
[`TileLinkCpuMemFiber`](TileLinkMemFiber.md) legal on the shared instruction port — a `require` holds it
under the 14 beats of d-channel buffering back to the bridge.
Host fan-out targets, all derived from [`SocSpecMap(spec)`](SocSpec.md) (offsets relative to each region
bus): per-core instruction RAM (32-bit, direct) and **one region per channel slot** `j` carrying every
core's `j`-th envelope RAM — each a [`BramWriteFiber`](BramWriteFiber.md) whose `TileLinkMemWriteLogic`
steers a 32-bit host beat into the addressed sub-word lane of its wide line, so every bank wires **direct**
to its narrow region bus with no `WidthAdapter`. A slot **no core has a bank in** (a `dio`-only slot) gets
no region bus at all — a fabric `Node` with no slave fails elaboration ([`SOC_TIPS.md`](SOC_TIPS.md) §9) —
and the slot stays a hole in the host map. The first three region buses keep the qubit builds' names
(`pulseMemBus` / `readoutEnvBus` / `demodEnvBus`), further ones are `envBus<j>`. The `robs` readout buffers
(host-readable → still a read/write `BramFiber` + adapter) and the host control block complete the fan-out.

**Shared batch clock, host-gated.** A free-running `refTime` (64-bit, gated by `riscqReset`) plus a
host-written `timeOffset` form `syncTime`; the low 32 bits become the `time` broadcast. Crucially each
core gets its own `coreTime_i` register fed from the *same* `syncTime` — value-identical every cycle (zero
skew), `EQUIVALENT_REGISTER_REMOVAL=NO` so Vivado cannot fold the replicas into one high-fanout net, and
the floorplan pins each replica to its core's band. The cores boot **held in reset**: `riscqReset` powers
up asserted and only releases when the host writes the control block (write `0x01` then `0x00` to
`riscqReset`), so nothing runs with uninitialised state before the first host reset pulse.

**Completion flag.** Every core's sticky `done` level ([ControlMemMaps](ControlMemMaps.md)) is crossed
into `hostCd` by a per-bit `BufferCC` and packed into one read-only host-control word at `0x50`, bit per
core. That is what `riscq.run.poll_done` reads, so a completion poll costs one host read for the whole SoC
and never touches a core's RAM — the port instruction fetch shares with the host image-load master
([specs/software/23](../../specs/software/23-done-register.md)). `riscqReset` clears the source registers,
so the word self-clears at the run boundary. The bits are independent levels, and `dspClk`/`hostClk` are an
asynchronous clock group, so the crossing needs no coherence and is not timed against the 500 MHz path.

**Board hub (cross-core puts).** One [`PutHub`](PutHub.md) in `riscqCd` takes every core's system
puts (`xput`, the node ≥ 16 half of its posted link) and re-puts group words, barrier releases and
unicast signals on one ordered broadcast; the top replicates each beat per core, gated by that core's
mask bit and piped `linkPipe`, into the core shell's `hubIn`. Its sticky `countMismatch` (a barrier's
members disagreed on the count) is crossed like `done` into host-control `0x54`
([specs/cross-core/02](../../specs/cross-core/02-put-network.md) §4).

**Host window (results → PS DDR4).** Each core's write-only 16 MB window funnels through one shared
[`HostWindowFunnel`](HostWindow.md) onto `io.hostMem`, a single-beat write-only AXI4 master wired to the
PS's `S_AXI_HP0_FPD`. The buffer's physical address comes from two more host-control words —
`HOSTWIN_BASE_LO` @ 72 and `HOSTWIN_BASE_HI` @ 76 (`[7:0]` = `base[39:32]`, `[31]` = enable) — written
while `riscqReset` is held, so the funnel is idle when the 40-bit base changes. `enable` powers up low,
so a core storing to the window before the host has programmed `base` stalls instead of writing DDR
address 0.

**Channel → converter maps, from the spec.** Every channel's own `dac` / `adc` field places it on a
physical converter: a DAC takes the real lanes of every channel mapped to it, and `spec.adcMap`
(core → the `adc` of its demod channel) fans the ADC back in. The legacy `dacMap: Map[(core, k), dacId]`
view is the same thing indexed by `k`, the channel's position among its core's DAC-bound channels.
When several channels map to one DAC, the DAC word is the **per-lane sum of their real parts**, built
with [`AdderTree`](../dsp/DSP.md) (the sum wraps modulo `2^w`,
matching the QubiC reference — software keeps co-mapped channels within full-scale; dropping the
saturating clamp also takes its comparators off the converter-boundary path). A single mapped channel is a
trivial pass-through; an unmapped DAC is tied to 0. Only the **mapped** ADCs are buffered (real lane;
`im := 0`) and fanned to the mapped cores; `adcPipe` adds register stages off the RFDC edge — extra latency, acceptable
since fmax is soft. The default `SocChannelMap` (object in the same file) gives each qubit's gate drive
its own DAC `0..qubitNum-1`, and shares converter 14/15 for readout drive and 0/4 for the ADC.

**Timed digital I/O.** Every `dio` channel's bank wires straight to the board — no converter and no map:
`PulseTableSoc.dioNames(spec)` names one port pair per channel, `<core>_<channel>` in spec order, which
[`Zcu216Top`](Zcu216Top.md) emits as `io_dio_<core>_<channel>_out` / `_in` ([`TimedDio`](TimedDio.md)).

**Readout trace (`robs`).** On a fire of any channel marked `trace: true` in the spec (the qubit builds'
readout drive), a `BramFiber` buffer captures the per-lane sum of the mapped ADC inputs into a
32-bit-per-lane trace, addressed by a fire-incremented pointer, for host read-back. Account for the
`AdderTree` latency in the write timing (`fire := RegNext(anyPulseValid)`).

**Per-core hard boundary.** Each core's `RiscvSoc` carries `KEEP_HIERARCHY` so `opt_design` cannot merge
the identical cores' shared host-load logic into a MUXF7/F8 macro straddling two per-core pblocks — see
[`ARCH.md`](ARCH.md) (two-region floorplan) and [`SOC_TIPS.md`](SOC_TIPS.md) §7.

## Parameters that matter

Everything per-build comes from the **spec**, not from constructor arguments: the cores and their channel
lists, each core's `memDepth` / `withMul` / `queueDepth`, the converter ids, the envelope depths, and the
SoC fields `linkPipe`, `hostwin_bits`, `rob_depth`, `adc_pipe`, `dac_num`/`adc_num`. The legacy
`apply(qubitNum, dacMap, adcMap, …)` builds that spec through `SocSpec.qubits`.

- **`spec`** — the [`SocSpec`](SocSpec.md) (from `software/configs/<name>.json` via
  `GenPulseTableSocJson`, or `SocSpec.qubits` via the legacy `apply`).
- **`qubitNum` / `dacMap` / `adcMap`** (legacy `apply`) — core count and the channel→converter placement
  (use `SocChannelMap.dacMap/adcMap(qubitNum)`); full ZCU216 build = 14 cores.
- **`link_pipe`** (spec, default 4) — per-direction `RegNext` depth of the posted link; raise it to span a
  wider core↔converter floorplan (it only adds to the constant software lead-time `D`). See
  [`ARCH.md`](ARCH.md).
- **`vivado`** (default false) — when set, emits the FPGA IP-packager attributes (`X_INTERFACE_INFO`,
  `FREQ_HZ`) on the AXI/AXI-Stream ports and **renames the host clock `hostClk`/`hostRst`**. Keep it off
  for sims and the OOC bench, whose XDC constrains the un-renamed `clk` port (a renamed clock breaks the
  OOC bench). The attributes themselves are sim-neutral; only the rename bites.
- **`withTest`** (default false) — exposes each core's CPU data-bus decode to a second Tilelink master so
  a sim can schedule pulses without a CPU program. In the real SoC the CPU is the sole `dBus` master.
- **`coreParam`** — the RISC-V plugin config replicated across all cores (each core's `fetchPcWidth`
  follows its `mem_depth` and its `withMul` its `with_mul`); defaults to the verified timing-closure stack
  (every flag RVLS-bit-exact). See [`RISCV.md`](../riscv/RISCV.md).
- **per-channel `interp`** (spec) — envelope interpolation factors that shrink the widest BRAM banks.
- **dsp-fmax lever** (specs/dsp-fmax.md, default off / bit-exact, set per build in the config JSON):
  `adc_pipe` (C2, the RFDC-edge ADC pipe depth, default 3). (The B1-alt param-buffer distributed RAM,
  the B2 dcOffset MAX_FANOUT cap and the B3 queue lean-pop are baked into `PulseParamBuffer`/`TimedQueue`;
  the C1 registered head (`regHead`) is a [TimedQueue](../dsp/TimedQueue.md)-level option, no longer
  plumbed through the SoC.)

## RTL generation

Three generator apps emit the toplevel Verilog (there is no plain `GenPulseTableSoc` object — pick the
form the flow needs). `GenPulseTableSocJson` takes a build's [`SocSpec`](SocSpec.md) JSON (and asserts
nothing else about it); the other two build the qubit spec from a count through the legacy `apply`:

```bash
# From a build's SocSpec JSON — what the co-sim / software flows use.  Args: <config.json> <dir> [vivado]
mill runMain riscq.soc.GenPulseTableSocJson software/configs/sim-2q.json software/build/sim-2q/rtl

# OOC fmax / floorplan flow — plain dspClk/clk ports, no IP-packager attrs.  Args: [qubitNum=14] [dir]
mill runMain riscq.soc.GenPulseTableSocOoc 14

# Vivado block-design flow — X_INTERFACE_INFO/FREQ_HZ attrs + ClockInterface.v (vivado = true).
mill runMain riscq.soc.GenPulseTableSocVivado 14
```

The latter two default to `qubitNum = 14` and the `SocChannelMap` layout; pass a smaller count for quick
iteration.
`GenPulseTableSocVivado` also emits the `ClockInterface.v` clock-buffer wrapper.

## Verification

- `RamOnFabricSim` — the fabric-wired core (`RiscqFiber` over the memory fibers on a
  `DualClockRam` preloaded from an rv32 ELF): re-runs the `rv32ui-p` suite under RVLS lock-step, proving
  the core→fabric bridge + memory fibers + address decode.
- `PulseTableSocSim` — the assembled SoC, bus-driven (no ELF): an AXI host round-trip; a scheduled gate
  pulse propagating through the `dacMap` `AdderTree` to the mapped DAC with `robs` capture; and a VNA-style
  readout (matched ≫ detuned magnitude) over the full `io.adc → demod → integrate → read-back`. The
  readout is checked by magnitude (phase-invariant for a matched tone) rather than bit-exact, so it is
  robust to the bulk `io.adc`/`io.time` → integrator latency — see [`SOC_TIPS.md`](SOC_TIPS.md) §4.2.
- `PulseTableSocCpuSim` — CPU-in-the-loop: the RISC-V core runs `sw/pulse_sched.elf`, reads `time`, writes
  the gate buffer `startTime`, programs `table[0]` and fires — the pulse reaches the DAC with no test
  master (`withTest = false`, the CPU the sole `dBus` master, exactly as the real SoC).

```bash
mill runMain riscq.soc.sim.RamOnFabricSim
mill runMain riscq.soc.sim.PulseTableSocSim
mill runMain riscq.soc.sim.PulseTableSocCpuSim
```

OOC fmax is signed off by `riscq.soc.bench.PulseTableSocVivadoBench` (a SoC-specific two-clock XDC — tight
`dspClk`, loose `clk`, async-grouped; the generic single-clock bench would leave `dspClk` unconstrained,
see [`SOC_TIPS.md`](SOC_TIPS.md) §6). fmax is a soft constraint — recorded, not tuned.

## Related

- [`ARCH.md`](ARCH.md) — the posted-link architecture and the two-region floorplan (read first).
- [`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md) — one qubit core + its DSP datapath.
- [`Zcu216Top`](Zcu216Top.md) — the board wrapper + the `vivado-scripts/` flows.
- [`SOC_TIPS.md`](SOC_TIPS.md) — fabric / SpinalSim gotchas (read before SoC work).
- [`QUBIC_DATAPATH_COMPARISON.md`](QUBIC_DATAPATH_COMPARISON.md) — datapath vs the QubiC reference.
