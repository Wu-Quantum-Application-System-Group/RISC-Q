package riscq.dsp

import spinal.core._
import spinal.lib._

/**
 * v1 — quarter-wave BRAM LUT. Bring-up datapath: fold front-end + one true-dual-port table read +
 * sign application, nothing else. Initiation interval 1, four pipeline stages:
 *
 * {{{
 *   s0: decode phase → addr/neg, register (BRAM input side)
 *   s1: true-dual-port table read   (readSync registers the address; data lands next cycle)
 *   s2: BRAM output register         (kept for fmax; costs only latency, which is free)
 *   s3: conditional negate + round-trip, register rsp
 * }}}
 *
 * One [[Mem]] with two synchronous read ports maps to a single BRAM36 in true-dual-port mode at
 * the default `t = 11`; smaller `t` drops into LUTRAM automatically (we never force a primitive).
 *
 * Worst-case output error vs. `A·sin(P)`: `ε ≤ Amax·π·2^-(t+2) + ½ ulp` — phase quantization
 * to the bin midpoint plus entry rounding. The testbench asserts this analytic bound exactly.
 */
object SinCosLut {

  /** cmd→rsp latency: s0 reg + BRAM input reg + BRAM output reg + rsp reg. */
  def latency: Int = 4

  /** Build the v1 datapath in the enclosing component, driving `rsp` from `cmd`. */
  def build(p: SinCosParams, cmd: Flow[SinCosCmd], rsp: Flow[SinCosRsp]): Unit = {
    // s0 — decode (combinational) then register the read inputs.
    val dec = SinCos.decode(p, cmd.payload.phase)
    val s0 = new Area {
      val valid   = RegNext(cmd.valid) init (False)
      val addrSin = RegNext(dec.addrSin)
      val addrCos = RegNext(dec.addrCos)
      val negSin  = RegNext(dec.negSin)
      val negCos  = RegNext(dec.negCos)
    }

    // s1 — true-dual-port table read. readSync registers the address (BRAM input register);
    //      the data lands next cycle. neg/valid travel alongside, delayed to stay aligned.
    val table = Mem.fill(1 << p.tableIndexBits)(UInt(p.ampWidth bits)) init (
      SinCos.quarterTable(p).map(v => U(v, p.ampWidth bits))
    )
    val s1 = new Area {
      val rdSin  = table.readSync(s0.addrSin)
      val rdCos  = table.readSync(s0.addrCos)
      val valid  = RegNext(s0.valid) init (False)
      val negSin = RegNext(s0.negSin)
      val negCos = RegNext(s0.negCos)
    }

    // s2 — BRAM output register (keep for fmax). Align neg/valid by one more cycle.
    val s2 = new Area {
      val rdSin  = RegNext(s1.rdSin)
      val rdCos  = RegNext(s1.rdCos)
      val valid  = RegNext(s1.valid) init (False)
      val negSin = RegNext(s1.negSin)
      val negCos = RegNext(s1.negCos)
    }

    // s3 — apply the quadrant sign and register the response. Magnitudes are in [0, Amax], so
    //      ±mag fits in ampWidth-bit signed: the most-negative code never appears, no overflow.
    //      The negate is one adder per output; its +1 is the two's-complement carry.
    def applySign(mag: UInt, neg: Bool): SInt = {
      val m = mag.intoSInt // non-negative, one guard bit
      Mux(neg, -m, m).resize(p.ampWidth bits)
    }
    rsp.valid       := RegNext(s2.valid) init (False)
    rsp.payload.sin := RegNext(applySign(s2.rdSin, s2.negSin))
    rsp.payload.cos := RegNext(applySign(s2.rdCos, s2.negCos))
  }
}

