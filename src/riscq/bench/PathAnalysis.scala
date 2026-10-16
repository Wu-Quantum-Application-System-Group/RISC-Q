package riscq.bench

import spinal.core._
import spinal.core.internals._
import spinal.lib.misc.PathTracer

import scala.collection.mutable

/**
 * Static, pre-synthesis analysis of an elaborated SpinalHDL netlist, built on SpinalHDL's
 * [[spinal.lib.misc.PathTracer]]. It is **DUT-agnostic**: construct it on any elaborated toplevel
 * (the riscq core, a DSP module, anything) and ask it three questions. Everything is reported as
 * SpinalHDL-level proxies (logic-level depth, net read-count, register hops) — not nanoseconds.
 * Use it to spot *where* to look; use Vivado ([[VivadoBench]] / [[VivadoRouteBench]]) for true timing.
 *
 *  1. Longest combinational paths ([[reportLongestPaths]]). Every register-D input is a comb-path
 *     *endpoint*. For each, `PathTracer.impl(e, e)` rebuilds the combinational cone feeding it (it
 *     stops at registers / inputs / literals), and we take the deepest chain of computational nodes
 *     in that cone. Endpoints are ranked by that depth.
 *  2. Largest fanout ([[reportFanout]]). A whole-design pass counts, for every real signal, how many
 *     real sinks read it (seeing through pure routing) — i.e. its logical load count.
 *  3. Register levels between two signals ([[registerLevels]] / [[reportRegisterLevels]]). The number
 *     of register hops on the paths from one signal to another — i.e. the pipeline latency in clock
 *     cycles. PathTracer itself stops at registers, so this uses its own latency-accumulating walk.
 *
 * Usage:
 * {{{
 *   val pa = PathAnalysis(SpinalVerilog(MyComponent()))
 *   pa.reportLongestPaths()
 *   pa.reportFanout()
 *   pa.reportRegisterLevels(myIn, myOut)      // pipeline latency between two signals
 * }}}
 *
 * Per-DUT runnable entry points live next to each module (e.g. `riscq.riscv.bench.PathAnalysis`).
 */
class PathAnalysis(top: Component) {

  // ---- collect every declared signal / memory in the whole hierarchy -------------------
  val signals = mutable.ArrayBuffer[BaseType]()
  val mems    = mutable.ArrayBuffer[Mem[_]]()
  top.walkComponents { c =>
    c.dslBody.walkDeclarations {
      case bt: BaseType => signals += bt
      case m: Mem[_]    => mems += m
      case _            =>
    }
  }

  // =====================================================================================
  // Shared node classification: which nodes cost a logic level, which are pure routing.
  // =====================================================================================
  // A node is "computational" iff it is a real logic operator. Everything else on a path is just
  // routing/relabeling that costs no logic level, so adjacent such nodes are treated as one:
  //   - named wires (BaseType), casts, resizes, fixed bit/range selects, concat, bit replication,
  //     and constant shifts are FREE (pure rewiring);
  //   - &&, ||, +, -, ===, reductions, multiplexers, and variable shifts are COMPUTATION.
  def isComputeExpr(node: BaseNode): Boolean = node match {
    case _: BaseType                          => false // named wire / net: no logic of its own
    case _: Cast                              => false // type reinterpret
    case _: Resize                            => false // zero/sign-extend or truncate
    case _: BitVectorBitAccessFixed           => false // constant single-bit select
    case _: BitVectorRangedAccessFixed        => false // constant range select
    case _: Operator.Bits.Cat                 => false // concatenation (pure routing)
    case _: Operator.BitVector.Repeat         => false // bit replication (pure routing)
    case s: Operator.BitVector.ShiftOperator  => !s.isInstanceOf[ConstantOperator] // var shift = barrel, const = rewire
    case _: Multiplexer                       => true  // mux / switch
    case _: BitVectorBitAccessFloating        => true  // variable index = mux
    case _: BitVectorRangedAccessFloating     => true  // variable range = mux
    case _: Operator                          => true  // real logic: &&, ||, +, ===, reductions, ...
    case _                                    => false // literals & misc: not a logic level
  }
  def isRegLeaf(n: PathTracer.Node): Boolean = n.node match {
    case bt: BaseType => bt.isReg
    case _            => false
  }
  def isComputational(n: PathTracer.Node): Boolean = isComputeExpr(n.node)

