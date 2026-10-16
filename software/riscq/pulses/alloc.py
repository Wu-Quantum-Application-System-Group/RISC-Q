"""EnvelopeAllocator: sequential packing of packed envelope lines into one channel's envelope
RAM, with content-hash dedup and a loud overflow error. One allocator per (core, channel)."""

from __future__ import annotations

import numpy as np


class EnvelopeAllocator:
    def __init__(self, env_depth: int):
        self.env_depth = env_depth
        self._next_line = 0
        self._by_content: dict[bytes, int] = {}
        self._image: list[tuple[int, np.ndarray]] = []

    def add(self, lines: np.ndarray, name: str | None = None) -> int:
        """Place packed lines ((n_lines, samples_per_line) uint32, from pack_env /
        Pulse.packed_lines); returns the RAM line index to program as the pulse `env` field.
        Identical content dedups to the first copy; overflow fails loud naming the pulse."""
        lines = np.ascontiguousarray(lines, dtype=np.uint32)
        if lines.ndim != 2 or lines.shape[0] == 0:
            raise ValueError(f"lines must be (n_lines, samples_per_line), got shape {lines.shape}")
        key = lines.tobytes() + bytes([lines.shape[1]])
        if key in self._by_content:
            return self._by_content[key]
        n = lines.shape[0]
        if self._next_line + n > self.env_depth:
            raise ValueError(
                f"envelope RAM overflow: {name or 'envelope'} needs {n} lines at "
                f"{self._next_line}, depth is {self.env_depth}")
        line0 = self._next_line
        self._next_line += n
        self._by_content[key] = line0
        self._image.append((line0, lines))
        return line0

    def image(self) -> list[tuple[int, np.ndarray]]:
        """The RAM image to upload: [(line0, lines)] in allocation order (deduped)."""
        return list(self._image)
