import akka.testkit._
import akka.actor._

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

