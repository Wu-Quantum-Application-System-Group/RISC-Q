package riscq.soc.rf

import spinal.core._
import spinal.lib._
import riscq.dsp.Complex
import riscq.soc.link.{EventSource, RfCmd}

/**
 * The contract every converter-edge channel kind implements (specs/universal-control/01 §2.3): one
 * demuxed posted `cmd` sub-window in, the shared `time` broadcast in, an envelope-RAM read port out,
 * and — depending on the kind — a DAC-bound `dacOut` or a decoder-bound `carrier`. The core shell
 * ([[riscq.soc.RiscqRfWithPulseTableFiber]]) instantiates channels from a `CoreSpec`'s list through
 * this interface and never names a kind. `envLanes` is the lane count the stored envelope line
 * expands to (the DAC batch for drives, the ADC batch for the demod).
 */
trait Channel extends Component {
  def cmd: Flow[RfCmd]
  def timeBcast: UInt
  def memPort: Option[MemReadPort[Bits]]   // the envelope-RAM read port, for kinds with a bank
  def envLanes: Int                        // lanes a stored line expands to (0 = no bank)
  def dacOut: Option[Flow[Vec[Complex]]]
  def carrier: Option[Flow[Vec[Complex]]]
  /** the channel's up-link reporter, for kinds that own one (the demod's is built by the shell, from the
    * decoder it feeds) */
  def event: Option[EventSource] = None
}
