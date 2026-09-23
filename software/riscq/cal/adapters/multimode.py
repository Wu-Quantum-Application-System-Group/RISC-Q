"""The QICK multimode station's artefacts → Config (spec 24 §4.5; qick-calibration/specs/03):
the hardware YAML (per-qubit lists in MHz / µs / DAC units / degrees), the multiphoton YAML (the
transition table) and the swap CSV (the storage-mode dataset). `from_multimode` converts once
into physical units; `save_multimode` writes the calibrated fields back into copies of the same
files, leaving everything else untouched. Fields with no image on riscq (`threshold`, `Ie/Ig`,
`threshold_list`, the polynomial gain columns) are carried opaquely."""

from __future__ import annotations

import copy
import csv
import math

import yaml

from riscq.cal.config import Config

GAIN_MAX = 32766.0          # QICK DAC units full scale (spec 24 §1)
MHZ, US = 1e6, 1e-6


def _first(v):
    return v[0] if isinstance(v, list) else v


def _set_first(d, key, value):
    if isinstance(d.get(key), list):
        d[key][0] = value
    else:
        d[key] = value


def from_multimode(hardware_yaml, multiphoton_yaml=None, swap_csv=None, q: int = 0) -> Config:
    """Load the station's three files into our paths (spec 24 §4.5). The transmon is qubit `q`;
    the drive lines are named as the HAL's logical channels (`f0g1`, `flux_low`, `flux_high`,
    `manipulate`), their `lines/<name>/core` defaulting to `q`."""
    with open(hardware_yaml) as f:
        hw = yaml.safe_load(f)
    dev = hw["device"]
    cfg = Config()
    cfg._multimode = {"hardware": hw, "multiphoton": None, "swap": None, "q": q}
    qb, ro = dev["qubit"], dev["readout"]
    cfg[f"qubit/{q}/freq"] = float(_first(qb["f_ge"])) * MHZ
    cfg[f"qubit/{q}/EF/freq"] = float(_first(qb["f_ef"])) * MHZ
    cfg[f"qubit/{q}/T1"] = float(_first(qb["T1"])) * US
    cfg[f"qubit/{q}/EF/T1"] = float(_first(qb.get("T1_ef", qb["T1"]))) * US
    pulses = qb["pulses"]
    for ours, theirs, sub in (("x", "pi_ge", ""), ("x90", "hpi_ge", ""), ("EF/x", "pi_ef", "EF/"),
                              ("EF/x90", "hpi_ef", "EF/")):
        p = pulses[theirs]
        sigma = float(_first(p["sigma"])) * US
        path = f"qubit/{q}/{ours}"
        cfg[f"{path}/env"] = "gaussian"
        cfg[f"{path}/dur"] = 4 * sigma                      # QICK's `gauss` is 4σ long
        cfg[f"{path}/amp"] = float(_first(p["gain"])) / GAIN_MAX
        cfg[f"{path}/phase"] = 0.0
        cfg[f"{path}/kwargs"] = {"sigmas": 4.0}
    cfg[f"readout/{q}/freq"] = float(_first(ro["frequency"])) * MHZ
    cfg[f"readout/{q}/amp"] = float(_first(ro["gain"])) / GAIN_MAX
    cfg[f"readout/{q}/dur"] = float(_first(ro["readout_length"])) * US
    cfg[f"readout/{q}/demod/dur"] = float(_first(ro["readout_length"])) * US
    cfg[f"readout/{q}/demod/phase"] = math.radians(float(_first(ro.get("phase", 0.0))))
    cfg[f"readout/{q}/demod/delay"] = float(_first(ro.get("adc_trig_offset", 0.0))) * US
    cfg["reset/relax"] = float(_first(ro.get("relax_delay", 100.0))) * US
    for name in ("f0g1", "flux_low", "flux_high", "manipulate"):
        cfg[f"lines/{name}/core"] = q
    man = dev.get("manipulate", {})
    for k, key in (("chi_ge", "chi_ge"), ("chi_ef", "chi_ef")):
        for i, v in enumerate(man.get(key, []) or []):
            cfg[f"mode/M{i + 1}/{k}"] = float(v) * MHZ
    ramp = float(dev.get("storage", {}).get("ramp_sigma", 0.005)) * US
    if multiphoton_yaml:
        with open(multiphoton_yaml) as f:
            mp = yaml.safe_load(f)
        cfg._multimode["multiphoton"] = mp
        for kind in ("pi", "hpi"):
            for name, t in (mp.get(kind) or {}).items():
                ours = "f0-g1" if name == "fn-gn+1" else name
                line = {"g0-e0": "qubit", "e0-f0": "qubit"}.get(ours, "f0g1")
                cfg[f"transition/{ours}/line"] = line
                cfg[f"transition/{ours}/freq"] = float(_first(t["frequency"])) * MHZ
                cfg[f"transition/{ours}/{kind}"] = {
                    "env": "flat_top" if line != "qubit" else "gaussian",
                    "dur": float(_first(t["length"])) * US,
                    "amp": float(_first(t["gain"])) / GAIN_MAX, "phase": 0.0,
                    "kwargs": {"ramp": ramp} if line != "qubit" else {"sigmas": 4.0}}
    if swap_csv:
        with open(swap_csv) as f:
            rows = list(csv.DictReader(f))
        cfg._multimode["swap"] = rows
        for row in rows:
            name = row.get("name") or row.get("mode") or row.get("")
            f_hz = float(row["freq"]) * MHZ
            cfg[f"mode/{name}/line"] = "f0g1" if "-" not in name else ("flux_low" if f_hz < 1e9 else "flux_high")
            cfg[f"mode/{name}/freq"] = f_hz
            cfg[f"mode/{name}/amp"] = float(row["gain"]) / GAIN_MAX
            cfg[f"mode/{name}/pi"] = float(row["pi"]) * US
            cfg[f"mode/{name}/hpi"] = float(row["h_pi"]) * US
            cfg[f"mode/{name}/ramp"] = ramp
            if row.get("last_update"):
                cfg[f"mode/{name}/updated"] = row["last_update"]
    return cfg


