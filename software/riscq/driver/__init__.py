"""The single Driver seam: 5 methods, everything above is backend-agnostic.

`read_host` joined the original four with specs/software/22: the per-core result window lives in PS
DDR4, not behind the AXI window, so no existing op can reach it. Every Driver also carries
`host_base`, the physical address of the buffer it allocated (or models), which `riscq.run.setup`
programs into the funnel's `HOSTWIN_BASE` registers."""

from typing import Protocol, runtime_checkable


@runtime_checkable
class Driver(Protocol):
    host_base: int

    def read32(self, addr: int) -> int: ...
    def write32(self, addr: int, value: int) -> None: ...
    def read_block(self, addr: int, nbytes: int) -> bytes: ...
    def write_block(self, addr: int, data: bytes) -> None: ...
    def read_host(self, offset: int, nbytes: int) -> bytes:
        """Read `nbytes` of the host result buffer at BUFFER-RELATIVE `offset` (so
        `m.hostwin_offset(core) + array_offset`). Only valid after the program's DONE — the window
        writes are posted (specs/software/22 §2.4)."""
        ...
