"""compile_c: real riscv-gcc invocations, cache behavior, loud failures."""

from pathlib import Path

import pytest

import riscq.build as build
from riscq.build import Image, Program, compile_c
from riscq.map import MEM_BASE, SocMap, SocParams

CONFIGS = Path(__file__).resolve().parents[1] / "configs"


@pytest.fixture()
def m() -> SocMap:
    return SocMap(SocParams.load(CONFIGS / "sim-2q.json"))


TRIVIAL = """
#include "riscq.h"
volatile int32_t seed = 3;
volatile int32_t out[8];
volatile int32_t scratch[256];   /* .bss: must NOT appear in the flat image */
int main(void) {
    for (int i = 0; i < 8; i++) out[i] = seed + i;
    return 0;
}
"""


def test_trivial_program(m, tmp_path, monkeypatch):
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)
    img = compile_c(TRIVIAL, m)
    assert isinstance(img, Image)
    assert img.entry == MEM_BASE
    assert 0 < len(img.data) <= m.mem_bytes
    for sym in ("main", "_start", "__rq_status", "__rq_magic", "seed", "out", "scratch"):
        assert sym in img.symbols, f"missing symbol {sym}"
    prog = Program.from_image(img)
    assert MEM_BASE <= prog.var_addr("out") < MEM_BASE + m.mem_bytes
    assert prog.var_size("out") == 8 * 4
    assert prog.var_size("__rq_status") == 4
    # .bss (1 KB scratch) is start.S-zeroed, not shipped: image stays smaller than .bss alone
    assert len(img.data) < 256 * 4 + 1024
    # _start is at the base: the image boots at 0x80000000
    assert img.symbols["_start"][0] == MEM_BASE


def test_cache_hit_skips_gcc(m, tmp_path, monkeypatch):
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)
    runs0 = build.CC_RUNS
    a = compile_c(TRIVIAL, m)
    assert build.CC_RUNS == runs0 + 1
    b = compile_c(TRIVIAL, m)
    assert build.CC_RUNS == runs0 + 1, "second identical compile must hit the cache"
    assert a.data == b.data and a.symbols == b.symbols
    c = compile_c(TRIVIAL + "\n/* different */", m)
    assert build.CC_RUNS == runs0 + 2
    assert c.data


def test_oversized_array_is_link_error(m, tmp_path, monkeypatch):
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)
    src = '#include "riscq.h"\nvolatile int32_t huge[8192];\nint main(void){ huge[0]=1; return 0; }'
    with pytest.raises(RuntimeError, match="riscv clang failed"):
        compile_c(src, m)  # 32 KB > 16 KB RAM -> loud link failure


def test_undefined_function_is_error(m, tmp_path, monkeypatch):
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)
    src = '#include "riscq.h"\nint main(void){ not_an_op(3); return 0; }'
    with pytest.raises(RuntimeError, match="implicit"):
        compile_c(src, m)


def test_libgcc_multiply_links(m, tmp_path, monkeypatch):
    """Plain RV32I multiply must resolve to libgcc __mulsi3 at link time."""
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)
    src = ('#include "riscq.h"\nvolatile int32_t a = 7, b = 6, c;\n'
           'int main(void){ c = a * b; return 0; }')
    img = compile_c(src, m)
    assert "c" in img.symbols


def test_extra_headers(m, tmp_path, monkeypatch):
    monkeypatch.setattr(build, "BUILD_ROOT", tmp_path)
    src = '#include "riscq.h"\n#include "user_ops.h"\nvolatile int32_t r;\nint main(void){ r = user_op(); return 0; }'
    hdr = "static inline int user_op(void) { return 123; }\n"
    img = compile_c(src, m, extra_headers={"user_ops.h": hdr})
    assert "r" in img.symbols
