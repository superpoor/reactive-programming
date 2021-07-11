package crawler

import akka.actor.{Actor, ActorLogging, ActorRef, Props, ReceiveTimeout}
import scala.concurrent.duration._

class Controller extends Actor with ActorLogging{
  // Either this or below
//  context.setReceiveTimeout(10.seconds)
  import context.dispatcher
  context.system.scheduler.scheduleOnce(10.seconds, self, Controller.Timeout)

  var cache = Set.empty[String]
  var children = Set.empty[ActorRef]
  def receive: Receive = {
    case Controller.Check(url, depth) =>
      log.debug("{} checking {}", depth, url)
      if (!cache(url) && depth > 0) {
        children += context.actorOf(Props(new Getter(url, depth - 1)))
      }
      cache += url

    case Getter.Done =>
      children -= sender
      if (children.isEmpty) context.parent ! Controller.Result(cache)

    case Controller.Timeout => children.foreach(_ ! Getter.Abort)
  }

}

object Controller {

  final case class Check(url: String, depth: Int)

  final case class Result(links: Set[String])

  final object Timeout

}
