package riscq.dsp

import spinal.core._
import spinal.lib._

/** Command payload for [[ComplexMul]]: the two operands `a`, `b` (product `p = a·b`). */
case class ComplexMulCmd(width: Int) extends Bundle {
  val a = Complex(width)
  val b = Complex(width)
}

/**
 * 3-multiplier (Karatsuba) complex product `p = a·b`, fully pipelined, initiation interval 1.
 * For `a = ar + i·ai`, `b = br + i·bi`:
 *
 * {{{
 *   m  = (ar − ai)·bi                          // one shared product
 *   pr = (br − bi)·ar + m  =  ar·br − ai·bi     // Re(a·b)
 *   pi = (br + bi)·ai + m  =  ar·bi + ai·br     // Im(a·b)
 * }}}
 *
 * Three DSP48E2 (the `(w+1)×w` products), the pre-adds and recombination in fabric. The full
 * integer product carries `2(w−1)` fractional bits; the output drops `w−1` of them with an
 * arithmetic `>>(w−1)` (a free constant slice), **round-to-nearest** (a `+½ ulp` constant folded
 * into the recombination adder — no separate rounder), then **saturates to `[−Amax, +Amax]`**.
 *
 * Saturation is needed because `|a·b|` can reach 2 (e.g. `a = b = −1−i` ⇒ `a·b = 2i`), so the slice
 * alone would wrap. It is distortion-free *in contract* — carrier/phasor operands always have
 * magnitude `< 1`, so the product never saturates — and merely bounds the error for out-of-contract
 * user envelopes. Same output convention as `SinCos` v2.
 *
 * Pipeline (II = 1, `latency` register stages):
 * {{{
 *   n0: register operands a, b
 *   n1: shared pre-adder      DA = ar−ai                           (one stage ahead of M)
 *   n2: shared product        M = DA·bi                            (held to n4, then copied per output)
 *   n3: side pre-adders       DB = br−bi, SB = br+bi               (one stage ahead of PRE/PIM)
 *   n4: side products         PRE = DB·ar, PIM = SB·ai
 *       M copies              MCR = M, MCI = M                     (→ recombination DSPs' C registers)
 *   n5: recombine + round     SRE=PRE+MCR+½ulp, SIM=PIM+MCI+½ulp
 *   n6: arithmetic >>(w−1) (slice) + narrow to SF(w) — clamp to ±Amax, or a bare truncate if `saturate`
 *       is off (the simple mode; the caller then keeps both operands in the unit disk). Done
 *       combinationally off the recombine register, no extra reg.
 * }}}
 *
 * Two structural rules drive Vivado's DSP packing:
 *
 *  - '''Each Karatsuba pre-add is placed exactly one stage before its multiply''' (`DA`→`M`, `DB`/`SB`
 *    →`PRE`/`PIM`). The link register between them then becomes the DSP48E2 `ADREG`, so Vivado packs
 *    the `br±bi` / `ar−ai` adds into the slice's '''internal `(D±A)` pre-adder''' instead of leaving
 *    them in fabric. That removes the CARRY8 pre-add and its route into the DSP operand port.
 *    Computing a pre-add two stages early would strand it in fabric.
 *  - '''`M` is produced one stage ahead of `PRE`/`PIM`''', then copied into a '''dedicated per-output
 *    register''' (`MCR` for `SRE`, `MCI` for `SIM`) the stage before the recombination. Each copy maps
 *    to its recombination DSP's '''C register (CREG)''', so `M` reaches the ALU as a settled C-port
 *    register (reg-to-reg route, then a DSP-internal `MREG + CREG` add) rather than routing
 *    combinationally from `M`'s DSP output into the ALU. Without the copies Vivado holds `M` in its own
 *    DSP `PREG` and routes that output straight into both recombination ALUs — one shared high-fanout
 *    net. Splitting the copies per output drops the fanout and lets the CREG break the path. This also
 *    keeps the recombination one DSP-ALU op deep, so every mul maps to exactly 3 DSP48E2 (no `PCIN`
 *    cascade, which would be a two-ALU chain and a 4th DSP/mul).
 *
 * The whole structure runs at a shared latency of 6.
 */
