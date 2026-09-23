# Enc8b10b / Dec8b10b — the 8b/10b codec pair

**Source:** `src/riscq/wr/pcs/Enc8b10b.scala`, `Dec8b10b.scala` · **Package:** `riscq.wr.pcs` ·
**Spec:** [02-pcs.md §1](../../specs/white-rabbit/02-pcs.md)

Standard Widmer–Franaszek 8b/10b (the 802.3 clause-36 subset), one symbol per instance,
combinational, with running disparity in/out so **two instances chain within a cycle** (the 20-bit
/ 2-symbol datapath geometry); disparity crosses the cycle boundary through one register in the
enclosing PCS.

## Bit conventions (fixed for the whole `riscq.wr` stack)

Defined once at `WrSymbols`:

- a 10-bit code is packed **LSB-first in transmission order**: bit 0 = `a` (first on the wire),
  bits 5..0 = `abcdei`, bits 9..6 = `fghj`;
- in a 20-bit raw word, **symbol 0 (bits [9:0]) is the even symbol** — the comma position — and is
  transmitted first; symbol 1 is bits [19:10] (matches GTY raw mode: TXDATA bit 0 first);
- `rdIn`/`rdOut`: `False` = RD−, `True` = RD+.

`WrSymbols` also names the K codes the PCS uses: K28.5 (comma), K27.7 (SOF), K29.7 (EOF), K28.7
(cal pattern), plus the idle second-bytes D5.6 / D16.2.

## Encoder (`Enc8b10b`)

`(data, isK, rdIn) → (code, rdOut)`. The 5b/6b + 3b/4b RD− tables are elaboration-time `Seq`s
(transmission-order bit strings, parsed LSB-first); the RD+ column is derived by complementing
unbalanced entries, with the two standard special cases in logic: D7's balanced-but-flipping 6b
block and the **A7 substitution** (`fghj = 0111/1000` for D.x.7 at the disparity combinations that
would produce a run of 5). Valid K bytes are the 12 standard ones; the K path is a 256-entry
elaboration ROM of the RD− codes, complemented for RD+.

`Enc8b10b.model(byte, isK, rdPlus)` is the **elaboration-time Scala mirror** of exactly the same
tables and selection rules. It exists so the decoder cannot drift from the encoder (below), and it
is *not* the verification golden — `Enc8b10bSim` checks both against an independent transcription
of wr-cores `gc_enc_8b10b.vhd`.

## Decoder (`Dec8b10b`)

`(code, rdIn) → (data, isK, codeErr, dispErr, rdOut)`. The 1024-entry reverse ROM is **generated at
elaboration by sweeping `Enc8b10b.model`** over every symbol at both disparities (collisions
asserted away), each entry carrying the decoded byte, `isK`, and a valid-at-RD−/RD+ pair of flags:

- `codeErr` — not a valid codeword under either disparity;
- `dispErr` — valid codeword, but not one the current running disparity may produce.

`rdOut` follows the **received word's actual bit balance** (popcount > 5 ⇒ RD+, < 5 ⇒ RD−, balanced
⇒ hold), so the tracker re-converges across erroneous words instead of sticking wrong.

## Verification — `Enc8b10bSim`

`mill runMain riscq.wr.pcs.sim.Enc8b10bSim` — exhaustive encode vs the independent wr-cores golden
(all data + all K × both disparities); exhaustive decode classification over all 1024 × 2 inputs;
round trip; and stream properties on the chained pair against a golden-tracked disparity: run
length ≤ 5, bounded DC wander, lock-step `rd` over 20k random idle/SOF/data/EOF cycles.
