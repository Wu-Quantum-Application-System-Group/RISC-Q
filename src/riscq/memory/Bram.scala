package riscq.memory

import spinal.core._
import spinal.lib._
import scala.io.Source

/**
 * BlackBox over the Xilinx true-dual-port BRAM HDL template (`src/riscq/memory/BramBlackBox.v`):
 * a write-first, byte-write block RAM with the two ports on *independent* clocks (`clka`/`clkb`),
 * sized by `NB_COL` (byte columns) × `COL_WIDTH` (8) × `RAM_DEPTH`. Drive it through [[Bram]].
 *
 * `RAM_PERFORMANCE` picks the output stage: `"LOW_LATENCY"` reads the memory register directly
 * (address → `dout` latency 1); `"HIGH_PERFORMANCE"` adds one output register (latency 2, better
 * clock-to-out). The output register is gated by `regce*` and cleared by `rst*` (output only — the
 * stored contents are never reset).
 *
 * @param dataWidth        data width in bits — `NB_COL * COL_WIDTH`, a multiple of 8 (one column /
 *                         write-enable bit per byte).
 * @param depth            number of words (`RAM_DEPTH`); the address width is `log2Up(depth)`,
 *                         matching the template's internal `clogb2(RAM_DEPTH-1)`.
 * @param highPerformance  true → `"HIGH_PERFORMANCE"` (output register, latency 2);
 *                         false → `"LOW_LATENCY"` (latency 1).
 */
case class BramBlackBox(dataWidth: Int, depth: Int, highPerformance: Boolean = true) extends BlackBox {
  val maskWidth    = dataWidth / 8
  val addressWidth = log2Up(depth)
  addGeneric("NB_COL", maskWidth)
  addGeneric("COL_WIDTH", 8)
  addGeneric("RAM_DEPTH", depth)
  addGeneric("RAM_PERFORMANCE", if (highPerformance) "HIGH_PERFORMANCE" else "LOW_LATENCY")

  val io = new Bundle {
    val clka   = in Bool()
    val clkb   = in Bool()
    val ena    = in Bool()
    val enb    = in Bool()
    val rsta   = in Bool()
    val rstb   = in Bool()
    val regcea = in Bool()
    val regceb = in Bool()
    val wea    = in Bits(maskWidth bit)
    val web    = in Bits(maskWidth bit)
    val addra  = in Bits(addressWidth bit)
    val addrb  = in Bits(addressWidth bit)
    val dina   = in Bits(dataWidth bit)
    val dinb   = in Bits(dataWidth bit)
    val douta  = out Bits(dataWidth bit)
    val doutb  = out Bits(dataWidth bit)
  }

  noIoPrefix()

  val source = Source.fromFile("src/riscq/memory/BramBlackBox.v").mkString
  setInlineVerilog(source)
}

/**
 * Typed true dual-port BRAM: wraps [[BramBlackBox]] with two `MemReadWritePort[T]` slave ports (one
 * per BRAM port, each in its own clock domain), translating the generic `MemReadWritePort` handshake
 * to the template's byte-write interface — the blackbox sibling of [[DualClockRam]]. A port writes
 * byte `i` when `enable && write && mask(i)`, and reads `1` (`LOW_LATENCY`) or `2`
 * (`HIGH_PERFORMANCE`) cycles after the address is presented (`enable` high, `write` low).
 *
 * The output register is held transparent (`regce*` tied high, `rst*` low) so a read always
 * propagates; reset the output via the consumer's pipeline, not the RAM. Concurrent same-address
 * access from both ports is a true-dual-port write/read race and must be avoided by the user, as for
 * any true dual-port RAM.
 *
 * @param dataType         payload type; its bit width must be a multiple of 8 (per-byte mask).
 * @param depth            number of words.
 * @param fastCd / slowCd  the two clock domains the ports live in (pass the same domain twice for a
 *                         single-clock BRAM).
 * @param highPerformance  output-register select (see [[BramBlackBox]]).
 */
case class Bram[T <: Data](
    dataType: T,
    depth: Int,
    fastCd: ClockDomain,
    slowCd: ClockDomain,
    outReg: Boolean = true,
) extends Component {
  val bitsWidth = dataType.getBitsWidth
  assert(bitsWidth % 8 == 0, "dataWidth must be a multiple of 8")
  val maskWidth    = bitsWidth / 8
  val addressWidth = log2Up(depth)
  val bram = BramBlackBox(bitsWidth, depth, outReg)

  val io = new Bundle {
    val port0 = slave port MemReadWritePort(dataType, addressWidth, maskWidth = maskWidth)
    val port1 = slave port MemReadWritePort(dataType, addressWidth, maskWidth = maskWidth)
  }

  /** Port aliases by clock-domain role, matching [[DualClockRam]]'s naming so [[Bram]] is a drop-in:
   *  `fastPort` = `io.port0` (fast / device clock `fastCd`); `slowPort` = `io.port1` (slow / host clock). */
  def fastPort = io.port0
  def slowPort = io.port1

  bram.io.clka := fastCd.readClockWire
  bram.io.clkb := slowCd.readClockWire

  // Port A — port0 (fast clock domain)
  bram.io.ena    := io.port0.enable
  bram.io.wea    := io.port0.mask & (io.port0.write #* maskWidth)
  bram.io.dina   := io.port0.wdata.asBits
  bram.io.addra  := io.port0.address.asBits
  bram.io.regcea := True
  bram.io.rsta   := False
  io.port0.rdata := bram.io.douta.as(dataType)

  // Port B — port1 (slow clock domain)
  bram.io.enb    := io.port1.enable
  bram.io.web    := io.port1.mask & (io.port1.write #* maskWidth)
  bram.io.dinb   := io.port1.wdata.asBits
  bram.io.addrb  := io.port1.address.asBits
  bram.io.regceb := True
  bram.io.rstb   := False
  io.port1.rdata := bram.io.doutb.as(dataType)
}

/** Emit `Bram.v` (32-bit × 256, fast/slow external clocks). Run with
  * `mill runMain riscq.memory.BramGen`. */
object BramGen extends App {
  SpinalConfig(netlistFileName = "Bram.v").generateVerilog(
    Bram(
      Bits(32 bits),
      depth = 256,
      fastCd = ClockDomain.external("fast"),
      slowCd = ClockDomain.external("slow")
    )
  )
}
