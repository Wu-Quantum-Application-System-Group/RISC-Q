package riscq.pulse

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._
import spinal.lib.misc.pipeline._
// import riscq.pulse.PGTest.batchSize

import scala.math
import spinal.lib.eda.bench.Rtl
import riscq.misc.XilinxRfsocTarget
import spinal.lib.eda.bench.Bench
import riscq.pulse.GenPG.batchSize

case class ReadoutResult(width: Int) extends Bundle {
  val r = SInt(width bits)
  val i = SInt(width bits)
}

case class AddTree(batchSize: Int, inWidth: Int, outWidth: Int) extends Component {
  val input = in port Vec.fill(batchSize)(SInt(inWidth bit))
  val sum = out port SInt(outWidth bit)

  val log2BatchSize = log2Up(batchSize)
  val bufferSize = (1 << (log2BatchSize + 1)) - 1
  val buffer = List.fill(bufferSize)(Reg(SInt(outWidth bit)))
  val firstInputIdx = bufferSize / 2
  for(i <- 0 until batchSize) {
    buffer(firstInputIdx + i) := input(i).resized
  }
  for(i <- 0 until firstInputIdx) {
    if(i * 2 + 2 < firstInputIdx + batchSize) {
      buffer(i) := buffer(i * 2 + 1) + buffer(i * 2 + 2)
    } else if (i * 2 + 1 < firstInputIdx + batchSize) {
      buffer(i) := buffer(i * 2 + 1)
    } else {
      buffer(i) := 0
    }
  }

  sum := buffer(0)
}

object TestAddTree extends App {
  val batchSize = 5
  SimConfig.compile{
    val dut = AddTree(batchSize = batchSize, inWidth = 16, outWidth = 27)
    dut
  }.doSim{dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)

    for(i <- 0 until batchSize){
      dut.input(i) #= 1
    }

    for(i <- 0 until 10){
      println(s"${dut.sum.toBigInt}")
      cd.waitRisingEdge()
    }
  }
}

case class ReadoutAccumulator(batchSize: Int, inWidth: Int, outWidth: Int, timeWidth: Int) extends Component {
  val io = new Bundle {
    // val en = in Bool()
    // val valid = out Bool()
    val adc = in port ComplexBatch(batchSize, inWidth)
    val carrier = in port ComplexBatch(batchSize, inWidth)
    // val accR = out port SInt(outWidth bits)
    // val accI = out port SInt(outWidth bits)
    val cmd = slave port Flow(UInt(timeWidth bits))
    val rsp = master port Flow(ReadoutResult(outWidth))
    val demodData = master port Flow(Vec.fill(batchSize)(Complex(inWidth)))
  }

  val compMulDelay = 3
  val timer = Reg(UInt(timeWidth bits)) init 0
  // val accBufferR = (for(i <- 0 until batchSize) yield List.fill(batchSize - i)(Reg(SInt(outWidth bits)))).toList
  // val accBufferI = (for(i <- 0 until batchSize) yield List.fill(batchSize - i)(Reg(SInt(outWidth bits)))).toList
  val result = Reg(ReadoutResult(outWidth))
  when(io.cmd.fire){
    timer := io.cmd.payload - 1
    result.r := S(0)
    result.i := S(0)
  }

  val timerGtZero = timer > 0
  when(timerGtZero) {
    timer := timer - 1
  }

  val accBuffer = (for(i <- 0 until batchSize) yield List.fill(batchSize - i)(Reg(ReadoutResult(outWidth)))).toList
  // val accValid = List.fill(batchSize + compMulDelay - 1)(Reg(Bool()) init False)
  // accValid(0) := timer > 0
  // (accValid, accValid.tail).zipped.foreach{(i, o) => o := i}

  // val busy = Reg(Bool()) init False
  // busy := timerGtZero
  val busy = Reg(Bool()) clearWhen(!timerGtZero) setWhen(io.cmd.valid)
  busy.addAttribute("MAX_FANOUT", 16)

  io.rsp.valid := !busy
  when(busy){
    result.r := result.r + accBuffer.last(0).r
    result.i := result.i + accBuffer.last(0).i
  }

  io.rsp.payload := result
  // io.rsp.valid := timer === 0
  // when(timer =/= 0){
  //   timer := timer - 1
  //   result.r := result.r + accBuffer.last(0).r
  //   result.i := result.i + accBuffer.last(0).i
  // }

  val compMul = List.fill(batchSize)(ComplexMul(inWidth))
  for(i <- 0 until batchSize){
    compMul(i).io.c0 := io.adc(i)
    compMul(i).io.c1 := io.carrier(i)
    accBuffer(0)(i).r := compMul(i).io.c.r.asSInt.resized
    accBuffer(0)(i).i := compMul(i).io.c.i.asSInt.resized
  }
  for(i <- 1 until batchSize){
    accBuffer(i)(0).r := accBuffer(i - 1)(0).r + accBuffer(i - 1)(1).r
    accBuffer(i)(0).i := accBuffer(i - 1)(0).i + accBuffer(i - 1)(1).i
    for(j <- 1 until batchSize - i){
      accBuffer(i)(j).r := accBuffer(i - 1)(j + 1).r
      accBuffer(i)(j).i := accBuffer(i - 1)(j + 1).i
    }
  }

  for((cm, dm) <- (compMul zip io.demodData.payload)) {
    dm := cm.io.c
  }
  io.demodData.valid := busy
}

