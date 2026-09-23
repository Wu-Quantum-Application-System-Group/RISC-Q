package riscq.misc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4WriteOnly}

/**
 * `X_INTERFACE_INFO` taggers for AXI4 / AXI4-Stream ports, ported from the RISC-Q reference
 * (`riscq.misc.Axi4Helper`). These let Vivado's IP packager recognise a SpinalHDL-emitted bundle of
 * scalar ports as a single `aximm` / `axis` bus interface, so the block design can auto-connect it.
 * See [[VivadoClkHelper]] for the rationale on why this is opt-in.
 */
object Axi4StreamVivadoHelper {
  def addStreamInference[T <: Data](s: Stream[T], ifcName: String = "AXIS"): Unit = {
    s.payload.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:axis:1.0 " + ifcName + " TDATA")
    s.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:axis:1.0 " + ifcName + " TVALID")
    s.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:axis:1.0 " + ifcName + " TREADY")
  }
}

object Axi4VivadoHelper {
  def addInference(axi: Axi4, ifcName: String = "AXI"): Unit = {
    def tag(d: Data, sig: String): Unit =
      d.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName + " " + sig)

    tag(axi.aw.valid, "AWVALID"); tag(axi.aw.ready, "AWREADY")
    tag(axi.aw.addr, "AWADDR"); Option(axi.aw.id).foreach(tag(_, "AWID"))
    tag(axi.aw.region, "AWREGION"); tag(axi.aw.len, "AWLEN"); tag(axi.aw.size, "AWSIZE")
    tag(axi.aw.burst, "AWBURST"); tag(axi.aw.lock, "AWLOCK"); tag(axi.aw.cache, "AWCACHE")
    tag(axi.aw.qos, "AWQOS"); tag(axi.aw.prot, "AWPROT")

    tag(axi.w.valid, "WVALID"); tag(axi.w.ready, "WREADY")
    tag(axi.w.data, "WDATA"); tag(axi.w.strb, "WSTRB"); tag(axi.w.last, "WLAST")
    Option(axi.w.id).foreach(tag(_, "WID"))

    tag(axi.b.valid, "BVALID"); tag(axi.b.ready, "BREADY")
    Option(axi.b.id).foreach(tag(_, "BID")); tag(axi.b.resp, "BRESP")

    tag(axi.ar.valid, "ARVALID"); tag(axi.ar.ready, "ARREADY")
    tag(axi.ar.addr, "ARADDR"); Option(axi.ar.id).foreach(tag(_, "ARID"))
    tag(axi.ar.region, "ARREGION"); tag(axi.ar.len, "ARLEN"); tag(axi.ar.size, "ARSIZE")
    tag(axi.ar.burst, "ARBURST"); tag(axi.ar.lock, "ARLOCK"); tag(axi.ar.cache, "ARCACHE")
    tag(axi.ar.qos, "ARQOS"); tag(axi.ar.prot, "ARPROT")

    tag(axi.r.valid, "RVALID"); tag(axi.r.ready, "RREADY")
    tag(axi.r.data, "RDATA"); Option(axi.r.id).foreach(tag(_, "RID"))
    tag(axi.r.resp, "RRESP"); tag(axi.r.last, "RLAST")
  }
}

object Axi4WriteOnlyVivadoHelper {
  /** Write-only twin of [[Axi4VivadoHelper.addInference]] for an [[Axi4WriteOnly]] master (the host
   *  window's `M_AXI_HOST` port). Only the write channels exist, and the optional AW fields the config
   *  drops (`region`/`lock`/`qos`) are `null` — so tag defensively. */
  def addInference(axi: Axi4WriteOnly, ifcName: String = "M_AXI"): Unit = {
    def tag(d: Data, sig: String): Unit =
      if (d != null) d.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName + " " + sig)

    tag(axi.aw.valid, "AWVALID"); tag(axi.aw.ready, "AWREADY")
    tag(axi.aw.addr, "AWADDR"); tag(axi.aw.id, "AWID")
    tag(axi.aw.region, "AWREGION"); tag(axi.aw.len, "AWLEN"); tag(axi.aw.size, "AWSIZE")
    tag(axi.aw.burst, "AWBURST"); tag(axi.aw.lock, "AWLOCK"); tag(axi.aw.cache, "AWCACHE")
    tag(axi.aw.qos, "AWQOS"); tag(axi.aw.prot, "AWPROT")

    tag(axi.w.valid, "WVALID"); tag(axi.w.ready, "WREADY")
    tag(axi.w.data, "WDATA"); tag(axi.w.strb, "WSTRB"); tag(axi.w.last, "WLAST")

    tag(axi.b.valid, "BVALID"); tag(axi.b.ready, "BREADY")
    tag(axi.b.id, "BID"); tag(axi.b.resp, "BRESP")
  }
}