def save_multimode(cfg: Config, hardware_yaml, multiphoton_yaml=None, swap_csv=None) -> None:
    """Write the calibrated fields back into copies of the loaded files (the byte-preserving
    spirit of `save_qcal`: only what a calibration wrote changes)."""
    src = getattr(cfg, "_multimode", None)
    if src is None:
        raise RuntimeError("save_multimode needs a Config loaded by from_multimode")
    q = src["q"]
    hw = copy.deepcopy(src["hardware"])
    qb, ro = hw["device"]["qubit"], hw["device"]["readout"]
    _set_first(qb, "f_ge", cfg[f"qubit/{q}/freq"] / MHZ)
    _set_first(qb, "f_ef", cfg[f"qubit/{q}/EF/freq"] / MHZ)
    _set_first(qb, "T1", cfg[f"qubit/{q}/T1"] / US)
    if "T1_ef" in qb:
        _set_first(qb, "T1_ef", cfg[f"qubit/{q}/EF/T1"] / US)
    for ours, theirs in (("x", "pi_ge"), ("x90", "hpi_ge"), ("EF/x", "pi_ef"), ("EF/x90", "hpi_ef")):
        _set_first(qb["pulses"][theirs], "gain", round(cfg[f"qubit/{q}/{ours}/amp"] * GAIN_MAX))
    _set_first(ro, "frequency", cfg[f"readout/{q}/freq"] / MHZ)
    _set_first(ro, "gain", round(cfg[f"readout/{q}/amp"] * GAIN_MAX))
    _set_first(ro, "readout_length", cfg[f"readout/{q}/demod/dur"] / US)
    if "phase" in ro:
        _set_first(ro, "phase", math.degrees(cfg[f"readout/{q}/demod/phase"]))
    man = hw["device"].setdefault("manipulate", {})
    for key in ("chi_ge", "chi_ef"):
        vals = list(man.get(key, []) or [])
        i = 0
        while f"mode/M{i + 1}/{key}" in cfg:
            v = cfg[f"mode/M{i + 1}/{key}"] / MHZ
            if i < len(vals):
                vals[i] = v
            else:
                vals.append(v)
            i += 1
        if vals:
            man[key] = vals
    with open(hardware_yaml, "w") as f:
        yaml.safe_dump(hw, f, default_flow_style=False, sort_keys=False)
    if multiphoton_yaml and src["multiphoton"] is not None:
        mp = copy.deepcopy(src["multiphoton"])
        for kind in ("pi", "hpi"):
            for name, t in (mp.get(kind) or {}).items():
                ours = "f0-g1" if name == "fn-gn+1" else name
                path = f"transition/{ours}/{kind}"
                if path in cfg:
                    _set_first(t, "frequency", cfg[f"transition/{ours}/freq"] / MHZ)
                    _set_first(t, "gain", round(cfg[f"{path}/amp"] * GAIN_MAX))
                    _set_first(t, "length", cfg[f"{path}/dur"] / US)
        with open(multiphoton_yaml, "w") as f:
            yaml.safe_dump(mp, f, default_flow_style=False, sort_keys=False)
    if swap_csv and src["swap"] is not None:
        rows = copy.deepcopy(src["swap"])
        for row in rows:
            name = row.get("name") or row.get("mode") or row.get("")
            if f"mode/{name}/freq" in cfg:
                row["freq"] = repr(cfg[f"mode/{name}/freq"] / MHZ)
                row["gain"] = repr(round(cfg[f"mode/{name}/amp"] * GAIN_MAX))
                row["pi"] = repr(cfg[f"mode/{name}/pi"] / US)
                row["h_pi"] = repr(cfg[f"mode/{name}/hpi"] / US)
                if f"mode/{name}/updated" in cfg:
                    row["last_update"] = cfg[f"mode/{name}/updated"]
        with open(swap_csv, "w", newline="") as f:
            w = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
            w.writeheader()
            w.writerows(rows)
