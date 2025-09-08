package riscq.network

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber

case class EmmetCore() extends GtCore(true) {

  // val init_clk = in Bool ()
  val initCd = ClockDomain.current
  initCd.renamePulledWires("init_clk")

  val txCd = ClockDomain(io.txClk)
  val rxCd = ClockDomain(io.rxClk)


  // Reset/Initialization registers
  val reset_cycle_count = txCd(Reg(UInt(10 bits)))
  val resetstate_n_initclk = Reg(Bool())
  val resetstate_n_intermediate = txCd(RegNext(resetstate_n_initclk)) // use BufferCC instead?
  resetstate_n_intermediate.addTag(crossClockDomain)
  val resetstate_n_txuserclk = txCd(Reg(Bool()))

  val coreresetdone_rxclk = rxCd(Reg(Bool()))
  val coreresetdone_txclkmeta = txCd(RegNext(coreresetdone_rxclk)) // use BufferCC instead?
  coreresetdone_txclkmeta.addTag(crossClockDomain)
  val coreresetdone_txclkstable = txCd(RegNext(coreresetdone_txclkmeta)) // use BufferCC instead?

  val partner_coreresetdone_rxclk = rxCd(Reg(Bool()))
  val partner_coreresetdone_txclkmeta = txCd(RegNext(partner_coreresetdone_rxclk)) // use BufferCC instead?
  partner_coreresetdone_txclkmeta.addTag(crossClockDomain)
  val partner_coreresetdone_txclkstable = txCd(RegNext(partner_coreresetdone_txclkmeta))

  // Constants
  val LFSR_SEED = B"64'hE9734555354A526D"
  val RESET_SIGNAL = B"64'hAA8A8BCD4E95FD3C"
  val POLARITY_CHECK = B"64'hB4A5D4AE376C9323"
  val PARITY_KEY = B"64'hD5F4A1B3E6C9D2F1"
  val SCRAMBLE_KEY = B"64'h56B68EA4B5CCA8B4"

  // Initialize clock domain areas
  val initCdArea = new ClockingArea(initCd) {
    when(!io.reset_n || !io.tx.resetdone || !io.rx.resetdone) {
      resetstate_n_initclk := False
    } elsewhen (!resetstate_n_initclk) {
      resetstate_n_initclk := True
    }
  }

  // Hamming encode function
  def hamming_encode(message_to_encode: Bits, parity_register: Bits): Unit = {
    parity_register(63 downto 40) := parity_register(55 downto 32)
    parity_register(31 downto 8) := parity_register(23 downto 0)

    // Parity bit 0 and 32
    parity_register(0) := (1 to 63 by 2).map(i => message_to_encode(i)).reduce(_ ^ _)
    parity_register(32) := (1 to 63 by 2).map(i => message_to_encode(i)).reduce(_ ^ _)

    // Parity bit 1 and 33
    parity_register(1) := (2 to 62 by 4).map(i => message_to_encode(i + 1 downto i).xorR).reduce(_ ^ _)
    parity_register(33) := (2 to 62 by 4).map(i => message_to_encode(i + 1 downto i).xorR).reduce(_ ^ _)

    // Parity bit 2 and 34
    parity_register(2) := (4 to 60 by 8).map(i => message_to_encode(i + 3 downto i).xorR).reduce(_ ^ _)
    parity_register(34) := (4 to 60 by 8).map(i => message_to_encode(i + 3 downto i).xorR).reduce(_ ^ _)

    // Parity bit 3 and 35
    parity_register(3) := (8 to 56 by 16).map(i => message_to_encode(i + 7 downto i).xorR).reduce(_ ^ _)
    parity_register(35) := (8 to 56 by 16).map(i => message_to_encode(i + 7 downto i).xorR).reduce(_ ^ _)

    // Parity bit 4 and 36
    parity_register(4) := (16 to 48 by 32).map(i => message_to_encode(i + 15 downto i).xorR).reduce(_ ^ _)
    parity_register(36) := (16 to 48 by 32).map(i => message_to_encode(i + 15 downto i).xorR).reduce(_ ^ _)

    // Parity bit 5 and 37
    parity_register(5) := message_to_encode(63 downto 32).xorR
    parity_register(37) := message_to_encode(63 downto 32).xorR

    // Parity bit 6 and 38
    parity_register(6) := message_to_encode(0)
    parity_register(38) := message_to_encode(0)

    // Parity bit 7 and 39
    parity_register(7) := message_to_encode.xorR
    parity_register(39) := message_to_encode.xorR
  }

  // Transmitter clock domain
  val txCdArea = new ClockingArea(txCd) {
    // LFSR registers
    val lfsr_reg = Reg(Bits(64 bits)) 

    // Transmitter registers
    val increment_sequence = Reg(Bool())
    increment_sequence := !increment_sequence
    val sequence_pause_next = Bool()
    val tx_parity_reg = Reg(Bits(64 bits))
    val tx_datablockcount = Reg(UInt(3 bits))

    val tx_ready = Reg(Bool())
    io.txCmd.ready := tx_ready

    val tx_userdata_out = Reg(Bits(64 bits))
    val tx_header_out = Reg(Bits(2 bits))
    val tx_sequence_out = Reg(UInt(7 bits))
    io.tx.userdata_out := tx_userdata_out
    io.tx.header_out := tx_header_out
    io.tx.sequence_out := tx_sequence_out

    // !!!
    val txCd_reset = BufferCC(!io.reset_n)
    when(txCd_reset) {
      lfsr_reg := 0
      increment_sequence := False
      tx_sequence_out := 0
      tx_userdata_out := 0
      tx_parity_reg := 0
      tx_datablockcount := 0
    }
    /// !!!

    val feedback = lfsr_reg(63) ^ lfsr_reg(62) ^ lfsr_reg(60) ^ lfsr_reg(59)

    def cycle_lfsr(): Unit = {
      when(lfsr_reg === 0) {
        lfsr_reg := LFSR_SEED
        tx_userdata_out := LFSR_SEED
      } otherwise {
        lfsr_reg := lfsr_reg(62 downto 0) ## feedback
        tx_userdata_out := lfsr_reg ^ SCRAMBLE_KEY
      }
    }

    // Sequence counter
    when(increment_sequence) {
      when(tx_sequence_out >= 0x20) {
        tx_sequence_out := 0
      } otherwise {
        tx_sequence_out := tx_sequence_out + 1
      }
    }

    sequence_pause_next := ((tx_sequence_out === 0x1f) && increment_sequence) || ((tx_sequence_out === 0x20) && !increment_sequence)

    // Reset state logic
    val reset_cond = !resetstate_n_txuserclk || !coreresetdone_txclkstable || !partner_coreresetdone_txclkstable || reset_cycle_count > 0 // only for debug
    when(
      !resetstate_n_txuserclk || !coreresetdone_txclkstable || !partner_coreresetdone_txclkstable || reset_cycle_count > 0
    ) {
      tx_ready := False
      when(reset_cycle_count >= 0x200 && !sequence_pause_next) {
        reset_cycle_count := 0
        when(coreresetdone_txclkstable) {
          tx_header_out := B"2'b00"
          tx_userdata_out := POLARITY_CHECK
          when(partner_coreresetdone_txclkstable) {
            resetstate_n_txuserclk := resetstate_n_intermediate
          }
        } otherwise {
          tx_header_out := B"2'b01"
          cycle_lfsr()
        }
      } otherwise {
        reset_cycle_count := reset_cycle_count + 1
        when(reset_cycle_count === 0 && !coreresetdone_txclkstable) {
          tx_header_out := B"2'b00"
          tx_userdata_out := RESET_SIGNAL
        } otherwise {
          tx_header_out := B"2'b01"
          cycle_lfsr()
        }
      }
      tx_parity_reg := B"64'h8080808080808080"
      tx_datablockcount := 0
    } otherwise {
      // Normal operation
      resetstate_n_txuserclk := resetstate_n_intermediate
      reset_cycle_count := 0

      when(io.gtpowergood) {
        // TX ready logic
        when(
          tx_sequence_out === 0x1f || (tx_datablockcount === 3 && tx_ready && io.txCmd.valid) || (tx_datablockcount === 4 && sequence_pause_next)
        ) {
          tx_ready := False
        } otherwise {
          tx_ready := True
        }

        // Data transmission
        when(io.txCmd.valid && tx_ready) {
          tx_userdata_out := io.txCmd.payload ^ SCRAMBLE_KEY
          tx_header_out := B"2'b10"
          hamming_encode(io.txCmd.payload, tx_parity_reg)
          tx_datablockcount := tx_datablockcount + 1
        } otherwise {
          when(!sequence_pause_next && (tx_header_out === B"2'b10" || tx_datablockcount =/= 0)) {
            tx_userdata_out := tx_parity_reg ^ PARITY_KEY
            tx_header_out := B"2'b01"
            tx_datablockcount := 0
          } otherwise {
            cycle_lfsr()
            tx_header_out := B"2'b01"
          }
        }
      } otherwise {
        // Waiting for transceiver
        cycle_lfsr()
        tx_header_out := B"2'b01"
        tx_ready := False
      }
    }
  }

  // Receiver clock domain
  val rxCdArea = new ClockingArea(rxCd) {
    val rx_gearboxslip = Reg(Bool())
    io.rx.gearboxslip := rx_gearboxslip
    val rx_polarity = Reg(Bool())
    io.rx.polarity := rx_polarity
    // Receiver registers
    val rx_dataqueue_1 = Reg(Bits(64 bits))
    val rx_dataqueue_2 = Reg(Bits(64 bits))
    val rx_dataqueue_3 = Reg(Bits(64 bits))
    val rx_dataqueue_4 = Reg(Bits(64 bits))
    val queuesize = Reg(UInt(3 bits))
    val queuecorrected = Reg(UInt(3 bits))
    val queuediscard = Reg(Bits(4 bits))
    val queueinit = Reg(Bool())
    val rx_parity_reg = Reg(Bits(64 bits))
    val gearboxaligned_count = Reg(UInt(7 bits))
    val prev_header = Reg(Bits(2 bits))
    val prev_data = Reg(Bits(64 bits))
    val consecutiveidenticalblock_count = Reg(UInt(10 bits))

    val rx_data = Reg(Bits(64 bits))
    io.rxRsp.payload := rx_data

    val rx_valid = Reg(Bool())
    io.rxRsp.valid := rx_valid

    val error_flag = Reg(Bool())
    // io.error_flag := error_flag

    // !!!
    val rxCd_reset = BufferCC(!io.reset_n)
    when(rxCd_reset) {
      partner_coreresetdone_rxclk := False
      rx_polarity := False
      rx_gearboxslip := False
      rx_dataqueue_1 := 0
      rx_dataqueue_2 := 0
      rx_dataqueue_3 := 0
      rx_dataqueue_4 := 0
      queuesize := 0
      queuecorrected := 0
      queuediscard := 0
      queueinit := False
      rx_parity_reg := 0
      gearboxaligned_count := 0
      prev_header := 0
      prev_data := 0
      consecutiveidenticalblock_count := 0
    }
    /// !!!

    // Queue management functions
    def shift_queue(): Unit = {
      hamming_encode(io.rx.userdata_in ^ SCRAMBLE_KEY, rx_parity_reg)

      rx_dataqueue_1 := io.rx.userdata_in ^ SCRAMBLE_KEY
      when(!queueinit || queuesize === 0) {
        queueinit := True
        queuesize := 1
        queuecorrected := 0
        rx_valid := False
      } elsewhen (queuecorrected > 0 || queuesize >= 4) {
        when(queuesize > 1) {
          rx_dataqueue_2 := rx_dataqueue_1
          when(queuesize > 2) {
            rx_dataqueue_3 := rx_dataqueue_2
            when(queuesize > 3) {
              rx_dataqueue_4 := rx_dataqueue_3
              rx_data := rx_dataqueue_4
            } otherwise {
              rx_data := rx_dataqueue_3
            }
          } otherwise {
            rx_data := rx_dataqueue_2
          }
        } otherwise {
          rx_data := rx_dataqueue_1
        }
        rx_valid := (queuecorrected > 0 && !queuediscard(3))
        when(queuecorrected > 0) {
          queuecorrected := queuecorrected - 1
        }
        queuediscard := queuediscard |<< 1
      } otherwise {
        queuesize := queuesize + 1
        rx_dataqueue_4 := rx_dataqueue_3
        rx_dataqueue_3 := rx_dataqueue_2
        rx_dataqueue_2 := rx_dataqueue_1
        rx_valid := False
      }
    }

    def dequeue_only(): Unit = {
      when(queuesize > 0 && queuecorrected > 0) {
        queuesize := queuesize - 1
        switch(queuesize) {
          is(4) { rx_data := rx_dataqueue_4 }
          is(3) { rx_data := rx_dataqueue_3 }
          is(2) { rx_data := rx_dataqueue_2 }
          is(1) { rx_data := rx_dataqueue_1 }
        }
        rx_valid := (queuecorrected > 0 && !queuediscard(3))
        queuediscard := queuediscard |<< 1
        queuecorrected := queuecorrected - 1
      } otherwise {
        rx_valid := False
      }
    }

    when(io.gtpowergood) {
      when(io.rx.headervalid_in === io.rx.datavalid_in) {
        // Count consecutive identical blocks
        when(io.rx.datavalid_in) {
          prev_header := io.rx.header_in
          prev_data := io.rx.userdata_in
          when(prev_header === io.rx.header_in && prev_data === io.rx.userdata_in) {
            consecutiveidenticalblock_count := consecutiveidenticalblock_count + 1
          } otherwise {
            consecutiveidenticalblock_count := 0
          }
        }

        // Partner shutdown detection
        when(partner_coreresetdone_rxclk && consecutiveidenticalblock_count > 0x200) {
          consecutiveidenticalblock_count := 0
          coreresetdone_rxclk := False
          partner_coreresetdone_rxclk := False
          error_flag := True
        } otherwise {
          switch(io.rx.header_in) {
            is(B"2'b10") {
              // Data header
              rx_gearboxslip := False
              when(io.rx.headervalid_in && io.rx.userdata_in =/= prev_data && !coreresetdone_rxclk) {
                gearboxaligned_count := gearboxaligned_count + 1
              } otherwise {
                gearboxaligned_count := 0
              }
              when(io.rx.headervalid_in && coreresetdone_rxclk) {
                shift_queue()
              } otherwise {
                dequeue_only()
              }
              error_flag := False
            }
            is(B"2'b01") {
              // Control header
              rx_gearboxslip := False
              rx_valid := False
              when(io.rx.userdata_in =/= prev_data && !coreresetdone_rxclk) {
                gearboxaligned_count := gearboxaligned_count + 1
              }
              when(gearboxaligned_count > 0x40) {
                gearboxaligned_count := 0
                coreresetdone_rxclk := True
              }
              when(coreresetdone_rxclk) {
                when(io.rx.headervalid_in && prev_header === B"2'b10") {
                  rx_valid := False
                  queuecorrected := queuesize
                  val parity_data_in = io.rx.userdata_in ^ PARITY_KEY
                  when(parity_data_in =/= rx_parity_reg) {
                    // Error correction logic - corrects single bit flips, detects double bit flips
                    when(
                      (queuesize - queuecorrected) > 0 && parity_data_in(38 downto 32) === parity_data_in(
                        6 downto 0
                      ) && parity_data_in(6 downto 0) =/= rx_parity_reg(6 downto 0)
                    ) {
                      when(
                        parity_data_in(39 downto 32) === parity_data_in(7 downto 0) && parity_data_in(
                          7 downto 0
                        ) === B"8'b10000000"
                      ) {
                        queuediscard(0) := True
                      } elsewhen (parity_data_in(39) === parity_data_in(7) && parity_data_in(7) === rx_parity_reg(7)) {
                        error_flag := True
                      } otherwise {
                        rx_dataqueue_1 := rx_dataqueue_1 ^ (B"64'h1" |<< (parity_data_in(5 downto 0) ^ rx_parity_reg(
                          5 downto 0
                        )).asUInt)
                      }
                    }
                    when(
                      (queuesize - queuecorrected) > 1 && parity_data_in(46 downto 40) === parity_data_in(
                        14 downto 8
                      ) && parity_data_in(14 downto 8) =/= rx_parity_reg(14 downto 8)
                    ) {
                      when(
                        parity_data_in(47 downto 40) === parity_data_in(15 downto 8) && parity_data_in(
                          15 downto 8
                        ) === B"8'b10000000"
                      ) {
                        queuediscard(1) := True
                      } elsewhen (parity_data_in(47) === parity_data_in(15) && parity_data_in(15) === rx_parity_reg(
                        15
                      )) {
                        error_flag := True
                      } otherwise {
                        rx_dataqueue_2 := rx_dataqueue_2 ^ (B"64'h1" |<< (parity_data_in(13 downto 8) ^ rx_parity_reg(
                          13 downto 8
                        )).asUInt)
                      }
                    }
                    when(
                      (queuesize - queuecorrected) > 2 && parity_data_in(54 downto 48) === parity_data_in(
                        22 downto 16
                      ) && parity_data_in(22 downto 16) =/= rx_parity_reg(22 downto 16)
                    ) {
                      when(
                        parity_data_in(55 downto 48) === parity_data_in(23 downto 16) && parity_data_in(
                          23 downto 16
                        ) === B"8'b10000000"
                      ) {
                        queuediscard(2) := True
                      } elsewhen (parity_data_in(55) === parity_data_in(23) && parity_data_in(23) === rx_parity_reg(
                        23
                      )) {
                        error_flag := True
                      } otherwise {
                        rx_dataqueue_3 := rx_dataqueue_3 ^ (B"64'h1" |<< (parity_data_in(21 downto 16) ^ rx_parity_reg(
                          21 downto 16
                        )).asUInt)
                      }
                    }
                    when(
                      (queuesize - queuecorrected) > 3 && parity_data_in(62 downto 56) === parity_data_in(
                        30 downto 24
                      ) && parity_data_in(30 downto 24) =/= rx_parity_reg(30 downto 24)
                    ) {
                      when(
                        parity_data_in(63 downto 56) === parity_data_in(31 downto 24) && parity_data_in(
                          31 downto 24
                        ) === B"8'b10000000"
                      ) {
                        queuediscard(3) := True
                      } elsewhen (parity_data_in(63) === parity_data_in(31) && parity_data_in(31) === rx_parity_reg(
                        31
                      )) {
                        error_flag := True
                      } otherwise {
                        rx_dataqueue_4 := rx_dataqueue_4 ^ (B"64'h1" |<< (parity_data_in(29 downto 24) ^ rx_parity_reg(
                          29 downto 24
                        )).asUInt)
                      }
                    }
                  } otherwise {
                    error_flag := False
                    dequeue_only()
                  }
                } otherwise {
                  dequeue_only()
                  error_flag := False
                }
              }
            }
            default {
              // 2'b00 and 2'b11 handling
              when(coreresetdone_rxclk) {
                dequeue_only()
              }
              when(io.rx.headervalid_in) {
                when(io.rx.header_in === B"2'b11" && io.rx.userdata_in === ~POLARITY_CHECK) {
                  rx_polarity := !rx_polarity
                  rx_gearboxslip := False
                  partner_coreresetdone_rxclk := True
                } elsewhen (io.rx.header_in === B"2'b00" && io.rx.userdata_in === POLARITY_CHECK) {
                  rx_gearboxslip := False
                  partner_coreresetdone_rxclk := True
                } elsewhen (io.rx.header_in === B"2'b11" && io.rx.userdata_in === ~RESET_SIGNAL) {
                  rx_polarity := !rx_polarity
                  rx_gearboxslip := False
                  when(partner_coreresetdone_rxclk) {
                    coreresetdone_rxclk := False
                    partner_coreresetdone_rxclk := False
                    error_flag := True
                  }
                } elsewhen (io.rx.header_in === B"2'b00" && io.rx.userdata_in === RESET_SIGNAL) {
                  rx_gearboxslip := False
                  when(coreresetdone_rxclk) {
                    coreresetdone_rxclk := False
                    partner_coreresetdone_rxclk := False
                    error_flag := True
                  }
                } otherwise {
                  rx_gearboxslip := True
                  gearboxaligned_count := 0
                  coreresetdone_rxclk := False
                  when(coreresetdone_rxclk || rx_valid) {
                    error_flag := True
                  }
                }
              }
            }
          }
        }
      } otherwise {
        error_flag := True
      }
    }
  }
}

