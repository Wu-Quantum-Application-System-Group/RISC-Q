from pathlib import Path

import pytest

SW_ROOT = Path(__file__).resolve().parents[1]
CONFIGS = SW_ROOT / "configs"


def pytest_addoption(parser):
    parser.addoption("--cosim", action="store_true", default=False,
                     help="run the verilator co-simulation tests")
    parser.addoption("--slow", action="store_true", default=False,
                     help="also run the full-loop anchor tests (minutes each; implies --cosim)")
    parser.addoption("--batch-cap", type=int, default=0, metavar="N",
                     help="fail a co-sim test that simulates more than N batches (0 = report only)")


def pytest_configure(config):
    if config.getoption("--slow"):
        config.option.cosim = True          # the anchors are co-sim tests: --slow implies --cosim
    config.addinivalue_line("markers", "cosim: verilator co-simulation test (needs --cosim)")
    config.addinivalue_line(
        "markers",
        "batch_cap(n): raise this test's simulated-batch cap to n. ONLY for a structural floor "
        "that cannot be cut without deleting the claim — a co-sim AXI word costs ~22 dspClk "
        "cycles, so one core's image load alone is ~7-12k batches and an N-core lock-step claim "
        "floors near N x 10k. Every use must name the floor in the test's docstring.")
    config.addinivalue_line(
        "markers",
        "slow: full-loop anchor — real shots, noise and fits end-to-end (needs --slow). The "
        "regression net for the tier split: if a host-pure responder or an L2 analytic target "
        "drifts from what the hardware really does, these are what notice "
        "(specs/software-test-refactor/01 §5).")


def pytest_collection_modifyitems(config, items):
    cosim, slow = config.getoption("--cosim"), config.getoption("--slow")
    skip_cosim = pytest.mark.skip(reason="co-sim test: pass --cosim to run")
    skip_slow = pytest.mark.skip(reason="full-loop anchor: pass --slow to run")
    for item in items:
        if "slow" in item.keywords and not slow:
            item.add_marker(skip_slow)
        elif "cosim" in item.keywords and not cosim:
            item.add_marker(skip_cosim)


# ── the simulated-batch meter (specs/software-test-refactor/02 §1, E2) ──
#
# A co-sim test's wall time is set by how many dspClk batches it makes the RTL simulate:
# ~11.5k batches/s with the ADC model off, ~7k with one attached, ~6k for multi/two-qubit. So
# simulated batches — NOT seconds — is the suite's cost unit: it is machine-independent, and it
# is the number a test author can actually reason about (points x shots x grid_period).
#
# refTime free-runs and is never reset, so `batch_time()` is a monotonic session-wide counter and
# the delta across a test is exactly what that test cost.

_batch_log: list[tuple[str, int]] = []


@pytest.fixture(autouse=True)
def sim_batches(request):
    """Record the simulated batches each co-sim test costs; optionally enforce a cap.

    Inert for host-pure tests: it only engages when a co-sim fixture is already in the test's
    fixture closure (directly or transitively, e.g. through `sub` / `remote` / `demod_phase`),
    so it never starts a simulator that the test did not ask for.
    """
    names = [n for n in ("cosim", "cosim_2q1c") if n in request.fixturenames]
    if not names:
        yield
        return
    drvs = [request.getfixturevalue(n)[0] for n in names]
    before = [d.sim.batch_time() for d in drvs]
    yield
    spent = sum(max(0, d.sim.batch_time() - t0) for d, t0 in zip(drvs, before))
    _batch_log.append((request.node.nodeid, spent))
    cap = request.config.getoption("--batch-cap")
    override = request.node.get_closest_marker("batch_cap")
    if override:                       # a documented structural floor, not a licence to be slow
        cap = int(override.args[0])
    if cap and spent > cap and "slow" not in request.node.keywords:   # anchors are not budgeted
        pytest.fail(f"simulated {spent:,} batches, over the {cap:,} cap "
                    f"(~{spent / 7000:.1f}s of co-sim). Cut points/shots, shrink the relax head, "
                    f"or move the assertion to a cheaper tier "
                    f"(specs/software-test-refactor/01-test-tiers.md).", pytrace=False)


