package ceedubs.irrec
package regex

import cats.implicits._
import cats.Foldable
import scala.collection.immutable.{SortedMap, SortedSet}

final case class NFA[I, A](
  initStates: SortedSet[I],
  finalStates: SortedSet[I],
  transitions: SortedMap[I, List[(I, A)]])

object NFA {

  /**
   * Similar to [[runNFA]], but optimized for a short input `F[A]`.
   *
   * This version will always consume the entire `F[A]`, even if it reaches a point in which a match
   * is impossible. However, it makes fewer allocations and has less overhead for each element that
   * it processes, so if the input `F[A]` is short, then it can be faster than [[runNFA]].
   */
  def runNFAShortInput[F[_], I, B, A](nfa: NFA[I, B], matches: (B, A) => Boolean)(
    implicit orderingI: Ordering[I],
    foldableF: Foldable[F]): F[A] => Boolean = { (fa: F[A]) =>
    val finalStates: SortedSet[I] = fa.foldLeft(nfa.initStates)(
      (currentStates, a) =>
        currentStates
          .flatMap(
            i =>
              nfa.transitions
                .getOrElse(i, List.empty)
                .collect { case (i, b) if matches(b, a) => i }))
    nfa.finalStates.exists(finalStates.contains(_))
  }

  def runNFA[F[_], I, B, A](nfa: NFA[I, B], matches: (B, A) => Boolean)(
    implicit orderingI: Ordering[I],
    foldableF: Foldable[F]): F[A] => Boolean = { (fa: F[A]) =>
    val finalStates: Either[Unit, SortedSet[I]] = fa.foldM(nfa.initStates) { (currentStates, a) =>
      val nextStates = currentStates.flatMap(
        i =>
          nfa.transitions
            .getOrElse(i, List.empty)
            .collect { case (i, b) if matches(b, a) => i })
      if (nextStates.isEmpty) Left(()) else Right(nextStates)
    }
    finalStates.fold(_ => false, states => states.exists(nfa.finalStates.contains(_)))
  }
}
