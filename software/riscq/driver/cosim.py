"""CosimDriver: Pyro5 proxy to the cocotb co-sim bench (riscq.sim.bench). Implements the
4-method Driver; sim-only extras live behind the explicit `.sim` attribute."""

from __future__ import annotations

import time
from pathlib import Path

import numpy as np
import Pyro5.api
import serpent


def _to_bytes(data) -> bytes:
    # Pyro5's serpent serializer ships bytes as {'data': b64, 'encoding': 'base64'} dicts.
    if isinstance(data, dict):
        return serpent.tobytes(data)
    return bytes(data)


class _SimExtras:
    """Sim-only operations, outside the Driver protocol."""

    def __init__(self, proxy: Pyro5.api.Proxy):
        self._proxy = proxy

    def get_params(self) -> str:
        """The build's SocParams JSON text (so the client derives the matching SocMap)."""
        return self._proxy.get_params()

    def advance(self, cycles: int) -> None:
        """Run the sim for `cycles` host-clock cycles."""
        self._proxy.advance(int(cycles))

    def batch_time(self) -> int:
        """Current batch time (refTime + timeOffset), monotonic across runs. Read it to pick an
        absolute `start_batch` ahead of now (spec 08: refTime free-runs in dspCd, not reset per run)."""
        return int(self._proxy.batch_time())

    def poll_word(self, addr: int, not_equal: int, timeout_cycles: int) -> int:
        """Run the sim until the 32-bit word at `addr` != not_equal, or `timeout_cycles`
        elapse. Returns the last read value either way (caller decides loudness)."""
        return self._proxy.poll_word(int(addr), int(not_equal), int(timeout_cycles))

    def dac_capture_arm(self, dac_id: int, n_batches: int, start_batch: int | None = None) -> int:
        """ARM a DAC capture: sample io_dac_<dac_id> for `n_batches` consecutive batches,
        starting now (arm may precede the riscqReset release) or at batch time `start_batch`
        (needs the release done). Returns a handle for dac_capture_get."""
        return self._proxy.dac_capture_arm(int(dac_id), int(n_batches),
                                           None if start_batch is None else int(start_batch))

    def dac_capture_get(self, handle: int) -> tuple[int, np.ndarray]:
        """Fetch a finished capture (runs the sim until it completes): (t0, samples) with
        samples int16 shape (n_batches, 16), lane k = payload bits [16k+15:16k]. Row j is
        batch time t0 + j; the per-DAC output pipe is already subtracted, so a pulse played
        at t occupies rows stamped [t, t+dur) on every DAC."""
        t0, n, data = self._proxy.dac_capture_get(int(handle))
        samples = np.frombuffer(_to_bytes(data), dtype="<i2").reshape(int(n), 16).copy()
        return int(t0), samples

    def dio_capture_arm(self, name: str, n_batches: int, start_batch: int | None = None) -> int:
        """ARM a timed-DIO capture of the board port `io_dio_<name>_out` (`<core>_<channel>`), like
        dac_capture_arm; the stamps have DIO_PIPE modelled out, so an entry scheduled at batch t
        shows its edge at stamp t. Returns a handle for dio_capture_get."""
        return self._proxy.dio_capture_arm(str(name), int(n_batches),
                                           None if start_batch is None else int(start_batch))

    def dio_capture_get(self, handle: int) -> tuple[int, np.ndarray]:
        """Fetch a finished DIO capture: (t0, levels) with levels uint16 per batch, row j = t0 + j."""
        t0, n, data = self._proxy.dio_capture_get(int(handle))
        levels = np.frombuffer(_to_bytes(data), dtype="<u4").reshape(int(n)).astype(np.uint16)
        return int(t0), levels

    def dio_set(self, name: str, value: int) -> None:
        """Drive the 16 input lines of the timed-DIO bank `io_dio_<name>_in` (applied on the next
        dspClk edge; the bank samples them and posts an edge event with that batch's time)."""
        self._proxy.dio_set(str(name), int(value))

    def set_model(self, spec: dict) -> None:
        """Select/replace the ADC-loop QuantumModel at runtime (spec 05 §3). `spec` is a
        JSON-serializable dict the sim process constructs, e.g. {"kind": "zero"},
        {"kind": "loopback", "gain": 1.0, "src": 0, "dst": 0},
        {"kind": "twolevel", "rabi_rad_per_amp": ..., "readout_code": ..., "init_excited": ...}.
        Needed because the co-sim fixture is session-scoped (one sim process for the whole run)."""
        self._proxy.set_model(dict(spec))

    def model_state(self) -> dict:
        """The active QuantumModel's exact state (specs/software-test-refactor/01 §4.3) — e.g.
        `{"bloch": [x, y, z]}` for a two-level model, `{"populations": [...]}` for three-level,
        `{"populations": ..., "marginals": ...}` for two-qubit, `{"models": [...]}` for a
        MultiModel. `{}` for models that carry no quantum state.

        A TEST OBSERVATION, deliberately confined to the co-sim seam: it lets a test assert what a
        played signal did to the qubit without re-measuring it through shots, which is what makes
        the physics gates cheap. There is no hardware counterpart, so nothing under `riscq/`
        outside `riscq/sim/` may call it."""
        return dict(self._proxy.model_state())

    def shutdown(self) -> None:
        self._proxy.shutdown()