  // =====================================================================================
  // 1) Longest register -> register combinational path
  // =====================================================================================
  private val NONE = Int.MinValue / 4

  // Longest chain in a cone that TERMINATES at a register leaf, measured in COMPUTATIONAL nodes
  // (free routing nodes count 0). Returns NONE if no register is reachable from this endpoint,
  // i.e. the cone bottoms out only at primary inputs / literals / memory reads.
  private def regConeDepth(root: PathTracer.Node): (Int, List[PathTracer.Node]) = {
    val cost  = mutable.HashMap[PathTracer.Node, Int]()
    val best  = mutable.HashMap[PathTracer.Node, PathTracer.Node]()
    val onStk = mutable.HashSet[PathTracer.Node]()
    def rec(n: PathTracer.Node): Int = cost.get(n) match {
      case Some(d) => d
      case None =>
        if (!onStk.add(n)) return NONE // comb-loop guard (shouldn't happen): don't memoize
        val d =
          if (n.ups.isEmpty) { if (isRegLeaf(n)) 0 else NONE } // leaf: only a register terminates a path
          else {
            var b = NONE
            for (up <- n.ups) {
              val du = rec(up)
              if (du != NONE && du > b) { b = du; best(n) = up }
            }
            if (b == NONE) NONE else b + (if (isComputational(n)) 1 else 0)
          }
        onStk.remove(n)
        cost(n) = d
        d
    }
    val d = rec(root)
    val chain = mutable.ListBuffer[PathTracer.Node](root)
    var cur = root
    while (best.contains(cur)) { cur = best(cur); chain += cur }
    (d, chain.toList)
  }

  /** Every register endpoint ranked by combinational logic depth (deepest first). Each entry is
   *  `(logicLevels, label, chain)` where `chain` runs from the destination register down to the
   *  source register through the cone of feeding logic. */
  def longestRegPaths(): Seq[(Int, String, List[PathTracer.Node])] = {
    val endpoints = signals.filter(_.isReg).map(bt => (bt: Expression, s"reg $bt"))
    endpoints.map { case (e, label) =>
      val (d, chain) = regConeDepth(PathTracer.impl(e, e))
      (d, label, chain)
    }.filter(_._1 != NONE).sortBy(-_._1).toSeq
  }

  // Required property: between the end register (chain head) and the start register (chain last)
  // there must be NO other register -- every interior node is combinational. Holds by construction
  // (PathTracer follows only latency-0 edges), but we assert it so the guarantee is machine-checked.
  private def interiorHasReg(chain: List[PathTracer.Node]): Boolean =
    chain.drop(1).dropRight(1).exists(isRegLeaf)

  /** Print the deepest path with free-routing runs collapsed: only real operators and the two
   *  register endpoints get their own line; consecutive free nodes merge into one [wire] entry. */
  private def printCollapsed(chain: List[PathTracer.Node]): Unit = {
    val free = mutable.ListBuffer[PathTracer.Node]()
    def flushFree(): Unit = if (free.nonEmpty) {
      val named = free.toList.collect { case n if n.node.isInstanceOf[Nameable] => n.node.toString }
      println(f"    [wire x${free.size}%2d] ${named.mkString(" = ")}")
      free.clear()
    }
    for (n <- chain) {
      if (isRegLeaf(n))            { flushFree(); println(s"    [REG ] ${n.node}") }
      else if (isComputational(n)) { flushFree(); println(s"    [op  ] ${n.node}") }
      else                         { free += n }
    }
    flushFree()
  }

