# riscq documentation

Module reference for **riscq** — an analog signal-processing SoC in SpinalHDL: one or more RISC-V
control processors driving hardware that generates and decodes analog signals, on a Xilinx UltraScale+
RFSoC (`xczu49dr`, ZCU216). For the project overview and working conventions, start with the top-level
[`CLAUDE.md`](../CLAUDE.md).

Each page below documents one module/component/plugin — what it is for, where it fits, the design
rationale and contracts, the interface, how to build/simulate it, and how it is verified. Read the
**area overviews** first (linked at the top of each section); they explain the architecture the
individual modules plug into.

---

## RISC-V control core — `riscq.riscv`

The single-issue, in-order, pipelined RV32I core (optional Zmmul multiply-only extension). **Start with the
[core overview](riscv/RISCV.md).**

| Doc | What |
|---|---|
| [RISC-V core](riscv/RISCV.md) | Overview: plugin-on-a-host architecture, the unified pipeline, verification |
| [RiscqParam](riscv/RiscqParam.md) | The configuration object — stage geometry, predictor sizing, optional extensions, levers |
| [PipelinePlugin](riscv/pipeline.md) | The single CtrlLink-chain skeleton + the elaboration protocol |
| [MemBus / DataMemBus](riscv/MemBus.md) | The core's instruction (get-only) and data memory bus shapes |
| [PcPlugin](riscv/PcPlugin.md) | Program counter and next-PC select (redirects, prediction) |
| [FetchPlugin](riscv/FetchPlugin.md) | Cacheless pipelined fetch + the per-id reorder buffer |
| [DecoderPlugin](riscv/DecoderPlugin.md) | RV32I/M decode + the generic `addDecoding`/`addInstruction` engine |
| [RegFilePlugin](riscv/RegFilePlugin.md) | 32×XLEN register file + operand forwarding |
| [SrcPlugin](riscv/SrcPlugin.md) | ALU/branch operand select + the shared add/sub & comparators |
| [IntAluPlugin](riscv/IntAluPlugin.md) | The integer ALU |
| [BranchPlugin](riscv/BranchPlugin.md) | Branch/jump resolution + the predictor corrector |
| [LsuPlugin](riscv/LsuPlugin.md) | Cacheless load/store unit |
| [MulPlugin](riscv/MulPlugin.md) | Optional RV32 M multiply (DSP-packed, halt-execute) |
| [WriteBackPlugin](riscv/WriteBackPlugin.md) | The generic rd-source mux (`addRdSource`) |
| [BtbPlugin](riscv/BtbPlugin.md) | Full-tag register Branch Target Buffer |
| [GSharePlugin](riscv/GSharePlugin.md) | GShare conditional-branch direction predictor |
| [CsrPlugin](riscv/CsrPlugin.md) | Minimal machine-mode Zicsr + trap layer |
| [RiscqProbe](riscv/RiscqProbe.md) | Sim verification harness (RVLS lock-step + Konata) |
| [vs RISC-Q](riscv/RISC-Q-COMPARISON.md) | Architecture comparison against the RISC-Q reference core |

## DSP signal modules — `riscq.dsp`, `riscq.dsp.pulse`

Signal generation and decoding. **Start with the [DSP overview](dsp/DSP.md)** (the shared fixed-point
and phase encodings live there); read [DSP48](dsp/DSP48.md) before any DSP-heavy datapath work.

| Doc | What |
|---|---|
| [DSP overview](dsp/DSP.md) | Number format, phase encoding, II=1 design philosophy, index |
| [DSP48](dsp/DSP48.md) | DSP48E2 inference & packing notes |
| [SinCos](dsp/SinCos.md) | Phase→(cos,sin) (LUT / LUT+Taylor / CORDIC) |
| [Cordic](dsp/Cordic.md) | Rotation-mode CORDIC rotator |
| [ComplexMul](dsp/ComplexMul.md) | 3-multiplier complex product + the `Complex` type |
| [AdderTree](dsp/AdderTree.md) | Pipelined balanced reduction to one sum |
| [PulseGenerator](dsp/PulseGenerator.md) | Timed complex-envelope CORDIC pulse generator (flagship) |
| [CarrierBatchGenerator](dsp/CarrierBatchGenerator.md) | Per-batch carrier samples (NCO + CORDIC + combine) |
| [PhasorBatchGenerator](dsp/PhasorBatchGenerator.md) | Per-frequency phasor batch (serial CORDIC regen) |
| [EnvelopeReader](dsp/EnvelopeReader.md) | Complex-envelope memory front-end |
| [PulseGeneratorAligned](dsp/PulseGeneratorAligned.md) | QubiC-style SRL-aligned variant |
| [ReadoutDecoder](dsp/ReadoutDecoder.md) | Demodulating readout integrator + 1-bit discrimination |
| [TimedQueue](dsp/TimedQueue.md) | Lead-time fire scheduler (decouples enqueue from fire) |

