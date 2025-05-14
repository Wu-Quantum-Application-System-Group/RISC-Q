package riscq.scratch

import spinal.core._
import spinal.lib._
import spinal.lib.eda.bench.Rtl
import spinal.lib.eda.bench.Bench
import riscq.misc.XilinxRfsocTarget
import riscq.memory.DualClockRam
import riscq.memory.DualClockRamTest
import riscq.pulse.TimedFifo
import riscq.pulse.PulseGenerator

object ManyRegs extends App {
  case class ManyRegs(n: Int) extends Component {
    val i = in Bits(n bits)
    val o = out Bits(n bits)
    
    val regs = RegNext(i)
    o := regs
  }

  val rtl = Rtl(SpinalVerilog(
    ManyRegs(100)
    ))
  Bench(List(rtl), XilinxRfsocTarget(), "./bench/")
}

object ManyOrs extends App {
  case class ManyOrs(n: Int) extends Component {
    val i = in Bits(n bits)
    val o = out Bool()
    
    o := i.orR
  }

  val rtl = Rtl(SpinalVerilog(
    ManyOrs(100)
    ))
  Bench(List(rtl), XilinxRfsocTarget(), "./bench/")

}

object LongCombPath extends App {
  case class LongCombPath(n: Int) extends Component {
    val width = 32
    val i = in UInt(width bits)
    val o = out UInt(width bits)

    val iBuf = RegNext(i)
    KeepAttribute(iBuf)

    val a = Vec.fill(n)(cloneOf(i))
    a(0) := iBuf
    a(1) := iBuf
    for(i <- 2 until n) {
      a(i) := a(i - 1) + a(i - 2)
    }

    val oBuf = RegNext(a.last)
    KeepAttribute(oBuf)
    o := oBuf
  }

  val rtl = Rtl(SpinalVerilog(
    LongCombPath(100)
    ))
  Bench(List(rtl), XilinxRfsocTarget(), "./bench/")
}

object BramInfer extends App {
  case class BramInfer(width: Int, depth: Int) extends Component {
    val mem = Mem.fill(depth)(Bits(width bits))
    mem.addAttribute("ram_style", "block")
    // mem.addAttribute("ram_style", "distributed")

    val port = slave port mem.readWriteSyncPort()

    val combLogic = !port.rdata(0)
    val o = RegNext(combLogic)
    out(o)
  }

  case class BramInferWithOutputBuffer(width: Int, depth: Int) extends Component {
    val mem = Mem.fill(depth)(Bits(width bits))
    mem.addAttribute("ram_style", "block")
    // mem.addAttribute("ram_style", "distributed")

    val port = mem.readWriteSyncPort()

    val bufOut = slave port cloneOf(port)
    val dataBuf = RegNext(port.rdata)
    bufOut.rdata := dataBuf
    bufOut.assignUnassignedByName(port)
    port.assignUnassignedByName(bufOut)

    val combLogic = !bufOut.rdata(0)
    val o = RegNext(combLogic)
    out(o)
  }

  val rtl1 = Rtl(SpinalVerilog(
    BramInfer(18, 1024)
    ))
  val rtl2 = Rtl(SpinalVerilog(
    BramInferWithOutputBuffer(18, 1024)
    ))
  Bench(List(rtl1, rtl2), XilinxRfsocTarget(1000 MHz), "./bench/")
}

object DspInfer extends App {
  case class MultInfer(wa: Int, wb: Int) extends Component {
    val a = in SInt(wa bits)
    val b = in SInt(wb bits)

    val c = a * b
    out(c)
  }

  case class MultAndAddInfer(wa: Int, wb: Int, wc: Int) extends Component {
    val a = in SInt(wa bits)
    val b = in SInt(wb bits)
    val c = in SInt(wc bits)

    val mReg = RegNext(a * b)
    val cc = RegNext(c)
    val pReg = RegNext(mReg +^ cc)
    out(pReg)
  }

  case class MultAndAddInferNoBuffer(wa: Int, wb: Int, wc: Int) extends Component {
    val a = in SInt(wa bits)
    val b = in SInt(wb bits)
    val c = in SInt(wc bits)

    val mReg = a * b
    val pReg = mReg +^ c
    out(pReg)
  }

  val rtl1 = Rtl(SpinalVerilog(
    MultInfer(27, 18)
    ))
  val rtl2 = Rtl(SpinalVerilog(
    MultAndAddInfer(27, 18, 47)
    ))
  val rtl3 = Rtl(SpinalVerilog(
    MultAndAddInferNoBuffer(27, 18, 47)
    ))
  Bench(List(rtl1, rtl2, rtl3), XilinxRfsocTarget(1000 MHz), "./bench/")
}

