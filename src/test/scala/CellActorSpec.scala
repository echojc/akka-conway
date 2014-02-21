import scala.concurrent.duration._
import akka.actor._
import akka.testkit._

class CellActorSpec extends BaseSpec {
  import CellActor._

  var cell: TestActorRef[CellActor] = _
  before {
    cell = TestActorRef(new CellActor)
  }
  after {
    expectNoMsg(100.millis)
  }

  describe("a cell actor") {
    describe("adding neighbours") {
      it("responds AddedNeighbour for a new neighbour") {
        val probe = TestProbe()
        cell ! AddNeighbour(probe.ref)
        expectMsg(AddedNeighbour(probe.ref))
      }

      it("responds AlreadyAddedNeighbour for an existing neighbour") {
        val probe = TestProbe()
        cell !! AddNeighbour(probe.ref)
        cell ! AddNeighbour(probe.ref)
        expectMsg(AlreadyAddedNeighbour(probe.ref))
      }
    }

    describe("calculating requested state") {
      it("returns dead at time 0") {
        cell ! GetState(0)
        expectMsg(MyState(0, State.Dead))
      }

      it("returns dead at negative time") {
        cell ! GetState(-1)
        expectMsg(MyState(-1, State.Dead))
      }

      it("sends messages to neighbours for their state at t-1") {
        val probe = TestProbe()
        cell !! AddNeighbour(probe.ref)
        cell !! GetState(1)
        probe.expectMsg(GetState(0))
      }

      it("sends a message to itself for state at t-1") {
        val probe = TestProbe()
        cell !! AddNeighbour(probe.ref)
        cell !! GetState(2)
        probe.expectMsg(GetState(1))
        probe.expectMsg(GetState(0))
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
              cell !! AddNeighbour(probe.ref)
            }

            cell ! GetState(1)
            liveProbes foreach { probe =>
              probe.expectMsg(GetState(0))
              probe.reply(MyState(0, State.Live))
            }
            deadProbes foreach { probe =>
              probe.expectMsg(GetState(0))
              probe.reply(MyState(0, State.Dead))
            }

            expectMsg(MyState(1, targetState))
          }
        }
      }

      describe("state change when live") {
        Seq(
          0 -> State.Dead,
          1 -> State.Dead,
          2 -> State.Dead,
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
              cell !! AddNeighbour(probe.ref)
            }

            cell.underlyingActor.states = Map(0 -> Right(State.Live))
            cell ! GetState(1)
            liveProbes foreach { probe =>
              probe.expectMsg(GetState(0))
              probe.reply(MyState(0, State.Live))
            }
            deadProbes foreach { probe =>
              probe.expectMsg(GetState(0))
              probe.reply(MyState(0, State.Dead))
            }

            expectMsg(MyState(1, targetState))
          }
        }
      }
    }
  }
}
