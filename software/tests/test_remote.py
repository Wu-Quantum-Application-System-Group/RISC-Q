"""Acceptance: the `drv.remote` server-side batch runner. A remote-enabled
CosimDriver routes `setup`/`rerun` through the SAME `riscq.run` functions running next to the
sim (one RPC per call), so a whole batch crosses the wire in 2 RPCs with ZERO per-op seam
traffic. Opt-in: the shared session cosim server stays on the local per-op path for every other
test — the `remote` fixture enables it for one test and restores after."""

import pytest

from riscq import run as rq
from riscq.driver.remote import RemoteDriver
from riscq.lang import Array, compile_kernel
from riscq.map import SocMap, SocParams
from test_rerun import CountingDriver, k_echo   # pulse-free echo kernel + 4-seam-op counter

pytestmark = pytest.mark.cosim


@pytest.fixture
def remote(cosim):
    """Enable the server-side runner for one test, restore the session driver's local per-op path
    after (so other tests in the session are uncontaminated)."""
    drv, m = cosim
    drv.enable_remote()
    yield drv, m
    drv.remote = None


def test_remote_matches_local(remote):
    """The SAME batch via the remote runner and via the plain local per-op path give identical
    result arrays — the server-side runner is correct."""
    drv, m = remote
    prog = compile_kernel(k_echo, m, xs=Array(4, input=True), out=Array(4))
    p, xs = {"offset": 10, "n": 4}, {"xs": [1, 2, 3, 4]}

    remote_out = rq.run(drv, m, {0: prog}, params={0: p}, arrays={0: xs},
                        results=["out"])[0]["out"]
    drv.remote = None                                    # same batch, local per-op path
    local_out = rq.run(drv, m, {0: prog}, params={0: p}, arrays={0: xs},
                       results=["out"])[0]["out"]

    assert list(remote_out) == [11, 12, 13, 14], f"remote wrong: {list(remote_out)}"
    assert list(local_out) == list(remote_out), \
        f"remote {list(remote_out)} != local {list(local_out)}"


def test_remote_zero_client_seam_ops(remote):
    """A remote batch issues ZERO client-side seam ops: setup + rerun run server-side, only 2
    RPCs (remote_setup/remote_rerun, not seam methods) cross. Contrast the local path's ~10."""
    drv, m = remote
    prog = compile_kernel(k_echo, m, xs=Array(4, input=True), out=Array(4))

    cd = CountingDriver(drv)                              # forwards `.remote` unchanged
    out = rq.run(cd, m, {0: prog}, params={0: {"offset": 7, "n": 4}},
                 arrays={0: {"xs": [10, 20, 30, 40]}}, results=["out"])[0]["out"]
    print(f"\n[remote] client seam ops = {cd.ops}")
    assert cd.ops == 0, f"remote batch leaked {cd.ops} client seam ops (all should run server-side)"
    assert list(out) == [17, 27, 37, 47], f"remote batch wrong: {list(out)}"

    drv.remote = None                                    # contrast: local per-op path
    cd2 = CountingDriver(drv)
    rq.setup(cd2, m, {0: prog})
    cd2.ops = 0
    rq.rerun(cd2, m, {0: prog}, params={0: {"offset": 7, "n": 4}},
             arrays={0: {"xs": [10, 20, 30, 40]}}, results=["out"])
    print(f"[local]  client seam ops = {cd2.ops}")
    assert cd2.ops >= 8, f"local rerun should cost ~10 seam ops, got {cd2.ops}"
    drv.enable_remote()


def test_remote_run_oneshot(remote):
    """`rq.run` (= setup + one rerun) via the remote runner returns correct results."""
    drv, m = remote
    prog = compile_kernel(k_echo, m, xs=Array(4, input=True), out=Array(4))
    out = rq.run(drv, m, {0: prog}, params={0: {"offset": 100, "n": 4}},
                 arrays={0: {"xs": [1, 2, 3, 4]}}, results=["out"])[0]["out"]
    assert list(out) == [101, 102, 103, 104], f"one-shot remote run wrong: {list(out)}"


def test_remote_driver_batch(cosim):
    """The same batch through RemoteDriver — the hardware client — against the
    co-sim DriverServer. The map comes from the get_params handshake, the batch rides the
    UNCONDITIONAL `.remote` path (no enable_remote), and zero per-op seam traffic crosses —
    exactly how the client will drive the board server."""
    drv, m = cosim
    rdrv = RemoteDriver(str(drv._proxy._pyroUri))
    try:
        m2 = SocMap(SocParams.from_json(rdrv.board.get_params()))
        assert m2.params == m.params, "handshake map != session map"
        prog = compile_kernel(k_echo, m2, xs=Array(4, input=True), out=Array(4))
        cd = CountingDriver(rdrv)
        out = rq.run(cd, m2, {0: prog}, params={0: {"offset": 5, "n": 4}},
                     arrays={0: {"xs": [1, 2, 3, 4]}}, results=["out"])[0]["out"]
        assert list(out) == [6, 7, 8, 9], f"RemoteDriver batch wrong: {list(out)}"
        assert cd.ops == 0, f"RemoteDriver batch leaked {cd.ops} per-op client calls"
    finally:
        rdrv.close()