  def reportLongestPaths(n: Int = 20): Unit = {
    println(s"[PathAnalysis] ${signals.count(_.isReg)} register endpoints, ${signals.size} signals, ${mems.size} memories")
    val ranked = longestRegPaths()
    val violations = ranked.count { case (_, _, chain) => interiorHasReg(chain) }
    assert(violations == 0, s"$violations reg->reg paths unexpectedly contain an interior register")

    println("\n================ Longest register -> register paths (logic levels = computational nodes) ================")
    println(s"  (checked: ${ranked.size} paths, $violations with an interior register)")
    for ((d, label, _) <- ranked.take(n)) println(f"  logic $d%3d   $label")

    println("\n================ Deepest register -> register path, free routing collapsed (end at top) ================")
    ranked.headOption.foreach { case (d, label, chain) =>
      println(s"  endpoint: $label   ($d logic levels, ${chain.size} raw nodes)")
      printCollapsed(chain)
    }
  }

  // =====================================================================================
  // 2) Largest fanout (real source -> real sink, free routing collapsed)
  // =====================================================================================
  // We count only "logical" fanout: a load where BOTH ends are real nodes -- a register or the
  // output of a computation -- seeing through the free routing (wires, casts, resizes, bit-selects,
  // concat) in between. A net is REAL iff it is a register or its combinational driver contains at
  // least one computational operator. Pure alias/slice nets are transparent.
  private def hasComputeDriver(bt: BaseType): Boolean = {
    var found = false
    def scan(e: Expression): Unit =
      if (!found) {
        if (isComputeExpr(e)) found = true
        else e match {
          case _: BaseType => // boundary: another named net, stop
          case other       => other.foreachDrivingExpression(scan)
        }
      }
    bt.foreachStatements { s =>
      s.foreachDrivingExpression(scan)
      s.walkParentTreeStatementsUntilRootScope(t => t.foreachDrivingExpression(scan))
    }
    found
  }
  private val realCache = mutable.HashMap[BaseType, Boolean]()
  def isReal(bt: BaseType): Boolean = realCache.getOrElseUpdate(bt, bt.isReg || hasComputeDriver(bt))

  /** `(source -> load count)` for every real net, descending highest-fanout first. */
  def largestFanout(): Seq[(BaseType, Int)] = {
    // For each real sink, collect the distinct real sources feeding it (descending through free nets
    // transparently), and credit one load to each source.
    val fanout = mutable.HashMap[BaseType, Int]().withDefaultValue(0)
    for (w <- signals if isReal(w)) {
      val sources = mutable.LinkedHashSet[BaseType]()
      val seen    = mutable.HashSet[BaseType]()
      def up(e: Expression): Unit = e match {
        case bt: BaseType =>
          if (isReal(bt)) sources += bt            // real source feeding w: count it
          else if (seen.add(bt)) descend(bt)       // free alias: see through to its own drivers
        case other => other.foreachDrivingExpression(up)
      }
      def descend(bt: BaseType): Unit = bt.foreachStatements { s =>
        s.foreachDrivingExpression(up)
        s.walkParentTreeStatementsUntilRootScope(t => t.foreachDrivingExpression(up))
      }
      descend(w)
      for (s <- sources) fanout(s) += 1
    }
    fanout.toSeq.sortBy(-_._2)
  }

  def reportFanout(n: Int = 25): Unit = {
    val sorted = largestFanout()
    println(s"\n================ Largest fanout (real source -> real sink, free routing collapsed) ================")
    println(s"  (${realCache.count(_._2)} real nets of ${signals.size} signals)")
    for ((bt, loads) <- sorted.take(n)) {
      val kind = if (bt.isReg) "reg " else "comp"
      println(f"  loads $loads%4d   [$kind] $bt")
    }
  }

  // =====================================================================================
  // 3) Register levels (pipeline latency) between two signals
  // =====================================================================================
  // PathTracer.impl stops at registers, so it cannot count register hops. This walk mirrors its
  // latency model (a register's input edge has latency 1, combinational edges 0, memory ports 1/0)
  // but accumulates the latency instead of stopping, so it measures how many clock cycles separate
  // `from` from `to`.

