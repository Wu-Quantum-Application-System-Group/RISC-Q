# host_window.S — W1 host-window CPU-in-the-loop program (specs/software/22 §4, docs/soc/HostWindow.md).
#
# Both cores run this same image; the sim writes a per-core tag word into each core's data RAM before
# releasing reset, so the two ramps are distinguishable in the one shared host buffer:
#
#     tag  = *0x80000FFC                       ; core << 16, written by the testbench
#     for i in 0..1023: *(0x40000000 + 4*i) = tag + i     ; the ramp, word stores
#     *(0x40001001) = 0xA5                     ; sb  — proves the byte mask survives to AXI wstrb
#     *(0x40001006) = 0x1234                   ; sh  — half-word, upper lanes
#     *(0x40002000) = 0xDA7A                   ; the last WINDOW store (an in-window ordering sentinel)
#     *(0x4010)     = 1                        ; the DONE register — the program's last store
#     spin forever
#
# 0x40000000 is the core's write-only 16 MB host window (`RiscvSoc.hostWinBase`): stores retire posted
# and land in PS DDR4 at base + (core << 24) + offset.
#
# Built for the riscq core (RV32I, reset vector 0x80000000). Rebuild with:
#   riscv64-unknown-elf-clang -march=rv32i -mabi=ilp32 -mno-relax \
#     -nostdlib -static -Wl,-Ttext=0x80000000 -Wl,-e,_start \
#     src/riscq/soc/sim/sw/host_window.S -o src/riscq/soc/sim/sw/host_window.elf
# (the toolchain `software/riscq/build.py` uses — /config/build/riscv-toolchain/bin; the older
#  /config/build/riscv-install/bin/clang has no riscv linker on this box)

  .section .text.init, "ax"
  .globl _start
_start:
  li   t0, 0x80000ffc      # per-core tag slot (testbench-written)
  lw   a0, 0(t0)           # a0 = core << 16
  li   t1, 0x40000000      # host window base
  li   t2, 0               # i
  li   t3, 1024            # ramp length
1:
  add  a1, a0, t2          # value = (core << 16) | i
  sw   a1, 0(t1)
  addi t1, t1, 4
  addi t2, t2, 1
  blt  t2, t3, 1b

  li   t1, 0x40001000
  li   a1, 0xa5
  sb   a1, 1(t1)           # byte lane 1 of the word at offset 0x1000
  li   a1, 0x1234
  sh   a1, 6(t1)           # upper half of the word at offset 0x1004

  li   t1, 0x40002000
  li   a1, 0xda7a
  sw   a1, 0(t1)           # in-window sentinel — must be this core's last window beat

  li   t1, 0x4010          # DONE register (specs/software/23) — the control block, not the window
  li   a1, 1
  sw   a1, 0(t1)           # the program's last store; the host polls this, not the RAM

2:
  j    2b                  # spin (the reset vector never falls through into uninitialised memory)
