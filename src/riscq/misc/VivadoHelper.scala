package riscq.misc

import spinal.core._
import spinal.lib._

/**
 * Vivado IP-packaging glue, ported from the RISC-Q reference (`riscq.misc.VivadoHelper`). None of this
 * is hardware the design *computes* with: it is the clock-buffer blackboxes and the `X_INTERFACE_INFO`
 * attributes that let Vivado's IP packager / block-design recognise the SoC's AXI / AXI-Stream / clock
 * ports as bus interfaces. Adding the attributes is sim-neutral (they are pure Verilog attributes), so
 * they are gated behind `PulseTableSoc(vivado = true)` only to keep the OOC `vivado-scripts/riscvsoc` flow
 * (which constrains `[get_ports clk]`) on the un-renamed default.
 *
 * The clock-buffer primitives [[IBUFGDS]] and [[BUFG]] live alongside in `IBUFGDS.scala` / `BUFG.scala`.
 */

/**
 * Differential→single-ended clock fan-in for the ZCU216 build: the board drives three LVDS clock pairs
 * (the 500 MHz `dspClk`, the 100 MHz `hostClk`, and `user_sysref`); this packages each through an
 * `IBUFGDS` and exposes the single-ended clock, carrying the `diff_clock` / `clock` interface attributes
 * so the block design auto-bundles the `*_clk_p` / `*_clk_n` port pairs. Mirrors the RISC-Q `ClockInterface`.
 */
case class ClockInterface() extends Component {
  val dspClk_clk_p = in Bool ()
  val dspClk_clk_n = in Bool ()
  dspClk_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 dspClk_diff CLK_P ")
  dspClk_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 dspClk_diff CLK_N ")

  val hostClk_clk_p = in Bool ()
  val hostClk_clk_n = in Bool ()
  hostClk_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 hostClk_diff CLK_P ")
  hostClk_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 hostClk_diff CLK_N ")

  val user_sysref_clk_p = in Bool ()
  val user_sysref_clk_n = in Bool ()
  user_sysref_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 user_sysref_diff CLK_P ")
  user_sysref_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 user_sysref_diff CLK_N ")

  val dspClk = out Bool ()
  dspClk.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 dspClk CLK")
  val hostClk = out Bool ()
  hostClk.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 hostClk CLK")
  val user_sysref = out Bool ()
  user_sysref.addAttribute("X_INTERFACE_INFO", "xilinx.com:signal:clock:1.0 user_sysref CLK")

  // 500 MHz dsp clock: IBUFGDS + a BUFG (the DSP datapath fans out widely, so it earns a global buffer).
  val ibufgds500m = IBUFGDS()
  val bufg500m    = BUFG()
  ibufgds500m.I  := dspClk_clk_p
  ibufgds500m.IB := dspClk_clk_n
  bufg500m.I     := ibufgds500m.O
  dspClk         := bufg500m.O

  // 100 MHz host clock + user_sysref: plain input buffers.
  val ibufgds100m = IBUFGDS()
  ibufgds100m.I  := hostClk_clk_p
  ibufgds100m.IB := hostClk_clk_n
  hostClk        := ibufgds100m.O

  val ibufgdsUserSysref = IBUFGDS()
  ibufgdsUserSysref.I  := user_sysref_clk_p
  ibufgdsUserSysref.IB := user_sysref_clk_n
  user_sysref          := ibufgdsUserSysref.O
}

object ClockInterfaceGen extends App {
  SpinalVerilog(ClockInterface())
}

/** Tags a clock/reset pair with the Vivado `clock`/`reset` interface + `FREQ_HZ`/`POLARITY` parameters. */
object VivadoClkHelper {
  def addInference(clk: Bool, rst: Bool, freq: Long): Unit = {
    rst.addAttribute("X_INTERFACE_INFO", s"xilinx.com:signal:reset:1.0 ${rst.getDisplayName()} rst")
    rst.addAttribute("X_INTERFACE_PARAMETERS", "POLARITY ACTIVE_HIGH")
    clk.addAttribute("X_INTERFACE_INFO", s"xilinx.com:signal:clock:1.0 ${clk.getDisplayName()} clk")
    clk.addAttribute("X_INTERFACE_PARAMETERS", s"FREQ_HZ ${freq}, ASSOCIATED_RESET ${rst.getDisplayName()}")
  }
}
