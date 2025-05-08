package riscq.pulse

import spinal.core._
import spinal.core.sim._
import spinal.lib._

// pop at time + 1
case class TimedFifo[T <: Data](
  dataType: HardType[T],
  depth: Int,
  timeWidth: Int
) extends Component {
  val latency = 1 // time between io.tiem >= startTime and io.pop.valid = true
  val timedData = HardType(new Bundle {
    val data = dataType()
    val startTime = UInt(timeWidth bit)
  })
  val io = new Bundle {
    val time = in port UInt(timeWidth bit)
    val push = slave port Stream(timedData)
    val pop = master port Flow(dataType())
  }

  val fifo = new StreamFifo(
    dataType = timedData,
    depth = depth,
    withAsyncRead = true,
    forFMax = true,
    useVec = true
  )

  // val doPop = RegNext(io.time === fifo.io.pop.startTime)
  val timeUp = RegNext(io.time >= fifo.io.pop.startTime)
  val popped = RegNext(timeUp)
  val doPop = timeUp && ~popped
  fifo.io.push << io.push
  fifo.io.pop.ready := doPop // && io.pop.ready
  io.pop.payload := fifo.io.pop.data
  io.pop.valid := fifo.io.pop.valid && doPop
}

object TestTimedFifo extends App {
  SimConfig.compile{
    val dut = TimedFifo(UInt(32 bit), 2, 32)
    dut.fifo.io.simPublic()
    dut
  }.doSimUntilVoid{ dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    cd.assertReset()
    dut.io.push.valid #= false
    cd.waitRisingEdge(10)
    cd.deassertReset()
    cd.waitRisingEdge(10)

    var time = 0
    val timeLogic = fork {
      while(true) {
        dut.io.time #= time
        cd.waitRisingEdge()
        time += 1
      }
    }

    dut.io.push.valid #= true
    dut.io.push.payload.data #= 123
    dut.io.push.payload.startTime #= 10
    cd.waitRisingEdge()
    dut.io.push.valid #= false

    for(i <- 0 until 15) {
      println(s"time: $time")
      println(s"v: ${dut.io.pop.valid.toBoolean} d: ${dut.io.pop.payload.toBigInt}")
      println(s"v: ${dut.fifo.io.pop.valid.toBoolean} d: ${dut.fifo.io.pop.payload.data.toBigInt} t: ${dut.fifo.io.pop.payload.startTime.toBigInt}")
      println("")
      cd.waitRisingEdge()
    }
    simSuccess()

  }
}