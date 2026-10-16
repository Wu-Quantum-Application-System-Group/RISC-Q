package riscq.riscv.csr

import spinal.core._
import spinal.core.sim._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.{Decode, DecoderPlugin, Rv32i}
import riscq.riscv.execute.Execute
import riscq.riscv.fetch.{Fetch, PcPlugin}
import riscq.riscv.misc.PipelinePlugin

/** The CSR-access classification signal this plugin owns and registers with the decoder. */
object CsrPlugin extends AreaObject {
  val IS_CSR = Payload(Bool()) // a Zicsr CSRR* (SYSTEM, funct3 =/= 000)
  // The SYSTEM env ops are pre-decoded at decodeAt (baked in): one key each so the
  // jumpAt-visible trap/mret qualifier is a registered bit instead of the opcode/funct3/funct12
  // compare cone. Bit-exact vs the old live executeAt classification (RVLS).
  val IS_ECALL  = Payload(Bool())
  val IS_EBREAK = Payload(Bool())
  val IS_MRET   = Payload(Bool())
}

/**
 * Minimal machine-mode **Zicsr + trap** layer — the smallest privileged slice that lets the
 * standard `riscv-tests` `-p` programs boot and terminate. It lives in the single execute
 * stage and reuses the same redirect + `throwWhen`-flush idiom as [[riscq.riscv.execute.BranchPlugin]].
 *
 * What it does:
 *  - **Zicsr**: `CSRRW/S/C` and the `*I` variants. Reads the old CSR value to rd (via
 *    `Execute.CSR_RD_DATA`), then writes the new value, with the standard "no write when
 *    rs1/zimm == 0 for set/clear" rule.
 *  - **Trap entry**: a retiring `ECALL`/`EBREAK` (and any illegal instruction) saves
 *    `mepc`/`mcause`, pushes the `mstatus` interrupt-enable stack, and redirects to `mtvec`.
 *  - **`MRET`**: pops the `mstatus` stack and redirects to `mepc`. `WFI`/`FENCE`/`FENCE.I`
 *    are nops.
 *
 * Deliberately tiny next to VexiiRiscv's `PrivilegedPlugin`: machine-mode only, no
 * interrupts/CLINT, no S/U mode, no PMP/MMU, direct `mtvec` only. CSRs the `-p` startup pokes
 * but riscq doesn't model (satp, pmp*, medeleg/mideleg, …) are write-ignored / read-zero WARL
 * stubs so the boot code never hits an illegal-instruction trap. See [[Csr]].
 */
class CsrPlugin(p: RiscqParam) extends FiberPlugin {
  import CsrPlugin._

  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val pcp   = host[PcPlugin]
    val dec   = host[DecoderPlugin]
    val lock  = retains(pp.elaborationLock)
    val rlock = retains(pcp.redirectRetainer)
    val dlock = retains(dec.elaborationLock)
    awaitBuild()

    // ---- Register this plugin's control-signal decoding with the decoder (built at decodeAt). ----
    dec.addDecodingDefault(IS_CSR, False)
    Seq(Rv32i.CSRRW, Rv32i.CSRRS, Rv32i.CSRRC, Rv32i.CSRRWI, Rv32i.CSRRSI, Rv32i.CSRRCI)
      .foreach(dec.addDecoding(_, IS_CSR, True))
    // Baked in: pre-decode the env ops as control payloads (built at decodeAt) so the
    // jumpAt-visible trap/mret qualifier reads a registered bit instead of the executeAt compare cone.
    dec.addDecodingDefault(IS_ECALL, False);  dec.addDecoding(Rv32i.ECALL,  IS_ECALL, True)
    dec.addDecodingDefault(IS_EBREAK, False); dec.addDecoding(Rv32i.EBREAK, IS_EBREAK, True)
    dec.addDecodingDefault(IS_MRET, False);   dec.addDecoding(Rv32i.MRET,   IS_MRET, True)
    dlock.release()

