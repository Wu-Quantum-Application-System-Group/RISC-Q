# RefTimeTsu — trigger → `refTime` timestamp capture

**Source:** `src/riscq/wr/time/RefTimeTsu.scala` · **Package:** `riscq.wr.time` · **Clock:** dspClk ·
**Spec:** [04-timebase.md §1](../../specs/white-rabbit/04-timebase.md)

Captures the free-running 64-bit dspClk **`refTime`** counter on a PCS timestamp trigger — the
timestamping unit of the WR exchange, re-based onto the pulse SoC's own time (`ep_timestamping_unit`
heritage, reduced to single-edge capture). One instance per direction: TX captures t1/t3, RX
captures t2/t4. There is no WR-local timebase — `refTime` *is* the timebase.

## Interface

| Signal | Dir | What |
|---|---|---|
| `io.trigger` | in `Bool` | raw PCS trigger pulse (62.5 MHz domain, 16 ns ≈ 8 dspClk cycles wide — no extender needed) |
| `io.refTime` | in `UInt(64)` | the SoC's free-running dspClk counter |
| `io.ack` | in `Bool` | host acknowledge (register-file read, 06 §2): clears `valid`/`overrun` |
| `io.ts`, `io.seq` | out | captured timestamp + 4-bit capture sequence number |
| `io.valid`, `io.overrun` | out | capture pending; sticky protocol-violation flag |

## Behaviour

- 2-FF synchronizer (`BufferCC`) + rising-edge detect → `ts := refTime`, `seq += 1`, `valid := True`.
- **Capture constant: `ts = refTime-in-force-at-the-trigger-rise + 2`** (the two sync FFs) — exact
  in simulation and asserted by `RefTimeTsuSim`; on hardware the trigger phase quantizes it ±1
  dspClk cycle. TX and RX are the *same component*, so the constant is matched and cancels in
  `delay_MM`; the ±1 dithers with the (unrelated) trigger phase and averages out across exchanges
  (spec README §3) — the statistical contract the sim's phase sweep verifies.
- A capture landing while `valid` is still set raises sticky `overrun` (the host runs one
  outstanding exchange at a time; on overrun it restarts). A capture coincident with `ack` is a
  fresh capture, not an overrun.
- No dual-edge capture: the paper's falling-edge disambiguation needs the DDMTD phase (Stage 2);
  Stage 1 accepts the ±1 noise and averages.

## Verification — `RefTimeTsuSim`

`mill runMain riscq.wr.time.sim.RefTimeTsuSim`, dspClk = 2000 sim units (1 ps grid):

- **Phase sweep** (600 pulses, two slide rates ≙ trigger-clock ppm offsets): exactly one capture
  per pulse (`seq` +1), `ts == refTime@rise + 2` always, quantization residue vs the
  continuous-time golden spans (0,1] — the dither statistic.
- **Matched-chain**: a second identical instance on the same trigger yields identical `ts` always.
- **Overrun/seq semantics** as above.
- **Round-trip golden (feeds W3)**: four TSUs (master/slave × TX/RX) on two syntonized counters
  with an injected 123456-cycle offset; 150 scripted SYNC/DELAY_REQ exchanges over a symmetric
  fractional-cycle link delay; `offset_MS = ((t1−t2)+(t4−t3))/2` lands within ±0.5 cycle per
  exchange and within **0.01 cycle in the mean** — the spec README §3 arithmetic validated end-to-end.
