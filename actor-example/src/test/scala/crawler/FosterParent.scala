package crawler

import akka.actor.{Actor, ActorRef, Props}

class FosterParent(childProps: Props, probe: ActorRef) extends Actor {
  private val child: ActorRef = context.actorOf(childProps, "child")
  def receive: Receive = {
    case msg if sender == context.parent =>
      probe forward msg
      child forward msg
    case msg =>
      probe forward msg
      context.parent forward msg
  }

}
