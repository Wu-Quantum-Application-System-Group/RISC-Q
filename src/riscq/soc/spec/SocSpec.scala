package riscq.soc.spec

import spinal.core.log2Up

/**
 * The one description of a build — cores, each with its channel list — read from the same JSON the
 * python `riscq.spec.SocSpec` loads (`software/configs/<name>.json`), so hardware and software derive
 * every address from one parameter set (specs/universal-control/01 §2.1).
 *
 * Two JSON forms are accepted, exactly as in python: the channel-list form (`"cores": [...]`) and the
 * legacy SocParams form (`qubit_num` + role-named scalars), converted by [[SocSpec.fromLegacy]] into
 * `gate` / `ro` / `demod` channels per core with the `dac_map`/`adc_map` converters or the generic
 * ZCU216 `SocChannelMap` layout. The python converter is the reference; `PrintSocMap` + the
 * `test_spec_scala.py` diff keep the two in step.
 */
case class ChannelSpec(
    name: String,
    kind: String,            // "pulse" (DAC-bound drive, 16 lanes) | "demod" (ADC-bound carrier + decoder, 4 lanes)
    slots: Int,
    envDepth: Int,
    interp: Int,
    dac: Option[Int],
    adc: Option[Int],
    trace: Boolean           // this channel's fires trigger the shared readout trace (robs)
) {
  require(SocSpec.kindLanes.contains(kind), s"channel '$name': unknown kind '$kind'")
  require(lanes % interp == 0, s"channel '$name': interp $interp must divide $lanes lanes")
  require((kind == "pulse") == dac.isDefined && (kind == "demod") == adc.isDefined,
    s"channel '$name': a pulse channel names a dac, a demod channel an adc, a dio channel neither")
  require((envDepth == 0) == (kind == "dio"), s"channel '$name': env_depth is 0 exactly for a dio channel")
  def lanes: Int = SocSpec.kindLanes(kind)
  def envWidth: Int = lanes * 2 * SocSpec.dataWidth / interp   // bits per stored (interpolated) line
  def samplesPerLine: Int = lanes / interp
  def lineBytes: Int = envWidth / 8
  def envBytes: Int = envWidth * envDepth / 8
}

case class CoreSpec(
    name: String,
    role: String,
    memDepth: Int,
    withMul: Boolean,
    queueDepth: Int,
    channels: Seq[ChannelSpec]
) {
  require(channels.nonEmpty, s"core '$name': a core needs at least one channel")
  require(channels.map(_.name).distinct.size == channels.size, s"core '$name': duplicate channel names")
  def index(name: String): Int = {
    val i = channels.indexWhere(_.name == name)
    require(i >= 0, s"core '${this.name}' has no channel '$name' (have ${channels.map(_.name)})")
    i
  }
  def channel(name: String): ChannelSpec = channels(index(name))
  def find(name: String): Option[ChannelSpec] = channels.find(_.name == name)
}

