"""Host unit tests for riscq.cal.Config: slash-path get/set/missing, YAML round-trip, deep copy, and
the qcal adapter against the real X6Y3 config.yaml — every qubit's gate/readout parameter
round-trips, the frequency codes are in range for the build's SocParams, `hardware/*` matches it, and
save_qcal re-emits a tree that still loads (with everything it does not calibrate untouched)."""

import math
from pathlib import Path

import pytest
import yaml

from riscq.cal import Config
from riscq.map import SocParams
from riscq.pulses import envelopes, units

SW_ROOT = Path(__file__).resolve().parents[1]
QCAL_YAML = SW_ROOT.parent / "build" / "qcal-x6y3-config" / "config.yaml"   # the artefact of record
X6Y3_YAML = SW_ROOT.parent / "examples" / "cal-config-x6y3.yaml"            # its tracked twin
PARAMS = SocParams.load(SW_ROOT / "configs" / "zcu216-14q.json")            # the X6Y3-class build


def test_get_set_slash_paths():
    cfg = Config()
    cfg["readout/0/freq"] = 5_000_000
    cfg["readout/0/amp"] = 0.3
    cfg["qubit/2/amp"] = 0.9
    assert cfg["readout/0/freq"] == 5_000_000
    assert cfg["readout/0/amp"] == 0.3
    assert cfg["qubit/2/amp"] == 0.9
    # intermediate nodes were created as nested dicts
    assert cfg["readout/0"] == {"freq": 5_000_000, "amp": 0.3}


def test_missing_key_raises_and_contains_get():
    cfg = Config({"readout": {"0": {"freq": 1}}})
    assert "readout/0/freq" in cfg
    assert "readout/1/freq" not in cfg
    assert cfg.get("readout/1/freq", 42) == 42
    with pytest.raises(KeyError):
        cfg["readout/1/freq"]
    with pytest.raises(KeyError):
        cfg[""]


def test_overwrite_scalar_with_subtree():
    cfg = Config()
    cfg["a/b"] = 1
    cfg["a/b/c"] = 2                     # b was a scalar; setting deeper replaces it with a dict
    assert cfg["a/b/c"] == 2


def test_yaml_round_trip(tmp_path):
    cfg = Config()
    cfg["readout/0/freq"] = 5_000_000
    cfg["qubit/0/x90/amp"] = 0.42
    cfg["qubit/0/x90/phase"] = 0.0
    path = tmp_path / "cfg.yaml"
    cfg.save(path)
    back = Config.load(path)
    assert back.to_dict() == cfg.to_dict()
    assert back["qubit/0/x90/amp"] == 0.42


def test_copy_is_deep():
    cfg = Config({"a": {"b": 1}})
    dup = cfg.copy()
    dup["a/b"] = 99
    assert cfg["a/b"] == 1               # original untouched
    assert dup["a/b"] == 99


# ── the qcal adapter, on the real X6Y3 config ──

@pytest.fixture(scope="module")
def qcal_tree():
    if not QCAL_YAML.exists():
        pytest.skip(f"the X6Y3 reference config is not present ({QCAL_YAML})")
    with open(QCAL_YAML) as f:
        return yaml.safe_load(f)


@pytest.fixture(scope="module")
def qcal_cfg(qcal_tree):
    return Config.from_qcal(QCAL_YAML)


