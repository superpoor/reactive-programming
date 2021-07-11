package crawler

import akka.actor.{Actor, ActorSystem, Props, ReceiveTimeout}
import scala.concurrent.duration._

class Main extends Actor {

  val receptionist = context.actorOf(Props[Receptionist], "receptionist")

  receptionist ! Receptionist.Get("http://www.google.com")
  receptionist ! Receptionist.Get("http://www.google.com/1")
  receptionist ! Receptionist.Get("http://www.google.com/2")
  receptionist ! Receptionist.Get("http://www.google.com/3")
  receptionist ! Receptionist.Get("http://www.google.com/4")
  receptionist ! Receptionist.Get("http://www.google.com/5")

  context.setReceiveTimeout(10.seconds)

  def receive: Receive = {
    case Receptionist.Result(url, links) =>
      println(links.toVector.sorted.mkString(s"Results for '$url': \n", "\n", "\n"))
    case Receptionist.Failed(url) =>
      println(s"Failed to fetch '$url'\n")
    case ReceiveTimeout =>
      context.stop(self)
  }

  override def postStop(): Unit = {
    AsyncWebClient.shutdown()
  }

}

object Main extends App {

  val system = ActorSystem("Main")
  val mainActor = system.actorOf(Props[Main])
}
