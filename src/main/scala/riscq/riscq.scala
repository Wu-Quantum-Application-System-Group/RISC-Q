package riscq

import spinal.core._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin._

import scala.collection.mutable.ArrayBuffer

case class RiscqParams() {
  var pulseMemOutReg = true
  var rfReadSync = false
  var enableBypass = true
  var pcReset = 0x80000000L
  var withTest = false
  var withMul = false

  val rfReadAt = -1 - rfReadSync.toInt

  def getPlugins() = new Area {
    val plugins = ArrayBuffer[FiberPlugin]()
    val pp = new schedule.PipelinePlugin()
    plugins += pp
    plugins += new riscv.RiscvPlugin(xlen = 32)
    plugins += new schedule.ReschedulePlugin()
    plugins += new fetch.PcPlugin()
    plugins += new fetch.FetchCachelessPlugin(
      wordWidth = 32,
      forkAt = 0,
      joinAt = 4
    )
    // plugins += new decode.DecoderSimplePlugin(decodeAt = 0)
    plugins += new decode.DecoderPlugin(decodeAt = 0)
    plugins += new regfile.RegFilePlugin(
      spec = riscv.IntRegFile,
      physicalDepth = 32,
      preferedWritePortForInit = "",
      syncRead = rfReadSync,
      dualPortRam = false,
      maskReadDuringWrite = false
    )
    plugins += new execute.RegReadPlugin(rfReadAt = rfReadAt, enableBypass = enableBypass)
    plugins += new execute.SrcPlugin(executeAt = 0, relaxedRs = true)
    plugins += new schedule.HazardPlugin(rfReadAt = rfReadAt, hazardAt = rfReadAt, enableBypass = enableBypass)
    plugins += new execute.WriteBackPlugin(riscv.IntRegFile, writeAt = 2, allowBypassFrom = 1)
    plugins += new execute.IntFormatPlugin()
    plugins += new execute.IntAluPlugin(executeAt = 0, formatAt = 0)
    plugins += new execute.BarrelShifterPlugin(shiftAt = 0, formatAt = 0)
    plugins += new execute.BranchPlugin(aluAt = 0, jumpAt = 1, wbAt = 0)
    plugins += new execute.lsu.LsuCachelessNoRspStorePlugin(addressAt = 0, forkAt = 0, joinAt = 1, wbAt = 2)
    if(withMul) {
      plugins += new execute.MulPlugin(splitAt = 0, partialMulAt = 0, add1At = 1, add2At = 2, formatAt = 2)
    }
    if(withTest) {
      plugins += new test.WhiteboxerPlugin()
    }
  }
}


case class RiscQ(plugins: Seq[FiberPlugin]) extends Component{
  val database = new Database
  val host = database on (new PluginHost)
  host.asHostOf(plugins)
}




