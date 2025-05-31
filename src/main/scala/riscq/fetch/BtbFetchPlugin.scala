package riscq.fetch

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import spinal.lib.misc.database.Database._
import spinal.lib.misc.pipeline._
import riscq.Global._
import riscq.schedule.PipelinePlugin
import spinal.lib.bus.tilelink
import spinal.lib.bus.misc.SizeMapping
import spinal.core.fiber.Fiber
import spinal.core.sim.SimMemPimper
import riscq.schedule.ReschedulePlugin

object BtbParams {
  val addr_size = 4
  val num_entries = (1 << addr_size)
  val tag_size = 30 - addr_size
}

case class BtbEntry() extends Bundle {
  val valid = Bool
  val tag = UInt(BtbParams.tag_size bits)
  val targetPc = UInt(32 bits)
}

class BtbFetchBramConnectArea(fcp: BtbFetchPlugin, port: MemReadWritePort[Bits]) extends Area {
  val logic = Fiber build new Area {
    port.enable := True
    port.write := False
    port.wdata := 0
    port.mask.setAllTo(True)

    val wordWidth = fcp.wordWidth
    val addrShift = log2Up(wordWidth / 8)
    port.address := fcp.logic.bus.cmd.address(addrShift, port.addressWidth bit)
    // val id = RegNext(RegNext(fcp.logic.bus.cmd.id))
    // val valid = RegNext(RegNext(fcp.logic.bus.cmd.valid) init False) init False
    // val word = RegNext(port.rdata)
    val id = RegNext(fcp.logic.bus.cmd.id)
    val valid = RegNext(fcp.logic.bus.cmd.valid) init False
    val word = port.rdata

    fcp.logic.bus.rsp.id := id
    fcp.logic.bus.rsp.error := False
    fcp.logic.bus.rsp.word := word
    fcp.logic.bus.rsp.valid := valid
    fcp.logic.bus.cmd.ready := True
  }
}

// Mostly copied from `FetchCachelessPlugin`
class BtbFetchPlugin(
    var wordWidth: Int,
    var forkAt : Int = 0,
    var btbAt : Int = 0,
    var jumpAt : Int = 2,
    var joinAt : Int = 2,
    var cmdForkPersistence: Boolean = true
) extends FiberPlugin {
  val logic = during setup new Area{
    PHYSICAL_WIDTH.set(32)
    // VIRTUAL_WIDTH.set(32)
    // val pp = host[FetchPipelinePlugin]
    val sp = host[ReschedulePlugin]
    val pp = host[PipelinePlugin]
    val pcp = host[PcPlugin]
    for(i <- forkAt to joinAt){
      pp.fetch(i)
    }
    val pcpRetainer = retains(pcp.elaborationLock, sp.elaborationLock)
    val buildBefore = retains(pp.elaborationLock)

    /* -- branch prediction table -- */
    val branch_targets = Mem(BtbEntry(), BtbParams.num_entries)
    branch_targets.simPublic()

    awaitBuild()

    val age = pp.feGetAge(jumpAt)
    val pcPort = pcp.newJumpInterface(age)
    val flushPort = sp.newFlushPort(age)

    pcpRetainer.release()

    Fetch.WORD_WIDTH.set(wordWidth)

    val idCount = joinAt - forkAt + 1 // default 2
    val p = CachelessBusParam(PHYSICAL_WIDTH, Fetch.WORD_WIDTH, idCount, false)
    // val p = CachelessBusParam(32, Fetch.WORD_WIDTH, idCount, false)
    val bus = master(CachelessBus(p))

    val BUFFER_ID = Payload(UInt(log2Up(idCount) bits)) // reserveId of current data

    val buffer = new Area{ // buffer for full fetch pipeline
      val reserveId = Counter(idCount) // next slot for data
      val inflight = Vec.fill(idCount)(RegInit(False)) // if a slot is loading
      val words = Mem.fill(idCount)(Fetch.WORD) // memory for saving fetched data
      val reservedHits = for (ctrlId <- forkAt+1 to joinAt; ctrl = pp.fetch(ctrlId)) yield {
        ctrl.isValid && ctrl(BUFFER_ID) === reserveId // reservedId is being used
      }
      val full = CombInit(reservedHits.orR || inflight.read(reserveId)) //TODO that's one cycle late, can use sort of ahead value

      when(bus.cmd.fire) { // sending loading message to memory
        inflight(reserveId) := True
      }

      when(bus.rsp.valid) { // respond received
        inflight(bus.rsp.id) := False
        words(bus.rsp.id) := bus.rsp.word
      }
    }

    val fork = new pp.Fetch(forkAt){ // send address to memory
      // forcedSpawn: fire a new one even if the last one is not fired
      val fresh = (forkAt == 0).option(host[PcPlugin].forcedSpawn()) // used for jump inst to resend the memory query
      // forkStream: payloades go to both bus and down. only move when both bus and down are available
      val cmdFork = forkStream(fresh)
      bus.cmd.arbitrationFrom(cmdFork.haltWhen(buffer.full)) // set valid and ready
      bus.cmd.id := buffer.reserveId // sending request

      bus.cmd.address := Fetch.WORD_PC  // set in PcPlugin

      BUFFER_ID := buffer.reserveId // id of the issued request

      when(up.isMoving) { // up is logic.inject in PcPlugin, isMoving = valid && (isReady || isCancel) 
        buffer.reserveId.increment() // fetch next data
      }
    }

    val btb_get = new pp.Fetch(btbAt) {
      val addr = Fetch.WORD_PC(2 until (2 + BtbParams.addr_size))
      val entry = insert(branch_targets.readAsync(addr))
    }

    val jump_prediction = new pp.Fetch(jumpAt) {
      val entry = btb_get.entry
      val tag = Fetch.WORD_PC((2 + BtbParams.addr_size) until PC_WIDTH)
      val in_table = entry.valid && (entry.tag === tag)
      val jump_predicted = insert(isValid && in_table)
      flushPort.self := False
      when(jump_predicted) {
        flushPort.valid := True
        pcPort.valid := True
        pcPort.pc := entry.targetPc
      } otherwise {
        flushPort.valid := False
        pcPort.setIdle()
      }
    }

    val jump_predicted = jump_prediction.jump_predicted

    // using payload in pp.Fetch(forkAt) is equivalent to in pp.fetch(forkAt).down, but has access to haltWhen
    val join = new pp.Fetch(joinAt){
      val haltIt = buffer.inflight.read(BUFFER_ID) // check if the data fetched in fork is ready
      Fetch.WORD := buffer.words.readAsync(BUFFER_ID) // get the data
      // // Implement bus rsp bypass into the pipeline (without using the buffer)
      when(bus.rsp.valid && bus.rsp.id === BUFFER_ID){ // bypass the fetched data when ready without entering buffer 
        haltIt := False
        Fetch.WORD := bus.rsp.word
      }
      haltWhen(haltIt)
    }
    buildBefore.release()
  }
}
