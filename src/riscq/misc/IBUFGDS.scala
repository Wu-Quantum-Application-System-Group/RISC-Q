package riscq.misc

import spinal.core._

/** Differential-clock input buffer (Xilinx primitive). */
case class IBUFGDS() extends BlackBox {
  val I  = in Bool ()
  val IB = in Bool ()
  val O  = out Bool ()
}
