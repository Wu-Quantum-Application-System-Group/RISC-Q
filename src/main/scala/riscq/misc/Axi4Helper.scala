package riscq.misc

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config}

object Axi4StreamVivadoHelper {
  def addStreamInference[T <: Data](s: Stream[T], ifcName: String = "AXIS") = {
    s.payload.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:axis:1.0 " + ifcName + " TDATA")
    s.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:axis:1.0 " + ifcName + " TVALID")
    s.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:axis:1.0 " + ifcName + " TREADY")
  }
}

object Axi4VivadoHelper {
  def Zynq7Axi4Port(idWidth: Int = 4): Axi4 = {
    val axiConfig = Axi4Config(
      addressWidth = 32,
      dataWidth = 32,
      idWidth = 4,
    )

    return Axi4(axiConfig)
  }

  def addInference(axi: Axi4, ifcName: String = "AXI") = {
    axi.aw.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWVALID")
    axi.aw.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWREADY")
    axi.aw.addr.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWADDR")
    Option(axi.aw.id).foreach(_.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWID"))
    axi.aw.region.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWREGION")
    axi.aw.len.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWLEN")
    axi.aw.size.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWSIZE")
    axi.aw.burst.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWBURST")
    axi.aw.lock.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWLOCK")
    axi.aw.cache.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWCACHE")
    axi.aw.qos.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWQOS")
    axi.aw.prot.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWPROT")
    // axi.aw.user.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" AWUSER")
    axi.w.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WVALID")
    axi.w.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WREADY")
    axi.w.data.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WDATA")
    axi.w.strb.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WSTRB")
    axi.w.last.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WLAST")
    Option(axi.w.id).foreach(_.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WID"))
    // axi.w.user.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" WUSER")
    axi.b.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" BVALID")
    axi.b.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" BREADY")
    Option(axi.b.id).foreach(_.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" BID"))
    axi.b.resp.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" BRESP")
    // axi.b.user.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" BUSER")
    axi.ar.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARVALID")
    axi.ar.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARREADY")
    axi.ar.addr.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARADDR")
    Option(axi.ar.id).foreach(_.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARID"))
    axi.ar.region.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARREGION")
    axi.ar.len.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARLEN")
    axi.ar.size.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARSIZE")
    axi.ar.burst.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARBURST")
    axi.ar.lock.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARLOCK")
    axi.ar.cache.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARCACHE")
    axi.ar.qos.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARQOS")
    axi.ar.prot.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARPROT")
    // axi.ar.user.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" ARUSER")
    axi.r.valid.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RVALID")
    axi.r.ready.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RREADY")
    axi.r.data.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RDATA")
    Option(axi.r.id).foreach(_.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RID"))
    axi.r.resp.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RRESP")
    axi.r.last.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RLAST")
    // axi.r.user.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:aximm:1.0 " + ifcName +" RUSER")
  }
}

object Axi4CocotbHelper {
  def setName(axi: Axi4, ifcName: String = "AXI") = {
    axi.aw.valid.setName(ifcName + "_AWVALID")
    axi.aw.ready.setName(ifcName + "_AWREADY")
    axi.aw.addr.setName(ifcName + "_AWADDR")
    axi.aw.id.setName(ifcName + "_AWID")
    axi.aw.region.setName(ifcName + "_AWREGION")
    axi.aw.len.setName(ifcName + "_AWLEN")
    axi.aw.size.setName(ifcName + "_AWSIZE")
    axi.aw.burst.setName(ifcName + "_AWBURST")
    axi.aw.lock.setName(ifcName + "_AWLOCK")
    axi.aw.cache.setName(ifcName + "_AWCACHE")
    axi.aw.qos.setName(ifcName + "_AWQOS")
    axi.aw.prot.setName(ifcName + "_AWPROT")
    axi.w.valid.setName(ifcName + "_WVALID")
    axi.w.ready.setName(ifcName + "_WREADY")
    axi.w.data.setName(ifcName + "_WDATA")
    axi.w.strb.setName(ifcName + "_WSTRB")
    axi.w.last.setName(ifcName + "_WLAST")
    axi.b.valid.setName(ifcName + "_BVALID")
    axi.b.ready.setName(ifcName + "_BREADY")
    axi.b.id.setName(ifcName + "_BID")
    axi.b.resp.setName(ifcName + "_BRESP")
    axi.ar.valid.setName(ifcName + "_ARVALID")
    axi.ar.ready.setName(ifcName + "_ARREADY")
    axi.ar.addr.setName(ifcName + "_ARADDR")
    axi.ar.id.setName(ifcName + "_ARID")
    axi.ar.region.setName(ifcName + "_ARREGION")
    axi.ar.len.setName(ifcName + "_ARLEN")
    axi.ar.size.setName(ifcName + "_ARSIZE")
    axi.ar.burst.setName(ifcName + "_ARBURST")
    axi.ar.lock.setName(ifcName + "_ARLOCK")
    axi.ar.cache.setName(ifcName + "_ARCACHE")
    axi.ar.qos.setName(ifcName + "_ARQOS")
    axi.ar.prot.setName(ifcName + "_ARPROT")
    axi.r.valid.setName(ifcName + "_RVALID")
    axi.r.ready.setName(ifcName + "_RREADY")
    axi.r.data.setName(ifcName + "_RDATA")
    axi.r.id.setName(ifcName + "_RID")
    axi.r.resp.setName(ifcName + "_RRESP")
    axi.r.last.setName(ifcName + "_RLAST")
  }
}