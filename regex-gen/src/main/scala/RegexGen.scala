package ceedubs.irrec
package regex

import ceedubs.irrec.regex.ScalacheckSupport._

import cats.Order
import cats.implicits._
import qq.droste.{scheme, Algebra, CoalgebraM}
import qq.droste.data.CoattrF
import qq.droste.data.prelude._
import org.scalacheck.{Arbitrary, Gen}, Gen.Choose

object RegexGen {

  def matchingGen[A](m: Match[A], genA: Gen[A])(
    implicit chooseA: Choose[A],
    orderA: Order[A]): Gen[A] = m match {
    case Match.Literal(expected) => Gen.const(expected)
    case Match.Wildcard => genA
    case Match.Range(l, r) => chooseA.choose(l, r)
    case m @ Match.NoneOf(_) => genA.filter(m.matches(_))
  }

  def kleeneFStreamAlgebra[A]: Algebra[KleeneF, Gen[Stream[A]]] = Algebra {
    case KleeneF.Plus(l, r) => Gen.oneOf(l, r)
    case KleeneF.Times(l, r) => l.flatMap(ls => r.map(rs => ls ++ rs))
    // TODO ceedubs probably need to do something fancier so we don't get large nested structures
    case KleeneF.Star(g) => Gen.containerOf[Stream, Stream[A]](g).map(_.flatten)
    case KleeneF.Zero => Gen.fail
    case KleeneF.One => Gen.const(Stream.empty)
  }

  def regexMatchingStreamAlgebra[A: Choose: Order](
    genA: Gen[A]): Algebra[CoattrF[KleeneF, Match[A], ?], Gen[Stream[A]]] =
    Algebra[CoattrF[KleeneF, Match[A], ?], Gen[Stream[A]]] {
      CoattrF.un(_) match {
        case Left(ma) => matchingGen(ma, genA).map(Stream(_))
        case Right(kf) => kleeneFStreamAlgebra(kf)
      }
    }

  def regexMatchingStreamGen[A: Choose: Order](genA: Gen[A]): Regex[A] => Gen[Stream[A]] =
    scheme.cata(regexMatchingStreamAlgebra(genA))

  def regexMatchingStringGen(genChar: Gen[Char]): Regex[Char] => Gen[String] = {
    val streamGen = regexMatchingStreamGen(genChar)
    r => streamGen(r).map(_.mkString)
  }

  def genRangeMatch[A](genA: Gen[A])(implicit orderingA: Ordering[A]): Gen[Match.Range[A]] =
    for {
      a1 <- genA
      a2 <- genA
    } yield if (orderingA.lt(a1, a2)) Match.Range(a1, a2) else Match.Range(a2, a1)

  def genMatch[A](genA: Gen[A], genRange: Gen[Match.Range[A]]): Gen[Match[A]] =
    Gen.frequency(5 -> genA.map(Match.lit(_)), 3 -> genRange, 1 -> Gen.const(Match.wildcard))

  def genRegexCoalgebraM[A](
    genA: Gen[A],
    genRangeA: Gen[Match.Range[A]],
    includeZero: Boolean,
    includeOne: Boolean): CoalgebraM[Gen, CoattrF[KleeneF, Match[A], ?], Int] = {
    val leafGen: Gen[CoattrF[KleeneF, Match[A], Int]] =
      Gen.frequency(
        10 -> genMatch[A](genA, genRangeA).map(CoattrF.pure),
        (if (includeOne) 2 else 0) -> Gen.const(CoattrF.roll(KleeneF.One)),
        (if (includeZero) 1 else 0) -> Gen.const(CoattrF.roll(KleeneF.Zero))
      )

    CoalgebraM[Gen, CoattrF[KleeneF, Match[A], ?], Int](
      (maxSize: Int) =>
        Gen
          .choose(0, maxSize)
          .flatMap(size =>
            if (size === 0) leafGen
            else {
              val newSize = size - 1
              Gen.frequency(
                10 -> Gen.const(CoattrF.roll(KleeneF.Times(newSize, newSize))),
                5 -> leafGen,
                4 -> Gen.const(CoattrF.roll(KleeneF.Plus(newSize, newSize))),
                2 -> Gen.const(CoattrF.roll(KleeneF.Star(newSize)))
              )
            }))
  }

  def genRegex[A](
    genA: Gen[A],
    genRangeA: Gen[Match.Range[A]],
    includeZero: Boolean,
    includeOne: Boolean): Gen[Regex[A]] =
    Gen.sized(
      maxSize =>
        scheme
          .anaM(
            genRegexCoalgebraM[A](
              genA,
              genRangeA,
              includeZero = includeZero,
              includeOne = includeOne))
          .apply(maxSize))

  def arbRegex[A](implicit arbA: Arbitrary[A], orderingA: Ordering[A]): Arbitrary[Regex[A]] =
    Arbitrary(
      genRegex(
        arbA.arbitrary,
        genRangeMatch(arbA.arbitrary),
        includeZero = true,
        includeOne = true))

  implicit val arbCharRegex: Arbitrary[Regex[Char]] = Arbitrary(CharRegexGen.genStandardRegexChar)

  implicit val arbByteRegex: Arbitrary[Regex[Byte]] = arbRegex[Byte]

  implicit val arbIntRegex: Arbitrary[Regex[Int]] = arbRegex[Int]

  implicit val arbLongRegex: Arbitrary[Regex[Long]] = arbRegex[Long]
}
