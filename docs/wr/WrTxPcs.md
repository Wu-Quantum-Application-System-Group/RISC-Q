# WrTxPcs — TX PCS: messages → framed raw words + timestamp trigger

**Source:** `src/riscq/wr/pcs/WrTxPcs.scala` (+ `Crc16.scala`) · **Package:** `riscq.wr.pcs` ·
**Clock:** `clk_ref` · **Spec:** [02-pcs.md §2/§4](../../specs/white-rabbit/02-pcs.md)

Turns host messages into 8b/10b frames on the 20-bit raw GTY pipe, one word (2 symbols) per cycle —
a stripped-down `ep_tx_pcs_16bit` (no autoneg, no carrier extend). Wire format:

```
/I/../I/  SOF(K27.7)  msg[0..N)  CRC16(hi,lo)  EOF(K29.7)  [fill]  /I/..
```

## Interface

| Signal | Dir | What |
|---|---|---|
| `io.frame` | slave `Stream(Fragment(Bits(8 bits)))` | message bytes (TYPE SEQ PAYLOAD, ≤ 62 B); CRC appended here |
| `io.txDataRaw` | out `Bits(20 bits)` | encoded word, symbol 0 first on the wire |
| `io.txTrigger` | out `Bool` | pulses **exactly in the cycle the SOF word is on `txDataRaw`** — the t1/t3 event |
| `io.calMode` | in `Bool` | continuous K28.7 (entered/left from idle) — the §3.5.6 calibration square wave |
| `io.oversize` | out `Bool` | pulse: a > 62 B message was consumed and discarded |

## How it works

- **Slurp, then blast.** The byte stream delivers ≤ 1 B/cycle but the line needs 2, so the message
  is loaded into a 64-byte buffer first (idle keeps running) and transmitted back-to-back once
  complete — the "FIFO holds a full message before send" contract of spec 02 §2, implemented
  structurally rather than as a software promise. Oversize messages are consumed and dropped.
- **Idle discipline** is 1000Base-X verbatim: `/I1/`(K28.5+D5.6) flips disparity, `/I2/`(K28.5+D16.2)
  preserves it, chosen so every idle word leaves RD−; the comma is always at symbol 0. A new frame
  starts only after ≥ 2 idle cycles.
- **Framing FSM**: `SOF` (K27.7 + first byte), `DATA` pairs, then the CRC/EOF tail in one of two
  shapes so EOF never puts a comma-class K at an odd position: odd body → `LO_EOF` (CRC-lo + EOF at
  symbol 1), even body → `CRC_PAIR` then `EOF_FILL` (EOF at symbol 0 + D16.2 fill).
- **CRC16** (`Crc16`, CCITT-FALSE: poly 0x1021, init 0xFFFF) over TYPE..PAYLOAD, appended high byte
  first; `Crc16.step` is a combinational byte advance applied up to twice per cycle, with a Scala
  mirror (`stepModel`/`model`) for goldens. The RX check is then a zero-residue test.
- **Trigger alignment**: `txDataRaw` and `txTrigger` are both registered once, so the trigger→port
  latency constant is **0 cycles** — asserted by `WrPcsLoopSim` (spec 02 §5); the encoder chain and
  the registered running disparity sit between the FSM and the port.

## Verification

`WrPcsLoopSim` (see [README](README.md)): message soup bit-exact through the serial model into
`WrRxPcs`; TX trigger cycle asserted to carry an SOF code at symbol 0; oversize refusal; cal
pattern; the end-to-end trigger-delta constant.