class _RemoteExtras:
    """Server-side batch runner (spec 08 §5): setup/rerun run the SAME riscq.run functions next
    to the sim, one RPC each, so a whole batch crosses the wire in 2 RPCs with ZERO per-op seam
    traffic (instead of ~10 round trips + the poll loop). Lives behind `drv.remote` like `.sim`."""

    def __init__(self, proxy: Pyro5.api.Proxy):
        self._proxy = proxy

    def setup(self, params_json: str, progmap: dict) -> None:
        self._proxy.remote_setup(params_json, progmap)

    def rerun(self, cores, params, arrays, results, timeout):
        raw = self._proxy.remote_rerun(list(cores), dict(params), dict(arrays),
                                       results, int(timeout))
        return {int(c): {n: _to_bytes(b) for n, b in d.items()} for c, d in raw.items()}


class CosimDriver:
    """Driver over Pyro5. `uri` is a PYRO uri string, a host:port (default object name), or a
    path to a file containing the uri (the bench writes one)."""

    def __init__(self, uri: str):
        if not uri.startswith("PYRO:"):
            p = Path(uri)
            if p.exists():
                uri = p.read_text().strip()
            else:
                uri = f"PYRO:riscq.cosim@{uri}"
        self._proxy = Pyro5.api.Proxy(uri)
        # A reply lost at the socket layer otherwise blocks the client FOREVER while the bench
        # idles healthy (measured: a 4.5 h silent hang mid-E-run). 900 s sits above the bench's
        # own 600 s per-op cap, so a healthy long op never trips it and a lost reply raises.
        self._proxy._pyroTimeout = 900.0
        self.sim = _SimExtras(self._proxy)
        self.remote = None   # opt-in server-side batch runner (enable_remote); OFF by default so
        #                      the run layer keeps its per-op path and existing tests are unchanged
        self._proc = None  # set by riscq.sim.server.start()
        self._host_base = None   # modelled PS DDR4 buffer base, fetched lazily (specs/software/22)

    def read32(self, addr: int) -> int:
        return self._proxy.read32(int(addr))

    def write32(self, addr: int, value: int) -> None:
        self._proxy.write32(int(addr), int(value) & 0xFFFFFFFF)

    def read_block(self, addr: int, nbytes: int) -> bytes:
        return _to_bytes(self._proxy.read_block(int(addr), int(nbytes)))

    def write_block(self, addr: int, data: bytes) -> None:
        self._proxy.write_block(int(addr), bytes(data))

    def read_host(self, offset: int, nbytes: int) -> bytes:
        """Read the modelled PS DDR4 result buffer at buffer-relative `offset` (spec 22 §2.6)."""
        return _to_bytes(self._proxy.read_host(int(offset), int(nbytes)))

    @property
    def host_base(self) -> int:
        """Physical base the bench models for the host result buffer — what `riscq.run.setup`
        programs into `HOSTWIN_BASE_LO/HI`."""
        if self._host_base is None:
            self._host_base = int(self._proxy.get_host_base())
        return self._host_base

    def enable_remote(self) -> "CosimDriver":
        """Route setup/rerun through the server-side runner (spec 08 §5): one RPC per batch
        instead of ~10 per-op round trips + the poll loop. Opt-in so per-op co-sim tests are
        untouched; the future RemoteDriver would set `.remote` unconditionally."""
        self.remote = _RemoteExtras(self._proxy)
        return self

    def close(self) -> None:
        self._proxy._pyroRelease()


def wait_for_uri(uri_file: str | Path, timeout_s: float = 600.0) -> str:
    """Block until the bench writes its uri file (verilator build can take minutes)."""
    uri_file = Path(uri_file)
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if uri_file.exists():
            text = uri_file.read_text().strip()
            if text.startswith("PYRO:"):
                return text
        time.sleep(0.2)
    raise TimeoutError(f"cosim bench did not publish {uri_file} within {timeout_s}s")
