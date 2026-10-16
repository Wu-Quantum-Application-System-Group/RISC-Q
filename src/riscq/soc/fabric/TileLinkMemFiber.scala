package riscq.soc.fabric

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.misc.pipeline
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink._
import spinal.lib.bus.tilelink.fabric.Node

import scala.math
import scala.collection.mutable.ArrayBuffer

/**
 * Tilelink slave fibers that expose an on-chip RAM port (`MemReadWritePort` / `MemWriteCmd`) on the
 * `tilelink.fabric` interconnect. Ported from the RISC-Q reference (`riscq.misc.TileLinkAccess`) for
 * the SoC's host / instruction / pulse / readout memories.
 *
 * Three flavours:
 *   - [[TileLinkMemReadWriteFiber]] — full read/write port (readout buffers, host-reachable RAMs);
 *   - [[TileLinkMemWriteFiber]]     — write-only command flow (host-loaded pulse-envelope memory);
 *   - [[TileLinkCpuMemFiber]]       — a stripped-down read/write slave for the CPU instruction/data RAM
 *       only, where the masters never back-pressure the d-channel (no read-reorder buffer / pipeline).
 *
 * The write-only flavour bridges a (possibly wider) RAM word to the bus's `dataWidth` by sub-word byte
 * addressing, so a 32-bit host bus can write into e.g. a 512-bit envelope line. The read/write flavours
 * force the bus word equal to the RAM word, so they carry no such byte-lane logic.
 */

/** Combinational logic translating a Tilelink slave bus into a write-only `MemWriteCmd` flow. The RAM
 *  word may be wider than the bus word; the low address bits pick which sub-word lane the bus beat
 *  writes (`ratio = outBytes / inBytes` lanes), and only that lane's byte mask is asserted. */
