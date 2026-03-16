package riscq.misc

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber._
import spinal.lib._
import spinal.lib.misc.pipeline
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.coherent.OrderingCmd
import spinal.lib.system.tag.PMA
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.pipeline._
import spinal.lib.io.TriStateArray
import scala.math
import scala.collection.mutable.ArrayBuffer

case class TileLinkMemWriteLogic[T <: Data](p: BusParameter, memPort: Flow[MemWriteCmd[T]])
    extends Component {
  assert(p.beatMax == 1, f"beatMax must be 1, but got ${p.beatMax}")
  assert(p.dataWidth <= memPort.dataType.getBitsWidth, f"dataWidth must be less than or equal to ${memPort.dataType.getBitsWidth}, but got ${p.dataWidth}")
  val io = new Area {
    val up = slave port Bus(p)
    val port = out port cloneOf(memPort)
  }
  val inDataBytes = p.dataBytes
  val outDataBytes = math.ceil(io.port.dataType.getBitsWidth / 8.0).toInt
  assert(isPow2(outDataBytes), f"outDataBytes must be a power of 2, but got ${outDataBytes}")

  val addrRange = log2Up(outDataBytes) - 1 downto log2Up(inDataBytes)

  val ratio = outDataBytes / inDataBytes
  
  // println(s"ratio: ${ratio}, io.port.mask: ${io.port.mask.getBitsWidth}, io.up.a.mask: ${io.up.a.mask.getBitsWidth}")
  val mask = Vec.fill(ratio)(cloneOf(io.up.a.mask))
  val sel = io.up.a.address(addrRange)
  mask.foreach(_ := 0)
  mask(sel) := io.up.a.mask
  val addressShifted = (io.up.a.address >> log2Up(outDataBytes))
  io.port.valid := io.up.a.valid
  io.port.data.assignFromBits(io.up.a.data #* ratio)
  io.port.mask := Cat(mask)
  io.port.address := addressShifted

  val rsp = cloneOf(io.up.d)
  io.up.a.ready := rsp.ready
  rsp.valid := io.up.a.valid
  rsp.opcode := Opcode.D.ACCESS_ACK
  rsp.param := 0
  rsp.source := io.up.a.source
  rsp.size := io.up.a.size
  rsp.denied := False
  // rsp.corrupt := False

  io.up.d << rsp.stage()
}

case class TileLinkMemWriteFiber[T <: Data](port: Flow[MemWriteCmd[T]]) extends Area {
  val up = Node.up()

  val dataBytes = math.pow(2, log2Up(port.dataType.getBitsWidth / 8)).toInt
  val thread = Fiber build new Area {
    // up.forceDataWidth(dataBytes * 8)
    // up.m2s.supported load up.m2s.proposed.intersect(M2sTransfers.allGetPut).copy(addressWidth = port.addressWidth + log2Up(dataBytes), dataWidth = dataBytes * 8)
    val proposedBytes = up.m2s.proposed.dataWidth / 8
    assert(up.m2s.proposed.dataWidth <= port.dataType.getBitsWidth, f"dataWidth must be less than or equal to ${port.dataType.getBitsWidth}, but got ${up.m2s.proposed.dataWidth}")
    up.m2s.supported load up.m2s.proposed
      .intersect(
        tilelink.M2sTransfers(
          putFull = tilelink.SizeRange.upTo(proposedBytes),
          putPartial = tilelink.SizeRange.upTo(proposedBytes)
        )
      )
      .copy(addressWidth = port.addressWidth + log2Up(dataBytes))
    up.s2m.none()

    val logic = TileLinkMemWriteLogic(up.bus.p, port)
    logic.io.port >> port
    logic.io.up << up.bus

    up.bus.get.simPublic
  }
}

case class TileLinkMemReadWriteLogic[T <: Data](p: BusParameter, inPort: MemReadWritePort[T], withOutReg: Boolean)
    extends Component {
  assert(p.beatMax == 1, "beatMax must be 1")
  val io = new Area {
    val up = slave port Bus(p)
    val port = master port cloneOf(inPort)
  }
  val dataBytes = math.ceil(io.port.dataType.getBitsWidth / 8.0).toInt
  val addressWidth = io.port.address.getBitsWidth + log2Up(dataBytes)

  val cmd = pipeline.Node()
  val rsp = pipeline.CtrlLink()
  val cmdLogic = new cmd.Area {
    val IS_GET = insert(Opcode.A.isGet(io.up.a.opcode))
    val SIZE = insert(io.up.a.size)
    val SOURCE = insert(io.up.a.source)
    val LAST = insert(True)

    valid := io.up.a.valid
    io.up.a.ready := isReady

    val addressShifted = (io.up.a.address >> log2Up(p.dataBytes))
    io.port.enable := isFiring
    io.port.write := !IS_GET
    if (p.withDataA) {
      io.port.wdata.assignFromBits(io.up.a.data)
      io.port.mask := io.up.a.mask
    }

    val readBuffer = Vec.fill(4)(Reg(io.port.rdata))
    val readBufferId = Reg(UInt(2 bits))
    val readPortValid = Vec.tabulate(4)(i => Delay(readBufferId === i, 1 + withOutReg.toInt))
    readBuffer(Delay(readBufferId, 1 + withOutReg.toInt)) := io.port.rdata
    when(isFiring) {
      readBufferId := readBufferId + 1
    }
    val RDATA_ID = insert(readBufferId)
    io.port.address := addressShifted
  }
  val rspLogic = new rsp.Area {
    val takeIt = cmdLogic.LAST || cmdLogic.IS_GET
    duplicateWhen(!io.up.d.ready && takeIt)
    io.up.d.valid := isValid && takeIt
    io.up.d.opcode := cmdLogic.IS_GET.mux(Opcode.D.ACCESS_ACK_DATA, Opcode.D.ACCESS_ACK)
    io.up.d.param := 0
    io.up.d.source := cmdLogic.SOURCE
    io.up.d.size := cmdLogic.SIZE
    io.up.d.denied := False
    io.up.d.corrupt := False
    val readPortValid = cmdLogic.readPortValid(cmdLogic.RDATA_ID)
    val bufferData = cmdLogic.readBuffer(cmdLogic.RDATA_ID)
    val rData = readPortValid.mux(io.port.rdata, bufferData)
    io.up.d.data := rData.asBits
  }

  val links = ArrayBuffer[pipeline.Link](rsp)
  val skidBuffer = pipeline.Node()
  if (withOutReg) {
    val read = pipeline.Node()
    links += pipeline.StageLink(cmd, read)
    links += pipeline.S2MLink(read, skidBuffer)
    links += pipeline.StageLink(skidBuffer, rsp.up)
  } else {
    links += pipeline.S2MLink(cmd, skidBuffer)
    links += pipeline.StageLink(skidBuffer, rsp.up)
  }
  pipeline.Builder(links)
}

case class TileLinkMemReadWriteFiber[T <: Data](port: MemReadWritePort[T], withOutReg: Boolean) extends Area {
  val up = Node.up()

  val dataBytes = math.pow(2, log2Up(port.dataType.getBitsWidth / 8)).toInt
  val thread = Fiber build new Area {
    up.forceDataWidth(dataBytes * 8)
    // up.m2s.supported load up.m2s.proposed.intersect(M2sTransfers.allGetPut).copy(addressWidth = port.addressWidth + log2Up(dataBytes), dataWidth = dataBytes * 8)
    up.m2s.supported load up.m2s.proposed
      .intersect(
        tilelink.M2sTransfers(
          get = tilelink.SizeRange(dataBytes),
          putFull = tilelink.SizeRange(dataBytes),
          putPartial = tilelink.SizeRange(dataBytes)
        )
      )
      .copy(addressWidth = port.addressWidth + log2Up(dataBytes), dataWidth = dataBytes * 8)
    up.s2m.none()

    val logic = TileLinkMemReadWriteLogic(up.bus.p, port, withOutReg)
    logic.io.port <> port
    logic.io.up << up.bus

    up.bus.get.simPublic
  }
}

case class TileLinkDriveFiber[T <: Data](port: T, default: T = null) {
  val up = tilelink.fabric.Node.up()
  val dataWidth = BigInt(2).pow(log2Up(port.getBitsWidth)).toInt max 16
  val addressWidth = log2Up(dataWidth / 8)
  println(s"datawidth${dataWidth}")
  val fiber = Fiber build new Area {
    up.m2s.supported load M2sSupport(
      addressWidth = addressWidth,
      dataWidth = dataWidth,
      transfers = M2sTransfers(
        get = SizeRange(dataWidth / 8),
        putPartial = SizeRange(dataWidth / 8)
      )
    )
    up.s2m.none()
    val factory = new SlaveFactory(up.bus, allowBurst = false)
    val writeReg = factory.drive(port, 0)
    if (default != null) {
      writeReg init (default)
    }
    Fiber.awaitCheck()
    println(s"parameter: ${up.m2s.parameters}")
  }
}

case class GpioFiber(width: Int = 32) extends Area {
  assert(width % 8 == 0, "width of GPIO must be mutiple of 8")
  // Define a node facing upward (toward masters only)
  val up = tilelink.fabric.Node.up()

  // Define a elaboration thread to specify the "up" parameters and generate the hardware
  val fiber = Fiber build new Area {
    // Here we first define what our up node support. m2s mean master to slave requests
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = 12,
      dataWidth = width,
      // Transfers define which kind of memory transactions our up node will support.
      // Here it only support 4 bytes get/putfull
      transfers = tilelink.M2sTransfers(
        get = tilelink.SizeRange(width / 8),
        putPartial = tilelink.SizeRange(width / 8)
      )
    )
    // s2m mean slave to master requests, those are only use for memory coherency purpose
    // So here we specify we do not need any
    up.s2m.none()

    // Then we can finally generate some hardware
    // Starting by defining a 32 bits TriStateArray (Array meaning that each pin has its own writeEnable bit
    // val pins = TriStateArray(width bits)
    val pins = Reg(Bits(width bits))

    // tilelink.SlaveFactory is a utility allowing to easily generate the logic required
    // to control some hardware from a tilelink bus.
    val factory = new tilelink.SlaveFactory(up.bus, allowBurst = false)

    // Use the SlaveFactory API to generate some hardware to read / drive the pins
    // val writeEnableReg = factory.drive(pins.writeEnable, 0x0) init (0)
    // val writeReg = factory.drive(pins.write, width / 8) init(0)
    // factory.read(pins.read, width / 8 * 2)
    val writeReg = factory.drive(pins, 0) init (0)
  }
}