object TestEmmetCore extends App {
  val report = SpinalVerilog(EmmetCore())
// .withVerilator
//     // .addIncludeDir("./src/main/rtl/")
//     // .addRtl("./src/main/rtl/emmetcore.v")
  case class TestTop() extends Component {
    val io = new Bundle {
      val tx_data = in Bits (64 bits)
      val tx_valid = in Bool ()
      val tx_ready = out Bool ()
      val rx_data = out Bits (64 bits)
      val rx_valid = out Bool ()
      val rst_n = in Bool ()
      val fireBuf = in Bool()
    }
    
    // Create a single clock domain for both TX and RX
    // Note: Even though we connect the same clock signal to both tx_userclk and rx_userclk,
    // SpinalHDL will still treat them as different clock domains because they're defined
    // as separate ClockDomain objects in EmmetCore. This is expected behavior.
    val sharedClock = ClockDomain.current.readClockWire
    
    val node1 = new EmmetCore()
    node1.io.reset_n := io.rst_n
    node1.io.txClk := sharedClock
    node1.io.rxClk := sharedClock
    node1.io.gtpowergood := True
    node1.io.tx.resetdone := True
    node1.io.rx.resetdone := True
    node1.io.txCmd.payload := io.tx_data
    node1.io.txCmd.valid := io.tx_valid
    io.tx_ready := node1.io.txCmd.ready

    val node2 = new EmmetCore()
    node2.io.reset_n := io.rst_n
    node2.io.txClk := sharedClock
    node2.io.rxClk := sharedClock
    node2.io.gtpowergood := True
    node2.io.tx.resetdone := True
    node2.io.rx.resetdone := True
    node2.io.txCmd.payload := 0
    node2.io.txCmd.valid := False
    io.rx_data := node2.io.rxRsp.payload
    io.rx_valid := node2.io.rxRsp.valid

    node1.io.rx.userdata_in := node2.io.tx.userdata_out
    node1.io.rx.datavalid_in := True
    node1.io.rx.header_in := node2.io.tx.header_out
    node1.io.rx.headervalid_in := True

    node2.io.rx.userdata_in := node1.io.tx.userdata_out
    node2.io.rx.header_in := node1.io.tx.header_out
    node2.io.rx.datavalid_in := True
    node2.io.rx.headervalid_in := True

    // // Buffer the data from node1 to node2
    // val dataBuf = RegNext(node1.tx_userdata_out)
    // val headerBuf = RegNext(node1.tx_header_out)

    // // Connect to node2 with cross-clock domain handling
    // node2.rx_userdata_in := dataBuf
    // node2.rx_header_in := headerBuf
    // node2.rx_datavalid_in := io.fireBuf
    // node2.rx_headervalid_in := io.fireBuf
    
    // // Add cross-clock domain tags to suppress warnings
    // dataBuf.addTag(crossClockDomain)
    // headerBuf.addTag(crossClockDomain)
    // io.fireBuf.addTag(crossClockDomain)
  }
  SimConfig
    .compile{
      val dut = TestTop()
      dut.node1.io.gtpowergood.simPublic()
      dut.node1.txCdArea.reset_cond.simPublic()
      dut.node1.resetstate_n_txuserclk.simPublic()
      dut.node1.coreresetdone_txclkstable.simPublic()
      dut.node1.partner_coreresetdone_txclkstable.simPublic()
      dut.node1.reset_cycle_count.simPublic()
      dut.node1.io.rx.header_in.simPublic()
      dut.node1.reset_cycle_count.simPublic()
      dut
    }
    .doSim { dut =>
      val cd = dut.clockDomain
      cd.forkStimulus(10)
      cd.assertReset()
      dut.io.tx_valid #= false
      cd.waitRisingEdge(100)
      cd.deassertReset()
      dut.io.rst_n #= false
      cd.waitRisingEdge()
      dut.io.rst_n #= true

      // val initMon = fork {
      //   while(true) {
      //     cd.waitRisingEdge()
      //     println(s"time: ${simTime} rx_header_in: ${dut.node1.rx_header_in.toBigInt}")
      //     println(s"time: ${simTime} reset_cycle_count: ${dut.node1.reset_cycle_count.toBigInt}")
      //   }
      // }

      cd.waitRisingEdge(1024)
      println(s"time: ${simTime} gt_powergood: ${dut.node1.io.gtpowergood.toBoolean}")
          // val reset_cond = !resetstate_n_txuserclk || !coreresetdone_txclkstable || !partner_coreresetdone_txclkstable || reset_cycle_count > 0
      println(s"time: ${simTime} reset_cond: ${dut.node1.txCdArea.reset_cond.toBoolean}")
      println(s"time: ${simTime} resetstate_n_txuserclk: ${dut.node1.resetstate_n_txuserclk.toBoolean}")
      println(s"time: ${simTime} coreresetdone_txclkstable: ${dut.node1.coreresetdone_txclkstable.toBoolean}")
      println(s"time: ${simTime} partner_coreresetdone_txclkstable: ${dut.node1.partner_coreresetdone_txclkstable.toBoolean}")
      println(s"time: ${simTime} reset_cycle_count: ${dut.node1.reset_cycle_count.toBigInt}")

      println(s"time: ${simTime} tx_ready: ${dut.io.tx_ready.toBoolean}")

      
      var data = 123
      val incrData = fork { while(true) { data += 1; cd.waitRisingEdge()} }
      dut.io.tx_data #= data
      dut.io.tx_valid #= true
      cd.waitRisingEdge()

      for(i <- 0 until 20) {
        dut.io.tx_data #= data
        println(s"time: ${simTime} tx_data: ${dut.io.tx_data.toBigInt}, rx_data: ${dut.io.rx_data.toBigInt}, rx_valid: ${dut.io.rx_valid.toBoolean}, tx_ready: ${dut.io.tx_ready.toBoolean}")
        cd.waitRisingEdge()
      }
    }
}