object ReadoutAccumulatorTest extends App {
  val batchSize = 4
  SimConfig.compile{
    val dut = ReadoutAccumulator(batchSize, 16, 32, 32)
    dut.compMul.map{_.io.c.simPublic}
    dut.accBuffer.map{_.map{_.simPublic}}
    dut.timer.simPublic
    dut.result.simPublic
    dut
  }.doSimUntilVoid{ dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    
    val input = fork {
      var time = 0
      val freq = 1 / 16.0
      val phaseAdc = 0
      while(true) {
        for(i <- 0 until batchSize) {
          val cr = math.cos((time + i) * freq)
          val ci = math.sin((time + i) * freq)
          val ar = math.cos((time + i) * freq + phaseAdc)
          val ai = -math.sin((time + i) * freq + phaseAdc)

          dut.io.carrier(i).r #= cr
          dut.io.carrier(i).i #= ci
          dut.io.adc(i).r #= ar
          dut.io.adc(i).i #= ai
        }
        time += batchSize
        cd.waitSampling()
      }
    }

    dut.io.cmd.valid #= false
    cd.waitSampling(100)
    dut.io.cmd.valid #= true
    dut.io.cmd.payload #= 4
    cd.waitSampling()
    dut.io.cmd.valid #= false
    for(i <- 0 until 30){
      println(s"${dut.io.rsp.valid.toBoolean}, ${dut.io.rsp.r.toBigInt}, ${dut.io.rsp.i.toBigInt}")
      println(s"compMul: ${dut.compMul.map(_.io.c.r.toDouble)}")
      println(s"accR: ${dut.accBuffer.last.map(_.r.toBigInt)}")
      println(s"timer: ${dut.timer.toBigInt}")
      // println(s"result: ${dut.io.rsp.payload.r.toBigInt},${dut.io.rsp.payload.i.toBigInt}")
      println(s"result: ${dut.result.r.toBigInt},${dut.result.i.toBigInt}")
      println("")
      cd.waitSampling()
    }
    simSuccess()
    
  }
}

case class ReadoutComparator(refWidth: Int, readoutWidth: Int) extends Component {
  val io = new Bundle {
    val refR = in port SInt(refWidth bit)
    val refI = in port SInt(refWidth bit)
    val readoutR = in port SInt(readoutWidth bit)
    val readoutI = in port SInt(readoutWidth bit)
    val res = out port Bool() // = 1 iff refR * readoutR + refI * readoutI < 0
  }

  val input, mult, add, output = Node()
  val REFR, REFI = Payload(SInt(refWidth bit))
  val READOUTR, READOUTI = Payload(SInt(readoutWidth bit))
  val MULTR, MULTI, SUM = Payload(SInt(refWidth + readoutWidth bit))

  val inLogic = new input.Area {
    REFR := io.refR
    REFI := io.refI
    READOUTR := io.readoutR
    READOUTI := io.readoutI
  }

  val multLogic = new mult.Area {
    MULTR := REFR * READOUTR
    MULTI := REFI * READOUTI
  }

  val addLogic = new add.Area {
    SUM := MULTR + MULTI
  }

  val outputLogic = new output.Area {
    io.res := SUM.sign
  }

  val connectors = List(StageLink(input, mult), StageLink(mult, add), StageLink(add, output))
  Builder(connectors)
}


object BenchReadoutComparator extends App {
  val rtl = Rtl(SpinalVerilog(
    ReadoutComparator(16, 27)
  ))
  Bench(List(rtl), XilinxRfsocTarget(), "./bench/")
}