def test_from_qcal_round_trips_every_qubit(qcal_cfg, qcal_tree):
    """Every gate/readout parameter of all 8 qubits maps into our paths — the X90 is the FAST_DRAG
    slot (its own axis `phase`) bracketed by the virtual-Z pair (`vz`), the X a REAL pulse (double
    length, same amp — not 2x the X90 amplitude), and the demod phase is degrees in qcal, radians
    here."""
    qubits = list(qcal_tree["single_qubit"])
    assert len(qubits) == 8
    for q in qubits:
        ge = qcal_tree["single_qubit"][q]["GE"]
        assert qcal_cfg[f"qubit/{q}/freq"] == ge["freq"]
        assert qcal_cfg[f"qubit/{q}/T1"] == ge["T1"]
        assert qcal_cfg[f"qubit/{q}/T2"] == ge["T2*"]

        x90, x = [p for p in ge["X90"]["pulse"] if p["env"] != "virtualz"][0], ge["X"]["pulse"][0]
        vz = [p for p in ge["X90"]["pulse"] if p["env"] == "virtualz"]
        assert qcal_cfg[f"qubit/{q}/x90/env"] == x90["env"] == "FAST_DRAG"
        assert qcal_cfg[f"qubit/{q}/x90/dur"] == x90["time"] == 35e-9
        assert qcal_cfg[f"qubit/{q}/x90/amp"] == x90["kwargs"]["amp"]
        assert qcal_cfg[f"qubit/{q}/x90/phase"] == x90["kwargs"]["phase"]        # the pulse's own axis
        assert qcal_cfg[f"qubit/{q}/x90/vz"] == [p["kwargs"]["phase"] for p in vz]   # the frame pair
        assert qcal_cfg[f"qubit/{q}/x90/kwargs"] == {k: v for k, v in x90["kwargs"].items()
                                                     if k not in ("amp", "phase")}
        assert qcal_cfg[f"qubit/{q}/x/dur"] == x["time"] == 70e-9                # DOUBLE LENGTH
        assert qcal_cfg[f"qubit/{q}/x/amp"] == x["kwargs"]["amp"]
        assert qcal_cfg[f"qubit/{q}/x/amp"] < 2 * qcal_cfg[f"qubit/{q}/x90/amp"]  # NOT double amplitude
        assert qcal_cfg[f"qubit/{q}/x/phase"] == x["kwargs"]["phase"] == 0.0
        assert f"qubit/{q}/x/vz" not in qcal_cfg                                 # a bare pulse, no frame

        ro = qcal_tree["readout"][q]
        assert qcal_cfg[f"readout/{q}/freq"] == ro["freq"]
        assert qcal_cfg[f"readout/{q}/amp"] == ro["amp"] and 0.01 < ro["amp"] < 0.06
        assert qcal_cfg[f"readout/{q}/dur"] == ro["time"]
        assert qcal_cfg[f"readout/{q}/env"] == ro["env"] == "cosine_square"
        assert qcal_cfg[f"readout/{q}/kwargs"] == ro["kwargs"]
        assert qcal_cfg[f"readout/{q}/demod/dur"] == ro["demod"]["time"]
        assert qcal_cfg[f"readout/{q}/demod/delay"] == ro["demod"]["delay"]
        assert qcal_cfg[f"readout/{q}/demod/phase"] == pytest.approx(
            math.radians(ro["demod"]["phase"]))                                  # degrees → radians
        assert qcal_cfg[f"readout/{q}/demod/env"] == ro["demod"]["env"]
        assert qcal_cfg[f"readout/{q}/demod/amp"] == ro["demod"]["kwargs"]["amp"]

    assert qcal_cfg["readout/herald"] is True
    assert qcal_cfg["reset/relax"] == qcal_tree["reset"]["passive"]["delay"] == 500e-6

    # the two phase knobs are NOT interchangeable in the real artefact: q5's FAST_DRAG carries its own
    # axis phase on top of the frame pair, and q6's virtual-Z pair is unequal (a per-qubit fine pass)
    assert qcal_cfg["qubit/5/x90/phase"] != 0.0
    assert qcal_cfg["qubit/6/x90/vz"][0] != qcal_cfg["qubit/6/x90/vz"][1]


def test_from_qcal_codes_in_range(qcal_cfg, qcal_tree):
    """Every X6Y3 tone lands on a legal 16-bit code for this build: the 5.4–5.5 GHz drives and the
    6.5–6.8 GHz readouts alias below the 8 GS/s DAC's Nyquist, and each demod code is 4x its drive
    code folded — the pulses/units contract, exercised on the real frequencies."""
    for q in qcal_tree["single_qubit"]:
        for path in (f"qubit/{q}/freq", f"readout/{q}/freq"):
            code = units._freq_code(qcal_cfg[path], PARAMS)
            assert -(1 << 15) <= code < (1 << 15)
        demod = units._demod_code(qcal_cfg[f"readout/{q}/freq"], PARAMS)
        assert -(1 << 15) <= demod < (1 << 15)
        # the durations are non-degenerate on this build's 2 ns batch
        assert units.batches(qcal_cfg[f"qubit/{q}/x90/dur"] * 1e9, PARAMS) > 0
        assert units.batches(qcal_cfg[f"readout/{q}/demod/dur"] * 1e9, PARAMS) > 0


