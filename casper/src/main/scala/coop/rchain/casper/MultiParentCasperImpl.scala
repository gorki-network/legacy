package coop.rchain.casper

import cats.data.EitherT
import cats.effect.{Concurrent, Sync}
import cats.syntax.all._
import coop.rchain.blockstorage._
import coop.rchain.blockstorage.casperbuffer.CasperBufferStorage
import coop.rchain.blockstorage.dag.BlockDagStorage.DeployId
import coop.rchain.blockstorage.dag.{BlockDagRepresentation, BlockDagStorage}
import coop.rchain.blockstorage.deploy.DeployStorage
import coop.rchain.casper.engine.BlockRetriever
import coop.rchain.casper.finality.Finalizer
import coop.rchain.casper.merging.BlockIndex
import coop.rchain.casper.protocol._
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.ProtoUtil._
import coop.rchain.casper.util._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.rholang._
import coop.rchain.catscontrib.Catscontrib.ToBooleanF
import coop.rchain.crypto.signatures.Signed
import coop.rchain.dag.DagOps
import coop.rchain.metrics.implicits._
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.models.BlockHash._
import coop.rchain.models.Validator.Validator
import coop.rchain.models.syntax._
import coop.rchain.models.{BlockHash => _, _}
import coop.rchain.rholang.interpreter.merging.RholangMergingLogic
import coop.rchain.rspace.hashing.Blake2b256Hash
import coop.rchain.rspace.internal
import coop.rchain.shared._
import coop.rchain.shared.syntax._

// format: off
class MultiParentCasperImpl[F[_]
  /* Execution */   : Concurrent: Time
  /* Transport */   : CommUtil: BlockRetriever: EventPublisher
  /* Rholang */     : RuntimeManager
  /* Casper */      : Estimator: SafetyOracle
  /* Storage */     : BlockStore: BlockDagStorage: DeployStorage: CasperBufferStorage
  /* Diagnostics */ : Log: Metrics: Span] // format: on
