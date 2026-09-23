# pulse_sched.S — M4 CPU-in-the-loop control program (docs/soc/PulseTableSoc.md §5).
#
# The RISC-V control core schedules one gate pulse by issuing the exact register writes the M3
# test-master harness performed (PulseTableSocSim Part 2), but now over the CPU's own dBus through the
# posted link (docs/soc/ARCH.md): the stores funnel through the PutBridge onto one ordered
# Flow(Put), demuxed to the gate buffer. startTime is per-buffer, so it lives in the gate window:
#     time     = *0xbff8                ; TimeMemMap current batch time
#     *0x14100 = time + LEAD            ; gate buffer startTime (put window 0x10000 + 0x4100)
#     program gate-drive table[0] @ 0x10000 (freq/phase/amp/env/dur), then fire outId 0
#     spin forever                      ; no further dBus traffic
# startTime precedes the table writes and the fire on the same ordered posted path, so the fire enqueues
# table[0] with the just-written startTime (docs/soc/ARCH.md §5.1).
#
# Every RF field lives in bus bits [16,32) (posted Put field bitOffset = 16) ⇒ the stored word is value<<16.
# Built for the riscq core (RV32I, reset vector 0x80000000). Rebuild with:
#   /config/build/riscv-install/bin/clang -target riscv32 -march=rv32i -mabi=ilp32 -mno-relax \
#     -nostdlib -static -Wl,-Ttext=0x80000000 -Wl,-e,_start \
#     src/riscq/soc/sim/sw/pulse_sched.S -o src/riscq/soc/sim/sw/pulse_sched.elf

  .section .text.init, "ax"
  .globl _start
_start:
  li   t0, 0xbff8          # TimeMemMap time register
  lw   a0, 0(t0)           # a0 = current batch time
  addi a0, a0, 1024        # startTime = time + LEAD (well ahead of the live batch time)
  li   t0, 0x14100         # gate buffer startTime (posted, per-buffer — put window 0x10000 + 0x4100)
  sw   a0, 0(t0)

  li   t1, 0x10000         # gate-drive RF fiber base
  li   t2, 0x07080000      # freq  = 1800 << 16   @ +0x04
  sw   t2, 0x04(t1)
  li   t2, 0x07d00000      # phase = 2000 << 16   @ +0x10  (table[0])
  sw   t2, 0x10(t1)
  li   t2, 0x23280000      # amp   = 9000 << 16   @ +0x14
  sw   t2, 0x14(t1)
  li   t2, 0x000a0000      # env   =   10 << 16   @ +0x18  (envelope base address)
  sw   t2, 0x18(t1)
  li   t2, 0x00060000      # dur   =    6 << 16   @ +0x1C  (6 batches)
  sw   t2, 0x1c(t1)

  sw   x0, 0x00(t1)        # fire outId 0 ⇒ latch table[0] into the generator's queues at startTime

1:
  j    1b                  # spin (the reset vector never falls through into uninitialised memory)
