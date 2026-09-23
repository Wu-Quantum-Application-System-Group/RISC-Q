# SOC_TIPS — gotchas & solutions building the `riscq.soc` SoC

Hard-won, non-obvious issues building the `riscq.soc` SoC. Each entry is **symptom → cause → fix**. Read
this before touching `riscq.soc` / the `tilelink.fabric` glue or writing a SoC-level SpinalSim.

---

## 1. `tilelink.fabric` wiring

### 1.1 Core → fabric bridge (`RiscqFiber`)
The agentic `FetchPlugin`/`LsuPlugin` already expose real Tilelink masters (`iBus`/`dBus`), so the
adapter is just (no `CachelessBusToTilelink` double-bridge):
```scala
node.m2s.forceParameters(master.p.node.m)          // master.p = BusParameter; .node.m = M2sParameters
node.s2m.supported.load(S2mSupport.none())
node.bus.component.rework(node.bus << master)      // rework: the node's bus Handle loads in its own Fiber thread
```
`node` is `Node.down()` (master-only). Do **not** `setAsDirectionLess()` the master — it's already an
IO of the child `Riscq` component, so `<<` is a normal parent-reads-child-output connection.

### 1.2 Fan-out offsets are RELATIVE to each region bus
Symptom: `scala.NotImplementedError` in `AddressMapping.withOffsetInvert` / `NeverMapping` during a
Node decoder's `getMemoryTransfers`. Cause: a slave connected `at <absolute host address> of regionBus`
when the WidthAdapter/decoder already rebased that region to 0. Fix: connect `at (i * stride) of
regionBus`, not `at (regionBase + i*stride)`. (Absolute offsets are only for connections directly off
the top `hostBus`.)

### 1.3 `beatMax must be 1` — it's about `blockSize`, not bursts
The word-wide on-chip memory fibers (`TileLinkMem{ReadWrite,Write}Logic`) assert `p.beatMax == 1`.
- **`blockSize` too large** for a *narrow* fiber is NOT the problem — each fiber's **decoder restricts
  the transfer size down to what that fiber supports**, so a 32-bit memory stays single-beat even with a
  big `blockSize`.
- **`blockSize` too small** (e.g. 4) for a *wide* fiber **is** the problem: the host can't reach the wide
  word, the WidthAdapter negotiates `sizeBytes = 0`, and `beatMax = (0+dataBytes-1)/dataBytes = 0 ≠ 1`.
- **Rule:** set `Axi4ToTilelinkFiber(blockSize = …)` ≥ the **widest** on-chip memory word in bytes
  (e.g. a 512-bit pulse-envelope line ⇒ `blockSize = 64`). The reference used 32 only because its widest
  word was 256-bit.
- **Diagnose** by printing the failing fiber's `p.dataWidth` / `p.sizeBytes` in the assert message.

### 1.4 Wide memories need a `WidthAdapter`; narrow ones connect directly
A 32-bit host write into a 512-bit envelope line is a *masked wide-word* write done by an upsizing
`tilelink.fabric.WidthAdapter` (16 partial host writes fill one line). Put the adapter on the pulse-mem
and readout-buffer (`robs`) paths; the 32-bit instruction memory connects straight to `hostBus`.
(Connecting a wide fiber directly to a 32-bit `Node` gives `sizeBytes = 0` — see 1.3.)

---

## 2. SpinalSim — `tilelink.sim.MasterAgent`

### 2.1 The phantom-`A` NPE (the single biggest time-sink)
Symptom: `NullPointerException: ...MasterAgent.callbackOnAtoD()[...source()] is null`, often with a
spurious `D src=1 bytes=8` printed before any `A`. Cause: a `MasterBus`'s `bus.a.*` is **undriven (X)**
until the agent's `StreamDriverOoo` runs its first `onSamplings` (at the first clock edge). During reset
the slave sees X-valued `a.valid`, latches a **phantom request**, and its response fires into an
unregistered callback. Fixes (use either/both):
- **Create the `MasterAgent` before the first clock edge** (before any `waitSampling`) so its driver
  holds `a.valid` low from edge 1; **or** drive `dut.<masterBus>.node.bus.a.valid #= false` at t=0
  before `forkStimulus`.
- It can also manifest **without an NPE** as a *garbage pulse*: the phantom fire reads an
  **uninitialized** pulse-table `dur` → loads the duration counter with garbage → a long spurious pulse
  from t=0. So **also reset the pulse table to 0** (`Reg(...) init ….getZero`) so a stray fire reads a
  benign `dur = 0`.
- In the real SoC the CPU/AXI master holds the bus low through reset, so none of this arises — it is a
  bus-master *sim-harness* artifact.

### 2.2 `MasterAgent` needs real `IdAllocator` + `IdCallback`
`new MasterAgent(bus, cd)(idAllocator)` — pass `implicit val idAllocator = new IdAllocator(DebugId.width)`
and `implicit val idCallback = new IdCallback`. Passing `null` works for the **slave** `MemoryAgent`
(`SimMem`) but the **master** OoO driver needs them to match responses to requests.

### 2.3 Driving a `SlaveFactory` register map
- `factory.write(reg, addr, bitOffset = 16)` ⇒ the field lives in bus bits `[16,32)`; write it with
  `agent.putInt(src, addr, (v & 0xFFFF) << 16)`.
- Reading a **halting** register (e.g. `res` gated by `res.valid`): `agent.getInt` simply **blocks**
  until the slave un-halts — which is exactly the CPU spin-wait behaviour you want to test.
- Space writes out a couple of cycles if you ever see flakiness, but the root cause of "weird" behaviour
  is almost always 2.1, not back-to-back timing.

### 2.4 Lead-time `TimedQueue` scheduling in a sim
Hold `io.time = 0` while doing all the TL writes (the pushed entries sit in the queues, since the
wrap-safe geq pop only fires once `time ≥ startTime − lead`), then ramp `io.time` and capture. The
hold-then-ramp is safe because the pop test is level-based.

---

## 3. SpinalSim — reset, clock domains, signal access

### 3.1 "Is this register actually reset?"
A reg whose value **varies across netlist edits** (e.g. after adding a `simPublic`) is **uninitialized /
random**, not reset. A reg that is **deterministically its init value** is reset. `cd.forkStimulus(period)`
only drives reset **if `cd.hasResetSignalSim`** and asserts it for `resetCycles` (default 16). **Do not**
mix manual `cd.assertReset()/deassertReset()` with `forkStimulus` — both drive the reset signal and
fight, deasserting it early.

### 3.2 Read internal signals via `simPublic`, not `out`
Wiring `val dbg = out(...); dbg := core.deep.signal` is a **HIERARCHY VIOLATION**. Call
`core.deep.signal.simPublic()` during elaboration and read `dut.core.deep.signal.toBigInt` in the sim.

### 3.3 `#=` then immediate read is stale
`sig #= v; sig.toBigInt` returns the **old** value (the assignment propagates on the next delta). Read
after a `waitSampling`, or just don't read what you just wrote.

### 3.4 Instantiate the DUT inside `compile{}`
`val dut0 = MyComp(); SimConfig.compile(dut0)` throws `GlobalData … null`. `compile` takes the component
**by name**: `SimConfig.compile(MyComp())`.

### 3.5 Two-clock SoC sims
Fork **both** domains (`dut.clockDomain` and the explicit `dut.dspCd`) at the **same period**; the
cross-domain `BufferCC`/fabric works when the clocks are aligned. Real CDC / multi-frequency clocking is
deferred to a later packaging pass (§7 of the plan).

