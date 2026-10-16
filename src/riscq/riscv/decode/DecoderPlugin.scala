package riscq.riscv.decode

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Retainer
import spinal.lib.logic.{DecodingSpec, Masked, Symplify}
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.fetch.Fetch
import riscq.riscv.misc.PipelinePlugin

import scala.collection.mutable

/**
 * Single-lane RV32I decoder + generic control-signal decoding engine.
 *
 * It produces the *intrinsic* decode results every consumer needs the same way — the positional
 * fields (rs1/rs2/rd, funct3/funct7), the legality flag, and the sign-extended immediate — as
 * `Decode.*` payloads.
 *
 * On top of that it is a generic **decoding engine** (mirrors VexiiRiscv's `DecoderPlugin` +
 * `DecoderService`): other plugins register, during their own setup, the per-instruction control
 * signals they consume (`addDecoding`/`addDecodingDefault`) and — for optional extensions — the
 * instruction encodings they add (`addInstruction`). This decoder collects them and builds one
 * `Symplify`/`DecodingSpec` pass per registered payload, all at `decodeAt`. That keeps each
 * extension's decoding inside its own `FiberPlugin` (a `MulPlugin` defines `SEL_MUL` and registers it
 * here, with no edit to this file) instead of hard-wiring every control bit into the decoder.
 *
 * Stripped to one lane: no aligner, no micro-op/uop framework, no traps.
 */
class DecoderPlugin(p: RiscqParam) extends FiberPlugin {
  // ---- Registration API (the simplified DecoderService). Plugins call these during their setup,
  //      while holding `elaborationLock` (via `retains`), so everything is registered before `logic`
  //      builds below. ----
  val elaborationLock = Retainer()

  // Optional-extension instruction encodings. Base RV32I legality/immediate still come from the
  // `Rv32i` catalog (so the decode-only config decodes correctly and a disabled extension's opcode is
  // illegal ⇒ traps); an extension adds its encodings here to make them legal.
  private val extraEncodings = mutable.LinkedHashSet[MaskedLiteral]()
  // Immediate-format spec: seeded from the base catalog in `logic`, extended per `addInstruction`.
  private val immTypeSpec = new DecodingSpec(Rv32i.ImmType()).setDefault(Masked(Rv32i.ImmType.NONE))
  // One DecodingSpec per registered control payload; built generically in `logic`.
  private val decodingSpecs = mutable.LinkedHashMap[Payload[_ <: BaseType], DecodingSpec[_ <: BaseType]]()
  private def getDecodingSpec(key: Payload[_ <: BaseType]): DecodingSpec[_ <: BaseType] =
    decodingSpecs.getOrElseUpdate(key, new DecodingSpec(key))

  /** Default value of a control payload, applied to every legal encoding that doesn't `addDecoding` it. */
  def addDecodingDefault(key: Payload[_ <: BaseType], value: Any): Unit =
    getDecodingSpec(key).setDefault(Masked(value))

  /** Set one control payload's value for one instruction encoding. */
  def addDecoding(enc: MaskedLiteral, key: Payload[_ <: BaseType], value: Any): Unit =
    getDecodingSpec(key).addNeeds(Masked(enc), Masked(value))

  /** Set several control payloads for one instruction encoding. */
  def addDecoding(enc: MaskedLiteral, decodings: Seq[(Payload[_ <: BaseType], Any)]): Unit =
    decodings.foreach { case (key, value) => addDecoding(enc, key, value) }

  /** Register an optional-extension instruction: make `enc` legal and give it an immediate format. */
  def addInstruction(enc: MaskedLiteral, immType: Rv32i.ImmType.E): Unit = {
    extraEncodings += enc
    immTypeSpec.addNeeds(Masked(enc), Masked(immType))
  }

