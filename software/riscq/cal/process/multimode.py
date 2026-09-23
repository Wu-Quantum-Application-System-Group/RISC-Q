"""The QICK multimode autocal (spec 24 §1, §4.8; qick-calibration/specs/05): the notebook's stage
order on the universal classes, gated by an `expts_to_run` dict (the operator's run plan), the
per-mode loops, "Ramsey after Rabi" and "χ before man_reset". Every stage is one `step`."""

from __future__ import annotations

from riscq.cal.cals import (Amplitude, Frequency, ReadoutCalibration, ReadoutFidelity, Resonator,
                            Spectroscopy, T1)
from riscq.cal.cals.multimode import PREP_M, PREP_f, READ_M, Chevron, ErrorAmplification, LengthRabi, chi
from riscq.cal.process.step import step

DEFAULT_RUN = {
    "resonator_spectroscopy": False, "single_shot": True, "pulse_probe_ge": True, "ramsey_ge": True,
    "amplitude_rabi_ge": True, "t1_ge": True, "pulse_probe_ef": True, "ramsey_ef": True,
    "amplitude_rabi_ef": True, "t1_ef": True, "f0g1_spectroscopy": True, "f0g1_chevron": True,
    "f0g1_error_amp": True, "f0g1_length_rabi": True, "chi": True, "storage_spectroscopy": True,
    "sideband_chevron": True, "sideband_error_amp": True, "man_modes": ["M1"], "stor_modes": [],
}


def autocal_multimode(cfg, q, drv, expts_to_run=None, snapshot_dir=None, verbose=False, **sizes):
    """Run the process for qubit `q`. `sizes` override per-stage knobs by stage name (a dict of
    constructor kwargs). Returns the list of Results."""
    run = {**DEFAULT_RUN, **(expts_to_run or {})}
    kw = lambda name, **d: {**d, **sizes.get(name, {})}   # noqa: E731
    results = []
    go = lambda name, cal: step(cal, drv, results, run.get(name, True), True, snapshot_dir, verbose)  # noqa: E731

    if run["resonator_spectroscopy"] and "resonator_spectroscopy" in sizes:
        go("resonator_spectroscopy", Resonator(cfg, q, **sizes["resonator_spectroscopy"]))
    go("single_shot", ReadoutCalibration(cfg, q, **kw("single_shot", shots=24)))
    go("single_shot", ReadoutFidelity(cfg, q, **kw("single_shot", shots=24)))

    go("pulse_probe_ge", Spectroscopy(cfg, q, **kw("pulse_probe_ge")))
    go("ramsey_ge", Frequency(cfg, q, **kw("ramsey_ge")))
    go("amplitude_rabi_ge", Amplitude(cfg, q, gate="X", derive_hpi=True, **kw("amplitude_rabi_ge")))
    go("ramsey_ge", Frequency(cfg, q, **kw("ramsey_ge")))                 # again: the AC-Stark rule
    go("t1_ge", T1(cfg, q, gate="X", **kw("t1_ge")))

    go("pulse_probe_ef", Spectroscopy(cfg, q, prep=("x",), unprep=("x",), target="EF/freq",
                                      readout="mapback", **kw("pulse_probe_ef")))
    go("ramsey_ef", Frequency(cfg, q, gate="EF/X90", prep=("x",), unprep=("x",), readout="mapback",
                              **kw("ramsey_ef")))
    go("amplitude_rabi_ef", Amplitude(cfg, q, gate="EF/X", prep=("x",), unprep=("x",), readout="mapback",
                                      derive_hpi=True, **kw("amplitude_rabi_ef")))
    go("ramsey_ef", Frequency(cfg, q, gate="EF/X90", prep=("x",), unprep=("x",), readout="mapback",
                              **kw("ramsey_ef")))
    go("t1_ef", T1(cfg, q, gate="X", prep=("EF/x",), unprep=("x",), readout="mapback", **kw("t1_ef")))

    for mode in run["man_modes"]:
        g = f"mode/{mode}/pi"
        go("f0g1_spectroscopy", Spectroscopy(cfg, q, line=cfg.get(f"mode/{mode}/line", "f0g1"),
                                             target=f"mode/{mode}/freq", prep=PREP_f, **kw("f0g1_spectroscopy")))
        go("f0g1_chevron", Chevron(cfg, q, g, prep=PREP_f, unprep=("EF/x",), **kw("f0g1_chevron")))
        go("f0g1_error_amp", ErrorAmplification(cfg, q, g, knob="freq", prep=PREP_f, unprep=("EF/x",),
                                                **kw("f0g1_error_amp")))
        go("f0g1_length_rabi", LengthRabi(cfg, q, g, prep=PREP_f, unprep=("x",), **kw("f0g1_length_rabi")))
        if run["chi"]:
            for gate in ("X90", "EF/X90"):
                r, _ = chi(cfg, q, mode, drv, gate=gate, **sizes.get("chi", {}))
                results.append(r)
                if r.ok:
                    r.apply()
        for s in run["stor_modes"]:
            name = f"{mode}-{s}"
            gs = f"mode/{name}/pi"
            go("storage_spectroscopy", Spectroscopy(cfg, q, line=cfg[f"mode/{name}/line"],
                                                    target=f"mode/{name}/freq", prep=PREP_M, unprep=READ_M,
                                                    readout="mapback", **kw("storage_spectroscopy")))
            go("sideband_chevron", Chevron(cfg, q, gs, prep=PREP_M, unprep=READ_M, **kw("sideband_chevron")))
            for knob in ("freq", "amp"):
                go("sideband_error_amp", ErrorAmplification(cfg, q, gs, knob=knob, prep=PREP_M,
                                                            unprep=READ_M, **kw("sideband_error_amp")))
    return results
