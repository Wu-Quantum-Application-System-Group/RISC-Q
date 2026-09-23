"""BoardServer: the Pyro5 face of the board (spec 10 §5) — the 4-method Driver, the server-side
batch runner (byte-for-byte the co-sim DriverServer's, spec 08 §5), the RFDC board ops, and the
XSA bundle store. Driver-generic: wraps any object with the Driver methods (PynqDriver in
production, a RAM fake in CI); pynq is only imported inside load(), so this module stays
CI-importable. `python -m riscq.board.server` / `riscq-board-server` starts the daemon."""

from __future__ import annotations

import argparse
import functools
import hashlib
import json
import sys
import threading
from pathlib import Path

import Pyro5.api
import serpent

DEFAULT_BITS = "~/riscq-bits"
DEFAULT_PORT = 9091
CHUNK_MAX = 4 * 1024 * 1024   # spec 10 §4: <= 4 MB per store_chunk


def _locked(fn):
    """Every exposed method serializes on one RLock: MMIO and xrfdc are not concurrency-safe and
    Pyro5's threaded daemon interleaves clients. RLock (not Lock) because remote_setup/
    remote_rerun re-enter the locked driver methods on self."""
    @functools.wraps(fn)
    def wrapper(self, *args, **kwargs):
        with self._lock:
            return fn(self, *args, **kwargs)
    return wrapper


