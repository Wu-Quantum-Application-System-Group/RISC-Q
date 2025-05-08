package riscq.pulse

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.fsm._
import scala.math
import riscq.tester.ByteHelper

case class PulseGenerator(
  batchSize: Int,
  dataWidth: Int,
  addrWidth: Int,
  timeWidth: Int,
  fifoDepth: Int = 2,
  durWidth: Int = 16,
  memLatency: Int = 2,
  timeInOffset: Int = 0, // real_time - io.time
  fifoNum: Int = 1, // number of parallel fifos for a parameter
) extends Component {
  val batchWidth = batchSize * dataWidth
  assert(fifoNum >= 1)
  val needMux = fifoNum > 1

  val io = new Bundle {
    val time = in port UInt(timeWidth bits) // the real time

    val startTime = in port UInt(timeWidth bit) // to control timed queue

    val amp = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator
    val freq = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator and freq phase generator
    val phase = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator
    val addr = slave port Flow(UInt(addrWidth bit)) // to pulse mem
    val dur = slave port Flow(UInt(durWidth bit)) // pulse duration
    val memPort = master port MemReadPort(Bits(batchWidth bits), addressWidth = addrWidth)// to pulse mem

    val pulse = master port Flow(ComplexBatch(batchSize, dataWidth)) // to dac

    val inId = needMux generate (slave port Flow(UInt(log2Up(fifoNum) bit))) // the input param will be pushed to which fifo
    val outId = needMux generate (slave port Flow(UInt(log2Up(fifoNum) bit))) // which fifo will be used for generating pulse

  }

  val startTime = io.startTime

  val pg = SimplePulseGenerator(
    batchSize,
    dataWidth,
    addrWidth,
    timeWidth,
    fifoDepth,
    durWidth,
    memLatency,
  )

  val timeQueueLatency = 1
  def shiftedTime(shift: Int): UInt = {
    RegNext(io.time + timeInOffset + timeQueueLatency + shift + 1 + needMux.toInt)
    // RegNext(time) is 1 earlier than time
  }

  val inId = needMux generate (RegNextWhen(io.inId.payload, io.inId.valid) init 0)
  val outId = needMux generate (RegNextWhen(io.outId.payload, io.outId.valid) init 0)

  val ampFifos = List.fill(fifoNum)(TimedFifo(io.amp.payload, fifoDepth, timeWidth))
  val phaseGenFreqFifos = List.fill(fifoNum)(TimedFifo(io.freq.payload, fifoDepth, timeWidth))
  val cgFreqFifos = List.fill(fifoNum)(TimedFifo(io.freq.payload, fifoDepth, timeWidth))
  val phaseFifos = List.fill(fifoNum)(TimedFifo(io.phase.payload, fifoDepth, timeWidth))
  val addrFifos = List.fill(fifoNum)(TimedFifo(io.addr.payload, fifoDepth, timeWidth))
  val durFifos = List.fill(fifoNum)(TimedFifo(io.dur.payload, fifoDepth, timeWidth))

  def connectFifos[T <: Data](fifos: List[TimedFifo[T]], inData: Flow[T], outData: Flow[T], latency: Int) = {
    fifos.foreach{ fifo =>
      fifo.io.push.payload.data := inData.payload
      fifo.io.push.payload.startTime := startTime
      if (needMux) {
        fifo.io.push.valid := False
      } else {
        fifo.io.push.valid := inData.valid
      }
      fifo.io.time := shiftedTime(latency)
    }

    if(needMux) {
      when(inData.valid) {
        fifos.onSel(inId){_.io.push.valid := True}
      }
      val outBuf = Reg(outData)
      fifos.onSel(outId){fifo => outBuf << fifo.io.pop}
      outData := outBuf
    } else {
      outData << fifos(0).io.pop
    }
  }

  connectFifos(ampFifos, io.amp, pg.io.amp, pg.ampLatency)
  connectFifos(phaseGenFreqFifos, io.freq, pg.io.phaseGenFreq, pg.freqLatency)
  connectFifos(cgFreqFifos, io.freq, pg.io.cgFreq, pg.freqLatency)
  connectFifos(phaseFifos, io.phase, pg.io.phase, pg.phaseLatency)
  connectFifos(addrFifos, io.addr, pg.io.addr, pg.addrLatency)
  connectFifos(durFifos, io.dur, pg.io.dur, pg.durLatency)

  io.memPort <> pg.io.memPort
  pg.io.time := io.time

  io.pulse := pg.io.pulse
}

