package riscq.riscv.bench

import riscq.bench.{PblockBench, Dut}

/**
 * Pblock-fit bench for the **bare RISC-V core** ([[riscq.riscv.Riscq]] alone, no DSP/RF datapath):
 * does one core's logic fit inside a candidate floorplan rectangle? This is the *core-band* sizing
 * question behind the two-region floorplan. The floorplan pins only the core + its RAM per band, so
 * this is the right unit to test against a sub-clock-region SLICE band of column X0.
 *
 * It reuses [[VivadoBench.dut]] (so it inherits the same `RISCQ_*` core A/B knobs) and hands it to the
 * generic [[riscq.bench.PblockBench]] engine (synth OOC + hard-Pblock place + report).
 *
 * Run (region defaults to the engine's `RISCQ_PBLOCK_REGION`; pass a SLICE band for a sub-CR band):
 *   mill runMain riscq.riscv.bench.RiscqCorePblockBench
 *   RISCQ_PBLOCK_REGION=SLICE_X0Y180:SLICE_X8Y239 mill runMain riscq.riscv.bench.RiscqCorePblockBench
 *   RISCQ_PBLOCK_FLOAT=RAMB,DSP,URAM RISCQ_PBLOCK_ROUTE=0 mill runMain riscq.riscv.bench.RiscqCorePblockBench
 */
object RiscqCorePblockBench extends App {
  val ws = sys.env.getOrElse("RISCQ_BENCH_WS", "simWorkspace/riscqCorePblockBench")
  PblockBench.printReport(PblockBench.runVivado(VivadoBench.dut, ws))
}
