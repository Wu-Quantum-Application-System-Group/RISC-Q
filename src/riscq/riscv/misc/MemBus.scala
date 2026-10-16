package riscq.riscv.misc

import spinal.lib.bus.misc.SizeMapping
import spinal.lib.bus.tilelink._

/**
 * Tilelink master-bus parameters for riscq's cacheless fetch / data busses.
 *
 * Transfers are word-sized (1..dataBytes); the fetch bus only reads (`get`), the data bus also
 * writes (`putFull`/`putPartial`). `idCount` sizes the source-id space: each in-flight request
 * needs a distinct id, so the pipelined fetch passes `idCount > 1` to keep several Gets
 * outstanding and reorder their responses, while the single-outstanding LSU keeps the default 2
 * (only source 0 is used; 2 avoids the 0-width-source corner case in the sim agent). This is the
 * much-simplified equivalent of VexiiRiscv's `CachelessBusParam.toTilelinkM2s`, minus AMO.
 */
object MemBus {
  def param(addressWidth: Int, dataWidth: Int, withStore: Boolean, idCount: Int = 2): BusParameter = {
    val bytes = dataWidth / 8
    val transfers = M2sTransfers(
      get        = SizeRange(1, bytes),
      putFull    = if (withStore) SizeRange(1, bytes) else SizeRange.none,
      putPartial = if (withStore) SizeRange(1, bytes) else SizeRange.none
    )
    M2sParameters(
      addressWidth = addressWidth,
      dataWidth    = dataWidth,
      masters = List(M2sAgent(
        name = null,
        mapping = List(M2sSource(id = SizeMapping(0, idCount), emits = transfers))
      ))
    ).toNodeParameters().toBusParameter()
  }
}
