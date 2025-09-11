package riscq.execute

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin._
import spinal.lib.misc.pipeline._
import riscq.schedule.PipelinePlugin
import riscq.riscv.Riscv
import riscq.riscv.Rvi

class MulPlugin(splitAt : Int,
                partialMulAt : Int,
                add1At : Int,
                add2At : Int,
                formatAt : Int) extends ExecutionUnit {
  during setup new Area {
    val pp = host[PipelinePlugin]
    val ifp = host[IntFormatPlugin]
    val srcp = host[SrcPlugin]
    val ifpRetainer = retains(ifp.elaborationLock)
    val buildBefore = retains(pp.elaborationLock)

    awaitBuild()

    import SrcKeys._

    val wb = newWriteback(ifp, formatAt)
    
    val high, signed1, signed2 = Payload(Bool())
    val result = Payload(SInt(32 bits))

    addUop(Rvi.MUL).srcs(SRC1.RF, SRC2.RF).decode(high -> False)
    addUop(Rvi.MULH).srcs(SRC1.RF, SRC2.RF).decode(high -> True, signed1 -> True, signed2 -> True)
    addUop(Rvi.MULHSU).srcs(SRC1.RF, SRC2.RF).decode(high -> True, signed1 -> True, signed2 -> False)
    addUop(Rvi.MULHU).srcs(SRC1.RF, SRC2.RF).decode(high -> True, signed1 -> False, signed2 -> False)

    ifpRetainer.release()
    uopRetainer.release()

    val high1, low1, high2, low2 = Payload(SInt(17 bits))
    val hh, hl, lh, ll = Payload(SInt(34 bits))
    val s1 = Payload(SInt(66 bits))
    val s2 = Payload(SInt(50 bits))
    val product = Payload(SInt(66 bits))

    new pp.Execute(splitAt) {
      val src1, src2 = Bits(32 bits)
      src1 := srcp.SRC1.asBits
      src2 := srcp.SRC2.asBits
      // Do a single sign-extension
      // Handles both the signed and unsigned case
      high1 := ((signed1 && src1.msb) ## src1(31 downto 16)).asSInt
      // low1 is conceptually UInt, but need SInt here.
      // Need to sign-extend with 0 for correctness
      low1 := (False ## src1(15 downto 0)).asSInt
      high2 := ((signed2 && src2.msb) ## src2(31 downto 16)).asSInt
      low2 := (False ## src2(15 downto 0)).asSInt
    }

    new pp.Execute(partialMulAt) {
      hh := high1 * high2
      hl := high1 * low2
      lh := low1 * high2
      ll := low1 * low2
    }

    new pp.Execute(add1At) {
      s1 := (hh << 32) + (hl << 16)
      s2 := (lh << 16) + ll
    }

    new pp.Execute(add2At) {
      product := s1 + s2
    }

    new pp.Execute(formatAt) {
      wb.valid := SEL
      when (high) {
        wb.payload := product.asBits(63 downto 32)
      } otherwise {
        wb.payload := product.asBits(31 downto 0)
      }
    }

    buildBefore.release()
  }
}
