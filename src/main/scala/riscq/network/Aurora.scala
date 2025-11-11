package riscq.network

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.tilelink.fabric._
import spinal.lib.bus.tilelink
import spinal.core.fiber.Fiber

  //   parameter SFP_SELECT = 0,
  //   parameter DATA_WIDTH = 64,
	// parameter STRB_WIDTH = 8,
	// parameter FRAME_SIZE_AW = 4 // FRAME_SIZE_AW ≤ SFIFO_AW
case class gty_sfp_wrapper_slc_io(SFP_SELECT: Int = 0, DATA_WIDTH: Int = 64, STRB_WIDTH: Int = 8, FRAME_SIZE_AW: Int = 4) extends Bundle {
  val RESET = in Bool()
  val HARD_ERR = out Bool()
  val SOFT_ERR = out Bool()
  val LANE_UP = out Bool()
  val CHANNEL_UP = out Bool()
  val PMA_INIT = in Bool()
  val INIT_CLK = in Bool() 
  val REF_CLK = in Bool()
  val DSP_CLK = in Bool() // clock for data/valid
  val CRC_PASS_FAIL_N = out Bool() // unused
  val CRC_VALID = out Bool() // unused
  val RXP = in Bool()
  val RXN = in Bool()
  val TXP = out Bool()
  val TXN = out Bool()
  val FRAME_SIZE = in Bits(FRAME_SIZE_AW bits)
  val TKEEP_LAST = in Bits(STRB_WIDTH bits)
  val DATA_D2A = in Bits(DATA_WIDTH bits) // tx
  val VALID_D2A = in Bool() // tx
  val DATA_A2D = out Bits(DATA_WIDTH bits) // rx
  val VALID_A2D = out Bool() // rx
  val MMCM_NOT_LOCKED_OUT = out Bool() // to sfp 1
  val USER_CLK_OUT = out Bool() // to sfp 1
  val SYNC_CLK_OUT = out Bool() // to sfp 1
  val SYS_RESET_OUT = out Bool() // to sfp 1
  val GT_RESET_OUT = out Bool() // to pma init of sfp 1
  val GT_QPLLCLK_QUAD1_OUT = out Bool() // to sfp 1
  val GT_QPLLREFCLK_QUAD1_OUT = out Bool() // to sfp 1
  val GT_QPLLLOCK_QUAD1_OUT = out Bool() // to sfp 1
  val GT_QPLLREFCLKLOST_QUAD1_OUT = out Bool() // to sfp 1
	// input                       RESET,
  //   output                      HARD_ERR,
  //   output                      SOFT_ERR,
  //   output                      LANE_UP,
  //   output                      CHANNEL_UP,
  //   input                       PMA_INIT,
  //   input                       INIT_CLK,
  //   input                       REF_CLK,
  //   input                       DSP_CLK,
  //   output                      CRC_PASS_FAIL_N,
  //   output                      CRC_VALID,
  //   input                       RXP,
  //   input                       RXN,
  //   output                      TXP,
  //   output                      TXN,
  //   //output                      REC_CLK,
  //   input [(FRAME_SIZE_AW-1):0] FRAME_SIZE, // FRAME_SIZE+2: quantity of frames to be sent; FRAME_SIZE=0: single cycle frame
  //   input [(STRB_WIDTH-1):0]    TKEEP_LAST,
  //   input [(DATA_WIDTH-1):0]    DATA_D2A,
  //   input                       VALID_D2A,
  //   output [(DATA_WIDTH-1):0]   DATA_A2D,
  //   output                      VALID_A2D,
  //   output                      MMCM_NOT_LOCKED_OUT,
  //   output                      USER_CLK_OUT,
  //   output                      SYNC_CLK_OUT,
  //   output                      SYS_RESET_OUT,
  //   output                      GT_RESET_OUT,
  //   output                      GT_QPLLCLK_QUAD1_OUT,
  //   output                      GT_QPLLREFCLK_QUAD1_OUT,
  //   output                      GT_QPLLLOCK_QUAD1_OUT,
  //   output                      GT_QPLLREFCLKLOST_QUAD1_OUT
}

case class gty_sfp_wrapper_slc(SFP_SELECT: Int = 0, DATA_WIDTH: Int = 64, STRB_WIDTH: Int = 8, FRAME_SIZE_AW: Int = 4) extends BlackBox {
  val io = gty_sfp_wrapper_slc_io(SFP_SELECT, DATA_WIDTH, STRB_WIDTH, FRAME_SIZE_AW)
  noIoPrefix()
  addGeneric("SFP_SELECT", SFP_SELECT)
  addGeneric("DATA_WIDTH", DATA_WIDTH)
  addGeneric("STRB_WIDTH", STRB_WIDTH)
  addGeneric("FRAME_SIZE_AW", FRAME_SIZE_AW)
}

