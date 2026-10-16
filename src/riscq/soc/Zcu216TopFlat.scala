package riscq.soc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4WriteOnly, Axi4SpecRenamer, Axi4CrossbarFactory, Axi4IdRemover}
import riscq.soc.link.HostWindowFunnel
import spinal.lib.bus.misc.SizeMapping
import riscq.misc.{IBUFGDS, BUFG}

/**
 * Flow B toplevel — the "flat" (no block-design, no IP-packager) ZCU216 integration. Where Flow A wraps
 * [[PulseTableSoc]] as a user IP and assembles the system in a Vivado block design (PS + RF Data
 * Converter + SmartConnect), this toplevel instantiates the **same** two Xilinx IPs as plain SpinalHDL
 * blackboxes ([[ZynqPs]], [[RfDataConverter]]) and wires everything in RTL. The host AXI fan-out
 * (PS HPM0_LPD → { SoC control, RFDC s_axi }) is a **SpinalHDL Axi4 crossbar**, not a Xilinx fabric IP:
 * SmartConnect and AXI Interconnect v2.1 are IP-Integrator-only, and the classic v1.7 interconnect is
 * not tcl-configurable for a 1→2 routed fabric in this Vivado — so the interconnect lives in SpinalHDL.
 *
 * Clocking: a single host clock from the PS `pl_clk0` (~100 MHz) runs the AXI fabric + the SoC host
 * domain + the RFDC `s_axi`; the external LVDS `dspClk` (~500 MHz, via an IBUFGDS/BUFG) runs the SoC DSP
 * datapath and the RFDC sample-data AXIS. The `user_sysref` LVDS feeds the RFDC sysref. ZynqMP exposes
 * no DDR/MIO fabric ports (they are dedicated PSU pins), so there is no inout passthrough to plumb.
 *
 * The Xilinx IP netlists (modules `zynq_ps` / `rf_data_converter`) are supplied by Vivado `create_ip`
 * and bound to these blackboxes by name at synthesis. (The packaged flat ZCU216 build flow that drove
 * this generator was removed from `vivado-scripts/`; the generator itself is retained.)
 */

/** Zynq UltraScale+ PS blackbox (module `zynq_ps`) — only the PL interface: one 32-bit AXI master
 * (HPM0_LPD, named `maxigp2`, 40-bit addr / 16-bit id) plus `pl_clk0` / `pl_resetn0`. */
case class ZynqPs() extends BlackBox {
  setDefinitionName("zynq_ps")
  val maxihpm0_lpd_aclk = in Bool ()
  val pl_clk0           = out Bool ()
  val pl_resetn0        = out Bool ()
  val maxigp2 = master(Axi4(Axi4Config(
    addressWidth = 40, dataWidth = 32, idWidth = 16,
    useRegion = false, awUserWidth = 16, arUserWidth = 16)))
  Axi4SpecRenamer(maxigp2)
  // S_AXI_HP0_FPD (`saxigp2`) — the host-window write port into the PS DDR4. Declared with the SoC's own
  // config so it wires straight through; a real PS8 wrapper presents a 6-bit ID here, so reviving this
  // (unbuilt) flat top for synthesis would need an id-width adapt.
  val saxihp0_fpd_aclk = in Bool ()
  val saxigp2 = slave(Axi4WriteOnly(HostWindowFunnel.axiConfig(40)))
  Axi4SpecRenamer(saxigp2)
}

/**
 * RF Data Converter blackbox (module `rf_data_converter`) — the 16-DAC / 16-ADC config of
 * `create-ip.tcl`. DAC slices take a 256-bit AXIS word (`s{tile}{slice}_axis`), ADC slices emit a 64-bit
 * AXIS word (`m{tile}{slice}_axis`); the AXI4-Lite `s_axi` (18-bit addr) is the converter control port.
 * Ports are created in tile/slice loops and named to match the IP exactly (bound by name at synthesis).
 */
