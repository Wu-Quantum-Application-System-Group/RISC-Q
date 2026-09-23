"""Config — the parameter tree the calibrations read and update (spec 06; qcal adapter in spec 13 §3).

A nested dict addressed by slash paths: `cfg["readout/0/freq"]` walks `data["readout"]["0"]["freq"]`;
assignment creates the intermediate dicts. Keys are strings (path segments), so a YAML round-trip
preserves them. Minimal on purpose — the calibrations only need get / set / a working copy.

Every value is in PHYSICAL units — Hz, seconds, normalized amplitude in [-1, 1], radians (spec 13 §2);
batches and hardware codes are derived inside a calibration's `run()`, never stored.

`from_qcal` / `save_qcal` are the one-way adapter to the qcal YAML tree (the artefact of record —
`build/qcal-x6y3-config/config.yaml`): `from_qcal` maps it into our paths — GE + EF + readout +
herald + relax, and the whole `two_qubit` section carried verbatim (spec 04 §2) — and `save_qcal`
writes the calibrated fields back into the loaded tree, leaving everything else untouched. Nothing
else in the stack knows qcal's layout.
"""

from __future__ import annotations

import copy
import math

import yaml

from riscq.pulses import units


def _amp_phase(kwargs: dict) -> tuple[float, float, dict]:
    """Split a qcal pulse's kwargs into (amp, phase, envelope kwargs) — `amp`/`phase` are the pulse's
    own slot codes in our model, the rest are the envelope's shape parameters."""
    kw = dict(kwargs or {})
    return float(kw.pop("amp", 1.0)), float(kw.pop("phase", 0.0)), kw


