package riscq.soc.fabric

import spinal.core._
import spinal.core.sim._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.misc.plugin.Hostable
import spinal.lib.bus.tilelink.{Bus, DebugId, Opcode, S2mSupport}
import spinal.lib.bus.tilelink.fabric.Node
import riscq.riscv.Riscq
import riscq.riscv.fetch.FetchPlugin
import riscq.riscv.execute.LsuPlugin
import riscq.riscv.misc.{DataMemBus, DataMemBusParam, MemBus}

/**
 * Tilelink-izes the core's simplified [[DataMemBus]] (`cmd` Stream + `rsp` Flow) into a real Tilelink
 * master, so the data bus joins the fabric exactly like the fetch bus does. Every `cmd` becomes one
 * `a` (read → `Get`, write → `PutPartial`) and each Tilelink `d` answers the single-outstanding `rsp`
 * (the core is always ready, so `d.ready` is held high). This is the riscq analog of VexiiRiscv's
 * `CachelessBusToTilelink`, minus AMO — it keeps the Tilelink boilerplate out of [[LsuPlugin]].
 */
case class DataMemBusToTilelink(p: DataMemBusParam) extends Component {
  val io = new Bundle {
    val dm = slave(DataMemBus(p))
    val tl = master(Bus(MemBus.param(p.addressWidth, p.dataWidth, withStore = true)))
  }

  io.tl.a.arbitrationFrom(io.dm.cmd)
  io.tl.a.opcode  := io.dm.cmd.write ? Opcode.A.PUT_PARTIAL_DATA | Opcode.A.GET
  io.tl.a.param   := 0
  io.tl.a.source  := 0
  io.tl.a.size    := log2Up(p.dataBytes)    // always a word
  io.tl.a.address := io.dm.cmd.address
  io.tl.a.mask    := io.dm.cmd.mask
  io.tl.a.data    := io.dm.cmd.data
  io.tl.a.corrupt := False
  if (DebugId.enabled) io.tl.a.debugId := 0

  io.tl.d.ready   := True                    // single-outstanding: always ready for the one response
  io.dm.rsp.valid := io.tl.d.valid
  io.dm.rsp.data  := io.tl.d.data            // a store's AccessAck carries no data; the LSU ignores it
}

/**
 * Thin fabric adapter wrapping a riscq [[Riscq]] core and exposing its instruction / data busses as
 * `tilelink.fabric` master nodes.
 *
 * The `FetchPlugin` already exposes a real Tilelink master (`iBus`), so that side is a plain node
 * adapter: force the (already-known) master parameters onto the down node and connect the bus into it.
 * The `LsuPlugin` instead speaks the simplified [[DataMemBus]], so the data side first runs through a
 * [[DataMemBusToTilelink]] bridge (the Tilelink boilerplate the LSU used to carry, relocated to this
 * boundary — like the RISC-Q reference's `CachelessBusToTilelink`); downstream the fabric sees an
 * ordinary Tilelink master, unchanged.
 *
 * A [[PostedStoreShim]] is spliced onto that `DataMemBus` ahead of the bridge — stores ack locally in
 * one cycle (non-blocking writes), reads pass through. The shim rides the simplified bus, so it never
 * has to speak Tilelink.
 *
 * @param plugins       the core's plugin list (e.g. `RiscqParam().plugins()`).
 * @param storeBufDepth posted-store buffer depth.
 */
case class RiscqFiber(plugins: Seq[Hostable], storeBufDepth: Int = 4)
    extends Area {
  val iBus = Node.down()
  val dBus = Node.down()
  iBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)
  dBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  // The core's clock domain (e.g. `riscqCd` in the SoC — same clock as the LSU, with the host reset).
  // Captured here, in the Area body, because the data-bus rework runs later inside a Fiber thread whose
  // implicit ClockDomain is NOT this one; the posted-store shim's registers must live here so the host
  // reset clears its buffer (otherwise it powers up with a garbage FIFO and drains a stray store).
  val coreCd = ClockDomain.current

  val riscq = Riscq(plugins)

  val thread = Fiber build new Area {
    val iMaster = riscq.host[FetchPlugin].logic.iBus
    val dMaster = riscq.host[LsuPlugin].logic.dBus

    /** Force the master's (known) m2s parameters onto a master-only down node, then drive the node's
     *  bus inside its own component (the node's `bus` Handle is only loaded in its elaboration thread,
     *  so the connection — and the data-side bridge — must be reworked into that component). */
    def forceMaster(node: Node, m: spinal.lib.bus.tilelink.M2sParameters): Unit = {
      node.m2s.forceParameters(m)
      node.s2m.supported.load(S2mSupport.none())
    }

    forceMaster(iBus, iMaster.p.node.m)
    iBus.bus.component.rework(iBus.bus << iMaster)

    // Data side: post stores, then bridge the simplified DataMemBus up to a Tilelink master, all inside
    // the node's component (where its `bus` Handle is loaded).
    forceMaster(dBus, MemBus.param(dMaster.p.addressWidth, dMaster.p.dataWidth, withStore = true).node.m)
    dBus.bus.component.rework {
      val bridge = DataMemBusToTilelink(dMaster.p)
      val shim = coreCd(PostedStoreShim(dMaster.p, storeBufDepth))  // stateful ⇒ pin to the core's reset
      shim.io.up   <> dMaster
      bridge.io.dm <> shim.io.down
      dBus.bus << bridge.io.tl
    }

    dBus.bus.get.simPublic() // the reference monitors dBus in sims
  }
}