case class EmmetCoreTop() extends GtCoreTop(EmmetCore(), true)

object GenEmmetCoreTop extends App {
  SpinalVerilog(EmmetCoreTop())
}

case class EmmetCoreAxiTop() extends GtCoreAxiTop(EmmetCoreTop())

object GenEmmetCoreAxiTop extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    EmmetCoreAxiTop()
  )
}

// case class EmmetLatencyTester() extends Component {
//   val io = new Bundle {
//     val axi = slave(Axi4(Axi4Config(
//       addressWidth = 32,
//       dataWidth = 32,
//       idWidth = 2
//     )))
//     val gt = GtPins()
//     val ledR = out Bool()
//     val dspClk = in Bool()
//   }

//   riscq.misc.Axi4VivadoHelper.addInference(io.axi, "S_AXIS")
//   io.gt.mgtrefclk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_P ")
//   io.gt.mgtrefclk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk_diff CLK_N ")
//   val reset_n = Reg(Bool()) init False
//   io.ledR := reset_n

//   val core = EmmetCoreTop()
//   core.io.gt <> io.gt
//   core.io.reset_n := reset_n

//   val rxBuffer = BufferCC(core.io.rxRsp)
//   val rxRsp = Reg(core.io.rxRsp.payload)
//   when(rxBuffer.valid) {
//     rxRsp := rxBuffer.payload
//   }