  /** Drive `onUp` with each immediate driver of `that` and the register latency of that edge
   *  (1 if crossing a register / synchronous memory port, 0 for pure combinational). */
  private def foreachUp(that: BaseNode)(onUp: (BaseNode, Int) => Unit): Unit = that match {
    case mem: Mem[_] =>
      mem.foreachStatements {
        case p: MemWrite     => p.foreachDrivingExpression(in => onUp(in, 1))
        case p: MemReadWrite => p.foreachDrivingExpression(in => onUp(in, 1))
        case _: MemReadSync  =>
        case _: MemReadAsync =>
        case _               =>
      }
    case bt: BaseType =>
      def inputs(f: BaseNode => Unit): Unit = bt.foreachStatements { s =>
        s.foreachDrivingExpression(in => f(in))
        s.walkParentTreeStatementsUntilRootScope(t => t.foreachDrivingExpression(in => f(in)))
      }
      if (bt.isReg) inputs(in => onUp(in, 1)) else inputs(in => onUp(in, 0))
    case p: MemReadSync  => p.foreachDrivingExpression(in => onUp(in, 1)); onUp(p.mem, 1)
    case p: MemReadWrite => p.foreachDrivingExpression(in => onUp(in, 1)); onUp(p.mem, 1)
    case p: MemReadAsync => p.foreachDrivingExpression(in => onUp(in, 0)); onUp(p.mem, 0)
    case e: Expression   => e.foreachDrivingExpression(in => onUp(in, 0))
    case _               =>
  }

  /** Result of [[registerLevels]]. `min` is the minimum register hops from `from` to `to` — the
   *  pipeline latency in clock cycles when not stalled, always exact. `max` is the maximum hops on a
   *  *feedforward* path: `Some(m)` when the relevant subgraph (only the nodes on a `from -> to` path)
   *  is acyclic, else `None` — `None` means register feedback lies on the path (an enable/hold
   *  register or a real loop), so the hop count has no finite upper bound. `min == max` is a single,
   *  perfectly balanced latency; `min < max` is a reconvergent feedforward path of mixed depth. */
  case class RegLevels(min: Int, max: Option[Int]) {
    def balanced: Boolean = max.contains(min)
  }