### 3.6 `DualClockRam` is intentionally MULTIDRIVEN
One array, two clocked ports ⇒ Verilator errors `MULTIDRIVEN`. Any sim instantiating a `DualClockRam`
(directly or via the SoC) needs `SimConfig.addSimulatorFlag("-Wno-MULTIDRIVEN")`.

### 3.7 Backdoor-preloading a `Mem`
`DualClockRam.mem` is `Verilator.public` ⇒ `dut.<path>.mem.setBigInt(wordIndex, value)`. The address is
the **word index**, not a byte address.

### 3.8 Never hand-roll a ready-toggling Stream monitor in a woken thread — use `StreamReadyRandomizer`/`StreamMonitor`
The tempting pattern

```scala
fork { while (true) {
  val ready = rng.nextInt(20) != 0
  io.stream.ready #= ready
  if (ready && io.stream.valid.toBoolean) record(io.stream.payload)   // WRONG
  cd.waitSampling()
} }
```

**races**: a thread's `#=` takes effect one edge **later** than its same-wake reads assume, so the DUT
fires at an edge where the monitor believed `ready` was low (byte lost) and stalls where it believed
high (byte recorded twice). Net effect on a byte stream: length preserved but byte *i* replaced by
byte *i+1* around every ready toggle — looks exactly like a DUT buffer bug (cost a full debug session
in `WrPcsLoopSim`; the slot RAM + wire were bit-clean, only the recording slipped). With a **constant**
ready the pattern is harmless, which is why it survives in simple sims. For randomized back-pressure
always use the callback pair, which samples `valid && ready && payload` in one coherent phase:

```scala
StreamReadyRandomizer(io.stream, cd).factor = 0.95f
StreamMonitor(io.stream, cd) { p => record(p) }
```

---

## 4. Driving the qubit core without a CPU program

The shared batch-time `time` is `RegNext(refTime + timeOffset)` and `refTime` is gated by `riscqReset`
(same clock domain as the CPU). So:
- **To advance `time`** in a bus-driven sim you must **deassert `riscqReset`**, which also **runs the
  CPU**. Keep the CPU benign by backdoor-loading a 1-instruction self-loop — `jal x0, 0` = `0x6f` — into
  each core's instruction RAM word 0 (the reset vector `0x80000000`). It loops on the fetch bus and never
  touches `dBus`.
- **To inject RF-config writes** without software, add a **test-only second master** to each core's
  `dMemPortDec` (gated by a `withTest` flag, so the real SoC keeps the CPU as the sole `dBus` master).
  The self-looping CPU + the test `MasterAgent` share `dMemPortDec` through the fabric arbiter with no
  conflict, because the CPU is idle on `dBus`.

