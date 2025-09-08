package riscq.network

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.Axi4
import spinal.lib.bus.amba4.axi.Axi4Config
import spinal.lib.bus.amba4.axi.Axi4ToTilelinkFiber
import spinal.lib.bus.tilelink.fabric.Node
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber

case class GtwizardIo() extends Bundle {
  val gtyrxp_in = in Bool ()
  val gtyrxn_in = in Bool ()
  val gtytxp_out = out Bool ()
  val gtytxn_out = out Bool ()
  val gtwiz_userclk_tx_reset_in = in Bool ()
  val gtwiz_userclk_tx_srcclk_out = out Bool ()
  val gtwiz_userclk_tx_usrclk_out = out Bool ()
  val gtwiz_userclk_tx_usrclk2_out = out Bool ()
  val gtwiz_userclk_tx_active_out = out Bool ()
  val gtwiz_userclk_rx_reset_in = in Bool ()
  val gtwiz_userclk_rx_srcclk_out = out Bool ()
  val gtwiz_userclk_rx_usrclk_out = out Bool ()
  val gtwiz_userclk_rx_usrclk2_out = out Bool ()
  val gtwiz_userclk_rx_active_out = out Bool ()
  val gtwiz_reset_clk_freerun_in = in Bool ()
  val gtwiz_reset_all_in = in Bool ()
  val gtwiz_reset_tx_pll_and_datapath_in = in Bool ()
  val gtwiz_reset_tx_datapath_in = in Bool ()
  val gtwiz_reset_rx_pll_and_datapath_in = in Bool ()
  val gtwiz_reset_rx_datapath_in = in Bool ()
  val gtwiz_reset_rx_cdr_stable_out = out Bool ()
  val gtwiz_reset_tx_done_out = out Bool ()
  val gtwiz_reset_rx_done_out = out Bool ()
  val gtwiz_userdata_tx_in = in Bits (64 bits)
  val gtwiz_userdata_rx_out = out Bits (64 bits)
  val gtrefclk00_in = in Bool ()
  val qpll0outclk_out = out Bool ()
  val qpll0outrefclk_out = out Bool ()
  val rxgearboxslip_in = in Bool ()
  val rxpolarity_in = in Bool ()
  val txheader_in = in Bits (6 bits)
  val txsequence_in = in Bits (7 bits)
  val gtpowergood_out = out Bool ()
  val rxdatavalid_out = out Bits (2 bits)
  val rxheader_out = out Bits (6 bits)
  val rxheadervalid_out = out Bits (2 bits)
  val rxpmaresetdone_out = out Bool ()
  val rxresetdone_out = out Bool ()
  val rxstartofseq_out = out Bits (2 bits)
  val txpmaresetdone_out = out Bool ()
  val txresetdone_out = out Bool ()
}

case class gtwizard_ultrascale(id: Int) extends BlackBox {
  setDefinitionName(s"gtwizard_ultrascale_${id}")
  val io = GtwizardIo()
  noIoPrefix()
}

case class GtwizardWrapper(id: Int) extends Component {
  val io = GtwizardIo()
  noIoPrefix()
  val gtwizard = new gtwizard_ultrascale(id)
  io <> gtwizard.io
}

object GenGtwizard extends App {
   SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl",
    romReuse = true
  ).generate(
    GtwizardWrapper(2)
  )
}

object Gen2Gtwizard extends App {
   SpinalConfig(
    mode = Verilog,
    romReuse = true
  ).generate(
    new Component{
      val io = new Bundle {
        val gt0 = GtwizardIo()
        val gt2 = GtwizardIo()
      }
      val gtwizard0 = new gtwizard_ultrascale(0)
      val gtwizard2 = new gtwizard_ultrascale(2)
      io.gt0 <> gtwizard0.io
      io.gt2 <> gtwizard2.io
    }
  )
}