//   val txCd = ClockDomain(core.io.tx_userclk)
//   val txBuffer = StreamFifoCC(core.io.txCmd.payload, 4, ClockDomain.current, txCd)
//   val txBufferLow = Stream(Bits(32 bits))
//   val txBufferHigh = Reg(Bits(32 bits)) init 0
//   txBuffer.io.push.valid := txBufferLow.valid
//   txBuffer.io.push.payload := txBufferHigh ## txBufferLow.payload
//   txBufferLow.ready := txBuffer.io.push.ready

//   val rxCd = ClockDomain(core.io.rx_userclk)
//   val rxReset = rxCd(BufferCC(ClockDomain.current.readResetWire))
//   val rxResetCd = ClockDomain(core.io.rx_userclk, rxReset)
//   val rxToTxBuffer = StreamFifoCC(Bits(32 bits), 8, rxResetCd, txCd)
//   rxToTxBuffer.io.push.payload := core.io.rxRsp.payload.resized
//   rxToTxBuffer.io.push.valid := core.io.rxRsp.valid
//   rxToTxBuffer.io.pop.ready := True

//   val startLoop = Bool()
//   val txArea = new ClockingArea(txCd) {
//     val time = Reg(UInt(32 bits))
//     time := time + 1
//     val prevTime = Reg(UInt(32 bits))
//     val latency = Reg(UInt(32 bits))

