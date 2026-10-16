# Zcu216Top — ZCU216 board toplevel scaffolding

**Source:** `src/riscq/soc/Zcu216Top.scala`, `src/riscq/soc/Zcu216TopFlat.scala` · **Package:** `riscq.soc`
· **Type:** abstract Component (`Zcu216Top`), Component (`Zcu216TopFlat`), Bundle (`RiscqZcu216SocPorts`)

The board-level scaffolding that wraps [`PulseTableSoc`](PulseTableSoc.md) for the ZCU216 RFSoC
(`xczu49dr`): the two clock domains and the external AXI / converter / DIO ports. The host-AXI address
map is derived from the build description — `SocSpecMap` in [`SocSpec`](SocSpec.md).
`Zcu216Top` is the abstract base `PulseTableSoc extends`; `Zcu216TopFlat` is a full board integration that
instantiates the Xilinx PS + RF Data Converter as blackboxes.

## `Zcu216Top` — the abstract base

Owns the two clock domains and the external port bundle; generates no hardware of its own (the SoC logic
lives in `PulseTableSoc`):

- **`hostCd`** — the implicit clock domain (the ~100 MHz host AXI clock).
- **`dspCd`** — the external `dspClk`/`dspRst` converter clock (~500 MHz) that runs the real-time datapath.
- **`io: RiscqZcu216SocPorts`** — the device-facing ports (below).

When `vivado` is set it renames the host clock/reset to `hostClk`/`hostRst` and tags both domains with
their target frequencies (`VivadoClkHelper.addInference`) so the block design's clock inference and CDC
analysis work. This is **off by default** so the single-clock OOC bench — which constrains the un-renamed
`clk` — is undisturbed. The rename is the only part that bites; the attributes are sim-neutral.

### `RiscqZcu216SocPorts`

The external ports: `dspClk`/`dspRst`, a slave `Axi4` host port (32-bit addr/data, 2-bit id), a
write-only `Axi4` `hostMem` master into the PS DDR4 ([HostWindow](HostWindow.md)), and per converter a
master `Stream` DAC (16-lane × 16-bit word) and a slave `Stream` ADC (4-lane × 16-bit). The converters are
free-running — DAC `valid` is tied high, ADC `ready` tied high. Under `vivado = true` the AXI and
AXI-Stream ports carry the `X_INTERFACE_INFO` attributes (`S_AXIS`, `M_AXI_HOST`, `DAC{i}_AXIS`,
`ADC{i}_AXIS`) the IP packager needs; on the default they are plain scalar ports the functional
sims/benches expect.

**Timed digital I/O.** The `dio: Seq[String]` constructor argument — `PulseTableSoc.dioNames(spec)`, one
`<core>_<channel>` entry per `dio` channel of the build — adds a port pair per bank:
`dio_<core>_<channel>_out` (16 bits, out) and `dio_<core>_<channel>_in` (16 bits, in), emitted as
`io_dio_<core>_<channel>_out` / `_in`. Builds with no `dio` channel get none. See
[`TimedDio`](TimedDio.md).

## `Zcu216TopFlat` — the flat board integration

A standalone, block-design-free ZCU216 top: it instantiates `PulseTableSoc` plus the **same** two Xilinx
IPs a block design would (the Zynq UltraScale+ PS and the RF Data Converter) as plain SpinalHDL
blackboxes, and wires everything in RTL.

- **Clocking.** The external LVDS `dspClk` comes in through an `IBUFGDS` + `BUFG` (the Xilinx
  clock-buffer blackboxes in `riscq.misc`); `user_sysref` through an `IBUFGDS`. The PS
  `pl_clk0` (~100 MHz) drives the host AXI fabric, the SoC host domain, and the RFDC `s_axi` control port;
  the buffered `dspClk` (~500 MHz) drives the SoC datapath and the RFDC sample-data AXIS.
- **Host AXI fan-out is a SpinalHDL crossbar.** The PS `HPM0_LPD` master fans to `{ SoC control, RFDC
  s_axi }` via an `Axi4CrossbarFactory`, **not** a Xilinx fabric IP (SmartConnect and AXI Interconnect
  v2.1 are IP-Integrator-only, and the classic v1.7 is not Tcl-configurable for a routed 1→2 here — see
  [`SOC_TIPS.md`](SOC_TIPS.md) §8.2). The PS master is 16-bit id while the SoC control port is 2-bit, so
  an `Axi4IdRemover` strips the PS id first (it serialises outstanding host transactions — fine for the
  low-rate control path).
- **Converter data.** SoC physical channel `i` maps to RFDC slice `(i/4, i%4)`; the analog `vin`/`vout`
  pins thread straight to top-level device ports. ZynqMP exposes no DDR/MIO fabric ports (dedicated PSU
  pins), so there is no `inout` passthrough.

The packaged *flat* build flow that drove `Zcu216TopFlat` was removed from `vivado-scripts/`; the generator
is retained for reference. The maintained flows are block-design-based (below).

```bash
mill runMain riscq.soc.GenZcu216TopFlat 14   # args: [qubitNum=14] [linkPipe=4]
```

## Feeding the Vivado flows

`PulseTableSoc` (not `Zcu216TopFlat`) is what the two maintained ZCU216 flows synthesise, each via one
`build-*.sh` that generates the RTL, runs Vivado, and writes reports under `build/<name>/`:

- **`vivado-scripts/riscvsoc/`** — the standalone OOC place&route bench: the real `PulseTableSoc`
  (`vivado = false`, plain `dspClk`/`clk` ports) floorplanned and routed in isolation.
- **`vivado-scripts/riscvsoc-bd/`** — packages `PulseTableSoc` (`vivado = true`, the IP-packager attrs)
  as a user IP in a block design with the Zynq PS + RF Data Converter + AXI SmartConnect, to measure the
  block-design / real-device penalty.

Both bake the two-region floorplan (cores in X0, datapath in X1–X5) via the per-flow `pblocks-*.tcl`. See
[`ARCH.md`](ARCH.md) for the floorplan rationale and **`../../vivado-scripts/README.md`** for the flows.

## Related

- [`PulseTableSoc`](PulseTableSoc.md) — the SoC this wraps.
- [`SocSpec`](SocSpec.md) — the build description and the derived host map (`SocSpecMap`).
- [`TimedDio`](TimedDio.md) — what sits behind the `dio` port pairs.
- [`ARCH.md`](ARCH.md) — the posted-link architecture + two-region floorplan.
- [`SOC_TIPS.md`](SOC_TIPS.md) §8 — hardware-build gotchas (PS/RFDC blackboxes, the IP OOC run).
