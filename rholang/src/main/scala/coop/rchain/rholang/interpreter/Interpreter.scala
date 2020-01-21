package coop.rchain.rholang.interpreter

import cats.effect._
import cats.implicits._
import coop.rchain.crypto.hash.Blake2b512Random
import coop.rchain.models.Par
import coop.rchain.rholang.interpreter.accounting._
import coop.rchain.rholang.interpreter.errors.{AggregateError, InterpreterError}

final case class EvaluateResult(cost: Cost, errors: Vector[InterpreterError]) {
  val failed: Boolean    = errors.nonEmpty
  val succeeded: Boolean = !failed
}

trait Interpreter[F[_]] {

  def evaluate(
      runtime: Runtime[F],
      term: String,
      normalizerEnv: Map[String, Par]
  ): F[EvaluateResult]

  def evaluate(
      runtime: Runtime[F],
      term: String,
      initialPhlo: Cost,
      normalizerEnv: Map[String, Par]
  ): F[EvaluateResult]

  def injAttempt(
      reducer: Reduce[F],
      term: String,
      initialPhlo: Cost,
      normalizerEnv: Map[String, Par]
  )(implicit rand: Blake2b512Random): F[EvaluateResult]
}

object Interpreter {

  def apply[F[_]](implicit interpreter: Interpreter[F]): Interpreter[F] = interpreter

  implicit def interpreter[F[_]](
      implicit S: Sync[F],
      C: _cost[F]
  ): Interpreter[F] =
    new Interpreter[F] {

      def evaluate(
          runtime: Runtime[F],
          term: String,
          normalizerEnv: Map[String, Par]
      ): F[EvaluateResult] =
        evaluate(runtime, term, Cost.UNSAFE_MAX, normalizerEnv)

      def evaluate(
          runtime: Runtime[F],
          term: String,
          initialPhlo: Cost,
          normalizerEnv: Map[String, Par]
      ): F[EvaluateResult] = {
        implicit val rand: Blake2b512Random = Blake2b512Random(128)
        runtime.space.createSoftCheckpoint() >>= { checkpoint =>
          injAttempt(runtime.reducer, term, initialPhlo, normalizerEnv).attempt >>= {
            case Right(evaluateResult) =>
              if (evaluateResult.errors.nonEmpty)
                runtime.space.revertToSoftCheckpoint(checkpoint).as(evaluateResult)
              else evaluateResult.pure[F]
            case Left(throwable) =>
              runtime.space.revertToSoftCheckpoint(checkpoint) >> throwable
                .raiseError[F, EvaluateResult]
          }
        }
      }

      // Internal helper exception to mark parser error
      case class ParserError(parseError: InterpreterError) extends Throwable

      def injAttempt(
          reducer: Reduce[F],
          term: String,
          initialPhlo: Cost,
          normalizerEnv: Map[String, Par]
      )(implicit rand: Blake2b512Random): F[EvaluateResult] = {
        val parsingCost = accounting.parsingCost(term)
        val evaluationResult = for {
          _ <- C.set(initialPhlo)
          _ <- charge[F](parsingCost)
          parsed <- ParBuilder[F]
                     .buildNormalizedTerm(term, normalizerEnv)
                     .handleErrorWith {
                       case err: InterpreterError => ParserError(err).raiseError[F, Par]
                     }
          _         <- reducer.inj(parsed)
          phlosLeft <- C.get
        } yield EvaluateResult(initialPhlo - phlosLeft, Vector())

        // Convert InterpreterError(s) to EvaluateResult
        // - all other errors are rethrown (not valid interpreter errors)
        evaluationResult
          .handleErrorWith {

            // Parsing error consumes only parsing cost
            case ParserError(parseError: InterpreterError) =>
              EvaluateResult(parsingCost, Vector(parseError)).pure[F]

            // Only InterpreterError(s) (multiple errors are result of parallel execution)
            // - all phlos is consumed
            case AggregateError(ipErrs, errs) if errs.isEmpty =>
              EvaluateResult(initialPhlo, ipErrs).pure[F]

            // Aggregate fatal errors are rethrown
            case error: AggregateError =>
              error.raiseError[F, EvaluateResult]

            // InterpreterError is returned as a result
            // - all phlos is consumed
            case error: InterpreterError =>
              EvaluateResult(initialPhlo, Vector(error)).pure[F]

            // Any other error is unexpected and it's fatal, rethrow
            case error: Throwable =>
              error.raiseError[F, EvaluateResult]

          }

      }
    }
}
