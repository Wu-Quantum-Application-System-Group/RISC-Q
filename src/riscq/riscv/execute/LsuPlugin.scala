package riscq.riscv.execute

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.misc.pipeline.Payload
import spinal.lib.misc.plugin.FiberPlugin
import riscq.riscv.{Global, RiscqParam}
import riscq.riscv.decode.{Decode, DecoderPlugin, Rv32i}
import riscq.riscv.misc.{DataMemBus, DataMemBusParam, PipelinePlugin}

/** Load/store classification control signals this plugin owns and registers with the decoder. */
object LsuPlugin extends AreaObject {
  val IS_LOAD  = Payload(Bool())
  val IS_STORE = Payload(Bool())
}

/**
 * Cacheless load/store unit over the simplified single-outstanding data bus (`dBus`, a
 * [[riscq.riscv.misc.DataMemBus]]: a `cmd` request stream + a `rsp` response flow): `LB/LH/LW/LBU/LHU`,
 * `SB/SH/SW`. It lives in the single execute stage and **halts** it until the memory access answers
 * (the first time the riscq pipeline ever back-pressures).
 *
 * The effective address `rs1 + imm` is already computed by `SrcPlugin` as `Execute.ADD_SUB`
 * (loads carry the I-immediate, stores the S-immediate), so the LSU adds no adder of its own.
 *
 * One simple code path: every access is a **word-sized, word-aligned** transfer with a byte mask. A
 * load drives `cmd.write = False` and reads the whole word back on `rsp`, then the addressed bytes are
 * shifted down and sign/zero-extended; a store drives `cmd.write = True` with the bytes/mask shifted
 * into the right lane and waits for the one-cycle ack on `rsp`. (Misaligned access is out of scope —
 * RV32I requires natural alignment and the tests obey it.)
 *
 * Keeping the LSU on `DataMemBus` leaves the full Tilelink A/D boilerplate at the core→fabric boundary
 * (`DataMemBusToTilelink`), so the plugin stays small. Unlike fetch, execute is the oldest stage, so a
 * load/store is never flushed mid-flight — no orphaned-response handling is needed. This is the depth-1
 * stripping of VexiiRiscv's `LsuCachelessPlugin` (no AMO, no MMU/PMA, no store buffer).
 */
class LsuPlugin(p: RiscqParam) extends FiberPlugin {
  import LsuPlugin._

