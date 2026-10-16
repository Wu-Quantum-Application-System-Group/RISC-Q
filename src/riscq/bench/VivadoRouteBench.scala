package riscq.bench

/**
 * Post-**place&route** sibling of [[VivadoBench]], and equally **DUT-agnostic**.
 *
 * [[VivadoBench]] runs synthesis only (out-of-context) and is fast enough for the edit/measure loop,
 * but its net delays are pre-placement *estimates* — optimistic/rough, never sign-off. This runs the
 * full `synth → opt → place → route` flow (still out-of-context on the target part) and re-parses
 * `report_timing` so we get the **real** achievable fmax and the **real** critical-path shape after
 * placement. Use it to confirm where the post-route critical path actually is before committing to a
 * structural change that OOC synth alone suggested.
 *
 * It is a thin wrapper: it asks [[VivadoBench]] to run the same flow with place&route enabled, and
 * the report is parsed/printed by [[VivadoBench]]'s `parse`/`printReport` (same format) — only the
 * numbers are post-route. Slower than [[VivadoBench]] (P&R), but small modules are still a few
 * minutes, not an hour. Drive it from a per-DUT wrapper (e.g. `riscq.riscv.bench.VivadoRouteBench`)
 * that supplies a [[Dut]]; this object has no `main`.
 */
object VivadoRouteBench {
  /** Generate the DUT's Verilog into `ws`, run the full place&route flow, and return the
   *  post-route `report_timing` text (feed it to `VivadoBench.parse` / `printReport`). */
  def runVivado(dut: Dut, ws: String): String = VivadoBench.runVivado(dut, ws, route = true)
}