case class RfDataConverter() extends BlackBox {
  setDefinitionName("rf_data_converter")

  // sample clocks + sysref + s_axi clock/reset
  val s_axi_aclk      = in Bool ()
  val s_axi_aresetn   = in Bool ()
  val adc2_clk_p      = in Bool ();  val adc2_clk_n = in Bool ()
  val dac2_clk_p      = in Bool ();  val dac2_clk_n = in Bool ()
  val sysref_in_p     = in Bool ();  val sysref_in_n = in Bool ()
  val user_sysref_adc = in Bool ();  val user_sysref_dac = in Bool ()
  val clk_adc = Array.tabulate(4)(i => out(Bool()).setName(s"clk_adc$i"))
  val clk_dac = Array.tabulate(4)(i => out(Bool()).setName(s"clk_dac$i"))
  val irq     = out Bool ()

  // per-tile AXIS clocks/resets (DAC tiles s0..s3, ADC tiles m0..m3) — all driven by dspClk
  val s_axis_aclk    = Array.tabulate(4)(t => in(Bool()).setName(s"s${t}_axis_aclk"))
  val s_axis_aresetn = Array.tabulate(4)(t => in(Bool()).setName(s"s${t}_axis_aresetn"))
  val m_axis_aclk    = Array.tabulate(4)(t => in(Bool()).setName(s"m${t}_axis_aclk"))
  val m_axis_aresetn = Array.tabulate(4)(t => in(Bool()).setName(s"m${t}_axis_aresetn"))

  // AXI4-Lite control port (s_axi), 18-bit address, 32-bit data, no id/prot
  val s_axi_awaddr  = in Bits (18 bits); val s_axi_awvalid = in Bool (); val s_axi_awready = out Bool ()
  val s_axi_wdata   = in Bits (32 bits); val s_axi_wstrb  = in Bits (4 bits)
  val s_axi_wvalid  = in Bool ();        val s_axi_wready = out Bool ()
  val s_axi_bresp   = out Bits (2 bits); val s_axi_bvalid = out Bool (); val s_axi_bready = in Bool ()
  val s_axi_araddr  = in Bits (18 bits); val s_axi_arvalid = in Bool (); val s_axi_arready = out Bool ()
  val s_axi_rdata   = out Bits (32 bits);val s_axi_rresp  = out Bits (2 bits)
  val s_axi_rvalid  = out Bool ();       val s_axi_rready = in Bool ()

  // per-slice DAC inputs (256-bit) / ADC outputs (64-bit) + analog pins; index i -> tile i/4, slice i%4
  private def ts(i: Int) = s"${i / 4}${i % 4}"
  val sAxisTdata  = Array.tabulate(16)(i => in(Bits(256 bits)).setName(s"s${ts(i)}_axis_tdata"))
  val sAxisTvalid = Array.tabulate(16)(i => in(Bool()).setName(s"s${ts(i)}_axis_tvalid"))
  val sAxisTready = Array.tabulate(16)(i => out(Bool()).setName(s"s${ts(i)}_axis_tready"))
  val mAxisTdata  = Array.tabulate(16)(i => out(Bits(64 bits)).setName(s"m${ts(i)}_axis_tdata"))
  val mAxisTvalid = Array.tabulate(16)(i => out(Bool()).setName(s"m${ts(i)}_axis_tvalid"))
  val mAxisTready = Array.tabulate(16)(i => in(Bool()).setName(s"m${ts(i)}_axis_tready"))
  val vinP  = Array.tabulate(16)(i => in(Bool()).setName(s"vin${ts(i)}_p"))
  val vinN  = Array.tabulate(16)(i => in(Bool()).setName(s"vin${ts(i)}_n"))
  val voutP = Array.tabulate(16)(i => out(Bool()).setName(s"vout${ts(i)}_p"))
  val voutN = Array.tabulate(16)(i => out(Bool()).setName(s"vout${ts(i)}_n"))
}

