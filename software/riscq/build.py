"""C -> flat binary image: stage fw/ + generated riscq_map.h/link.ld, invoke the riscv clang,
objcopy to a flat image at 0x80000000, extract the symbol table with nm. Cached on input hash."""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
from dataclasses import dataclass, field
from pathlib import Path

from riscq.map import MEM_BASE, SocMap

# resolved from PATH; the toolchain ships no rv32 runtime archive, so fw/muldiv.c provides the
# soft mul/div libcalls instead of -lgcc
CC = shutil.which("riscv64-unknown-elf-clang")
OBJCOPY = shutil.which("riscv64-unknown-elf-objcopy")
NM = shutil.which("riscv64-unknown-elf-nm")

_SW_ROOT = Path(__file__).resolve().parent.parent      # software/
FW_DIR = _SW_ROOT / "fw"
BUILD_ROOT = _SW_ROOT / "build" / "cc"

# number of actual compiler invocations this process made (tests assert cache hits against it)
CC_RUNS = 0


@dataclass
class Image:
    """A linked flat program image: `data` loads at 0x80000000 (text+rodata+data; .bss is NOT
    in the image — start.S zeroes it), `symbols` maps global name -> (address, size in bytes)."""

    data: bytes
    symbols: dict[str, tuple[int, int]]
    entry: int = MEM_BASE


class Program:
    """A compiled program: the flat image plus what the runner needs (spec 02/03).

    - `params`: host-writable param global -> None for user runtime params (kernels only emit
      user params now; the value is written by riscq.run.write_params).
    - `arrays`: Array param name -> element count (run() reads these back by default).
    - `host_arrays`: the subset of `arrays` placed in the core's write-only host window
      (specs/software/22) -> (byte offset in the window, element count). Those have NO RAM symbol;
      `riscq.run.rerun` reads them with `Driver.read_host` instead of `read_array`.
    - `core`: the core this specialization was compiled for — its channel geometry and header
      (compile_kernel(core=...)); 0 for hand-written C and single-core builds.
    - `envelopes`: channel index (in that core's channel list) -> [(line0, lines)] RAM images.
    - `tables`: live ParamTable symbol -> [(phase, amp, env, dur), ...] per-slot design-time
      codes riscq.run.load_tables fills the .data `struct rq_slot[]` with (spec 02 §3.2).
    - `c_source`: the generated C (kernels only; readable, spec principle 7).
    - `bindings`: the compile-time bound int parameters (`npts`, `shots`, `period`, `mode`, the
      sweep descriptors, ...) — the constants that were folded into the C. Carried so a reader
      can recover what specialization this is without re-deriving it; the host-pure test
      responder reads the sweep off here rather than closing over it
      (specs/software-test-refactor/01 §2.2).

    Hand-written C uses `Program.from_image(img)` — everything empty, same runner."""

    def __init__(self, image: Image, params=None, arrays=None, envelopes=None, tables=None,
                 c_source: str | None = None, bindings=None, host_arrays=None, core: int = 0):
        self.image = image
        self.core = int(core)
        self.params: dict[str, int | None] = dict(params or {})
        self.arrays: dict[str, int] = dict(arrays or {})
        self.host_arrays: dict[str, tuple[int, int]] = {n: (int(o), int(c))
                                                        for n, (o, c) in (host_arrays or {}).items()}
        self.envelopes: dict = dict(envelopes or {})
        self.tables: dict = dict(tables or {})
        self.c_source = c_source
        self.bindings: dict[str, int] = dict(bindings or {})

    @classmethod
    def from_image(cls, image: Image) -> "Program":
        return cls(image)

    def var_addr(self, name: str) -> int:
        if name not in self.image.symbols:
            raise KeyError(f"symbol {name!r} not in program "
                           f"(known: {sorted(self.image.symbols)[:20]}...)")
        return self.image.symbols[name][0]

    def var_size(self, name: str) -> int:
        self.var_addr(name)
        return self.image.symbols[name][1]


def _flags(soc_map: SocMap) -> list[str]:
    march = "rv32i_zmmul" if soc_map.params.with_mul else "rv32i"
    return [f"-march={march}", "-mabi=ilp32", "-O2", "-fwrapv", "-nostdlib",
            "-ffreestanding", "-mno-relax", "-Werror=implicit-function-declaration", "-I."]


def _parse_nm(text: str) -> dict[str, tuple[int, int]]:
    symbols = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) == 4:
            addr, size, _, name = parts
            symbols[name] = (int(addr, 16), int(size, 16))
        elif len(parts) == 3:
            addr, _, name = parts
            symbols[name] = (int(addr, 16), 0)
    return symbols


def compile_c(c_source: str, soc_map: SocMap, extra_headers: dict[str, str] | None = None,
              core: int = 0) -> Image:
    """Compile a C program (its whole main.c text) against this build's fw/ runtime, for the
    channel geometry of `core` (its generated riscq_map.h). The header text is part of the
    build-cache key, so cores with the same channel list share one compile.
    Loud failures: missing toolchain, compile/link errors (RAM overflow is a link error),
    oversize image."""
    global CC_RUNS
    if CC is None:
        raise RuntimeError("riscv64-unknown-elf-clang not found on PATH — "
                           "put the riscv LLVM toolchain's bin/ on PATH")

    files = {
        "main.c": c_source,
        "start.S": (FW_DIR / "start.S").read_text(),
        "muldiv.c": (FW_DIR / "muldiv.c").read_text(),
        "riscq.h": (FW_DIR / "riscq.h").read_text(),
        "riscq_map.h": soc_map.gen_header(core),
        "link.ld": soc_map.gen_linker(),
    }
    files.update(extra_headers or {})
    flags = _flags(soc_map)

    key = hashlib.sha256(
        json.dumps({"cc": CC, "files": files, "flags": flags},
                   sort_keys=True).encode()).hexdigest()[:16]
    build_dir = BUILD_ROOT / key
    bin_path = build_dir / "main.bin"
    sym_path = build_dir / "symbols.json"

    if not (bin_path.exists() and sym_path.exists()):
        if build_dir.exists():
            shutil.rmtree(build_dir)
        build_dir.mkdir(parents=True)
        for name, text in files.items():
            (build_dir / name).write_text(text)

        cmd = [CC] + flags + ["-T", "link.ld", "-o", "main.elf", "start.S", "main.c", "muldiv.c"]
        CC_RUNS += 1
        r = subprocess.run(cmd, cwd=build_dir, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"riscv clang failed ({' '.join(cmd)}):\n{r.stderr}")

        r = subprocess.run([OBJCOPY, "-O", "binary", "main.elf", "main.bin"],
                           cwd=build_dir, capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"objcopy failed:\n{r.stderr}")

        r = subprocess.run([NM, "-S", "main.elf"], cwd=build_dir,
                           capture_output=True, text=True)
        if r.returncode != 0:
            raise RuntimeError(f"nm failed:\n{r.stderr}")
        sym_path.write_text(json.dumps(_parse_nm(r.stdout)))

    data = bin_path.read_bytes()
    if len(data) > soc_map.mem_bytes:  # belt: link.ld's ASSERT should have caught this
        raise RuntimeError(f"image {len(data)} B exceeds RAM {soc_map.mem_bytes} B")
    symbols = {k: tuple(v) for k, v in json.loads(sym_path.read_text()).items()}
    return Image(data=data, symbols=symbols)
