package riscq.soc

import spinal.core._
import riscq.riscv.RiscqParam

/**
 * Generate `PulseTableSoc.v` from a per-build `SocSpec` JSON (under `software/configs/`) — the file
 * the python `riscq.spec.SocSpec` also loads, so hardware and software derive their address maps from
 * one parameter set ([[riscq.soc.spec.SocSpec]]; legacy `SocParams` files are converted on load).
 *
 * Defaults to the plain-port (`vivado = false`) form the co-sim / OOC flows use. Pass `vivado` as a third
 * arg for the IP-packager / block-design form (the old `GenPulseTableSocVivado`): the
 * `X_INTERFACE_INFO`/`FREQ_HZ` attrs, the `hostClk`/`hostRst` rename, `LutInputs = 6`, plus the companion
 * `ClockInterface.v` clock-buffer wrapper.
 *
 *   mill runMain riscq.soc.GenPulseTableSocJson software/configs/sim-2q.json software/build/sim-2q/rtl
 *   mill runMain riscq.soc.GenPulseTableSocJson software/configs/zcu216-14q.json build/rtl vivado
 */
object GenPulseTableSocJson extends App {
  require(args.length == 2 || args.length == 3,
    "usage: GenPulseTableSocJson <config.json> <targetDir> [vivado]")
  val spec = riscq.soc.spec.SocSpec.load(args(0))
  val dir = args(1)
  val vivadoMode = args.lift(2).contains("vivado")

  // The SoC is built from the channel-list spec directly (universal-control/01 P1): every core's
  // channels, converters and memories come from its CoreSpec; the host map is SocSpecMap(spec).
  val qubitNum = spec.qubitNum

  // `def` (not `val`) so the Component is constructed inside `.generate` — building it at the App's top
  // level throws `GlobalData ... null` (no elaboration context yet).
  def buildSoc() = new PulseTableSoc(spec,
    // The bit-exact residual-depth levers (B3/B4/E1–E3) and the fanout caps (B2 jump / E4 fetch /
    // dsp-fmax B2 dcOffset) plus B1-alt/B3-leanpop are all baked into the RTL now — no longer knobs;
    // the C1 registered head is a TimedQueue-level option, no longer plumbed through the SoC.
    // `withMul` is per core, from the spec.
    coreParam = RiscqParam(gshareMem = true, csrWarl = true,
      aluNoFastForward = true, aluResultOneHot = true, pcRegMaxFanout = 16),
    vivado = vivadoMode)

  // vivado mode: LUT6 packing + the companion ClockInterface.v BUFG wrapper, matching GenPulseTableSocVivado.
  val spinal = SpinalConfig(mode = Verilog, targetDirectory = dir, romReuse = true)
  val emit   = if (vivadoMode) spinal.setScopeProperty(LutInputs, 6) else spinal
  emit.generate(buildSoc())
  if (vivadoMode) emit.generate(riscq.misc.ClockInterface())

  val extra = if (vivadoMode) " + ClockInterface.v" else ""
  println(s"[GenPulseTableSocJson] emitted $dir/PulseTableSoc.v$extra " +
    s"(${spec.name}, qubitNum=$qubitNum, vivado=$vivadoMode)")
}
