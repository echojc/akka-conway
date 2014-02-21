import akka.actor._

object CellActor {
  case class GetState(time: Int)
  case class AddNeighbour(ref: ActorRef)

  case class MyState(time: Int, state: State)
  case class AddedNeighbour(ref: ActorRef)
  case class AlreadyAddedNeighbour(ref: ActorRef)

  case class StateRequestContext(sender: ActorRef, time: Int)

  sealed trait State
  object State {
    case object Dead extends State
    case object Live extends State
  }
}

class CellActor extends Actor {
  import CellActor._

  type PendingRequest = Either[ActorRef, State]

  var neighbours = Set.empty[ActorRef]
  var states = Map[Int, Either[List[PendingRequest], State]](
    0 -> Right(State.Dead)
  )
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
      if (time < 0) {
        respondWithState(time, State.Dead)
      } else if (states contains time) {
        checkCachedStateForAction(time)
      } else {
        sendStateRequestsForTime(time - 1)
        queueStateRequest(time)
      }

    case MyState(lastTime, state) if states contains (lastTime + 1) =>
      val time = lastTime + 1
      states += (time -> updatedState(time, state))
      processPendingStateRequests(time)
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

  def sendStateRequestsForTime(time: Int): Unit = {
    (neighbours + self) foreach (_ ! GetState(time))

    val pendingReplies = neighbours map (Left(_))
    val pendingTime = time + 1
    states += (pendingTime -> Left(pendingReplies.toList))
  }

  def updatedState(time: Int, state: State) = {
    states(time) match {
      case Left(pendingRequests) =>
        val newPendingRequests = updatedPendingRequests(pendingRequests, state)
        if (isPendingRequestsReady(time, newPendingRequests))
          Right(determineNewState(time, newPendingRequests))
        else
          Left(newPendingRequests)

      case anythingElse => anythingElse
    }
  }

  def updatedPendingRequests(requests: List[PendingRequest], state: State) =
    requests map {
      case Left(pendingActor) if pendingActor == sender => Right(state)
      case anythingElse => anythingElse
    }

  def isPendingRequestsReady(time: Int, requests: List[PendingRequest]): Boolean =
    (states.get(time - 1).map(_.isRight) == Some(true)) && (requests forall (_.isRight))

  def determineNewState(time: Int, requests: List[PendingRequest]): State = {
    val liveCount = countLiveCells(requests)
    val previousState = states(time - 1)
    val deadConditionHolds =
      previousState == Right(State.Dead) && (liveCount == 2 || liveCount == 3)
    val liveConditionHolds =
      previousState == Right(State.Live) && liveCount == 3

    if (deadConditionHolds || liveConditionHolds)
      State.Live
    else
      State.Dead
  }

  def countLiveCells(requests: List[PendingRequest]): Int =
    requests count {
      case Right(State.Live) => true
      case _ => false
    }

  def processPendingStateRequests(time: Int): Unit =
    states(time).right.foreach { state =>
      val (readyRequests, pendingRequests) =
        pendingStateRequests partition (_.time == time)

      readyRequests foreach (ctx => respondWithState(time, state, ctx.sender))
      pendingStateRequests = pendingRequests
    }
}

