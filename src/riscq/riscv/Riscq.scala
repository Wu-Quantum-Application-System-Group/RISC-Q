package riscq.riscv

import spinal.core._
import spinal.lib.misc.database.Database
import spinal.lib.misc.plugin.{Hostable, PluginHost}

/**
 * The riscq toplevel. Like VexiiRiscv, it defines no hardware itself: it only owns a
 * fiber `Database` (for globally shared constants) and a `PluginHost` (on which all the
 * feature plugins are registered and elaborated).
 */
class Riscq extends Component {
  val database = new Database
  val host     = database on (new PluginHost)
}

object Riscq {
  def apply(plugins: Seq[Hostable]): Riscq = {
    val r = new Riscq
    r.host.asHostOf(plugins)
    r
  }
}

/** Emit Verilog for the default configuration. Run with `mill runMain riscq.riscv.RiscqGen`. */
object RiscqGen extends App {
  SpinalVerilog(Riscq(RiscqParam().plugins()))
}
