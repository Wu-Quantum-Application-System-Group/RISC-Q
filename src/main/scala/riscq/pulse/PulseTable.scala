package riscq.pulse

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber

case class PulseTableTerm(dataWidth: Int, envWidth: Int) extends Bundle {
  val phase = Bits(dataWidth bit)
  val amp = Bits(dataWidth bit)
  val env = Bits(envWidth bit)
  val dur = Bits(dataWidth bit)
}

// case class PulseTableCmd(dataWidth: Int, addrWidth: Int) extends Bundle {
//   val addr = UInt(addrWidth bits)
//   val data = Bits(dataWidth bits)
// }

// case class PulseTable(dataWidth: Int, pulseNum: Int) extends Component {
//   // each pulse has 4 parameters, 4 bits of address should be enough
//   // addrWidth should be 
//   // 0 for the index
//   val addrWidth = log2Up(pulseNum + 1) + 4
//   val io = new Bundle {
//     val cmd = slave(Flow(PulseTableCmd(dataWidth, addrWidth)))
//     val rsp = master(Flow(PulseTableTerm(dataWidth)))
//   }

//   val table = Vec.fill(pulseNum)(Reg(PulseTableTerm(dataWidth)))
//   val outId= Reg(UInt(addrWidth bits))
//   when(io.cmd.valid && io.cmd.payload.addr === 0) {
//     outId := io.cmd.payload.data.resized.asUInt
//   }
//   io.rsp.payload := table(outId)
//   io.rsp.valid := Delay(io.cmd.valid && io.cmd.payload.addr === 0, 1)

//   def paramAddr(pulseId: Int, paramId: Int) = pulseId * 4 + paramId
//   for(i <- 0 until pulseNum) {
//     when(io.cmd.valid) {
//       when(io.cmd.payload.addr === paramAddr(i, 0)) {
//         table(i).phase := io.cmd.payload.data.resized
//       }
//       when(io.cmd.payload.addr === paramAddr(i, 1)) {
//         table(i).amp := io.cmd.payload.data.resized
//       }
//       when(io.cmd.payload.addr === paramAddr(i, 2)) {
//         table(i).env := io.cmd.payload.data.resized
//       }
//       when(io.cmd.payload.addr === paramAddr(i, 3)) {
//         table(i).dur := io.cmd.payload.data.resized
//       }
//     }
//   }
// }

case class PulseGeneratorWithTableFiber(
  startTime: UInt,
  time: UInt,
  batchSize: Int,
  dataWidth: Int,
  envAddrWidth: Int,
  timeWidth: Int,
  pulseNum: Int = 1,
  fifoDepth: Int = 2,
  durWidth: Int = 16,
  memLatency: Int = 2,
  timeInOffset: Int = 0, // real_time - io.time
  fifoTimeWidth: Int = 16,
) extends Area {
  val up = tilelink.fabric.Node.up()


  val table = Vec.fill(pulseNum)(Reg(PulseTableTerm(dataWidth, envAddrWidth)))
  val outId = Reg(UInt(log2Up(pulseNum) bit))
  val outParam = table(outId)
  val outParamValid = False
  val outParamFlow = Reg(Flow(outParam))
  outParamFlow.payload := outParam
  outParamFlow.valid := Delay(outParamValid, 1)
  KeepAttribute(outParamFlow)

  val pg = PulseGenerator(
    batchSize = batchSize,
    dataWidth = dataWidth,
    addrWidth = envAddrWidth,
    timeWidth = timeWidth,
    fifoDepth = fifoDepth,
    durWidth = durWidth,
    memLatency = memLatency,
    timeInOffset = timeInOffset + 1,
    fifoTimeWidth = fifoTimeWidth,
    fifoCond = "geq"
  )
  pg.io.startTime := RegNext(startTime).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")
  pg.io.time := RegNext(time).addAttribute("EQUIVALENT_REGISTER_REMOVAL", "NO")

  val tlAddrWidth = log2Up(pulseNum + 1) + 4 + 2 // 4 for at most 16 params, 2 for each param takes 4 bytes of address

  pg.io.phase.payload.assignFromBits(outParamFlow.phase)
  pg.io.phase.valid := outParamFlow.valid
  pg.io.amp.payload.assignFromBits(outParamFlow.amp)
  pg.io.amp.valid := outParamFlow.valid
  pg.io.addr.payload.assignFromBits(outParamFlow.env)
  pg.io.addr.valid := outParamFlow.valid
  pg.io.dur.payload.assignFromBits(outParamFlow.dur)
  pg.io.dur.valid := outParamFlow.valid

  val logic = Fiber build new Area {
    up.m2s.supported load tilelink.SlaveFactory.getSupported(
      addressWidth = tlAddrWidth,
      dataWidth = 32,
      allowBurst = false,
      proposed = up.m2s.proposed
    )
    up.s2m.none()

    val factory = new tilelink.SlaveFactory(up.bus, false)

    factory.write(outId, 0)
    factory.onWrite(0) {
      outParamValid := True
    }

    factory.driveFlow(pg.io.freq, 4, bitOffset = 16)

    val pulseOffset = 4 * 4
    for(i <- 0 until pulseNum) {
      factory.write(table(i).phase, (i + 1) * pulseOffset + 0, bitOffset = 16)
      factory.write(table(i).amp, (i + 1) * pulseOffset + 4, bitOffset = 16)
      factory.write(table(i).env, (i + 1) * pulseOffset + 8, bitOffset = 16)
      factory.write(table(i).dur, (i + 1) * pulseOffset + 12, bitOffset = 16)
    }
  }
}