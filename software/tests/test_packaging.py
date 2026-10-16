"""Import hygiene — the board-side module set imports with ONLY numpy + Pyro5
(+ serpent, Pyro5's own dependency) installed. The subprocess runs `python -S` (no site-packages)
with a fake site dir holding just the allowed distributions, so any stray scipy/cocotb/pynq
import in the chain fails loud. Proves the riscq wheel runs on the offline PYNQ image."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

SW_ROOT = Path(__file__).resolve().parents[1]

# the board-import surface (riscq.board.pynq_driver is the one exception: it
# imports pynq at module top and only ever runs on the board)
BOARD_MODULES = ["riscq", "riscq.map", "riscq.build", "riscq.run",
                 "riscq.driver", "riscq.driver.cosim", "riscq.driver.remote",
                 "riscq.board", "riscq.board.server"]

ALLOWED_DISTS = ["numpy", "Pyro5", "serpent"]


def _stripped_site(tmp_path: Path) -> Path:
    """A site dir with symlinks to ONLY the allowed distributions (+ numpy's .libs)."""
    site = tmp_path / "site"
    site.mkdir()
    for name in ALLOWED_DISTS:
        p = Path(__import__(name).__file__)
        if p.name == "__init__.py":
            (site / p.parent.name).symlink_to(p.parent)
            libs = p.parent.parent / (p.parent.name + ".libs")
            if libs.exists():
                (site / libs.name).symlink_to(libs)
        else:
            (site / p.name).symlink_to(p)
    return site


def _run_import(site: Path, tmp_path: Path, statement: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, "-S", "-c", statement],
                          env={"PYTHONPATH": f"{site}:{SW_ROOT}"},
                          cwd=tmp_path, capture_output=True, text=True)


def test_board_modules_import_with_numpy_pyro_only(tmp_path):
    site = _stripped_site(tmp_path)
    # control: the stripped env must genuinely lack everything else, or this test proves nothing
    ctrl = _run_import(site, tmp_path, "import scipy")
    assert ctrl.returncode != 0, "stripped env leaked the real site-packages (scipy importable)"

    proc = _run_import(site, tmp_path, "import " + ", ".join(BOARD_MODULES))
    assert proc.returncode == 0, \
        f"board modules must import with numpy+Pyro5 only:\n{proc.stderr}"
