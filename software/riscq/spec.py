"""SocSpec — the one description of a build: cores, each with its channel list.

Read here (python) and by the elaboration (`src/riscq/soc/spec/SocSpec.scala`, uJson) from the same
JSON under software/configs/, so hardware and software derive every address from one parameter set
(specs/universal-control/01 §2.1). Two JSON forms are accepted:

  - the channel-list form: `{"name", "dsp_freq_hz", ..., "cores": [{"name", "channels": [...]}]}`
  - the legacy SocParams form (`qubit_num` + role-named scalars `gate_pulse_num`, `gate_interp`,
    `readout_interp`, `demod_interp`, `dac_map`, `adc_map`), converted by `from_legacy` into the same
    dataclasses: every core gets the channels `gate` (pulse), `ro` (pulse, traced) and `demod`.

Channel kinds today: `pulse` (a DAC-bound drive: PulseParamBuffer + PulseGenerator, 16 lanes) and
`demod` (the ADC-bound demod carrier whose window triggers the decoder, 4 lanes). Later kinds (dio,
tones, ...) add fields here and a Scala twin; the map formulas in riscq.map do not change.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

# ── fixed architecture constants (assert, never parameterize) ──
BATCH_SIZE = 16          # DAC samples per batch (= per dsp cycle)
ADC_BATCH = 4            # ADC samples per batch
DATA_WIDTH = 16          # SInt sample width
READOUT_MAX_WIN_LOG2 = 14  # decoder no-overflow bound: longest demod window <= 2^this batches
READOUT_ACC_WIDTH = 32   # decoder accumulator width (= one-word readback)

_FIXED = {
    "batch_size": BATCH_SIZE,
    "adc_batch": ADC_BATCH,
    "data_width": DATA_WIDTH,
    "readout_max_win_log2": READOUT_MAX_WIN_LOG2,
    "readout_acc_width": READOUT_ACC_WIDTH,
}

KIND_LANES = {"pulse": BATCH_SIZE, "demod": ADC_BATCH, "dio": 0}   # dio: 16 timed lines, no bank


@dataclass(frozen=True)
class ChannelSpec:
    """One channel of a core. `slots` = pulse-table depth, `env_depth` = envelope-RAM lines,
    `interp` = envelope interpolation (stored samples per line = lanes // interp). A `pulse`
    channel names its physical `dac`, a `demod` channel its `adc`. `trace` marks the channel whose
    fires trigger the shared readout trace (`robs`) — the readout drive on the qubit builds."""

    name: str
    kind: str
    slots: int
    env_depth: int
    interp: int
    dac: int | None = None
    adc: int | None = None
    trace: bool = False

    def __post_init__(self):
        if self.kind not in KIND_LANES:
            raise ValueError(f"channel {self.name!r}: unknown kind {self.kind!r}")
        if self.lanes % self.interp:
            raise ValueError(f"channel {self.name!r}: interp {self.interp} must divide {self.lanes} lanes")
        if self.kind == "pulse" and (self.dac is None or self.adc is not None):
            raise ValueError(f"channel {self.name!r}: a pulse channel names a dac, not an adc")
        if self.kind == "demod" and (self.adc is None or self.dac is not None):
            raise ValueError(f"channel {self.name!r}: a demod channel names an adc, not a dac")
        if self.kind == "dio" and (self.dac is not None or self.adc is not None):
            raise ValueError(f"channel {self.name!r}: a dio channel names neither a dac nor an adc")
        if self.slots < 1:
            raise ValueError(f"channel {self.name!r}: slots must be >= 1")
        if (self.env_depth == 0) != (self.kind == "dio"):
            raise ValueError(f"channel {self.name!r}: env_depth is 0 exactly for a dio channel")

    @property
    def lanes(self) -> int:
        return KIND_LANES[self.kind]

    @property
    def env_width(self) -> int:
        """Bits per stored envelope line (the interpolated line)."""
        return self.lanes * 2 * DATA_WIDTH // self.interp

    @property
    def samples_per_line(self) -> int:
        return self.lanes // self.interp

    @property
    def line_bytes(self) -> int:
        return self.env_width // 8

    @property
    def env_bytes(self) -> int:
        return self.env_width * self.env_depth // 8


@dataclass(frozen=True)
class CoreSpec:
    name: str
    channels: tuple[ChannelSpec, ...]
    role: str = "qubit"
    mem_depth: int = 4096
    with_mul: bool = True
    queue_depth: int = 4

    def __post_init__(self):
        names = [c.name for c in self.channels]
        if len(set(names)) != len(names):
            raise ValueError(f"core {self.name!r}: duplicate channel names {names}")
        if not self.channels:
            raise ValueError(f"core {self.name!r}: a core needs at least one channel")

    def index(self, name: str) -> int:
        for i, c in enumerate(self.channels):
            if c.name == name:
                return i
        raise KeyError(f"core {self.name!r} has no channel {name!r} (have {[c.name for c in self.channels]})")

    def channel(self, name: str) -> ChannelSpec:
        return self.channels[self.index(name)]

    def find(self, name: str) -> ChannelSpec | None:
        return next((c for c in self.channels if c.name == name), None)


@dataclass(frozen=True)
class SocSpec:
    """One build's elaboration parameters — the python view of software/configs/<name>.json, the
    same file GenPulseTableSocJson feeds to the SpinalHDL elaboration."""

    name: str
    cores: tuple[CoreSpec, ...]
    dsp_freq_hz: float
    dac_num: int = 16
    adc_num: int = 16
    link_pipe: int = 4
    hostwin_bits: int = 24     # per-core host window = 1 << hostwin_bits bytes (24 = 16 MB)
    rob_depth: int = 1024
    adc_pipe: int = 3
    with_white_rabbit: bool = False   # White Rabbit node window + phy ports (specs/white-rabbit/06)
    wr_marker_dac: int | None = None  # spare DAC carrying the sync marker (white-rabbit/09)
    board: int = 0                    # this board's id in the system (0 = the barrier root)
    boards: int = 1                   # boards in the system (> 1 rides the WR lane, cross-core/02 §5)

    def __post_init__(self):
        if not self.cores:
            raise ValueError("a SocSpec needs at least one core")
        if self.wr_marker_dac is not None and not (self.with_white_rabbit and 0 <= self.wr_marker_dac < self.dac_num):
            raise ValueError(f"wr_marker_dac={self.wr_marker_dac} needs with_white_rabbit and a valid DAC id")
        if not (0 <= self.board < self.boards <= 16) or (self.boards > 1 and not self.with_white_rabbit):
            raise ValueError(f"board {self.board} of {self.boards}: needs 0 <= board < boards <= 16, "
                             f"and boards > 1 needs with_white_rabbit")
        names = [c.name for c in self.cores]
        if len(set(names)) != len(names):
            raise ValueError(f"duplicate core names {names}")
        for c in self.cores:
            for ch in c.channels:
                if ch.dac is not None and not 0 <= ch.dac < self.dac_num:
                    raise ValueError(f"{c.name}/{ch.name}: dac {ch.dac} outside dac_num {self.dac_num}")
                if ch.adc is not None and not 0 <= ch.adc < self.adc_num:
                    raise ValueError(f"{c.name}/{ch.name}: adc {ch.adc} outside adc_num {self.adc_num}")

    # ── loading ──
    @classmethod
    def load(cls, json_path: str | Path) -> "SocSpec":
        return cls.from_json(Path(json_path).read_text())

    @classmethod
    def from_json(cls, text: str) -> "SocSpec":
        raw = json.loads(text)
        for key, value in _FIXED.items():  # fixed constants may be stated, never changed
            if key in raw and raw.pop(key) != value:
                raise ValueError(f"{key} is fixed by architecture at {value}")
        if "cores" in raw:
            return cls._from_channel_list(raw)
        return cls.from_legacy(raw)

    @classmethod
    def _from_channel_list(cls, raw: dict) -> "SocSpec":
        defaults = raw.pop("core_defaults", {})
        cores = []
        for c in raw.pop("cores"):
            c = dict(c)
            chans = tuple(ChannelSpec(**ch) for ch in c.pop("channels"))
            merged = {**defaults, **c}
            cores.append(CoreSpec(channels=chans, **merged))
        return cls(cores=tuple(cores), **raw)

    @classmethod
    def from_legacy(cls, raw: dict) -> "SocSpec":
        """The SocParams form: `qubit_num` identical qubit cores of gate / ro / demod channels.
        Converter ids come from `dac_map` (`[gateDac, roDac]` per core) / `adc_map` when present,
        else the generic ZCU216 SocChannelMap layout (gate on DAC = core, readout drive on 14/15,
        demod on ADC 0/4)."""
        raw = dict(raw)
        raw.pop("queue_depth_ignored", None)
        n = int(raw["qubit_num"])
        dac_map = raw.get("dac_map")
        adc_map = raw.get("adc_map")
        if dac_map is not None and len(dac_map) != n:
            raise ValueError(f"dac_map needs {n} entries, got {len(dac_map)}")
        if adc_map is not None and len(adc_map) != n:
            raise ValueError(f"adc_map needs {n} entries, got {len(adc_map)}")
        known = {"name", "qubit_num", "dac_num", "adc_num", "mem_depth", "env_depth", "rob_depth",
                 "gate_pulse_num", "gate_interp", "readout_interp", "demod_interp", "link_pipe",
                 "with_mul", "dsp_freq_hz", "hostwin_bits", "queue_depth", "adc_pipe",
                 "dac_map", "adc_map", "with_white_rabbit", "wr_marker_dac", "board", "boards"}
        unknown = set(raw) - known
        if unknown:
            raise ValueError(f"unknown SocParams fields: {sorted(unknown)}")
        required = {"name", "qubit_num", "dac_num", "adc_num", "mem_depth", "env_depth",
                    "gate_pulse_num", "gate_interp", "readout_interp", "link_pipe", "dsp_freq_hz"}
        missing = required - set(raw)
        if missing:
            raise ValueError(f"missing SocParams fields: {sorted(missing)}")
        env_depth = int(raw["env_depth"])
        cores = []
        for c in range(n):
            gate_dac = int(dac_map[c][0]) if dac_map is not None else c
            ro_dac = int(dac_map[c][1]) if dac_map is not None else (14 if c < 7 else 15)
            adc = int(adc_map[c]) if adc_map is not None else (0 if c < 7 else 4)
            chans = (
                ChannelSpec("gate", "pulse", int(raw["gate_pulse_num"]), env_depth,
                            int(raw["gate_interp"]), dac=gate_dac),
                ChannelSpec("ro", "pulse", 1, env_depth, int(raw["readout_interp"]),
                            dac=ro_dac, trace=True),
                ChannelSpec("demod", "demod", 1, env_depth, int(raw.get("demod_interp", 4)), adc=adc),
            )
            cores.append(CoreSpec(name=f"q{c}", channels=chans, role="qubit",
                                  mem_depth=int(raw["mem_depth"]),
                                  with_mul=bool(raw.get("with_mul", True)),
                                  queue_depth=int(raw.get("queue_depth", 4))))
        return cls(name=str(raw["name"]), cores=tuple(cores), dsp_freq_hz=float(raw["dsp_freq_hz"]),
                   dac_num=int(raw["dac_num"]), adc_num=int(raw["adc_num"]),
                   link_pipe=int(raw["link_pipe"]), hostwin_bits=int(raw.get("hostwin_bits", 24)),
                   rob_depth=int(raw.get("rob_depth", 1024)), adc_pipe=int(raw.get("adc_pipe", 3)),
                   with_white_rabbit=bool(raw.get("with_white_rabbit", False)),
                   wr_marker_dac=(int(raw["wr_marker_dac"]) if raw.get("wr_marker_dac") is not None else None),
                   board=int(raw.get("board", 0)), boards=int(raw.get("boards", 1)))

    def to_json(self) -> str:
        """The canonical channel-list form (what the remote runner ships; `from_json` reads it back)."""
        def chan(ch: ChannelSpec) -> dict:
            d = {"name": ch.name, "kind": ch.kind, "slots": ch.slots,
                 "env_depth": ch.env_depth, "interp": ch.interp}
            if ch.dac is not None:
                d["dac"] = ch.dac
            if ch.adc is not None:
                d["adc"] = ch.adc
            if ch.trace:
                d["trace"] = True
            return d
        out = {"name": self.name, "dsp_freq_hz": self.dsp_freq_hz, "dac_num": self.dac_num,
               "adc_num": self.adc_num, "link_pipe": self.link_pipe,
               "hostwin_bits": self.hostwin_bits, "rob_depth": self.rob_depth,
               "adc_pipe": self.adc_pipe,
               "with_white_rabbit": self.with_white_rabbit, "wr_marker_dac": self.wr_marker_dac,
               "board": self.board, "boards": self.boards,
               "cores": [{"name": c.name, "role": c.role, "mem_depth": c.mem_depth,
                          "with_mul": c.with_mul, "queue_depth": c.queue_depth,
                          "channels": [chan(ch) for ch in c.channels]} for c in self.cores]}
        return json.dumps(out, indent=4)

    # ── per-core access ──
    def core(self, index: int) -> CoreSpec:
        if not 0 <= index < len(self.cores):
            raise ValueError(f"core {index} out of range (have {len(self.cores)} cores)")
        return self.cores[index]

    @property
    def max_channels(self) -> int:
        return max(len(c.channels) for c in self.cores)

    # ── legacy uniform views (the SocParams fields every consumer still reads; P2 retires them) ──
    def _uniform(self, values, what: str):
        vals = list(values)
        if any(v != vals[0] for v in vals):
            raise ValueError(f"{what} differs across cores/channels ({vals}); use the per-core spec")
        return vals[0]

    @property
    def qubit_num(self) -> int:
        return len(self.cores)

    @property
    def mem_depth(self) -> int:
        return self._uniform((c.mem_depth for c in self.cores), "mem_depth")

    @property
    def with_mul(self) -> bool:
        return self._uniform((c.with_mul for c in self.cores), "with_mul")

    @property
    def queue_depth(self) -> int:
        return self._uniform((c.queue_depth for c in self.cores), "queue_depth")

    @property
    def env_depth(self) -> int:
        return self._uniform((ch.env_depth for c in self.cores for ch in c.channels), "env_depth")

    def _named(self, name: str):
        return [c.channel(name) for c in self.cores]

    @property
    def gate_pulse_num(self) -> int:
        return self._uniform((ch.slots for ch in self._named("gate")), "gate slots")

    @property
    def gate_interp(self) -> int:
        return self._uniform((ch.interp for ch in self._named("gate")), "gate interp")

    @property
    def readout_interp(self) -> int:
        return self._uniform((ch.interp for ch in self._named("ro")), "ro interp")

    @property
    def demod_interp(self) -> int:
        return self._uniform((ch.interp for ch in self._named("demod")), "demod interp")

    @property
    def dac_map(self) -> tuple:
        """Legacy view: per core, the dacs of its DAC-bound channels in order ((gateDac, roDac) on
        the qubit builds)."""
        return tuple(tuple(ch.dac for ch in c.channels if ch.dac is not None) for c in self.cores)

    @property
    def adc_map(self) -> tuple:
        """Legacy view: per core, the adc of its first ADC-bound channel (every core must have one)."""
        out = []
        for c in self.cores:
            adcs = [ch.adc for ch in c.channels if ch.adc is not None]
            if not adcs:
                raise ValueError(f"core {c.name!r} has no ADC-bound channel; no legacy adc_map view")
            out.append(adcs[0])
        return tuple(out)