case class SocSpec(
    name: String,
    dspFreqHz: Double,
    dacNum: Int,
    adcNum: Int,
    linkPipe: Int,
    hostwinBits: Int,
    robDepth: Int,
    adcPipe: Int,
    cores: Seq[CoreSpec],
    withWhiteRabbit: Boolean = false,     // White Rabbit node window + phy ports (specs/white-rabbit/06)
    wrMarkerDac: Option[Int] = None,      // spare DAC carrying the sync marker (white-rabbit/09)
    board: Int = 0,                       // this board's id in the system (0 = the barrier root)
    boards: Int = 1                       // boards in the system (> 1 needs the WR lane: cross-core/02 §5)
) {
  require(0 <= board && board < boards && boards <= 16, s"board $board of $boards")
  require(boards == 1 || withWhiteRabbit, "a multi-board system carries its puts on the White Rabbit lane")
  require(wrMarkerDac.forall(d => withWhiteRabbit && d >= 0 && d < dacNum),
    s"wr_marker_dac=$wrMarkerDac needs with_white_rabbit and a valid DAC id")
  require(cores.nonEmpty, "a SocSpec needs at least one core")
  require(cores.map(_.name).distinct.size == cores.size, "duplicate core names")
  for (c <- cores; ch <- c.channels) {
    ch.dac.foreach(d => require(0 <= d && d < dacNum, s"${c.name}/${ch.name}: dac $d outside dac_num $dacNum"))
    ch.adc.foreach(a => require(0 <= a && a < adcNum, s"${c.name}/${ch.name}: adc $a outside adc_num $adcNum"))
  }

  def qubitNum: Int = cores.length
  def maxChannels: Int = cores.map(_.channels.length).max

  // ── legacy uniform views (what PulseTableSoc still takes; P1 makes the SoC read the lists) ──
  private def uniform[T](vals: Seq[T], what: String): T = {
    require(vals.forall(_ == vals.head), s"$what differs across cores/channels ($vals); use the per-core spec")
    vals.head
  }
  def memDepth: Int = uniform(cores.map(_.memDepth), "mem_depth")
  def withMul: Boolean = uniform(cores.map(_.withMul), "with_mul")
  def queueDepth: Int = uniform(cores.map(_.queueDepth), "queue_depth")
  def envDepth: Int = uniform(cores.flatMap(_.channels.map(_.envDepth)), "env_depth")
  private def named(n: String) = cores.map(_.channel(n))
  def gatePulseNum: Int = uniform(named("gate").map(_.slots), "gate slots")
  def gateInterp: Int = uniform(named("gate").map(_.interp), "gate interp")
  def readoutInterp: Int = uniform(named("ro").map(_.interp), "ro interp")
  def demodInterp: Int = uniform(named("demod").map(_.interp), "demod interp")
  /** (core, k) → dac, k indexing the core's DAC-bound channels in order — `PulseTableSoc.dacMap`. */
  def dacMap: Map[(Int, Int), Int] =
    cores.zipWithIndex.flatMap { case (c, i) =>
      c.channels.flatMap(_.dac).zipWithIndex.map { case (d, k) => (i, k) -> d } }.toMap
  /** core → the adc of its first ADC-bound channel — `PulseTableSoc.adcMap`. */
  def adcMap: Map[Int, Int] =
    cores.zipWithIndex.flatMap { case (c, i) => c.channels.flatMap(_.adc).headOption.map(i -> _) }.toMap
}

object SocSpec {
  /** The qubit-build spec `PulseTableSoc`'s legacy constructor takes: `qubitNum` identical cores of
    * gate / ro / demod, converters from `dacMap` ((core, 0) = gate, (core, 1) = readout drive) and
    * `adcMap`. */
  def qubits(qubitNum: Int, dacMap: Map[(Int, Int), Int], adcMap: Map[Int, Int], dacNum: Int = 16,
             adcNum: Int = 16, gatePulseNum: Int = 8, envDepth: Int = 1024, gateInterp: Int = 4,
             readoutInterp: Int = 16, demodInterp: Int = 4, memDepth: Int = 4096, withMul: Boolean = true,
             queueDepth: Int = 4, linkPipe: Int = 4, hostwinBits: Int = 24, robDepth: Int = 1024,
             adcPipe: Int = 3, name: String = "qubits", dspFreqHz: Double = 500e6): SocSpec = {
    val cores = (0 until qubitNum).map { c =>
      CoreSpec(s"q$c", "qubit", memDepth, withMul, queueDepth, Seq(
        ChannelSpec("gate", "pulse", gatePulseNum, envDepth, gateInterp, Some(dacMap((c, 0))), None, trace = false),
        ChannelSpec("ro", "pulse", 1, envDepth, readoutInterp, Some(dacMap((c, 1))), None, trace = true),
        ChannelSpec("demod", "demod", 1, envDepth, demodInterp, None, Some(adcMap(c)), trace = false)))
    }
    SocSpec(name, dspFreqHz, dacNum, adcNum, linkPipe, hostwinBits, robDepth, adcPipe, cores)
  }

