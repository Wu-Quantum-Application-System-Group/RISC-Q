# EnvelopeReader — complex-envelope memory front-end

**Source:** `src/riscq/dsp/pulse/EnvelopeReader.scala` · **Package:** `riscq.dsp.pulse`
· **Type:** Component

Reads the stored complex pulse envelope from an external RAM and presents it to the datapath as a batch
of `N` complex lanes (one DAC sample each). It is the complex analogue of the RISC-Q reference's real
`PulseMemReader`, and feeds the envelope `ComplexMul`s in [PulseGenerator](PulseGenerator.md). Number
format is SF(w) per lane (see [DSP.md](DSP.md)).

## Role / dataflow

```
io.addr (Flow) ─▶ addrReg (load / auto-increment) ─▶ io.memPort.cmd
                                                io.memPort.rsp ─▶ split into N lanes ─▶ RegNext ─▶ io.env
```

`io.memPort` is a `master MemReadPort` to a host-written envelope RAM (the RAM itself + its Tilelink
write path live at the SoC level — see [PulseTableSoc](../soc/PulseTableSoc.md)). `io.env` is a plain
`ComplexBatch(N, w)` consumed by the downstream envelope multiply.

## Design rationale

- **Free-running address.** `addrReg` loads from `io.addr` on a Flow fire and otherwise
  **auto-increments every cycle** (`+1`), exactly like the reference. The junk read between pulses is
  harmless — the toplevel's duration gate masks the result — so no explicit "stop" logic is needed. The
  command is **always valid** (the RAM is always read).
- **Word layout (a shared contract).** The memory word is `N` complex lanes × 2 × w bits. Lane `k`
  occupies bits `[(2k+2)·w−1 : 2k·w]`, with `re` in the lower half and `im` in the upper half — i.e.
  `subdivideIn(2N slices)` yields `[re0, im0, re1, im1, …]`. This layout is **shared with the SoC's
  Tilelink envelope writer** (the host fills the RAM in this order) and with the verification model;
  document changes here in lock-step with the writer.
- **Output register.** The lane split is registered (`RegNext`) so the slicing logic stays off the
  memory-response critical path.

## Latency

`latency = 1 (addr reg) + memLatency + 1 (output reg) = memLatency + 2`, **exported** so
[PulseGenerator](PulseGenerator.md)'s `addr`-queue lead time derives automatically. With the default
`memLatency = 2`, `latency = 4`.

## Interface & configuration

`EnvelopeReader(EnvelopeReaderParams(batchSize, dataWidth, addrWidth, memLatency))`. `memLatency` (≥ 1)
is the external memory's cmd→rsp cycle count and must match the real RAM read path; `wordWidth =
batchSize·2·dataWidth` is the memory word width. Ports: `io.addr` (`slave Flow(UInt(addrWidth))`),
`io.memPort` (`master MemReadPort(Bits(wordWidth), addrWidth)`), `io.env` (`out ComplexBatch(N, w)`).

## Usage / verification

```bash
mill runMain riscq.dsp.pulse.sim.EnvelopeReaderSim
```

`EnvelopeReaderSim` wraps the reader around a preloaded `Mem` with exactly `memLatency` cmd→rsp cycles
(each address holding a word with `re_0 = addr` plus distinct per-lane patterns), drives a
load/auto-increment schedule, and verifies against a Scala model of the address register: the measured
pipeline latency equals the exported `latency`, the address sequence auto-increments after a load, and
every lane's re/im matches the documented word layout.

## Related

- [PulseGenerator](PulseGenerator.md) · [ComplexMul](ComplexMul.md) · [DSP.md](DSP.md)
- SoC envelope RAM + write path: [PulseTableSoc](../soc/PulseTableSoc.md) · [ARCH.md](../soc/ARCH.md)