def test_from_qcal_hardware_matches_socparams(qcal_cfg):
    """hardware/* is CHECKED against the build, never imported: DAC 8 GS/s, ADC 2 GS/s, qdrv/rdrv/rdlo
    interpolation 4/16/4."""
    qcal_cfg.check_hardware(PARAMS)                    # the real X6Y3 tree matches zcu216-14q
    bad = qcal_cfg.copy()
    bad._qcal["hardware"]["sample_rate"]["DAC"] = 4e9
    with pytest.raises(ValueError, match="DAC sample rate"):
        bad.check_hardware(PARAMS)
    bad = qcal_cfg.copy()
    bad._qcal["hardware"]["interpolation_ratio"]["rdlo"] = 2
    with pytest.raises(ValueError, match="rdlo interpolation"):
        bad.check_hardware(PARAMS)


def test_save_qcal_writes_back_only_the_calibrated_fields(qcal_cfg, qcal_tree, tmp_path):
    """save_qcal re-emits a tree that still loads: the calibrated fields carry the new values (the
    X90's amp + its virtual-Z PAIR, the X's amp, GE freq/T1/T2*, the readout freq/amp, the demod
    window + its phase back in DEGREES) and everything else round-trips as-loaded — reset pulses
    untouched, EF/two_qubit written back with their unchanged values."""
    cal = qcal_cfg.copy()
    cal["qubit/3/freq"] = 5.4321e9
    cal["qubit/3/x90/amp"] = 0.123
    cal["qubit/3/x90/phase"] = 0.05
    cal["qubit/3/x90/vz"] = [0.25, 0.25]
    cal["qubit/3/x/amp"] = 0.234
    cal["qubit/3/T1"] = 6.6e-5
    cal["readout/3/freq"] = 6.7e9
    cal["readout/3/amp"] = 0.0345
    cal["readout/3/demod/dur"] = 8e-7
    cal["readout/3/demod/phase"] = math.radians(-30.0)

    path = tmp_path / "config.yaml"
    cal.save_qcal(path)
    with open(path) as f:
        out = yaml.safe_load(f)

    ge = out["single_qubit"][3]["GE"]
    assert ge["freq"] == 5.4321e9 and ge["T1"] == 6.6e-5
    assert [p["kwargs"]["phase"] for p in ge["X90"]["pulse"] if p["env"] == "virtualz"] == [0.25, 0.25]
    drive = [p for p in ge["X90"]["pulse"] if p["env"] != "virtualz"]
    assert [p["kwargs"]["amp"] for p in drive] == [0.123]
    assert [p["kwargs"]["phase"] for p in drive] == [0.05]
    assert ge["X"]["pulse"][0]["kwargs"]["amp"] == 0.234
    assert out["readout"][3]["freq"] == 6.7e9 and out["readout"][3]["amp"] == 0.0345
    assert out["readout"][3]["demod"]["time"] == 8e-7
    assert out["readout"][3]["demod"]["phase"] == pytest.approx(-30.0)          # radians → degrees

    # untouched subtrees round-trip bit-for-bit
    assert out["two_qubit"] == qcal_tree["two_qubit"]
    assert out["reset"] == qcal_tree["reset"]
    assert out["hardware"] == qcal_tree["hardware"]
    assert out["single_qubit"][3]["EF"] == qcal_tree["single_qubit"][3]["EF"]
    assert out["single_qubit"][0] == qcal_tree["single_qubit"][0]               # q0 was not calibrated

    # and the emitted tree loads straight back into a Config with the same values
    back = Config.from_qcal(path)
    assert back["qubit/3/x90/amp"] == 0.123
    assert back["readout/3/demod/phase"] == pytest.approx(math.radians(-30.0))
    assert back.to_dict() == cal.to_dict()


