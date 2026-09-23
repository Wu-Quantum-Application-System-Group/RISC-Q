# SocSpec — the one description of a build

**Source:** `src/riscq/soc/spec/SocSpec.scala` · **Package:** `riscq.soc.spec` · **Type:** case classes
(`ChannelSpec`, `CoreSpec`, `SocSpec`, `SocSpecMap`) + the `PrintSocMap` app · **Python twin:**
`software/riscq/spec.py` (`riscq.spec.SocSpec`) and `riscq.map.SocMap`

The build description both sides read from the same JSON under `software/configs/`: a list of cores,
each with its list of channels. The elaboration (`GenPulseTableSocJson` → [`PulseTableSoc`](PulseTableSoc.md)),
the python address map and the generated `riscq_map.h` all derive from it, so no address appears literally
anywhere else, and **a core's hardware is its channel list** — the shell builds one block per entry
(specs/universal-control/01 §2.1–2.2).

## The JSON

```json
{ "name": "sim-dio", "dsp_freq_hz": 5.0e8, "dac_num": 16, "adc_num": 16, "link_pipe": 4,
  "hostwin_bits": 24, "rob_depth": 1024, "adc_pipe": 3,
  "with_white_rabbit": false, "wr_marker_dac": null, "board": 0, "boards": 1,
  "core_defaults": {"mem_depth": 4096, "with_mul": true, "queue_depth": 4},
  "cores": [
    {"name": "q0", "role": "qubit", "channels": [
      {"name": "gate",  "kind": "pulse", "dac": 0,  "slots": 8, "env_depth": 1024, "interp": 4},
      {"name": "ro",    "kind": "pulse", "dac": 14, "slots": 1, "env_depth": 1024, "interp": 16, "trace": true},
      {"name": "demod", "kind": "demod", "adc": 0,  "slots": 1, "env_depth": 1024, "interp": 4},
      {"name": "ttl",   "kind": "dio",              "slots": 8, "env_depth": 0,    "interp": 1}]}
  ]}
```

- `kind` picks the RTL block, its register layout and its lane count:

  | `kind` | Block | Lanes | Converter | Envelope bank | Reports |
  |---|---|---|---|---|---|
  | `pulse` | [`PulseDriveChannel`](RfChannels.md) | 16 (`batch_size`) | `dac` (required) | yes | — |
  | `demod` | [`DemodChannel`](RfChannels.md) + the decoder | 4 (`adc_batch`) | `adc` (required) | yes | `result` sink |
  | `dio` | [`TimedDio`](TimedDio.md) | 0 | none (board ports) | **none** (`env_depth` 0) | `fifo` sink |

  A `pulse` channel names a `dac` and a `demod` channel an `adc`; a `dio` channel names neither, and
  `env_depth` is 0 **exactly** for `dio` (both are `require`d). `slots` is the channel's table depth
  (pulses, or `{mask, value, dur}` DIO entries). Several `pulse` channels on one `dac` are summed.
  `trace` marks the channels whose fires trigger the shared readout trace (`robs`). Exactly one `demod`
  channel per core (one decoder, one ADC).
- The architecture-fixed constants (`batch_size`, `adc_batch`, `data_width`, the decoder bounds) may be
  stated but are asserted, never plumbed.
- **Legacy form.** The `SocParams` files (`qubit_num` + `gate_pulse_num`, `gate_interp`, `readout_interp`,
  `demod_interp`, `dac_map`, `adc_map`) are converted on load by `SocSpec.fromLegacy` (python
  `from_legacy`): every core gets `gate` / `ro` / `demod`, converters from `dac_map`/`adc_map` or the
  generic ZCU216 `SocChannelMap` layout. The qubit configs still use it; `SocSpec.qubits` is the same
  conversion in Scala, which is what `PulseTableSoc`'s legacy `(qubitNum, dacMap, adcMap, …)` `apply`
  calls.

`with_white_rabbit` / `wr_marker_dac` add the White Rabbit node ([WrNode](../wr/WrNode.md)); `board` /
`boards` place the build in a multi-board system (board 0 is the barrier root; `boards > 1` needs the
WR lane — [PutHub](PutHub.md), [PutLane](PutLane.md)). All four default to a single plain board.

## `SocSpecMap` — the derived host map

Region 0 = the core RAMs; region `1+j` = every core's `j`-th channel envelope RAM, strided by the widest
such bank; then the shared readout trace and the host control block. Every region is `regionSize =
pow2ceil(max stride · cores)` wide. A slot no core has a bank in (a `dio`-only slot) keeps its region but
is a 1-byte-stride **hole** — and `PulseTableSoc` gives it no region bus at all, since a fabric `Node`
needs a slave. Per core, channel `k` owns RF sub-window `0x10000 + k·0x10000` — node `k` of the core's **put window**
(`putAddrWidth = 16 + nodeBits = 28`; nodes `0 until localNodes = 16` are the core's own channels, every
node from 16 up is a system unit for the board hub — [specs/cross-core/02](../../specs/cross-core/02-put-network.md) §3.2).

**Event sinks.** A core's reporting channels get one sink each, in channel order, at
`0x4200 + 0x20·k` in its control block — `demod` → a `result` sink (`res`/`real`/`imag`), `dio` → a
`fifo` sink; the Scala twin is `EventPlan` ([EventLink](EventLink.md)), the python one
`SocMap.sinks(core)`, which emits `RQ_SINK_<NAME>` into `riscq_map.h`.

`software/tests/test_spec_scala.py` diffs `PrintSocMap`'s JSON (entries, per-core channel tables,
`put_addr_width`, `dac_pipe`) against `riscq.map.SocMap` for all **8** configs — the five qubit builds plus
`sim-mm`, `x6y3-multimode` and `sim-dio`.

```bash
mill runMain riscq.soc.spec.PrintSocMap software/configs/sim-2q.json
```

## Status

P0–P5 of the channel-list refactor are done: the description and the derived maps exist on both sides
(P0), [`PulseTableSoc`](PulseTableSoc.md) and [`RiscqRfWithPulseTableFiber`](RiscqRfWithPulseTableFiber.md)
build every core from its channel list (P1), the python stack works over the channel-list map (P2), the
RF window follows the channel count and multi-channel cores are gated in co-sim (P3), the up-link is the
tagged [`EventLink`](EventLink.md) (P4), and [`TimedDio`](TimedDio.md) is the first non-pulse kind (P5).
The qubit builds' addresses and netlist are unchanged throughout.

## Related

- [PulseTableSoc](PulseTableSoc.md) (the host regions) · [RiscqRfWithPulseTableFiber](RiscqRfWithPulseTableFiber.md)
  (the per-core channels) · [PutLink](PutLink.md) (the demux) · [Zcu216Top](Zcu216Top.md) (the board ports)
- Channel kinds: [RfChannels](RfChannels.md) · [TimedDio](TimedDio.md); sinks: [EventLink](EventLink.md)
- specs/universal-control/01 — the refactor plan
