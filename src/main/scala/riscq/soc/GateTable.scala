package riscq.soc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber

case class GateParam(
  dataWidth: Int = 16,
  addrWidth: Int = 16,
  timeWidth: Int = 32,
  durWidth: Int = 16,
) extends Bundle {
  val phase = SInt(dataWidth bit)
  val amp = SInt(dataWidth bit)
  val addr = SInt(addrWidth bit)
  val start = SInt(timeWidth bit)
  val dur = SInt(durWidth bit)
}

case class GateTableFiber(gateNum: Int) extends Area {
  val up = Node.up()
  val table = Vec.fill(gateNum)(Reg(GateParam()))

  val logic = Fiber build new Area {
    // up.m2s.supported load tilelink.SlaveFactory.getSupported(
    //   addressWidth = 16,
    //   dataWidth = 32,
    //   allowBurst = false,
    //   proposed = up.m2s.proposed
    // )

    val dataWidth = 32
    up.m2s.supported load up.m2s.proposed.copy(
    addressWidth = 16,
    dataWidth = dataWidth,
    transfers = up.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8),
        )
      )
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)

    for (j <- 0 until gateNum) {
      factory.drive(table(j).phase, j * 32, bitOffset = 0)
      factory.drive(table(j).amp, j * 32 + 1 * 4, bitOffset = 0)
      factory.drive(table(j).addr, j * 32 + 2 * 4, bitOffset = 0)
      factory.drive(table(j).start, j * 32 + 3 * 4, bitOffset = 0)
      factory.drive(table(j).dur, j * 32 + 4 * 4, bitOffset = 0)
    }
  }
}