def test_from_qcal_save_qcal_uncalibrated_is_verbatim(tmp_path):
    """Load → touch nothing → save == input as PARSED TREES: EF and the whole `two_qubit`
    section now round-trip THROUGH the adapter, not around it — string-reference pulse entries,
    path-valued vz `freq` keys, `dynamical_decoupling`, all opaque — and the degrees-valued demod
    phase stays verbatim unless recalibrated (degrees↔radians is not float-exact both ways: q4's
    31.16847° would drift in the last ulp)."""
    cfg = Config.from_qcal(X6Y3_YAML)
    out = tmp_path / "config.yaml"
    cfg.save_qcal(out)
    with open(X6Y3_YAML) as f:
        a = yaml.safe_load(f)
    with open(out) as f:
        b = yaml.safe_load(f)
    assert b == a


def test_save_qcal_persists_the_window_timings(tmp_path):
    """`from_qcal` reads the readout DRIVE LENGTH (`readout[q].time`) and the demod
    DELAY, and `Window(knob='dur'|'demod/delay')` proposes both — but `save_qcal` used to write back
    only the demod window, so those two proposals were lost the moment the session was persisted.

    Move them on one qubit, save, reload: both must come back. The untouched-tree verbatim
    round-trip (`test_from_qcal_save_qcal_uncalibrated_is_verbatim`) is the other half of the claim
    — these are seconds on both sides, so writing them unconditionally cannot perturb a tree
    nothing calibrated."""
    cfg = Config.from_qcal(X6Y3_YAML)
    cfg["readout/0/dur"] = 6.4e-7                      # a longer readout drive (Window knob='dur')
    cfg["readout/0/demod/delay"] = 4.2e-7              # the window opens later (knob='demod/delay')
    out = tmp_path / "config.yaml"
    cfg.save_qcal(out)

    with open(out) as f:
        tree = yaml.safe_load(f)
    assert tree["readout"][0]["time"] == 6.4e-7
    assert tree["readout"][0]["demod"]["delay"] == 4.2e-7
    back = Config.from_qcal(out)
    assert back["readout/0/dur"] == 6.4e-7
    assert back["readout/0/demod/delay"] == 4.2e-7
    assert back.to_dict() == cfg.to_dict()             # nothing else moved


def test_from_qcal_loads_ef_and_two_qubit_and_writes_back(tmp_path):
    """The EF keys land on the paths the EF consumers read (base.ef_pulse:
    `qubit/{q}/EF/x90/...`), the two_qubit subtrees are carried verbatim, and a calibrated EF/CZ
    value survives save_qcal."""
    cfg = Config.from_qcal(X6Y3_YAML)
    with open(X6Y3_YAML) as f:
        tree = yaml.safe_load(f)

    ef = tree["single_qubit"][5]["EF"]
    drive = [p for p in ef["X90"]["pulse"] if p["env"] != "virtualz"][0]
    assert cfg["qubit/5/EF/freq"] == ef["freq"]
    assert cfg["qubit/5/EF/T1"] == ef["T1"] and cfg["qubit/5/EF/T2"] == ef["T2*"]
    assert cfg["qubit/5/EF/x90/env"] == "FAST_DRAG"
    assert cfg["qubit/5/EF/x90/amp"] == drive["kwargs"]["amp"]
    assert cfg["qubit/5/EF/x90/vz"] == [p["kwargs"]["phase"] for p in ef["X90"]["pulse"]
                                        if p["env"] == "virtualz"]           # the EF frame pair
    assert cfg["qubit/5/EF/x/dur"] == ef["X"]["pulse"][0]["time"]            # the EF X, a real pulse
    assert cfg["two_qubit/(0, 1)"] == tree["two_qubit"]["(0, 1)"]            # verbatim subtree
    assert cfg["two_qubit/(5, 6)/CZ/pulse"][0] == "single_qubit/6/EF/X/pulse"   # string reference
    assert cfg["two_qubit/(0, 1)/CZ/pulse"][2]["freq"] == "single_qubit/0/GE/freq"  # path-valued freq

    cfg["qubit/5/EF/freq"] = 5.36e9
    cfg["qubit/5/EF/x90/amp"] = 0.111
    cfg["qubit/5/EF/x90/vz"] = [0.03, 0.04]
    cfg["two_qubit/(0, 1)/CZ/freq"] = 5.31e9
    pl = cfg.copy()["two_qubit/(5, 6)/CZ/pulse"]                 # a fresh list (the proposal shape)
    pl[2]["kwargs"]["phase"] = 0.777                             # the target drive's relative phase
    cfg["two_qubit/(5, 6)/CZ/pulse"] = pl
    out = tmp_path / "config.yaml"
    cfg.save_qcal(out)
    with open(out) as f:
        back = yaml.safe_load(f)

    ef = back["single_qubit"][5]["EF"]
    assert ef["freq"] == 5.36e9
    assert [p["kwargs"]["amp"] for p in ef["X90"]["pulse"] if p["env"] != "virtualz"] == [0.111]
    assert [p["kwargs"]["phase"] for p in ef["X90"]["pulse"] if p["env"] == "virtualz"] == [0.03, 0.04]
    assert back["two_qubit"]["(0, 1)"]["CZ"]["freq"] == 5.31e9
    got = back["two_qubit"]["(5, 6)"]["CZ"]["pulse"]
    assert got[2]["kwargs"]["phase"] == 0.777
    assert got[0] == got[3] == "single_qubit/6/EF/X/pulse"       # the sandwich survives the write
    assert back["two_qubit"]["(1, 2)"] == tree["two_qubit"]["(1, 2)"]        # untouched pair verbatim


