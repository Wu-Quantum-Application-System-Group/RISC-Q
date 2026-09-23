"""PynqDriver: the 4-method Driver over the ZCU216 AXI window + the RFDC bring-up ops
(spec 10 §3). The ONLY module that imports pynq/xrfclk/xrfdc — it only ever runs on the board;
the server imports it lazily inside load(). The RFDC operations are reproduced from two working
references — QubiC's PLInterface and qcal-riscq's RiscqPlInterface (this gateware's previous
driver, same board) — and stay unverified until M6 hardware bring-up (spec 10 §7)."""

from __future__ import annotations

import asyncio
import logging
import re
from pathlib import Path

import numpy as np
import pynq
import xrfclk
import xrfdc  # noqa: F401 — registers the RFdc driver so overlay.rf_data_converter binds

log = logging.getLogger(__name__)

AXI_BASE = 0x8000_0000   # riscvsoc-bd flow: bd-build.tcl assign_bd_address -offset
AXI_SIZE = 0x1000_0000   # ... -range

# board.json defaults (spec 10 §4); tile/block keys are "tile,block" strings
BOARD_DEFAULTS = {
    "lmk_freq": 500.25,                             # qcal-riscq's proven value on this board
    "lmx_freq": None,
    "adc_nyquist": 1,
    "dac_nyquist": {"default": 2},
    "dac_current": {},
    # measured on the ZCU216 with this RFDC config: free-run DAC 224 / ADC 64. The target must sit
    # in [measured, measured + 31] — xrfdc only ADDS delay, at most 31 steps (QubiC's 260/60 miss
    # that window on both sides). 240/72 leave headroom both ways.
    "mts": {"daclatency": 240, "adclatency": 72},
}

_refclks_done = False    # LMK/LMX setup runs once per server process, not per load (spec 10 §3.2)


