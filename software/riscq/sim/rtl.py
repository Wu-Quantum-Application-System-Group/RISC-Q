"""Generate the sim RTL (PulseTableSoc.v + $readmemb .bin files) from a SocParams JSON via
mill runMain riscq.soc.GenPulseTableSocJson — only when the config changed."""

from __future__ import annotations

import hashlib
import subprocess
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]   # software/riscq/sim -> repo root


def _rtl_source_digest() -> str:
    """Content hash of the SpinalHDL sources the generator elaborates. Without it the stamp keys on
    the config alone, so an RTL change (a new port, a new register) silently re-uses a stale
    PulseTableSoc.v and the co-sim tests the OLD hardware."""
    h = hashlib.sha256()
    for path in sorted((REPO_ROOT / "src" / "riscq").rglob("*.scala")):
        h.update(path.relative_to(REPO_ROOT).as_posix().encode())
        h.update(path.read_bytes())
    return h.hexdigest()


def ensure_rtl(config_json: str | Path, build_dir: str | Path, force: bool = False) -> Path:
    """Return the RTL directory (build_dir/rtl), regenerating it if the config or the SpinalHDL
    sources changed."""
    config_json = Path(config_json)
    rtl_dir = Path(build_dir) / "rtl"
    stamp = rtl_dir / ".config.sha"
    digest = hashlib.sha256(config_json.read_bytes() + _rtl_source_digest().encode()).hexdigest()

    if (not force and stamp.exists() and stamp.read_text() == digest
            and (rtl_dir / "PulseTableSoc.v").exists()):
        return rtl_dir

    cmd = ["mill", "runMain", "riscq.soc.GenPulseTableSocJson", str(config_json.resolve()),
           str(rtl_dir.resolve())]
    r = subprocess.run(cmd, cwd=REPO_ROOT, capture_output=True, text=True)
    if r.returncode != 0 or not (rtl_dir / "PulseTableSoc.v").exists():
        raise RuntimeError(f"RTL generation failed ({' '.join(cmd)}):\n{r.stdout}\n{r.stderr}")
    stamp.write_text(digest)
    return rtl_dir
