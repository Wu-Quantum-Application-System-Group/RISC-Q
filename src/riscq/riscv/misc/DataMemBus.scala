package riscq.riscv.misc

import spinal.core._
import spinal.lib._

/**
 * Parameters for [[DataMemBus]] — the core's simplified data-side memory link.
 *
 * `addressWidth` is the byte-address width (XLEN: the data bus reaches the whole SoC map), `dataWidth`
 * the access width (always 32 here). `dataBytes` is the byte-mask / lane width.
 */
case class DataMemBusParam(addressWidth: Int, dataWidth: Int) {
  def dataBytes: Int = dataWidth / 8
}

/**
 * One word-granular access: a read (`write = False`) or a byte-masked write (`write = True`). `address`
 * is word-aligned; `mask` selects the live bytes within the word and `data` carries the store bytes
 * already shifted into their lane (both ignored on a read).
 */
case class DataMemCmd(p: DataMemBusParam) extends Bundle {
  val write   = Bool()
  val address = UInt(p.addressWidth bits)
  val data    = Bits(p.dataWidth bits)
  val mask    = Bits(p.dataBytes bits)
}

/** The answer to one command: the read word (don't-care for a write, which only needs the ack). */
case class DataMemRsp(p: DataMemBusParam) extends Bundle {
  val data = Bits(p.dataWidth bits)
}

/**
 * The simplified, **single-outstanding, no-AMO** data memory bus the [[riscq.riscv.execute.LsuPlugin]]
 * speaks — the riscq analog of VexiiRiscv's `LsuCachelessBus`, stripped to what an in-order RV32I LSU
 * needs: a `cmd` request stream and a `rsp` response flow. Every accepted `cmd` produces exactly one
 * `rsp` (a load's word, or a store's ack with don't-care data); the master halts until it arrives, so
 * `rsp` is a [[Flow]] (the master is always ready for its one outstanding response).
 *
 * This keeps the LSU free of the full Tilelink A/D-channel boilerplate. The Tilelink-ization (and any
 * fabric posting/decoding) lives at the core→fabric boundary in `DataMemBusToTilelink` — mirroring the
 * VexiiRiscv split where the cacheless plugin emits `LsuCachelessBus` and a separate bridge converts it.
 */
case class DataMemBus(p: DataMemBusParam) extends Bundle with IMasterSlave {
  val cmd = Stream(DataMemCmd(p))
  val rsp = Flow(DataMemRsp(p))

  override def asMaster(): Unit = {
    master(cmd)
    slave(rsp)
  }
}
