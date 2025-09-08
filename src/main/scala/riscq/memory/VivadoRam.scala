package riscq.memory

import spinal.core._
import spinal.lib._
import spinal.lib.eda.bench.Rtl
import spinal.lib.eda.bench.Bench
import riscq.misc.XilinxRfsocTarget

case class DualClockRam(
    width: Int,
    depth: Int,
    fastCd: ClockDomain,
    slowCd: ClockDomain,
    withOutRegFast: Boolean = false,
    withOutRegSlow: Boolean = false,
    style: String = "block",
) extends Component {

  val mem = Mem.fill(depth)(Bits(width bit))
  mem.addAttribute("ram_style", style)
  Verilator.public(mem)

  val slowLogic = new ClockingArea(slowCd) {
    val port = mem.readWriteSyncPort(width / 8, clockCrossing = true)
    val slowPort = slave port cloneOf(port)
    if(withOutRegSlow) {
      slowPort.rdata := RegNext(port.rdata)
    } else {
      slowPort.rdata := port.rdata
    }
    port.wdata := slowPort.wdata
    port.write := slowPort.write
    port.address := slowPort.address
    port.mask := slowPort.mask
    port.enable := slowPort.enable
  }
  val slowPort = slowLogic.slowPort

  val fastLogic = new ClockingArea(fastCd) {
    val port = mem.readWriteSyncPort(width / 8, clockCrossing = true)
    val fastPort = slave port cloneOf(port)
    if(withOutRegFast) {
      fastPort.rdata := RegNext(port.rdata)
    } else {
      fastPort.rdata := port.rdata
    }
    port.wdata := fastPort.wdata
    port.write := fastPort.write
    port.address := fastPort.address
    port.mask := fastPort.mask
    port.enable := fastPort.enable
  }
  val fastPort = fastLogic.fastPort
}

case class DualClockRamTest(
    width: Int,
    depth: Int,
    fastCd: ClockDomain,
    slowCd: ClockDomain,
    withOutRegFast: Boolean = false,
    withOutRegSlow: Boolean = false,
    style: String = "block",
) extends Component {

  val mem = Mem.fill(depth)(Bits(width bit))
  mem.addAttribute("ram_style", style)
  Verilator.public(mem)

  val slowLogic = new ClockingArea(slowCd) {
    val port = mem.readWriteSyncPort(width / 8, clockCrossing = true)
    val slowPort = slave port cloneOf(port)
    if(withOutRegSlow) {
      slowPort.rdata := RegNext(port.rdata)
    } else {
      slowPort.rdata := port.rdata
    }
    port.wdata := slowPort.wdata
    port.write := slowPort.write
    port.address := slowPort.address
    port.mask := slowPort.mask
    port.enable := slowPort.enable
  }
  val slowPort = slowLogic.slowPort

  val fastLogic = new ClockingArea(fastCd) {
    val port = mem.readWriteSyncPort(width / 8, clockCrossing = true)
    val fastPort = slave port cloneOf(port)
    if(withOutRegFast) {
      fastPort.rdata := RegNext(port.rdata)
    } else {
      fastPort.rdata := port.rdata
    }
    port.wdata := fastPort.wdata
    port.write := fastPort.write
    port.address := fastPort.address
    port.mask := fastPort.mask
    port.enable := fastPort.enable
  }
  val fastPort = fastLogic.fastPort
}

case class UramBlackBox(dataWidth: Int, addressWidth: Int, pipeNum: Int = 3) extends BlackBox {
  val maskWidth = dataWidth / 8
  addGeneric("AWIDTH", addressWidth)
  addGeneric("NUM_COL", maskWidth)
  addGeneric("DWIDTH", dataWidth)
  addGeneric("NBPIPE", pipeNum)

  val io = new Bundle {
    val clk = in Bool()
    val wea = in Bits(maskWidth bit)
    val mem_ena = in Bool()
    val dina = in Bits(dataWidth bit)
    val addra = in Bits(addressWidth bit)
    val douta = out Bits(dataWidth bit)

    val web = in Bits(maskWidth bit)
    val mem_enb = in Bool()
    val dinb = in Bits(dataWidth bit)
    val addrb = in Bits(addressWidth bit)
    val doutb = out Bits(dataWidth bit)
  }

  noIoPrefix()
  addRTLPath("./src/main/scala/riscq/memory/UramBlackBox.v")
}

case class Uram[T <: Data](dataType: T, addressWidth: Int, pipeNum: Int = 3) extends Component {
  val bitsWidth = dataType.getBitsWidth
  assert(bitsWidth % 8 == 0, "dataWidth must be a multiple of 8")
  val maskWidth = bitsWidth / 8
  val uram = UramBlackBox(bitsWidth, addressWidth, pipeNum)
  val io = new Bundle {
    val port0 = slave port MemReadWritePort(dataType, addressWidth, maskWidth = maskWidth)
    val port1 = slave port MemReadWritePort(dataType, addressWidth, maskWidth = maskWidth)
  }

  uram.io.clk := ClockDomain.current.readClockWire

  uram.io.wea := io.port0.mask & (io.port0.write #* maskWidth)
  uram.io.mem_ena := io.port0.enable
  uram.io.dina := io.port0.wdata.asBits
  uram.io.addra := io.port0.address.asBits
  io.port0.rdata := uram.io.douta.as(dataType)
  
  uram.io.web := io.port1.mask & (io.port1.write #* maskWidth)
  uram.io.mem_enb := io.port1.enable
  uram.io.dinb := io.port1.wdata.asBits
  uram.io.addrb := io.port1.address.asBits
  io.port1.rdata := uram.io.doutb.as(dataType)
}

object BenchUram extends App {
  val report = SpinalVerilog(new Uram(Bits(32 bits), 7))
  val rtl = Rtl(report)
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./bench/")
}

object GenUram extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    new Uram(Bits(32 bits), 7)
  )
}