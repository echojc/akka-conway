import scala.concurrent.duration._
import akka.actor._
import akka.testkit._

import org.scalatest._

abstract class BaseSpec(val _system: ActorSystem)
    extends TestKit(_system)
    with ImplicitSender
    with FunSpecLike
    with ShouldMatchers
    with BeforeAndAfter
    with BeforeAndAfterAll {

  def this() = this(ActorSystem("test-system"))

  override def afterAll(): Unit = {
    _system.shutdown()
  }

  implicit class RouteRepliesToDeadLetter(ref: ActorRef) {
    def !![T](msg: T)(implicit system: ActorSystem) =
      ref.tell(msg, system.deadLetters)
  }
}

sealed trait State
object State {
  case object Dead extends State
  case object Live extends State
}

sealed trait Msg
object Msg {
  case class GetState(time: Int) extends Msg
  case class AddNeighbour(ref: ActorRef) extends Msg

  case class MyState(time: Int, state: State) extends Msg
  case class AddedNeighbour(ref: ActorRef) extends Msg
  case class AlreadyAddedNeighbour(ref: ActorRef) extends Msg
}

case class StateRequestContext(sender: ActorRef, time: Int)

class CellActor extends Actor {
  import Msg._

  type PendingRequest = Either[ActorRef, State]

  var neighbours = Set.empty[ActorRef]
  var states = Map.empty[Int, Either[List[PendingRequest], State]]
  var pendingStateRequests = List.empty[StateRequestContext]

  def receive = {

    case AddNeighbour(neighbour) =>
      if (neighbours contains neighbour) {
        sender ! AlreadyAddedNeighbour(neighbour)
      } else {
        addNeighbour(neighbour)
        sender ! AddedNeighbour(neighbour)
      }

    case GetState(time) =>
      if (time <= 0) {
        respondWithState(time, State.Dead)
      } else if (states contains time) {
        checkCachedStateForAction(time)
      } else {
        sendStateRequestsToNeighbours(time - 1)
        queueStateRequest(time)
      }

    case MyState(lastTime, state) if states contains (lastTime + 1) =>
      val time = lastTime + 1

      val newState = updatedState(time, state)
      processPendingStateRequests(time, newState)
      states += (time -> newState)
  }

  def addNeighbour(newNeighbour: ActorRef): Unit =
    neighbours += newNeighbour

  def checkCachedStateForAction(time: Int): Unit =
    states(time) match {
      case Left(_) => queueStateRequest(time)
      case Right(result) => respondWithState(time, result)
    }

  def queueStateRequest(time: Int): Unit =
    pendingStateRequests ::= StateRequestContext(sender, time)

  def respondWithState(time: Int, state: State, target: ActorRef = sender): Unit =
    target ! MyState(time, state)

  def sendStateRequestsToNeighbours(time: Int): Unit = {
    neighbours foreach (_ ! GetState(time))

    val pendingReplies = neighbours map (Left(_))
    val pendingTime = time + 1
    states += (pendingTime -> Left(pendingReplies.toList))
  }

  def updatedState(time: Int, state: State) =
    states(time) match {
      case Left(pendingRequests) => updatedPendingRequests(pendingRequests, state)
      case anythingElse => anythingElse
    }

  def updatedPendingRequests(requests: List[PendingRequest], state: State) = {
    val newPendingRequests = determineNewPendingRequests(requests, state)

    if (isPendingRequestsReady(newPendingRequests))
      Right(determineNewState(newPendingRequests))
    else
      Left(newPendingRequests)
  }

  def determineNewPendingRequests(requests: List[PendingRequest], state: State) =
    requests map {
      case Left(pendingActor) if pendingActor == sender => Right(state)
      case anythingElse => anythingElse
    }

  def isPendingRequestsReady(requests: List[PendingRequest]): Boolean =
    requests forall (_.isRight)

  def determineNewState(requests: List[PendingRequest]): State = {
    val liveCount = countLiveCells(requests)

    if (liveCount == 2 || liveCount == 3)
      State.Live
    else
      State.Dead
  }

  def countLiveCells(requests: List[PendingRequest]): Int =
    requests count {
      case Right(State.Live) => true
      case _ => false
    }

  def processPendingStateRequests(time: Int, state: Either[List[PendingRequest], State]): Unit =
    state.right.foreach { state =>
      val (readyRequests, pendingRequests) =
        pendingStateRequests partition (_.time == time)

      readyRequests foreach (ctx => respondWithState(time, state, ctx.sender))
      pendingStateRequests = pendingRequests
    }
}

class AppSpec extends BaseSpec {
  var cell: ActorRef = _

  before {
    cell = newCell
  }

  after {
    expectNoMsg(100.millis)
  }

  def newCell = TestActorRef(new CellActor)

  describe("a cell actor") {
    describe("adding neighbours") {
      it("responds AddedNeighbour for a new neighbour") {
        val probe = TestProbe()
        cell ! Msg.AddNeighbour(probe.ref)
        expectMsg(Msg.AddedNeighbour(probe.ref))
      }

      it("responds AlreadyAddedNeighbour for an existing neighbour") {
        val probe = TestProbe()
        cell !! Msg.AddNeighbour(probe.ref)
        cell ! Msg.AddNeighbour(probe.ref)
        expectMsg(Msg.AlreadyAddedNeighbour(probe.ref))
      }
    }

    describe("calculating requested state") {
      it("returns dead at time 0") {
        cell ! Msg.GetState(0)
        expectMsg(Msg.MyState(0, State.Dead))
      }

      it("returns dead at negative time") {
        cell ! Msg.GetState(-1)
        expectMsg(Msg.MyState(-1, State.Dead))
      }

      it("sends messages to neighbours for their state at t-1") {
        val probe = TestProbe()
        cell !! Msg.AddNeighbour(probe.ref)
        cell !! Msg.GetState(1)
        probe.expectMsg(Msg.GetState(0))
      }

      describe("state change when dead") {
        Seq(
          0 -> State.Dead,
          1 -> State.Dead,
          2 -> State.Live,
          3 -> State.Live,
          4 -> State.Dead,
          5 -> State.Dead,
          6 -> State.Dead,
          7 -> State.Dead,
          8 -> State.Dead
        ) foreach { case (liveNeighbours, targetState) =>
          it(s"becomes $targetState with $liveNeighbours live neighbours") {
            val liveProbes = List.fill(liveNeighbours)(TestProbe())
            val deadProbes = List.fill(8 - liveNeighbours)(TestProbe())
            (liveProbes ++ deadProbes) foreach { probe =>
              cell !! Msg.AddNeighbour(probe.ref)
            }

            cell ! Msg.GetState(1)
            liveProbes foreach { probe =>
              probe.expectMsg(Msg.GetState(0))
              probe.reply(Msg.MyState(0, State.Live))
            }
            deadProbes foreach { probe =>
              probe.expectMsg(Msg.GetState(0))
              probe.reply(Msg.MyState(0, State.Dead))
            }

            expectMsg(Msg.MyState(1, targetState))
          }
        }
      }
    }
  }
}
