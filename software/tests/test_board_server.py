"""H1 acceptance (spec 10 §8): BoardServer + RemoteDriver + bundle store, end-to-end over a
real Pyro5 daemon on loopback with a RAM-dict fake driver — the full client→serpent→server wire
path, no hardware. Covers the 4-op round-trip (serpent bytes-dict unwrap both directions), the
get_params→SocMap handshake, the chunked store (bit-exact reassembly + sha reject), loud load()
on a missing bundle, and the remote_setup same-build params guard."""

from __future__ import annotations

import dataclasses
import hashlib
import os
import threading
from pathlib import Path

import Pyro5.api
import pytest

from riscq.board.server import BoardServer
from riscq.driver.remote import RemoteDriver, upload_bundle
from riscq.map import SocMap, SocParams
from riscq import run as rq

CONFIGS = Path(__file__).resolve().parents[1] / "configs"
PARAMS_TEXT = (CONFIGS / "sim-2q.json").read_text()


class RamDriver:
    """RAM-dict fake of the Driver seam: a word-addressed {addr: uint32} dict, plus a bytearray
    standing in for the PS DDR4 host-window result buffer (specs/software/22)."""

    def __init__(self):
        self.mem = {}
        self.host = bytearray(1 << 20)
        self.host_base = 0x70000000

    def read32(self, addr):
        return self.mem.get(addr, 0)

    def write32(self, addr, value):
        self.mem[addr] = value & 0xFFFFFFFF

    def read_block(self, addr, nbytes):
        words = b"".join(self.mem.get(addr + i, 0).to_bytes(4, "little")
                         for i in range(0, nbytes + 3, 4))
        return words[:nbytes]

    def write_block(self, addr, data):
        data = bytes(data)
        assert len(data) % 4 == 0
        for i in range(0, len(data), 4):
            self.mem[addr + i] = int.from_bytes(data[i:i + 4], "little")

    def read_host(self, offset, nbytes):
        return bytes(self.host[offset:offset + nbytes])


@pytest.fixture
def board(tmp_path):
    """(RemoteDriver, BoardServer, RamDriver, bits_dir) — daemon on an OS-picked loopback port."""
    fake = RamDriver()
    bits = tmp_path / "bits"
    srv = BoardServer(bits_dir=bits, driver=fake, params_text=PARAMS_TEXT)
    daemon = Pyro5.api.Daemon(host="127.0.0.1")
    uri = daemon.register(srv, objectId="riscq.board")
    thread = threading.Thread(target=daemon.requestLoop, daemon=True)
    thread.start()
    drv = RemoteDriver(str(uri))
    yield drv, srv, fake, bits
    drv.close()
    daemon.shutdown()
    thread.join(timeout=5)
    daemon.close()


def test_ops_round_trip(board):
    """All 5 Driver ops cross the wire 1:1, including the serpent bytes-dict unwrap in BOTH
    directions (client block-write payload up, server block-read bytes down)."""
    drv, _, fake, _ = board
    drv.write32(0x100, 0xDEADBEEF)
    assert drv.read32(0x100) == 0xDEADBEEF
    assert fake.mem[0x100] == 0xDEADBEEF

    blob = bytes(range(256)) * 4                       # 1 KB, all byte values
    drv.write_block(0x2000, blob)
    assert drv.read_block(0x2000, len(blob)) == blob
    assert isinstance(drv.read_block(0x2000, 8), bytes)


def test_host_window_read_round_trip(board):
    """The fifth seam op (specs/software/22 §2.6): `read_host` is buffer-relative and `host_base`
    is the physical address `riscq.run.setup` programs into the funnel."""
    drv, _, fake, _ = board
    fake.host[0x1000:0x1000 + 8] = bytes(range(8))
    assert drv.read_host(0x1000, 8) == bytes(range(8))
    assert isinstance(drv.read_host(0x1000, 8), bytes)
    assert drv.host_base == fake.host_base


def test_get_params_handshake(board):
    """drv.board.get_params() yields the loaded build's JSON — the client derives the matching
    SocMap with no version side channel."""
    drv, _, _, _ = board
    m = SocMap(SocParams.from_json(drv.board.get_params()))
    assert m.params == SocParams.from_json(PARAMS_TEXT)
    info = drv.board.info()
    assert info["bundle"] is None and info["mts_result"] is None   # fake: nothing loaded


def test_store_chunked_upload(board):
    """upload_bundle chunks a multi-chunk file up; the store reassembles it bit-exact and
    bundles() lists it."""
    drv, _, _, bits = board
    xsa_data = os.urandom(9_000_000)                   # > 2 chunks at 4 MB
    xsa_file, params_file = bits.parent / "top.xsa", bits.parent / "params.json"
    xsa_file.write_bytes(xsa_data)
    params_file.write_text(PARAMS_TEXT)

    upload_bundle(drv, "b1", xsa_file, params_file, board={"adc_nyquist": 2})

    assert drv.board.bundles() == {"b1": ["board.json", "params.json", "top.xsa"]}
    assert (bits / "b1" / "top.xsa").read_bytes() == xsa_data
    assert (bits / "b1" / "params.json").read_text() == PARAMS_TEXT
    assert b'"adc_nyquist": 2' in (bits / "b1" / "board.json").read_bytes()


def test_store_rejects_corrupt_sha(board):
    """A sha/size mismatch fails loud at store_end and leaves NO file (partial or final)."""
    drv, _, _, bits = board
    data = os.urandom(1024)
    drv._proxy.store_begin("bad", "top.xsa", len(data), hashlib.sha256(data).hexdigest())
    drv._proxy.store_chunk(data[:512] + bytes(512))    # corrupt second half
    with pytest.raises(Exception, match="corrupt"):
        drv._proxy.store_end()
    assert not (bits / "bad" / "top.xsa").exists()
    assert not (bits / "bad" / "top.xsa.part").exists()


def test_load_missing_bundle_fails_loud(board):
    drv, _, _, _ = board
    with pytest.raises(Exception, match="missing"):
        drv.board.load("nonexistent")


def test_remote_setup_params_guard(board):
    """The same-build check: a client whose SocParams differ from the loaded bundle's is
    rejected before anything touches the driver; a matching client passes (empty progmap
    parks every core in the fake RAM — run.setup really ran server-side)."""
    drv, _, fake, _ = board
    mine = SocParams.from_json(PARAMS_TEXT)
    cores14 = tuple(dataclasses.replace(c, name=f"q{i}") for i, c in enumerate(mine.cores * 7))
    other = dataclasses.replace(mine, cores=cores14)             # a 14-core build, not this one
    with pytest.raises(Exception, match="wrong bundle"):
        drv.remote.setup(other.to_json(), {})
    assert fake.mem == {}                              # rejected before any driver op

    m = SocMap(SocParams.from_json(PARAMS_TEXT))
    rq.setup(drv, m, {})                               # routes via drv.remote (unconditional)
    park = {m.imem(core) for core in range(m.params.qubit_num)}
    assert {a for a, v in fake.mem.items() if v == 0x6F} >= park
