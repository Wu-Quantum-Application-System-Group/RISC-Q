package riscq.dsp

import spinal.core._

/** Emit Verilog for the `SinCos` converter. Run with
  * `mill runMain riscq.dsp.SinCosGen`.
  *
  *  - `SinCos.v`       — v1 quarter-wave LUT, default params (16/16/11).
  *  - `SinCosTaylor.v` — v2 Taylor-corrected, recommended production config (16/16/8).
  */
object SinCosGen extends App {
  SpinalVerilog(SinCos(SinCosParams()))
  SpinalConfig(netlistFileName = "SinCosTaylor.v")
    .generateVerilog(SinCos(SinCosParams(tableIndexBits = 8, method = SinCosMethod.Taylor)))
}
