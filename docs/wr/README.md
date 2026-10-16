# riscq.wr — White Rabbit two-board synchronization

**Package:** `riscq.wr` · **Source:** `src/riscq/wr/`

Hardware for aligning the `syncTime` batch clocks of two `PulseTableSoc` boards over a direct GTY
serial lane, following the White Rabbit model (WR paper §3) — no WR switch, no Ethernet stack.
Stage 1 (what is implemented) targets **cycle-level (2 ns) alignment**: a deterministic
constant-latency link carries marker frames whose SOF symbols trigger `refTime` captures on both
boards; host Python computes the two-way offset and writes the slave's `timeOffset`. Sub-cycle
correction (Stage 2: a DDMTD phase detector between dspClk and the GTY clocks, plus clock steering)
is not implemented. A stable correction assumes both boards' dspClk derive from a common RF
reference (syntonized `refTime`s); the software measures the drift slope and refuses to correct if
it is not ~0.

## Implemented modules

| Page | Module(s) | What |
|---|---|---|
| [Enc8b10b](Enc8b10b.md) | `Enc8b10b`, `Dec8b10b`, `WrSymbols` | 8b/10b codec pair + the symbol alphabet and bit-packing conventions |
| [WrTxPcs](WrTxPcs.md) | `WrTxPcs`, `Crc16` | message → framed 20-bit raw TX words; SOF timestamp trigger; cal pattern |
| [WrRxPcs](WrRxPcs.md) | `WrRxPcs` | aligned raw words → sync monitor, frame reassembly + CRC check, RX timestamp trigger |
| [RefTimeTsu](RefTimeTsu.md) | `RefTimeTsu` | PCS trigger → 64-bit `refTime` capture (matched 2-FF chains, seq/overrun) |
| [SyncMarker](SyncMarker.md) | `SyncMarker` | `syncTime`-compare scope pin (stretch, periodic re-arm, missed flag) |
| [GtySimPhy](GtySimPhy.md) | `WrPhyIo`, `GtySimPhy` | the phy contract + the serial-link sim model (ps delays, dice-throw) |
| [WrNode](WrNode.md) | `WrNode` | the memory-mapped WR peripheral: PCS + TSUs + marker + register file/CDC |
| [WrGtyPhy](WrGtyPhy.md) | `WrGtyPhy`, `GtyResetCtrl`, `BufferBypassCtrl`, `CommaAligner`, harvested `.v` wrappers | the real-GTY deterministic PHY (1.25 Gb/s, QPLL0, buffers bypassed, dice-throw alignment) |
| [WrSoftware](WrSoftware.md) | `PulseTableSoc(withWhiteRabbit)`, `software/riscq/wr.py` | the SoC attach (`wrPhy`/`wrMarker` ports, `map.wrBase` window) + the host exchange/measure/sync software |

Not yet documented here: the ZCU216 bitstream and the two-board hardware bring-up.

## Measurement model

The all-coarse two-way exchange of WR paper §3.5.3; every timestamp is a `refTime` capture in 2 ns
dspClk units:

```
master sends SYNC       → t1 = refTime_M at TX SOF ;  t2 = refTime_S at RX SOF
slave  sends DELAY_REQ  → t3 = refTime_S at TX SOF ;  t4 = refTime_M at RX SOF

delay_MM  = (t4 − t1) − (t3 − t2)          # round trip minus turnaround (paper eq 3.9)
offset_MS = ((t1 − t2) + (t4 − t3)) / 2    # symmetric-link identity, ≡ refTime_M − refTime_S
```

Sign convention: `offset_MS = refTime_M − refTime_S` (positive ⇒ slave behind ⇒ the slave *adds*
the correction). Since `syncTime = refTime + timeOffset`, the correction written is
`timeOffset_S += round(offset_MS + timeOffset_M − timeOffset_S)`. Each capture carries ±1 dspClk
cycle of quantization; because dspClk and the GTY link clocks are unrelated, the trigger phase
slides across the 2 ns window between exchanges, so **averaging N exchanges dithers the
quantization away** (~2 ns/√N; N ≈ 100 → ~0.2 ns). Constant asymmetries cancel between identical
boards to first order. The four timestamps are read by the host over MMIO — frames are only
timestamped events (type + sequence number), one outstanding exchange at a time.

## Verification

```bash
mill runMain riscq.wr.pcs.sim.Enc8b10bSim    # exhaustive codec golden (enc + dec + stream properties)
mill runMain riscq.wr.pcs.sim.WrPcsLoopSim   # TX→serial-bit model→RX loop: soup, errors, exact trigger timing
mill runMain riscq.wr.time.sim.RefTimeTsuSim # ps-grid phase sweep + matched-chain + 4-TSU exchange golden
mill runMain riscq.wr.time.sim.SyncMarkerSim # cycle-exact marker rise/fall, periodic, missed/disarm
mill runMain riscq.wr.gty.sim.GtySimPhySim   # link-model self-test: bit-exact, constant latency, dice stats
mill runMain riscq.wr.sim.WrTwoNodeSim       # sign-off: two nodes, register-driven exchange, ±0.5 ns
mill runMain riscq.wr.sim.WrNodeSim          # SoC attach over the real AXI path (torn-read, marker)

cd software
python3 -m pytest tests/test_wr.py           # wr.py math + golden parity on the two-node trace
python3 -m pytest tests/test_wr.py --cosim   # + the sim-wr verilator cosim smoke (self-loopback)
```

**Design-level status: the two-node sign-off and the SoC/software gates are green** — two-node:
mean `offset_MS` recovered to ~0.03 cycle (±0.25 gate), post-correction marker skew ~0.4 ns
(1-cycle gate), injected ppm slope recovered to 0.3%, asymmetry sign check exact (see
[WrNode](WrNode.md)); SoC/software: the full AXI-path register smoke incl. the 64-bit torn-read
latch, `wr.py` op-for-op parity with the two-node golden trace, and the verilator cosim
self-loopback smoke (see [WrSoftware](WrSoftware.md)). Hardware bring-up on the ZCU216 is the
next step.

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
  `WrRxPcs` input+trigger register constant) asserted constant — the latency regression below;
  cal-pattern detect/release; oversize messages refused without transmit.

## Latency constants

The link's timestamp usefulness rests on **build-time-constant** trigger↔serial latency. The PCS
contributions, asserted by `WrPcsLoopSim` against the serial model:

| Path | Constant | Where |
|---|---|---|
| TX: `txTrigger` → SOF word on `txDataRaw` | **0 cycles** (both registered once, same edge) | `WrTxPcs` |
| RX: SOF word on `rxDataRaw` → `rxTrigger` | **2 cycles** (input reg + trigger reg) | `WrRxPcs` |
| TSU: trigger rise → captured `ts` value | **+2 dspClk cycles** (the two sync FFs; ±1 phase quantization) | `RefTimeTsu` |

Any re-pipelining of these paths moves the asserted constant and fails the sim — update the
constants here deliberately, never silently.