/**
 * v2 — first-order Taylor correction. Same quadrant fold and quarter-wave table as v1, but the table
 * is read at the **coarse** bin midpoint and the fine residual is corrected with one Taylor term —
 * the Xilinx DDS-compiler architecture. With the coarse full-circle values `S = A·sin(P_c)`,
 * `C = A·cos(P_c)` (v1 output at the coarse midpoint) and the centered residual `δ` (radians), the
 * result is the rotation
 *
 * {{{
 *   sin_out = round( S + δ·C )      cos_out = round( C − δ·S )
 * }}}
 *
 * Correcting *after* the quadrant signs bakes the per-quadrant derivative sign-flips into `S`/`C`,
 * so there are no per-quadrant cases. `δ = δ_int · 2^-(t+f) · (π/2)` with the centered integer
 * residual `δ_int = fine − 2^(f-1)` (just invert fine's MSB); the `2^-(t+f)` is a free binary-point
 * placement, so only the `π/2` is a real KCM constant ([[SinCos.taylorPiConst]]).
 *
 * Fixed-point assembly. The table stores `gw` guard bits, so `S`/`C` carry `gw` fractional bits
 * below an output code; the KCM output `δ_rad = δ_int · round((π/2)·2^Pb)` carries `m+Pb`
 * fractional bits (`m = t+f`). The DSP product `δ_rad·C` then has `R = m+Pb+gw` fractional bits, so
 *
 * {{{
 *   sin_out = ( (S << (m+Pb)) + δ_rad·C + 2^(R-1) ) >> R     // one round, negation carry folded in
 * }}}
 *
 * Accuracy, worst case in output codes:
 * {{{
 *   ε ≤ Amax·π²·2^-(2t+5)   [Taylor remainder]   + 2^-(gw+1)          [table quant]
 *     + Amax·2^-(t+Pb+2)    [δ KCM quant]         + ½                  [final round]
 * }}}
 * At the default `(n_p,n_a,t,gw,Pb) = (16,16,8,2,10)` this is ≈ 0.81 code < 1 ulp — faithful.
 *
 * Pipeline (II = 1, latency 8): two DSP48E2 (one per product) + one LUTRAM table.
 * {{{
 *   s0: decode → addr/neg + centered residual δ_int (invert fine MSB), register
 *   s1: table read (addr reg)              ∥  KCM δ_rad = δ_int · π/2-const, register
 *   s2: table data reg (BRAM/LUTRAM out)   ∥  δ_rad reg
 *   s3: apply quadrant signs → S, C        ∥  δ_rad reg
 *   s4: DSP multiply δ_rad·C, δ_rad·S      (MREG)            ∥  carry S, C
 *   s5: product reg                        (PREG)            ∥  carry S, C
 *   s6: round-accumulate (S<<sh) ± product, >> R → pre-clamp `full`, register
 *   s7: saturate `full` to [-Amax,+Amax], narrow to n_a, register rsp
 * }}}
 * s6/s7 split the final round: the wide accumulate add and the saturation compares were one fused
 * 9-level stage; separating them lifts fmax at one free latency cycle.
 */
object SinCosTaylor {

  /** cmd→rsp latency: s0..s7 each add one register (see the stage map above). */
  def latency: Int = 8

