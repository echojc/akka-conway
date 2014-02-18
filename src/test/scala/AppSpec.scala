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

  var neighbours = Set.empty[ActorRef]
  var states = Map.empty[Int, Either[List[Either[ActorRef, State]], State]]
  var waitingStateRequests = List.empty[StateRequestContext]

  def receive = {

    case AddNeighbour(ref) =>
      if (neighbours contains ref) {
        sender ! AlreadyAddedNeighbour(ref)
      } else {
        neighbours += ref
        sender ! AddedNeighbour(ref)
      }

    case GetState(time) =>
      if (time <= 0) {
        sender ! MyState(time, State.Dead)
      } else if (states contains time) {
        states(time).fold(
          notReady => waitingStateRequests ::= StateRequestContext(sender, time),
          readyResult => sender ! MyState(time, readyResult)
        )
      } else {
        neighbours foreach (_ ! GetState(time - 1))
        states += (time -> Left(neighbours.map(Left(_)).toList))
        waitingStateRequests ::= StateRequestContext(sender, time)
      }

    case MyState(lastTime, state) if states contains (lastTime + 1) =>
      val time = lastTime + 1

      val newValue =
        states(time) match {
          case Left(waitingRequests) =>
            val newWaitingRequests =
              waitingRequests map {
                case Left(ref) if ref == sender => Right(state)
                case anythingElse => anythingElse
              }

            if (newWaitingRequests forall (_.isRight)) {
              val liveCount = newWaitingRequests count {
                case Right(State.Live) => true
                case _ => false
              }
              if (liveCount == 2 || liveCount == 3) {
                Right(State.Live)
              } else {
                Right(State.Dead)
              }
            } else {
              Left(newWaitingRequests)
            }
          case anythingElse => anythingElse
        }

      states += (time -> newValue)

      newValue.right.foreach { state =>
        val (readyReqs, pendingReqs) = waitingStateRequests partition (_.time == time)
        waitingStateRequests = pendingReqs
        readyReqs foreach (_.sender ! MyState(time, state))
      }
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