case class Cmult(width: Int) extends Component {
  val ar = in SInt (width bits)
  val br = in SInt (width bits)
  val ai = in SInt (width bits)
  val bi = in SInt (width bits)
  val ar_d, ar_dd, ar_ddd, ar_dddd = Reg(SInt(width bits))
  val br_d, br_dd, br_ddd, br_dddd = Reg(SInt(width bits))
  val ai_d, ai_dd, ai_ddd, ai_dddd = Reg(SInt(width bits))
  val bi_d, bi_dd, bi_ddd, bi_dddd = Reg(SInt(width bits))

  val pr = out SInt (width + width + 1 bits)
  val pi = out SInt (width + width + 1 bits)

  val addcommon, addr, addi = Reg(SInt(width + 1 bits))
  val mult0, multr, multi, pr_int, pi_int = Reg(SInt(width + width + 1 bits))
  val common, commonr1, commonr2 = Reg(SInt(width + width + 1 bits))

  ar_d := ar
  ar_dd := ar_d
  ar_ddd := ar_dd
  ar_dddd := ar_ddd
  br_d := br
  br_dd := br_d
  br_ddd := br_dd
  br_dddd := br_ddd
  ai_d := ai
  ai_dd := ai_d
  ai_ddd := ai_dd
  ai_dddd := ai_ddd
  bi_d := bi
  bi_dd := bi_d
  bi_ddd := bi_dd
  bi_dddd := bi_ddd

  addcommon := ar_d -^ ai_d
  mult0 := addcommon * bi_dd
  common := mult0

  addr := br_ddd -^ bi_ddd
  multr := addr * ar_dddd
  commonr1 := common
  pr_int := multr + commonr1

  addi := br_ddd +^ bi_ddd
  multi := addi * ai_dddd
  commonr2 := common
  pi_int := multi + commonr2

  pr := pr_int
  pi := pi_int
}

object BenchComplexMul extends App {
  import riscq.pulse._

  val rtl = Rtl(SpinalVerilog(ComplexMul(16)))
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./build/")
}

object BenchTableAndDsp extends App {
  import riscq.pulse._

  val rtl = Rtl(SpinalSystemVerilog(
    TableAndDsp(16)
  ))
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./bench/")
}

// object BenchCarrierGenerator extends App {
//   import riscq.pulse._

//   val param = CarrierGeneratorSpec(
//     batchSize = 4,
//     carrierWidth = 16,
//     freqWidth = 16,
//     clockWidth = 32
//   )
//   val rtl = Rtl(SpinalVerilog(
//     CarrierGenerator(param)
//   ))
//   Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./build/")
// }

// object BenchPulseGenerator extends App {
//   import riscq.pulse._

//   val dataWidth = 16
//   val addrWidth = 12
//   val phaseWidth = 16
//   val batchSize = 16
//   val pgSpec = PulseGeneratorSpec(
//     dataWidth = dataWidth,
//     batchSize = batchSize,
//     bufferDepth = 1 << addrWidth,
//     clockWidth = 32,
//     phaseWidth = phaseWidth,
//     freqWidth = phaseWidth,
//     ampWidth = 16,
//   )
//   val rtl = Rtl(SpinalVerilog(
//     PulseGeneratorWithCarrierInput(pgSpec)
//   ))
//   Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./build/")
// }

object BenchCordic extends App {
  import riscq.pulse._
  val rtl = Rtl(SpinalVerilog(
    Cordic(xyWidth = 16, zWidth = 16, correctGain = true)
  ))
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./bench/")
}

object BramOutRegInfer extends App {
  val rtl = Rtl(SpinalVerilog{ new Component{
    val cd = ClockDomain.current
    val bram = DualClockRamTest(32, 1024, cd, cd, true, true)
    val slowPort = slave port cloneOf(bram.slowPort)
    slowPort <> bram.slowPort
  }
  })
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./bench/")
}

object BenchTimedQueue extends App {
  val rtl= Rtl(SpinalVerilog(
    TimedFifo(Bits(32 bit), 2, 32)
  ))
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./bench/")
}

object BenchPulseGenerator extends App {
  val batchSize = 16
  val dataWidth = 16
  val addrWidth = 12
  val timeWidth = 32
  val rtl= Rtl(SpinalVerilog(
    PulseGenerator(batchSize, dataWidth, addrWidth, timeWidth, fifoDepth = 4, fifoNum = 2)
  ))
  Bench(List(rtl), XilinxRfsocTarget(1000 MHz), "./bench/")
}