case class ReadoutDecoder(batchSize: Int, inWidth: Int, accWidth: Int, timeWidth: Int) extends Component {
  val io = new Bundle {
    val adc = in port ComplexBatch(batchSize, inWidth)
    val carrier = in port ComplexBatch(batchSize, inWidth)
    val cmd = slave port Flow(UInt(timeWidth bits))
    val refR = slave port Flow(SInt(inWidth bit))
    val refI = slave port Flow(SInt(inWidth bit))
    val res = master port Flow(Bool())
    val demodData = master port Flow(Vec.fill(batchSize)(Complex(inWidth)))
  }
  val resValid = Reg(Bool()) init False

  val timer = Reg(UInt(timeWidth bit))
  val compMul = List.fill(batchSize)(ComplexMul(inWidth))
  (io.demodData.payload zip compMul).foreach{case (dataOut, dataIn) => 
    dataOut := dataIn.io.c
  }
  io.demodData.valid := False

  val addTreeR = AddTree(batchSize, inWidth, accWidth)
  val addTreeI = AddTree(batchSize, inWidth, accWidth)
  for(i <- 0 until batchSize){
    compMul(i).io.c0 := io.adc(i)
    compMul(i).io.c1 := io.carrier(i)
    addTreeR.input(i) := compMul(i).io.c.r.asSInt.resized
    addTreeI.input(i) := compMul(i).io.c.i.asSInt.resized
  }
  val sumR = Reg(SInt(accWidth bit))
  val sumI = Reg(SInt(accWidth bit))
  when(io.cmd.fire){
    sumR := S(0)
    sumI := S(0)
  }

  val refR = RegNextWhen(io.refR.payload, io.refR.valid)
  val refI = RegNextWhen(io.refI.payload, io.refI.valid)
  val cmp = ReadoutComparator(refWidth = inWidth, readoutWidth = accWidth)
  cmp.io.readoutI := sumI
  cmp.io.readoutR := sumR
  cmp.io.refI := refI
  cmp.io.refR := refR
  io.res.payload := cmp.io.res
  io.res.valid := resValid

  val fsm = new StateMachine {
    val idle = makeInstantEntry()
    idle.whenIsActive {
      when(io.cmd.fire) {
        timer := io.cmd.payload
        resValid := False
        goto(waitSampling)
      }
    }
    val waitSampling = new State {
      whenIsActive {
        timer := timer - 1
        sumR := sumR + addTreeR.sum
        sumI := sumI + addTreeI.sum
        io.demodData.valid := True
        when(timer === 0) {
          goto(waitCmp)
        }
      }
    }
    val waitCmp = new StateDelay(cyclesCount = 3) {
      whenCompleted{
        resValid := True
        exit()
      }
    }
  }
}

object BenchReadoutDecoder extends App {
  val rtl = Rtl(SpinalVerilog(
    ReadoutDecoder(batchSize = 4, inWidth = 16, accWidth = 27, timeWidth = 12)
  ))
  Bench(List(rtl), XilinxRfsocTarget(FMax = 1000 MHz), "./bench/")
}

object ReadoutDecoderTest extends App {
  val batchSize = 4
  SimConfig.compile{
    val dut = ReadoutDecoder(
      batchSize = batchSize,
      inWidth = 16,
      accWidth = 27,
      timeWidth = 12,
    )
    dut
  }.doSimUntilVoid{ dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    
    val input = fork {
      var time = 0
      val freq = 1 / 16.0
      val phaseAdc = 0
      while(true) {
        for(i <- 0 until batchSize) {
          val cr = math.cos((time + i) * freq)
          val ci = math.sin((time + i) * freq)
          val ar = math.cos((time + i) * freq + phaseAdc)
          val ai = -math.sin((time + i) * freq + phaseAdc)

          dut.io.carrier(i).r #= cr
          dut.io.carrier(i).i #= ci
          dut.io.adc(i).r #= ar
          dut.io.adc(i).i #= ai
        }
        time += batchSize
        cd.waitSampling()
      }
    }

    dut.io.refR.valid #= false
    dut.io.refI.valid #= false
    dut.io.cmd.valid #= false

    cd.waitRisingEdge(10)

    dut.io.refR.payload #= -7
    dut.io.refI.payload #= -7
    dut.io.refR.valid #= true
    dut.io.refI.valid #= true

    cd.waitRisingEdge(10)

    dut.io.cmd.valid #= true
    dut.io.cmd.payload #= 4

    cd.waitRisingEdge()
    dut.io.cmd.valid #= false
    for(i <- 0 until 15){
      println(s"res: ${dut.io.res.payload.toBoolean}, valid: ${dut.io.res.valid.toBoolean}")
      // println(s"rsp valid: ${dut.acc.io.rsp.valid.toBoolean}")
      println("")
      cd.waitRisingEdge()
    }
    simSuccess()
  }
}


// object ReadoutDecoderTest extends App {
//   val batchSize = 4
//   SimConfig.compile{
//     val dut = ReadoutDecoderOld(
//       batchSize = batchSize,
//       refWidth = 16,
//       inWidth = 16,
//       accWidth = 27,
//       timeWidth = 12,
//     )
//     dut.nFired.simPublic()
//     dut.acc.io.simPublic()
//     dut
//   }.doSimUntilVoid{ dut =>
//     val cd = dut.clockDomain
//     cd.forkStimulus(10)
    
