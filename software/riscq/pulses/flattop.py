"""Long flat-top pulses as contiguous slots (specs/universal-control/01 §4.3 of spec 24, P3).

A slot plays exactly `dur` envelope lines from its base (one line per batch), so a pulse longer than
the channel's envelope RAM does not fit one slot. A flat-top of flat length T is therefore three
slots fired back to back: the rising ramp, `reps` fires of one square piece, the falling ramp —
contiguous through the channel's startTime auto-advance, and phase-continuous because the carrier
is time-referenced (every fire computes exp(i·pi·f·(16·t + k)) from the absolute batch time, and
every piece carries the same slot phase). `tests/test_multichannel.py` pins this bit-exactly
against the golden of ONE long pulse."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from riscq.map import ChannelInfo
from riscq.pulses import envelopes
from riscq.pulses.pulse import Pulse


@dataclass(frozen=True)
class FlatTop:
    """The three slots of one flat-top and how many times the square piece repeats. Play as
    `play(ch, up, t); fire(ch, body) × reps; fire(ch, down)` — at most TRAIN_AHEAD fires may be
    queued ahead of the playing one (riscq.cal.base), so long trains pace the body fires."""

    up: Pulse
    body: Pulse
    down: Pulse
    reps: int

    def pulses(self) -> dict[str, Pulse]:
        """The ParamTable entries, in slot order up / body / down."""
        return {"up": self.up, "body": self.body, "down": self.down}

    def flat_batches(self, channel: ChannelInfo) -> int:
        return self.reps * -(-len(self.body.env) // channel.samples_per_line)

    def dur_batches(self, channel: ChannelInfo) -> int:
        spl = channel.samples_per_line
        return -(-len(self.up.env) // spl) + self.flat_batches(channel) + -(-len(self.down.env) // spl)

    def envelope(self) -> np.ndarray:
        """The equivalent single envelope (what one long slot would play), for goldens."""
        return np.concatenate([self.up.env] + [self.body.env] * self.reps + [self.down.env])


def flat_top(channel: ChannelInfo, ramp_batches: int, flat_batches: int, amp: float,
             freq_hz: float | None = None, phase: float = 0.0, ramp: str = "gaussian",
             sigmas: float = 2.0) -> FlatTop:
    """A flat-top for `channel`: gaussian half-ramps of `ramp_batches` each (the rising half of a
    `gaussian(2·ramp, sigmas)`), a flat of at least `flat_batches` (rounded UP to `reps` equal square
    pieces no longer than half the channel's envelope RAM), same amp/phase on every slot."""
    if ramp_batches < 1 or flat_batches < 1:
        raise ValueError("ramp_batches and flat_batches must be >= 1")
    spl = channel.samples_per_line
    if ramp != "gaussian":
        raise ValueError(f"unknown ramp {ramp!r} (gaussian only)")
    full = envelopes.gaussian(2 * ramp_batches * spl, sigmas)
    up, down = full[: ramp_batches * spl], full[ramp_batches * spl:]
    cap = max(1, channel.env_depth // 2)                # lines per piece: leave room for the ramps
    reps = -(-flat_batches // cap)
    piece = -(-flat_batches // reps)
    body = envelopes.square(piece * spl)
    return FlatTop(Pulse(up, amp, freq_hz, phase), Pulse(body, amp, freq_hz, phase),
                   Pulse(down, amp, freq_hz, phase), reps)