class PynqDriver:
    """MMIO Driver + overlay + RFDC ops. Construction IS bring-up, in the reference order:
    ref clocks -> overlay download -> MMIO -> MTS -> Nyquist zones -> DAC VOP."""

    def __init__(self, xsa: str, params_json: str, board: dict | None = None,
                 download: bool = True):
        # pynq's Xrt-backed EmbeddedDevice does `asyncio.get_event_loop()` when it is first
        # constructed (XrtDevice.__init__, triggered by the Overlay below). The board server
        # runs load() in a Pyro5 worker thread, which on Python 3.10+ has no implicit loop — give
        # it one so the probe doesn't raise "no current event loop in thread ...". We never run
        # the loop (the driver polls, it never waits on interrupts); the probe just needs the
        # call to succeed. No-op on the main thread, which already has a loop.
        try:
            asyncio.get_event_loop()
        except RuntimeError:
            asyncio.set_event_loop(asyncio.new_event_loop())

        cfg = {**BOARD_DEFAULTS, **(board or {})}
        self.params_text = Path(params_json).read_text()

        global _refclks_done
        if not _refclks_done:
            self.refclks(cfg["lmk_freq"], cfg["lmx_freq"])
            _refclks_done = True
        log.info(f"loading overlay: {xsa}")
        self.overlay = pynq.Overlay(str(xsa), download=download)
        self.rfdc = self.overlay.rf_data_converter
        self.mmio = pynq.MMIO(AXI_BASE, AXI_SIZE)

        # Auto-MTS is opt-out and non-fatal: board.json "mts": null skips it at bring-up (run
        # drv.board.mts() by hand instead; mts_result stays None = "not run"), and a hard MTS
        # miss with it enabled is logged + surfaced via info(), never aborts load().
        if cfg["mts"]:
            try:
                self.mts_result = self.mts(**cfg["mts"])
            except RuntimeError as e:
                log.error(f"auto-MTS failed, continuing (set board.json \"mts\": null to skip): {e}")
                self.mts_result = 1
        else:
            self.mts_result = None
        log.info(f"mts: {self.mts_result}")
        zones = cfg["dac_nyquist"]
        for tile in range(4):
            for block in range(4):
                self.dac_nyquist_zone(tile, block,
                                      zones.get(f"{tile},{block}", zones.get("default", 2)))
        self.adc_nyquist_zone(cfg["adc_nyquist"])
        for tileblock, uA in cfg["dac_current"].items():
            tile, block = (int(x) for x in tileblock.split(","))
            self.dacvop(tile, block, uA)

        # ── host-window result buffer (specs/software/22) ──
        # One contiguous CMA buffer, 16 MB per core, allocated ONCE for the session: the PL writes
        # results straight into it over S_AXI_HP0_FPD, so a raw run is no longer bounded by the
        # core's 16 KB RAM and readback is a numpy copy instead of word-at-a-time MMIO.
        # `pynq.allocate` is non-cacheable by default, so a read sees DDR with no cache maintenance.
        from riscq.map import SocMap, SocParams
        nbytes = SocMap(SocParams.from_json(self.params_text)).hostwin_bytes_total
        _check_cma(nbytes)
        self._host_buf = pynq.allocate(shape=(nbytes,), dtype=np.uint8)
        self.host_base = int(self._host_buf.device_address)
        log.info(f"host window: {nbytes / (1 << 20):.0f} MB at {self.host_base:#x}")

    # ── the Driver protocol over pynq.MMIO (a numpy uint32 view of the /dev/mem mmap) ──

    def _check(self, addr: int, nbytes: int = 4) -> None:
        if addr % 4:
            raise ValueError(f"unaligned address {addr:#x}")
        if addr < 0 or addr + nbytes > AXI_SIZE:
            raise ValueError(f"[{addr:#x}, {addr + nbytes:#x}) outside the AXI window "
                             f"(size {AXI_SIZE:#x})")

    def read32(self, addr: int) -> int:
        self._check(addr)
        return self.mmio.read(addr)

    def write32(self, addr: int, value: int) -> None:
        self._check(addr)
        self.mmio.write(addr, int(value) & 0xFFFFFFFF)

    def read_block(self, addr: int, nbytes: int) -> bytes:
        self._check(addr, nbytes)
        # Word-at-a-time single-beat reads (like read32/write_block). A numpy slice read
        # (self.mmio.array[a:b].tobytes()) issues a multi-beat/wide AXI burst that this gateware's
        # BRAM host read port DECERRs -> SIGBUS ("Bus error"); the PL only handles 32-bit beats.
        nwords = -(-nbytes // 4)
        buf = b"".join((self.mmio.read(addr + 4 * i) & 0xFFFFFFFF).to_bytes(4, "little")
                       for i in range(nwords))
        return buf[:nbytes]

    def close(self) -> None:
        """Release the CMA result buffer. Called before a reload so the next driver's
        `pynq.allocate` sees the pool free (specs/software/22 §3)."""
        buf, self._host_buf = getattr(self, "_host_buf", None), None
        if buf is not None:
            buf.freebuffer()

    def read_host(self, offset: int, nbytes: int) -> bytes:
        """Read the CMA result buffer at buffer-relative `offset` (specs/software/22 §2.6). Only
        valid after the program's DONE — the window writes are posted (§2.4)."""
        if not 0 <= offset <= self._host_buf.nbytes - nbytes:
            raise ValueError(f"read_host [{offset}, {offset + nbytes}) outside the "
                             f"{self._host_buf.nbytes} B host buffer")
        view = self._host_buf[offset:offset + nbytes]
        invalidate = getattr(self._host_buf, "invalidate", None)
        if invalidate is not None:
            invalidate()      # no-op on a non-cacheable buffer; correct if one is ever cacheable
        return view.tobytes()

    def write_block(self, addr: int, data: bytes) -> None:
        data = bytes(data)
        if len(data) % 4:
            raise ValueError(f"write_block length {len(data)} is not a multiple of 4 "
                             "(pynq MMIO word-loops the payload)")
        self._check(addr, len(data))
        self.mmio.write(addr, data)

    # ── RFDC ops, reproduced verbatim from the references (spec 10 §3.3 / §7) ──

    def refclks(self, lmk_freq: float, lmx_freq: float | None = None) -> None:
        if lmx_freq is None:
            xrfclk.set_ref_clks(lmk_freq=lmk_freq)
        else:
            xrfclk.set_ref_clks(lmk_freq=lmk_freq, lmx_freq=lmx_freq)
        log.info(f"ref clocks: lmk={lmk_freq} lmx={lmx_freq}")

    def config_mts(self, daclatency: int = -1, adclatency: int = -1) -> None:
        for mts_cfg in (self.rfdc.mts_dac_config, self.rfdc.mts_adc_config):
            mts_cfg.RefTile = 2      # the references' choice — see xrfdc MTS restrictions
            mts_cfg.Tiles = 0xF      # bitmask: sync all 4 tiles
            mts_cfg.SysRef_Enable = 1
        self.rfdc.mts_dac_config.Target_Latency = daclatency
        self.rfdc.mts_adc_config.Target_Latency = adclatency

    def mts(self, daclatency: int = 240, adclatency: int = 72) -> int:
        """Two-pass MTS (QubiC): free sync to measure the latencies; if all tiles agree and are
        within target, re-sync pinned to the targets. 0 iff every measured latency == target.
        Raises (XRFdc_MultiConverter_Sync) if a tile never reaches the started state — run it by
        hand via drv.board.mts() to see the full converter error; __init__ guards the auto call
        so an MTS miss never aborts bring-up (spec 10 §7: MTS is re-pinned at bring-up)."""
        self.config_mts()
        self.rfdc.mts_dac()
        self.rfdc.mts_adc()
        dac_lat, adc_lat = self._mts_latencies()
        log.debug(f"mts before: dac={dac_lat} adc={adc_lat}")
        if (all(l == dac_lat[0] for l in dac_lat) and dac_lat[0] <= daclatency
                and all(l == adc_lat[0] for l in adc_lat) and adc_lat[0] <= adclatency):
            self.config_mts(daclatency, adclatency)
            self.rfdc.mts_dac()
            self.rfdc.mts_adc()
            dac_lat, adc_lat = self._mts_latencies()
            log.debug(f"mts after: dac={dac_lat} adc={adc_lat}")
        return 0 if (all(l == daclatency for l in dac_lat)
                     and all(l == adclatency for l in adc_lat)) else 1

    def _mts_latencies(self) -> tuple[list, list]:
        return ([self.rfdc.mts_dac_config.Latency[i] for i in range(4)],
                [self.rfdc.mts_adc_config.Latency[i] for i in range(4)])

    def adc_nyquist_zone(self, n: int) -> None:
        for tile in range(4):        # rfdc-config.tcl enables every slice: full 4x4
            for block in range(4):
                self.rfdc.adc_tiles[tile].blocks[block].NyquistZone = n

    def dac_nyquist_zone(self, tile: int, block: int, n: int) -> None:
        self.rfdc.dac_tiles[tile].blocks[block].NyquistZone = n

    def dacvop(self, tile: int, block: int, uA: int) -> None:
        log.info(f"dac vop: tile {tile} block {block} -> {uA} uA")
        self.rfdc.dac_tiles[tile].blocks[block].SetDACVOP(uA)


def _check_cma(nbytes: int) -> None:
    """Fail loudly and early if the kernel's CMA pool cannot hold the result buffer — otherwise
    `pynq.allocate` dies deep in the driver with an opaque error (specs/software/22 §3). Fix by
    rebuilding with a narrower window (`"hostwin_bits": 23` in the build JSON) or rebuilding the
    image with a larger `cma=` boot arg."""
    try:
        info = Path("/proc/meminfo").read_text()
    except OSError:
        return                     # not Linux/procfs — let allocate() speak for itself
    fields = {k: int(v) * 1024 for k, v in re.findall(r"^(Cma\w+):\s+(\d+) kB", info, re.M)}
    total, free = fields.get("CmaTotal"), fields.get("CmaFree")
    if total is None:
        return
    if total < nbytes or (free is not None and free < nbytes):
        raise RuntimeError(
            f"host-window buffer needs {nbytes / (1 << 20):.0f} MB of CMA, but CmaTotal="
            f"{total / (1 << 20):.0f} MB / CmaFree={(free or 0) / (1 << 20):.0f} MB. Rebuild with a "
            f"smaller `hostwin_bits` in the build JSON, boot with a larger `cma=`, or restart the "
            f"server to free a leaked buffer.")
