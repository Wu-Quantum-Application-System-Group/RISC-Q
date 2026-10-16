package riscq.dsp.pulse

import spinal.core._

/** Emit Verilog for the pulse generator. Run with `mill runMain riscq.dsp.pulse.PulseGen`. */
object PulseGen extends App {
  SpinalVerilog(PulseGenerator(PulseGeneratorParams()))
}

/** Emit Verilog for the QubiC-aligned pulse generator.
 *  Run with `mill runMain riscq.dsp.pulse.PulseGenAligned`. */
object PulseGenAligned extends App {
  SpinalVerilog(PulseGeneratorAligned(PulseGeneratorParams()))
}
