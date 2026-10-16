# GtySimPhy / WrPhyIo — the phy contract and its simulation model

**Source:** `src/riscq/wr/gty/GtySimPhy.scala` · **Package:** `riscq.wr.gty`

## `WrPhyIo` — the contract

The phy side of the WR node as one `IMasterSlave` bundle (minus the board pins): the two link clocks (`clkRef` 62.5 MHz, `clkRx` recovered), the raw 20-bit datapath
(`txDataRaw`/`rxDataRaw`, no handshakes — one word every cycle, determinism is the point), the
reset requests (`resetAll`, `resetRxDatapath`, host-domain levels) and the bring-up status
(`ready`, `aligned`, `diceCount`). **Master = the phy.** [`WrGtyPhy`](WrGtyPhy.md) implements the master
side in RTL over the real `GTYE4_CHANNEL`; `WrNode` consumes the slave side and builds its
`clk_ref`/`clk_rx` clock domains from the clock pins — the node elaborates identically against
either.

## `GtySimPhy` — the sim-side link model

`GtySimPhy.link(a, b, Config(...))` forks a complete cross-wired serial link between two
node-facing bundles (a simulation model, not a `Component` — everything it models is behavioral
and the node needs only the contract):

- each node's `clkRef` is a free toggler with a private random phase; each direction replays the
  sender's word stream on the receiver's `clkRx`, which is **the sender's clock delayed by the
  configured per-direction ps delay** (ideal-CDR model) — rate-locked by construction like the
  real recovered clock;
- the effective word latency is `floor(delayPs/P) + 3 + 1` words with the sub-word remainder in
  the `clkRx` phase — **constant per run**, and `delay + P` adds exactly one word (asserted);
- alignment models the **dice-throw** (see [CommaAligner](WrGtyPhy.md#the-fabric-controllers)): each roll picks a word-boundary bit offset
  0..19; non-zero offsets deliver mis-grouped words with `aligned` low; every `rollCycles` RX
  cycles the model re-rolls and bumps `diceCount` until offset 0 lands → `aligned`/`ready`.
  `resetAll` (either side — the link couples the two nodes, like real hardware) and the
  receiver's `resetRxDatapath` restart the throw. `Config.rollProbe` exposes the rolled offsets
  to self-tests.

## Verification — `GtySimPhySim`

`mill runMain riscq.wr.gty.sim.GtySimPhySim` — counter patterns on both directions: after
alignment, delivery is bit-exact, consecutive, and unshifted; word latency constant within a run,
identical across 20 re-init cycles (alternating full / RX-only resets), and exactly +1 word for
+1 refClk period of delay; rolled offsets cover **all 20 positions** and dice-throw try counts
vary — the geometric bring-up statistics expected on hardware.
