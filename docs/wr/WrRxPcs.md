# WrRxPcs — RX PCS: sync monitor, frame reassembly, timestamp trigger

**Source:** `src/riscq/wr/pcs/WrRxPcs.scala` · **Package:** `riscq.wr.pcs` · **Clock:** `clk_rx` ·
**Spec:** [02-pcs.md §3](../../specs/white-rabbit/02-pcs.md)

Decodes the aligned 20-bit raw words (comma at symbol 0 **by construction** — the dice-throw
aligner of spec 01 §5 guarantees it, so there is no shifter here), reassembles frames, and raises
the RX timestamp trigger at SOF. A reduced `ep_rx_pcs_16bit` + `ep_sync_detect`.

## Interface

| Signal | Dir | What |
|---|---|---|
| `io.rxDataRaw` | in `Bits(20 bits)` | aligned raw word from the PHY |
| `io.frame` | master `Stream(Fragment(Bits(8 bits)))` | delivered messages, TYPE..PAYLOAD (CRC stripped) — **delivery itself is the crc-ok flag** |
| `io.rxTrigger` | out `Bool` | pulses per SOF seen while synced — the t2/t4 event, fixed 2-cycle offset from the word on `rxDataRaw` |
| `io.synced`, `io.calDetected` | out `Bool` | sync-monitor state; consecutive-K28.7 cal detector (≥ 2^`calThreshLog2`) |
| `io.codeErrCnt` … `io.syncLossCnt` | out `UInt(16)` | saturating error/drop/loss counters (register file later, 06 §1) |

## How it works

- **Decode stage**: input register, two chained `Dec8b10b`, registered running disparity (popcount
  rule — re-converges after errors).
- **Sync monitor** (802.3 fig 36-9 reduced to one saturating counter): +1 per clean comma, −4 per
  code/disparity error, hold otherwise (frames are comma-less but clean); `synced` at ≥ 4. An error
  burst mid-frame drops sync and the idle gap re-acquires it in a few commas; each falling edge
  counts in `syncLossCnt`.
- **Frame reassembly** (HUNT/COLLECT): on SOF at symbol 0 while synced, collect bytes until EOF
  (either symbol position), tracking the CRC residue on the fly; finalize checks residue == 0 and
  length ≥ 3. Any mid-frame code/disparity error, unexpected K, missing EOF within any legal length,
  runt, bad CRC, or both buffers busy **drops the whole frame and counts it** — corrupt frames are
  refused, never delivered mangled.
- **2-slot ping-pong buffer**: collection writes 16-bit words into one slot while the previous
  frame drains from the other onto the 1 B/cycle output stream; a frame arriving while its target
  slot is still draining is dropped (the real exchange runs at ~Hz — spec D4 — so this never
  matters in operation).
- **Trigger offset**: input reg + trigger reg ⇒ `rxTrigger` fires exactly **2 cycles** after the
  SOF word is on `io.rxDataRaw` — the RX half of the spec 02 §5 latency constant, asserted by
  `WrPcsLoopSim` end-to-end (`rxTrig − txTrig = D + 2` against the serial model's word delay `D`).
  The trigger fires for every synced SOF, delivered or not: association with a message is by the
  one-outstanding-exchange software discipline (spec 04 §1, 07 §2), not by the trigger.

## Verification

`WrPcsLoopSim` (see [README](README.md)): bit-exact delivery under back-pressure, error injection →
exact refusal + counter movement + sync recovery, trigger-timing exactness, cal detect. The
back-pressure monitor uses `StreamReadyRandomizer`/`StreamMonitor` — see SOC_TIPS §3.8 for why a
hand-rolled ready-toggling thread must not be used.
