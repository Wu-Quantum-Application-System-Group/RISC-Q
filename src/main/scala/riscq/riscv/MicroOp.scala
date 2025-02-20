package riscq.riscv

import spinal.core.{AreaObject, MaskedLiteral, Nameable}

class Resource
case class RfResource(rf : RegfileSpec, access : RfAccess) extends Resource

class RfAccess extends Nameable
class RfRead extends RfAccess
class RfWrite extends RfAccess

object RS1 extends RfRead with AreaObject
object RS2 extends RfRead with AreaObject
object RS3 extends RfRead with AreaObject
object RD  extends RfWrite with AreaObject
object PC_READ  extends Resource with AreaObject
object INSTRUCTION_SIZE  extends Resource with AreaObject
object LQ  extends Resource with AreaObject
object SQ  extends Resource with AreaObject
object FPU extends Resource with AreaObject
object RM  extends Resource with AreaObject

abstract class MicroOp(val resources : Seq[Resource]){
  def key : MaskedLiteral
}
case class SingleDecoding(key : MaskedLiteral, override val resources : Seq[Resource]) extends MicroOp(resources) with Nameable {
  override def toString = s"SingleDecoding ${getName("")} $key"
}
case class MultiDecoding(key : MaskedLiteral, uop : Seq[MicroOp])

trait RegfileSpec extends Nameable{
  def sizeArch : Int
  def width : Int
  def x0AlwaysZero : Boolean
  def getName() : String
  def initialValue : BigInt

  def ->(access : RfAccess) = RfResource(this, access)
}