  // fixed by architecture — the JSON may state them, but they are asserted, never plumbed
  val batchSize = 16
  val adcBatch = 4
  val dataWidth = 16
  val readoutMaxWinLog2 = 14
  val readoutAccWidth = 32
  val kindLanes: Map[String, Int] = Map("pulse" -> batchSize, "demod" -> adcBatch, "dio" -> 0)
  private val fixed = Map("batch_size" -> batchSize, "adc_batch" -> adcBatch, "data_width" -> dataWidth,
    "readout_max_win_log2" -> readoutMaxWinLog2, "readout_acc_width" -> readoutAccWidth)

  def load(path: String): SocSpec = fromJson(scala.io.Source.fromFile(path).mkString)

  def fromJson(text: String): SocSpec = {
    val cfg = ujson.read(text)
    for ((key, value) <- fixed if cfg.obj.contains(key))
      require(cfg(key).num.toInt == value, s"$key is fixed by architecture at $value, got ${cfg(key).num.toInt}")
    if (cfg.obj.contains("cores")) fromChannelList(cfg.obj) else fromLegacy(cfg.obj)
  }

  private def intOr(o: collection.Map[String, ujson.Value], key: String, default: Int): Int =
    o.get(key).map(_.num.toInt).getOrElse(default)
  private def boolOr(o: collection.Map[String, ujson.Value], key: String, default: Boolean): Boolean =
    o.get(key).map(_.bool).getOrElse(default)
  private def optInt(o: collection.Map[String, ujson.Value], key: String): Option[Int] =
    o.get(key).map(_.num.toInt)

  private def fromChannelList(o: collection.Map[String, ujson.Value]): SocSpec = {
    val defaults = o.get("core_defaults").map(_.obj).getOrElse(collection.mutable.LinkedHashMap.empty[String, ujson.Value])
    def coreField[T](c: collection.Map[String, ujson.Value], key: String, get: ujson.Value => T, default: T): T =
      c.get(key).orElse(defaults.get(key)).map(get).getOrElse(default)
    val cores = o("cores").arr.map { cv =>
      val c = cv.obj
      CoreSpec(
        name = c("name").str,
        role = coreField(c, "role", _.str, "qubit"),
        memDepth = coreField(c, "mem_depth", _.num.toInt, 4096),
        withMul = coreField(c, "with_mul", _.bool, true),
        queueDepth = coreField(c, "queue_depth", _.num.toInt, 4),
        channels = c("channels").arr.map { chv =>
          val ch = chv.obj
          ChannelSpec(ch("name").str, ch("kind").str, ch("slots").num.toInt, ch("env_depth").num.toInt,
            ch("interp").num.toInt, optInt(ch, "dac"), optInt(ch, "adc"), boolOr(ch, "trace", false))
        }.toSeq)
    }.toSeq
    SocSpec(o("name").str, o("dsp_freq_hz").num, intOr(o, "dac_num", 16), intOr(o, "adc_num", 16),
      intOr(o, "link_pipe", 4), intOr(o, "hostwin_bits", 24), intOr(o, "rob_depth", 1024),
      intOr(o, "adc_pipe", 3), cores, boolOr(o, "with_white_rabbit", false), optInt(o, "wr_marker_dac"),
      intOr(o, "board", 0), intOr(o, "boards", 1))
  }

