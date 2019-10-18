package coop.rchain.casper.util.comm

import scala.collection.immutable.Queue

import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.syntax.all._

import coop.rchain.comm.PeerNode
import coop.rchain.comm.protocol.routing.Packet
import coop.rchain.p2p.effects.PacketHandler

trait PacketDispatcher[F[_]] {
  def dispatch(peer: PeerNode, packet: Packet): F[Unit]
}

object PacketDispatcher {
  def apply[F[_]](ev: PacketDispatcher[F]): PacketDispatcher[F] = ev
}

class FairRoundRobinDispatcher[F[_]: Sync, S, M](
    handle: (S, M) => F[Unit],
    queue: Ref[F, Queue[S]],
    messages: Ref[F, Map[S, Queue[M]]],
    retries: Ref[F, Map[S, Int]],
    skipped: Ref[F, Int],
    maxSourceQueueSize: Int,
    giveUpAfterSkipped: Int,
    dropSourceAfterRetries: Int
) {
  assert(maxSourceQueueSize > 0)
  assert(giveUpAfterSkipped >= 0)
  assert(dropSourceAfterRetries >= 0)

  def dispatch(source: S, message: M): F[Unit] =
    ensureSourceExists(source) >>
      enqueueMessage(source, message) >>
      handleMessages(source)

  private[comm] def ensureSourceExists(source: S): F[Unit] =
    messages.get
      .map(_.contains(source))
      .ifM(
        ().pure,
        queue.update(_.enqueue(source)) *>
          messages.update(_ + (source -> Queue.empty[M])) *>
          retries.update(_ + (source  -> 0))
      )

  private[comm] def enqueueMessage(source: S, message: M): F[Unit] =
    messages.get
      .map(_(source).size < maxSourceQueueSize)
      .ifM(
        messages.update(ps => ps.updated(source, ps(source).enqueue(message))) *>
          retries.update(_.updated(source, 0)),
        ().pure
      )

  private[comm] def handleMessages(source: S): F[Unit] =
    queue.get.flatMap { q =>
      if (q.head == source) ().pure
      else failure(q.head)
    } >> handleNextMessage // because maybe gave up on failure

  private[comm] val handleNextMessage: F[Unit] =
    queue.get.map(_.head) >>= { s =>
      handleMessage(s).ifM(
        success(s) >> handleNextMessage,
        ().pure
      )
    }

  private[comm] def handleMessage(source: S): F[Boolean] =
    messages.get.map(_(source).headOption) >>=
      (_.fold(false.pure)(handle(source, _).attempt.as(true)))

  private[comm] val rotate: F[Unit] =
    queue.update(_.dequeue match { case (s, q) => q.enqueue(s) })

  private[comm] def dropSource(source: S): F[Unit] =
    queue.update(_.filterNot(_ == source)) *>
      messages.update(_ - source) *>
      retries.update(_ - source)

  private[comm] def giveUp(source: S): F[Unit] =
    skipped.update(_ => 0) *>
      retries.update(r => r.updated(source, r(source) + 1)) >>
      retries.get.map(_(source) > dropSourceAfterRetries).ifM(dropSource(source), rotate)

  private[comm] def success(source: S): F[Unit] =
    messages.update(ms => ms.updated(source, ms(source).dequeue._2)) *>
      skipped.update(_ => 0) *>
      rotate

  private[comm] def failure(source: S): F[Unit] =
    skipped.update(_ + 1) >>
      skipped.get.map(_ < giveUpAfterSkipped).ifM(().pure, giveUp(source))
}

object FairRoundRobinDispatcher {

  def packetDispatcher[F[_]: Sync](
      handler: PacketHandler[F],
      maxPeerQueueSize: Int,
      giveUpAfterSkipped: Int,
      dropPeerAfterRetries: Int
  ): F[PacketDispatcher[F]] =
    for {
      queue   <- Ref.of(Queue.empty[PeerNode])
      packets <- Ref.of(Map.empty[PeerNode, Queue[Packet]])
      retries <- Ref.of(Map.empty[PeerNode, Int])
      skipped <- Ref.of(0)
    } yield new PacketDispatcher[F] {
      val dispatcher =
        new FairRoundRobinDispatcher[F, PeerNode, Packet](
          handler.handlePacket,
          queue,
          packets,
          retries,
          skipped,
          maxPeerQueueSize,
          giveUpAfterSkipped,
          dropPeerAfterRetries
        )

      def dispatch(peer: PeerNode, packet: Packet): F[Unit] = dispatcher.dispatch(peer, packet)
    }

  def concurrentPacketDispatcher[F[_]: Concurrent](
      handler: PacketHandler[F],
      maxPeerQueueSize: Int,
      giveUpAfterSkipped: Int,
      dropPeerAfterRetries: Int
  ): F[PacketDispatcher[F]] =
    for {
      lock <- Semaphore(1)
      dispatcher <- packetDispatcher(
                     handler,
                     maxPeerQueueSize,
                     giveUpAfterSkipped,
                     dropPeerAfterRetries
                   )
    } yield new PacketDispatcher[F] {
      def dispatch(peer: PeerNode, packet: Packet): F[Unit] =
        lock.withPermit(
          dispatcher.dispatch(peer, packet)
        )
    }
}
