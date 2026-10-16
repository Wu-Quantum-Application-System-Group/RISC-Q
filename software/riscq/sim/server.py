"""Co-sim lifecycle: start(config, build_dir) spawns the verilator+cocotb bench as a
subprocess (cocotb owns that process's event loop) and returns a connected CosimDriver;
stop(drv) shuts the bench down. `python -m riscq.sim.server <config.json> <build_dir>` is the
subprocess entry (builds the RTL + verilator model, then runs the bench)."""

from __future__ import annotations

import hashlib
import shutil
import subprocess
import sys
import time
from pathlib import Path

from riscq.driver.cosim import CosimDriver
from riscq.sim.rtl import ensure_rtl

VERILATOR_ARGS = ["-Wno-fatal", "-Wno-MULTIDRIVEN", "-Wno-WIDTH", "-Wno-CASEINCOMPLETE",
                  "-Wno-UNOPTFLAT", "--timescale", "1ns/1ps", "--threads", "1"]
START_TIMEOUT_S = 600.0   # first verilator build of the SoC takes minutes


def start(config_json: str | Path, build_dir: str | Path) -> CosimDriver:
    config_json = Path(config_json).resolve()
    build_dir = Path(build_dir).resolve()
    build_dir.mkdir(parents=True, exist_ok=True)
    uri_file = build_dir / "cosim.uri"
    uri_file.unlink(missing_ok=True)
    log_path = build_dir / "cosim.log"

    proc = subprocess.Popen(
        [sys.executable, "-m", "riscq.sim.server", str(config_json), str(build_dir)],
        stdout=open(log_path, "wb"), stderr=subprocess.STDOUT)

    deadline = time.monotonic() + START_TIMEOUT_S
    while time.monotonic() < deadline:
        if uri_file.exists():
            text = uri_file.read_text().strip()
            if text.startswith("PYRO:"):
                drv = CosimDriver(text)
                drv._proc = proc
                return drv
        if proc.poll() is not None:
            raise RuntimeError(f"cosim process died (exit {proc.returncode}); "
                               f"log tail:\n{_tail(log_path)}")
        time.sleep(0.2)
    proc.kill()
    raise TimeoutError(f"cosim bench not up within {START_TIMEOUT_S}s; log tail:\n{_tail(log_path)}")


def stop(drv: CosimDriver) -> None:
    try:
        drv.sim.shutdown()
    except Exception:
        pass
    drv.close()
    proc = getattr(drv, "_proc", None)
    if proc is not None:
        try:
            proc.wait(timeout=120)
        except subprocess.TimeoutExpired:
            proc.kill()


def _tail(log_path: Path, n: int = 30) -> str:
    if not log_path.exists():
        return "<no log>"
    return "\n".join(log_path.read_text(errors="replace").splitlines()[-n:])


def _sim_main(config_json: Path, build_dir: Path) -> None:
    """Subprocess body: ensure RTL, verilate (cached on RTL hash), run the cocotb bench."""
    from cocotb.runner import get_runner

    rtl_dir = ensure_rtl(config_json, build_dir)
    top_v = rtl_dir / "PulseTableSoc.v"
    sim_build = build_dir / "sim_build"
    sim_build.mkdir(parents=True, exist_ok=True)

    runner = get_runner("verilator")
    stamp = sim_build / ".rtl.sha"
    digest = hashlib.sha256(top_v.read_bytes()).hexdigest()
    if not (stamp.exists() and stamp.read_text() == digest
            and (sim_build / "PulseTableSoc").exists()):
        runner.build(verilog_sources=[top_v], hdl_toplevel="PulseTableSoc",
                     build_dir=sim_build, always=True, build_args=VERILATOR_ARGS)
        stamp.write_text(digest)

    for bin_file in rtl_dir.glob("*.bin"):   # $readmemb paths are relative to the sim cwd
        shutil.copy(bin_file, sim_build)

    # hdl_toplevel_lang must be explicit: when the verilated binary is cached we skip
    # runner.build(), and test()'s language auto-detect reads build()-only attributes.
    runner.test(hdl_toplevel="PulseTableSoc", hdl_toplevel_lang="verilog",
                test_module="riscq.sim.bench", build_dir=sim_build, test_dir=sim_build,
                extra_env={"RISCQ_COSIM_URI_FILE": str(build_dir / "cosim.uri"),
                           "RISCQ_COSIM_CONFIG": str(config_json)})


if __name__ == "__main__":
    _sim_main(Path(sys.argv[1]), Path(sys.argv[2]))
