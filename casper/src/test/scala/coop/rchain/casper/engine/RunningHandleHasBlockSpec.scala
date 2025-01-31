package coop.rchain.casper.engine

import cats.effect.IO
import cats.syntax.all._
import com.google.protobuf.ByteString
import coop.rchain.casper.blocks.BlockRetriever
import coop.rchain.casper.blocks.BlockRetriever.{RequestState, RequestedBlocks}
import coop.rchain.casper.protocol._
import coop.rchain.catscontrib.ski._
import coop.rchain.comm.CommError._
import coop.rchain.comm.protocol.routing.Protocol
import coop.rchain.comm.rp.Connect.{Connections, ConnectionsCell}
import coop.rchain.comm.rp.ProtocolHelper.toPacket
import coop.rchain.comm.rp.RPConf
import coop.rchain.comm.{Endpoint, NodeIdentifier, PeerNode}
import coop.rchain.metrics.Metrics
import coop.rchain.models.BlockHash.BlockHash
import coop.rchain.p2p.EffectsTestInstances.{
  createRPConfAsk,
  LogStub,
  LogicalTime,
  TransportLayerStub
}
import coop.rchain.shared.Log
import org.scalatest._
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers
import cats.effect.Ref
import cats.effect.unsafe.implicits.global

class RunningHandleHasBlockSpec extends AnyFunSpec with BeforeAndAfterEach with Matchers {

  val local: PeerNode = peerNode("src", 40400)

  implicit val log: Log[IO] = new LogStub
  implicit val metrics      = new Metrics.MetricsNOP[IO]
  implicit val currentRequests: RequestedBlocks[IO] =
    Ref.unsafe[IO, Map[BlockHash, RequestState]](Map.empty[BlockHash, RequestState])
  implicit val connectionsCell: ConnectionsCell[IO] =
    Ref.unsafe[IO, Connections](List(local))
  implicit val transportLayer = new TransportLayerStub[IO]
  implicit val rpConf         = createRPConfAsk[IO](local)
  implicit val time           = new LogicalTime[IO]
  implicit val commUtil       = CommUtil.of[IO]
  implicit val blockRetriever = BlockRetriever.of[IO]

  val hash = ByteString.copyFrom("hash", "UTF-8")
  val hb   = HasBlock(hash)

  val networkId = "nid"
  val conf      = RPConf(local, networkId, null, null, 0, null)

  private def endpoint(port: Int): Endpoint = Endpoint("host", port, port)
  private def peerNode(name: String, port: Int): PeerNode =
    PeerNode(NodeIdentifier(name.getBytes.toIndexedSeq), endpoint(port))

  private def alwaysSuccess: PeerNode => Protocol => CommErr[Unit] = kp(kp(Right(())))
  private def alwaysDoNotIgnoreF: BlockHash => IO[Boolean]         = _ => false.pure[IO]
  override def beforeEach(): Unit = {
    transportLayer.reset()
    transportLayer.setResponses(alwaysSuccess)
    time.reset()
  }

  describe("BlockRetriever") {
    it("should store on a waiting list and don't request if RequestState by different peer") {
      // given
      val sender    = peerNode("somePeer", 40400)
      val otherPeer = peerNode("otherPeer", 40400)
      val requestStateBefore =
        Map(
          hash -> RequestState(timestamp = System.currentTimeMillis, waitingList = List(otherPeer))
        )
      currentRequests.set(requestStateBefore).unsafeRunSync()
      // when
      NodeRunning.handleHasBlockMessage[IO](sender, hb.hash)(alwaysDoNotIgnoreF).unsafeRunSync()
      // then
      transportLayer.requests shouldBe empty

      val requestStateAfter = currentRequests.get.unsafeRunSync().get(hash).get
      requestStateAfter.waitingList.size should be(2)
    }

    it("should request block and add peer to waitingList list if peers list is empty") {
      // given
      val sender = peerNode("somePeer", 40400)
      val requestStateBefore =
        Map(
          hash -> RequestState(
            timestamp = System.currentTimeMillis,
            peers = Set.empty,
            waitingList = List.empty
          )
        )
      currentRequests.set(requestStateBefore).unsafeRunSync()
      // when
      NodeRunning.handleHasBlockMessage[IO](sender, hb.hash)(alwaysDoNotIgnoreF).unsafeRunSync()
      // then
      val (recipient, msg) = transportLayer.getRequest(0)
      // assert RequestState
      val br = BlockRequest.from(
        convert[PacketTypeTag.BlockRequest.type](toPacket(msg).toOption.get).get
      )
      br.hash shouldBe hash
      recipient shouldBe sender
      transportLayer.requests.size shouldBe 1
      // assert RequestState information stored
      val requestStateAfter = currentRequests.get.unsafeRunSync().get(hash).get
      requestStateAfter.waitingList should be(List(sender))
    }
  }

  describe("if there is no yet an an entry in the RequestState blocks") {
    it("should request block and store information about RequestState block") {
      // given
      val sender             = peerNode("somePeer", 40400)
      val requestStateBefore = Map.empty[BlockHash, RequestState]
      currentRequests.set(requestStateBefore).unsafeRunSync()
      // when
      NodeRunning.handleHasBlockMessage[IO](sender, hb.hash)(alwaysDoNotIgnoreF).unsafeRunSync()
      // then
      val (recipient, msg) = transportLayer.getRequest(0)
      // assert RequestState
      val br = BlockRequest.from(
        convert[PacketTypeTag.BlockRequest.type](toPacket(msg).toOption.get).get
      )
      br.hash shouldBe hash
      recipient shouldBe sender
      transportLayer.requests.size shouldBe 1
      // assert RequestState informaton stored
      val requestStateAfter = currentRequests.get.unsafeRunSync().get(hash).get
      requestStateAfter.waitingList should be(List(sender))
    }

    describe("if casper does NOT contain block with given hash") {
      describe("if there is already an entry in the RequestState blocks") {
        it("should ignore if peer on the RequestState peers list") {
          // given
          val sender                                   = peerNode("somePeer", 40400)
          val casperContains: BlockHash => IO[Boolean] = _ => true.pure[IO]
          // when
          NodeRunning.handleHasBlockMessage[IO](sender, hb.hash)(casperContains).unsafeRunSync()
          // then
          transportLayer.requests shouldBe empty
        }
      }
    }
  }

  describe("Running") {
    describe("handleHasBlock") {
      it("should not call send hash to BlockReceiver if it is ignorable hash") {
        // given
        val casperContains: BlockHash => IO[Boolean] = _ => true.pure[IO]
        // when
        NodeRunning.handleHasBlockMessage[IO](null, hb.hash)(casperContains).unsafeRunSync()
        // then
        transportLayer.requests shouldBe empty
      }
    }
  }
}