case class ComplexMul(width: Int, saturate: Boolean = true, resetValid: Boolean = true) extends Component {
  require(width >= 2, "Complex SF(width) needs at least a sign bit + a magnitude bit")
  // NB: do NOT KEEP_HIERARCHY this block — a fence here cuts the DSP48E2 pipeline and blocks the
  // register retiming the 3-DSP/lane packing relies on. Fence the *enclosing* datapath block
  // (PulseGenerator / ReadoutDecoder) instead.
  val io = new Bundle {
    val cmd = slave(Flow(ComplexMulCmd(width)))  // no backpressure — II = 1 by construction
    val rsp = master(Flow(Complex(width)))       // rsp.valid is cmd.valid delayed by `latency`
  }

  val w        = width
  val amax     = (BigInt(1) << (w - 1)) - 1   // ±Amax saturation limit (2^(w-1) − 1)
  val roundCst = BigInt(1) << (w - 2)         // +½ ulp at the >>(w−1) rounding point
  val accW     = 2 * w + 3                    // recombination width (no-overflow: |pr|,|pi| ≲ 2^(2w))

  /** Drop `w−1` fractional bits (arithmetic shift), then narrow to SF(w): clamp to `[−Amax, +Amax]` when
   *  `saturate`, else a bare two's-complement truncate (`saturate = false` ⇒ the caller keeps both operands
   *  within the unit disk so the product can't overflow, so no clamp logic is built — the simpler/faster
   *  path). The clamp tests both rails as parallel flags with a `max_fanout` cap so the select replicates
   *  next to the `w` output-bit muxes (otherwise this select net is fanout/route-bound). */
  def narrow(acc: SInt): SInt = {
    val shifted = acc >> (w - 1) // free constant slice, accW−(w−1) bits
    if (!saturate) shifted.resize(w bits)
    else {
      val hi    = S(amax, shifted.getWidth bits)
      val lo    = S(-amax, shifted.getWidth bits)
      val ovfHi = shifted > hi
      val ovfLo = shifted < lo
      ovfHi.addAttribute("max_fanout", 6)
      ovfLo.addAttribute("max_fanout", 6)
      (ovfHi ? hi | (ovfLo ? lo | shifted)).resize(w bits)
    }
  }

  // Stage layout (n0..n6, latency 6): each Karatsuba pre-add sits one stage before its multiply
  // (→ DSP48E2 internal pre-adder), M is held two stages then copied per output (→ the recombination
  // DSPs' C registers), and the output >>(w−1) narrow is done combinationally off the recombine register.

  // n0: register operands a, b
  val ar0 = RegNext(io.cmd.a.re);  val ai0 = RegNext(io.cmd.a.im)
  val br0 = RegNext(io.cmd.b.re);  val bi0 = RegNext(io.cmd.b.im)

  // n1: shared pre-adder DA = ar−ai (one stage ahead of M ⇒ this reg is M's DSP ADREG)
  val da1 = RegNext(ar0 -^ ai0)
  val ar1 = RegNext(ar0);  val ai1 = RegNext(ai0)
  val br1 = RegNext(br0);  val bi1 = RegNext(bi0)

  // n2: shared product M = DA·bi; carry ar, ai, br, bi forward.
  // use_dsp on the three products only (mulM/mulPre/mulPim): the 14q SoC's ~2786-DSP demand sits
  // right at Vivado's ~65% DSP-utilization heuristic, and without the directive the global DSP
  // balancer demotes a handful of these muls to fabric (CARRY8 chains, dp ≈ 3.3 ns — measured:
  // the 14q builds spilled 18 DSPs in the readout decoders and collapsed under the resulting
  // congestion; the choice is also environment-sensitive, so un-pinned builds are not
  // reproducible). Keep the scope tight: a module-level use_dsp drags the pre-adds /
  // recombination adds into standalone DSPs (+425, 75% util ⇒ the datapath confine NOFITs).
  // Expression-vs-register placement of the attribute synthesizes identically (measured,
  // byte-identical builds); what the MREG absorption really needs is a CLOCKED synthesis run —
  // an untimed run maps the product register as PREG and leaves MREG empty (DRC DPOP-4; see the
  // riscvsoc-bd packaged-IP OOC XDC). Zero behavioural change.
  val mulM = da1 * bi1
  mulM.addAttribute("use_dsp", "yes")
  val m2  = RegNext(mulM)
  val ar2 = RegNext(ar1);  val ai2 = RegNext(ai1)
  val br2 = RegNext(br1);  val bi2 = RegNext(bi1)

  // n3: side pre-adders DB = br−bi, SB = br+bi (one stage ahead of PRE/PIM ⇒ these regs are the DSP
  //     ADREGs, keeping br±bi out of fabric and off the route into the DSP operand port)
  val db3 = RegNext(br2 -^ bi2)
  val sb3 = RegNext(br2 +^ bi2)
  val ar3 = RegNext(ar2);  val ai3 = RegNext(ai2)
  val m3  = RegNext(m2)

  // n4: side products PRE = DB·ar, PIM = SB·ai; dedicated M copies MCR, MCI per output:
  //     each copy's reg maps to its recombination DSP's C register (CREG), so M is a settled
  //     low-fanout C-port input rather than a live high-fanout route from M's DSP output
  val mulPre = db3 * ar3
  val mulPim = sb3 * ai3
  mulPre.addAttribute("use_dsp", "yes")
  mulPim.addAttribute("use_dsp", "yes")
  val pre4 = RegNext(mulPre)
  val pim4 = RegNext(mulPim)
  val mcr4 = RegNext(m3)
  val mci4 = RegNext(m3)

  // n5: recombine + round
  val sre5 = RegNext(pre4.resize(accW) + mcr4.resize(accW) + S(roundCst, accW bits))
  val sim5 = RegNext(pim4.resize(accW) + mci4.resize(accW) + S(roundCst, accW bits))

  // n6: >>(w−1) narrow off the recombine (n5) register. No-sat: a bare bit-slice — free, kept
  // combinational so the recombination DSP's PREG drives the next stage directly. Sat: the clamp is
  // real fabric logic on the DSP output; register it (one extra cycle) so it is a registered
  // DSP-output barrier — without it Vivado over-folds the cascade and FF/route blow up.
  val reN = narrow(sre5)
  val imN = narrow(sim5)
  if (saturate) { io.rsp.payload.re := RegNext(reN); io.rsp.payload.im := RegNext(imN) }
  else          { io.rsp.payload.re := reN;          io.rsp.payload.im := imN }
  // rsp.valid is cmd.valid delayed by `latency`. `resetValid` (default) gives a well-defined false from
  // reset (the standalone Flow contract). `resetValid = false` makes the chain reset-free (off the global
  // reset net, SRL-inferable) — safe for a parent that never gates on this valid:
  // PulseGenerator and ReadoutDecoder read the rsp payload at fixed latency and gate the output with the
  // reset-bearing duration counter / time-compare instead, so a boot value is never acted on.
  io.rsp.valid := (if (resetValid) Delay(io.cmd.valid, latency, init = False)
                   else            Delay(io.cmd.valid, latency))

  /** cmd→rsp pipeline latency: 6 register stages + 1 for the registered clamp output (sat only). */
  def latency: Int = ComplexMul.latency(saturate)
}

