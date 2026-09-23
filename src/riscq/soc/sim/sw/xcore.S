# xcore.S — put-network R2 CPU-in-the-loop program (specs/cross-core/02 §8.1): publish → barrier →
# a pulse conditioned on the OTHER core's shared bit, scheduled from the barrier's released time.
#
# Both cores run this same image. The host writes each core's slot (0 or 1) into the last RAM word
# before the reset release (RQ_PARAM-style). Then:
#     publish(group 0, slot = me, bit = (me == 0))       ; one store to the group node
#     me == 0: signal(core 1, mailbox 0, 0x77)           ; one store to core 1's inbox node
#     t0 = barrier(id 0, count 2)                        ; store the count, halting load of the release mailbox
#     me == 1: wait_signal(mailbox 0)                    ; halting load
#     other = board[0] >> (me ^ 1) & 1                   ; the other core's bit — fresh by ordering
#     startTime = t0 + 1024; program table[0]; fire iff other == 1
# Expected: core 1 fires (core 0 published 1), core 0 does not (core 1 published 0); both cores read
# the same t0, so core 1's pulse rises exactly at t0 + 1024.
#
# Node addresses: 0x10000 + node·0x10000 with node 16 = group 0, 20 = barrier 0, 37 = core 1's inbox
# (SocSpecMap: localNodes 16, groupNodes 4, barrierIds 16). Inbox: board@0x4300, release@0x4320,
# mailbox 0@0x4340 (put offset 0x140 from 0x4200).
# Built for the riscq core (RV32I, reset vector 0x80000000). Rebuild with:
#   /config/build/riscv-install/bin/clang -target riscv32 -march=rv32i -mabi=ilp32 -mno-relax \
#     -nostdlib -static -Wl,-Ttext=0x80000000 -Wl,-e,_start \
#     src/riscq/soc/sim/sw/xcore.S -o src/riscq/soc/sim/sw/xcore.elf

  .section .text.init, "ax"
  .globl _start
_start:
  li   t0, 0x80003ffc      # PARAM word (last word of the 4096-word RAM): my slot / core index
  lw   s0, 0(t0)

  # publish(group 0, slot s0, bit (s0 == 0)): data = (slot << 1) | bit
  slli t1, s0, 1
  seqz t2, s0
  or   t1, t1, t2
  li   t0, 0x110000        # node 16 = group 0
  sw   t1, 0(t0)

  # core 0 → core 1: signal(mailbox 0, 0x77)
  bnez s0, 1f
  li   t0, 0x260140        # node 37 = core 1's inbox, offset 0x140 = mailbox 0
  li   t1, 0x77
  sw   t1, 0(t0)
1:
  # barrier(id 0, count 2)
  li   t0, 0x150000        # node 20 = barrier 0
  li   t1, 2
  sw   t1, 0(t0)
  li   t0, 0x4320          # release mailbox: HALTS until the hub releases; a0 = t0
  lw   a0, 0(t0)

  # core 1: wait_signal(mailbox 0) → a2 = 0x77
  beqz s0, 2f
  li   t0, 0x4340
  lw   a2, 0(t0)
2:
  # the other core's bit from board[0]
  li   t0, 0x4300
  lw   a1, 0(t0)
  xori t3, s0, 1
  srl  a1, a1, t3
  andi a1, a1, 1

  # schedule on the gate channel (RF_CH0 @ 0x10000): startTime = t0 + 1024, table[0], fire iff a1
  addi a0, a0, 1024
  li   t0, 0x14100         # gate startTime
  sw   a0, 0(t0)
  li   t1, 0x10000
  li   t2, 0x07080000      # freq  = 1800 << 16
  sw   t2, 0x04(t1)
  li   t2, 0x07d00000      # phase = 2000 << 16   (table[0])
  sw   t2, 0x10(t1)
  li   t2, 0x23280000      # amp   = 9000 << 16
  sw   t2, 0x14(t1)
  li   t2, 0x000a0000      # env   =   10 << 16
  sw   t2, 0x18(t1)
  li   t2, 0x00060000      # dur   =    6 << 16
  sw   t2, 0x1c(t1)
  beqz a1, 3f
  sw   x0, 0x00(t1)        # fire outId 0
3:
  j    3b
