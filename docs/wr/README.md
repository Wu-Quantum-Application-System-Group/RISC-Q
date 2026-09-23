# riscq.wr — White Rabbit two-board synchronization

**Package:** `riscq.wr` · **Source:** `src/riscq/wr/` · **Design:** [specs/white-rabbit/](../../specs/white-rabbit/README.md)

Hardware for aligning the `syncTime` batch clocks of two `PulseTableSoc` boards over a direct GTY
serial lane, following the White Rabbit model (WR paper §3) — no WR switch, no Ethernet stack. The
staged plan, measurement model, and accuracy budget live in the spec; this directory documents the
**implemented modules**. Stage 1 targets **cycle-level (2 ns) alignment**: a deterministic
constant-latency link carries marker frames whose SOF symbols trigger `refTime` captures on both
boards; host Python computes the two-way offset and writes the slave's `timeOffset`.

## Implemented modules

| Page | Module(s) | Milestone | What |
|---|---|---|---|
| [Enc8b10b](Enc8b10b.md) | `Enc8b10b`, `Dec8b10b`, `WrSymbols` | W1 | 8b/10b codec pair + the symbol alphabet and bit-packing conventions |
| [WrTxPcs](WrTxPcs.md) | `WrTxPcs`, `Crc16` | W1 | message → framed 20-bit raw TX words; SOF timestamp trigger; cal pattern |
| [WrRxPcs](WrRxPcs.md) | `WrRxPcs` | W1 | aligned raw words → sync monitor, frame reassembly + CRC check, RX timestamp trigger |
| [RefTimeTsu](RefTimeTsu.md) | `RefTimeTsu` | W2 | PCS trigger → 64-bit `refTime` capture (matched 2-FF chains, seq/overrun) |
| [SyncMarker](SyncMarker.md) | `SyncMarker` | W2 | `syncTime`-compare scope pin (stretch, periodic re-arm, missed flag) |
| [GtySimPhy](GtySimPhy.md) | `WrPhyIo`, `GtySimPhy` | W3 | the phy contract + the serial-link sim model (ps delays, dice-throw) |
| [WrNode](WrNode.md) | `WrNode` | W3 | the memory-mapped WR peripheral: PCS + TSUs + marker + register file/CDC |
| [WrGtyPhy](WrGtyPhy.md) | `WrGtyPhy`, `GtyResetCtrl`, `BufferBypassCtrl`, `CommaAligner`, harvested `.v` wrappers | W4 | the real-GTY deterministic PHY (1.25 Gb/s, QPLL0, buffers bypassed, dice-throw alignment) |
| [WrSoftware](WrSoftware.md) | `PulseTableSoc(withWhiteRabbit)`, `software/riscq/wr.py` | W5 | the SoC attach (`wrPhy`/`wrMarker` ports, `map.wrBase` window) + the host exchange/measure/sync software |

Later milestones (W6/W7: the ZCU216 bitstream and two-board bring-up) add their pages here as
they land — the module map is spec [README §6](../../specs/white-rabbit/README.md#6-module-map).

## Verification

```bash
mill runMain riscq.wr.pcs.sim.Enc8b10bSim    # exhaustive codec golden (enc + dec + stream properties)
mill runMain riscq.wr.pcs.sim.WrPcsLoopSim   # TX→serial-bit model→RX loop: soup, errors, exact trigger timing
mill runMain riscq.wr.time.sim.RefTimeTsuSim # ps-grid phase sweep + matched-chain + 4-TSU exchange golden
mill runMain riscq.wr.time.sim.SyncMarkerSim # cycle-exact marker rise/fall, periodic, missed/disarm
mill runMain riscq.wr.gty.sim.GtySimPhySim   # link-model self-test: bit-exact, constant latency, dice stats
mill runMain riscq.wr.sim.WrTwoNodeSim       # W3 SIGN-OFF: two nodes, register-driven exchange, ±0.5 ns
mill runMain riscq.wr.sim.WrNodeSim          # W5: SoC attach over the real AXI path (torn-read, marker)

cd software
python3 -m pytest tests/test_wr.py           # wr.py math + golden parity on the W3 trace
python3 -m pytest tests/test_wr.py --cosim   # + the sim-wr verilator cosim smoke (self-loopback)
```

**Design-level status: the W3 two-node sign-off and the W5 SoC/software gates are green** — W3:
mean `offset_MS` recovered to ~0.03 cycle (±0.25 gate), post-correction marker skew ~0.4 ns
(1-cycle gate), injected ppm slope recovered to 0.3%, asymmetry sign check exact (see
[WrNode](WrNode.md)); W5: the full AXI-path register smoke incl. the 64-bit torn-read latch,
`wr.py` op-for-op parity with the W3 golden trace, and the verilator cosim self-loopback smoke
(see [WrSoftware](WrSoftware.md)). Next: W6 ZCU216 bitstream + single-board bring-up.

- **`Enc8b10bSim`** — exhaustive encode (all 256 data + 12 K codes × both disparities) against an
  **independent golden transcribed from wr-cores `gc_enc_8b10b.vhd`**; exhaustive decode
  classification of all 1024 codes × both disparities (`data`/`isK`/`codeErr`/`dispErr`/`rdOut`);
  encode→decode round trip; run-length ≤ 5 and DC-balance over a 400k-bit random symbol stream on
  the chained two-symbol pair.
- **`WrPcsLoopSim`** — `WrTxPcs` → a serial bit-queue model (line delay + random word-alignment
  phase, the pre-`GtySimPhy` stand-in) → `WrRxPcs`, TX and RX on separate clock domains, three line
  delays. Gates: random message soup delivered exactly once, in order, bit-exact under random RX
  back-pressure; injected single-bit line errors drop exactly the hit frame, counters move, sync
  recovers; **trigger timing exact** — TX trigger coincides with the SOF word on `txDataRaw`, and
  `rxTrig − txTrig = D + 2` for every frame (`D` = the model's word delay, `+2` = the documented
  `WrRxPcs` input+trigger register constant) asserted constant, the spec 02 §5 latency regression;
  cal-pattern detect/release; oversize messages refused without transmit.

## Latency constants (spec 02 §5)

The link's timestamp usefulness rests on **build-time-constant** trigger↔serial latency. The PCS
contributions, asserted by `WrPcsLoopSim` against the serial model:

| Path | Constant | Where |
|---|---|---|
| TX: `txTrigger` → SOF word on `txDataRaw` | **0 cycles** (both registered once, same edge) | `WrTxPcs` |
| RX: SOF word on `rxDataRaw` → `rxTrigger` | **2 cycles** (input reg + trigger reg) | `WrRxPcs` |
| TSU: trigger rise → captured `ts` value | **+2 dspClk cycles** (the two sync FFs; ±1 phase quantization) | `RefTimeTsu` |

Any re-pipelining of these paths moves the asserted constant and fails the sim — update the
constants here and in spec 02 §5 deliberately, never silently.
