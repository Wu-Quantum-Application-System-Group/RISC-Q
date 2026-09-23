# PutLane — put frames on the White Rabbit lane (board ↔ board)

**Source:** `src/riscq/soc/link/PutLane.scala` · **Package:** `riscq.soc.link` · **Type:** bundle
(`PutFrame`) + `Area`s (`PutFramePacker`, `PutFrameUnpacker`, `FrameRouter`, `PutLane`)

The board-to-board leg of the put network ([specs/cross-core/02](../../specs/cross-core/02-put-network.md)
§5, decision D5): the [PutHub](PutHub.md)'s frames for the other board ride the **same deterministic
GTY lane** the White Rabbit node uses for its marker frames, as one more frame type. `PutLane` is the
WR TX/RX PCS pair ([WrTxPcs](../wr/WrTxPcs.md) / [WrRxPcs](../wr/WrRxPcs.md)) with the put path
beside the host's frames; [WrNode](../wr/WrNode.md) instantiates it in place of its bare PCS pair.

## The frame

A `PutFrame` is 72 bits: `board[4]`, `aux[8]`, `node[12]`, `offset[16]`, `data[32]`. On the wire it is
a WR frame of TYPE `0x50` ('P'), a running SEQ byte and the 9 payload bytes (11 bytes, ≈ 100 ns at
1.25 Gb/s); the PCS appends and checks the CRC, so only CRC-good frames are ever delivered. What the
fields mean is the hub's business:

| Frame | `board` | `aux` | `node` | `offset` | `data` |
|---|---|---|---|---|---|
| a forwarded group publish | source board | source core | `groupNode(g)` | 0 | `{slot, bit}` |
| a barrier arrival (non-root → root) | source board | source core | `barrierNode(b)` | **0** | the member count |
| a barrier release (root → a board) | destination board | — | `barrierNode(b)` | **the arriver mask** (≠ 0) | the released time |
| a unicast to a core on another board | — | — | `inboxNode(board, core)` | the inbox offset | the word |

## Structure

```
  hub.laneOut (dspCd) ─▶ StreamFifoCC ─▶ PutFramePacker ─┐
                                                          ├─ arbiter (host first, fragment-locked) ─▶ WrTxPcs ─▶ txDataRaw
  host txFifo (markers, refCd) ────────────────────────────┘
  rxDataRaw ─▶ WrRxPcs ─▶ FrameRouter ─┬─ TYPE == 'P' ─▶ PutFrameUnpacker ─▶ StreamFifoCC ─▶ hub.laneIn (dspCd)
                                       └─ else ─────────▶ host rxFifo
```

- **TX.** `putTx` (a `Stream(PutFrame)` in `dspCd`) crosses into the PCS's `clkRef` domain through a
  16-deep `StreamFifoCC`; the packer serialises one frame per 11 cycles; a `lowerFirst.fragmentLock`
  arbiter interleaves whole frames with the host's, the host (the markers) first. Timestamps are
  unaffected: the PCS raises its TX/RX triggers at the physical SOF, so a put frame ahead of a marker
  delays the marker and its recorded departure equally.
- **RX.** Every CRC-good frame is routed whole by its first byte: put frames to the unpacker (a frame of
  the wrong length is dropped) and a `StreamFifoCC` into `dspCd` as `putRx` (a `Flow`; a burst beyond
  the FIFO is dropped, posted semantics); anything else to the host's byte FIFO exactly as before.
- **Clock domains.** The CC FIFOs' `dspCd` sides are the plain dsp clock, never the per-run `riscqCd`
  (its reset would desync a crossing FIFO, [SOC_TIPS](SOC_TIPS.md) §8.8); the hub (`riscqCd`) drives
  `putTx` as ordinary same-clock signals.

## Bandwidth and latency

≈ 10 M frames/s per direction against < 1 M puts/s of traffic; a frame costs ≈ 100 ns of lane time plus
the constant PHY latency. A two-board barrier is one forwarded arrival plus one release frame.

## Verification

`riscq.soc.sim.PutLaneSim` — two [PutHub](PutHub.md)s (board 0 the root, board 1) over two `PutLane`s
cross-wired at the raw 20-bit word level with a few cycles of delay: a bit published on either board
reaches both boards' broadcasts with the same group word; a barrier with members on both boards
releases exactly once per board, to exactly the arrivers, with the **same** time on both (the root's
stamp, while board 1's own clock is offset); a unicast to a core on the far board lands there with
mask `1 << core`; and the ordering contract holds across the lane — a bit published before an arrival
precedes the release beat on both boards.

```bash
mill runMain riscq.soc.sim.PutLaneSim
```

## Related

[PutHub](PutHub.md) · [WrNode](../wr/WrNode.md) · [WrTxPcs](../wr/WrTxPcs.md) · [WrRxPcs](../wr/WrRxPcs.md) ·
[specs/cross-core/02](../../specs/cross-core/02-put-network.md) §5 · [specs/white-rabbit](../../specs/white-rabbit/README.md)
