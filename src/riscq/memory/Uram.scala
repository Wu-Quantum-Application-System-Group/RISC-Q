package riscq.memory

import spinal.core._
import spinal.lib._
import scala.io.Source

/**
 * BlackBox over the Xilinx UltraRAM HDL template (`src/riscq/memory/UramBlackBox.v`): a true
 * dual-port RAM with per-byte write enables and `NBPIPE` output pipeline registers, sized by the
 * `AWIDTH` / `DWIDTH` / `NUM_COL` (byte columns) / `NBPIPE` generics. Drive it through [[Uram]].
 *
 * Read latency: the template chains `NBPIPE + 2` registers — one memory-read register (`memreg`),
 * `NBPIPE` pipeline stages, AND the `dout` output register — each gated on a delayed copy of
 * `mem_en`. So data presented with the address in cycle 0 is stable on `dout` in cycle
 * `NBPIPE + 2` — one cycle LATER than a Bram with its output register (1 + outReg). Bus fibers
 * must be told this exact latency (see `TileLinkCpuMemLogic`).
 */
case class xilinx_ultraram_true_dual_port_bytewrite(dataWidth: Int, addressWidth: Int, pipeNum: Int = 3) extends BlackBox {
  val maskWidth = dataWidth / 8
  addGeneric("AWIDTH", addressWidth)
  addGeneric("NUM_COL", maskWidth)
  addGeneric("DWIDTH", dataWidth)
  addGeneric("NBPIPE", pipeNum)

  val io = new Bundle {
    val clk = in Bool()

    val wea     = in Bits(maskWidth bit)
    val mem_ena = in Bool()
    val dina    = in Bits(dataWidth bit)
    val addra   = in Bits(addressWidth bit)
    val douta   = out Bits(dataWidth bit)

    val web     = in Bits(maskWidth bit)
    val mem_enb = in Bool()
    val dinb    = in Bits(dataWidth bit)
    val addrb   = in Bits(addressWidth bit)
    val doutb   = out Bits(dataWidth bit)
  }

  noIoPrefix()

  val source = Source.fromFile("src/riscq/memory/UramBlackBox.v").mkString
  setInlineVerilog(source)
}

/**
 * Typed true dual-port UltraRAM: wraps [[xilinx_ultraram_true_dual_port_bytewrite]] with two
 * `MemReadWritePort[T]` slave ports (one per UltraRAM port), translating the generic
 * `MemReadWritePort` handshake to the template's byte-write interface. A port writes byte `i` when
 * `enable && write && mask(i)`, and reads `NBPIPE + 2` cycles after the address is presented
 * (`enable` high, `write` low) — see the blackbox note above.
 *
 * @param dataType      payload type; its bit width must be a multiple of 8 (per-byte mask).
 * @param addressWidth  address width in bits.
 * @param pipeNum       UltraRAM output pipeline depth (`NBPIPE`).
 */
case class Uram[T <: Data](dataType: T, addressWidth: Int, pipeNum: Int = 3) extends Component {
  val bitsWidth = dataType.getBitsWidth
  assert(bitsWidth % 8 == 0, "dataWidth must be a multiple of 8")
  val maskWidth = bitsWidth / 8
  val uram = xilinx_ultraram_true_dual_port_bytewrite(bitsWidth, addressWidth, pipeNum)
  val io = new Bundle {
    val port0 = slave port MemReadWritePort(dataType, addressWidth, maskWidth = maskWidth)
    val port1 = slave port MemReadWritePort(dataType, addressWidth, maskWidth = maskWidth)
  }

  uram.io.clk := ClockDomain.current.readClockWire

  uram.io.wea     := io.port0.mask & (io.port0.write #* maskWidth)
  uram.io.mem_ena := io.port0.enable
  uram.io.dina    := io.port0.wdata.asBits
  uram.io.addra   := io.port0.address.asBits
  io.port0.rdata  := uram.io.douta.as(dataType)

  uram.io.web     := io.port1.mask & (io.port1.write #* maskWidth)
  uram.io.mem_enb := io.port1.enable
  uram.io.dinb    := io.port1.wdata.asBits
  uram.io.addrb   := io.port1.address.asBits
  io.port1.rdata  := uram.io.doutb.as(dataType)
}

/** Emit `Uram.v` (32-bit × 128). Run with `mill runMain riscq.memory.UramGen`. */
object UramGen extends App {
  SpinalConfig(netlistFileName = "Uram.v", romReuse = true)
    .generateVerilog(Uram(Bits(32 bits), 7))
}