(
    validatorId: Option[ValidatorIdentity],
    // todo this should be read from chain, for now read from startup options
    casperShardConf: CasperShardConf,
    approvedBlock: BlockMessage
) extends MultiParentCasper[F] {
  import MultiParentCasperImpl._

  implicit private val logSource: LogSource = LogSource(this.getClass)

  // TODO: Extract hardcoded version from shard config
  private val version = 1L

  def getValidator: F[Option[ValidatorIdentity]] = validatorId.pure[F]

  def getVersion: F[Long] = version.pure[F]

  def getApprovedBlock: F[BlockMessage] = approvedBlock.pure[F]

  private def updateLastFinalizedBlock(newBlock: BlockMessage): F[Unit] =
    lastFinalizedBlock.whenA(
      newBlock.body.state.blockNumber % casperShardConf.finalizationRate == 0
    )

  /**
    * Check if there are blocks in CasperBuffer available with all dependencies met.
    * @return First from the set of available blocks
    */
  override def getDependencyFreeFromBuffer: F[List[BlockMessage]] = {
    import cats.instances.list._
    for {
      pendants       <- CasperBufferStorage[F].getPendants
      pendantsStored <- pendants.toList.filterA(BlockStore[F].contains)
      depFreePendants <- pendantsStored.filterA { pendant =>
                          for {
                            pendantBlock   <- BlockStore[F].get(pendant)
                            justifications = pendantBlock.get.justifications
                            // If even one of justifications is not in DAG - block is not dependency free
                            missingDep <- justifications
                                           .map(_.latestBlockHash)
                                           .existsM(dagContains(_).not)
                          } yield !missingDep
                        }
      r <- depFreePendants.traverse(BlockStore[F].getUnsafe)
    } yield r
  }

  def dagContains(hash: BlockHash): F[Boolean] = blockDag.flatMap(_.contains(hash))

  def bufferContains(hash: BlockHash): F[Boolean] = CasperBufferStorage[F].contains(hash)

  def contains(hash: BlockHash): F[Boolean] = bufferContains(hash) ||^ dagContains(hash)

  def deploy(d: Signed[DeployData]): F[Either[DeployError, DeployId]] = {
    import coop.rchain.models.rholang.implicits._

    InterpreterUtil
      .mkTerm(d.data.term, NormalizerEnv(d))
      .bitraverse(
        err => DeployError.parsingError(s"Error in parsing term: \n$err").pure[F],
        _ => addDeploy(d)
      )
  }

  def addDeploy(deploy: Signed[DeployData]): F[DeployId] =
    for {
      _ <- DeployStorage[F].add(List(deploy))
      _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(deploy)}")
    } yield deploy.sig

  def estimator(dag: BlockDagRepresentation[F]): F[IndexedSeq[BlockHash]] =
    Estimator[F].tips(dag, approvedBlock).map(_.tips)

  def lastFinalizedBlock: F[BlockMessage] = {

    def processFinalised(finalizedSet: Set[BlockHash]): F[Unit] =
      finalizedSet.toList.traverse { h =>
        for {
          block   <- BlockStore[F].getUnsafe(h)
          deploys = block.body.deploys.map(_.deploy)

          // Remove block deploys from persistent store
          deploysRemoved   <- DeployStorage[F].remove(deploys)
          finalizedSetStr  = PrettyPrinter.buildString(finalizedSet)
          removedDeployMsg = s"Removed $deploysRemoved deploys from deploy history as we finalized block $finalizedSetStr."
          _                <- Log[F].info(removedDeployMsg)

          // Remove block index from cache
          _ <- BlockIndex.cache.remove(h).pure

          // Remove block post-state mergeable channels from persistent store
          stateHash = block.body.state.postStateHash.toBlake2b256Hash.bytes
          _         <- RuntimeManager[F].getMergeableStore.delete(stateHash)
        } yield ()
      }.void

    def newLfbFoundEffect(newLfb: BlockHash): F[Unit] =
      BlockDagStorage[F].recordDirectlyFinalized(newLfb, processFinalised) >>
        EventPublisher[F].publish(RChainEvent.blockFinalised(newLfb.base16String))

    implicit val ms = CasperMetricsSource

    for {
      dag                      <- blockDag
      lastFinalizedBlockHash   = dag.lastFinalizedBlock
      lastFinalizedBlockHeight <- dag.lookupUnsafe(lastFinalizedBlockHash).map(_.blockNum)
      work = Finalizer
        .run[F](
          dag,
          casperShardConf.faultToleranceThreshold,
          lastFinalizedBlockHeight,
          newLfbFoundEffect
        )
      newFinalisedHashOpt <- Span[F].traceI("finalizer-run")(work)
      blockMessage        <- BlockStore[F].getUnsafe(newFinalisedHashOpt.getOrElse(lastFinalizedBlockHash))
    } yield blockMessage
  }

  def blockDag: F[BlockDagRepresentation[F]] =
    BlockDagStorage[F].getRepresentation

  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    BlockDagStorage[F].accessEquivocationsTracker { tracker =>
      tracker.equivocationRecords.map { equivocations =>
        equivocations
          .map(_.equivocator)
          .flatMap(weights.get)
          .sum
          .toFloat / weightMapTotal(weights)
      }
    }

  def getRuntimeManager: F[RuntimeManager[F]] = Sync[F].delay(RuntimeManager[F])

  def fetchDependencies: F[Unit] = {
    import cats.instances.list._
    for {
      pendants       <- CasperBufferStorage[F].getPendants
      pendantsUnseen <- pendants.toList.filterA(BlockStore[F].contains(_).not)
      _ <- Log[F].debug(s"Requesting CasperBuffer pendant hashes, ${pendantsUnseen.size} items.") >>
            pendantsUnseen.toList.traverse_(
              dependency =>
                Log[F]
                  .debug(
                    s"Sending dependency ${PrettyPrinter.buildString(dependency)} to BlockRetriever"
                  ) >>
                  BlockRetriever[F].admitHash(
                    dependency,
                    admitHashReason = BlockRetriever.MissingDependencyRequested
                  )
            )
    } yield ()
  }

  override def getSnapshot(targetMessageOpt: Option[BlockMessage]): F[CasperSnapshot[F]] = {
    import cats.instances.list._

    def computeOnChainState(b: BlockMessage): F[OnChainCasperState] =
      for {
        av <- RuntimeManager[F].getActiveValidators(b.body.state.postStateHash)
        // bonds are available in block message, but please remember this is just a cache, source of truth is RSpace.
        bm          = b.body.state.bonds
        shardConfig = casperShardConf
      } yield OnChainCasperState(shardConfig, bm.map(v => v.validator -> v.stake).toMap, av)

    // parents do not include invalid latest messages and share the same bonds map
    def computeParents(dag: BlockDagRepresentation[F]): F[List[BlockMessage]] =
      for {
        r         <- Estimator[F].tips(dag, approvedBlock)
        (_, tips) = (r.lca, r.tips)

        /**
          * Before block merge, `EstimatorHelper.chooseNonConflicting` were used to filter parents, as we could not
          * have conflicting parents. With introducing block merge, all parents that share the same bonds map
          * should be parents. Parents that have different bond maps are only one that cannot be merged in any way.
          */
        // For now main parent bonds map taken as a reference, but might be we want to pick a subset with equal
        // bond maps that has biggest cumulative stake.
        blocks  <- tips.toList.traverse(BlockStore[F].getUnsafe)
        parents = blocks.filter(b => b.body.state.bonds == blocks.head.body.state.bonds)
      } yield parents

    /**
      * Justifications might include invalid latest messages and should be bonded in parents
      * We ensure that only the justifications given in the block are those
      * which are bonded validators in the chosen parent. This is safe because
      * any latest message not from a bonded validator will not change the
      * final fork-choice.
      */
    def computeJustifications(
        dag: BlockDagRepresentation[F],
        onChainState: OnChainCasperState
    ): F[Map[Validator, BlockHash]] =
      dag.latestMessageHashes.map(_.filterKeys(onChainState.bondsMap.keySet.contains(_)))

    for {
      // the most recent view on the DAG, includes everything node seen so far
      fullDag <- BlockDagStorage[F].getRepresentation

      getBlockView = (m: BlockMessage) =>
        for {
          p <- m.header.parentsHashList.traverse(BlockStore[F].getUnsafe)
          s <- computeOnChainState(p.head)
          j = m.justifications.map { case Justification(v, h) => (v, h) }.toMap

          findLfb = (latestMessages: Map[Validator, BlockHash]) =>
            for {
              minNum       <- p.map(_.blockHash).traverse(fullDag.lookupUnsafe).map(_.map(_.blockNum).min)
              lowestHeight = minNum - Finalizer.MaxSearchDepth // lowest height puts a constraint on search area

              lfb <- fullDag
                      .findLastFinalizedBlock(
                        latestMessagesView = latestMessages,
                        faultToleranceThreshold = casperShardConf.faultToleranceThreshold,
                        lowestHeight = lowestHeight
                      )
                      .flatMap { lfbOpt =>
                        // if approved block is in search range - return it.
                        // This is required because genesis has fault tolerance less then max value so wont be finalized for
                        // all thresholds.
                        // Also in future approved block restored from LFS might be finalized with fault tolerance less then current
                        // fault tolerance from shard config

                        val approvedBlockIsInRange = fullDag
                          .lookupUnsafe(approvedBlock.blockHash)
                          .map(_.blockNum)
                          .map(_ >= lowestHeight)

                        val notFoundF = approvedBlockIsInRange.ifM(
                          approvedBlock.blockHash.pure[F], {
                            val lfbNotFoundErrMsg = s"No last finalized block found when creating casper snapshot for " +
                              s"${targetMessageOpt.map(PrettyPrinter.buildString(_)).getOrElse("the most recent view")}."
                            new Exception(lfbNotFoundErrMsg).raiseError[F, BlockHash]
                          }
                        )
                        lfbOpt.map(_.pure[F]).getOrElse(notFoundF)
                      }
            } yield lfb
          dagView <- fullDag.truncate(j, findLfb)
        } yield (dagView, p, s, j)

      getLatestView = for {
        p <- computeParents(fullDag)
        s <- computeOnChainState(p.head)
        j <- computeJustifications(fullDag, s)
      } yield (fullDag, p, s, j)

      // if target message supplied, create dag view, otherwise get the most recent view
      view                                         <- targetMessageOpt.map(getBlockView).getOrElse(getLatestView)
      (dag, parents, onChainState, justifications) = view
      lfb                                          = dag.lastFinalizedBlock

      parentMetas <- parents.map(_.blockHash).traverse(dag.lookupUnsafe)
      maxBlockNum = parentMetas.map(_.blockNum).max
      maxSeqNums <- justifications.toList
                     .traverse {
                       case (v, h) => dag.lookupUnsafe(h).map((v -> _.seqNum))
                     }
                     .map(_.toMap)
      deploysInScope <- {
        val currentBlockNumber  = maxBlockNum + 1
        val earliestBlockNumber = currentBlockNumber - onChainState.shardConf.deployLifespan
        for {
          result <- DagOps
                     .bfTraverseF[F, BlockMetadata](parentMetas)(
                       b =>
                         ProtoUtil
                           .getParentMetadatasAboveBlockNumber(
                             b,
                             earliestBlockNumber,
                             dag
                           )
                     )
                     .foldLeftF(Set.empty[Signed[DeployData]]) { (deploys, blockMetadata) =>
                       for {
                         block        <- BlockStore[F].getUnsafe(blockMetadata.blockHash)
                         blockDeploys = ProtoUtil.deploys(block).map(_.deploy)
                       } yield deploys ++ blockDeploys
                     }
        } yield result
      }
      invalidBlocks <- dag.invalidBlocksMap
    } yield CasperSnapshot(
      dag,
      lfb,
      parents,
      justifications.map { case (v, h) => Justification(v, h) }.toSet,
      invalidBlocks,
      deploysInScope,
      maxBlockNum,
      maxSeqNums,
      onChainState
    )
  }

  override def validate(
      b: BlockMessage,
      s: CasperSnapshot[F]
  ): F[Either[BlockError, ValidBlock]] = {
    val validationProcess: EitherT[F, BlockError, ValidBlock] =
      for {
        _ <- EitherT(
              Validate
                .blockSummary(b, approvedBlock, s, casperShardConf.shardName, deployLifespan)
            )
        _ <- EitherT.liftF(Span[F].mark("post-validation-block-summary"))
        _ <- EitherT(
              InterpreterUtil
                .validateBlockCheckpoint(b, s, RuntimeManager[F])
                .map {
                  case Left(ex)       => Left(ex)
                  case Right(Some(_)) => Right(BlockStatus.valid)
                  case Right(None)    => Left(BlockStatus.invalidTransaction)
                }
            )
        _ <- EitherT.liftF(Span[F].mark("transactions-validated"))
        _ <- EitherT(Validate.bondsCache(b, RuntimeManager[F]))
        _ <- EitherT.liftF(Span[F].mark("bonds-cache-validated"))
        _ <- EitherT(Validate.neglectedInvalidBlock(b, s))
        _ <- EitherT.liftF(Span[F].mark("neglected-invalid-block-validated"))
        _ <- EitherT(
              EquivocationDetector.checkNeglectedEquivocationsWithUpdate(b, s.dag, approvedBlock)
            )
        _      <- EitherT.liftF(Span[F].mark("neglected-equivocation-validated"))
        depDag <- EitherT.liftF(CasperBufferStorage[F].toDoublyLinkedDag)
        status <- EitherT(EquivocationDetector.checkEquivocations(depDag, b, s.dag))
        _      <- EitherT.liftF(Span[F].mark("equivocation-validated"))
      } yield status

    val blockPreState  = b.body.state.preStateHash
    val blockPostState = b.body.state.postStateHash
    val blockSender    = b.sender.toByteArray
    val indexBlock = for {
      mergeableChs <- RuntimeManager[F].loadMergeableChannels(blockPostState, blockSender, b.seqNum)

      index <- BlockIndex(
                b.blockHash,
                b.body.deploys,
                b.body.systemDeploys,
                blockPreState.toBlake2b256Hash,
                blockPostState.toBlake2b256Hash,
                RuntimeManager[F].getHistoryRepo,
                mergeableChs
              )
      _ = BlockIndex.cache.putIfAbsent(b.blockHash, index)
    } yield ()

    val validationProcessDiag = for {
      // Create block and measure duration
      r                    <- Stopwatch.duration(validationProcess.value)
      (valResult, elapsed) = r
      _ <- valResult
            .map { status =>
              val blockInfo   = PrettyPrinter.buildString(b, short = true)
              val deployCount = b.body.deploys.size
              Log[F].info(s"Block replayed: $blockInfo (${deployCount}d) ($status) [$elapsed]") <*
                indexBlock.whenA(casperShardConf.maxNumberOfParents > 1)
            }
            .getOrElse(().pure[F])
    } yield valResult

    Log[F].info(s"Validating block ${PrettyPrinter.buildString(b, short = true)}.") *> validationProcessDiag
  }

  override def handleValidBlock(block: BlockMessage): F[BlockDagRepresentation[F]] =
    for {
      updatedDag <- BlockDagStorage[F].insert(block, invalid = false)
      _          <- CasperBufferStorage[F].remove(block.blockHash)
      _          <- updateLastFinalizedBlock(block)
    } yield updatedDag

  override def handleInvalidBlock(
      block: BlockMessage,
      status: InvalidBlock,
      dag: BlockDagRepresentation[F]
  ): F[BlockDagRepresentation[F]] = {
    // TODO: Slash block for status except InvalidUnslashableBlock
    def handleInvalidBlockEffect(
        status: BlockError,
        block: BlockMessage
    ): F[BlockDagRepresentation[F]] =
      for {
        _ <- Log[F].warn(
              s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for ${status.toString}."
            )
        // TODO should be nice to have this transition of a block from casper buffer to dag storage atomic
        r <- BlockDagStorage[F].insert(block, invalid = true)
        _ <- CasperBufferStorage[F].remove(block.blockHash)
      } yield r

    status match {
      case InvalidBlock.AdmissibleEquivocation =>
        val baseEquivocationBlockSeqNum = block.seqNum - 1
        for {
          _ <- BlockDagStorage[F].accessEquivocationsTracker { tracker =>
                for {
                  equivocations <- tracker.equivocationRecords
                  _ <- Sync[F].unlessA(equivocations.exists {
                        case EquivocationRecord(validator, seqNum, _) =>
                          block.sender == validator && baseEquivocationBlockSeqNum == seqNum
                        // More than 2 equivocating children from base equivocation block and base block has already been recorded
                      }) {
                        val newEquivocationRecord =
                          EquivocationRecord(
                            block.sender,
                            baseEquivocationBlockSeqNum,
                            Set.empty[BlockHash]
                          )
                        tracker.insertEquivocationRecord(newEquivocationRecord)
                      }
                } yield ()
              }
          // We can only treat admissible equivocations as invalid blocks if
          // casper is single threaded.
          updatedDag <- handleInvalidBlockEffect(InvalidBlock.AdmissibleEquivocation, block)
        } yield updatedDag

      case InvalidBlock.IgnorableEquivocation =>
        /*
         * We don't have to include these blocks to the equivocation tracker because if any validator
         * will build off this side of the equivocation, we will get another attempt to add this block
         * through the admissible equivocations.
         */
        Log[F]
          .info(
            s"Did not add block ${PrettyPrinter.buildString(block.blockHash)} as that would add an equivocation to the BlockDAG"
          )
          .as(dag)

      case ib: InvalidBlock if InvalidBlock.isSlashable(ib) =>
        handleInvalidBlockEffect(ib, block)

      case ib: InvalidBlock =>
        CasperBufferStorage[F].remove(block.blockHash) >> Log[F]
          .warn(
            s"Recording invalid block ${PrettyPrinter.buildString(block.blockHash)} for $ib."
          )
          .as(dag)
    }
  }
}

