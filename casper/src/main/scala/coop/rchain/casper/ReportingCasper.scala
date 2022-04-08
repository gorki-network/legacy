package coop.rchain.casper

import cats.Parallel
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, ContextShift, Sync}
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.blockstorage.ApprovedStore.ApprovedStore
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.blockstorage.syntax._
import coop.rchain.casper.ReportingCasper.RhoReportingRspace
import coop.rchain.casper.genesis.Genesis
import coop.rchain.casper.protocol.{
  BlockMessage,
  ProcessedDeploy,
  ProcessedSystemDeploy,
  SystemDeployData
}
import coop.rchain.casper.syntax.casperSyntaxRholangRuntimeReplay
import coop.rchain.casper.util.ProtoUtil
import coop.rchain.casper.util.rholang.RuntimeManager.StateHash
import coop.rchain.metrics.Metrics.Source
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.models.Validator.Validator
import coop.rchain.models.{BindPattern, ListParWithRandom, Par, TaggedContinuation}
import coop.rchain.rholang.RholangMetricsSource
import coop.rchain.rholang.interpreter.RhoRuntime.{bootstrapRegistry, createRhoEnv}
import coop.rchain.rholang.interpreter.SystemProcesses.{BlockData, Definition, InvalidBlocks}
import coop.rchain.rholang.interpreter.accounting.{_cost, CostAccounting}
import coop.rchain.rholang.interpreter.{Reduce, ReplayRhoRuntimeImpl}
import coop.rchain.rspace.RSpace.RSpaceStore
import coop.rchain.rspace.ReportingRspace.ReportingEvent
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.{ReportingRspace, Match => RSpaceMatch}
import coop.rchain.shared.Log

import scala.concurrent.ExecutionContext

/**
  * @param processedDeploy Deploy details
  * @param events Reporting events which were generated by this deploy
  */
final case class DeployReportResult(
    processedDeploy: ProcessedDeploy,
    events: Seq[Seq[ReportingEvent]]
)

/**
  * @param processedSystemDeploy system deploy type
  * @param events Reporting events which were generated by this system deploy
  */
final case class SystemDeployReportResult(
    processedSystemDeploy: SystemDeployData,
    events: Seq[Seq[ReportingEvent]]
)

/**
  * This class is holding the reporting replay events results.
  * @param deployReportResult List of user deploy result
  * @param systemDeployReportResult List of system deploy result
  * @param postStateHash postStateHash which generated by the replay. It is possible that this stateHash is different
  *                      from the stateHash in the blockMessage when there is a bug.
  */
final case class ReplayResult(
    deployReportResult: List[DeployReportResult],
    systemDeployReportResult: List[SystemDeployReportResult],
    postStateHash: ByteString
)

trait ReportingCasper[F[_]] {
  def trace(
      block: BlockMessage
  ): F[ReplayResult]
}

object ReportingCasper {
  def noop[F[_]: Sync]: ReportingCasper[F] = new ReportingCasper[F] {

    override def trace(
        block: BlockMessage
    ): F[ReplayResult] =
      Sync[F].delay(ReplayResult(List.empty, List.empty, ByteString.copyFromUtf8("empty")))
  }

  type RhoReportingRspace[F[_]] =
    ReportingRspace[F, Par, BindPattern, ListParWithRandom, TaggedContinuation]