### 4.1 In the *full* SoC, release `riscqReset` by writing the host control block
Symptom: `time` stays **0** forever in a `PulseTableSoc` sim (debug prints `advancing: false`), and the
very next `MasterAgent.putInt` into the RF/control tree **hangs** (no `D` response). Cause: in the
toplevel, `riscqReset` is gated by `riscqResetHostCd`, a `factory.drive(...)`-backed register that has
**no init** (same as the reference) — so the cores **boot held in reset**: `refTime` never counts and the
whole `riscqCd` fabric (`dMemPortDec`, the control + RF slaves) never answers a TL request. Fix: the host
must **explicitly release reset over AXI**, exactly as the reference testbench does — write `0x01` (reset
up, hold) then `0x00` (reset down, release) to the host control block (`hostCtrlBase`, `riscqReset`@offset
0), then wait out the `BufferCC(5)+Delay(5)` CDC before scheduling anything. (A qubit-core sim with no
host control block doesn't hit this; it only appears once the host control block is in the loop.)

### 4.2 Checking the readout end-to-end in the SoC: assert *magnitude*, not bit-exact
Problem: a SoC-level "does the ADC reach the readout" test wants to drive `io.adc` and check the
integrated `real/imag` — but the integrated value depends on the **exact** phase the LO carrier and the
externally-driven ADC tone have *at the decoder input*, and those differ by the (constant but non-obvious)
bulk pipeline latency from `io.adc`/`io.time` through the converters/`adcBufs`/demod. Chasing that offset
to predict an exact `real`/`imag` is the deferred "absolute alignment" problem (plan §4).
- **Fix — drive a frequency-matched tone and assert on |z| = √(real²+imag²), VNA-style.** The carrier is
  `exp(iπ·(F·s + φ)/2^(w−1))` at sample index `s = adcN·t + k` (`F` the 16-bit freq code written to the
  demod fiber). Feed a real cosine at the **same `F`**, phase-locked to the SoC batch `time` (read
  `dut.riscqArea.time` in the ADC fork so the tone's frequency *exactly* matches the LO's, independent of
  the pipeline delay). Then the integral is `real ≈ C·cosΔ`, `imag ≈ C·sinΔ` for a **constant** unknown
  phase offset Δ — so **|z| ≈ C = (Aadc·Alo/2^(w−1))·(samples/2) is phase-invariant** and robustly large,
  while a **detuned** tone averages to ~0. Assert `|z|_matched ≫ |z|_detuned` (e.g. ratio ≥ 4×; observed
  ~36×). This needs **zero** knowledge of the pipeline latency.
- **No overflow surprise:** the demod product is `(a·b+½ulp)>>(w−1)` *resized to w bits* before the adder
  tree (`ComplexMul` output is `w`-wide, not `2w`), so the `accWidth`-bit accumulator only grows like
  `w + log2Up(adcN) + durWidth` — `accWidth = 32` is safe for any sane window.
- **Read it back over the real bus:** fire the demod (its slot `dur` is the window), wait past the
  window's opening, then read `res` (which **halts** until the integral settles), then `real`/`imag`. No
  `simPublic` on the decoder needed — go through the TL slave.
- **`io.adc` is a `slave Stream`** the SoC reads **combinationally** (it ignores `valid`); drive
  `.payload` (and `.valid` for tidiness), never `.ready` (the top drives it). Hold every ADC `payload` at
  0 from t=0 so the `robs` ADC-sum reduction never latches X before the readout part runs.

### 4.3 CPU-in-the-loop: build + backdoor-load a tiny program, let the core schedule the pulse
The faithful sign-off replaces the test-master writes with a RISC-V program (so `withTest = false` — the
CPU is the sole `dBus` master). Mechanics that bit:
- **Toolchain.** A working LLVM is at `/config/build/riscv-install/bin` (clang 20 targeting
  `riscv32-unknown-unknown-elf`, `ld.lld`). Build a bare-metal `.S` at the reset vector with
  `clang -target riscv32 -march=rv32i -mabi=ilp32 -mno-relax -nostdlib -static -Wl,-Ttext=0x80000000
  -Wl,-e,_start prog.S -o prog.elf`. **No GP/SP/runtime needed** for a pure-MMIO program (only `li`/`lw`/
  `sw`/`j`). The reset vector is `RiscqParam.resetVector = 0x80000000`; commit the `.elf` next to the `.S`.
- **This LLVM fork pads every instruction with three `nop`s** (a hazard-spacing feature for the RISC-Q
  core). Harmless for our interlocked core (it's RVLS-verified on un-padded ELFs) — don't fight it;
  it just costs a few cycles. `llvm-objdump -d` to sanity-check the `lui`/`addi` constants.
- **Every RF field is in bus bits [16,32)** (SlaveFactory `bitOffset = 16`), so the program stores
  `value << 16` — e.g. `li t2, 0x07080000 ; sw t2, 0x04(t1)` writes `freq = 1800`. The low 16 bits are 0
  so a bare `lui` suffices (no `addi`).
- **Load it** with `new Elf(file, 32).load(image, 0)` (`spinal.lib.misc.Elf` + `SparseMemory`), then
  `core.mem.mem.setBigInt(wordIdx, image.readInt(0x80000000 + 4*wordIdx))` for the program's word range
  (load a generous window, e.g. 256 words; the `j self` loop means anything past it is unreached). Put a
  `jal x0,0` (`0x6f`) in word 0 of the **other** cores so they idle.
- **Don't predict timing — poll.** After reset-release (§4.1) the CPU runs immediately; `simPublic` the
  core's `startTime` reg and spin until it's non-zero (proves the dBus reached the control block), then
  sweep `time` to `startTime + margin` and assert the DAC pulse exactly as the bus-driven SoC sim does. (Observed:
  the program finishes before the post-reset settle wait even completes — `startTime` is already set.)

---

## 5. Module / params gotchas

- **`PulseTableTerm` field widths** must match their destination `PulseGenerator.io` port widths
  (`durWidth ≠ dataWidth` is allowed) — `assignFromBits` requires equal widths. Parameterize each field.
- **`startTime` auto-advance staging (spec 09 B0) is beat-order — the staging is load-bearing.** Every fire
  adds the fired slot's `dur` to that buffer's `startTime`, applied the **beat after** the fire (the fired
  `outId`/`outParam` settle a cycle later). Two facts make the staging matter: (1) back-to-back
  `play()`/`fire()` calls really do produce **adjacent cmd beats** through the posted link, so an explicit
  `set_start` can land on the very beat after a fire — it must **win** over the increment (a
  `!explicitStartWrite` guard; `…fire(a); set_start(t)…` leaves `t`, not `t+dur_a`); (2) the export carries
  one `RegNext` (`io.startTime := RegNext(startTime)`), so — since a fired pulse reaches the `TimedQueue`
  push 2 cycles after its fire beat and the queues sample `startTime` **at push** — each fired pulse
  captures `startTime` **as of its own fire beat (pre-increment)** and back-to-back fires capture the
  running sum. The rejected alternative (increment when `outParamFlow.valid`, 2 beats late, no export stage)
  lets an intervening absolute `set_start` be corrupted (`fire@N, set_start@N+1, inc@N+2` → `t+dur`). The
  extra stage is uniform across buffers ⇒ absolute pulse timing is bit-identical for all existing software.
- **`ReadoutDecoder.accWidth`** is `require`-checked `≥ dataWidth + log2Up(batchSize) + maxWinLog2`, **and**
  must stay `≤ 32` to read the integral back in one TL word. The window length is the **demod pulse's
  `dur`** (16-bit field), so the bound is a *software* contract: the driver rejects a demod-table
  `dur > 2^maxWinLog2` (default 2^14) — hardware can no longer clamp it with a narrow field width.
- **A fire pushed with less than `LEAD` to spare plays with the PREVIOUS pulse's parameters — it is
  neither dropped nor flagged.** Measured 2026-09-14 (universal-cal V0 parity): the generator latches
  the queue head's params `lead` before start, so a late-arriving fire is played at its own `startTime`
  but with whatever params were latched last (silent after a fresh boot, the last pulse's amp/env/dur
  otherwise), and every later fire in the chain is one pulse behind. The heralded grid is the sharp
  edge: `herald_offset` leaves the post-herald drive exactly `LEAD` after the herald window closes, and
  the old kernels fit only because the shot path between `read_res()` and the fire is a handful of
  instructions — four volatile RAM loads (runtime params passed straight into a call) were enough to
  tip it. Keep the shot path free of loads (copy params into locals before the loop, `k_batched`), and
  suspect this whenever a sweep looks "shifted by one point".
- **Per-core `now() + period` grids are NOT aligned across cores — start a multi-core grid from a
  barrier.** Measured 2026-09-15 (universal-cal V5 parity, `tests/test_parity_2q.py`): cores that
  release together and read `now()` after identical preambles still landed 2–23 batches apart, the
  offset growing with the program (the old `k_cz_cond` fired the two lines of a drive-form CZ 14
  batches apart; a coupler-form sweep 7–10). The put network's `barrier(id, count)` returns ONE
  released time to every member, so `t_ro = barrier(grp) + period` gives every core of an
  experiment the same grid exactly (`riscq.cal.batched.k_batched`; a one-core experiment is a
  one-member group). Never compare cross-core timing on `now()`-anchored programs.
- **Readout pacing (carrier-triggered decoder).** The demod carrier's `valid` is the decoder window
  ([specs/new-readout-decoder](../../specs/new-readout-decoder/README.md)); the decoder's `res.valid` is
  forwarded up as a **level** the sink mirrors, so `read_res` is **idempotent** (no arm, no consume).
  Freshness is a *software timing* contract: after firing the demod at `t`, `wait_until(t + RQ_RO_LEAD)`
  before `read_res` so the previous shot's still-held level has dropped (past `winStart`) — reading earlier
  returns the previous shot. Also keep ≥ 1 idle batch between consecutive demod windows (zero-gap windows
  have no falling edge and merge into one integral — and a fire *during* an open window extends it, since
  the generator's `dur` counter reloads).
- **The demod carrier DOES re-tune + re-play on-core** (spec 08 §2.2 / B1; debunks the
  `riscq/cal/readout.py` "can't be re-tuned + re-played on-core" folklore, which had no root cause and
  forced the VNA into a host loop). One program that `set_freq(demod, code)` + re-plays per point
  resolves the matched code (peak at 4× the DAC code) *element-for-element identically* to a host loop
  of one run per code (`software/tests/test_vna_retune.py`). **Requirement:** schedule each retuned play
  far enough ahead that the phasor-regen lead is covered — `set_freq` pushes the freq into the phasor
  queue against the buffer's *current* startTime, and the phasor regenerates `leadFreqP` (≈ linkPipe+52)
  cycles before it; a grid `period ≫ LEAD` between points covers this with margin (`init_pulse_params`
  anchoring startTime to `now()` up front makes the first regen due immediately — *wrap-safely*; see the
  next bullet for why it must be `now()`, not 0). The old failure was almost certainly a play issued too
  soon after the retune, not a HW limit.
- **`set_freq` against `startTime = 0` "sticks" on hardware (the `TimedQueue` wrap window).** *Symptom:*
  sweeping a carrier across reruns, the frequency intermittently doesn't change — sometimes a whole sweep
  reads back at one (stale) frequency; amplitude/phase sweeps never do this; co-sim never reproduces it.
  *Cause:* `set_freq` pushes the freq into the pulse-generator phasor/carrier `TimedQueue`s tagged with
  the buffer's *current* `startTime`, and the queue's due test `!(time + leadC1 − startTime).msb` is a
  SIGNED compare — only wrap-safe within ±2^31 of `io.time`. `io.time` (`refTime + timeOffset`) FREE-RUNS
  and is never reset (the drive-drop fix below), wrapping 2^32 every ~8.6 s. Scheduling against absolute
  `0` while `io.time` is in the upper half (≥ 2^31, ~half of every uptime cycle) reads as ~2^31 in the
  *future* ⇒ the entry never pops ⇒ the phasor regen never fires ⇒ the carrier holds its previous
  frequency. Co-sim starts `refTime` at 0 and never reaches 2^31 in a short run, so it always "works"
  there — the bug is hardware-only. Amplitude/phase are immune: they're pushed on `fire`, scheduled at the
  pulse's own `startTime = now()+LEAD` (always within `LEAD` of `io.time`, hence always in-window).
  *Fix:* schedule the freq against a near-`now()` time — `init_pulse_params` now does `set_start(ch,
  now())` (was `0`), which is bit-exact in co-sim (both due immediately when `time ≈ 0`) and correct on
  hardware, so every `init_pulse_params; set_freq` kernel (all of `riscq.cal.kernels`) is covered. A
  hand-written kernel that `set_freq`s *without* a preceding `init_pulse_params` must likewise anchor its
  `startTime` to `now()` (or the pulse's `t`), never 0. Deterministic repro without hardware: write
  `timeOffset ≥ 2^31` to the host control block so `io.time` lands in the upper half — the old
  `set_start(0)` schedule then fails while the `now()` schedule still tracks.
- **`wait_until` released early across the 2^32 time wrap — a run's RAW shots collapse to ONE repeated IQ
  value from some shot index on, on every core at once (fixed 2026-09-18).** *Symptom:* on hardware only,
  intermittently (~`run_seconds / 8.6` of runs), a contiguous tail or stretch of a rerun's shots carries
  the same `real/imag` pair on all cores, starting at the same shot index everywhere and ending at a
  different index per core; the `ReadoutCalibration` hexbin shows one black dot with ghost clouds. Looks
  like a readback/DMA fault but survives `host=False`. *Cause:* `TimeMemMap.waitTimeCmp` was the unsigned
  `time + 3 < timeCmp`. On the shot where `t_ro + tail` crosses 2^32 while `time` has not, `timeCmp` is
  tiny and the halt releases at once — for up to one grid period every `wait_until` is a no-op, the CPU
  runs the shot loop flat out, its pulse posts overflow the depth-4 `TimedQueue`s down the no-back-pressure
  `Flow(Put)` (dropped, never played) and `read_real/imag` return the latest-value sink's last real shot every
  iteration. When `time` wraps too the compare works again, the CPU waits out the lead it built up, and
  the run finishes normally. *Fix:* the halt is now the signed distance `(timeCmp − (time + 3)).asSInt > 0`
  (`ControlMemMaps.scala`), wrap-safe within ±2^31 like the `TimedQueue` test above; `ControlMapFiberSim`
  holds a read across the wrap. Detector for old bundles: `np.unique(iq, axis=0, return_counts=True)` on a
  RAW capture — a max count in the hundreds is this.
- *(Historical — the armed decoder, removed by the carrier-triggered rewrite.)* The old software-armed
  window had a startTime-before-`dur` ordering contract whose violation latched a stale previous-shot
  window that silently survived resets (`decStartTime` lived in `dspCd`); a matched readout then collapsed
  to a fixed fraction on every re-play, masquerading as "the carrier dies on re-fire" (commit `4e55a2c`).
  That bug class — not just the bug — is why the decoder is now triggered by the carrier itself.
- **A pulse un-drained at a run's end drops the NEXT run's drive** (the "multi-pulse batch corrupts the
  next run" folklore; spec 08 §3 / B0). **Symptom:** after a program schedules a drive pulse well in the
  future and returns before batch time reaches it, the *next* program's drive never reaches the DAC (the
  captured window is all-zero); a program that waits past its last pulse leaves the next run clean.
  **Cause:** each `PulseGenerator` parameter (amp/phase/env/dur) is a `TimedQueue` (async-read
  `StreamFifo`) that pops when `time ≥ startTime − lead`. The generators live in `dspCd`, which
  `riscqReset` does **not** reset. When `refTime` (hence batch `time`) was *also* zeroed at each run's
  reset, a leftover entry kept the previous run's large `startTime`, sat at the FIFO head "in the future,"
  and blocked every fresh entry queued behind it (FIFO order); `init_pulse_params` only clears the
  buffer's `startTime` register, it cannot drain the queues. **Fix:** make `refTime` **free-running** in
  `dspCd` — NOT reset by `riscqReset`, only by the external `dspRst` — so batch `time` is **monotonic
  across runs** (`PulseTableSoc.riscqArea`). A leftover entry then falls into the *past*, pops as `time`
  sweeps past its `startTime`, and drains on its own before the next run schedules anything, so runs are
  independent with no per-run queue flush and no software preamble. Software works in now()-relative time,
  so the ever-growing base is transparent. Repro: `software/tests/test_batch_drivedrop.py`
  (drained-vs-un-drained A/B pins it to the leftover entries).
- *(Folklore, retired — the warm-up row / "cold first readout".)* Every cal once discarded its first
  measurement ("the cold-decoder first read"; "absorbs the cold-first-readout verilator settle"), later
  batched as a discarded row 0 repeating point 0 (specs 08/09). It was never an analog/settle effect —
  Verilator is two-state and deterministic. The three real mechanisms behind bad early reads are all
  fixed: the armed decoder's never-reset `decStartTime` + arm-ordering (removed by the carrier-triggered
  rewrite — the first `res` read now HALTS until the first window's integral settles, sink `init False`),
  the un-drained timed-queue drive-drop (free-running `refTime`, above), and stale amp/phase in a fresh
  SoC's first window batches (the `LEAD = 96` contract, `map.py`). `test_batch.py::test_first_row_clean`
  pins it: a run's first window is bit-identical to later ones. [Spec 11](../../specs/software/11-remove-warmup.md)
  removed the row; first-read hygiene is a *timing* contract (`LEAD` / `wait_until(t + RQ_RO_LEAD)`),
  not a discarded measurement.
- *(Co-sim infra corollary.)* `riscq.sim.rtl.ensure_rtl` caches the generated `PulseTableSoc.v` on the
  **config-JSON hash only** — after editing the *RTL Scala* it returns the stale `.v`, so the co-sim
  silently tests the un-patched design. Delete `software/build/<name>/` (or its `rtl/.config.sha`) to
  force a regen before trusting a co-sim run after an RTL change.
- **Elaboration check for a fiber `Area`**: wrap it in a `Component` that supplies the clock domains and
  **stub host masters** (`MasterBus`) for its otherwise-dangling slave nodes (`iMemPortArb`,
  `pulseMemFiber.up`), else the fabric can't negotiate. Drive any init-only `Reg`s (e.g. `time := time +
  1`) to silence `UNASSIGNED REGISTER` errors.

---

## 6. Scala / build

- **A dual-clock SoC needs a custom two-clock XDC — the generic `riscq.bench.VivadoBench` engine only
  constrains `clk`.** That engine writes `create_clock … [get_ports clk]` and nothing else, so on the
  SoC it would leave the whole `dspClk` domain — the actual critical path — **unconstrained** (no WNS, a
  meaningless fmax). Write a SoC-specific bench (`riscq.soc.bench.PulseTableSocVivadoBench`) that emits its
  own XDC: tight `create_clock dspClk`, a loose `create_clock clk` at its real 100 MHz, and
  `set_clock_groups -asynchronous -group {dspClk} -group {clk}` so the `BufferCC` host↔dsp CDC paths are
  excluded (else they dominate WNS with a bogus cross-clock requirement). With the host clock loose it
  always meets, so the global WNS — hence the reported fmax — is the `dspClk` critical domain. Observed
  ~555 MHz (2-qubit, OOC synth), critical path **inside the RISC-V core**, not the DSP datapath. Reuse
  `VivadoBench.parse`/`Path` for the `report_timing` output; only the run + XDC differ.
- **`Map.collect` that yields a *tuple* rebuilds a `Map` — and silently de-dups keys.** A subtle,
  high-impact bug. `dacMap.collect { case ((c, ch), id) if id == dacId => (c, ch) }` returns `(c, ch)`
  2-tuples, so Scala builds a **`Map[c, ch]`**: two channels of the *same core* (gate `(0,0)` + readout
  `(0,1)`) **collide on key `c` and the gate is silently overwritten** — the pulse never reaches its DAC.
  **Symptom:** a DAC that should sum N channels takes the `pulses.size == 1` path; the Verilog shows only
  *one* channel feeding the DAC register (e.g. `_zz_…dacPayloads_8 <= {…dac_1_…re}`, no adder, no other
  channel anywhere). **Fix:** `dacMap.toList.collect { … }` — `.toList` first, so `.collect` runs on a
  `List` and returns a `List[(c,ch)]` with no key de-dup. (Same trap for any `collect`/`map` over a `Map`
  whose body returns a 2-tuple: force a `Seq`/`List` first, or `.iterator.collect{…}.toList`.)
- **Diagnosing "right input, zero output" datapaths:** when a probe shows a signal non-zero but its
  consumer's output is stuck (here: `core.dac(0)(0).re` = the pulse, but `io.dac(8)` = 0), **read the
  generated Verilog** (`grep` the output port back to its driver reg, then the reg's RHS). The netlist
  showed `dacPayloads_8` was fed by `dac_1` (not the summed `dac_0+dac_1`) — instantly localising the bug
  to the channel-selection logic rather than the (correct) `AdderTree`/`RegNext` datapath. Faster than
  adding more `simPublic` probes.
- **Scala 2.12 forbids `_` in numeric literals** (`0x0000_0064` is a parse error) — write `0x64`.
- **`math.max` resolves to `spinal.lib.math`** under `import spinal.lib._`; use `scala.math.max`.
- **Run one `mill` invocation at a time** — parallel invocations contend on the mill server and time out.
- **Trust the mill build, not metals diagnostics** (metals lags after new files/imports).

---

## 7. Multi-qubit fmax/area: DSP-spill into LUTs, and the `KEEP_HIERARCHY` fix

Scaling `PulseTableSoc` to many qubits (14) exposed a **synthesis-flow** trap that looks like the design
is too big for the part but isn't. Read this before benching/scaling any DSP-heavy multi-instance SoC.

### 7.1 Symptom
At 14 qubits the OOC synth reports **439k LUT (103% of the `xczu49dr`)** and `place_design` **fails**
(`[Place 30-99] Placer could not place all instances`, 460k LUT > 425k capacity). Vivado also warns
**`[Synth 8-3323] Resources of type DSP have been overutilized. Used = 5082, Available = 4272`** — yet
the final report shows only **2896 DSP used (68%)**, leaving 1376 DSPs idle while LUTs overflow. fmax
also "drops" (442 MHz, critical path in `ComplexMul`'s `m2` recombination carry chain) vs the 2-qubit
~555 MHz (which was RISC-V-core-bound).

### 7.2 It is NOT linear-scaling or a real capacity limit — measure to prove it
Per-block synthesis is **perfectly linear** up to the DSP budget. A `PulseGenerator` is **81 DSP +
~3.2k LUT** at *both* N=2 and N=7 (187 DSP/core, ~16k LUT/core; no overflow warning). Linear
extrapolation → 14 q ≈ **2618 DSP / ~220k LUT**, which fits with margin (61% DSP, 52% LUT). So the
14-qubit blow-up is the *tool*, not the design. **Diagnose by:** (a) re-synth at smaller N and check
DSP/core is constant; (b) `report_utilization -hierarchical` — the same `PulseGenerator` module shows
~3.2k LUT at N≤7 but 6–11k at N=14 (and two identical instances diverge, e.g. 6k vs 11k, by allocation
order); (c) the RISC-V core is only **2.2k LUT (7% of total)** — the LUTs are 71% in the multiply-heavy
datapath, not the CPU.

### 7.3 Cause
Vivado infers multipliers in two passes: a naive count (~363 DSP-candidates/core) then Karatsuba
pre-adder packing into the DSP48E2 that reduces it to ~187/core. At N≤~11 the naive total stays under
4272, so packing runs and everything fits in DSP. At N=14 the naive total (~5082) **exceeds 4272**, so
the resource-balancer **preemptively spills ~2186 multiplies into LUTs** before the packing pass — even
though the packed design needs only ~2618 DSP. Hence 2896 DSP (under-shoot) + 103% LUT.

### 7.4 Fix — `KEEP_HIERARCHY` on the datapath **blocks** (not on `ComplexMul`)
Fence each DSP datapath Component so it synthesizes as a self-contained unit and packs its ComplexMuls
into DSP48E2s independently, before any global budget check:
```scala
case class PulseGenerator(...) extends Component {
  this.addAttribute("KEEP_HIERARCHY", "TRUE")   // likewise ReadoutDecoder
```
Measured at 14 q (default synth flow, two-clock XDC):

| N=14 approach | LUT | DSP | DSP-overutil warn | fmax | crit path | fits? |
|---|---|---|---|---|---|---|
| default (flatten) | 438k (103%) | 2896 | yes | 442* | ComplexMul `m2` | **no — place fails** |
| `KEEP_HIERARCHY` on **ComplexMul** | 388k (91%) | 3543 | yes | 399 | ComplexMul `m2` | marginal |
| **`KEEP_HIERARCHY` on datapath blocks** | **216k (51%)** | **3066 (72%)** | **no** | **539** | **RISC-V core** | **yes** |
| global `-flatten_hierarchy none` | 214k (50%) | 2618 | no | core-bound | — | yes |

(*442 MHz was a congestion artifact of the bloated netlist.) The block-level fence **matches the global
`-flatten_hierarchy none`** on area, fits with headroom, and restores fmax to ~539 MHz with the path
back inside the RISC-V core — but is *surgical* (top level and cores still flatten/optimize normally).

### 7.5 Why NOT `ComplexMul`, and NOT routing
- **Fence at `ComplexMul` is worse on every axis** (388k LUT, 3543 DSP, 399 MHz). The efficient
  3-DSP/lane packing needs Vivado to **retime the enclosing PulseGenerator's pipeline registers *into*
  the DSP48E2** (pre-adder reg / CREG / M-reg). A fence at the `ComplexMul` boundary cuts straight
  through that pipeline, so the registers can't fold in — pre-adds/`m2` spill to LUT and paths lengthen.
  The fence must enclose the **whole DSP pipeline + its registers** ⇒ the datapath block, not the mul.
- **Routing/implementation does NOT recover it.** This is a *synthesis* mapping decision: `opt_design`
  only trims ~5% (438k→418k via LUT-combining); it can never move a multiply back into a DSP. Don't run
  place&route hoping the LUTs shrink — fix the synthesis fence.

### 7.6 Bench implication
`PulseTableSocVivadoBench` uses the **default** synth flow (no `-flatten_hierarchy none`), so it reports
honest multi-qubit numbers **only because** these blocks now carry `KEEP_HIERARCHY`. Without the fence
the bench over-reports LUT ~2× and shows an artificial DSP-datapath critical path. (`KEEP_HIERARCHY` is
synthesis-only — no functional effect; the standalone sims/goldens and the standalone P6/R4 fmax benches
are unaffected, the datapath packs to its usual ~97 DSP/3k LUT.)

### 7.7 The fence costs DSP — `KEEP_HIERARCHY` blocks cross-boundary dead-output elimination
The block fence fits, but it uses **more DSP** than `-flatten_hierarchy none` (7.4: 3066 vs 2618 = +448 =
14 cores × 2 drives × 16). This is **not** a packing difference — it's **dead-code elimination across the
fence**. The mechanism, pinned by per-instance synthesis (a standalone `PulseGenerator` is **97 DSP** in
*both* flatten modes — so it is **not** a "denser template" effect; the lean **81** appears only in SoC
context):

- The SoC routes only the **real** pulse lane to the DAC (`RiscqRf…dac(0) := pg.io.pulse.payload`;
  `PulseTableSoc.dacMap` uses `_.re`), so each `PulseGenerator`'s `io.pulse.payload(k).im` is **dead**.
- The envelope `ComplexMul` is a 3-DSP Karatsuba: shared `M`, real-output mult `PRE`, imag-output mult
  `PIM`. With the imag output dead, Vivado prunes `PIM` ⇒ each `envMul` is **2 DSP**, −16/PulseGenerator
  (the carrier ComplexMuls stay 3 — both their outputs feed the envelope mult).
- **`-flatten_hierarchy none` (soft boundary) propagates this pruning across the `PulseGenerator`
  boundary; the hard `KEEP_HIERARCHY` fence blocks it** (it forbids optimization that depends on what's
  unused *outside* the block), so the fence keeps all 3 DSP.

**Fix — kill the dead output at the source, not at the fence:** `PulseGeneratorParams.realOutput` (default
false; set true by `RiscqRfWithPulseTableFiber.mkDrive`) drives `io.pulse.im := 0` **inside**
`PulseGenerator`. The dead output is then **intra-block**, so Vivado prunes `PIM` by ordinary intra-block
DCE **even under the fence** (verified: a standalone `PulseGenerator` with `im := 0` drops 97 → **81 DSP**,
each `envMul` 2 DSP, under default flatten). This is strictly better than relying on `-flatten none`'s
cross-boundary pruning: **keep the `KEEP_HIERARCHY` fence** (so the §7.4 LUT-spill protection stays) **and**
get the lean DSP count — 14 q ≈ **2618 DSP @ ~539 MHz**. It needs **no `ComplexMul` change**.

`realOutput` is opt-in because the carrier ComplexMuls and the `ReadoutDecoder`/demod genuinely need both
I and Q (and a future IQ-DAC path would too); the standalone `PulseGenerator` keeps the full complex
output. Bit-exactness: `PulseGeneratorSim` adds a `realOutput=true` run asserting the **real lane is
identical to the full-complex golden** and the imag lane is 0 — the optimization removes only dead logic.

**General lesson:** a `KEEP_HIERARCHY` fence preserves *unused* logic that the surrounding context would
otherwise let synthesis delete. If you fence a block for DSP-packing reasons, also make sure its outputs
are all actually consumed — or tie the dead ones off **inside** the block so intra-block DCE still fires.

---

## 8. Taking the SoC to real ZCU216 hardware (`vivado-scripts/`)

The maintained Vivado flows for `PulseTableSoc` on the `xczu49dr` (built with the `vivado` on `PATH`,
currently 2026.1) are **`riscvsoc-bd/`** (block
design + IP packager, mirrors RISC-Q `gen-project.sh`) and the **`riscvsoc/`** OOC place&route bench. An
earlier **flat** flow (flat RTL with the PS/RFDC as SpinalHDL blackboxes, top `Zcu216TopFlat`) was
removed; its generator `Zcu216TopFlat` and the design lessons below (8.2 / 8.4 / 8.5) are kept for
reference. The non-obvious bits hit while writing them:

### 8.1 The `vivado=true` flag must stay opt-in — it renames the host clock
**Symptom:** adding `X_INTERFACE_INFO` for the IP packager also wants `hostCd.renamePulledWires("hostClk",
"hostRst")`, which renames the top-level `clk` port → and immediately breaks `PulseTableSocVivadoBench`,
whose XDC does `create_clock … [get_ports clk]`.
**Fix:** gate *all* of it behind `PulseTableSoc(vivado = false)` (default). `GenPulseTableSocVivado` sets it
true; every sim/bench keeps the plain `clk`. The attributes themselves are sim-neutral (pure Verilog
attributes) — it is only the rename that bites.

### 8.2 None of the Xilinx AXI fabrics work outside a block design (for a routed 1→N)
**Symptom:** "use an AXI Interconnect instead of SmartConnect, since SmartConnect needs a BD." But
`create_ip xilinx.com:ip:axi_interconnect:2.1` errors *"only supported in IP Integrator"*, and the classic
**v1.7** — though it *does* `create_ip` standalone — doesn't expose `NUM_MASTER_PORTS` / per-master
`M0x_A00_BASE_ADDR` via Tcl in 2024.2 (the params simply don't exist on the IP).
**Fix:** build the fan-out in **SpinalHDL** (`Axi4CrossbarFactory`) inside the flat top (`Zcu216TopFlat`).
It *is* an AXI interconnect, just SpinalHDL-generated, and keeps the flat top truly block-design-free.
SmartConnect is fine in the block-design flow (`riscvsoc-bd`) because that flow *is* a block design.

### 8.3 ZynqMP `zynq_ultra_ps_e` has **no** DDR / FIXED_IO fabric ports
Unlike Zynq-7000's `processing_system7` (DDR_* / FIXED_IO_* inout buses you must thread to the top), the
UltraScale+ PS keeps DDR + MIO on dedicated PSU pins — with `PSU__USE__M_AXI_GP2 + FPGA_PL0` the IP's
instantiation template is just `maxihpm0_lpd_aclk`, the `maxigp2` AXI master, `pl_clk0`, `pl_resetn0`. So
the flat top needs **zero** `inout(Analog(...))` passthrough — a big simplification over what Zynq-7000
would require. (Confirm any IP's real port list from its Vivado `.veo` instantiation template — don't
hand-guess widths.)

### 8.4 `Axi4SpecRenamer` makes a SpinalHDL `Axi4` blackbox bind to Xilinx flat ports for free
Name the blackbox bundle `maxigp2` and call `Axi4SpecRenamer(maxigp2)`: SpinalHDL emits leaf ports
`maxigp2_awaddr` / `maxigp2_awvalid` / `maxigp2_awuser` / … — exactly the PS's port names, so the blackbox
binds by name with no per-signal `setName`. Match the `Axi4Config` to the IP (`useRegion = false`,
`awUserWidth = 16`, etc.) so the renamed signal *set* is identical (extra/missing ports = bind failure).

### 8.5 `Axi4CrossbarFactory` requires `master.idWidth ≤ every slave.idWidth`
**Symptom:** `assertion failed: Expect … idWidth=16 <= … idWidth=2` — the PS master is 16-bit id, the SoC
control port is `Axi4(32,32,2)`.
**Fix:** `Axi4IdRemover(ps.maxigp2)` before the crossbar (id 16 → 0; it stores + echoes the original id).
Trade-off: it serialises outstanding host transactions — fine for the low-rate PS control/config path, and
the only clean option short of widening the SoC's fixed id width.

### 8.6 Keep the TCL proc-free with `source`d includes
The block-design driver (`riscvsoc-bd/flow-bd.tcl`) sets script-scope config vars then `source`s ordered
`inc/*.tcl` steps; because nothing runs inside a `proc`, every step sees `$TOP`/`$ZYNQ_PS`/`$RFDC_TARGET`/…
at global scope — no `global` declarations, no thread-the-variable-through-every-proc tax that the RISC-Q
`riscq-project.tcl` pays. `inc/rfdc-config.tcl` writes to `$RFDC_TARGET` (`[get_bd_cells …]` for the BD;
the indirection once also served the flat flow's `[get_ips …]`).

### 8.7 riscvsoc-bd: synth args set on `synth_1` do NOT reach the cores — the IP synthesises OOC
**Symptom:** set `STEPS.SYNTH_DESIGN.ARGS.<anything>` (control-set threshold, strategy, GLOBAL_RETIMING)
on `synth_1` in the block-design flow and re-run → the result is **byte-identical**; `report_utilization`
shows the same LUT/FF/control-set counts. The Vivado GUI even confirms the property *is* set on `synth_1`.
**Cause:** in a block design the whole `PulseTableSoc` (all 14 cores) is packaged as a **user IP**
(`create_bd_cell -type ip … user.org:user:PulseTableSoc:1.0 top`, bd-build.tcl) and a user IP inside a BD
**synthesises out-of-context in its own child run** — here `riscq_bd_top_0_synth_1` (=`${BD_NAME}_top_0_synth_1`).
`synth_1` only synthesises the BD *wrapper* (PS/RFDC/SmartConnect stitch — ≈no core FFs), so every arg you
put on `synth_1` is applied to a run that doesn't contain the logic you care about. (This is also why a BD
synthesises the user IP at ~default LUT despite `synth_1` carrying `Flow_PerfOptimized_high`: the perf
strategy never reached the cores either.) Confirm the split with `find … -name '*.runs'`: the BD has a
`riscq_bd_top_0_synth_1` child run alongside `synth_1`. (A flat top — cores included — would instead be
`synth_1` itself, so there `synth_1` args DO apply.)
**Fix:** set the arg on the **IP OOC run**, not `synth_1`. Subtlety: that run **does not exist** when
`run.tcl` executes — Vivado materialises the BD's OOC child runs only *at* `launch_runs` (it both creates
and launches them in one step; `generate_target all` does NOT create the run objects, so a bare
`get_runs … *_top_*` returns empty and warns). Create them first with `create_ip_run` — the exact idiom
Vivado's own `scripts/project/synth_bd.tcl` uses (`create_ip_run [get_files $bd]`) — then set the property:
```tcl
if {[llength [set bd [get_files -quiet $BD_NAME.bd]]]} { catch { create_ip_run $bd } }
foreach r [get_runs -quiet -filter {IS_SYNTHESIS && NAME =~ *_top_*}] {
  set_property STEPS.SYNTH_DESIGN.ARGS.CONTROL_SET_OPT_THRESHOLD $N $r
}
launch_runs synth_1 -jobs 8   ;# now launches the already-created, already-configured IP run
```
`create_project -force` (create-project.tcl) rebuilds the project fresh each run, so the IP run is new and
re-synthesises with the arg — no stale-cache trap. The `RISCQ_CSET_THRESH` / `RISCQ_IP_RETIMING` knobs in
`riscvsoc-bd/inc/run.tcl` do exactly this. (A flat top would instead carry the cores in `synth_1`, where
`synth_1` args apply directly.)

### 8.8 A cross-clock FIFO's push side must NOT sit in `riscqCd` — the per-run reset desyncs it

**Symptom (avoided by design):** a `StreamFifoCC` whose push domain is `riscqCd` and whose pop domain is
`hostCd` starts emitting garbage after the second run.
**Cause:** `riscqCd` is `dspCd`'s clock with the **per-run core reset**. A cross-clock FIFO's occupancy is
`pushPtr − popPtr` compared across the domains; asserting `riscqReset` zeroes only the push pointer, so
the pop side reads a huge fake occupancy and drains stale/never-written entries.
**Fix:** put the push side in `dspCd` (same clock, board-level reset only) and let the riscqCd logic drive
its push port combinationally — `RiscqRfWithPulseTableFiber.hostWinFifo` does exactly this, and there is
already precedent for dspCd registers fed from riscqCd logic (`getPipe(riscvSoc.cmd, linkPipe)`). Beats
are atomic, so a mid-run reset just drops `valid`; whatever was already accepted drains harmlessly.
See [HostWindow](HostWindow.md).

### 8.9 The cores run BEFORE the host asserts `riscqReset`, and a wrapped PC finds a freshly loaded program

**Symptom:** a CPU-in-the-loop sim that backdoor-loads a program, *then* pulses `riscqReset`, sees the
program's first stores twice — once from a phantom early run.
**Cause:** `riscqResetHostCd` is a `SlaveFactory.drive` register that powers up **deasserted**, so
`riscqReset` releases as soon as `dspRst` does — long before the testbench's first AXI write. The cores
fetch all-zero (illegal) words whose PC **wraps** the 16 KB RAM (`fetchPcWidth = log2Up(memDepth) + 2`),
so the moment the image lands in RAM the wrapped PC runs it, against whatever state the run needs.
**Fix in a testbench:** write `riscqReset = 1` as the **first** AXI transaction, before loading anything
(this is what `riscq.run.setup` does on hardware), and clear any sim-side observation state just before
releasing. `HostWindowCpuSim` does both; `PulseTableSocCpuSim` predates the issue and is unaffected only
because its phantom run cannot reach a converter.

### 8.10 Host-window results are POSTED — never read the buffer from compiled or C code

**The rule:** the host result buffer ([HostWindow](HostWindow.md)) is read from **python, after
`poll_done`** — never from a program or from C straight after the status read.
**Why:** every stage of the window path is posted. A core sees a window store retire in one cycle;
the completion signal (the `done` register in the host control block since
[specs/software/23](../../specs/software/23-done-register.md); `__rq_status` in URAM before it) is
written by the program *after* its last result store was accepted, but that store may still be
sitting in a CC FIFO or on the AXI channel.
There is deliberately no drain/idle bit — the last write lands within microseconds (worst case a few
hundred single-beat writes at 100 MHz), while the host's path from observing DONE to touching the
buffer is Python on the ARM: a 5–10× margin on the tightest path, 100× on the client path.
**If that ever has to go:** a `pending` counter (accepted − B responses) in `HostWindowFunnel` — one
register, one poll. Across cores nothing needs ordering; each owns a disjoint 16 MB slice.

### 8.11 Completion is a REGISTER, not a memory word — and that is what keeps the CPU-mem slave legal

**The rule:** the host learns a core has finished by reading the host control block's `DONE` word
(`0x50`, bit per core), never by polling a word in the core's RAM. `start.S` sets the core-local
`done` register (`0x4010`) as its last store; `riscqReset` is its only clear.
**Why:** the core's RAM port 1 is shared by instruction fetch and the host image-load master through
a round-robin fabric arbiter, and its slave is the stripped `TileLinkCpuMemFiber` — no reorder
buffer, `a.ready := True`, and a simulation `assert` that its d channel is never back-pressured.
That assert used to be justified by "the host only touches the port while the core is in reset",
which the run protocol has not honoured since spec 03 (the poll, the result read-back and the
mid-run host→core input path all overlap live fetch). The premise that actually holds is arithmetic:
**host transactions in flight ≤ d-channel buffering back to the AXI bridge** — 4 (`slotsCount`) ≤ 14
(3 × `StreamPipe.FULL` + the dsp→host `FifoCc` `dDepth`), with a `require` in `PulseTableSoc`.
**What to re-check:** anything that raises `slotsCount`, or issues bursts/DMA into a core's RAM,
breaks that inequality. The board driver reads word-at-a-time for a related reason.
**Bonus:** one read covers every core, and parked cores never raise their bit — so `poll_done` takes
the list of cores that were actually given a program.

---

## 9. The universal-control refactor (cores built from a channel list)

Gotchas from making the SoC build every core from its [`SocSpec`](SocSpec.md) channel list
(`specs/universal-control/01`) instead of a fixed gate/readout/demod tree.

### 9.1 A channel slot no core has a bank in must get NO region bus

**Symptom:** adding a bank-less channel kind (`dio`, `env_depth = 0`) makes elaboration fail inside the
`tilelink.fabric` decoder for that slot's host region.
**Cause:** [`SocSpecMap`](SocSpec.md) gives every channel slot `j` a host region, but a fabric `Node`
must have at least one slave downstream — a region whose only channels have no envelope RAM has none.
**Fix:** build the region bus only when some core has a bank in that slot (`PulseTableSoc.envBuses` is a
`Seq[Option[Node]]`; the core fiber's `envMems` is likewise an `Option` per channel). The slot keeps its
address-map hole (1-byte stride) so every other slot's base is unchanged.

### 9.2 `setName` on a child drops the parent prefix — use `setCompositeName` / `setPartialName`

**Symptom:** a block named per channel comes out with no parent in its name — every core's bank is a
bare `gateMemFiber`, an `io`-bundle port a bare `dio_q0_ttl_out` instead of `io_dio_q0_ttl_out` — so
netlist names stop matching the pblock/XDC patterns that key off the hierarchy.
**Cause:** `setName` is **absolute** (`mode = ABSOLUTE`): it replaces the whole name, parent prefix
included.
**Fix:** name a child relative to its owner — `setCompositeName(this, s"${ch.name}MemFiber")` for the
per-channel envelope banks and channels (`<parent>_<postfix>`), and `setPartialName(s"dio_${n}_out")` for
a member of the `io` bundle (`OWNER_PREFIXED`, so the `io_` prefix survives).

### 9.3 An internal `Flow` of a Component cannot be read by the parent — make it an `io` port

**Symptom:** the parent reads a child's internal signal (a report `Flow`, a derived time) and elaboration
fails, or the signal is silently pruned/unassigned.
**Cause:** SpinalHDL only lets a parent see a child's **IO**; a plain internal signal of a hard
`Component` is not visible across the boundary (the same rule that forces `RiscvSoc`'s `iLoad`/`dTap` to
be slave-IO, §1).
**Fix:** export it. [`TimedDio`](TimedDio.md) publishes its edge report as `io.event` (a `master Flow`)
plus `io.eventTime` / `io.time` `out` ports, and the shell wires those into the up-link's `EventSource`.

### 9.4 A `TimedQueue` pops at most every OTHER cycle, and holds `queueDepth` entries

**Symptom:** a DIO train programmed with `dur = 1` misses edges; a long train plays its first few entries
and drops the rest.
**Cause:** the queue's pop FSM is `pop = timeUp && !RegNext(pop.valid)` — a 1-cycle blank after every pop
(II = 2) — and a push into a **full** queue is silently dropped (posted semantics, no back-pressure
reaches the core).
**Fix:** the shortest hold of an entry followed by another is **2 batches** (assert it in software), and
pace a train to at most `queueDepth` entries scheduled ahead (the core's `queue_depth`, 4 by default) —
the `riscq.cal.base.TRAIN_AHEAD` idiom. Both limits are shared with the pulse channels, and both are exercised by `TimedDioSim`.

## 10. The put network (cross-core puts, `specs/cross-core/02`)

### 10.1 A `Vec(Reg)` element you `val`-bind is a reference, not a copy

**Symptom:** `val w = regs(g); w(slot) := bit; regs(g) := w` elaborates but the word never changes (or
the "copy" mutates the register directly), and a broadcast of `w` carries the *old* value.
**Cause:** `regs(g)` is the register (a muxed read when `g` is a `UInt`); assigning into it assigns the
register. There is no value semantics.
**Fix:** `val w = cloneOf(regs(g)); w := regs(g); w(slot) := bit; regs(g) := w` — the `PutHub` group
word update. Same for a `Bits` you mean to derive from a register.

### 10.2 A `StreamArbiter` `roundRobin` serves same-cycle sources in a *rotating* order

**Symptom:** a model that assumes "lowest index first" among sources valid on the same cycle mismatches
the hub's per-word sequence after the first round.
**Cause:** the round-robin priority rotates after every grant; the order among simultaneous sources
depends on history.
**Fix:** check order-insensitive properties (the final word after each burst, the beat count, per-source
order), as `PutHubSim` does — never the interleaving.

### 10.3 Do not edit Scala while the co-sim tier runs

**Symptom:** `pytest --cosim` reports `RuntimeError` setup errors on the 3-core (`sim-2q1c`) tests, or
tests that pass alone, after you touched `src/` during the run.
**Cause:** each co-sim config generates its RTL with `mill` *at test time*, so a half-edited tree is
elaborated mid-run.
**Fix:** finish and compile the Scala change, then start the tier; edit only docs/python meanwhile.

### 10.4 Linking an RV32 sim program needs `lld`

**Symptom:** the `clang -target riscv32 …` line in the `sw/*.S` headers fails with
`unrecognised emulation mode: elf32lriscv`.
**Cause:** the default system `ld` is x86-only.
**Fix:** add `-fuse-ld=lld` (`/config/build/riscv-install/bin/ld.lld` exists); `sw/xcore.S` documents
the full line.

### 10.5 `hubIn` must be driven by the top — a default drive is an `ASSIGNMENT OVERLAP`

**Symptom:** `ASSIGNMENT OVERLAP` on `riscqCores_i_hubIn_valid` when the SoC top connects the hub.
**Cause:** a `Flow` declared in the core shell with a default (`valid := False`) and then `<<`-driven by
the parent is two full drivers, not a default plus an override.
**Fix:** declare it undriven in the shell and require the top to drive it (`PulseTableSoc` is the only
instantiator); a top without a hub would tie it off itself.

### 8.9 A LOC'd GT's BUFG_GTs must escape every hard confine pblock

A `GTYE4_CHANNEL` LOC'd to a site (WR link: `X0Y4`, SFP0) can only drive `BUFG_GT`/`BUFG_GT_SYNC`
cells **in its own clock region** (`rule_gt_bufggt` and friends). If the transceiver wrapper's
subtree is swept into the hard datapath confine (`X1Y0:X5Y7`), the IO Clock Placer fails with
"illegal clock rule" — and the workaround Vivado prints (`CLOCK_DEDICATED_ROUTE ANY_CMT_COLUMN`)
would move the recovered/link clocks onto general routing, which for White Rabbit destroys the
deterministic-latency premise. Fix at the *membership* level, like the URAM `mem` exclusion: keep
`NAME !~ ${base}/wrGtyPhy_1/*` out of the pblock filters (`pblocks-bd.tcl`) so the GT-adjacent
buffers and bring-up FSMs float to the GT's region. The WR *node* (PCS/TSU/register) logic is
plain fabric on BUFG_GT-driven clocks and confines fine.

### 8.9 One `Mem.write` call = one write port — multiple calls un-infer the RAM into LUT loops

Every `Mem.write(...)` *call site* elaborates a separate write port (five `when`-branch calls in
`WrRxPcs` emitted five clocked `always` blocks writing `slots_buf`). Verilator simulates that
happily (`-Wno-MULTIDRIVEN` hides it), but Vivado cannot map a multi-write-port array to any RAM
primitive: it resolves the drivers into register/LUT logic **with single-LUT feedback loops** —
fatal `DRC LUTLP-1` ("combinatorial loop") at implementation, invisible until a hardware build.
When branches are mutually exclusive, fold them into ONE enable-gated port: default
`en = False` / `data = 0` wires that the branches assign, and a single
`mem.write(addr, data, enable = en)` outside the `when` tree — bit-exact (last assignment wins,
same as SpinalHDL's port ordering), and the RAM infers cleanly. Same-cycle read semantics
unchanged (`readAsync` write-first note still applies).
