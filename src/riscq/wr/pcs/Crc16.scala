package riscq.wr.pcs

import spinal.core._

/**
 * CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflection) over the frame's TYPE..PAYLOAD
 * bytes (spec 02 §4). `step` is the byte-serial LFSR advance as pure combinational logic — the
 * PCS applies it up to twice per cycle (2 bytes/cycle datapath). TX appends the CRC high byte
 * first, so the RX check is `step`ped over TYPE..CRC and compared against the zero residue.
 */
object Crc16 {
  val poly = 0x1021
  val init = 0xffff

  /** One byte of CRC advance, combinational (unrolled 8-step LFSR). */
  def step(crc: Bits, byte: Bits): Bits = {
    var c = crc ^ (byte ## B(0, 8 bits))
    for (_ <- 0 until 8) {
      val shifted = c(14 downto 0) ## False
      c = c(15) ? (shifted ^ B(poly, 16 bits)) | shifted
    }
    c
  }

  /** Scala mirror of [[step]] for goldens and elaboration-time use. */
  def stepModel(crc: Int, byte: Int): Int = {
    var c = (crc ^ (byte << 8)) & 0xffff
    for (_ <- 0 until 8) c = if ((c & 0x8000) != 0) ((c << 1) ^ poly) & 0xffff else (c << 1) & 0xffff
    c
  }

  /** CRC over a whole message, Scala side. */
  def model(bytes: Seq[Int]): Int = bytes.foldLeft(init)(stepModel)
}
