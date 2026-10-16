package riscq.misc

import spinal.core._

/** GT-sourced global clock buffer (Xilinx primitive) — the usrclk network under buffer bypass. */
case class BUFG_GT() extends BlackBox {
  val I       = in Bool ()
  val O       = out Bool ()
  val CE      = in Bool ()
  val CEMASK  = in Bool ()
  val CLR     = in Bool ()
  val CLRMASK = in Bool ()
  val DIV     = in Bits (3 bits)

  /** All-enabled, divide-by-one wiring — the WR 20-bit usrclk case. */
  def driveDefaults(): this.type = {
    CE := True
    CEMASK := False
    CLR := False
    CLRMASK := False
    DIV := 0
    this
  }
}

/** GT reference-clock input buffer (Xilinx primitive), `MGTREFCLK0` pair → QPLL refclk. */
case class IBUFDS_GTE4() extends BlackBox {
  val I     = in Bool ()
  val IB    = in Bool ()
  val CEB   = in Bool ()
  val O     = out Bool ()
  val ODIV2 = out Bool ()
}