case class Zcu216TopFlat(
    qubitNum: Int,
    dacMap: Map[(Int, Int), Int],
    adcMap: Map[Int, Int],
    dacNum: Int = 16,
    adcNum: Int = 16,
    // posted-link RegNext depth per direction. Raise for a wider core↔converter floorplan span — the
    // two-region floorplan pins the cores in one region and the datapath in another, a full-die crossing,
    // so it needs more stages than the default.
    linkPipe: Int = 4
) extends Component {
  setDefinitionName("Zcu216TopFlat")

  // ── external LVDS clocks → single-ended (IBUFGDS; the dsp clock also gets a BUFG) ──
  val dspClk_clk_p = in Bool (); val dspClk_clk_n = in Bool ()
  val user_sysref_clk_p = in Bool (); val user_sysref_clk_n = in Bool ()
  val sysref_in_p = in Bool (); val sysref_in_n = in Bool ()
  val dac_clk_clk_p = in Bool (); val dac_clk_clk_n = in Bool ()
  val adc_clk_clk_p = in Bool (); val adc_clk_clk_n = in Bool ()

  val dspIbuf = IBUFGDS(); dspIbuf.I := dspClk_clk_p; dspIbuf.IB := dspClk_clk_n
  val dspBufg = BUFG();    dspBufg.I := dspIbuf.O
  val dspClk  = dspBufg.O

  val srefIbuf = IBUFGDS(); srefIbuf.I := user_sysref_clk_p; srefIbuf.IB := user_sysref_clk_n
  val userSysref = srefIbuf.O

  // ── PS + RFDC blackboxes ──
  val ps   = ZynqPs()
  val rfdc = RfDataConverter()
  ps.maxihpm0_lpd_aclk := ps.pl_clk0

  // host reset (active high) from pl_resetn0; the SoC + RFDC AXIS run on their own domains. Reset
  // synchronisation is left to the SoC's internal BufferCC (riscqReset); for the flat bring-up build the
  // raw async reset is acceptable (a real board would add a proc_sys_reset, deferred like the BD flow).
  val hostReset = !ps.pl_resetn0
  val hostCd = ClockDomain(
    clock = ps.pl_clk0, reset = hostReset,
    config = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = HIGH))

  // dsp-domain reset, synchronised onto dspClk (boot-init register chain, no async reset needed)
  val dspBoot = ClockDomain(clock = dspClk, config = ClockDomainConfig(resetKind = BOOT))
  val dspReset = dspBoot(BufferCC(hostReset, init = False))

  val core = new ClockingArea(hostCd) {
    val soc = PulseTableSoc(qubitNum = qubitNum, dacMap = dacMap, adcMap = adcMap,
      dacNum = dacNum, adcNum = adcNum, vivado = false, linkPipe = linkPipe)
    soc.io.dspClk := dspClk
    soc.io.dspRst := dspReset
    // host-window results → PS DDR4 over S_AXI_HP0_FPD, clocked by the same host clock as the SoC.
    ps.saxihp0_fpd_aclk := ps.pl_clk0
    ps.saxigp2 << soc.io.hostMem

    // host AXI fan-out: PS HPM0_LPD → { SoC control @0x8000_0000, RFDC s_axi @0xB000_0000 }.
    // The crossbar requires master.idWidth ≤ every slave.idWidth; the SoC control port is idWidth=2 while
    // the PS master is idWidth=16, so strip the PS id first (Axi4IdRemover stores+echoes it, which
    // serialises outstanding host transactions — fine for the low-rate PS control/config path).
    val host    = Axi4IdRemover(ps.maxigp2)
    val rfdcAxi = Axi4(Axi4Config(addressWidth = 32, dataWidth = 32, idWidth = 16, useRegion = false))
    val crossbar = Axi4CrossbarFactory()
    crossbar.addSlaves(
      soc.io.axi -> SizeMapping(0x80000000L, BigInt(1) << 28),
      rfdcAxi    -> SizeMapping(0xb0000000L, BigInt(1) << 18))
    crossbar.addConnection(host, Seq(soc.io.axi, rfdcAxi))
    crossbar.build()

    // rfdcAxi (full Axi4, single-beat control traffic) → RFDC AXI4-Lite s_axi
    rfdc.s_axi_awaddr  := rfdcAxi.aw.addr(17 downto 0).asBits; rfdc.s_axi_awvalid := rfdcAxi.aw.valid
    rfdcAxi.aw.ready   := rfdc.s_axi_awready
    rfdc.s_axi_wdata   := rfdcAxi.w.data; rfdc.s_axi_wstrb := rfdcAxi.w.strb
    rfdc.s_axi_wvalid  := rfdcAxi.w.valid; rfdcAxi.w.ready := rfdc.s_axi_wready
    rfdcAxi.b.valid    := rfdc.s_axi_bvalid; rfdcAxi.b.resp := rfdc.s_axi_bresp
    rfdcAxi.b.id       := 0; rfdc.s_axi_bready := rfdcAxi.b.ready
    rfdc.s_axi_araddr  := rfdcAxi.ar.addr(17 downto 0).asBits; rfdc.s_axi_arvalid := rfdcAxi.ar.valid
    rfdcAxi.ar.ready   := rfdc.s_axi_arready
    rfdcAxi.r.valid    := rfdc.s_axi_rvalid; rfdcAxi.r.data := rfdc.s_axi_rdata
    rfdcAxi.r.resp     := rfdc.s_axi_rresp;  rfdcAxi.r.id := 0; rfdcAxi.r.last := True
    rfdc.s_axi_rready  := rfdcAxi.r.ready
  }

  // ── RFDC clocks / sysref / s_axi control clock ──
  rfdc.s_axi_aclk    := ps.pl_clk0
  rfdc.s_axi_aresetn := ps.pl_resetn0
  rfdc.adc2_clk_p := adc_clk_clk_p; rfdc.adc2_clk_n := adc_clk_clk_n
  rfdc.dac2_clk_p := dac_clk_clk_p; rfdc.dac2_clk_n := dac_clk_clk_n
  rfdc.sysref_in_p := sysref_in_p;  rfdc.sysref_in_n := sysref_in_n
  rfdc.user_sysref_adc := userSysref; rfdc.user_sysref_dac := userSysref
  for (t <- 0 until 4) {
    rfdc.s_axis_aclk(t) := dspClk; rfdc.s_axis_aresetn(t) := !dspReset
    rfdc.m_axis_aclk(t) := dspClk; rfdc.m_axis_aresetn(t) := !dspReset
  }

  // ── DAC / ADC sample data: SoC physical channel i ↔ RFDC slice (i/4, i%4) ──
  for (i <- 0 until dacNum) {
    rfdc.sAxisTdata(i)  := core.soc.io.dac(i).payload
    rfdc.sAxisTvalid(i) := core.soc.io.dac(i).valid
    core.soc.io.dac(i).ready := rfdc.sAxisTready(i)
  }
  for (i <- 0 until adcNum) {
    core.soc.io.adc(i).valid   := rfdc.mAxisTvalid(i)
    core.soc.io.adc(i).payload := rfdc.mAxisTdata(i)
    rfdc.mAxisTready(i) := core.soc.io.adc(i).ready
  }

  // ── analog converter pins straight to top-level device ports ──
  val vinP  = Array.tabulate(adcNum)(i => in(Bool()).setName(s"vin${i / 4}${i % 4}_p"))
  val vinN  = Array.tabulate(adcNum)(i => in(Bool()).setName(s"vin${i / 4}${i % 4}_n"))
  val voutP = Array.tabulate(dacNum)(i => out(Bool()).setName(s"vout${i / 4}${i % 4}_p"))
  val voutN = Array.tabulate(dacNum)(i => out(Bool()).setName(s"vout${i / 4}${i % 4}_n"))
  for (i <- 0 until adcNum) { rfdc.vinP(i) := vinP(i); rfdc.vinN(i) := vinN(i) }
  for (i <- 0 until dacNum) { voutP(i) := rfdc.voutP(i); voutN(i) := rfdc.voutN(i) }
}

object GenZcu216TopFlat extends App {
  // args: <qubitNum=14> [linkPipe=4].
  val qubitNum = args.lift(0).map(_.toInt).getOrElse(14)
  val linkPipe = args.lift(1).map(_.toInt).getOrElse(4)
  SpinalConfig(mode = Verilog, targetDirectory = "./build/rtl-flat", romReuse = true)
    .generate(Zcu216TopFlat(
      qubitNum = qubitNum,
      dacMap   = SocChannelMap.dacMap(qubitNum),
      adcMap   = SocChannelMap.adcMap(qubitNum),
      linkPipe = linkPipe))
  println(s"[GenZcu216TopFlat] emitted build/rtl-flat/Zcu216TopFlat.v (qubitNum=$qubitNum, linkPipe=$linkPipe)")
}
