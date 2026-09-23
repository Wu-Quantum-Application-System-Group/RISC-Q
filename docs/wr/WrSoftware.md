# WrSoftware — the SoC attach + host measurement/correction software

**Source:** `software/riscq/wr.py`, `src/riscq/soc/PulseTableSoc.scala` (`withWhiteRabbit`) ·
**Spec:** [06-node-soc.md](../../specs/white-rabbit/06-node-soc.md) §1/§4,
[07-software.md](../../specs/white-rabbit/07-software.md)

Stage-1 White Rabbit software: bring up the link, run timestamped exchanges, average, write the
slave's `timeOffset`, verify — all through the 4-method `Driver` seam
(`riscq.driver.Driver.read32/write32`), so the same code runs against the cosim backend, locally
on the board ARM, or over `RemoteDriver`. No servos, no syntonization, no board-local loops: the
exchange rate is ~1–10 Hz, far inside MMIO latency budgets.

## Structure (spec 07 §1)

| Piece | What |
|---|---|
| `WrRegs` | register offsets + CTRL bit constants over `SocMap.wr_base` (mirrors [WrNode](WrNode.md)'s map; `CTRL_PMA_LOOPBACK` is the W6 self-test bit — pass it to `bringup()`/`release()` so it lands in the same write as the reset release) |
| `Exchange` | one `(t1, t2, t3, t4)` tuple; `delay_mm` (eq 3.9), `offset(eps)` (README §3), `t_mid` |
| `fit(points)` | least squares `(mean-y at x̄, slope)` — arithmetic mirrors the Scala golden |
| `WrNodeCtl` | one board: `bringup`/`release`/`await_up`, `send_marker`, `txts`/`rxts` (latched 64-bit reads, seq/overrun checks, ack), `pop_frame`, `set_marker`, `time_offset`/`set_time_offset` |
| `WrLink` | the pair: `link_up` (release both first), `exchange`, `measure(n)` → `Fit`, `sync()` (drift-refusal + correction + residual refit), `verify(at)` |

Contracts worth knowing:

- **`timeOffset` is a client-side shadow** — the hostCtrl registers are write-only, so
  `time_offset()` returns the last value this client wrote (signed, unbounded; wrapped to 64 bits
  at write time). The host is the only writer, so the shadow is ground truth (spec 06 §5).
- **`sync()` refuses on drift**: if the fitted slope implies > 0.5 cycle of movement over the
  measurement span, a static `timeOffset` would not hold — it raises instead of writing
  (spec 07 §3, the shared-RF-reference check).
- `round_half_up` reproduces Scala `Math.round` (Python's `round()` is banker's rounding) — the
  written correction must equal the golden's bit-for-bit.
- **Every method's register-op sequence deliberately mirrors the `WrTwoNodeSim` Scala host** —
  that is what makes the golden-parity replay below possible; change both together or the parity
  test fails.

## Verification (spec 07 §6)

`software/tests/test_wr.py`, three layers:

1. **Pure math** — `fit` on synthetic lines, the offset/delay identities at real (> 2^32)
   refTime magnitudes (wrap-free integer arithmetic), Scala-rounding semantics.
2. **Golden parity** — `WrNodeCtl`/`WrLink` replayed against the committed W3 register trace
   (`tests/data/wr_two_node_trace.jsonl`, written by `WrTwoNodeSim`'s nominal run): a
   `TraceDriver` asserts the Python client issues the **identical op sequence** (bring-up, 40
   exchanges, marker arms — the trace must be fully consumed), the per-exchange
   `(t1,t2,t3,t4)`/offset values match bit-exact, and the decision (mean, slope, and the
   round-half-up `corr` actually written) matches the recorded golden decision. Cheap lock-step,
   no re-simulation. Regenerate with `mill runMain riscq.wr.sim.WrTwoNodeSim` + copy from
   `simWorkspace/`.
3. **Cosim smoke** (`--cosim`, k_echo-class ~1 min) — the `sim-wr` build
   (`software/configs/sim-wr.json`, `qubit_num = 1`, `with_white_rabbit = true`,
   `wr_marker_dac = 4`) under the verilator cocotb bench, whose `_wr_loopback` coroutine stands
   in for the phy (constant-delay word queue, no dice-throw — that is `GtySimPhy`'s job in the
   SpinalSim gates): bring-up, two self-exchanges (constant latency), marker arm → fire, and a
   DAC-4 capture showing the width-50 full-scale marker step at the armed syncTime.

The SoC-side gate (`WrNodeSim`, the real AXI path incl. the 64-bit torn-read) is on the
[WrNode](WrNode.md) page.
