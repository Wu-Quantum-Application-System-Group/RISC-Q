"""Pulse: one envelope + carrier parameters, with per-channel packing/duration hooks."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from riscq.map import SocMap
from riscq.pulses import units
from riscq.pulses.pack import pack_env


@dataclass(frozen=True)
class Pulse:
    """env is complex float64 on the target channel's stored-sample grid
    (riscq.pulses.envelopes); amp in [-1, 1], phase in rad. freq_hz is the carrier frequency, or
    None to inherit the ParamTable's carrier (spec 02 §3.2) — a Pulse carries no channel/slot."""

    env: np.ndarray
    amp: float
    freq_hz: float | None = None
    phase: float = 0.0
    name: str | None = None

    def packed_lines(self, m: SocMap, channel: int, core: int = 0) -> np.ndarray:
        """Envelope-RAM lines, (n_lines, samples_per_line) uint32, zero-padded to whole lines.
        `channel` is the channel's index in `core`'s channel list."""
        return pack_env(self.env, m.channel(channel, core).samples_per_line)

    def dur_batches(self, m: SocMap, channel: int, core: int = 0) -> int:
        """Pulse duration in batches = number of envelope lines (one line per batch)."""
        return -(-len(self.env) // m.channel(channel, core).samples_per_line)

    def freq_code(self, m: SocMap, carrier_hz: float | None = None) -> int:
        """PLAIN carrier frequency code (the golden/pulse-table form, not a seated word): the
        Pulse's own freq_hz, or `carrier_hz` (the table carrier) when it has none."""
        f = self.freq_hz if self.freq_hz is not None else carrier_hz
        if f is None:
            raise ValueError("Pulse has no carrier: set Pulse.freq_hz or supply a table carrier")
        return units._freq_code(f, m.params)

    def amp_code(self) -> int:
        """PLAIN amplitude code (slot-code / golden form); load_tables seats it for the register."""
        return units._amp_code(self.amp)

    def phase_code(self) -> int:
        """PLAIN phase code (slot-code / golden form); load_tables seats it for the register."""
        return units._phase_code(self.phase)