## On-chip memory — `riscq.memory`

| Doc | What |
|---|---|
| [Memory overview](memory/README.md) | The three RAM blocks and when to use each |
| [DualClockRam](memory/DualClockRam.md) | Mem-inferred true-dual-port, dual-clock, byte-write BRAM |
| [Bram](memory/Bram.md) | Dual-clock BRAM over the Xilinx HDL template |
| [Uram](memory/Uram.md) | Single-clock UltraRAM over the Xilinx HDL template |

## SoC & bus fabric — `riscq.soc` (+ `fabric` / `link` / `rf`)

The multi-core SoC tying each core to its converter-edge channel list over a narrow posted link. **Start
with the [SoC architecture](soc/ARCH.md)**; read [SOC_TIPS](soc/SOC_TIPS.md) before SoC work.

| Doc | What |
|---|---|
| [SoC architecture](soc/ARCH.md) | The narrow posted-link RF tree + two-region floorplan (overview) |
| [PulseTableSoc](soc/PulseTableSoc.md) | Multi-core control SoC toplevel, built from a `SocSpec` |
| [SocSpec](soc/SocSpec.md) | The build description (cores → channel lists) and the derived host map, shared with python |
| [RiscvSoc](soc/RiscvSoc.md) | The hard per-core unit (registered-boundary floorplan target) |
| [RiscqRfWithPulseTableFiber](soc/RiscqRfWithPulseTableFiber.md) | One core: core + RAM + control + its channel list over the link |
| [Zcu216Top](soc/Zcu216Top.md) | ZCU216 board toplevel scaffolding |
| [floorplan-harnesses](soc/floorplan-harnesses.md) | `RiscqFarmTop` / `RiscqCloneTop` floorplan-sweep tops |
| [SOC_TIPS](soc/SOC_TIPS.md) | SoC / fabric / SpinalSim gotchas |
| [vs QubiC datapath](soc/QUBIC_DATAPATH_COMPARISON.md) | Pulse→DAC datapath comparison vs QubiC |
| **fabric** — [RiscqFiber](soc/RiscqFiber.md) | Core → Tilelink-fabric adapter |
| [TileLinkMemFiber](soc/TileLinkMemFiber.md) | On-chip RAM as a Tilelink fabric slave |
| [MemMapFiber](soc/MemMapFiber.md) | Memory-mapped register block on the fabric |
| [DualClockRamFiber](soc/DualClockRamFiber.md) | Clock-crossing RAM bank as a fabric slave |
| [BramFiber](soc/BramFiber.md) | Clock-crossing BRAM-blackbox bank as a fabric slave |
| [BramWriteFiber](soc/BramWriteFiber.md) | Write-only clock-crossing BRAM bank (host-loaded envelope memory) |
| [PostedStoreShim](soc/PostedStoreShim.md) | Local-ack posted-store adapter |
| **link** — [RfLinkBridge](soc/RfLinkBridge.md) | Core-side posted-write funnel |
| [RfLink](soc/RfLink.md) | The down-link payload, pipe, and demux |
| [EventLink](soc/EventLink.md) | The up-link as puts into the core's inbox: the reporters' serialisation and the sink kinds (`result`, `fifo`, `latest`, `mailbox`) |
| [PutHub](soc/PutHub.md) | The board hub: group words, counted barriers with a value-carrying release, unicast signals; the root and the lane on multi-board systems |
| [PutLane](soc/PutLane.md) | Put frames on the White Rabbit lane: packer, router, unpacker, the CDC FIFOs around the PCS pair |
| [HostWindow](soc/HostWindow.md) | Per-core write-only window into the PS DDR4 (bridge + funnel) |
| **rf** — [PulseParamBuffer](soc/PulseParamBuffer.md) | DSP-side register file for one channel |
| [RfChannels](soc/RfChannels.md) | The `Channel` contract + its `PulseDriveChannel` / `DemodChannel` kinds |
| [ControlMemMaps](soc/ControlMemMaps.md) | The per-core Time / Done control block |
| **dio** — [TimedDio](soc/TimedDio.md) | Timed digital I/O: 16 scheduled outputs + 16 timestamped inputs |