  val logic = during setup new Area {
    val pp    = host[PipelinePlugin]
    val dec   = host[DecoderPlugin]
    val lock  = retains(pp.elaborationLock)
    val dlock = retains(dec.elaborationLock)
    awaitBuild()

    // ---- Register this plugin's control-signal decodings with the decoder (built at decodeAt). ----
    import Rv32i._
    dec.addDecodingDefault(IS_LOAD, False)
    dec.addDecodingDefault(IS_STORE, False)
    Seq(LB, LH, LW, LBU, LHU).foreach(dec.addDecoding(_, IS_LOAD, True))
    Seq(SB, SH, SW).foreach(dec.addDecoding(_, IS_STORE, True))
    dlock.release()

    // The data bus spans the full 32-bit SoC map (put window, readout, data RAM) and any rs1+imm
    // pointer ⇒ it stays XLEN-wide regardless of how narrow the fetch PC becomes.
    val dBus = master(DataMemBus(DataMemBusParam(addressWidth = Global.XLEN, dataWidth = 32)))
    dBus.simPublic()

    val c = pp.ctrl(p.executeAt)

    val lsu = new c.Area {
      val funct3  = apply(Decode.FUNCT3)

      val isLoad  = apply(IS_LOAD)
      val isStore = apply(IS_STORE)
      // Wrong-path gate: the branch corrector resolves at executeAt+1 (jumpAt), so an older
      // branch can flush this execute stage the same cycle a younger load/store sits here.
      // `throwWhen` raises `isCancel` (and drops `isFiring`) but does NOT clear `isValid`, so we
      // must drop `active` on cancel ourselves — otherwise a wrong-path store would still drive its
      // Tilelink PutPartial. We use the node's own `up.isCancel` (not BranchPlugin's signal) to
      // avoid a plugin cross-dependency. Loads are gated too (no spurious bus traffic on wrong path).
      val cancelled = up.isCancel
      val active     = isValid && !cancelled && (isLoad || isStore)

      // ---- Single-outstanding handshake: send one cmd, halt the stage until the response. ----
      val cmdSent = RegInit(False)
      val rspGot  = RegInit(False)
      val rspData = Reg(Bits(32 bits))
      val rspNow  = rspGot || dBus.rsp.valid

      when(dBus.cmd.fire)   { cmdSent := True }
      when(dBus.rsp.valid)  { rspGot := True; rspData := dBus.rsp.data }  // rsp is a Flow; we're always ready
      when(down.isFiring)   { cmdSent := False; rspGot := False }   // reset at commit
      // E2 (baked in): the wrong-path `cancelled` (up.isCancel = the jumpAt mispredict) is kept OUT of
      // the halt qualifier so it stops feeding the ready/CE spine. A cancelled load/store is only ever
      // hit on its first execute cycle (no cmd in flight), and the flush clears this stage's valid next
      // edge off the ready spine, so the one extra halted cycle sits inside the mispredict flush shadow —
      // zero IPC. `cmd.valid`/`first`/`dbg` keep the `active` qualifier (no wrong-path bus traffic).
      val haltActive = isValid && (isLoad || isStore)
      haltWhen(haltActive && !rspNow)

      // ---- Command-address latch. The operands (rs1/rs2) arrive through the 1-deep bypass whose
      //      window is a single cycle, but a load/store *halts* this stage for the whole memory
      //      access. So we must snapshot everything derived from rs1/rs2 (effective address, store
      //      data, byteOff / shift / mask) on the first active cycle and use the snapshot from then
      //      on — otherwise the re-driven a-channel and `word |>> shift` would read closed-bypass
      //      garbage on later stall cycles. funct3/opcode are StageLink-held, already stable.
      val latched = RegInit(False)              // set once the command is formed, cleared at commit
      val first   = active && !latched          // the one cycle we capture the live (bypassed) inputs
      when(first)         { latched := True }
      when(down.isFiring) { latched := False }

      val liveAddr      = Execute.ADD_SUB.asUInt                 // rs1 + imm (bypassed operands)
      val liveStoreData = apply(Execute.RS2_DATA)                // rs2 (bypassed)
      val addrReg       = Reg(UInt(Global.XLEN bits))
      val storeDataReg  = Reg(Bits(Global.XLEN bits))
      when(first) { addrReg := liveAddr; storeDataReg := liveStoreData }
      // B4 (baked in): the operand-snapshot pass-through select is `!latched` alone. `first`'s `active`
      // term carries the jumpAt mispredict broadcast (via up.isCancel) into byteOff → shift → the 32-bit
      // load-extend cone, which synthesis maps into per-bit FDSE set pins on the LOAD_DATA capture.
      // Equivalent wherever the value is consumed: when active, `first == !latched`; when idle the live
      // pass-through is unused (cmd.valid, the downstream valid and the dbg qualifier all gate on `active`).
      val snapSel   = !latched
      val addr      = snapSel ? liveAddr      | addrReg          // stable from the first active cycle on
      val rawStore  = snapSel ? liveStoreData | storeDataReg
      val byteOff   = addr(1 downto 0)
      val shift     = (byteOff << 3).resize(5)                   // 0/8/16/24-bit lane offset

      // E1 (baked in): the *load*-result down-shift is taken straight from the *registered* addrReg
      // instead of riding the live effective-address mux. The load word is consumed only with the
      // response, which arrives >=1 cycle after the first active cycle (dBus.rsp.valid implies latched;
      // the >=1-cycle rsp contract, MemBus.md), by which time addrReg holds this access's address. This
      // cuts SRC1/SRC2/DO_SUB -> the AGU adder -> addr(1:0) out of the 32-bit sign/zero-extend cone. The
      // cmd/mask/store lanes keep `shift` (from the live `addr`) — they must issue on the first cycle.
      // Equivalent wherever consumed: when the load fires latched is set, so addrReg(1:0) == addr(1:0).
      val loadShift = (addrReg(1 downto 0) << 3).resize(5)
      // The contract this relies on, checked in simulation only (absent from synthesis): a response
      // never arrives on the load's first active cycle, so addrReg is valid when `word` is used.
      GenerationFlags.simulation {
        assert(!(dBus.rsp.valid && !latched),
          "dBus.rsp answered before the command was latched — violates the >=1-cycle rsp contract the load-shift relies on")
      }

      // ---- Command (word-sized, word-aligned, byte-masked). ----
      val sizeBytes = Bits(dBus.p.dataBytes bits)
      switch(funct3(1 downto 0)) {       // funct3 low bits select 1 / 2 / 4 byte access
        is(0)   { sizeBytes := B"0001" } // byte
        is(1)   { sizeBytes := B"0011" } // half
        default { sizeBytes := B"1111" } // word
      }
      val fullMask  = B((1 << dBus.p.dataBytes) - 1, dBus.p.dataBytes bits)
      val storeData = (rawStore |<< shift).resize(32)

      dBus.cmd.valid   := active && !cmdSent
      dBus.cmd.write   := isStore
      dBus.cmd.address := (addr & ~U(3, Global.XLEN bits)).resized
      dBus.cmd.mask    := isStore ? (sizeBytes |<< byteOff).resize(dBus.p.dataBytes) | fullMask
      dBus.cmd.data    := storeData            // a load ignores data/mask (the bridge issues a read)

      // ---- Load result: shift the addressed bytes down, then sign/zero-extend per funct3. ----
      val word    = rspGot ? rspData | dBus.rsp.data
      val shifted = (word |>> loadShift).resize(32)
      val byte    = shifted(7 downto 0)
      val half    = shifted(15 downto 0)
      val loaded  = Bits(32 bits)
      loaded := shifted                              // LW
      switch(funct3) {
        is(B"000") { loaded := byte.asSInt.resize(32).asBits } // LB  (sign-extend)
        is(B"001") { loaded := half.asSInt.resize(32).asBits } // LH  (sign-extend)
        is(B"100") { loaded := byte.resize(32) }               // LBU (zero-extend)
        is(B"101") { loaded := half.resize(32) }               // LHU (zero-extend)
      }
      Execute.LOAD_DATA := loaded
      // IS_LOAD is a decoded payload, read by WriteBackPlugin.

      // Whitebox the committed access (effective address, byte length, value) for the probe's
      // trace backends: stores report the raw register value, loads the extended result.
      val nbytes = UInt(3 bits)
      switch(funct3(1 downto 0)) {
        is(0)   { nbytes := 1 }
        is(1)   { nbytes := 2 }
        default { nbytes := 4 }
      }
      val dbgMemValid = CombInit(down.isFiring && active)
      val dbgIsStore  = CombInit(isStore)
      val dbgAddr     = CombInit(addr)
      val dbgSize     = CombInit(nbytes)
      val dbgData     = CombInit(isStore ? rawStore | loaded) // latched store value (bypass may be closed at commit)
      dbgMemValid.simPublic()
      dbgIsStore.simPublic()
      dbgAddr.simPublic()
      dbgSize.simPublic()
      dbgData.simPublic()
    }

    lock.release()
  }
}