  /** The SocParams form: `qubit_num` identical qubit cores of gate / ro / demod channels (the python
    * `SocSpec.from_legacy` twin — keep the two identical). */
  def fromLegacy(o: collection.Map[String, ujson.Value]): SocSpec = {
    val n = o("qubit_num").num.toInt
    val dacMap = o.get("dac_map").map(_.arr.map(_.arr.map(_.num.toInt).toSeq).toSeq)
    val adcMap = o.get("adc_map").map(_.arr.map(_.num.toInt).toSeq)
    dacMap.foreach(m => require(m.length == n, s"dac_map needs $n entries, got ${m.length}"))
    adcMap.foreach(m => require(m.length == n, s"adc_map needs $n entries, got ${m.length}"))
    val envDepth = o("env_depth").num.toInt
    val cores = (0 until n).map { c =>
      val gateDac = dacMap.map(_(c)(0)).getOrElse(c)
      val roDac = dacMap.map(_(c)(1)).getOrElse(if (c < 7) 14 else 15)
      val adc = adcMap.map(_(c)).getOrElse(if (c < 7) 0 else 4)
      CoreSpec(s"q$c", "qubit", o("mem_depth").num.toInt, boolOr(o, "with_mul", true), intOr(o, "queue_depth", 4), Seq(
        ChannelSpec("gate", "pulse", o("gate_pulse_num").num.toInt, envDepth, o("gate_interp").num.toInt, Some(gateDac), None, trace = false),
        ChannelSpec("ro", "pulse", 1, envDepth, o("readout_interp").num.toInt, Some(roDac), None, trace = true),
        ChannelSpec("demod", "demod", 1, envDepth, intOr(o, "demod_interp", 4), None, Some(adc), trace = false)))
    }
    SocSpec(o("name").str, o("dsp_freq_hz").num, o("dac_num").num.toInt, o("adc_num").num.toInt,
      o("link_pipe").num.toInt, intOr(o, "hostwin_bits", 24), intOr(o, "rob_depth", 1024),
      intOr(o, "adc_pipe", 3), cores, boolOr(o, "with_white_rabbit", false), optInt(o, "wr_marker_dac"),
      intOr(o, "board", 0), intOr(o, "boards", 1))
  }
}

/**
 * The host-AXI map and the per-core channel tables derived from a [[SocSpec]] — the Scala twin of
 * python's `riscq.map.SocMap` (universal-control/01 §2.2): region 0 = the core RAMs; region 1+j =
 * every core's j-th channel envelope RAM (stride = the widest such bank); then the shared readout
 * trace and the host control block. Every region is `regionSize` wide; per-core sub-windows are a
 * power-of-two stride apart. It replaced the fixed-triple `SocMemoryMap` (universal-control/01 P1) and
 * is diffed against the python `riscq.map.SocMap` per config (`tests/test_spec_scala.py`).
 */
object SocSpecMap {
  val putWindow = 0x10000       // PutBridge base in the core's address space
  val rfChStride = 0x10000      // one sub-window per channel
  /** The put window (specs/cross-core/02 §3.2, D1): a posted write's address is `node · 2^16 + offset`.
   *  Nodes `0 until localNodes` are the core's OWN channels (node k = channel k, so the local channel
   *  map above is unchanged and `RiscvSoc` never needs its own system id); every node ≥ `localNodes`
   *  is a system-wide unit the parent routes to the hub. */
  val nodeBits   = 12           // {board 4, unit 8}
  val localNodes = 16           // node ids reserved for the core's own channels
  val putAddrWidth = 16 + nodeBits
  /** System node ids past the local ones (specs/cross-core/02 §4, one board): `groupNodes` shared
   *  words, `barrierIds` counted barriers, then one inbox node per core (unicast). */
  val groupNodes = 4
  val barrierIds = 16
  def groupNode(g: Int): Int   = localNodes + g
  def barrierNode(b: Int): Int = localNodes + groupNodes + b
  /** A core's inbox unit; its system node id carries the board in the top 4 bits (D1: `{board 4, unit 8}`);
    * group and barrier nodes are board-agnostic services (board field 0). */
  def inboxUnit(core: Int): Int = localNodes + groupNodes + barrierIds + core
  def inboxNode(board: Int, core: Int): Int = (board << 8) | inboxUnit(core)
  def inboxNode(core: Int): Int = inboxNode(0, core)
  /** The core's put-window width (the PutBridge's putAddrWidth), checked against its channel count. */
  def putAddrWidth(core: CoreSpec): Int = {
    require(core.channels.length <= localNodes, s"core '${core.name}': more than $localNodes channels")
    putAddrWidth
  }
}

