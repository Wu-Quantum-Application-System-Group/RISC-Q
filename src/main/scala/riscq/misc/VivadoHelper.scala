package riscq.misc

import spinal.core._
import spinal.lib._

case class IBUFDS_GTE4() extends BlackBox {
  val I = in Bool()
  val IB = in Bool()
  val CEB = in Bool()
  val O = out Bool()
  val ODIV2 = out Bool()
}
// IBUFDS_GTE4 #(
//     .REFCLK_EN_TX_PATH  (1'b0),
//     .REFCLK_HROW_CK_SEL (2'b00),
//     .REFCLK_ICNTL_RX    (2'b00)
//   ) IBUFDS_GTE4_MGTREFCLK0_X0Y1_INST (
//     .I     (mgtrefclk0_x0y1_p),
//     .IB    (mgtrefclk0_x0y1_n),
//     .CEB   (1'b0),
//     .O     (mgtrefclk0_x0y1_int),
//     .ODIV2 ()
//   );

case class BUFG() extends BlackBox {
  val I = in Bool()
  val O = out Bool()
}

case class IBUFGDS() extends BlackBox {
  val I = in Bool()
  val IB = in Bool()
  val O = out Bool()
}

case class IbufgdsTestTop() extends Component {
  val eclk = IBUFGDS()
  val eclkI = in Bool()
  val eclkIb = in Bool()
  eclk.I := eclkI
  eclk.IB := eclkIb
  
  val cd = ClockDomain.current
  cd.clock := eclk.O

  val oreg = out port Reg(Bool())
  oreg := ~oreg
}

case class NoClkModule() extends Component {
  val i = in Bool()
  val o = out Bool()
  o := ~i
}

case class ClockInterface() extends Component {
  val dspClk_clk_p = in Bool()
  val dspClk_clk_n = in Bool()
  dspClk_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 dspClk_diff CLK_P ")
  dspClk_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 dspClk_diff CLK_N ")

  val hostClk_clk_p = in Bool()
  val hostClk_clk_n = in Bool()
  hostClk_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 hostClk_diff CLK_P ")
  hostClk_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 hostClk_diff CLK_N ")

  val user_sysref_clk_p = in Bool()
  val user_sysref_clk_n = in Bool()
  user_sysref_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 user_sysref_diff CLK_P ")
  user_sysref_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 user_sysref_diff CLK_N ")

  val dspClk = out Bool()
  dspClk.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 dspClk CLK")
  val hostClk = out Bool()
  hostClk.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 hostClk CLK")
  val user_sysref = out Bool()
  user_sysref.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 user_sysref CLK")

  val ibufgds500m = IBUFGDS()
  val ibufgds100m = IBUFGDS()
  val ibufgdsUserSysref = IBUFGDS()

  ibufgds500m.I := dspClk_clk_p
  ibufgds500m.IB := dspClk_clk_n
  dspClk := ibufgds500m.O
  ibufgds100m.I := hostClk_clk_p
  ibufgds100m.IB := hostClk_clk_n
  hostClk := ibufgds100m.O
  ibufgdsUserSysref.I := user_sysref_clk_p
  ibufgdsUserSysref.IB := user_sysref_clk_n
  user_sysref := ibufgdsUserSysref.O
}

object ClkIfcTest extends App {
  SpinalVerilog(ClockInterface())
}

object IbufgdsTest extends App {
  SpinalVerilog(IbufgdsTestTop())
}

object VivadoClkHelper extends App {
  def addInference(clk: Bool, rst: Bool, freq: Long) = {
    rst.addAttribute("X_INTERFACE_INFO", s"xilinx.com:signal:reset:1.0 ${rst.getDisplayName()} rst")
    rst.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
    clk.addAttribute("X_INTERFACE_INFO", s"xilinx.com:signal:clock:1.0 ${clk.getDisplayName()} clk")
    clk.addAttribute("X_INTERFACE_PARAMETERS", s"FREQ_HZ ${freq}, ASSOCIATED_RESET ${rst.getDisplayName()}")
  }
}