object MultiParentCasperImpl {

  // TODO: Extract hardcoded deployLifespan from shard config
  // Size of deploy safety range.
  // Validators will try to put deploy in a block only for next `deployLifespan` blocks.
  // Required to enable protection from re-submitting duplicate deploys
  val deployLifespan = 50

  def addedEvent(block: BlockMessage): RChainEvent = {
    val (blockHash, parents, justifications, deployIds, creator, seqNum) = blockEvent(block)
    RChainEvent.blockAdded(
      blockHash,
      parents,
      justifications,
      deployIds,
      creator,
      seqNum
    )
  }

  def createdEvent(b: BlockMessage): RChainEvent = {
    val (blockHash, parents, justifications, deployIds, creator, seqNum) = blockEvent(b)
    RChainEvent.blockCreated(
      blockHash,
      parents,
      justifications,
      deployIds,
      creator,
      seqNum
    )
  }

  private def blockEvent(block: BlockMessage) = {

    val blockHash = block.blockHash.base16String
    val parentHashes =
      block.header.parentsHashList.map(_.base16String)
    val justificationHashes =
      block.justifications.toList
        .map(j => (j.validator.base16String, j.latestBlockHash.base16String))
    val deployIds: List[String] =
      block.body.deploys.map(pd => PrettyPrinter.buildStringNoLimit(pd.deploy.sig))
    val creator = block.sender.base16String
    val seqNum  = block.seqNum
    (blockHash, parentHashes, justificationHashes, deployIds, creator, seqNum)
  }
}