case class SocSpecMap(spec: SocSpec) {
  private def pow2ceil(x: Int): Int = 1 << log2Up(x)
  val putWindow = SocSpecMap.putWindow
  val rfChStride = SocSpecMap.rfChStride
  val robWidth = SocSpec.adcBatch * 32
  val robBytes = robWidth * spec.robDepth / 8
  val coreStride = pow2ceil(1 << 16)
  val nSlots = spec.maxChannels
  val slotStrides: Seq[Int] = (0 until nSlots).map { j =>   // a bank-less slot (dio) is a 1-byte hole
    pow2ceil(math.max(1, spec.cores.filter(_.channels.length > j).map(_.channels(j).envBytes).max)) }
  val robStride = pow2ceil(2 * pow2ceil(robBytes))
  val regionSize = pow2ceil((Seq(coreStride, robStride) ++ slotStrides).max * spec.qubitNum)
  val coreMemBase = 0
  val slotBases: Seq[Int] = (0 until nSlots).map(j => (1 + j) * regionSize)
  val robBase = (1 + nSlots) * regionSize
  val hostCtrlBase = (2 + nSlots) * regionSize
  val wrBase = (3 + nSlots) * regionSize        // White Rabbit node window (withWhiteRabbit builds)

  def coreMemOffset(core: Int): Int = coreMemBase + core * coreStride
  def envOffset(core: Int, j: Int): Int = {
    require(j < spec.cores(core).channels.length, s"core $core has no channel $j")
    slotBases(j) + core * slotStrides(j)
  }
  def channelBase(j: Int): Int = putWindow + j * rfChStride
  def putAddrWidth(core: Int): Int = SocSpecMap.putAddrWidth(spec.cores(core))
  /** `PulseTableSoc.dacAlignStages + 2` — the python `dac_pipe` (uniform across driven DACs). */
  def dacPipe: Int = {
    def combineLatency(n: Int) = if (n <= 1) 0 else log2Up(n) + 1
    val align = (0 until spec.dacNum).map(d =>
      combineLatency(spec.cores.flatMap(_.channels).count(_.dac.contains(d)))).max
    align + 2
  }

  /** The contract-test descriptors, in the python `SocMap.entries()` order and naming. */
  def entries: Seq[(String, Int, Int, String)] = {
    val perCore = spec.cores.zipWithIndex.flatMap { case (c, i) =>
      (s"core${i}_ram", coreMemOffset(i), c.memDepth * 4, "ram_rw") +:
        c.channels.zipWithIndex.filter(_._1.envBytes > 0).map { case (ch, j) =>
          (s"core${i}_${ch.name}_env", envOffset(i, j), ch.envBytes, s"env_${ch.name}") }
    }
    perCore ++ Seq(("robs", robBase, robBytes, "robs_ro"), ("host_ctrl", hostCtrlBase, 0x54, "ctrl_wo"))
  }
}

/**
 * Print a build's derived map as one JSON line so `software/tests/test_spec_scala.py` can diff it
 * against python's `riscq.map.SocMap`:
 *
 *   mill runMain riscq.soc.spec.PrintSocMap software/configs/sim-2q.json
 */
object PrintSocMap extends App {
  require(args.length == 1, "usage: PrintSocMap <config.json>")
  val spec = SocSpec.load(args(0))
  val m = SocSpecMap(spec)
  def optNum(v: Option[Int]): ujson.Value = v.map(x => ujson.Num(x)).getOrElse(ujson.Null)
  val out = ujson.Obj(
    "name" -> spec.name,
    "region_size" -> m.regionSize,
    "entries" -> ujson.Arr(m.entries.map { case (n, a, b, k) =>
      ujson.Obj("name" -> n, "host_addr" -> a, "nbytes" -> b, "kind" -> k) }: _*),
    "channels" -> ujson.Arr(spec.cores.map { c => ujson.Arr(c.channels.zipWithIndex.map { case (ch, j) =>
      ujson.Obj("index" -> j, "name" -> ch.name, "kind" -> ch.kind, "base" -> m.channelBase(j),
        "slots" -> ch.slots, "samples_per_line" -> ch.samplesPerLine, "line_bytes" -> ch.lineBytes,
        "dac" -> optNum(ch.dac), "adc" -> optNum(ch.adc)) }: _*) }: _*),
    "put_addr_width" -> ujson.Arr(spec.cores.indices.map(i => ujson.Num(m.putAddrWidth(i))): _*),
    "dac_pipe" -> m.dacPipe)
  println(ujson.write(out))
}