  def rhoReporter[F[_]: ContextShift: Concurrent: Log: Metrics: Span: Parallel: BlockDagStorage](
      rspaceStore: RSpaceStore[F],
      approvedStore: ApprovedStore[F]
  )(implicit scheduler: ExecutionContext): ReportingCasper[F] =
    new ReportingCasper[F] {
      override def trace(
          block: BlockMessage
      ): F[ReplayResult] =
        for {
          reportingRspace  <- ReportingRuntime.createReportingRSpace(rspaceStore)
          reportingRuntime <- ReportingRuntime.createReportingRuntime(reportingRspace)
          dag              <- BlockDagStorage[F].getRepresentation
          // TODO approvedBlock is not equal to genesisBlock
          genesis          <- approvedStore.getApprovedBlock
          isGenesis        = genesis.exists(a => block.blockHash == a.candidate.block.blockHash)
          invalidBlocksSet <- dag.invalidBlocks
          invalidBlocks = invalidBlocksSet
            .map(block => (block.blockHash, block.sender))
            .toMap
          preStateHash     = ProtoUtil.preStateHash(block)
          blockdata        = BlockData.fromBlock(block)
          _                <- reportingRuntime.setBlockData(blockdata)
          _                <- reportingRuntime.setInvalidBlocks(invalidBlocks)
          invalidBlocksSet <- dag.invalidBlocks
          unseenBlocksSet  <- ProtoUtil.unseenBlockHashes(dag, block)
          seenInvalidBlocksSet = invalidBlocksSet.filterNot(
            block => unseenBlocksSet.contains(block.blockHash)
          )
          invalidBlocks = seenInvalidBlocksSet
            .map(block => (block.blockHash, block.sender))
            .toMap
          res <- replayDeploys(
                  reportingRuntime,
                  preStateHash,
                  block.body.deploys,
                  block.body.systemDeploys,
                  !isGenesis,
                  blockdata,
                  invalidBlocks
                )
        } yield res

      private def replayDeploys(
          runtime: ReportingRuntime[F],
          startHash: StateHash,
          terms: Seq[ProcessedDeploy],
          systemDeploys: Seq[ProcessedSystemDeploy],
          withCostAccounting: Boolean,
          blockData: BlockData,
          invalidBlocks: Map[BlockHash, Validator]
      ): F[ReplayResult] =
        for {
          _ <- runtime.reset(Blake2b256Hash.fromByteString(startHash))
          _ <- runtime.setBlockData(blockData)
          _ <- runtime.setInvalidBlocks(invalidBlocks)

          res <- terms.toList.traverse { term =>
                  Log[F].info(s"Replay user deploy ${PrettyPrinter.buildString(term.deploy.sig)}") *>
                    runtime
                      .replayDeployE(withCostAccounting)(term)
                      .semiflatMap(_ => runtime.getReport)
                      .getOrElse(Seq.empty)
                      .map(DeployReportResult(term, _))
                }

          sysRes <- systemDeploys.toList.traverse { term =>
                     Log[F].info(s"Replay system deploy ${term.systemDeploy}") *>
                       runtime
                         .replayBlockSystemDeploy(blockData)(term)
                         .semiflatMap(_ => runtime.getReport)
                         .getOrElse(Seq.empty)
                         .map(SystemDeployReportResult(term.systemDeploy, _))
                   }

          checkPoint <- runtime.createCheckpoint

        } yield ReplayResult(res, sysRes, checkPoint.root.toByteString)
    }
}

class ReportingRuntime[F[_]: Sync: Span](
    override val reducer: Reduce[F],
    override val space: RhoReportingRspace[F],
    override val cost: _cost[F],
    override val blockDataRef: Ref[F, BlockData],
    override val invalidBlocksParam: InvalidBlocks[F],
    override val mergeChs: Ref[F, Set[Par]]
) extends ReplayRhoRuntimeImpl[F](
      reducer,
      space,
      cost,
      blockDataRef,
      invalidBlocksParam,
      mergeChs
    ) {
  def getReport: F[Seq[Seq[ReportingEvent]]] = space.getReport
}

object ReportingRuntime {
  implicit val RuntimeMetricsSource: Source =
    Metrics.Source(RholangMetricsSource, "reportingRuntime")

  def createReportingRSpace[F[_]: Concurrent: ContextShift: Parallel: Log: Metrics: Span](
      store: RSpaceStore[F]
  )(
      implicit scheduler: ExecutionContext
  ): F[RhoReportingRspace[F]] = {
    import coop.rchain.rholang.interpreter.storage._
    implicit val m: RSpaceMatch[F, BindPattern, ListParWithRandom] = matchListPar[F]

    ReportingRspace.create[F, Par, BindPattern, ListParWithRandom, TaggedContinuation](store)
  }

  def createReportingRuntime[F[_]: Concurrent: Log: Metrics: Span: Parallel](
      reporting: RhoReportingRspace[F],
      extraSystemProcesses: Seq[Definition[F]] = Seq.empty
  ): F[ReportingRuntime[F]] =
    for {
      cost     <- CostAccounting.emptyCost[F]
      mergeChs <- Ref.of(Set[Par]())
      rhoEnv <- {
        implicit val c = cost
        createRhoEnv(reporting, mergeChs, Genesis.NonNegativeMergeableTagName, extraSystemProcesses)
      }
      (reducer, blockRef, invalidBlocks) = rhoEnv
      runtime                            = new ReportingRuntime[F](reducer, reporting, cost, blockRef, invalidBlocks, mergeChs)
      _                                  <- bootstrapRegistry(runtime)
    } yield runtime
}
