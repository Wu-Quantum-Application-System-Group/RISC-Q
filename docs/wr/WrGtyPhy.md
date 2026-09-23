# WrGtyPhy — the deterministic-latency GTY PHY

**Source:** `src/riscq/wr/gty/` (`WrGtyPhy.scala`, `GtyResetCtrl.scala`, `CommaAligner.scala`,
`WrGtyChannel.v`, `WrGtyCommon.v`) · **Package:** `riscq.wr.gty` ·
**Spec:** [01-gty-phy.md](../../specs/white-rabbit/01-gty-phy.md)

The real-transceiver implementation of the [WrPhyIo](GtySimPhy.md) contract: one `GTYE4_CHANNEL`
at **1.25 Gb/s from QPLL0** (`MGTREFCLK0` = 156.25 MHz), **raw 20-bit datapath** (8b/10b lives in
the PCS fabric), **TX and RX elastic buffers bypassed**, no GT comma logic, no RXSLIDE — the
determinism levers of spec D1 that make the link latency a per-reset constant.

## The harvested primitive wrappers

`WrGtyChannel.v` / `WrGtyCommon.v` carry the **complete GT wizard attribute sets** (491 + 89
attributes, `gtwizard_ultrascale` v1.7, Vivado 2026.1), harvested from a throwaway WR-configured
wizard instance via `vivado-scripts/utils/gty-wr-ip.tcl` (reproducible; re-run it after a Vivado
version bump and diff). They expose only the ~50 WR-needed ports; every other primitive input is
tied to the wizard's constant. This is the repo's memory-blackbox pattern (`BramBlackBox.v`)
rather than spec 01 §2's per-attribute `addGeneric` — hand-transcribing 500 attributes into Scala
invites drift; the byte-exact `.v` diff does not. Key harvested values:

| Attribute | Value |
|---|---|
| `TXBUF_EN` / `RXBUF_EN` | FALSE / FALSE (bypassed) |
| `TX_XCLK_SEL` / `RX_XCLK_SEL` | TXUSR / RXUSR |
| `TX/RX_DATA_WIDTH`, `TX/RX_INT_DATAWIDTH` | 20, 0 (= 20-bit internal) |
| `TXOUT_DIV` / `RXOUT_DIV` | 8 / 8 (5 GHz QPLL0CLKOUT → 1.25 Gb/s) |
| `RXSLIDE_MODE`, `ALIGN_*` | OFF / all disabled |
| TXOUTCLKSEL tie | **TXPROGDIVCLK** (`TX_PROGDIV_CFG` 80 → 62.5 MHz; the only wizard-legal source under TX bypass — spec 01 §2 guessed TXOUTCLKPCS) |
| RXOUTCLKSEL tie | RXOUTCLKPMA |
| `QPLL0_FBDIV` / `QPLL0_REFCLK_DIV` / rate | 64 / 1 / HALF (10 GHz VCO), integer-N, SDM tied 0 |
| `TXSYNC_*`, `RXSYNC_*` | single-lane auto mode (`RXSYNC_SKIP_DA` = 1) |

Harvest surprises baked into the design (see the harvest notes in `gty-wr-ip.tcl`):

1. **Raw-20 data is 8-1-1 interleaved** on the primitive (bits 8/9 and 18/19 of each symbol ride
   TXCTRL0/1 / RXCTRL0/1) — `WrGtyChannel.v` does the interleave internally and presents clean
   20-bit `TXDATA`/`RXDATA`, so a straight wire to the PCS is correct.
2. **Power-on hold**: `USER_GTPOWERGOOD_DELAY_EN = 1` requires forcing `TXPISOPD = 1`,
   `TXRATE = 001`, `TXRATEMODE = 1` and holding `GTTXRESET` until `GTPOWERGOOD` + a settle
   delay — the `WrGtyPhy.powerOn` area reproduces the wizard's `gtye4_delay_powergood` contract
   and gates the reset controller behind it.
3. **`clk_free` must be ≤ 62.5 MHz** with both buffers bypassed (wizard/PG182 rule) — spec 01
   §1's 100 MHz free-run clock is amended; the W6 build feeds 62.5 MHz (or a divided clock).

## The fabric controllers

- **[`GtyResetCtrl`](../../specs/white-rabbit/01-gty-phy.md)** (`clk_free`) — the wizard's
  `gtwiz_reset` reduced to one TX/RX pair: QPLL reset → lock → TX resets → PMA → userRdy delay →
  `txResetDone` → RX leg with the fixed **CDR-stable hold** (CDR lock is not observable) → PMA →
  userRdy → `rxResetDone`. `resetRxDatapath` redoes only the RX leg (the dice-throw knob); hung
  waits time out into a counted full retry. Gate: `GtyResetCtrlSim` (scripted fake GT asserting
  release order, RX-only reset isolation, timeout/retry recovery).
- **`BufferBypassCtrl`** (per direction, in its user-clock domain) — single-lane auto mode:
  `DLYSRESET` until `DLYSRESETDONE`, then wait `SYNCDONE`; statics `SYNCMODE=1`,
  `SYNCALLIN=PHALIGNDONE`, `SYNCIN=0` wired in `WrGtyPhy`. Timeout → `error` (reset ctrl retries).
- **[`CommaAligner`](../../specs/white-rabbit/01-gty-phy.md)** (`clk_rx`) — port of wr-cores
  `gtx_comma_detect_lp`: 40-bit sliding window scanned for K28.5 (either disparity) at all 20
  offsets, wr-cores hysteresis (+4/−1, up at 500, loss at 1000); a consistent comma at the wrong
  position emits one `rxResetReq` — the **dice-throw** (geometric, p = 1/20) — until position 0
  lands, so RX bitslide is 0 by construction and **no shifter sits in the datapath**. Gate:
  `CommaAlignerSim` (all 20 offsets, dice statistics, loss of sync).

## Verification & gates (W4)

```bash
mill runMain riscq.wr.gty.sim.CommaAlignerSim       # all 20 offsets + dice-throw + hysteresis
mill runMain riscq.wr.gty.sim.GtyResetCtrlSim       # scripted-GT order/retry + buffer bypass FSM
mill runMain riscq.wr.gty.WrGtyPhyGen               # elaboration
mill runMain riscq.wr.gty.bench.WrGtyPhyVivadoBench # Vivado OOC synth on the ZCU216 part
```

The GT behaviour itself is board territory: W6 brings the PHY up on the ZCU216 (PMA loopback via
`io.loopback` first), where the dice-throw convergence and per-reset latency stability get their
real measurements.