  /** Build the v2 datapath in the enclosing component, driving `rsp` from `cmd`. */
  def build(p: SinCosParams, cmd: Flow[SinCosCmd], rsp: Flow[SinCosRsp]): Unit = {
    val t    = p.tableIndexBits
    val f    = p.fineBits
    val m    = p.quarterIndexBits        // t + f
    val gw   = p.tableGuardBits
    val pb   = p.piScaleBits
    val cmul = SinCos.taylorPiConst(p)   // round((π/2)·2^pb)
    val cmulW = cmul.bitLength + 1       // signed width of the (positive) KCM constant
    val sh   = m + pb                    // left-shift applied to S/C before the final round
    val rnd  = m + pb + gw               // fractional bits rounded off at the output (R)
    val roundConst = BigInt(1) << (rnd - 1)
    val magW = p.ampWidth - 1 + gw       // unsigned table magnitude width

    // s0 — fold (reuse v1 decode) + centered fine residual δ_int = fine − 2^(f-1) (invert MSB).
    val dec  = SinCos.decode(p, cmd.payload.phase)
    val u    = cmd.payload.phase.asBits.asUInt
    val fine = u(f - 1 downto 0)
    val s0 = new Area {
      val valid    = RegNext(cmd.valid) init (False)
      val addrSin  = RegNext(dec.addrSin)
      val addrCos  = RegNext(dec.addrCos)
      val negSin   = RegNext(dec.negSin)
      val negCos   = RegNext(dec.negCos)
      val deltaInt = RegNext((fine ^ U(BigInt(1) << (f - 1), f bits)).asSInt) // f-bit signed
    }

    // High-precision quarter table (gw guard bits), magnitudes only.
    val table = Mem.fill(1 << t)(UInt(magW bits)) init (
      SinCos.quarterTable(p, gw).map(v => U(v, magW bits))
    )

    // s1 — coarse table read (readSync registers the address); KCM injects the π/2 factor. The KCM
    //      is a small `f`-bit × constant multiply — keep it in fabric (use_dsp=no) so the two
    //      DSP48E2 are spent only on the δ·C / δ·S products.
    val s1 = new Area {
      val rdSin    = table.readSync(s0.addrSin)
      val rdCos    = table.readSync(s0.addrCos)
      val deltaRad = RegNext(s0.deltaInt * S(cmul, cmulW bits)) // δ·2^(m+pb), signed
      deltaRad.addAttribute("use_dsp", "no")
      val valid    = RegNext(s0.valid) init (False)
      val negSin   = RegNext(s0.negSin)
      val negCos   = RegNext(s0.negCos)
    }

    // s2 — table output register (keep for fmax); δ_rad rides alongside.
    val s2 = new Area {
      val rdSin    = RegNext(s1.rdSin)
      val rdCos    = RegNext(s1.rdCos)
      val deltaRad = RegNext(s1.deltaRad)
      val valid    = RegNext(s1.valid) init (False)
      val negSin   = RegNext(s1.negSin)
      val negCos   = RegNext(s1.negCos)
    }

    // s3 — apply quadrant signs → coarse full-circle S, C (the v1 result at the coarse midpoint).
    def applySign(mag: UInt, neg: Bool): SInt = {
      val s = mag.intoSInt // non-negative, one guard bit → magW+1 bits signed
      Mux(neg, -s, s)
    }
    val s3 = new Area {
      val S        = RegNext(applySign(s2.rdSin, s2.negSin)) // A·sin(P_c)·2^gw, signed
      val C        = RegNext(applySign(s2.rdCos, s2.negCos)) // A·cos(P_c)·2^gw, signed
      val deltaRad = RegNext(s2.deltaRad)
      val valid    = RegNext(s2.valid) init (False)
    }

    // s4 — DSP multiply (MREG): the two rotation products δ·C and δ·S. S/C ride to the adder.
    val s4 = new Area {
      val prodSin = RegNext(s3.deltaRad * s3.C) // δ_rad · C  → enters sin with +
      val prodCos = RegNext(s3.deltaRad * s3.S) // δ_rad · S  → enters cos with −
      val S       = RegNext(s3.S)
      val C       = RegNext(s3.C)
      val valid   = RegNext(s3.valid) init (False)
    }

    // s5 — DSP product register (PREG). Keep both regs for fmax.
    val s5 = new Area {
      val prodSin = RegNext(s4.prodSin)
      val prodCos = RegNext(s4.prodCos)
      val S       = RegNext(s4.S)
      val C       = RegNext(s4.C)
      val valid   = RegNext(s4.valid) init (False)
    }

    // s6 — round-accumulate: (base << sh) ± product + 2^(R-1), then arithmetic >> R. All terms are
    //      widened to a common accumulator so the adds never overflow nor truncate (must stay
    //      bit-exact with the golden). The >> R is a constant slice (free); only the wide add costs
    //      logic. Register the pre-saturation rounded value `full` here so the add chain and the
    //      saturation compares of s7 land in *separate* stages — that fused add+clamp was a long
    //      critical path; splitting it lifts fmax with one free latency cycle.
    val accW  = sh + p.ampWidth + gw + 4
    val fullW = accW - rnd                 // width after the >> rnd slice (> ampWidth)
    def roundAccum(base: SInt, prod: SInt, add: Boolean): SInt = {
      val shifted = (base << sh).resize(accW bits)
      val p2      = prod.resize(accW bits)
      val rc      = S(roundConst, accW bits)
      val acc     = if (add) shifted + p2 + rc else shifted - p2 + rc
      (acc >> rnd).resize(fullW bits) // arithmetic shift → accW-rnd bits (constant slice, free)
    }
    val s6 = new Area {
      val fullSin = RegNext(roundAccum(s5.S, s5.prodSin, add = true))  // round( S + δ·C ), pre-clamp
      val fullCos = RegNext(roundAccum(s5.C, s5.prodCos, add = false)) // round( C − δ·S ), pre-clamp
      val valid   = RegNext(s5.valid) init (False)
    }

    // s7 — saturate to [-Amax, +Amax] (overflow corner) and narrow to ampWidth, register rsp.
    //      First-order Taylor overshoots the ±peak by up to the dropped 2nd-order term, so the
    //      rounded value can reach Amax+1; clamp rather than wrap. Lossless for faithful configs
    //      (never triggers — the peak real value is < Amax and the error < 1 ulp); for coarse t it
    //      bounds the error to (Amax − |real|), within the accuracy bound.
    def saturate(full: SInt): SInt = {
      val hi      = S(p.ampMax, full.getWidth bits)
      val lo      = S(-p.ampMax, full.getWidth bits)
      val clamped = (full > hi) ? hi | (((full < lo) ? lo | full))
      clamped.resize(p.ampWidth bits)
    }
    rsp.valid       := RegNext(s6.valid) init (False)
    rsp.payload.sin := RegNext(saturate(s6.fullSin))
    rsp.payload.cos := RegNext(saturate(s6.fullCos))
  }
}
