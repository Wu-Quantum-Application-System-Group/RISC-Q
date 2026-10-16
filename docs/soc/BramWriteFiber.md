# BramWriteFiber — write-only clock-crossing BRAM bank as a fabric slave

**Source:** `src/riscq/soc/fabric/BramWriteFiber.scala` · **Package:** `riscq.soc.fabric` · **Type:** fabric fiber (`Area`)

## Purpose

The **write-only** sibling of [`BramFiber`](BramFiber.md): the same bank of `num` clock-crossing
[`Bram`](../memory/Bram.md) blackboxes with each `fastPort` left for the caller's datapath, but the
host side (`slowPort`) is exposed through the write-only [`TileLinkMemWriteFiber`](TileLinkMemFiber.md)
instead of the read/write `TileLinkMemReadWriteFiber`. Same `up` / `rams` handles, so swapping
`BramFiber → BramWriteFiber` at a call site is a one-word change.

Use it for memories the host only ever **loads** and never reads back.

## Role in the system

Backs the per-channel **pulse-envelope memories** in
[`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md) — one bank per channel that has an
envelope, named `<channel>MemFiber` (`gateMemFiber`, `roMemFiber` and `demodMemFiber` on a qubit core;
a `dio` channel has no bank). The host writes complex envelope
lines over the fabric at `hostCd`; the pulse generators read them at `dspCd`. The host never reads an
envelope back, so a read port would be pure overhead — hence the write-only slave. (The `robs`
readout-trace buffers, which the host *does* read back, stay on the read/write [`BramFiber`](BramFiber.md).)

```
hostCd  ── up : Node ──► TileLinkMemWriteFiber ──► Flow[MemWriteCmd] ──► ram(i).slowPort (write-only)
                                                                          ║ Bram (BramBlackBox.v) ║
                                                            ram(i).fastPort ──► envelope reader (dspCd)
```

## Design rationale — two resources saved vs `BramFiber`

- **No read-reorder buffer.** [`TileLinkMemReadWriteLogic`](TileLinkMemFiber.md) carries a 4-entry
  rotating read-response buffer + elastic pipeline solely to cover d-channel back-pressure on *reads*.
  Write-only, that collapses to the tiny combinational [`TileLinkMemWriteLogic`](TileLinkMemFiber.md)
  (one `.stage()` on the ack).
- **No fabric `WidthAdapter`.** `TileLinkMemWriteLogic` bridges a narrow (32-bit) host bus into a wider
  RAM word by sub-word byte addressing — the low address bits pick the lane, `addressShifted` picks the
  word — so a wide bank (e.g. the 128-bit gate line) wires **direct** to the narrow 32-bit host region
  bus, each 4-byte host beat writing one lane. The read/write sibling instead forces bus word = RAM word,
  so the wide banks needed an upsizing adapter per core. Equal-width banks (readout / demod at 32-bit)
  degenerate to a single lane and work unchanged.

The host byte-address → (word, lane) mapping is identical to the old `WidthAdapter` + read/write path, so
software that loads envelopes is unchanged.

- **Same bank layout as the sibling.** `step = 1 << log2Up(width*depth/8)`; each RAM's `slowPort` is
  driven write-only from its fiber's `Flow[MemWriteCmd]` (`enable := valid`, `write := True`, address /
  wdata / mask forwarded; `rdata` ignored). `outReg = withOutReg` still selects the device-side read
  latency (1 vs 2) to match the datapath's `memLatency`.

## Interface & configuration

- `num`, `width`, `depth` — bank count and per-RAM geometry.
- `hostCd` — host (`slowPort`) side **and** fabric clock domain.
- `dspCd` — device (`fastPort`) side clock domain.
- `withOutReg` (default true) — device-side output-register / read-latency select.

Exposes `up` (slave node) and `rams` (for the caller to reach each `fastPort`).

## Verification

The backing blackbox is signed off by [BramSim](../memory/Bram.md); the write-only host path is exercised
end-to-end through the assembled SoC by [PulseTableSocSim](PulseTableSoc.md) (loads the gate envelope at
sub-word ratio 4 and the demod envelope at ratio 1 over AXI, then checks the DAC pulse and the
ADC-tone readout) and [PulseTableSocCpuSim](PulseTableSoc.md) (CPU-in-the-loop gate-envelope load → DAC).

```bash
mill runMain riscq.soc.sim.PulseTableSocSim
mill runMain riscq.soc.sim.PulseTableSocCpuSim
```

## Related

[BramFiber](BramFiber.md) (the read/write sibling) · [TileLinkMemFiber](TileLinkMemFiber.md)
(`TileLinkMemWriteFiber` / `TileLinkMemWriteLogic`) · [Bram](../memory/Bram.md) ·
[RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md) · [PulseTableSoc](PulseTableSoc.md)