    val c        = pp.ctrl(p.executeAt)
    val redirect = pcp.newRedirect()

    // ---- The machine CSR file (only the architecturally-live registers; see Csr) ----
    // With `p.csrWarl` each CSR's WARL/WLRL latitude is applied: fields that can only ever hold one
    // value become constants, and the rest keep only the bits that actually vary — the dead bits are
    // driven 0 (or dropped) so Vivado synthesises away the surplus flops. This is also strictly *more*
    // machine-only-Spike-conformant (MPP≡11, mepc[1:0]≡0); see the per-field write sites.
    val mstatusMie  = RegInit(False)
    val mstatusMpie = RegInit(False)
    // MPP machine-only ⇒ WARL-hardwire 11 (a constant, like machine-only Spike which clamps MPP to M);
    // otherwise a writable 2-bit field that mret clears to 00.
    val mstatusMpp  = if (p.csrWarl) B"11" else RegInit(B"11")
    // No reset on mtvec/mepc/mcause/mtval/mscratch/mie — intentionally:
    // their reset value is never architecturally observed, so dropping the init keeps them out of the
    // async-reset control-set group (denser packing / lighter reset net for the replicated multi-core
    // build), with identical behaviour. mepc/mcause are HW-written on a trap before any read; mtval is
    // only ever 0; mscratch is a SW scratchpad written before read; mie is dead (no interrupts, and
    // mstatus.MIE=0 gates anyway); mtvec is programmed by software before the first trap. Do NOT add an
    // init back: it would re-grow the reset group for no functional gain.
    val mtvec       = Reg(UInt(Global.FETCH_PC_WIDTH bits))
    val mepc        = Reg(UInt(Global.FETCH_PC_WIDTH bits))
    val mcause      = Reg(Bits(Global.XLEN bits))
    // mtval: HW only ever writes 0 (on traps) ⇒ WARL-hardwire 0 (the spec permits hardwired-zero mtval).
    val mtval       = if (p.csrWarl) B(0, Global.XLEN bits) else Reg(Bits(Global.XLEN bits))
    val mscratch    = Reg(Bits(Global.XLEN bits))
    val mie         = Reg(Bits(Global.XLEN bits))          // stored, but no interrupts are taken

    // ---- WARL/WLRL narrowing helpers (identity when the flag is off) ----
    // A PC-like CSR (mtvec/mepc): direct-mode mtvec + IALIGN=32 ⇒ the low two bits read 0.
    def warlPc(v: Bits): UInt = {
      val u = v.asUInt.resize(Global.FETCH_PC_WIDTH)
      if (p.csrWarl) u & ~U(3, Global.FETCH_PC_WIDTH bits) else u
    }
    // mcause is WLRL: only the 4-bit synchronous cause is storable (the interrupt bit is never set).
    def warlCause(v: Bits): Bits = if (p.csrWarl) v & B(0xF, Global.XLEN bits) else v
    // mie: no interrupts implemented ⇒ keep only the M-mode enable bits MSIE/MTIE/MEIE (3/7/11),
    // matching machine-only Spike's read-back; every other bit reads 0.
    def warlMie(v: Bits): Bits = if (p.csrWarl) v & B(0x888, Global.XLEN bits) else v