  /**
   * Register hops on the paths from `from` to `to`, as `Some(RegLevels)`, or `None` if `from` does
   * not feed `to` at all. Counting starts at `from`'s output, so a register that *is* `from` is not
   * itself counted; `to = RegNext(from)` reports `min == max == 1`. Assumes the usual well-formed
   * netlist (no purely-combinational loops).
   *
   * Method: (1) explore the fan-in cone of `to`, stopping at `from`, recording each up-edge with its
   * latency; (2) keep only the nodes that can actually reach `from` (the relevant subgraph); (3) on
   * that subgraph, `min` is a shortest-path (0/1-weight Dijkstra), always sound; `max` is the longest
   * path, defined only when the subgraph is acyclic (a feedback/hold register has no finite longest
   * path), so it is reported as a topological-DP value or `None`.
   */
  def registerLevels(from: BaseType, to: BaseType): Option[RegLevels] = {
    if (from == to) return Some(RegLevels(0, Some(0)))

    // (1) explore the up-cone of `to`, stopping at `from`; record up-edges (driver, latency).
    val ups = mutable.HashMap[BaseNode, List[(BaseNode, Int)]]()
    ups(from) = Nil // sentinel: a known node, never expanded
    def explore(n: BaseNode): Unit =
      if (n != from && !ups.contains(n)) {
        val es = mutable.ListBuffer[(BaseNode, Int)]()
        foreachUp(n) { (up, lat) => es += ((up, lat)) }
        ups(n) = es.toList
        es.foreach { case (up, _) => explore(up) }
      }
    explore(to)

    // (2) relevant nodes = those that can reach `from` = nodes reachable from `from` over reversed
    // edges. Plain single-source reachability, so a visited-set walk is sound despite feedback.
    val downs = mutable.HashMap[BaseNode, mutable.ListBuffer[BaseNode]]()
    for ((x, es) <- ups; (up, _) <- es) downs.getOrElseUpdate(up, mutable.ListBuffer()) += x
    val canReach = mutable.HashSet[BaseNode](from)
    val stack    = mutable.Stack[BaseNode](from)
    while (stack.nonEmpty) {
      val x = stack.pop()
      for (d <- downs.getOrElse(x, Nil)) if (canReach.add(d)) stack.push(d)
    }
    if (!canReach.contains(to)) return None // `from` does not feed `to`

    def relevantUps(n: BaseNode): List[(BaseNode, Int)] =
      ups(n).filter { case (up, _) => canReach.contains(up) }

    // (3a) min hops = shortest path `to -> from`. Dijkstra over 0/1 edge weights is sound even with
    // feedback: a loop only ever adds hops, so it never shortens the minimum.
    val dist = mutable.HashMap[BaseNode, Int]().withDefaultValue(Int.MaxValue)
    val pq   = mutable.PriorityQueue[(Int, BaseNode)]()(Ordering.by(-_._1)) // min-heap on distance
    dist(to) = 0; pq.enqueue((0, to))
    while (pq.nonEmpty) {
      val (d, u) = pq.dequeue()
      if (d == dist(u) && u != from) // never expand past `from`; skip stale heap entries
        for ((v, w) <- relevantUps(u) if d + w < dist(v)) { dist(v) = d + w; pq.enqueue((d + w, v)) }
    }
    val min = dist(from)

    // (3b) max hops = longest path `to -> from`, but only finite on a feedforward (acyclic) subgraph.
    // Detect any register feedback loop first (3-colour DFS); if present, max is unbounded (None).
    val (white, grey, black) = (0, 1, 2)
    val color = mutable.HashMap[BaseNode, Int]().withDefaultValue(white)
    var cyclic = false
    def detect(n: BaseNode): Unit = {
      color(n) = grey
      for ((up, _) <- relevantUps(n)) color(up) match {
        case `grey`  => cyclic = true
        case `white` => detect(up)
        case _       =>
      }
      color(n) = black
    }
    detect(to)

    val max =
      if (cyclic) None
      else {
        val longest = mutable.HashMap[BaseNode, Int]() // DAG DP: sound because acyclic
        def lp(n: BaseNode): Int =
          if (n == from) 0
          else longest.getOrElse(n, {
            val best = relevantUps(n).map { case (up, w) => lp(up) + w }.max // ≥1 relevant up exists
            longest(n) = best; best
          })
        Some(lp(to))
      }

    Some(RegLevels(min, max))
  }

  def reportRegisterLevels(from: BaseType, to: BaseType): Unit = {
    println(s"\n================ Register levels (pipeline latency) ================")
    println(s"  from: $from")
    println(s"  to:   $to")
    registerLevels(from, to) match {
      case None => println("  result: no path — `from` does not feed `to`.")
      case Some(r) if r.balanced =>
        println(f"  result: latency ${r.min}%d clock cycle(s)  (balanced; ${r.min}%d register hops)")
      case Some(RegLevels(min, Some(max))) =>
        println(f"  result: reconvergent feedforward path — min $min%d / max $max%d register hops")
      case Some(RegLevels(min, None)) =>
        println(f"  result: min latency $min%d clock cycle(s); register feedback (hold/loop) on the path, so max is unbounded")
    }
  }

  // =====================================================================================
  // 4) Combinational levels (logic depth) between two registers
  // =====================================================================================
  // The number of computational operators on the *single-cycle* (latency-0) path from `from` to
  // `to`, with pure-routing wires not counted — the classic "logic levels" of a register->register
  // timing path. This is the same depth measure as the longest-path view (1), but pinned to a chosen
  // source register instead of "the deepest register in the cone". `PathTracer.impl(from, to)` builds
  // exactly the combinational cone of `to` with `from` as a leaf (it follows only latency-0 edges, so
  // it stops at every other register), and flags every node that lies on a `from -> to` path
  // (`hits != 0`), so we just take the min/max computational-node count over that flagged subgraph.

