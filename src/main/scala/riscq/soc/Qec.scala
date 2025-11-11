package riscq.soc

import spinal.core._

object QECMessage {
  val HEADER_IDLE = 0
  val HEADER_RESET = 1
  val HEADER_SYNDROME = 2
  val HEADER_ERROR = 3
  val HEADER_DECODE = 4
  val HEADER_TIME_OFFSET = 5
  val HEADER_TYPE = HardType(UInt(8 bits))
  val DATA_TYPE = HardType(Bits(56 bits))
}

case class QECMessage() extends Bundle {
  val data = QECMessage.DATA_TYPE()
  val header = QECMessage.HEADER_TYPE()
}