@Pyro5.api.expose
class BoardServer:
    """One Pyro5 object; serpent on the wire (Pyro5 default), LAN-trust security model.
    Starts empty (driver ops fail loud) until load() — or CI passes a fake driver directly."""

    def __init__(self, bits_dir: str | Path = DEFAULT_BITS, driver=None,
                 params_text: str | None = None):
        self._lock = threading.RLock()
        self._bits = Path(bits_dir).expanduser()
        self._drv = driver
        self._params = params_text
        self._bundle = None
        self._xsa_sha = None
        self._m = None                # server-side SocMap, built on remote_setup
        self._progs = {}              # core -> Program, rebuilt from the wire on remote_setup
        self._upload = None           # in-flight store_begin state (one at a time)

    def _driver(self):
        if self._drv is None:
            raise RuntimeError("no bundle loaded — call load(<bundle>) first")
        return self._drv

    # ── the Driver face ──

    @_locked
    def read32(self, addr):
        return self._driver().read32(int(addr))

    @_locked
    def write32(self, addr, value):
        self._driver().write32(int(addr), int(value))

    @_locked
    def read_block(self, addr, nbytes):
        return self._driver().read_block(int(addr), int(nbytes))

    @_locked
    def write_block(self, addr, data):
        data = serpent.tobytes(data) if isinstance(data, dict) else bytes(data)
        self._driver().write_block(int(addr), data)

    @_locked
    def read_host(self, offset, nbytes):
        """The host result buffer, buffer-relative (specs/software/22 §2.6). In practice this runs
        server-side inside `remote_rerun`; the RPC exists for ad-hoc client reads."""
        return self._driver().read_host(int(offset), int(nbytes))

    @_locked
    def get_host_base(self):
        """Physical base of the driver's CMA result buffer — what `riscq.run.setup` programs into
        `HOSTWIN_BASE_LO/HI`."""
        return int(self._driver().host_base)

    # ── handshake ──

    @_locked
    def get_params(self):
        if self._params is None:
            raise RuntimeError("no bundle loaded — call load(<bundle>) first")
        return self._params

    @_locked
    def info(self):
        try:
            from importlib.metadata import version
            riscq_version = version("riscq")
        except Exception:
            riscq_version = "unknown"
        return {"bundle": self._bundle, "xsa_sha": self._xsa_sha,
                "mts_result": getattr(self._drv, "mts_result", None),
                "versions": {"python": sys.version.split()[0], "riscq": riscq_version}}

    # ── server-side batch runner: the SAME riscq.run functions next to the MMIO window, one RPC
    # per batch (spec 08 §5). poll_done takes its hardware branch (no `.sim` here). ──

    @_locked
    def remote_setup(self, params_json, progmap):
        from riscq import run as _run
        from riscq.map import SocMap, SocParams

        mine = SocParams.from_json(self.get_params())
        theirs = SocParams.from_json(params_json)
        if theirs != mine:   # same-build guard: co-sim can't fail this, hardware can (spec 10 §5)
            raise ValueError(f"client SocParams ({theirs.name!r}) != the loaded bundle's "
                             f"({mine.name!r}) — wrong bundle loaded?")
        self._m = SocMap(mine)
        self._progs = {int(c): _run._prog_from_wire(w) for c, w in progmap.items()}
        _run.setup(self._driver(), self._m, self._progs)
        return None

    @_locked
    def remote_rerun(self, cores, params, arrays, results, timeout):
        from riscq import run as _run
        progs = {int(c): self._progs[int(c)] for c in cores}
        out = _run.rerun(self._driver(), self._m, progs,
                         params={int(c): v for c, v in dict(params).items()},
                         arrays={int(c): v for c, v in dict(arrays).items()},
                         results=(None if results is None else list(results)),
                         timeout=int(timeout))
        return {c: {n: bytes(a.astype("<i4").tobytes()) for n, a in d.items()}
                for c, d in out.items()}

    # ── board ops: thin delegates (spec 10 §3.3) ──

    @_locked
    def mts(self, daclatency=240, adclatency=72):
        return self._driver().mts(daclatency=int(daclatency), adclatency=int(adclatency))

    @_locked
    def refclks(self, lmk_freq, lmx_freq=None):
        self._driver().refclks(lmk_freq, lmx_freq)

    @_locked
    def adc_nyquist_zone(self, n):
        self._driver().adc_nyquist_zone(int(n))

    @_locked
    def dac_nyquist_zone(self, tile, block, n):
        self._driver().dac_nyquist_zone(int(tile), int(block), int(n))

    @_locked
    def dacvop(self, tile, block, uA):
        self._driver().dacvop(int(tile), int(block), int(uA))

    # ── bundle store (spec 10 §4): chunked upload into ~/riscq-bits/<name>/ ──

    @_locked
    def store_begin(self, bundle, filename, nbytes, sha256):
        if self._upload is not None:   # a crashed client's upload: discard it, start fresh
            self._upload["fh"].close()
            self._upload["tmp"].unlink(missing_ok=True)
        d = self._bits / str(bundle)
        d.mkdir(parents=True, exist_ok=True)
        tmp = d / (str(filename) + ".part")
        self._upload = {"fh": open(tmp, "wb"), "tmp": tmp, "final": d / str(filename),
                        "nbytes": int(nbytes), "sha256": str(sha256),
                        "hash": hashlib.sha256(), "got": 0}

    @_locked
    def store_chunk(self, data):
        if self._upload is None:
            raise RuntimeError("store_chunk without store_begin")
        data = serpent.tobytes(data) if isinstance(data, dict) else bytes(data)
        if len(data) > CHUNK_MAX:
            raise ValueError(f"chunk of {len(data)} B > {CHUNK_MAX} B")
        self._upload["fh"].write(data)
        self._upload["hash"].update(data)
        self._upload["got"] += len(data)

    @_locked
    def store_end(self):
        up, self._upload = self._upload, None
        if up is None:
            raise RuntimeError("store_end without store_begin")
        up["fh"].close()
        digest = up["hash"].hexdigest()
        if up["got"] != up["nbytes"] or digest != up["sha256"]:
            up["tmp"].unlink(missing_ok=True)   # no partial file survives a bad upload
            raise ValueError(f"upload of {up['final'].name} corrupt: got {up['got']} of "
                             f"{up['nbytes']} B, sha256 {digest} != {up['sha256']}")
        up["tmp"].replace(up["final"])

    @_locked
    def bundles(self):
        if not self._bits.is_dir():
            return {}
        return {d.name: sorted(f.name for f in d.iterdir()
                               if f.is_file() and f.suffix != ".part")
                for d in sorted(self._bits.iterdir()) if d.is_dir()}

    @_locked
    def load(self, bundle, download=True):
        d = self._bits / str(bundle)
        xsa, params = d / "top.xsa", d / "params.json"
        missing = [p.name for p in (xsa, params) if not p.exists()]
        if missing:
            have = sorted(f.name for f in d.iterdir()) if d.is_dir() else "<no bundle dir>"
            raise FileNotFoundError(f"bundle {bundle!r}: missing {missing} in {d} (have: {have})")
        board_file = d / "board.json"
        board = json.loads(board_file.read_text()) if board_file.exists() else None

        from riscq.board.pynq_driver import PynqDriver   # lazy: only importable on the board
        # free the previous driver's CMA result buffer BEFORE allocating the next one: it is
        # 16 MB per core (224 MB on the 14q build), so waiting for the GC to reclaim it would make
        # a reload fail the CMA pre-check for no reason (specs/software/22 §3).
        close = getattr(self._drv, "close", None)
        if close is not None:
            close()
        self._drv = None
        self._drv = PynqDriver(str(xsa), str(params), board=board, download=bool(download))
        self._params = params.read_text()
        self._bundle = str(bundle)
        self._xsa_sha = hashlib.sha256(xsa.read_bytes()).hexdigest()
        self._m, self._progs = None, {}
        return self.info()


def main(argv=None):
    ap = argparse.ArgumentParser(description="riscq board server (spec 10 §5)")
    ap.add_argument("--bits", default=DEFAULT_BITS, help="bundle store dir")
    ap.add_argument("--bundle", default=None, help="load this bundle at startup")
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--port", type=int, default=DEFAULT_PORT)
    ap.add_argument("--no-download", action="store_true",
                    help="attach to an already-configured PL (Overlay download=False)")
    args = ap.parse_args(argv)

    server = BoardServer(bits_dir=args.bits)
    if args.bundle:
        server.load(args.bundle, download=not args.no_download)
    daemon = Pyro5.api.Daemon(host=args.host, port=args.port)
    uri = daemon.register(server, objectId="riscq.board")
    print(f"riscq board server @ {uri}   (bundle: {server._bundle})", flush=True)
    daemon.requestLoop()


if __name__ == "__main__":
    main()