# ── adapter completeness ──

def test_save_qcal_persists_config_added_pair_keys(tmp_path):
    """A pair subtree is written back WHOLESALE, so a key a calibration ADDS under
    `two_qubit/(i, j)/` — JAZZ's `ZZ11`, an exploratory `bSWAP` — reaches the artefact of record
    instead of being dropped, and the CZ list still round-trips with its
    string-reference entries."""
    cfg = Config.from_qcal(X6Y3_YAML)
    cfg["two_qubit/(0, 1)/ZZ11"] = 1.234e5                      # JAZZ's characterization output
    cfg["two_qubit/(5, 6)/bSWAP"] = {"freq": 1.1e10, "time": 4e-7}
    cfg["two_qubit/(0, 1)/CZ/freq"] = 5.33e9
    out = tmp_path / "config.yaml"
    cfg.save_qcal(out)
    with open(out) as f:
        back = yaml.safe_load(f)

    assert back["two_qubit"]["(0, 1)"]["ZZ11"] == 1.234e5
    assert back["two_qubit"]["(5, 6)"]["bSWAP"] == {"freq": 1.1e10, "time": 4e-7}
    assert back["two_qubit"]["(0, 1)"]["CZ"]["freq"] == 5.33e9
    assert back["two_qubit"]["(5, 6)"]["CZ"]["pulse"][0] == "single_qubit/6/EF/X/pulse"
    # and they survive a reload — the new keys are opaque on the way back in
    assert Config.from_qcal(out)["two_qubit/(0, 1)/ZZ11"] == 1.234e5


