package riscq.misc

import spinal.core._

/** Global clock buffer (Xilinx primitive). */
case class BUFG() extends BlackBox {
  val I = in Bool ()
  val O = out Bool ()
}
