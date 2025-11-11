package riscq.tester

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi.sim.Axi4Master
import riscq.soc.QECMessage
import helios.HeliosParams
import helios.HeliosCore
import java.io.File
import spinal.lib.misc.Elf
import spinal.lib.bus.tilelink.sim.Monitor
import spinal.lib.bus.tilelink.sim.MonitorSubscriber
import spinal.lib.bus.tilelink.sim.TransactionA
import spinal.lib.bus.tilelink.sim.TransactionD
import riscq.soc.HeliosCoreWrapper

object PrintGrid {
  def apply(x: Int, y: Int, grid: Vec[Vec[Bool]]) {
    for(i <- 0 until x) {
      for(j <- 0 until y) {
        print(s"${if(grid(i)(j).toBoolean) "1" else "0"} ")
      }
      println()
    }
  }
}
object TestHelios extends App {
  SimConfig.compile{
    val params = HeliosParams(3, 3, 6)
    val dut = new HeliosCore(params)
    dut.output.simPublic()
    dut.controller.measurements.simPublic()
    dut.controller.global_stage.simPublic()
    dut
  }.doSim{dut =>
    val cd = dut.clockDomain
    cd.forkStimulus(10)
    cd.assertReset()
    dut.meas_in.valid #= false
    cd.waitSampling(100)
    cd.deassertReset()

    def driveSynd0(meas_in: Vec[Vec[Bool]]) {
      meas_in(0)(0) #= false
      meas_in(1)(0) #= false
      meas_in(2)(0) #= false
      meas_in(3)(0) #= false
    }
    def driveSynd1(meas_in: Vec[Vec[Bool]]) {
      meas_in(0)(0) #= false
      meas_in(1)(0) #= false
      meas_in(2)(0) #= true
      meas_in(3)(0) #= false
    }
    def driveSynd2(meas_in: Vec[Vec[Bool]]) {
      meas_in(0)(0) #= false
      meas_in(1)(0) #= false
      meas_in(2)(0) #= false
      meas_in(3)(0) #= false
    }

    val feedSynd = fork{
      while(true) {
        if(dut.meas_in.ready.toBoolean) {
          dut.meas_in.valid #= true
          driveSynd0(dut.meas_in.payload)
          cd.waitSampling()
          dut.meas_in.valid #= false
          cd.waitSampling()
          PrintGrid(4, 1, dut.controller.measurements)

          while(! dut.meas_in.ready.toBoolean) { cd.waitSampling() }
          dut.meas_in.valid #= true
          driveSynd1(dut.meas_in.payload)
          cd.waitSampling()
          dut.meas_in.valid #= false
          cd.waitSampling()
          PrintGrid(4, 1, dut.controller.measurements)

          while(! dut.meas_in.ready.toBoolean) { cd.waitSampling() }
          dut.meas_in.valid #= true
          driveSynd2(dut.meas_in.payload)
          cd.waitSampling()
          dut.meas_in.valid #= false
          cd.waitSampling()
          PrintGrid(4, 1, dut.controller.measurements)
        } else {
          cd.waitSampling()
        }
      }
    }
    // dut.meas_in.valid #= true
    // driveSynd0(dut.meas_in.payload)
    // cd.waitSampling()
    // driveSynd1(dut.meas_in.payload)


    for(i <- 0 until 100) {
      val res = dut.output.payload
      val valid = dut.output.valid.toBoolean
      val ready = dut.meas_in.ready.toBoolean
      val stage = dut.controller.global_stage.toBigInt
      println(s"t: ${i}, output.valid: ${valid}, input.ready: ${ready}, stage: ${stage}")
      if(valid) {
        PrintGrid(3, 3, res)
        println(s"t: ${i}")
        // for(k <- 0 until 3) {
        //   for(i <- 0 until 3) {
        //       println(s"${dut.corrections.payload.ew_tail(k)(i)(0).toBoolean}")
        //   }
        // }
      }
      cd.waitSampling()
    }
  }
}