# MemBus / DataMemBus — the core's memory bus shapes

**Source:** [`src/riscq/riscv/misc/MemBus.scala`](../../src/riscq/riscv/misc/MemBus.scala),
[`src/riscq/riscv/misc/DataMemBus.scala`](../../src/riscq/riscv/misc/DataMemBus.scala) ·
**Package:** `riscq.riscv.misc` · **Type:** `object` (param builder) + `Bundle`s

The core talks to memory over two cacheless links: a **fetch** (instruction) bus and a **data**
(LSU) bus. `MemBus` builds the Tilelink master-bus parameters both use; `DataMemBus` is the
simplified non-Tilelink bundle the LSU itself speaks (Tilelink-ized at the fabric boundary).

## MemBus — the Tilelink `BusParameter` builder

`MemBus.param(addressWidth, dataWidth, withStore, idCount = 2)` returns a Tilelink
`BusParameter` for a cacheless word-granular master. It is the much-simplified equivalent of
VexiiRiscv's `CachelessBusParam.toTilelinkM2s`, minus AMO. Two shapes are built from it:

| user | `withStore` | `idCount` | shape |
|---|---|---|---|
| [FetchPlugin](FetchPlugin.md) `iBus` | **false** | `> 1` (one per in-flight Get) | **get-only**, multi-outstanding |
| [LsuPlugin](LsuPlugin.md) dBus side | true | 2 (default) | get + putFull/putPartial, single-outstanding |

Transfers are word-sized (`SizeRange(1, dataBytes)`). `withStore` adds `putFull`/`putPartial`;
the fetch bus omits both (it only reads). `idCount` sizes the source-id space — **each in-flight
request needs a distinct id**. The pipelined fetch passes `idCount > 1` so several `Get`s can be
outstanding and their responses reordered by id (see [FetchPlugin](FetchPlugin.md)); the
single-outstanding LSU keeps the default 2 (only source 0 is used — 2 just avoids the
0-width-source corner case in the sim agent).

### The get-only consequence (`withStore = false`)

A get-only Tilelink bus has **null `a.mask` / `a.data` / `a.corrupt`** channels — they aren't
materialised because the master never writes. Drive only `a.opcode = GET`, `a.param`, `a.source`,
`a.size`, and `a.address`; the `size` field alone says "one word". **Driving the null fields is a
design error.** This is the durable fetch-side gotcha — see the [FetchPlugin](FetchPlugin.md)
fork logic, which sets exactly those five fields.

### Address widths

The fetch and data buses are both built at **`Global.XLEN`** (32-bit) address width even when the
fetch PC is narrowed, because both must reach the instruction/data RAM at the fabric's decode base
(`0x8000_0000`). When the PC is narrowed (`fetchPcWidth`), the carried offset is reconstructed
(`pcBase | offset`) onto the full-width bus address. See [RiscqParam](RiscqParam.md).

## DataMemBus — the LSU's simplified data link

`DataMemBus` is **not** Tilelink — it is the stripped bundle the [LSU](LsuPlugin.md) drives
directly, the riscq analog of VexiiRiscv's `LsuCachelessBus`. It is a `cmd` request `Stream` + a
`rsp` response **`Flow`**:

- **`DataMemCmd`** — one word-granular access: `write` (read vs byte-masked write), word-aligned
  `address`, `data` (store bytes already shifted into their lane), and `mask` (live byte lanes).
  `data`/`mask` are don't-care on a read.
- **`DataMemRsp`** — the read word (don't-care for a write, which only needs the ack).

**Why `rsp` is a `Flow`, not a `Stream`:** every accepted `cmd` produces exactly one `rsp` (a
load's word, or a store's ack with don't-care data), and the LSU is single-outstanding — it
**halts** until the response arrives — so the master is always ready for its one outstanding
response. No `ready` is needed on the response path.

### The ≥1-cycle response contract

`rsp.valid` is **never asserted in the same cycle its command is presented** — the response for an
access always arrives at least one cycle after that access's *first active execute cycle*. Every
backend honours it: the sim model ([SimMem](../../src/riscq/riscv/sim/SimMem.scala)) answers after a
random **1–3-cycle** latency; in the SoC the [posted-store shim](../soc/PostedStoreShim.md) registers
its write-ack, and the CPU RAM slave answers at the synchronous-read [`Bram`/`Uram`](../memory/README.md)'s
fixed read latency (≥ 2 cycles). A zero-latency (combinational) memory model would violate the
contract.

The [LSU](LsuPlugin.md)'s registered load-shift (**baked in**) relies on it: it
takes the load-result byte-lane down-shift off the live effective-address adder and derives it from the
*registered* `addrReg` instead, which is correct only because the response can't beat the address
latch (`dBus.rsp.valid` implies the command was already `latched`, so `addrReg` holds this access's
address by the time the load word is consumed). LsuPlugin carries a simulation-only assert of exactly
that (`dBus.rsp.valid` ⇒ `latched`) so any future 0-latency backend trips it immediately.

`DataMemBusParam(addressWidth, dataWidth)` is XLEN-wide on address (the data bus reaches the whole
SoC map) and 32-bit on data; `dataBytes` is the byte-mask lane width. Keeping the LSU on this
bundle leaves it free of the full Tilelink A/D-channel boilerplate; the Tilelink-ization (and any
fabric posting/decoding) lives at the core→fabric boundary in `DataMemBusToTilelink` — mirroring
the VexiiRiscv split where the cacheless plugin emits `LsuCachelessBus` and a separate bridge
converts it.

## Related

- [FetchPlugin](FetchPlugin.md) — drives the get-only multi-outstanding `iBus`.
- [LsuPlugin](LsuPlugin.md) — drives `DataMemBus`.
- [RiscqParam](RiscqParam.md) — `fetchPcWidth` / `Global.XLEN` and the address-width split.
