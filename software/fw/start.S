/* riscq bare-metal entry: sp to RAM top, zero .bss, __rq_status = RUNNING, call main,
 * publish DONE (0xD04E0000 | main's low 16 return bits), raise the hardware DONE register, park.
 *
 * The host detects completion from the hardware register (RQ_CTRL_DONE, specs/software/23), not from
 * __rq_status — polling that word would read the core's RAM through the port instruction fetch shares
 * with the host image-load master. __rq_status stays as the exit-code carrier, and it is written
 * FIRST so a host that reads it after the poll sees the finished value. */

#include "riscq_map.h"

    .section .text.init, "ax"
    .globl _start
_start:
    la   sp, __stack_top
    la   t0, __bss_start          /* zero .bss (the flat image carries no .bss bytes) */
    la   t1, __bss_end
1:  bgeu t0, t1, 2f
    sw   x0, 0(t0)
    addi t0, t0, 4
    j    1b
2:  la   t0, __rq_status
    li   t1, 1                    /* RUNNING */
    sw   t1, 0(t0)
    call main
    la   t0, __rq_status
    slli a0, a0, 16               /* status = 0xD04E0000 | (ret & 0xFFFF) */
    srli a0, a0, 16
    lui  t1, 0xD04E0
    or   t1, t1, a0
    sw   t1, 0(t0)
    li   t0, RQ_CTRL_DONE         /* the program's LAST store: the host polls this register */
    li   t1, 1
    sw   t1, 0(t0)
3:  j    3b                       /* park */

    .section .data
    .align 2
    .globl __rq_status
    .type  __rq_status, @object
    .size  __rq_status, 4
__rq_status:
    .word 0                       /* 0 = RESET, 1 = RUNNING, 0xD04E....  = DONE */
    .globl __rq_magic
    .type  __rq_magic, @object
    .size  __rq_magic, 4
__rq_magic:
    .word 0x52515121              /* "RQQ!" sanity marker */