    val csr = new c.Area {
      val word    = up(Fetch.WORD)                    // registered instruction word at execute
      val funct3  = apply(Decode.FUNCT3)
      val addr    = word(31 downto 20).asUInt          // CSR address
      val rs1Idx  = word(Rv32i.rs1Range)
      val zimm    = word(Rv32i.rs1Range).asUInt.resize(Global.XLEN).asBits // 5-bit zero-extended

      val isCsr    = apply(IS_CSR)                      // decoded CSRR* (this plugin's own key)

      // Baked in: read the registered decoder keys instead of the live executeAt compares.
      // Equivalent for every legal env word (the key is True exactly on that encoding); illegal words
      // trap via isIllegal regardless of these bits.
      val isEcall  = apply(IS_ECALL)
      val isEbreak = apply(IS_EBREAK)
      val isMret   = apply(IS_MRET)
      val isIllegal = !apply(Decode.LEGAL)             // every non-RV32I word traps (cause 2)

      // ---- CSR read (old value) ----
      val mstatusRead = Bits(Global.XLEN bits)
      mstatusRead := 0
      mstatusRead(Csr.MSTATUS_MIE)                          := mstatusMie
      mstatusRead(Csr.MSTATUS_MPIE)                         := mstatusMpie
      mstatusRead(Csr.MSTATUS_MPP + 1 downto Csr.MSTATUS_MPP) := mstatusMpp

      val rdValue = Bits(Global.XLEN bits)
      rdValue := 0
      switch(addr) {
        is(Csr.MSTATUS)  { rdValue := mstatusRead }
        is(Csr.MTVEC)    { rdValue := Global.fullPc(mtvec).asBits.resized } // rebuild full mtvec for SW read
        is(Csr.MEPC)     { rdValue := Global.fullPc(mepc).asBits.resized }  // rebuild full mepc  for SW read
        is(Csr.MCAUSE)   { rdValue := mcause }
        if (!p.csrWarl) is(Csr.MTVAL) { rdValue := mtval } // WARL: mtval ≡ 0 (the switch default)
        is(Csr.MSCRATCH) { rdValue := mscratch }
        is(Csr.MIE)      { rdValue := mie }
        // MHARTID + every unmodelled CSR read as 0 (the default above)
      }

      // ---- CSR write (new value) ----
      val src       = funct3(2) ? zimm | Execute.RS1_DATA           // *I variants use zimm
      val nextValue = Bits(Global.XLEN bits)
      switch(funct3(1 downto 0)) {
        is(B"01") { nextValue := src }              // CSRRW(I)
        is(B"10") { nextValue := rdValue | src }    // CSRRS(I)  (set)
        default   { nextValue := rdValue & ~src }   // CSRRC(I)  (clear)
      }
      // Set/clear with rs1/zimm == 0 must have no write side effect (spec).
      val writeEn = isCsr && down.isFiring && (funct3(1 downto 0) === B"01" || rs1Idx =/= 0)
      // csrCommitMaxFanout: writeEn is the root of the per-CSR clock-enable decode (the observed
      // `valid → … → mscratch/CE` serial chain) — cap its fanout so Vivado replicates the qualifier
      // near each CSR's enable instead of routing one net into every CE. Bit-exact (attribute only).
      if (p.csrCommitMaxFanout > 0) writeEn.addAttribute("MAX_FANOUT", p.csrCommitMaxFanout)

      // Every writable CSR with its write action, in address-list order. Unmodelled CSRs have no
      // entry: their writes are ignored.
      val writeActions: Seq[(Int, () => Unit)] = Seq(
        Option(Csr.MSTATUS -> { () =>
          mstatusMie  := nextValue(Csr.MSTATUS_MIE)
          mstatusMpie := nextValue(Csr.MSTATUS_MPIE)
          if (!p.csrWarl) mstatusMpp := nextValue(Csr.MSTATUS_MPP + 1 downto Csr.MSTATUS_MPP)
        }),
        Option(Csr.MTVEC    -> (() => mtvec    := warlPc(nextValue))),    // WARL direct-only: [1:0] forced 0
        Option(Csr.MEPC     -> (() => mepc     := warlPc(nextValue))),    // WARL IALIGN=32: [1:0] forced 0
        Option(Csr.MCAUSE   -> (() => mcause   := warlCause(nextValue))), // WLRL: only the low 4-bit code stored
        if (p.csrWarl) None else Some(Csr.MTVAL -> (() => mtval := nextValue)), // WARL: mtval hardwired 0
        Option(Csr.MSCRATCH -> (() => mscratch := nextValue)),
        Option(Csr.MIE      -> (() => mie      := warlMie(nextValue)))    // WARL: only MSIE/MTIE/MEIE writable
      ).flatten

      // Baked in: each CSR gets its own flat `writeEn && (addr === X)` enable, decoded in
      // PARALLEL from the registered execute payloads — instead of a when(writeEn){switch(addr)}
      // nest, which Vivado maps into a serial decode chain through the per-CSR clock enables (the
      // observed 5-level `valid → … → mstatusMie → mtvec → mscratch/CE` path). The addresses are
      // distinct so at most one enable fires ⇒ bit-identical to the switch; the trap/mret writes
      // below keep last-assignment priority.
      for ((csrAddr, action) <- writeActions) when(writeEn && addr === csrAddr) { action() }

      Execute.CSR_RD_DATA := rdValue
      // IS_CSR is a decoded payload, read by WriteBackPlugin.

      // ---- Trap entry (ecall/ebreak/illegal) + MRET: redirect PC and flush younger stages ----
      val pc        = apply(Global.PC)
      val exception = down.isFiring && (isEcall || isEbreak || isIllegal)
      val cause     = UInt(4 bits)
      when(isIllegal)        { cause := Csr.CAUSE_ILLEGAL }
        .elsewhen(isEbreak)  { cause := Csr.CAUSE_EBREAK }
        .otherwise           { cause := Csr.CAUSE_ECALL_M }

      when(exception) {
        mepc        := pc                                  // pc is 4-aligned ⇒ mepc[1:0] stays 0 (WARL)
        mcause      := cause.resize(Global.XLEN).asBits    // cause ∈ {2,3,11}; upper bits 0 (WLRL-safe)
        if (!p.csrWarl) mtval := 0                          // WARL: mtval hardwired 0
        mstatusMpie := mstatusMie
        mstatusMie  := False
        if (!p.csrWarl) mstatusMpp := B"11"                 // WARL: MPP already constant 11
      }
      val doMret = isMret && down.isFiring
      when(doMret) {
        mstatusMie  := mstatusMpie
        mstatusMpie := True
        // Machine-only: MPP stays 11. WARL hardwires it; the non-WARL path clears to 00 (legacy — a
        // latent divergence from machine-only Spike, harmless
        // today because nothing reads MPP after mret).
        if (!p.csrWarl) mstatusMpp := B"00"
      }

      val doRedirect = exception || doMret
      redirect.valid   := doRedirect
      // Direct mtvec. Under WARL mtvec[1:0] is already 0, so the alignment mask folds away.
      val mtvecTarget  = if (p.csrWarl) mtvec else mtvec & ~U(3, Global.FETCH_PC_WIDTH bits)
      redirect.payload := exception ? mtvecTarget | mepc
      // Flush the younger (wrong-path) stages — off the up.ready spine (see PipelinePlugin.throwStagesBefore).
      pp.throwStagesBefore(doRedirect, p.executeAt)
      pp.throwSkidsBefore(doRedirect, p.executeAt) // also cancel any skid-buffered wrong-path instruction

      // ---- Whitebox for the probe / trace backends ----
      val dbgTrap     = CombInit(exception)
      val dbgCause    = CombInit(cause)
      val dbgCsrValid = CombInit(isCsr && down.isFiring)
      val dbgCsrAddr  = CombInit(addr)
      val dbgCsrWrite = CombInit(writeEn)
      val dbgCsrWdata = CombInit(nextValue)
      val dbgCsrRdata = CombInit(rdValue)
      dbgTrap.simPublic()
      dbgCause.simPublic()
      dbgCsrValid.simPublic()
      dbgCsrAddr.simPublic()
      dbgCsrWrite.simPublic()
      dbgCsrWdata.simPublic()
      dbgCsrRdata.simPublic()
    }

    rlock.release()
    lock.release()
  }
}
