package riscq.memory

import spinal.core._
import spinal.lib._
/**
 * Vivado-targeted on-chip RAM. [[DualClockRam]] is a true dual-port block RAM whose two byte-write
 * read/write ports sit in two *different* clock domains (a clock-crossing BRAM), inferred from a
 * SpinalHDL `Mem`, for handing samples between a slow control clock and a fast converter clock.
 *
 * Its blackbox siblings — the same dual-port, dual-clock BRAM / UltraRAM wrapped around the Xilinx
 * HDL templates — live alongside in [[Bram]] (`Bram.scala`) and [[Uram]] (`Uram.scala`).
 */

/**
 * True dual-port block RAM with the two ports in independent clock domains (a clock-crossing
 * BRAM). Both ports are byte-write `readWriteSyncPort`s over one shared `Mem`; the `ram_style`
 * attribute (default `"block"`) steers Vivado's inference.
 *
 * Each port's `rdata` is the `Mem`'s synchronous read (1 cycle); `withOutReg{Fast,Slow}` adds one
 * more output register (2 cycles) to ease timing on the fast side.
 *
 * @param width  data width in bits — must be a multiple of 8 (one mask bit per byte).
 * @param depth  number of words.
 * @param fastCd / slowCd  the two clock domains the ports live in.
 */
case class DualClockRam(
    width: Int,
    depth: Int,
    fastCd: ClockDomain,
    slowCd: ClockDomain,
    withOutRegFast: Boolean = false,
    withOutRegSlow: Boolean = false,
    style: String = "block",
) extends Component {
  require(width % 8 == 0, "DualClockRam width must be a multiple of 8 (one mask bit per byte)")

  val mem = Mem.fill(depth)(Bits(width bit))
  mem.addAttribute("ram_style", style)
  Verilator.public(mem)

  /** Wire one byte-write clock-crossing port of `mem` out as a slave, in clock domain `cd`,
   *  optionally registering `rdata` once more. */
  def buildPort(cd: ClockDomain, withOutReg: Boolean) = new ClockingArea(cd) {
    val port = mem.readWriteSyncPort(width / 8, clockCrossing = true)
    val ext  = slave port cloneOf(port)
    ext.rdata    := (if (withOutReg) RegNext(port.rdata) else port.rdata)
    port.wdata   := ext.wdata
    port.write   := ext.write
    port.address := ext.address
    port.mask    := ext.mask
    port.enable  := ext.enable
  }

  val slowLogic = buildPort(slowCd, withOutRegSlow)
  val slowPort  = slowLogic.ext

  val fastLogic = buildPort(fastCd, withOutRegFast)
  val fastPort  = fastLogic.ext
}

/** Emit `DualClockRam.v` (32-bit × 256, fast/slow external clocks). Run with
  * `mill runMain riscq.memory.DualClockRamGen`. */
object DualClockRamGen extends App {
  SpinalConfig(netlistFileName = "DualClockRam.v").generateVerilog(
    DualClockRam(
      width = 32,
      depth = 256,
      fastCd = ClockDomain.external("fast"),
      slowCd = ClockDomain.external("slow")
    )
  )
}
