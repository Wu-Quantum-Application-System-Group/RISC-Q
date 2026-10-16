package riscq.riscv.csr

/**
 * The handful of machine-mode CSR addresses and trap-cause codes riscq implements — just
 * enough for the standard `riscv-tests` `-p` (machine-mode) startup and termination sequence.
 *
 * Far less than a real privileged spec: only [[MSTATUS]]/[[MTVEC]]/[[MEPC]]/[[MCAUSE]] (and
 * a few scratch/interrupt regs) carry real behaviour; every other CSR the `-p` startup pokes
 * (satp, pmp*, medeleg/mideleg, …) is accepted as a write-ignored / read-zero WARL stub so
 * the boot code runs without illegal-instruction traps. No S/U mode, no interrupts, no PMP.
 */
object Csr {
  // ---- CSR addresses (instr[31:20]) ----
  val MSTATUS  = 0x300
  val MISA     = 0x301
  val MEDELEG  = 0x302
  val MIDELEG  = 0x303
  val MIE      = 0x304
  val MTVEC    = 0x305
  val MSCRATCH = 0x340
  val MEPC     = 0x341
  val MCAUSE   = 0x342
  val MTVAL    = 0x343
  val MIP      = 0x344
  val MHARTID  = 0xF14

  // ---- Machine trap causes (mcause, synchronous) ----
  val CAUSE_ILLEGAL = 2
  val CAUSE_EBREAK  = 3
  val CAUSE_ECALL_M = 11

  // ---- mstatus bit positions we model ----
  val MSTATUS_MIE  = 3
  val MSTATUS_MPIE = 7
  val MSTATUS_MPP  = 11 // two bits [12:11]

  // funct12 (instr[31:20]) selecting the SYSTEM environment ops (funct3 == 0)
  val FUNCT12_ECALL  = 0x000
  val FUNCT12_EBREAK = 0x001
  val FUNCT12_MRET   = 0x302
}