## White Rabbit synchronization — `riscq.wr`

Two-board `syncTime` alignment over a GTY serial lane; design in
[specs/white-rabbit/](../specs/white-rabbit/README.md).

| Doc | What |
|---|---|
| [WR overview](wr/README.md) | Implemented modules, verification gates, latency constants |
| [Enc8b10b](wr/Enc8b10b.md) | 8b/10b codec pair + `riscq.wr` bit conventions |
| [WrTxPcs](wr/WrTxPcs.md) | TX PCS: framing, CRC16, SOF timestamp trigger, cal pattern |
| [WrRxPcs](wr/WrRxPcs.md) | RX PCS: sync monitor, frame reassembly, RX timestamp trigger |
| [RefTimeTsu](wr/RefTimeTsu.md) | Trigger → 64-bit `refTime` timestamp capture unit |
| [SyncMarker](wr/SyncMarker.md) | `syncTime`-compare scope marker pin |
| [GtySimPhy](wr/GtySimPhy.md) | The phy contract (`WrPhyIo`) + serial-link simulation model |
| [WrNode](wr/WrNode.md) | The memory-mapped WR peripheral (register map, CDC, two-node sign-off) |
| [WrGtyPhy](wr/WrGtyPhy.md) | The real-GTY deterministic PHY (harvested wrappers + reset/bypass/aligner) |
| [WrSoftware](wr/WrSoftware.md) | `PulseTableSoc(withWhiteRabbit)` attach + `wr.py` exchange/measure/sync software |

## Analysis & build tooling — `riscq.bench`, `riscq.misc`

| Doc | What |
|---|---|
| [bench overview](bench/README.md) | The DUT-agnostic analysis & fmax/floorplan engines |
| [PathAnalysis](bench/PathAnalysis.md) | Static, pre-synth netlist analysis |
| [VivadoBench](bench/VivadoBench.md) | Synthesis-only fmax bench |
| [VivadoRouteBench](bench/VivadoRouteBench.md) | Post-place&route fmax bench (authoritative) |
| [PblockBench](bench/PblockBench.md) | OOC floorplan-fit bench (FIT/NOFIT) |
| [misc primitives](misc/README.md) | Xilinx clock primitives + Vivado IP-packaging helpers |

ZCU216 Vivado flows that consume these designs are documented in
[`vivado-scripts/README.md`](../vivado-scripts/README.md).

## Control-software framework — `docs/software/`

The host/firmware framework that writes, loads, drives, and reads back the SoC (design specs). **Start
with the [software framework overview](software/README.md).**

| Doc | What |
|---|---|
| [01 — hardware contract](software/01-hardware-contract.md) | The memory map + fixed-point encodings (single source of truth) |
| [02 — firmware](software/02-firmware.md) | `libriscq` bare-metal C library + build |
| [03 — driver](software/03-driver.md) | The `Driver` ABC, backends, and `Soc` facade |
| [04 — pulse & program](software/04-pulse-and-program.md) | Envelope generation + the sequence DSL |
| [05 — remote](software/05-remote.md) | Pyro5 server / proxy |
| [06 — co-sim](software/06-cosim.md) | cocotb backend + qutip ADC injection |
| [board server](software/board-server.md) | Running riscq on the ZCU216: offline install, bundles, `riscq-board-server`, `RemoteDriver` |
| [`riscq.cal`](software/cal.md) | The calibration library: sequence · axes · measure · analysis on one batched kernel; writing a calibration; the timing rules; verification tiers |
