# WrNode — the memory-mapped White Rabbit peripheral

**Source:** `src/riscq/wr/WrNode.scala` · **Package:** `riscq.wr` ·
**Spec:** [06-node-soc.md](../../specs/white-rabbit/06-node-soc.md)

Ties the PCS pair — since the put network's R4 wrapped as a [PutLane](../soc/PutLane.md), which
adds the hub's put frames beside the host's marker frames and exposes `putTx` / `putRx` to the SoC —
([WrTxPcs](WrTxPcs.md)/[WrRxPcs](WrRxPcs.md)), the timestamping
([RefTimeTsu](RefTimeTsu.md) ×2), and the [SyncMarker](SyncMarker.md) into **one
`MemMapDriverFiber` register window** for the host — the `hostCtrlDriver` idiom. All sequencing
is host software (spec 07); the node is pure dataplane + registers. Construction mirrors
`BramFiber`: instantiate in the host clock domain, pass `dspCd` and the [WrPhyIo](GtySimPhy.md)
bundle explicitly; `refTime`/`syncTime` are dspClk inputs from `riscqArea` — the node never owns
or modifies them, and `timeOffset` stays in the existing host control block.

## Register map (window `up`, 32-bit regs — see the `WrNode.scala` header for the field layout)

| Offset | Reg | CDC class |
|---|---|---|
| 0x000 | CTRL: resetAll / resetRxDatapath / calMode / role / pmaLoopback[4] | host regs; levels to phy + `BufferCC` to `clk_ref` (pmaLoopback → the GTY `LOOPBACK` port in the vivado build; no-op in sims) |
| 0x004 | STATUS: ready, aligned, synced, diceCount | `BufferCC` levels (quasi-static) |
| 0x010/0x020 | TX/RX timestamp: LO (latches HI), HI, CTRL {valid, overrun, seq} + ack | quasi-static-while-valid capture, `BufferCC`; ack = toggle-CC pulse |
| 0x030–0x03c | markerTime lo/hi, CTRL {width + arm / armed, missed}, period | value regs + toggle-CC arm strobe |
| 0x040/0x050 | TX / RX message FIFOs (byte+last; pop-on-read), occupancies | `StreamFifoCC` (host↔`clk_ref`, `clk_rx`→host) |
| 0x060–0x070 | codeErr, dispErr, crcErr, dropped, syncLoss | `BufferCC`, torn — software reads twice |

Key contracts baked into the CDC choices:

- **Timestamps**: `ts`/`seq` are stable while `valid` (the one-outstanding-exchange discipline),
  so a plain full-width `BufferCC` is coherent whenever the host sees `valid`; the LO-read
  latches HI for torn-read safety of the 64-bit value; a write to the CTRL offset acks
  (toggle-CC pulse into dspClk).
- **`clk_ref`/`clk_rx` are reset-less** (BOOT-init registers): the PCS is self-recovering via
  the sync monitor, so no reset needs to cross into the link domains; the FIFOs run with
  `withPopBufferedReset = false` accordingly.
- The PCS trigger pulses cross into dspClk **inside `RefTimeTsu` and nowhere else** (spec 04 §3).

## SoC attach — `PulseTableSoc(withWhiteRabbit = true)` (W5)

The node joins `PulseTableSoc` behind the `withWhiteRabbit` parameter (default off — the
14-qubit builds are untouched), exactly as the host control block does:

- the register window maps at **`map.wrBase = 6 * regionSize`** of `hostBus` (mirrored by
  `SocMap.wr_base` in `software/riscq/map.py`; JSON configs opt in with
  `"with_white_rabbit": true` through `GenPulseTableSocJson`);
- `refTime`/`syncTime` come straight from `riscqArea`; `timeOffset` stays in the host control
  block;
- **sim builds** (`vivado = false`) expose the phy contract as the `wrPhy` toplevel port (a
  `slave(WrPhyIo())` driven by the [GtySimPhy](GtySimPhy.md) model); **the vivado build**
  instantiates the real [WrGtyPhy](WrGtyPhy.md) instead and exposes the GTY board pins
  `wrRefClkP/N` / `wrRxP/N` / `wrTxP/N`, with `clk_free` = hostClk/2 through a BUFG (the
  `wrClkFreeDiv` divider, PG182's ≤ 62.5 MHz bypass rule) and CTRL[4] driving the GT's
  near-end PMA loopback (the W6 self-test);
- the marker is observable two ways: the `wrMarker` digital pin, and — when `wrMarkerDac`
  (JSON `"wr_marker_dac"`) names a spare, unmapped DAC — a **full-scale step on that
  converter** (the scope observable of specs 09; one `RegNext` + the shared RFDC-edge stage,
  a constant board-identical delay).

## Verification

**`WrNodeSim` (the W5 map/CDC gate)** — `mill runMain riscq.wr.sim.WrNodeSim`: a single
`PulseTableSoc(withWhiteRabbit = true)` with the `GtySimPhy` serial **self-loopback**, every
register access through the real host path (AXI → `Axi4ToTilelinkFiber` → hostBus → the window).
Gates: power-up `resetAll` + release/readback smoke; link-up through the modeled dice-throw;
both TSUs capture a self-looped frame with the body round-tripping through RXF and constant
TX→RX latency; the **64-bit latch torn-read** (with `refTime` forced across the 2^32 boundary, a
HI read after a new capture still returns the previous exchange's latched word until a LO read
re-latches); the marker arms over the bus, fires `wrMarker`, and steps the spare-DAC route
(`wrMarkerDac = 4`) full-scale for exactly the marker window; error counters stay zero.

## `WrTwoNodeSim` (the W3 design-level sign-off)

`mill runMain riscq.wr.sim.WrTwoNodeSim` — two complete `WrNode`s cross-wired through the
[GtySimPhy](GtySimPhy.md) link, each with an independent free-running 500 MHz `refTime`
(64-bit-straddling initial values, private phases **incommensurate with the link clocks** — the
capture-dither prerequisite, spec README §3/risk 4) and the SoC-shaped
`syncTime = RegNext(refTime + BufferCC(timeOffset))`. A Scala host drives both register maps over
real Tilelink (`MasterAgent`) running the spec-07 exchange (SYNC/DELAY_REQ frames, seq +
overrun + frame-body checks, latched 64-bit reads, acks):

- **nominal** (symmetric 40 ns link, syntonized): mean `offset_MS` over 40 exchanges within
  ±0.25 cycle (**±0.5 ns**) of the injected refTime difference (measured ~0.03 cycle); the
  modeled `timeOffset` correction is then written and both markers armed at the same `syncTime`
  → pins rise within 1 dspClk cycle (measured ~0.4 ns);
- **ppm**: dspClk_B stretched 1999→2003 ps — the least-squares fit recovers the injected drift
  slope within 10% (measured 0.3%);
- **asym**: +32 ns of B→A delay shifts the measured offset by exactly `(d_BA − d_AB)/2` — the
  end-to-end pin of the spec README §3 sign convention.

The nominal run's register-level trace lands in `simWorkspace/wr_two_node_trace.jsonl` (with the
final mean/slope/corr decision record) — the committed copy at
`software/tests/data/wr_two_node_trace.jsonl` is the W5 Python golden-parity input
(spec 07 §6, [WrSoftware](WrSoftware.md)).
