package riscq.memory

import spinal.core._
import spinal.lib._

/**
 * A 32-bit-facing UltraRAM that doubles the usable depth of a [[Uram]] by packing **two 32-bit
 * words into each 64-bit UltraRAM row**. A plain `Uram(Bits(32 bits), …)` stores one 32-bit word
 * per row and wastes the rest of the primitive's width; `HalfUram` instead backs the array with a
 * 64-bit `Uram` and presents a 32-bit port with twice the depth, so a given array costs half as
 * many URAM primitives.
 *
 * Same two-`MemReadWritePort` shape as [[Uram]], but each port is 32-bit with `addressWidth`-wide
 * addresses; the backing URAM is one bit narrower in address (`addressWidth - 1`, i.e. half the
 * depth) and 64-bit wide. The low address bit selects the half of the row:
 *   - `address(0) == 0` → low 32 bits `[31:0]`, `address(0) == 1` → high 32 bits `[63:32]`.
 *
 * '''Writes need no read-modify-write:''' the 4-bit outer byte mask is placed into the addressed
 * half of the 8-bit inner mask (zeros in the other half), and the 32-bit `wdata` is replicated to
 * both halves — so only the addressed half's bytes are written.
 *
 * '''Reads''' mux the correct half out of the 64-bit row; the select bit is delayed by the row's
 * read latency (`pipeNum + 2` register stages) so it lines up with `rdata`. Outer read latency
 * therefore equals [[Uram]]'s — `pipeNum + 2` cycles address→data — the mux is combinational.
 *
 * @param addressWidth address width of the 32-bit words (depth = `1 << addressWidth`).
 * @param pipeNum      backing UltraRAM output pipeline depth (`NBPIPE`), passed to [[Uram]].
 */
case class HalfUram(addressWidth: Int, pipeNum: Int = 3) extends Component {
  assert(addressWidth >= 1, "addressWidth must be >= 1 (a 64-bit row holds two 32-bit words)")

  val uram = Uram(Bits(64 bits), addressWidth - 1, pipeNum) // half the depth, 64-bit rows

  val io = new Bundle {
    val port0 = slave port MemReadWritePort(Bits(32 bits), addressWidth, maskWidth = 4)
    val port1 = slave port MemReadWritePort(Bits(32 bits), addressWidth, maskWidth = 4)
  }

  // Map one 32-bit outer port onto a 64-bit inner Uram port: address >> 1 picks the row,
  // address(0) picks the half.
  def adapt(outer: MemReadWritePort[Bits], inner: MemReadWritePort[Bits]): Unit = {
    val sel = outer.address(0) // 0 = low 32 bits, 1 = high 32 bits

    inner.address := (outer.address >> 1).resized
    inner.enable  := outer.enable
    inner.write   := outer.write
    inner.wdata   := outer.wdata ## outer.wdata                              // both halves; mask picks one
    inner.mask    := sel ? (outer.mask ## B(0, 4 bits)) | (B(0, 4 bits) ## outer.mask)

    // Delay the half-select to the row's read latency so it lines up with the returned rdata.
    val selRead = Delay(sel, pipeNum + 2)
    outer.rdata := selRead ? inner.rdata(63 downto 32) | inner.rdata(31 downto 0)
  }

  adapt(io.port0, uram.io.port0)
  adapt(io.port1, uram.io.port1)
}

/** Emit `HalfUram.v` (32-bit × 256, backed by a 64-bit × 128 URAM). */
object HalfUramGen extends App {
  SpinalConfig(netlistFileName = "HalfUram.v", romReuse = true)
    .generateVerilog(HalfUram(8))
}
