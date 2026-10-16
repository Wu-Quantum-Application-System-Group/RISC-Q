# RISC-Q

A RISC-V–controlled SoC for superconducting-qubit control, written in [SpinalHDL](https://github.com/SpinalHDL/SpinalHDL)
and targeting the AMD/Xilinx **ZCU216** RFSoC board (`xczu49dr-ffvf1760-2-e`).

Each qubit gets its own small in-order RV32I control core (optional Zmmul). The core runs the
control program and schedules pulses ahead of time. Converter-edge DSP modules generate the
DAC waveforms and decode the ADC readout cycle-exactly, and a narrow posted link connects each
core to its DSP. The Python package in `software/` compiles experiments into core firmware and
runs them on the board or in co-simulation.

## Repository layout

| Path | What |
|---|---|
| `src/riscq/riscv/` | the RISC-V control core (plugin pipeline, RV32I + Zmmul) |
| `src/riscq/dsp/` | signal generation and decoding: SinCos, pulse generator, readout decoder |
| `src/riscq/memory/` | on-chip RAM blocks (dual-clock BRAM, BRAM/UltraRAM wrappers) |
| `src/riscq/soc/` | the multi-qubit SoC (`PulseTableSoc`), bus fabric, posted links, board top |
| `src/riscq/wr/` | White Rabbit time-sync node |
| `src/riscq/bench/` | Vivado fmax / floorplan benches |
| `software/` | the Python control software (`riscq` package), configs, tests |
| `examples/` | Jupyter notebooks: pulses, readout, calibrations |
| `vivado-scripts/` | the ZCU216 Vivado flows (OOC bench `riscvsoc/`, block design `riscvsoc-bd/`) |
| `docs/` | per-module reference; start at [docs/README.md](docs/README.md) |
| `ext/` | git submodules: `SpinalHDL` (built from source), `rvls` (RISC-V lock-step bindings), `NaxSoftware` (prebuilt riscv-tests ELFs for the core's ISA sims) |

## Requirements

For generating the RTL and building the FPGA image:

| Tool | Version | Notes |
|---|---|---|
| Linux x86-64 | — | developed on Ubuntu 24.04 |
| JDK | 21 (tested) | runs mill / Scala 2.12.18 |
| [mill](https://mill-build.org) | 1.1.0 | pinned in `.mill-version`. Install the mill launcher script. On the first run it fetches that version, Scala and the Maven dependencies (needs network). |
| git | — | with submodule support |
| AMD Vivado | **2026.1** | must be on `PATH` (or set `RISCQ_VIVADO_BIN`). Needs Zynq UltraScale+ RFSoC device support and a license that covers `xczu49dr`. The flow uses the Zynq UltraScale+ PS, RF Data Converter, SmartConnect and Processor System Reset IPs. No board files are needed. |
| Machine | ≥ 32 GB RAM | the 14-qubit implementation peaks at ~16 GB |

Running the simulations and the Python software needs more: Verilator, cocotb, a RISC-V LLVM
toolchain (`riscv64-unknown-elf-clang` on `PATH`), and Python ≥ 3.10. See [software/README.md](software/README.md).

## Getting the source

```bash
git clone <this repo> RISC-Q
cd RISC-Q
git submodule update --init      # ext/SpinalHDL, ext/rvls, ext/NaxSoftware (no need for --recursive)
```

## Building the ZCU216 image — `vivado-scripts/riscvsoc-bd/build-riscvsoc-bd.sh`

This flow builds the full board image. It generates the SoC RTL from a JSON config, packages it as
a Vivado IP, and places that IP in a block design with the Zynq PS, the RF Data Converter and an AXI
SmartConnect. It then runs synthesis, implementation (with the floorplan), the bitstream and the XSA
export.

```bash
cd vivado-scripts/riscvsoc-bd
./build-riscvsoc-bd.sh
```

The script runs two steps:

1. **RTL generation (mill).** It runs
   `mill runMain riscq.soc.GenPulseTableSocJson <config> build/<proj> vivado`. On the first run this
   compiles SpinalHDL and the project, which takes a few minutes.
2. **Vivado (batch).** It runs `flow-bd.tcl` and logs to `build/<proj>/vivado.log`. A full 14-qubit
   run takes a few hours.

### Outputs

Everything goes to `<repo>/build/<proj>/` (`<proj>` defaults to `riscvsoc-bd`; `build/` is
git-ignored):

| File | What |
|---|---|
| `PulseTableSoc.v`, `ClockInterface.v`, `*.bin` | the generated RTL and its memory-init files |
| `PulseTableSoc.bit` | the bitstream |
| `PulseTableSoc.xsa` | the hardware platform (bitstream embedded) for the board software / Vitis / PetaLinux |
| `timing_impl.rpt`, `util_impl.rpt` | post-route timing (the script prints WNS/TNS when it finishes) and per-pblock utilisation |
| `vivado.log`, `*.xpr`, `*.runs/` | the Vivado project and logs |

### Common options

All options are environment variables:

```bash
# a different SoC config (qubit count, DAC/ADC maps, channels): any file in software/configs/
RISCQ_CONFIG=software/configs/x6y3.json RISCQ_PROJ_NAME=x6y3 ./build-riscvsoc-bd.sh

# stop after implementation (no bitstream / XSA)
RISCQ_RUN_BITSTREAM=0 ./build-riscvsoc-bd.sh

# reuse the RTL already in build/<proj> (skip the mill step)
RISCQ_SKIP_GEN=1 ./build-riscvsoc-bd.sh
```

| Variable | Default | Meaning |
|---|---|---|
| `RISCQ_CONFIG` | `software/configs/zcu216-14q.json` | SocParams JSON to build |
| `RISCQ_PROJ_NAME` | `riscvsoc-bd` | build folder name under `build/`, so several designs can build in parallel |
| `RISCQ_RUN_BITSTREAM` | `1` | `0` stops after implementation |
| `RISCQ_SKIP_GEN` | `0` | `1` skips RTL generation |
| `RISCQ_VIVADO_BIN` | dir of `vivado` on `PATH` | Vivado `bin/` directory |
| `RISCQ_PLACE_DIRECTIVE` | `ExtraNetDelay_high` | placer directive |
| `RISCQ_ROW`, `RISCQ_PERROW`, `RISCQ_CONFINE`, `RISCQ_BD_BASE` | see `pblocks-bd.tcl` | floorplan knobs |

A relative `RISCQ_CONFIG` is resolved from the repo root, because mill runs there. The floorplan
in `pblocks-bd.tcl` finds the core count in the netlist and fills the X0 core bands in order.
Both the 14-qubit config and the 8-core `x6y3.json` build with the default knobs.

If the 14-qubit build misses timing by a small margin, run `./close-incremental.sh` afterwards. It
does an incremental re-implementation of that build with the `TimingClosure` directive. See
[vivado-scripts/riscvsoc-bd/README.md](vivado-scripts/riscvsoc-bd/README.md) for the flow details,
the results of record, and how this flow differs from the out-of-context bench.

## Other entry points

```bash
mill runMain riscq.riscv.RiscqGen                # the core's Verilog
mill runMain riscq.soc.sim.PulseTableSocSim      # bus-driven SoC simulation sign-off
mill runMain riscq.soc.sim.PulseTableSocCpuSim   # CPU-in-the-loop pulse simulation
```

The per-module simulations and benches are listed in the docs: [docs/README.md](docs/README.md).
