package riscq.execute.lsu

import spinal.core._
import spinal.lib._
import spinal.lib.misc.plugin.FiberPlugin
import riscq.{Global, riscv}
import spinal.core.fiber.{Handle, Retainer}
import spinal.core.sim.SimDataPimper
import riscq.decode.Decode
import riscq.riscv.Riscv.{LSLEN, XLEN}
import riscq.riscv.Riscv
import riscq.riscv.MicroOp
import spinal.lib.misc.pipeline._
import riscq.schedule.{ReschedulePlugin, ScheduleService}
import riscq.execute._
import riscq.schedule.PipelinePlugin
import riscq.riscv.{Rvi, IntRegFile}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class LsuCachelessNoRspStorePlugin(
    var addressAt: Int = 0,
    var forkAt: Int = 0,
    var joinAt: Int = 1,
    var wbAt: Int = 2
) extends ExecutionUnit
    with LsuCachelessBusProvider {

  val WITH_RSP = Payload(Bool())
  override def getLsuCachelessBus(): LsuCachelessBus = logic.bus

  def bufferSize = joinAt - forkAt + 1
  def busParam = LsuCachelessBusParam(
    addressWidth = Global.PHYSICAL_WIDTH,
    dataWidth = 32,
    pendingMax = bufferSize + 1
  )

  val logic = during setup new Area {
    val pp = host[PipelinePlugin]
    val ifp = host[IntFormatPlugin]
    val srcp = host[SrcPlugin]
    val ss = host[ScheduleService]
    val buildBefore = retains(pp.elaborationLock)
    val ifpRetainer = retains(ifp.elaborationLock)
    val scheduleRetainer = retains(ss.elaborationLock)

    awaitBuild()

    val LOAD = Payload(Bool())
    val STORE = Payload(Bool())
    val SIZE = Payload(UInt(2 bits))
    val FLOAT = Payload(Bool())

    val sk = SrcKeys

    val iwb = newWriteback(ifp, wbAt)

    val writingRf = ArrayBuffer[MicroOp](Rvi.LB, Rvi.LH, Rvi.LW, Rvi.LBU, Rvi.LHU)
    val loadOps = List(sk.Op.ADD, sk.SRC1.RF, sk.SRC2.I)
    if (XLEN.get == 64) writingRf ++= List(Rvi.LD, Rvi.LWU)
    for (load <- writingRf) {
      val op = addUop(load).srcs(loadOps).decode(LOAD -> True, STORE -> False)
      val spec = Rvi.loadSpec(load)
      spec.signed match {
        case false => ifp.zeroExtend(iwb, op, spec.width)
        case true  => ifp.signExtend(iwb, op, spec.width)
      }
      // op.mayFlushUpTo(forkAt) // page fault / trap
      op.dontFlushFrom(forkAt + 1)
    }

    // Store stuff
    val storeOps = List(sk.Op.ADD, sk.SRC1.RF, sk.SRC2.S)
    val writingMem = ArrayBuffer[MicroOp](Rvi.SB, Rvi.SH, Rvi.SW)
    if (XLEN.get == 64) writingMem ++= List(Rvi.SD)
    for (store <- writingMem) {
      val op = addUop(store).srcs(storeOps).decode(LOAD -> False, STORE -> True)
      // op.mayFlushUpTo(forkAt) // page fault / trap
      op.addRsSpec(riscv.RS2, 0)
      op.dontFlushFrom(forkAt)
    }

    // elp.setDecodingDefault(FENCE, False)
    // layer.add(Rvi.FENCE).addDecoding(FENCE -> True)
    // layer(Rvi.FENCE).setCompletion(forkAt)

    ifpRetainer.release()
    uopRetainer.release()
    scheduleRetainer.release()

    val injectCtrl = pp.execute(0)
    val inject = new injectCtrl.Area {
      SIZE := Decode.INSTRUCTION(13 downto 12).asUInt
    }

    // Hardware elaboration
    val addressCtrl = pp.execute(addressAt)
    val forkCtrl = pp.execute(forkAt)
    val joinCtrl = pp.execute(joinAt)
    val wbCtrl = pp.execute(wbAt)
    for (i <- forkAt to joinAt) {
      pp.execute(i)
    }

    val bus = master(LsuCachelessBus(busParam)).simPublic()

    val rrp = host[RegReadPlugin]
    val onFirst = new pp.Execute(0) {
      val WRITE_DATA = insert(down(rrp(IntRegFile, riscv.RS2)))
    }

    val onAddress = new addressCtrl.Area {
      val RAW_ADDRESS = insert(srcp.ADD_SUB.asUInt)

      // val MISS_ALIGNED = insert((1 to log2Up(LSLEN / 8)).map(i => SIZE === i && RAW_ADDRESS(i - 1 downto 0) =/= 0).orR)
    }

    val cmdInflights = Bool()

    val onFork = new forkCtrl.Area {
      val skip = False

      val noStore = ~STORE
      val maxStoreOnTheFly = bufferSize
      val storeId = U(bufferSize)
      val storeCounter = CounterUpDown(1 << log2Up(maxStoreOnTheFly), incWhen = bus.cmd.fire && STORE, decWhen = bus.rsp.valid && bus.rsp.payload.id === storeId) // just for future implementation of fence

      val cmdCounter = Counter(bufferSize, bus.cmd.fire && noStore) // for loadId
      val cmdSent = RegInit(False) setWhen (bus.cmd.fire) clearWhen (down.isMoving)

      bus.cmd.assertPersistence()
      bus.cmd.valid := isValid && SEL && !cmdSent && !forkCtrl.up.isCancel && !skip // && !doFence
      bus.cmd.id := STORE.mux(storeId, cmdCounter.value)
      bus.cmd.write := STORE
      bus.cmd.address := onAddress.RAW_ADDRESS
      val mapping = (0 to log2Up(Riscv.LSLEN / 8)).map { size =>
        val w = (1 << size) * 8
        size -> onFirst.WRITE_DATA(0, w bits).#*(Riscv.LSLEN / w) // !!! use with mask
      }
      bus.cmd.data := bus.cmd.size.muxListDc(mapping)
      bus.cmd.size := SIZE.resized
      bus.cmd.mask := AddressToMask(bus.cmd.address, bus.cmd.size, Riscv.LSLEN / 8)

      val freezeIt = bus.cmd.isStall
      haltWhen(freezeIt)

      // when(onAddress.MISS_ALIGNED) {
      //   skip := True
      // }

      WITH_RSP := (bus.cmd.valid || cmdSent) && noStore
    }

    val onJoin = new joinCtrl.Area {
      val buffers = List.fill(bufferSize + 1)(new Area {
        val valid = RegInit(False)
        val payload = Reg(LsuCachelessRsp(bus.p, false))
      })

      val busRspWithoutId = LsuCachelessRsp(bus.p, false)
      busRspWithoutId.assignSomeByName(bus.rsp.payload)
      when(bus.rsp.valid) {
        buffers.onSel(bus.rsp.id) { b =>
          b.valid := True
          b.payload := busRspWithoutId
        }
      }

      val pop = WITH_RSP && down.isMoving
      val rspCounter = Counter(bufferSize, pop)
      val reader = buffers.reader(rspCounter.resized)
      val readerValid = reader(_.valid)
      when(pop) {
        buffers.onSel(rspCounter.resized)(_.valid := False)
      }

      val busRspHit = bus.rsp.valid && bus.rsp.id === rspCounter.value.resize(bus.rsp.id.getWidth)
      val rspValid = readerValid || busRspHit
      val rspPayload = readerValid.mux(CombInit(reader(_.payload)), busRspWithoutId)

      val READ_DATA = insert(rspPayload.data)
      haltWhen(WITH_RSP && !rspValid)
    }

    for (eid <- forkAt + 1 to joinAt) {
      pp.execute(eid).up(WITH_RSP).setAsReg().init(False)
    }

    val onWb = new wbCtrl.Area {
      val rspSplits = onJoin.READ_DATA.subdivideIn(8 bits)
      val rspShifted = Bits(LSLEN bits)
      val wordBytes = LSLEN / 8

      // For alignment
      // Generate minimal mux to move from a wide aligned memory read to the register file shifter representation
      for (i <- 0 until wordBytes) {
        val srcSize = 1 << (log2Up(wordBytes) - log2Up(i + 1))
        val srcZipped = rspSplits.zipWithIndex.filter { case (v, b) => b % (wordBytes / srcSize) == i }
        val src = srcZipped.map(_._1)
        val range = log2Up(wordBytes) - 1 downto log2Up(wordBytes) - log2Up(srcSize)
        val sel = srcp.ADD_SUB(range).asUInt
        rspShifted(i * 8, 8 bits) := src.read(sel)
      }

      // wordBytes = 4
      // i = 0
      // srcSize = 4
      // srczipped = rspSplits(b : b%(1) = 0) = rspSplits.zipwithindex
      // src = rspSplits
      // range = 1 downto  0
      // sel = ADD_SUB(1:0)
      // rspShifted(0:7) := src(ADDRESS(1:0))

      // ADDRESS = 11

      // wordBytes = 4
      // i = 1
      // srcSize = 2
      // srczipped = rspSplits(b : b%(2) = 1) = rspSplits.zipwithindex.oddindexes
      // src = rspSplits(1,3)
      // range = 1 downto  1
      // sel = ADD_SUB(1:1)
      // rspShifted(8:15) := src(ADDRESS(1:1))

      // wordBytes = 4
      // i = 2
      // srcSize = 1
      // srczipped = rspSplits(b : b%(1) = 2) = rspSplits.zipwithindex.2
      // src = rspSplits(2)
      // range = 1 downto  2
      // sel = empty
      // rspShifted(16:23) := src

      iwb.valid := SEL && isValid
      iwb.payload := rspShifted

    }

    buildBefore.release()
  }

}