object ComplexMul {
  /** 7 pipeline nodes n0..n6, giving 6 inter-stage registers. The saturating output adds one more
   *  register so the `±Amax` clamp is a registered DSP-output barrier; the no-sat output is a bare
   *  slice and stays combinational. */
  val nStages: Int = 7
  def latency(saturate: Boolean = true): Int = (nStages - 1) + (if (saturate) 1 else 0)

  /**
   * Bit-exact integer model of `p = a·b` — the 3-mult Karatsuba product and `+½ ulp` round-to-nearest
   * on the `>>(w−1)` shift, then either `±Amax` saturation (`saturate = true`) or a bare two's-complement
   * wrap to `width` bits (`saturate = false`, matching the HW `resize`), exactly as the hardware. Reused
   * by `ComplexMulSim` and by the carrier/pulse golden models.
   */
  def model(width: Int, ar: BigInt, ai: BigInt, br: BigInt, bi: BigInt, saturate: Boolean = true): (BigInt, BigInt) = {
    val amax     = (BigInt(1) << (width - 1)) - 1
    val roundCst = BigInt(1) << (width - 2)
    def wrap(x: BigInt): BigInt = {
      val mod = BigInt(1) << width
      val r   = ((x % mod) + mod) % mod
      if (r >= (BigInt(1) << (width - 1))) r - mod else r
    }
    def fit(x: BigInt): BigInt =
      if (saturate) (if (x > amax) amax else if (x < -amax) -amax else x) else wrap(x)
    val m      = (ar - ai) * bi
    val prFull = (br - bi) * ar + m
    val piFull = (br + bi) * ai + m
    (fit((prFull + roundCst) >> (width - 1)), fit((piFull + roundCst) >> (width - 1)))
  }
}
