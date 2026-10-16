package spinal.lib.misc

import net.fornwall.jelf.{ElfFile, ElfSection, ElfSectionHeader, ElfSymbol}
import spinal.core._
import spinal.lib.sim.SparseMemory

import java.io.File
import java.nio.file.Files

/**
 * ELF parse/load utility (based on net.fornwall.jelf), vendored from VexiiRiscv's
 * `spinal.lib.misc.Elf` so riscq no longer depends on the VexiiRiscv build module. Parses an ELF,
 * resolves symbol addresses, and loads program sections into a simulation memory / `Mem`. Only the
 * `Elf` class riscq's sims use is carried over (the upstream `ElfTest`/`ElfMapper` dev tools are dropped).
 */
class Elf(val f: File, addressWidth: Int) {
  val fBytes = Files.readAllBytes(f.toPath)
  val elf    = ElfFile.from(fBytes)

  def foreachSection(body: ElfSection => Unit): Unit = {
    for (sectionId <- 0 until elf.e_shnum) {
      val section = elf.getSection(sectionId)
      body(section)
    }
  }

  def getData(section: ElfSection): Array[Byte] = {
    val fileAddress = section.header.sh_offset
    val size        = section.header.sh_size.toInt
    if (size == 0) return Array.fill(0)(0.toByte)

    val ret = new Array[Byte](size)
    if (section.header.sh_type != ElfSectionHeader.SHT_NOBITS && section.header.sh_type != ElfSectionHeader.SHT_NULL) {
      Array.copy(fBytes, fileAddress.toInt, ret, 0, size)
    }
    ret
  }

  def load(mem: SparseMemory, offset: Long): Unit = {
    foreachSection { section =>
      if ((section.header.sh_flags & ElfSectionHeader.FLAG_ALLOC) != 0) {
        val data          = getData(section)
        val memoryAddress = (section.header.sh_addr - offset) & ((BigInt(1) << addressWidth) - 1).toLong
        mem.write(memoryAddress, data)
      }
    }
  }

  def getMemInit[T <: Data](ram: Mem[T], offset: BigInt, allowOverflow: Boolean = false) = {
    val wordSize    = ram.wordType.getBitsWidth / 8
    val initContent = Array.fill[BigInt](ram.wordCount)(0)
    foreachSection { section =>
      if ((section.header.sh_flags & ElfSectionHeader.FLAG_ALLOC) != 0) {
        val data          = getData(section)
        val memoryAddress = (section.header.sh_addr - offset) & ((BigInt(1) << addressWidth) - 1).toLong
        for ((byte, i) <- data.zipWithIndex) {
          val addressWithoutOffset = memoryAddress + i
          val addressWord          = addressWithoutOffset / wordSize
          if (addressWord < 0 || addressWord >= initContent.size) {
            assert(allowOverflow)
          } else {
            initContent(addressWord.toInt) |= BigInt(byte.toInt & 0xFF) << ((addressWithoutOffset.toInt % wordSize) * 8)
          }
        }
      }
    }
    initContent
  }

  def load(func: (Long, Byte) => Unit): Unit = {
    foreachSection { section =>
      if ((section.header.sh_flags & ElfSectionHeader.FLAG_ALLOC) != 0) {
        val data          = getData(section)
        val memoryAddress = (section.header.sh_addr) & ((BigInt(1) << addressWidth) - 1).toLong
        for ((byte, i) <- data.zipWithIndex) {
          func(memoryAddress + i, byte)
        }
      }
    }
  }

  def loadArray(array: Array[Byte], offset: Long, allowOverflow: Boolean = false): Unit = {
    load { (address, data) =>
      if (address > offset && address < offset + array.size) array(address - offset toInt) = data
      else assert(allowOverflow)
    }
  }

  def init[T <: Data](ram: Mem[T], offset: BigInt, allowOverflow: Boolean = false): Unit = {
    val initContent = getMemInit(ram, offset, allowOverflow)
    ram.initBigInt(initContent)
  }

  def load[T <: Data](ram: Mem[T], offset: BigInt, allowOverflow: Boolean = false): Unit = {
    val initContent = getMemInit(ram, offset, allowOverflow)
    import spinal.core.sim._
    for ((e, i) <- initContent.zipWithIndex) {
      ram.setBigInt(i, e)
    }
  }

  def getSymbolAddress(name: String): Long = {
    val s = getELFSymbol(name)
    s.st_value
  }

  def getELFSymbol(symbolName: String): ElfSymbol = {
    if (symbolName == null) return null
    // Check dynamic symbol table for symbol name.
    import elf._
    var sh = getDynamicSymbolTableSection
    if (sh != null) {
      val numSymbols = sh.symbols.length
      var i          = 0
      while ({
        i < numSymbols
      }) {
        var symbol = sh.symbols(i)
        if (symbolName == symbol.getName) return symbol
        else if (symbolName == (sh.symbols(numSymbols - 1 - i)).getName) return sh.symbols(numSymbols - 1 - i)

        i += 1
      }
    }
    // Check symbol table for symbol name.
    sh = getSymbolTableSection
    if (sh != null) {
      val numSymbols = sh.symbols.length
      var i          = 0
      while ({
        i < numSymbols
      }) {
        var symbol = sh.symbols(i)
        if (symbolName == symbol.getName) return symbol
        else if (symbolName == (sh.symbols(numSymbols - 1 - i)).getName) return sh.symbols(numSymbols - 1 - i)

        i += 1
      }
    }
    null
  }
}