  /**
   * Combinational logic levels on the register-to-register paths from `from` to `to`, as
   * `Some((min, max))` computational operators (free routing — wires, casts, resizes, fixed
   * bit-selects, concat, constant shifts — is not counted), or `None` when there is no purely
   * combinational path between them (every path crosses a register, i.e. they are not in the same
   * clock cycle). `min == max` is a single logic depth; `min < max` is a reconvergent path whose
   * branches differ in depth (the `max` is the one that bounds fmax).
   */
  def combinationalLevels(from: BaseType, to: BaseType): Option[(Int, Int)] = {
    if (from == to) return None
    val root  = PathTracer.impl(from, to) // comb cone of `to`, `from` flagged as a leaf (hits != 0)
    val memo  = mutable.HashMap[PathTracer.Node, Option[(Int, Int)]]()
    val onStk = mutable.HashSet[PathTracer.Node]()
    def depth(n: PathTracer.Node): Option[(Int, Int)] =
      if (n.node == from) Some((0, 0)) // reached the source register: this branch is a real path
      else memo.get(n) match {
        case Some(v) => v
        case None =>
          if (!onStk.add(n)) return None // comb-loop guard (shouldn't happen in a valid netlist)
          var acc: Option[(Int, Int)] = None
          for (up <- n.ups if up.hits != 0; (lo, hi) <- depth(up)) acc = acc match {
            case None             => Some((lo, hi))
            case Some((aLo, aHi)) => Some((math.min(aLo, lo), math.max(aHi, hi)))
          }
          val self = if (isComputational(n)) 1 else 0 // count this node iff it is real logic
          val res  = acc.map { case (lo, hi) => (lo + self, hi + self) }
          onStk.remove(n)
          memo(n) = res
          res
      }
    depth(root)
  }

  def reportCombinationalLevels(from: BaseType, to: BaseType): Unit = {
    println(s"\n================ Combinational levels (register -> register logic depth) ================")
    println(s"  from: $from")
    println(s"  to:   $to")
    combinationalLevels(from, to) match {
      case None =>
        println("  result: no single-cycle path — every path from `from` to `to` crosses a register.")
      case Some((lo, hi)) if lo == hi =>
        println(f"  result: $lo%d combinational level(s)  (computational operators; routing wires not counted)")
      case Some((lo, hi)) =>
        println(f"  result: reconvergent — min $lo%d / max $hi%d combinational levels (max bounds fmax)")
    }
  }

  /** Find declared signals matching `name`, preferring exact matches: an exact local-name or
   *  full-path match wins; only if there is none do we fall back to a substring match on the full
   *  path. Convenience for command-line / ad-hoc lookups; prefer passing the [[BaseType]] directly
   *  when you have it. */
  def findSignals(name: String): Seq[BaseType] = {
    val exact = signals.filter(bt => bt.getName() == name || bt.toString == name).toSeq
    if (exact.nonEmpty) exact else signals.filter(_.toString.contains(name)).toSeq
  }

  /** Resolve a signal name to a single [[BaseType]] via [[findSignals]], printing a helpful message
   *  (and returning `None`) when the name is missing or ambiguous. */
  def resolveSignal(name: String): Option[BaseType] = findSignals(name) match {
    case Seq(one) => Some(one)
    case Seq()    => println(s"[PathAnalysis] no signal matches '$name'"); None
    case many =>
      println(s"[PathAnalysis] '$name' is ambiguous (${many.size} matches); be more specific:")
      many.take(10).foreach(bt => println(s"    $bt"))
      None
  }

  /** Resolve two signal names and report the register levels between them. */
  def reportRegisterLevels(fromName: String, toName: String): Unit =
    for (f <- resolveSignal(fromName); t <- resolveSignal(toName)) reportRegisterLevels(f, t)

  /** Resolve two signal names and report the combinational levels between them. */
  def reportCombinationalLevels(fromName: String, toName: String): Unit =
    for (f <- resolveSignal(fromName); t <- resolveSignal(toName)) reportCombinationalLevels(f, t)
}

object PathAnalysis {
  def apply(top: Component): PathAnalysis = new PathAnalysis(top)
  def apply(report: SpinalReport[_ <: Component]): PathAnalysis = new PathAnalysis(report.toplevel)
}
