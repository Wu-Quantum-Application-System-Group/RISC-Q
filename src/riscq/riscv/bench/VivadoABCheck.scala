package riscq.riscv.bench

import riscq.bench.{VivadoBench => Engine}

/**
 * OOC synthesis determinism check for riscq fmax experiments.
 *
 * Synthesises the current RTL twice in two separate workspaces and compares the resulting fmax.
 * Each run deletes its workspace and generates fresh Verilog before invoking Vivado — there is no
 * caching. The expected result is *identical* fmax from both runs (zero run-to-run spread).
 *
 * Motivation:
 *   - This check separates stochastic Vivado non-determinism (A ≠ B on identical RTL) from
 *     true netlist-order sensitivity (A = B on identical RTL, A ≠ C when RTL is reshuffled).
 *   - If A ≠ B here, cross-run A/B comparisons require multiple samples and averaging.
 *   - If A = B here, fmax differences between runs reflect real RTL changes, not noise.
 *
 * To also verify netlist-order sensitivity, manually reshuffle a few whitebox vals (e.g. in
 * execute/WriteBackPlugin.scala), then run:
 *   RISCQ_AB_WS_A=bench/VivadoABCheck-C mill runMain riscq.riscv.bench.VivadoABCheck
 * and compare the fmax against the A/B baseline workspaces.
 *
 * Run:
 *   mill runMain riscq.riscv.bench.VivadoABCheck
 */
object VivadoABCheck {
  val wsA = sys.env.getOrElse("RISCQ_AB_WS_A", "bench/VivadoABCheck-A")
  val wsB = sys.env.getOrElse("RISCQ_AB_WS_B", "bench/VivadoABCheck-B")

  def main(args: Array[String]): Unit = {
    println("[VivadoABCheck] Synthesis determinism check: same RTL, two separate workspaces.")
    println("[VivadoABCheck] Each run deletes its workspace and generates fresh Verilog before synthesis.")
    println()

    println(s"[VivadoABCheck] === Run A (workspace: $wsA) ===")
    val fmaxA = Engine.computeFmax(Engine.parse(VivadoBench.runVivado(wsA)))

    println()
    println(s"[VivadoABCheck] === Run B (workspace: $wsB) ===")
    val fmaxB = Engine.computeFmax(Engine.parse(VivadoBench.runVivado(wsB)))

    val delta = math.abs(fmaxA - fmaxB)
    println()
    println("=" * 68)
    println(f"  Run A:  ${fmaxA}%.0f MHz   ($wsA)")
    println(f"  Run B:  ${fmaxB}%.0f MHz   ($wsB)")
    println(f"  Delta:  ${delta}%.1f MHz")
    println()
    if (delta < 2.0)
      println("  RESULT: DETERMINISTIC — OOC synthesis gives the same fmax for identical RTL.")
    else
      println(f"  RESULT: NON-DETERMINISTIC — ${delta}%.1f MHz spread on identical RTL; Vivado is stochastic here.")
    println("=" * 68)
  }
}
