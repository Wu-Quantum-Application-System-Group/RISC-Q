# PathAnalysis — static, pre-synth netlist analysis (no tool in the loop)

**Source:** `src/riscq/bench/PathAnalysis.scala` · **Package:** `riscq.bench` · **Type:** class + companion `object`

## Purpose

A static, **pre-synthesis** analysis of an elaborated SpinalHDL netlist, built on SpinalHDL's
`spinal.lib.misc.PathTracer`. Construct it on any elaborated toplevel and ask it where the deep logic
and the heavy fanout are, and how many register hops separate two signals — all with **no Vivado in
the loop**, so it runs instantly and is the first thing to reach for when narrowing *where* to
optimise. It is the free counterpart to [VivadoBench](VivadoBench.md), which gives the same "where"
view but with the synthesiser's real numbers.

It is **DUT-agnostic**: the constructor takes a `Component` or a `SpinalReport`, so it works on the
core, a DSP module, a memory block — anything elaborated. The per-DUT runnable entry points live next
to each module (e.g. `riscq.riscv.bench.PathAnalysis`, `riscq.dsp.bench.PathAnalysis`).

## The one caveat that matters

Everything is reported as **SpinalHDL-level proxies — not nanoseconds**: logic-level depth
(computational-node count), net read-count (logical load), register hops. These rank candidates and
expose structure; they are *not* timing. **Always confirm a suspected critical path with Vivado**
([VivadoBench](VivadoBench.md) / [VivadoRouteBench](VivadoRouteBench.md)) before acting on it — on
this FPGA the bulk of a real critical path is routing, which a logic-level count cannot see.

### The free-routing collapse (the key idea behind every view)

A node is counted as a **logic level only if it is a real logic operator**. Everything else on a path
is pure rewiring that costs no logic level, so adjacent such nodes collapse to one:

- **Free (count 0):** named wires, casts, resizes, fixed bit/range selects, concatenation, bit
  replication, and *constant* shifts.
- **Computation (count 1):** `&&`, `||`, `+`, `-`, `===`, reductions, multiplexers (incl.
  variable bit/range indexing = a mux), and *variable* shifts (a barrel shifter).

This is what makes the depth numbers meaningful — a 32-bit bus rename or a sign-extend does not inflate
the count, only actual gates do. The same classification underpins the fanout and combinational-level
views.

## The four views

1. **Longest register → register paths** (`reportLongestPaths`). Every register-D input is a
   combinational-path *endpoint*; for each, the feeding combinational cone is rebuilt (it bottoms out
   at registers / primary inputs / literals / memory reads) and the deepest chain of computational
   nodes in that cone is measured. Endpoints are ranked deepest-first. The report prints the ranked
   list, then the single deepest path with free-routing runs collapsed (only real operators and the
   two register endpoints get their own line). A machine-checked assertion guarantees no interior
   register sits on a reported path.

2. **Largest fanout** (`reportFanout`). A whole-design pass counts, for every *real* net (a register,
   or a net whose driver contains real logic), how many real sinks read it — seeing *through* the free
   routing in between. This is the logical load count, the thing that drives replication / `max_fanout`
   decisions, not the raw Verilog net degree.

3. **Register levels between two signals** (`reportRegisterLevels(from, to)`) — the **pipeline
   latency in clock cycles**: the number of register hops on the paths from `from` to `to`. Reported
   as a `RegLevels(min, max)`: `min` is the exact shortest-path hop count; `max` is the longest
   *feedforward* path, or `None` when register feedback (a hold/enable register or a real loop) lies
   on the path, so the hop count has no finite upper bound. `min == max` is a single balanced latency;
   `min < max` is a reconvergent feedforward path of mixed depth. `to = RegNext(from)` reports
   `min == max == 1`.

4. **Combinational levels between two registers** (`reportCombinationalLevels(from, to)`) — the logic
   depth of the *single-cycle* path between two chosen registers (the classic "logic levels" of one
   timing path), pinned to a source register instead of "the deepest in the cone" as view 1 is.
   Returns `Some((min, max))` computational operators, or `None` when every path between them crosses
   a register (i.e. they are not in the same clock cycle). For a reconvergent path the `max` branch is
   the one that bounds fmax.

`registerLevels` is the *cycle* distance (latency), `combinationalLevels` the *logic-depth* distance
within one cycle — complementary views of how far apart two signals sit.

## Usage

The generic class:

```scala
val pa = PathAnalysis(SpinalVerilog(MyComponent()))
pa.reportLongestPaths()
pa.reportFanout()
pa.reportRegisterLevels(myIn, myOut)          // pipeline latency between two signals
pa.reportCombinationalLevels(srcReg, dstReg)  // logic depth of a single-cycle path
```

You can pass a `BaseType` directly, or a name string — `findSignals` / `resolveSignal` resolve a name
(preferring exact local/full-path matches, then a substring fallback) and report ambiguity helpfully.

The core's thin entry point (no args = longest-paths + fanout; two signal names = levels between them):

```bash
mill runMain riscq.riscv.bench.PathAnalysis
mill runMain riscq.riscv.bench.PathAnalysis <from> <to>
```

## Related

- [VivadoBench](VivadoBench.md) — the same "where to optimise" view, with the synthesiser's numbers.
- [VivadoRouteBench](VivadoRouteBench.md) — the authoritative post-route timing.
- [README](README.md) — the `riscq.bench` engine overview.
