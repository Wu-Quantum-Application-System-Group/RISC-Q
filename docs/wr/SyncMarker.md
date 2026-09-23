# SyncMarker — scope-visible sync verification output

**Source:** `src/riscq/wr/time/SyncMarker.scala` · **Package:** `riscq.wr.time` · **Clock:** dspClk ·
**Spec:** [04-timebase.md §2](../../specs/white-rabbit/04-timebase.md)

A dspClk comparator that pulses a pin when **`syncTime`** reaches a host-armed 64-bit
`markerTime`, stretched to `width` cycles for scope visibility. Both boards get the same
`markerTime` (in the aligned `syncTime` epoch, a safe margin in the future); after the
`timeOffset` correction the two pins' rising edges must coincide within one dspClk cycle on the
scope — the Stage-1 acceptance measurement (spec README §7).

It compares **`syncTime`, not `refTime`, deliberately**: the marker verifies the *corrected*
alignment end-to-end including the `timeOffset` path (`syncTime = RegNext(refTime + timeOffset)`;
the one-cycle `RegNext` is identical on both boards and cancels).

## Interface & behaviour

| Signal | Dir | What |
|---|---|---|
| `io.syncTime` | in `UInt(64)` | from `riscqArea` (the node never owns or modifies it) |
| `io.arm` + `io.markerTime` | in | load a target; arming with a **past** time does not arm and raises `missed` (doubles as the disarm idiom) |
| `io.width` | in `UInt(8)` | marker pulse width in dspClk cycles (host default ~100) |
| `io.period` | in `UInt(32)` | `0` = one-shot (disarm on fire); else auto re-arm at `target + period` — continuous scope triggering |
| `io.marker`, `io.armed`, `io.missed` | out | the pin + status |

- Fire is the first `syncTime >= target` hit (not equality — robust to the moment being skipped by
  a `timeOffset` rewrite); after the arm-time future check the two coincide.
- **Marker rise = one cycle after `syncTime === markerTime`** (the stretch register) — a matched
  constant on both boards, cancels on the scope. Asserted cycle-exact by the sim.

## Verification — `SyncMarkerSim`

`mill runMain riscq.wr.time.sim.SyncMarkerSim` — the TB reproduces the SoC's
`syncTime = RegNext(refTime + timeOffset)` shape with a nonzero `timeOffset` (the marker must
track `syncTime`, not `refTime`). Cycle-exact checks via an `onSamplings` transition trace:
one-shot rise at `markerTime + 1` and fall `width` later; `width = 1` minimal pulse; periodic
rises exactly `period` apart with `armed` held; past-arm → `missed` + never fires; a good re-arm
clears `missed`.