case class SimplePulseGenerator(
  batchSize: Int,
  dataWidth: Int,
  addrWidth: Int,
  timeWidth: Int,
  fifoDepth: Int = 2,
  durWidth: Int = 16,
  memLatency: Int = 2,
) extends Component {
  val batchWidth = batchSize * dataWidth

  val io = new Bundle {
    val time = in port UInt(timeWidth bits) // the real time
    val amp = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator
    val phaseGenFreq = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator
    val cgFreq = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator
    val phase = slave port Flow(AFix.S(0 exp, dataWidth bit)) // to carrier generator
    val addr = slave port Flow(UInt(addrWidth bit)) // to pulse mem
    val dur = slave port Flow(UInt(durWidth bit)) // pulse duration
    val memPort = master port MemReadPort(Bits(batchWidth bits), addressWidth = addrWidth)// to pulse mem

    val pulse = master port Flow(ComplexBatch(batchSize, dataWidth)) // to dac
  }


  val phaseGen = FreqPhaseBatchGenerator(batchSize, dataWidth)
  val cg = CarrierGeneratorWithAmp(batchSize, dataWidth, timeWidth)
  val pmReader = PulseMemReader(batchSize, dataWidth, addrWidth, memLatency)
  val envMult = EnvelopeMultiplier(batchSize, dataWidth)
  // envMult.addAttribute("KEEP_HIERARCHY", "TRUE")

  pmReader.io.memPort <> io.memPort

  envMult.io.carrier := cg.io.carrier
  envMult.io.env := pmReader.io.env


  // // !!!!!!!!!!!!!!!!! flow to stream
  // // freq has to be set in advance
  cg.io.freqPhases << phaseGen.io.phases

  cg.io.time := io.time

  // addr
  // startTime = 100
  // addrLatency = 3 + 2 = 5
  // shiftedTime = RegNext(io.time + 5 + 2) = io.time + 6
  // pop time: io.time + 6 === startTime + 1 => io.time = 95
  val pulseBufLatency = 2

  val freqLatency = phaseGen.latency + cg.freqPhaseLatency + envMult.latency + pulseBufLatency
  phaseGen.io.freq.assignSomeByName(io.phaseGenFreq)

  val cgFreqLatency = cg.freqLatency + envMult.latency + pulseBufLatency
  cg.io.freq << io.cgFreq

  val phaseLatency = cg.phaseLatency + envMult.latency + pulseBufLatency
  cg.io.phase << io.phase

  val ampLatency = cg.ampLatency + envMult.latency + pulseBufLatency
  cg.io.amp << io.amp

  val addrLatency = pmReader.latency + envMult.latency + pulseBufLatency
  pmReader.io.addr << io.addr


  val durLatency = 3 // queue -> timer -> resValid -> pulseBuf

  val timer = Reg(UInt(durWidth bits)) init 0 // for pulse duration
  val timerGtZero = timer > 0
  when(timerGtZero) {
    timer := timer - U(1)
  }
  when(io.dur.fire) {
    timer := io.dur.payload
  }

  val resValid = RegNext(timerGtZero)
  resValid.addAttribute("MAX_FANOUT", "16")
  io.pulse.valid := resValid

  val pulseBuf0 = RegNext(envMult.io.pulse)
  val pulseBuf = Reg(io.pulse.payload)
  pulseBuf := resValid.mux(pulseBuf0, pulseBuf.getZero)
  io.pulse.payload := pulseBuf
}