//     val input = fork {
//       var time = 0
//       val freq = 1 / 16.0
//       val phaseAdc = 0
//       while(true) {
//         for(i <- 0 until batchSize) {
//           val cr = math.cos((time + i) * freq)
//           val ci = math.sin((time + i) * freq)
//           val ar = math.cos((time + i) * freq + phaseAdc)
//           val ai = -math.sin((time + i) * freq + phaseAdc)

//           dut.io.carrier(i).r #= cr
//           dut.io.carrier(i).i #= ci
//           dut.io.adc(i).r #= ar
//           dut.io.adc(i).i #= ai
//         }
//         time += batchSize
//         cd.waitSampling()
//       }
//     }

//     dut.io.refR.valid #= false
//     dut.io.refI.valid #= false
//     dut.io.cmd.valid #= false

//     cd.waitRisingEdge(10)

//     dut.io.refR.payload #= 7
//     dut.io.refI.payload #= -7
//     dut.io.refR.valid #= true
//     dut.io.refI.valid #= true

//     cd.waitSampling(10)

//     dut.io.cmd.valid #= true
//     dut.io.cmd.payload #= 4

//     cd.waitSampling()
//     dut.io.cmd.valid #= false
//     for(i <- 0 until 15){
//       println(s"res: ${dut.io.res.payload.toBoolean}, valid: ${dut.io.res.valid.toBoolean}")
//       println(s"nfired: ${dut.nFired.toBoolean}")
//       // println(s"rsp valid: ${dut.acc.io.rsp.valid.toBoolean}")
//       println(s"acc rspv: ${dut.acc.io.rsp.valid.toBoolean}, rspr: ${dut.acc.io.rsp.payload.r.toBigInt}, rspi: ${dut.acc.io.rsp.payload.i.toBigInt}")
//       println("")
//       cd.waitSampling()
//     }
//     simSuccess()
//   }
// }

// object TestReadoutComparator extends App {
//   SimConfig.compile{
//     val dut = ReadoutComparator(refWidth = 16, readoutWidth = 27)
//     dut
//   }.doSim {dut =>
//     val cd = dut.clockDomain
//     cd.forkStimulus(10)

//     dut.io.refR.valid #= false
//     dut.io.refI.valid #= false
//     dut.io.readoutR.valid #= false
//     dut.io.readoutI.valid #= false

//     cd.waitRisingEdge(10)

//     dut.io.refR.payload #= -7
//     dut.io.refI.payload #= 7
//     dut.io.readoutR.payload #= 7
//     dut.io.readoutI.payload #= 0
//     dut.io.refR.valid #= true
//     dut.io.refI.valid #= true
//     dut.io.readoutR.valid #= true
//     dut.io.readoutI.valid #= true

//     cd.waitRisingEdge()

//     dut.io.refR.valid #= false
//     dut.io.refI.valid #= false
//     dut.io.readoutR.valid #= false
//     dut.io.readoutI.valid #= false


//     for(i <- 0 until 10) {
//       println(s"res: ${dut.io.res.payload.toBoolean}, valid: ${dut.io.res.valid.toBoolean}")
//       cd.waitRisingEdge()
//     }
//   }
// }

// case class ReadoutDecoderOld(batchSize: Int, refWidth: Int, inWidth: Int, accWidth: Int, timeWidth: Int) extends Component {
//   val io = new Bundle {
//     val clear = in port Bool()

//     val adc = in port ComplexBatch(batchSize, inWidth)
//     val carrier = in port ComplexBatch(batchSize, inWidth)
//     val cmd = slave port Flow(UInt(timeWidth bits))
//     val refR = slave port Flow(SInt(refWidth bit))
//     val refI = slave port Flow(SInt(refWidth bit))
//     val res = master port Flow(Bool())
//     val demodData = master port Flow(Vec.fill(batchSize)(Complex(inWidth)))
//   }

//   val acc = ReadoutAccumulator(batchSize = batchSize, inWidth = inWidth, outWidth = accWidth, timeWidth = timeWidth)
//   val cmp = ReadoutComparator(refWidth = refWidth, readoutWidth = accWidth)

//   acc.io.carrier := io.carrier
//   acc.io.adc := io.adc
//   acc.io.cmd := io.cmd
//   io.demodData := acc.io.demodData

//   val nFired = Reg(Bool()) init True clearWhen(acc.io.rsp.valid) setWhen(io.cmd.valid)
//   cmp.io.readoutR.valid := acc.io.rsp.valid & nFired
//   cmp.io.readoutR.payload := acc.io.rsp.r
//   cmp.io.readoutI.valid := acc.io.rsp.valid & nFired
//   cmp.io.readoutI.payload := acc.io.rsp.i
//   cmp.io.refR := io.refR
//   cmp.io.refI := io.refI
//   io.res.payload := cmp.io.res.payload
//   io.res.valid := cmp.io.res.valid & !nFired
// }