case class TileLinkMemWriteLogic[T <: Data](p: BusParameter, memPort: Flow[MemWriteCmd[T]])
    extends Component {
  assert(p.beatMax == 1, f"beatMax must be 1, but got ${p.beatMax}")
  assert(
    p.dataWidth <= memPort.dataType.getBitsWidth,
    f"dataWidth must be <= ${memPort.dataType.getBitsWidth}, but got ${p.dataWidth}"
  )
  val io = new Area {
    val up   = slave port Bus(p)
    val port = out port cloneOf(memPort)
  }
  val inDataBytes  = p.dataBytes
  val outDataBytes = math.ceil(io.port.dataType.getBitsWidth / 8.0).toInt
  assert(isPow2(outDataBytes), f"outDataBytes must be a power of 2, but got ${outDataBytes}")

  val addrRange = log2Up(outDataBytes) - 1 downto log2Up(inDataBytes)
  val ratio     = outDataBytes / inDataBytes

  // Replicate the bus beat across every lane, but only enable the addressed lane's bytes.
  val mask = Vec.fill(ratio)(cloneOf(io.up.a.mask))
  val sel  = io.up.a.address(addrRange)
  mask.foreach(_ := 0)
  mask(sel) := io.up.a.mask
  val addressShifted = (io.up.a.address >> log2Up(outDataBytes))
  io.port.valid := io.up.a.valid
  io.port.data.assignFromBits(io.up.a.data #* ratio)
  io.port.mask    := Cat(mask)
  io.port.address := addressShifted

  val rsp = cloneOf(io.up.d)
  io.up.a.ready := rsp.ready
  rsp.valid     := io.up.a.valid
  rsp.opcode    := Opcode.D.ACCESS_ACK
  rsp.param     := 0
  rsp.source    := io.up.a.source
  rsp.size      := io.up.a.size
  rsp.denied    := False
  io.up.d << rsp.stage()
}

case class TileLinkMemWriteFiber[T <: Data](port: Flow[MemWriteCmd[T]]) extends Area {
  val up = Node.up()

  val dataBytes = math.pow(2, log2Up(port.dataType.getBitsWidth / 8)).toInt
  val thread = Fiber build new Area {
    val proposedBytes = up.m2s.proposed.dataWidth / 8
    assert(
      up.m2s.proposed.dataWidth <= port.dataType.getBitsWidth,
      f"dataWidth must be <= ${port.dataType.getBitsWidth}, but got ${up.m2s.proposed.dataWidth}"
    )
    up.m2s.supported load up.m2s.proposed
      .intersect(
        tilelink.M2sTransfers(
          putFull    = tilelink.SizeRange.upTo(proposedBytes),
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

/** Pipelined logic translating a Tilelink slave bus into a `MemReadWritePort`. A small read-data
 *  reorder buffer (4 slots, indexed by a wrapping id) covers the RAM read latency `1 + withOutReg`,
 *  so a Get's response carries the word read for *its* beat even with several in flight. */
case class TileLinkMemReadWriteLogic[T <: Data](p: BusParameter, inPort: MemReadWritePort[T], withOutReg: Boolean)
    extends Component {
  assert(p.beatMax == 1, "beatMax must be 1")
  val io = new Area {
    val up   = slave port Bus(p)
    val port = master port cloneOf(inPort)
  }
  val dataBytes    = math.ceil(io.port.dataType.getBitsWidth / 8.0).toInt
  val addressWidth = io.port.address.getBitsWidth + log2Up(dataBytes)

  val cmd = pipeline.Node()
  val rsp = pipeline.CtrlLink()
  val cmdLogic = new cmd.Area {
    val IS_GET = insert(Opcode.A.isGet(io.up.a.opcode))
    val SIZE   = insert(io.up.a.size)
    val SOURCE = insert(io.up.a.source)
    val LAST   = insert(True)

    valid           := io.up.a.valid
    io.up.a.ready   := isReady

    val addressShifted = (io.up.a.address >> log2Up(p.dataBytes))
    io.port.enable := isFiring
    io.port.write  := !IS_GET
    if (p.withDataA) {
      io.port.wdata.assignFromBits(io.up.a.data)
      io.port.mask := io.up.a.mask
    }

    // 4-entry rotating buffer of in-flight read responses (covers d-channel backpressure). Stored in a
    // `Mem` (distributed RAM: 1 sync-write / 1 async-read) rather than a `Vec` of indexed `Reg`s. The
    // indexed write into a `Vec` would give each slot its own one-hot write clock-enable; the single
    // LUTRAM write port instead has a constant-true enable and a combinational async read, so the slot
    // flip-flops drop out entirely. Bit-identical: write index, read index and the `readPortValid`
    // live-vs-buffered mux are unchanged.
    val readBuffer   = Mem.fill(4)(cloneOf(io.port.rdata))
    readBuffer.addAttribute("ram_style", "distributed")
    val readBufferId = Reg(UInt(2 bits))
    val readPortValid = Vec.tabulate(4)(i => Delay(readBufferId === i, 1 + withOutReg.toInt))
    readBuffer.write(Delay(readBufferId, 1 + withOutReg.toInt), io.port.rdata)
    when(isFiring) {
      readBufferId := readBufferId + 1
    }
    val RDATA_ID = insert(readBufferId)
    io.port.address := addressShifted
  }
  val rspLogic = new rsp.Area {
    val takeIt = cmdLogic.LAST || cmdLogic.IS_GET
    duplicateWhen(!io.up.d.ready && takeIt)
    io.up.d.valid  := isValid && takeIt
    io.up.d.opcode := cmdLogic.IS_GET.mux(Opcode.D.ACCESS_ACK_DATA, Opcode.D.ACCESS_ACK)
    io.up.d.param  := 0
    io.up.d.source := cmdLogic.SOURCE
    io.up.d.size   := cmdLogic.SIZE
    io.up.d.denied := False
    io.up.d.corrupt := False
    val readPortValid = cmdLogic.readPortValid(cmdLogic.RDATA_ID)
    val bufferData    = cmdLogic.readBuffer.readAsync(cmdLogic.RDATA_ID)
    val rData         = readPortValid.mux(io.port.rdata, bufferData)
    io.up.d.data := rData.asBits
  }

  val links      = ArrayBuffer[pipeline.Link](rsp)
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
    up.m2s.supported load up.m2s.proposed
      .intersect(
        tilelink.M2sTransfers(
          get        = tilelink.SizeRange(dataBytes),
          putFull    = tilelink.SizeRange(dataBytes),
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

/** Stripped-down slave logic for the riscq **CPU instruction/data RAM** (the `mem` in [[riscq.soc.RiscvSoc]]).
 *  A drop-in for [[TileLinkMemReadWriteLogic]] on that one bus, with the read-reorder buffer and the
 *  `spinal.lib.misc.pipeline` elastic stages **removed** — it is a plain fixed-latency in-order slave.
 *
 *  Why this is sufficient here (and only here):
 *   - Both CPU masters hold `d.ready` **permanently high** — `FetchPlugin.iBus` (`iBus.d.ready := True`)
 *     and the `LsuPlugin` data bus through `DataMemBusToTilelink` (`io.tl.d.ready := True`, strictly
 *     single-outstanding). The instruction RAM's only other client, the host image-load, shares the slow
 *     port solely *while the core is held in reset* (it loads, then the host releases the reset), so it
 *     never overlaps fetch. The d-channel at this slave is therefore never back-pressured.
 *   - With `d.ready` always asserted, there is nothing for an elastic buffer to absorb: a request can be
 *     accepted every cycle (`a.ready := True`, so fetch sustains IPC = 1) and its response emitted at the
 *     RAM's fixed read latency `L = 1 + withOutReg`, reading the word straight off `io.port.rdata` because
 *     the response control is delayed by the same `L`. The reorder buffer / `readBufferId` / `duplicateWhen`
 *     of the general logic all existed only to cover d-channel back-pressure with several reads in flight.
 *
 *  The bus word width equals the RAM word (the fiber forces it, like its sibling), so — as in the
 *  read/write path — there is no sub-word byte-lane logic; that bridging lives only in the write-only
 *  [[TileLinkMemWriteLogic]]. A simulation `assert` guards the no-back-pressure premise.
 *
 *  `latency` is the RAM's exact accept-edge → rdata-stable-cycle read latency and MUST match the
 *  storage: Bram = 1 + outReg (2 with the output register); Uram = pipeNum + 2 (the template chains
 *  memreg + NBPIPE pipes + the dout register, one MORE stage than Bram+outReg at pipeNum = 1 — the
 *  latency-2 assumption here was exactly the bug that made every host/CPU read return the previous
 *  read's data once RiscvSoc switched to UltraRAM). */
case class TileLinkCpuMemLogic[T <: Data](p: BusParameter, inPort: MemReadWritePort[T], latency: Int)
    extends Component {
  assert(p.beatMax == 1, "beatMax must be 1")
  val io = new Area {
    val up   = slave  port Bus(p)
    val port = master port cloneOf(inPort)
  }
  val a = io.up.a
  val d = io.up.d
  val isGet = Opcode.A.isGet(a.opcode)

  // Accept every beat and drive the RAM port combinationally. `a.ready := True` ⇒ `a.fire === a.valid`.
  a.ready         := True
  io.port.enable  := a.valid
  io.port.write   := !isGet
  io.port.address := a.address >> log2Up(p.dataBytes)   // bus byte-addr → RAM word index (widths match)
  if (p.withDataA) {
    io.port.wdata.assignFromBits(a.data)
    io.port.mask := a.mask
  }

  // Response: the control follows the read latency so it lines up with `io.port.rdata` (one `d` per `a`,
  // returned in order — single slave, fixed latency). No buffer: the d-channel is never back-pressured.
  d.valid   := Delay(a.fire, latency, init = False)
  d.opcode  := Delay(isGet, latency).mux(Opcode.D.ACCESS_ACK_DATA, Opcode.D.ACCESS_ACK)
  d.param   := 0
  d.source  := Delay(a.source, latency)
  d.size    := Delay(a.size, latency)
  d.denied  := False
  d.corrupt := False
  d.data    := io.port.rdata.asBits

  assert(!(d.valid && !d.ready), "TileLinkCpuMemLogic: d-channel back-pressured — premise violated")
}

/** Fiber wrapper for [[TileLinkCpuMemLogic]] — identical node negotiation to [[TileLinkMemReadWriteFiber]],
 *  only the slave logic is the stripped-down fixed-latency variant. Use this exclusively for the riscq
 *  CPU's instruction/data RAM, where the masters never back-pressure the d-channel. `latency` must be
 *  the storage's exact read latency (see [[TileLinkCpuMemLogic]]). */
case class TileLinkCpuMemFiber[T <: Data](port: MemReadWritePort[T], latency: Int) extends Area {
  val up = Node.up()

  val dataBytes = math.pow(2, log2Up(port.dataType.getBitsWidth / 8)).toInt
  val thread = Fiber build new Area {
    up.forceDataWidth(dataBytes * 8)
    up.m2s.supported load up.m2s.proposed
      .intersect(
        tilelink.M2sTransfers(
          get        = tilelink.SizeRange(dataBytes),
          putFull    = tilelink.SizeRange(dataBytes),
          putPartial = tilelink.SizeRange(dataBytes)
        )
      )
      .copy(addressWidth = port.addressWidth + log2Up(dataBytes), dataWidth = dataBytes * 8)
    up.s2m.none()

    val logic = TileLinkCpuMemLogic(up.bus.p, port, latency)
    logic.io.port <> port
    logic.io.up << up.bus

    up.bus.get.simPublic
  }
}