object TestPulseGenerator extends App {
  val batchSize = 16
  val dataWidth = 16
  val addrWidth = 12
  val timeWidth = 32
  SimConfig.compile{
    val dut = PulseGenerator(
      batchSize=batchSize,
      dataWidth=dataWidth,
      addrWidth=addrWidth,
      timeWidth=timeWidth,
      fifoDepth = 4,
      memLatency = 2,
      timeInOffset = 0,
    )
    dut.pg.phaseGen.io.simPublic()
    dut.pg.cg.io.simPublic()
    dut.pg.cg.amp.simPublic()
    dut.pg.cg.phase.simPublic()
    dut.pg.cg.freq.simPublic()
    dut.pg.envMult.io.simPublic()
    dut.pg.pmReader.io.simPublic()
    // dut.pg.ampQueue.io.simPublic()
    // dut.pg.phaseQueue.io.simPublic()
    // dut.pg.addrQueue.io.simPublic()
    // dut.pg.durQueue.io.simPublic()
    // dut.pg.freqQueue.io.simPublic()
    dut
  }.doSimUntilVoid{ dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    cd.assertReset()
    dut.io.amp.valid #= false
    dut.io.freq.valid #= false
    dut.io.phase.valid #= false
    dut.io.addr.valid #= false
    dut.io.dur.valid #= false

    cd.waitRisingEdge(100)
    cd.deassertReset()
    cd.waitRisingEdge(100)

    dut.io.time #= 0
    cd.waitRisingEdge()

    dut.io.amp.valid #= true
    dut.io.phase.valid #= true
    dut.io.addr.valid #= true
    dut.io.dur.valid #= true
    dut.io.freq.valid #= true


    // dut.io.amp.payload #= 0
    val freq = 1.0 / 8
    // val freq = 0
    dut.io.startTime #= 60
    dut.io.amp.payload #= dut.io.amp.payload.maxValue / 2
    dut.io.freq.payload #= freq
    dut.io.phase.payload #= 0
    dut.io.addr.payload #= 0
    dut.io.dur.payload #= 0
    cd.waitRisingEdge()

    dut.io.amp.valid #= false
    dut.io.phase.valid #= false
    dut.io.addr.valid #= false
    dut.io.dur.valid #= false
    dut.io.freq.valid #= false
    cd.waitRisingEdge()

    val memLogic = fork {
      var addr = 0
      var rsp1 = 0
      var rsp2 = 0
      while(true) {
        rsp2 = rsp1
        rsp1 = if (addr > 200) ((1 << 15) - 1) else (1 << 13)
        // addr = dut.io.memPort.cmd.payload.toInt
        sleep(5)
        addr = dut.io.memPort.cmd.payload.toInt
        val rsp2Str = ByteHelper.intToBinStr(rsp2, 16)
        // val rsp2Str = ByteHelper.intToBinStr((1 << 15) - 1, 16)
        val rsp2Bytes = ByteHelper.fromBinStr(rsp2Str * 16).reverse
        dut.io.memPort.rsp #= rsp2Bytes

        // println(s"$time $addr, $rsp1, $rsp2")
        // println(s"dutio addr: ${dut.io.memPort.cmd.payload.toBigInt}")
        // println(s"pmReader addr: ${dut.pmReader.io.memPort.cmd.payload.toBigInt}")
        cd.waitRisingEdge()
      }
    }

    dut.io.amp.valid #= true
    dut.io.phase.valid #= true
    dut.io.addr.valid #= true
    dut.io.dur.valid #= true
    dut.io.freq.valid #= true

    dut.io.startTime #= 120
    dut.io.freq.payload #= freq / 2
    dut.io.amp.payload #= dut.io.amp.payload.maxValue
    dut.io.phase.payload #= 0.5
    dut.io.addr.payload #= 1000
    dut.io.dur.payload #= 3

    cd.waitRisingEdge()

    dut.io.amp.valid #= false
    dut.io.phase.valid #= false
    dut.io.addr.valid #= false
    dut.io.freq.valid #= false

    dut.io.dur.payload #= 3
    dut.io.startTime #= 124

    cd.waitRisingEdge()
    dut.io.dur.valid #= false

    println(s"${dut.io.time.toBigInt}")

    var time = 0
    val timeLogic = fork {
      while(true) {
        time += 1
        dut.io.time #= time
        cd.waitRisingEdge()
      }
    }

    val waitStart = 115
    cd.waitRisingEdge(waitStart)
    for(i <- 0 until 15) {
      sleep(3)
      println(s"time: ${dut.io.time.toBigInt} v: ${dut.io.pulse.valid.toBoolean}")
      println(s"pulse: ${dut.io.pulse.payload.map{_.r.toDouble}}")
      // println(s"envMult: env: ${dut.envMult.io.env.map{_.toBigInt}}")
      println(s"envMult: carrier: ${dut.pg.envMult.io.carrier.map{_.r.toDouble}}")
      println(s"envMult: pulse: ${dut.pg.envMult.io.pulse.map{_.r.toDouble}}")
      // println(s"pm addr in: ${dut.pmReader.io.addr.payload.toBigInt} ${dut.pmReader.io.addr.valid.toBoolean}")
      // println(s"pm addr out: ${dut.pmReader.io.memPort.cmd.payload.toBigInt}")
      // println(s"pm env: ${dut.pmReader.io.env.map{_.toBigInt}}")
      // println(s"carrier freq: ${dut.cg.freq.toDouble}")
      // println(s"carrier freqphase: ${dut.cg.io.freqPhases.payload.map{_.r.toDouble}}")
      // println(s"cg phase: ${dut.cg.phase.toDouble}")
      // println(s"cg amp: ${dut.cg.amp.toDouble}")
      // println(s"cg carrier: ${dut.cg.io.carrier.map{_.r.toDouble}}")
      // println(s"env: ${dut.pmReader.io.env.map{_.toBigInt.toString(2)}}")
      // println(s"env: ${dut.pmReader.io.memPort.rsp.toBigInt.toString(2)}")
      // println(s"amp: ${dut.cg.io.amp.payload.toDouble}, ${dut.cg.io.amp.valid.toBoolean}, ${dut.cg.amp.toDouble}")
      // println(s"ampQueue: ${dut.ampQueue.io.pop.payload.toDouble}, ${dut.ampQueue.io.pop.valid.toBoolean}")
      // println(s"phaseQueue: ${dut.phaseQueue.io.pop.payload.toDouble}, ${dut.phaseQueue.io.pop.valid.toBoolean}")
      // println(s"durQueue: ${dut.durQueue.io.pop.payload.toBigInt}, ${dut.durQueue.io.pop.valid.toBoolean}")
      // println(s"addrQueue: ${dut.addrQueue.io.pop.payload.toBigInt}, ${dut.addrQueue.io.pop.valid.toBoolean}")
      // println(s"phase gen: ${dut.phaseGen.io.phases.payload.map{_.r.toDouble}}")
      // println(s"freqQueue: ${dut.freqQueue.io.pop.payload.toDouble}, ${dut.freqQueue.io.pop.valid.toBoolean}")
      println("")
      cd.waitRisingEdge()
    }

    simSuccess()
  }
}