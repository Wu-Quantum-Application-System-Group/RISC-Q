package riscq.soc.fabric

import spinal.core._
import spinal.core.fiber.Fiber
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.lib.bus.tilelink.fabric.Node

import scala.collection.mutable

/**
 * Tilelink slave fibers exposing CPU-mapped control registers via a `SlaveFactory`. Ported from the
 * RISC-Q reference (`riscq.soc.Misc`) for the qubit core's control block and the host control block.
 *
 *   - [[MemMapFiber]]       — a register block whose mappings are contributed incrementally
 *     (`addMapping`), so the time/host/startTime maps each register their own offsets on one slave.
 *   - [[MemMapDriverFiber]] — a register block whose single `driveProc` builds the whole map at once
 *     (the host bus's reset/control registers).
 */
case class MemMapFiber(addressWidth: Int = 10, dataWidth: Int = 32) extends Area {
  val up       = Node.up()
  val mappings = mutable.LinkedHashSet[tilelink.SlaveFactory => Unit]()

  /** Register one register-map fragment; called before elaboration. */
  def addMapping(mapping: tilelink.SlaveFactory => Unit): Unit = mappings += mapping

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = addressWidth,
      dataWidth    = dataWidth,
      transfers = up.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get        = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull    = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8)
        )
      )
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)
    mappings.foreach(mapping => mapping(factory))
  }
}

case class MemMapDriverFiber(driveProc: tilelink.SlaveFactory => Unit, addressWidth: Int = 10, dataWidth: Int = 32)
    extends Area {
  val up = Node.up()

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.M2sSupport(
      addressWidth = addressWidth,
      dataWidth    = dataWidth,
      transfers = up.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get        = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull    = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8)
        )
      )
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)
    driveProc(factory)
  }
}