case class TileLinkFifo(busParameter: BusParameter, depth: Int = 2) extends Component {
  val io = new Bundle {
    val input = slave(Bus(busParameter))
    val output = master(Bus(busParameter))
  }

  val a = StreamFifo(ChannelA(busParameter), depth)
  a.io.push << io.input.a
  io.output.a << a.io.pop
  val b = busParameter.withBCE generate StreamFifo(ChannelB(busParameter), depth)
  val c = busParameter.withBCE generate StreamFifo(ChannelC(busParameter), depth)
  val d = StreamFifo(ChannelD(busParameter), depth)
  d.io.push << io.output.d
  io.input.d << d.io.pop
  val e = busParameter.withBCE generate StreamFifo(ChannelE(busParameter), depth)

  if (busParameter.withBCE) {
    b.io.push << io.output.b
    io.input.b << b.io.pop
    c.io.push << io.input.c
    io.output.c << c.io.pop
    e.io.push << io.input.e
    io.output.e << e.io.pop
  }
}

case class TileLinkFifoFiber(depth: Int = 2) extends Area {
  val up = Node.slave()
  val down = Node.master()

  val logic = Fiber build new Area {
    down.m2s.proposed.load(up.m2s.proposed)
    up.m2s.supported load down.m2s.supported
    down.m2s.parameters load up.m2s.parameters

    up.s2m.from(down.s2m)

    val fifo = TileLinkFifo(up.bus.p, depth)
    fifo.io.input << up.bus
    fifo.io.output >> down.bus
  }
}

case class TileLinkPipeFiber(pipe: StreamPipe) extends Area {
  val up = Node.slave()
  val down = Node.master()

  val logic = Fiber build new Area {
    down.m2s.proposed.load(up.m2s.proposed)
    up.m2s.supported load down.m2s.supported
    down.m2s.parameters load up.m2s.parameters

    up.s2m.from(down.s2m)

    down.bus.a << up.bus.a.pipelined(pipe)
    up.bus.d << down.bus.d.pipelined(pipe)
    if (up.bus.p.withBCE) {
      up.bus.b << down.bus.b.pipelined(pipe)
      down.bus.c << up.bus.c.pipelined(pipe)
      down.bus.e << up.bus.e.pipelined(pipe)
    }
  }
}