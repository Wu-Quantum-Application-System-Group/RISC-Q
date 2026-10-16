# Patches to vendored dependencies

`ext/rvls` is a **git submodule**, so edits inside it are tracked only by the submodule's own git.
The submodule is pinned to a fork (branch `riscq-machine-only-csr-stubs`, see `.gitmodules`) that
already carries the patch below; the patch is kept here so it can be re-applied onto upstream rvls
or after the submodule is reset to a commit without it.

## `rvls-hart-machine-only-stub-csrs.patch`

**File:** `ext/rvls/src/hart.cpp` (the RVLS↔Spike bridge used for per-instruction lock-step).

**Why:** riscq is a machine-mode-only core. The standard `rv32ui-p-*` startup still pokes a few
S-mode / delegation / PMP CSRs (`satp`, `stvec`, `medeleg`, `mideleg`, `pmp*`) behind the
riscv-tests "set a temporary `mtvec` so an illegal CSR write just skips" guard, and riscq answers
them as write-ignored / read-zero **WARL stubs** instead of trapping. To lock-step against riscq,
Spike is configured **machine-only** (isa `RV32I`, priv `M`) — but Spike keeps those CSRs in its
`csrmap` even without the S extension and **traps** in `verify_permissions` (`!extension_enabled('S')`,
or no PMP). That trap is exactly where riscq commits, so lock-step diverges with *"DUT missed a trap"*.

**What it does:** adds a permissive `stub_csr_t` (a `basic_csr_t` whose `verify_permissions` checks
only the numeric privilege level and skips the extension gate) and, in the `Hart` constructor,
replaces the S-mode/delegation CSRs **only when `!extension_enabled('S')`** and the PMP CSRs **only
when `pmpNum == 0`**. So machine-only Spike (riscq) stops trapping on them, while VexiiRiscv's real
S/U/PMP configs keep their genuine CSRs untouched.

Combined with machine-only `mret` (MPP WARL-clamps to M, so the post-`mret` `ecall` stays cause 11
just like riscq), riscq and Spike stay on the same PC / trap-cause / register stream — no S/U-mode
support and no S/U-mode tests are needed. Result: **all 39 `rv32ui-p-*` pass per-instruction
lock-step**.

### Apply + rebuild

```bash
git -C ext/rvls apply ../../patches/rvls-hart-machine-only-stub-csrs.patch   # only if missing
make -C ext/rvls SPIKE=/path/to/riscv-isa-sim                                # builds rvls.so
```

`make -C ext/rvls` links against the static libs of a built Spike — SpinalHDL's `riscv-isa-sim`
fork, built as described in `ext/rvls/README.md` (`SPIKE` defaults to `../riscv-isa-sim` relative
to `ext/rvls`) — producing `ext/rvls/build/apps/rvls.so` (the native library `rvls.jni.Frontend`
loads). [`sim/RvTestSim`](../src/riscq/riscv/sim/RvTestSim.scala) auto-enables
lock-step whenever that `.so` exists.