  val logic = during setup new Area {
    val pp   = host[PipelinePlugin]
    val lock = retains(pp.elaborationLock)
    awaitBuild()

    // Wait until every plugin has registered its instructions / control decodings.
    elaborationLock.await()

    // The full legal-encoding set = base RV32I (intrinsic) ++ any optional-extension encodings. It is
    // both the legality cover and the `coverAll` every DecodingSpec.build minimises against.
    val encodings = (Rv32i.all ++ extraEncodings).map(Masked(_))
    // Base immediate formats (extensions already added theirs via addInstruction).
    for ((enc, fmt) <- Rv32i.instructions) immTypeSpec.addNeeds(Masked(enc), Masked(fmt))

    // Rebuild the sign-extended immediate from a raw instruction word + its format. A pure function of
    // the word, so it can run wherever the word is available: at decodeAt (deriveImmFromWord off) or, with
    // the flag on, at the consumer stage (regReadAt) — letting the decode→regRead link carry only the
    // 3-bit format instead of the 32-bit immediate.
    def decodeImm(instr: Bits, immType: Rv32i.ImmType.C): Bits = {
      // The five RISC-V immediate formats (unprivileged spec, Fig. 2.4). U/J keep their implicit low
      // zeros; the `S(...).resize` sign-extends each to XLEN.
      val immI = S(instr(31 downto 20))
      val immS = S(instr(31 downto 25) ## instr(11 downto 7))
      val immB = S(instr(31) ## instr(7) ## instr(30 downto 25) ## instr(11 downto 8) ## False)
      val immU = S(instr(31 downto 12) ## B"x000")
      val immJ = S(instr(31) ## instr(19 downto 12) ## instr(20) ## instr(30 downto 21) ## False)
      val imm  = Bits(Global.XLEN bits)
      switch(immType) {
        is(Rv32i.ImmType.I) { imm := immI.resize(Global.XLEN).asBits }
        is(Rv32i.ImmType.S) { imm := immS.resize(Global.XLEN).asBits }
        is(Rv32i.ImmType.B) { imm := immB.resize(Global.XLEN).asBits }
        is(Rv32i.ImmType.U) { imm := immU.resize(Global.XLEN).asBits }
        is(Rv32i.ImmType.J) { imm := immJ.resize(Global.XLEN).asBits }
        default             { imm := B(0, Global.XLEN bits) } // NONE (R-type / fence / system)
      }
      imm
    }

    val c = pp.ctrl(p.decodeAt)
    val decoder = new c.Area {
      val instr = up(Fetch.WORD) // registered instruction word arriving at the decode stage

      // --- Positional fields (same bit ranges for every RV32I instruction) ---
      val rs1    = apply(Decode.RS1)
      val rs2    = apply(Decode.RS2)
      val rd     = apply(Decode.RD)
      val funct3 = apply(Decode.FUNCT3)
      val funct7 = apply(Decode.FUNCT7)
      rs1    := instr(Rv32i.rs1Range).asUInt
      rs2    := instr(Rv32i.rs2Range).asUInt
      rd     := instr(Rv32i.rdRange).asUInt
      funct3 := instr(Rv32i.funct3Range)
      funct7 := instr(Rv32i.funct7Range)

      // --- Legality: does the word match any legal (base or enabled-extension) encoding? ---
      val legal = apply(Decode.LEGAL)
      legal := Symplify(instr, encodings)

      // --- Immediate: pick the format from the encoding, then sign-extend ---
      val immType = immTypeSpec.build(instr, encodings)
      // The sign-extended immediate. With deriveImmFromWord off it is carried as the Decode.IMM payload
      // (decode→regRead). With the flag on we carry only the 3-bit Decode.IMM_TYPE and rebuild IMM at
      // regReadAt (below); here `imm` is then a sim-only whitebox of the decode-stage value (pruned in
      // synthesis), keeping DecodeSim's check stable in both modes.
      val imm = decodeImm(instr, immType)
      if (p.deriveImmFromWord) apply(Decode.IMM_TYPE) := immType
      else                     apply(Decode.IMM)      := imm

      // --- Generic per-instruction control signals. ---
      // Each registered payload is decoded with one Symplify/DecodingSpec pass against the same legal
      // set above (defaults fill every legal encoding that didn't `addDecoding` it). Driven here at
      // `decodeAt`; the StageLinks register every result forward into regReadAt/execute, so each late
      // mux select the execute plugins read is register-direct. The owning plugins read these payloads
      // instead of comparing opcode/funct themselves. On an illegal word the defaults make every
      // control 0/RF/ADD (the CsrPlugin `!LEGAL` trap still fires).
      for ((key, spec) <- decodingSpecs)
        apply(key).assignFromBits(spec.build(instr, encodings).asBits)

      // Whitebox the decoded fields for the self-checking testbench.
      instr.simPublic()
      rs1.simPublic()
      rs2.simPublic()
      rd.simPublic()
      funct3.simPublic()
      funct7.simPublic()
      imm.simPublic()
      legal.simPublic()
    }

    // deriveImmFromWord: rebuild the immediate at the consumer stage (regReadAt) instead of carrying the
    // 32-bit Decode.IMM on the decode→regRead link. The word is already carried to executeAt (CsrPlugin),
    // so it is present here; only the 3-bit IMM_TYPE arrives on the decode→regRead link. Driver and the
    // SrcPlugin/BranchPlugin readers are all at regReadAt, so the rebuilt IMM crosses no StageLink and
    // costs no pipeline register for the wide immediate — only the narrow IMM_TYPE is carried.
    val immRebuild = p.deriveImmFromWord generate new Area {
      val cr   = pp.ctrl(p.regReadAt)
      val area = new cr.Area {
        apply(Decode.IMM) := decodeImm(apply(Fetch.WORD), apply(Decode.IMM_TYPE))
      }
    }

    lock.release()
  }
}