//     when(rxToTxBuffer.io.pop.valid) {
//       latency := time - prevTime
//       prevTime := time
//     }

//     val txStartLoop = BufferCC(startLoop)
//     core.io.txCmd.valid := txBuffer.io.pop.valid
//     core.io.txCmd.payload := txBuffer.io.pop.payload
//     txBuffer.io.pop.ready := core.io.txCmd.ready
//     when(txStartLoop) {
//       core.io.txCmd.valid := txBuffer.io.pop.valid || rxToTxBuffer.io.pop.valid
//     }
//   }


//   val driver = AxiToTileLinkDriver(factory => {
//     factory.drive(reset_n, 0)
//     factory.read(reset_n, 0)
//     factory.driveStream(txBufferLow, 4)
//     factory.drive(txBufferHigh, 8)
//     factory.read(rxRsp(0, 32 bits), 12)
//     factory.read(rxRsp(32, 32 bits), 16)
//     factory.read(BufferCC(core.gt.io.rxresetdone_out.pull()), 32)
//     factory.read(BufferCC(core.gt.io.txresetdone_out.pull()), 36)
//     factory.read(BufferCC(core.gt.io.gtpowergood_out.pull()), 40)
//     factory.read(BufferCC(core.io.txCmd.ready), 44)
//     factory.drive(startLoop, 128)
//     factory.read(BufferCC(txArea.latency), 132)
//   })
//   driver.axi <> io.axi
// }

case class EmmetLatencyTester() extends LatencyTester(EmmetCoreTop())

object GenEmmetLatencyTester extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/",
    romReuse = true
  ).generate(
    EmmetLatencyTester()
  )
}