def test_save_qcal_writes_gate_kwargs_and_an_inserted_vz_pair(tmp_path):
    """The envelope kwargs the DRAG optimizer tunes (`N`, `weights`) are written back, and a
    vz pair calibrated on a gate that HAD none (the X is a bare FAST_DRAG in qcal) becomes two real
    virtualz entries bracketing the drive — the `Phase(gate='X')` write-back path."""
    cfg = Config.from_qcal(X6Y3_YAML)
    kw = dict(cfg["qubit/2/x90/kwargs"])
    kw["N"], kw["weights"] = 3, [0.2, 0.05, 0.01]
    cfg["qubit/2/x90/kwargs"] = kw
    cfg["qubit/2/x/vz"] = [0.11, 0.22]                       # a frame the X gate did not have
    cfg["qubit/5/EF/x/vz"] = [-0.03, 0.04]
    out = tmp_path / "config.yaml"
    cfg.save_qcal(out)
    with open(out) as f:
        back = yaml.safe_load(f)

    drive = [p for p in back["single_qubit"][2]["GE"]["X90"]["pulse"] if p["env"] != "virtualz"][0]
    assert drive["kwargs"]["N"] == 3 and drive["kwargs"]["weights"] == [0.2, 0.05, 0.01]
    assert drive["kwargs"]["alpha"] == cfg["qubit/2/x90/kwargs"]["alpha"]      # untuned kwargs kept

    x = back["single_qubit"][2]["GE"]["X"]["pulse"]
    assert [p["env"] for p in x] == ["virtualz", "FAST_DRAG", "virtualz"]      # the pair was inserted
    assert [p["kwargs"]["phase"] for p in x if p["env"] == "virtualz"] == [0.11, 0.22]
    assert all(p["channel"] == x[1]["channel"] and p["time"] == 0.0 for p in (x[0], x[2]))
    efx = back["single_qubit"][5]["EF"]["X"]["pulse"]
    assert [p["kwargs"]["phase"] for p in efx if p["env"] == "virtualz"] == [-0.03, 0.04]

    # the inserted pairs re-load as the same knobs (and the untouched X gates stay bare)
    again = Config.from_qcal(out)
    assert again["qubit/2/x/vz"] == [0.11, 0.22] and again["qubit/5/EF/x/vz"] == [-0.03, 0.04]
    assert "qubit/3/x/vz" not in again
    assert again["qubit/2/x90/kwargs"]["weights"] == [0.2, 0.05, 0.01]


def test_opaque_subtrees_round_trip_verbatim(tmp_path):
    """Everything out of scope survives the adapter byte-identically: the whole
    `reset` section (passive / active / unconditional pulse lists), `readout/esp`, each pair's
    `dynamical_decoupling`, and a planted `bSWAP` subtree — none of it is understood, all of it is
    carried."""
    with open(X6Y3_YAML) as f:
        tree = yaml.safe_load(f)
    tree["two_qubit"]["(4, 5)"]["bSWAP"] = {           # the reference's exploratory subtree
        "freq": 1.0746e10,
        "pulse": [{"channel": "Q4.qdrv", "env": "cosine_square", "time": 3e-7,
                   "kwargs": {"amp": 0.5, "phase": 0.0, "ramp_fraction": 0.25}}]}
    src = tmp_path / "in.yaml"
    with open(src, "w") as f:
        yaml.safe_dump(tree, f, default_flow_style=False, sort_keys=False)

    out = tmp_path / "out.yaml"
    Config.from_qcal(src).save_qcal(out)
    with open(out) as f:
        back = yaml.safe_load(f)

    assert back == tree                                       # nothing calibrated → verbatim
    assert back["reset"] == tree["reset"]
    assert back["readout"]["esp"] == tree["readout"]["esp"] == {"enable": False,
                                                               "qubits": [0, 2, 3, 4, 5, 6, 7]}
    assert back["two_qubit"]["(4, 5)"]["bSWAP"] == tree["two_qubit"]["(4, 5)"]["bSWAP"]
    assert back["two_qubit"]["(0, 1)"]["CZ"]["dynamical_decoupling"]["enable"] is False


def test_qcal_envelopes_build(qcal_cfg):
    """The X6Y3 envelopes are buildable on this build's channel grids: the FAST_DRAG gate (35 ns of
    2 GS/s gate samples) and the cosine_square readout tone, both peaking at FULL."""
    x90 = envelopes.build("FAST_DRAG", 70, 2e9, **qcal_cfg["qubit/0/x90/kwargs"])
    assert x90.shape == (70,) and abs(x90).max() == pytest.approx(envelopes.FULL)
    assert abs(x90.imag).max() > 0                       # the DRAG quadrature is present (alpha=1)
    ro = envelopes.build("cosine_square", 250, 5e8, **qcal_cfg["readout/0/kwargs"])
    assert ro.shape == (250,) and abs(ro).max() == pytest.approx(envelopes.FULL)
    assert abs(ro[0]) < 1e-9 and abs(ro[125]) == pytest.approx(envelopes.FULL)   # ramps up to a flat top