def pytest_terminal_summary(terminalreporter, exitstatus, config):
    if not _batch_log:
        return
    total = sum(n for _, n in _batch_log)
    tr = terminalreporter
    tr.write_sep("=", "simulated co-sim batches")
    tr.write_line("(seconds are an upper bound: 7k batches/s with an ADC model attached, "
                  "11.5k with it off)")
    for nodeid, n in sorted(_batch_log, key=lambda kv: -kv[1])[:25]:
        tr.write_line(f"{n:>12,}  (<={n / 7000:6.1f}s)  {nodeid}")
    tr.write_line(f"{total:>12,}  TOTAL over {len(_batch_log)} tests "
                  f"(<={total / 7000 / 60:.1f} min of simulated RTL)")


@pytest.fixture(scope="session")
def socmap():
    """The sim-2q `SocMap`, without starting a simulator — the host-pure tests derive every code
    and grid from it exactly as the co-sim ones do."""
    from riscq.map import SocMap, SocParams

    return SocMap(SocParams.load(CONFIGS / "sim-2q.json"))


@pytest.fixture
def responder(monkeypatch):
    """Factory for the host-pure calibration harness (specs/software-test-refactor/01 §2.2):
    `responder(config_json_path)` → a `Responder` whose `.drv` satisfies `socmap(drv)`."""
    from tests.responder import Responder

    def make(config_json) -> "Responder":
        return Responder(monkeypatch, Path(config_json).read_text())

    return make


@pytest.fixture(scope="session")
def cosim(request):
    """A running verilator co-sim of the sim-2q build: (CosimDriver, SocMap)."""
    if not request.config.getoption("--cosim"):
        pytest.skip("needs --cosim")
    from riscq.map import SocMap, SocParams
    from riscq.sim import server

    drv = server.start(CONFIGS / "sim-2q.json", SW_ROOT / "build" / "sim-2q")
    m = SocMap(SocParams.from_json(drv.sim.get_params()))
    yield drv, m
    server.stop(drv)


@pytest.fixture(scope="session")
def cosim_2q1c(request):
    """A running verilator co-sim of the sim-2q1c (3-core: 2 qubits + 1 coupler) build, with the
    explicit dac_map/adc_map (specs/two-qubit/01 §1): (CosimDriver, SocMap)."""
    if not request.config.getoption("--cosim"):
        pytest.skip("needs --cosim")
    from riscq.map import SocMap, SocParams
    from riscq.sim import server

    drv = server.start(CONFIGS / "sim-2q1c.json", SW_ROOT / "build" / "sim-2q1c")
    m = SocMap(SocParams.from_json(drv.sim.get_params()))
    yield drv, m
    server.stop(drv)


@pytest.fixture(scope="session")
def cosim_mm(request):
    """A running verilator co-sim of the sim-mm build (universal-control/01 P3): core 0 has five
    channels (gate / f0g1 / flux / ro / demod), core 1 the plain three: (CosimDriver, SocMap)."""
    if not request.config.getoption("--cosim"):
        pytest.skip("needs --cosim")
    from riscq.map import SocMap, SocParams
    from riscq.sim import server

    drv = server.start(CONFIGS / "sim-mm.json", SW_ROOT / "build" / "sim-mm")
    m = SocMap(SocParams.from_json(drv.sim.get_params()))
    yield drv, m
    server.stop(drv)


@pytest.fixture(scope="session")
def cosim_dio(request):
    """A running verilator co-sim of the sim-dio build (universal-control/01 P5): core 0 carries a
    timed-DIO bank `ttl` next to gate / ro / demod: (CosimDriver, SocMap)."""
    if not request.config.getoption("--cosim"):
        pytest.skip("needs --cosim")
    from riscq.map import SocMap, SocParams
    from riscq.sim import server

    drv = server.start(CONFIGS / "sim-dio.json", SW_ROOT / "build" / "sim-dio")
    m = SocMap(SocParams.from_json(drv.sim.get_params()))
    yield drv, m
    server.stop(drv)