case class AuroraTester(SFP_SELECT: Int = 0, DATA_WIDTH: Int = 64, STRB_WIDTH: Int = 8, FRAME_SIZE_AW: Int = 4) extends Component {
  val mgtrefclk1_128_clk_p = in Bool ()
  val mgtrefclk1_128_clk_n = in Bool ()
  val mgtrefclk1_IBUFDSGTE4 = IBUFDS_GTE4()
  mgtrefclk1_IBUFDSGTE4.I := mgtrefclk1_128_clk_p
  mgtrefclk1_IBUFDSGTE4.IB := mgtrefclk1_128_clk_n
  mgtrefclk1_IBUFDSGTE4.CEB := False
  val mgtrefclk1_128 = mgtrefclk1_IBUFDSGTE4.O

  val io = new Bundle {
    val reset = in Bool()
    val gty_rxp_0 = in Bool()
    val gty_rxn_0 = in Bool()
    val gty_txp_0 = out Bool()
    val gty_txn_0 = out Bool()
    val frame_size = in Bits(FRAME_SIZE_AW bits)
    // val tkeep_last = in Bits(STRB_WIDTH bits)
    val tx_data = in Bits(DATA_WIDTH bits)
    val tx_valid = in Bool()
    val rx_data = out Bits(DATA_WIDTH bits)
    val rx_valid = out Bool()
  }

  val gt = gty_sfp_wrapper_slc()
  gt.io.RESET := io.reset
  gt.io.PMA_INIT := io.reset
  gt.io.INIT_CLK := ClockDomain.current.readClockWire
  gt.io.REF_CLK := mgtrefclk1_128
  gt.io.DSP_CLK := ClockDomain.current.readClockWire
  gt.io.RXP := io.gty_rxp_0
  gt.io.RXN := io.gty_rxn_0
  io.gty_txp_0 := gt.io.TXP
  io.gty_txn_0 := gt.io.TXN
  gt.io.FRAME_SIZE := io.frame_size
  // gt.io.TKEEP_LAST := io.tkeep_last
  // gt.io.FRAME_SIZE := 0
  gt.io.TKEEP_LAST := 0xff
  gt.io.DATA_D2A := io.tx_data
  gt.io.VALID_D2A := io.tx_valid
  io.rx_data := gt.io.DATA_A2D
  io.rx_valid := gt.io.VALID_A2D
}

case class AuroraTesterTop() extends Component {
  val axiConfig = Axi4Config(
    addressWidth = 32,
    dataWidth = 32,
    idWidth = 2
  )
  val io = new Bundle {
    val axi = slave(Axi4(axiConfig))
    riscq.misc.Axi4VivadoHelper.addInference(axi, "S_AXIS")
    val gty_rxp_0 = in Bool()
    val gty_rxn_0 = in Bool()
    val gty_txp_0 = out Bool()
    val gty_txn_0 = out Bool()
    val mgtrefclk1_128_clk_p = in Bool ()
    val mgtrefclk1_128_clk_n = in Bool ()
    val ledR = out Bool()
  }
  io.mgtrefclk1_128_clk_p.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk1_128_diff CLK_P ")
  io.mgtrefclk1_128_clk_n.addAttribute("X_INTERFACE_INFO", "xilinx.com:interface:diff_clock:1.0 mgtrefclk1_128_diff CLK_N ")

  val aurora = AuroraTester()
  aurora.mgtrefclk1_128_clk_p := io.mgtrefclk1_128_clk_p
  aurora.mgtrefclk1_128_clk_n := io.mgtrefclk1_128_clk_n
  aurora.io.gty_rxp_0 := io.gty_rxp_0
  aurora.io.gty_rxn_0 := io.gty_rxn_0
  io.gty_txp_0 := aurora.io.gty_txp_0
  io.gty_txn_0 := aurora.io.gty_txn_0
  val txDataLow = Reg(Flow(Bits(32 bits)))
  val txDataHigh = Reg(Bits(32 bits))
  aurora.io.tx_data(31 downto 0) := txDataLow.payload
  aurora.io.tx_data(63 downto 32) := txDataHigh
  aurora.io.tx_valid := txDataLow.valid
  val rxDataLow = RegNextWhen(aurora.io.rx_data(31 downto 0), aurora.io.rx_valid)
  val rxDataHigh = RegNextWhen(aurora.io.rx_data(63 downto 32), aurora.io.rx_valid)

  val bridge = new Axi4ToTilelinkFiber(blockSize = 32, slotsCount = 4)
  bridge.up load io.axi
  val hostBus = bridge.down
  hostBus.setDownConnection(a = StreamPipe.FULL, d = StreamPipe.FULL)

  val driverNode = Node.up()
  val dataWidth = 32
  driverNode at 0 of hostBus
  val driverLogic = Fiber build new Area {
    driverNode.m2s.supported load driverNode.m2s.proposed.copy(
    addressWidth = 16,
    dataWidth = dataWidth,
    transfers = driverNode.m2s.proposed.transfers.intersect(
        tilelink.M2sTransfers(
          get = tilelink.SizeRange.upTo(dataWidth / 8),
          putFull = tilelink.SizeRange(dataWidth / 8),
          putPartial = tilelink.SizeRange(dataWidth / 8),
        )
      )
    )
    driverNode.s2m.none()

    val factory = new tilelink.SlaveFactory(driverNode.bus, false)

    val auroraReset = Bool()
    io.ledR := auroraReset
    aurora.io.reset := auroraReset
    factory.drive(auroraReset, 0)
    factory.read(auroraReset, 4)
    factory.driveFlow(txDataLow, 8)
    factory.drive(txDataHigh, 12)
    factory.read(rxDataLow, 16)
    factory.read(rxDataHigh, 20)
    factory.drive(aurora.io.frame_size, 24)
  }
}

object AuroraTesterTop extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "./build/rtl/aurora",
    romReuse = true
  ).generate(
    AuroraTesterTop()
  )
}

