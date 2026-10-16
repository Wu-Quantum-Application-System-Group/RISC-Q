package riscq.riscv.test.konata

import java.io.{BufferedWriter, File, FileWriter}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import spinal.core.sim._

/**
 * Konata pipeline-trace writer, vendored from VexiiRiscv's `vexiiriscv.test.konata` so riscq no
 * longer depends on the VexiiRiscv build module. Emits the Kanata `0004` text format consumed by the
 * Konata viewer; [[riscq.riscv.test.RiscqProbe]] drives it (one [[Thread]] per hart, F/E stages).
 *
 * Each `Command` writes itself as one Konata line keyed by an instruction id. An [[Instruction]] is a
 * timestamp-ordered list of `Command`s; the [[Backend]] buffers pending instructions per cycle and
 * flushes everything up to the minimum thread `cycleLock`, so a still-in-flight instruction holds the
 * trace open until it retires or flushes.
 */
abstract class Command(val at: Long) {
  def toString(id: Long): String
}

class Spawn(at: Long, hartId: Int) extends Command(at) {
  override def toString(id: Long): String = s"I\t$id\t0\t$hartId\n"
}

class Comment(at: Long, text: String) extends Command(at) {
  override def toString(id: Long): String = s"L\t$id\t0\t$text\n"
}

class Stage(at: Long, name: String) extends Command(at) {
  override def toString(id: Long): String = s"S\t$id\t0\t$name\n"
}

class Retire(at: Long) extends Command(at) {
  override def toString(id: Long): String = s"R\t$id\t$id\t0\n"
}

class Flush(at: Long) extends Command(at) {
  override def toString(id: Long): String = s"R\t$id\t$id\t1\n"
}

class Instruction() {
  var id  = -1L
  var ptr = 0
  val elements = ArrayBuffer[Command]()
  def nextAt = elements(ptr).at
  def pop() = {
    val ret = elements(ptr)
    ptr += 1
    ret
  }
  def +=(that: Command) = {
    if (elements.nonEmpty) assert(elements.last.at <= that.at)
    elements += that
  }
  def nonEmpty = ptr != elements.size
  def first    = ptr == 0
}

class Thread {
  var cycleLock = -1L
}

class Backend(f: File) {
  Option(f.getParentFile).foreach(_.mkdirs())
  val bf = new BufferedWriter(new FileWriter(f))

  def newThread(): Thread = {
    val t = new Thread
    threads += t
    t
  }

  val threads  = ArrayBuffer[Thread]()
  val pendings = mutable.HashMap[Long, mutable.ArrayBuffer[Instruction]]()

  var cycle   = 0L
  var idAlloc = 0L

  def insert(i: Instruction) = if (i.nonEmpty) {
    val buf = pendings.getOrElseUpdate(i.nextAt, new mutable.ArrayBuffer[Instruction]())
    buf += i
  }

  bf.write("Kanata\t0004\n")
  bf.write("C=\t0\n")

  def refresh(): Unit = {
    val cycleEnd = threads.map(_.cycleLock).min
    var skips    = 0L

    while (cycle != cycleEnd && pendings.nonEmpty) {
      skips += 1
      pendings.get(cycle) match {
        case Some(instrs) => {
          bf.write(s"C\t$skips\n")
          skips = 0
          for (instr <- instrs) {
            if (instr.first) {
              instr.id = idAlloc
              idAlloc += 1
            }
            do {
              val e = instr.pop()
              bf.write(e.toString(instr.id))
            } while (instr.nonEmpty && instr.nextAt == cycle)
            insert(instr)
          }
          pendings.remove(cycle)
        }
        case None =>
      }
      cycle += 1
    }
    if (skips != 0) bf.write(s"C\t$skips\n")
  }

  def flush(): Unit = {
    refresh()
    bf.flush()
  }

  def close(): Unit = {
    refresh()
    bf.close()
  }

  def spinalSimFlusher(period: Long): this.type = {
    periodicaly(period) {
      flush()
    }
    delayed(1)(onSimEnd(close()))
    this
  }
}
