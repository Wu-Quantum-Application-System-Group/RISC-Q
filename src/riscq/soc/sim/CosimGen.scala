package riscq.soc.sim

import spinal.core._
import riscq.soc.PulseTableSoc

/**
 * Emit a small `PulseTableSoc` for the Python co-simulation. Same 2-qubit config
 * as `PulseTableSocCpuSim`/`PulseTableSocSim` (dacMap (0,*)->8, (1,*)->1; adcMap 0->12, 1->13), so the
 * cocotb harness drives the identical DUT the Scala sims sign off. `withTest = false`: the cocotb path
 * uses only the top-level host AXI + ADC/DAC ports (the test tap is sim-only `simPublic`, not a port),
 * running the production ELF flow exactly as on real hardware.
 *
 *   mill runMain riscq.soc.sim.CosimGen [targetDir]
 */
object CosimGen extends App {
  val dir    = if (args.nonEmpty) args(0) else "software/python/riscq/cosim/rtl"
  val dacMap = Map((0, 0) -> 8, (0, 1) -> 8, (1, 0) -> 1, (1, 1) -> 1)
  val adcMap = Map(0 -> 12, 1 -> 13)
  SpinalConfig(mode = Verilog, targetDirectory = dir, romReuse = true)
    .generate(PulseTableSoc(qubitNum = 2, dacMap = dacMap, adcMap = adcMap, withTest = false))
  println(s"[CosimGen] emitted $dir/PulseTableSoc.v (qubitNum=2, withTest=false)")
}

/**
 * The DUT the `examples/` co-sim notebooks drive. Identical 2-qubit `PulseTableSoc` as [[CosimGen]],
 * but core 0 is wired to the **same physical converters it owns in the production 14-qubit map**
 * ([[riscq.soc.SocChannelMap]] / `GenPulseTableSoc`): gate-drive → DAC 0, readout-drive → DAC 14,
 * ADC → ADC 14. So the notebooks can play a pulse on `dac_0` and inject a tone on `adc_14` exactly as
 * named on the real board, while still Verilating the small fast 2-qubit DUT. Core 1 self-loops on
 * distinct converters (1/15) so nothing collides. `withTest = false` → the production ELF flow.
 *
 *   mill runMain riscq.soc.sim.CosimExampleGen [targetDir]
 */
object CosimExampleGen extends App {
  val dir    = if (args.nonEmpty) args(0) else "software/python/riscq/cosim/rtl_examples"
  val dacMap = Map((0, 0) -> 0, (0, 1) -> 14, (1, 0) -> 1, (1, 1) -> 15)
  val adcMap = Map(0 -> 14, 1 -> 15)
  SpinalConfig(mode = Verilog, targetDirectory = dir, romReuse = true)
    .generate(PulseTableSoc(qubitNum = 2, dacMap = dacMap, adcMap = adcMap, withTest = false))
  println(s"[CosimExampleGen] emitted $dir/PulseTableSoc.v (core 0 → DAC 0 / ADC 14, qubitNum=2)")
}
