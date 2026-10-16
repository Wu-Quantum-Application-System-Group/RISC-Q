# riscq.misc — Xilinx primitives & Vivado IP-packaging helpers

**Package:** `riscq.misc` · **Sources:** `src/riscq/misc/{BUFG,IBUFGDS,Axi4Helper,VivadoHelper,GtClockBuffers}.scala`

Four thin wrappers that exist purely to get the SoC into a **Vivado block design on the ZCU216**. None
of this is hardware the design *computes* with — it is the clock-buffer primitives the board's LVDS
clocks need, plus the `X_INTERFACE_INFO` / `FREQ_HZ` attributes that let Vivado's IP packager recognise
SpinalHDL-emitted scalar ports as AXI / AXI-Stream / clock **bus interfaces** so the block design can
auto-connect them. All of it is ported from the RISC-Q reference. The one addition,
`GtClockBuffers`, holds the GT clock primitives of the White Rabbit PHY (see below).

These four are used by the board toplevel ([../soc/Zcu216Top.md](../soc/Zcu216Top.md) and
`Zcu216TopFlat`) and gated by the `vivado` flag — see "When `vivado = true`" below.

## `BUFG` — global clock buffer

`src/riscq/misc/BUFG.scala` — a one-line `BlackBox` for the Xilinx `BUFG` primitive (`I → O`). A global
clock buffer drives a clock onto a low-skew global routing spine. The board toplevel puts one on the
~500 MHz `dspClk` after the input buffer, because the DSP datapath fans that clock out widely and so it
earns a global buffer; the slower `hostClk` / `user_sysref` do not get one.

## `IBUFGDS` — differential clock input buffer

`src/riscq/misc/IBUFGDS.scala` — a one-line `BlackBox` for the Xilinx `IBUFGDS` primitive (`I`, `IB` →
`O`). The ZCU216 delivers its reference clocks as **LVDS differential pairs** (`*_clk_p` / `*_clk_n`);
`IBUFGDS` converts each pair to a single-ended clock at the device edge. Every external clock on the
board (the 500 MHz `dspClk`, the 100 MHz `hostClk`, and `user_sysref`) comes in through one.

`Zcu216TopFlat` instantiates `IBUFGDS` + `BUFG` directly; the block-design path bundles the same logic
into the `ClockInterface` component below so the IP packager can auto-connect the `diff_clock` pairs.

## `GtClockBuffers` — GT clock primitives

`src/riscq/misc/GtClockBuffers.scala` — `BlackBox`es for the Xilinx `IBUFDS_GTE4` (GT reference-clock
input buffer, `MGTREFCLK` pair → QPLL refclk) and `BUFG_GT` (GT-sourced global clock buffer;
`driveDefaults()` wires it all-enabled, divide-by-one). They are not IP-packaging glue: only the White
Rabbit GTY PHY ([../wr/WrGtyPhy.md](../wr/WrGtyPhy.md)) uses them, for its reference clock and usrclk
networks.

## `Axi4Helper` — AXI / AXI-Stream interface taggers

`src/riscq/misc/Axi4Helper.scala` — `Axi4VivadoHelper.addInference` (plus its write-only twin
`Axi4WriteOnlyVivadoHelper.addInference`) and `Axi4StreamVivadoHelper.addStreamInference` tag a
SpinalHDL `Axi4` / `Stream` bundle's scalar ports with the `X_INTERFACE_INFO` attributes (`aximm` /
`axis`) that make Vivado's IP packager treat the loose ports as one named bus. Without them the
packager sees dozens of unrelated scalars and cannot auto-wire the block design. The board toplevel
tags its host AXI slave, the host-window write-only master (`M_AXI_HOST`), and each DAC/ADC AXI-Stream
port (e.g. `DAC0_AXIS`, `ADC0_AXIS`) so the RF Data Converter and AXI SmartConnect connect by name.

## `VivadoHelper` — clock/reset taggers + the `ClockInterface` wrapper

`src/riscq/misc/VivadoHelper.scala` holds the rest of the IP-packaging glue:

- **`VivadoClkHelper.addInference(clk, rst, freq)`** tags a clock/reset pair with the `clock` / `reset`
  interface attributes plus the parameters the packager needs: `FREQ_HZ`, `ASSOCIATED_RESET`, and
  `POLARITY ACTIVE_HIGH`. The toplevel tags its 100 MHz host domain and 500 MHz DSP domain with their
  real frequencies so the block design clocks the IP correctly.
- **`ClockInterface`** — a small `Component` that packages the three board LVDS clock pairs
  (`dspClk`, `hostClk`, `user_sysref`) each through an `IBUFGDS` (and a `BUFG` on `dspClk`) and carries
  the `diff_clock` / `clock` interface attributes, so the block design auto-bundles the `*_clk_p` /
  `*_clk_n` pairs. The block-design flow emits it as a separate `ClockInterface.v`
  next to the SoC RTL (`GenPulseTableSocJson <config> <dir> vivado`; `ClockInterfaceGen` emits it
  standalone). It is the IP-packager-friendly equivalent of the inline buffers in
  `Zcu216TopFlat`.

## When `vivado = true`

Adding `X_INTERFACE_INFO` attributes is **sim-neutral** (they are pure Verilog attributes), but all of
this is gated behind `PulseTableSoc(vivado = true)` anyway. The reason is the OOC fmax flow
(`vivado-scripts/riscvsoc`, RTL from `GenPulseTableSocOoc`) constrains
`[get_ports clk]` and so needs the **un-renamed default** ports — the `vivado = false` form keeps
plain `dspClk` / `clk` ports and no separate `ClockInterface.v`. The `vivado = true` form, which the
block-design flow uses, applies the attributes and the renamed clock structure the IP packager wants.

## Related

- [../soc/Zcu216Top.md](../soc/Zcu216Top.md) — the board toplevel that wires all of these.
- `vivado-scripts/README.md` — the ZCU216 flows (`vivado = false` OOC bench vs `vivado = true` block design).
- [../bench/VivadoBench.md](../bench/VivadoBench.md) — the OOC synth bench, which likewise constrains the default `clk` port.
