package nova.node

import forge.tags._
import nova.core._
import nova.lang._

@op case class FltNeg[A:Flt](a: A) extends Primitive[A]
@op case class FltAdd[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltSub[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltMul[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltDiv[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltMod[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltLst[A:Flt](a: A, b: A) extends Primitive[Bit]
@op case class FltLeq[A:Flt](a: A, b: A) extends Primitive[Bit]
@op case class FltEql[A:Flt](a: A, b: A) extends Primitive[Bit]
@op case class FltNeq[A:Flt](a: A, b: A) extends Primitive[Bit]


@op case class FltMin[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltMax[A:Flt](a: A, b: A) extends Primitive[A]
@op case class FltAbs[A:Flt](a: A) extends Primitive[A]
@op case class FltCeil[A:Flt](a: A) extends Primitive[A]
@op case class FltFloor[A:Flt](a: A) extends Primitive[A]
@op case class FltPow[A:Flt](b: A, e: A) extends Primitive[A]
@op case class FltExp[A:Flt](a: A) extends Primitive[A]
@op case class FltLn[A:Flt](a: A) extends Primitive[A]
@op case class FltSqrt[A:Flt](a: A) extends Primitive[A]

@op case class FltSin[A:Flt](a: A) extends Primitive[A]
@op case class FltCos[A:Flt](a: A) extends Primitive[A]
@op case class FltTan[A:Flt](a: A) extends Primitive[A]
@op case class FltSinh[A:Flt](a: A) extends Primitive[A]
@op case class FltCosh[A:Flt](a: A) extends Primitive[A]
@op case class FltTanh[A:Flt](a: A) extends Primitive[A]
@op case class FltAsin[A:Flt](a: A) extends Primitive[A]
@op case class FltAcos[A:Flt](a: A) extends Primitive[A]
@op case class FltAtan[A:Flt](a: A) extends Primitive[A]

@op case class FltInvSqrt[A:Flt](a: A) extends Primitive[A]
@op case class FltSigmoid[A:Flt](a: A) extends Primitive[A]

@op case class FltRandom[A:Flt](max: Option[A]) extends Primitive[A] {
  override def effects: Effects = Effects.Simple
}
