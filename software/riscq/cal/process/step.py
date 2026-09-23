"""The one helper both processes share: run a calibration, snapshot the working Config, apply."""

from __future__ import annotations


def step(cal, drv, results: list, gate: bool = True, apply: bool = True, snapshot_dir=None,
         verbose: bool = False):
    """Run `cal` when `gate` is set; on `ok` snapshot the Config (QICK's two-phase commit, spec 24
    §3.17) and `apply()` the proposal. Appends the Result to `results` and returns it (None when
    gated off)."""
    if not gate:
        return None
    r = cal.run(drv)
    if verbose:
        print(f"  {r.label}: ok={r.ok} proposal={r.proposal}")
    if apply and r.ok:
        if snapshot_dir is not None:
            r.cfg.snapshot(snapshot_dir)
        r.apply()
    results.append(r)
    return r
