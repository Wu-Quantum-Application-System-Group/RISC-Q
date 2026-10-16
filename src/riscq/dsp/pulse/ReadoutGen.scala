package riscq.dsp.pulse

import spinal.core._

/** Emit Verilog for the readout decoder. Run with `mill runMain riscq.dsp.pulse.ReadoutGen`. */
object ReadoutGen extends App {
  SpinalVerilog(ReadoutDecoder(ReadoutDecoderParams()))
}