class Config:
    def __init__(self, data: dict | None = None):
        self._data: dict = copy.deepcopy(data) if data else {}
        self._qcal: dict | None = None      # the source qcal tree, when loaded by from_qcal

    @classmethod
    def load(cls, path) -> "Config":
        with open(path) as f:
            return cls(yaml.safe_load(f) or {})

    # ── the qcal tree (spec 13 §3) ──

    @classmethod
    def from_qcal(cls, path) -> "Config":
        """Load a qcal config.yaml into our paths. qcal's X90 is virtualz(vz0) · FAST_DRAG · virtualz(vz1):
        the pair straddling the pulse is the virtual-Z frame `vz` = [before, after] and the FAST_DRAG's
        own axis phase is `x90/phase` — two separate knobs (both nonzero/asymmetric on X6Y3, so neither
        folds into the other). Its X is a SINGLE FAST_DRAG of double duration — not a double-amplitude
        X90 — so both are read as real pulses. Times are seconds, the demod phase DEGREES here, radians there.

        The EF subspace loads like GE (`qubit/{q}/EF/{freq, T1, T2, x90, x}` — the EF X90 carries a
        virtual-Z pair too), and each `two_qubit` pair's subtree is carried VERBATIM under
        `two_qubit/(i, j)/...` (spec 04 §2): pulse lists with string-reference entries, path-valued
        `freq` keys inside vz entries, `dynamical_decoupling` — all opaque, so unknown fields round-trip."""
        with open(path) as f:
            tree = yaml.safe_load(f)
        cfg = cls()
        cfg._qcal = tree
        for q in tree["single_qubit"]:
            ge = tree["single_qubit"][q]["GE"]
            cfg[f"qubit/{q}/freq"] = float(ge["freq"])
            cfg[f"qubit/{q}/T1"] = float(ge["T1"])
            cfg[f"qubit/{q}/T2"] = float(ge["T2*"])
            cfg._read_gate(f"qubit/{q}/x90", ge["X90"]["pulse"])
            cfg._read_gate(f"qubit/{q}/x", ge["X"]["pulse"])

            ef = tree["single_qubit"][q]["EF"]
            cfg[f"qubit/{q}/EF/freq"] = float(ef["freq"])
            cfg[f"qubit/{q}/EF/T1"] = float(ef["T1"])
            cfg[f"qubit/{q}/EF/T2"] = float(ef["T2*"])
            cfg._read_gate(f"qubit/{q}/EF/x90", ef["X90"]["pulse"])
            cfg._read_gate(f"qubit/{q}/EF/x", ef["X"]["pulse"])

            ro = tree["readout"][q]
            _, _, ro_kw = _amp_phase(ro.get("kwargs"))
            cfg[f"readout/{q}/freq"] = float(ro["freq"])
            cfg[f"readout/{q}/amp"] = float(ro["amp"])       # the readout drive power (0.015–0.052 on X6Y3)
            cfg[f"readout/{q}/dur"] = float(ro["time"])      # the DRIVE length
            cfg[f"readout/{q}/env"] = ro["env"]
            cfg[f"readout/{q}/kwargs"] = ro_kw
            dm = ro["demod"]
            dm_amp, _, dm_kw = _amp_phase(dm.get("kwargs"))
            cfg[f"readout/{q}/demod/delay"] = float(dm["delay"])   # ADC round trip: the window opens late
            cfg[f"readout/{q}/demod/phase"] = math.radians(float(dm["phase"]))   # qcal stores degrees
            cfg[f"readout/{q}/demod/dur"] = float(dm["time"])      # the integration WINDOW
            cfg[f"readout/{q}/demod/env"] = dm["env"]
            cfg[f"readout/{q}/demod/amp"] = dm_amp
            cfg[f"readout/{q}/demod/kwargs"] = dm_kw

        for pk, sub in (tree.get("two_qubit") or {}).items():
            cfg[f"two_qubit/{pk}"] = copy.deepcopy(sub)              # verbatim — the pair key has no '/'

        cfg["readout/herald"] = bool(tree["readout"]["herald"])
        cfg["reset/relax"] = float(tree["reset"]["passive"]["delay"])   # 500 us on X6Y3
        return cfg

    def _read_gate(self, path: str, pulses: list) -> None:
        """One qcal gate (a pulse list) → our `{env, dur, amp, phase, kwargs}` (+ `vz`).

        A gate is one drive pulse, optionally bracketed by a virtualz PAIR (qcal's X90 is
        virtualz(p) · FAST_DRAG · virtualz(p); its X is a bare FAST_DRAG). The two phases are
        different knobs and both are carried: `phase` is the drive pulse's own axis phase (our
        Pulse.phase), `vz` the [before, after] frame advance around it. The X6Y3 config uses both —
        q5's FAST_DRAG has a nonzero phase, and q6's virtual-Z pair is NOT equal — so neither may be
        folded into the other. The pair is what Phase calibrates, and what brackets EVERY X90 play in
        every X90 (the compiler's frame bracket, spec 13 §7)."""
        drive = [p for p in pulses if p["env"] != "virtualz"]
        vz = [p for p in pulses if p["env"] == "virtualz"]
        if len(drive) != 1 or len(vz) not in (0, 2):
            raise ValueError(f"{path}: expected one drive pulse and 0 or 2 virtualz, got {pulses}")
        amp, phase, kw = _amp_phase(drive[0]["kwargs"])
        self[f"{path}/env"] = drive[0]["env"]
        self[f"{path}/dur"] = float(drive[0]["time"])
        self[f"{path}/amp"] = amp
        self[f"{path}/phase"] = phase
        self[f"{path}/kwargs"] = kw
        if vz:
            self[f"{path}/vz"] = [float(p["kwargs"]["phase"]) for p in vz]

    def save_qcal(self, path) -> None:
        """Write the CALIBRATED fields back into the qcal tree this Config was loaded from: GE and EF
        freq / T1 / T2*, the X90 and X amp + phase + envelope kwargs + virtual-Z pair (both
        subspaces), the readout freq/amp, the three readout TIMINGS `Window` sweeps (the drive
        length, the demod window and its delay), the demod phase, and each `two_qubit` pair's WHOLE
        subtree — our copy is authoritative (it was carried verbatim on load), so `CZ/pulse`'s
        string-reference entries survive AND config-added keys like JAZZ's `ZZ11` reach the artefact
        (spec 14 F0). Everything outside those paths — the `reset` section, `readout/esp`, `hardware`
        — round-trips untouched, so an uncalibrated load → save is verbatim."""
        if self._qcal is None:
            raise RuntimeError("save_qcal needs a Config loaded by from_qcal")
        tree = copy.deepcopy(self._qcal)
        for q in tree["single_qubit"]:
            ge = tree["single_qubit"][q]["GE"]
            ge["freq"] = float(self[f"qubit/{q}/freq"])
            ge["T1"] = float(self[f"qubit/{q}/T1"])
            ge["T2*"] = float(self[f"qubit/{q}/T2"])
            for gate, name in ((ge["X90"], "x90"), (ge["X"], "x")):
                self._write_gate(f"qubit/{q}/{name}", gate["pulse"])
            ef = tree["single_qubit"][q]["EF"]
            ef["freq"] = float(self[f"qubit/{q}/EF/freq"])
            ef["T1"] = float(self[f"qubit/{q}/EF/T1"])
            ef["T2*"] = float(self[f"qubit/{q}/EF/T2"])
            for gate, name in ((ef["X90"], "EF/x90"), (ef["X"], "EF/x")):
                self._write_gate(f"qubit/{q}/{name}", gate["pulse"])
            ro = tree["readout"][q]
            ro["freq"] = float(self[f"readout/{q}/freq"])
            ro["amp"] = float(self[f"readout/{q}/amp"])
            ro["demod"]["time"] = float(self[f"readout/{q}/demod/dur"])
            # the two other readout TIMINGS `Window` calibrates (spec 20 U1): the drive length and
            # when the integration window opens after it. Seconds on both sides — no unit
            # conversion, so an untouched tree still round-trips verbatim.
            ro["time"] = float(self[f"readout/{q}/dur"])
            ro["demod"]["delay"] = float(self[f"readout/{q}/demod/delay"])
            # degrees↔radians is not float-exact both ways — rewrite only a RECALIBRATED phase, so an
            # untouched one stays verbatim (the load → save round-trip contract).
            if float(self[f"readout/{q}/demod/phase"]) != math.radians(float(ro["demod"]["phase"])):
                ro["demod"]["phase"] = math.degrees(float(self[f"readout/{q}/demod/phase"]))
        for pk in (tree.get("two_qubit") or {}):
            tree["two_qubit"][pk] = copy.deepcopy(self[f"two_qubit/{pk}"])
        with open(path, "w") as f:
            yaml.safe_dump(tree, f, default_flow_style=False, sort_keys=False)

    def _write_gate(self, path: str, pulses: list) -> None:
        """The inverse of _read_gate: the drive pulse's amp + phase + envelope kwargs (the FAST_DRAG
        `N` / `weights` / `alpha` / `anh` the DRAG optimizer tunes) and, in order, the virtual-Z pair.

        A calibrated pair on a gate that had NONE (qcal's X is a bare FAST_DRAG, but `Phase(gate='X')`
        calibrates its frame too — spec 14 §3.3) is written as two new virtualz entries bracketing the
        drive, shaped like the X90's. An all-zero pair is a no-op frame, so it is not inserted — a
        load → save with nothing calibrated stays verbatim."""
        vz = [float(v) for v in self.get(f"{path}/vz", [])]
        if any(vz) and not any(p["env"] == "virtualz" for p in pulses):
            drive = next(p for p in pulses if p["env"] != "virtualz")
            blank = {"channel": drive["channel"], "env": "virtualz", "time": 0.0,
                     "kwargs": {"phase": 0.0}}
            pulses.insert(0, copy.deepcopy(blank))
            pulses.append(copy.deepcopy(blank))
        it = iter(vz)
        for p in pulses:
            if p["env"] == "virtualz":
                p["kwargs"]["phase"] = float(next(it))
            else:
                p["kwargs"].update(self.get(f"{path}/kwargs", {}),
                                   amp=float(self[f"{path}/amp"]),
                                   phase=float(self[f"{path}/phase"]))

    def check_hardware(self, params) -> None:
        """Assert the qcal tree's `hardware` section describes THIS build (spec 13 §3): the converter
        rates and interpolation ratios are fixed by elaboration, so they are checked, never imported."""
        if self._qcal is None:
            raise RuntimeError("check_hardware needs a Config loaded by from_qcal")
        hw = self._qcal["hardware"]
        # the rates are the CHANNELS' own (their lanes per batch), resolved by name off the spec
        cs = params.core(0)
        want = {"DAC": units.channel_rate(params, cs.channel("gate")),     # 16 lanes/batch
                "ADC": units.channel_rate(params, cs.channel("demod"))}    # 4 lanes/batch
        for k, v in want.items():
            if float(hw["sample_rate"][k]) != v:
                raise ValueError(f"qcal {k} sample rate {hw['sample_rate'][k]} != build's {v}")
        # the interp factors likewise: the uniform views name the gate/ro/demod channels on the
        # spec and assert every core agrees
        for k, v in (("qdrv", params.gate_interp), ("rdrv", params.readout_interp),
                     ("rdlo", params.demod_interp)):
            if int(hw["interpolation_ratio"][k]) != v:
                raise ValueError(f"qcal {k} interpolation {hw['interpolation_ratio'][k]} != build's {v}")

    def save(self, path) -> None:
        with open(path, "w") as f:
            yaml.safe_dump(self._data, f, default_flow_style=False, sort_keys=True)

    def snapshot(self, directory) -> str:
        """QICK's two-phase commit reduced to one helper (spec 24 §3.17): copy the working tree
        to `<directory>/old_config_<timestamp>.yaml` before an `apply()`. Returns the path."""
        import datetime
        import os
        os.makedirs(directory, exist_ok=True)
        stamp = datetime.datetime.now().strftime("%Y%m%dT%H%M%S")
        path = os.path.join(directory, f"old_config_{stamp}.yaml")
        self.save(path)
        return path

    def copy(self) -> "Config":
        dup = Config(self._data)
        dup._qcal = copy.deepcopy(self._qcal)     # a copy of a qcal config can still save_qcal
        return dup

    def to_dict(self) -> dict:
        return copy.deepcopy(self._data)

    @staticmethod
    def _keys(path) -> list[str]:
        keys = [k for k in str(path).split("/") if k != ""]
        if not keys:
            raise KeyError(f"empty config path {path!r}")
        return keys

    def __getitem__(self, path):
        node = self._data
        for k in self._keys(path):
            if not isinstance(node, dict) or k not in node:
                raise KeyError(f"no config entry {path!r} (missing {k!r})")
            node = node[k]
        return node

    def __setitem__(self, path, value) -> None:
        keys = self._keys(path)
        node = self._data
        for k in keys[:-1]:
            if not isinstance(node.get(k), dict):
                node[k] = {}
            node = node[k]
        node[keys[-1]] = value

    def __contains__(self, path) -> bool:
        try:
            self[path]
            return True
        except KeyError:
            return False

    def get(self, path, default=None):
        try:
            return self[path]
        except KeyError:
            return default
