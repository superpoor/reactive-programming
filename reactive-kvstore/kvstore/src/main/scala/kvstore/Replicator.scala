package kvstore

import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import scala.concurrent.duration._

object Replicator {
  case class Replicate(key: String, valueOption: Option[String], id: Long)
  case class Replicated(key: String, id: Long)
  
  case class Snapshot(key: String, valueOption: Option[String], seq: Long)
  case class SnapshotAck(key: String, seq: Long)

  case object Retransmit

  def props(replica: ActorRef): Props = Props(new Replicator(replica))
}

class Replicator(val replica: ActorRef) extends Actor {
  import context.dispatcher

  context.system.scheduler.scheduleAtFixedRate(
    200.milliseconds,
    200.milliseconds,
    self,
    Replicator.Retransmit
  )

  /*
   * The contents of this actor is just a suggestion, you can implement it in any way you like.
   */

  // map from sequence number to pair of sender and request
  var acks = Map.empty[Long, (ActorRef, Replicator.Replicate)]
  // a sequence of not-yet-sent snapshots (you can disregard this if not implementing batching)
  var pending = Vector.empty[Replicator.Snapshot]
  
  var _seqCounter = 0L
  def nextSeq() = {
    val ret = _seqCounter
    _seqCounter += 1
    ret
  }

  
  /* TODO Behavior for the Replicator. */
  def receive: Receive = {
    case rep: Replicator.Replicate => handleReplicate(rep)
    case Replicator.SnapshotAck(key, seq) => handleSnapshotAck(key, seq)
    case Replicator.Retransmit => handleRetransmit()
  }

  private def handleReplicate(replicate: Replicator.Replicate): Unit = {
    val seq = nextSeq()
    replica ! Replicator.Snapshot(replicate.key, replicate.valueOption, seq)
    acks += seq -> (context.sender, replicate)
  }

  private def handleSnapshotAck(key: String, seq: Long): Unit = {
    acks.get(seq).foreach { case (sender, replicateMsg) =>
      sender ! Replicator.Replicated(key, replicateMsg.id)
      acks -= seq
    }
  }

  private def handleRetransmit(): Unit = {
    acks.foreach { case (seq, (_, replicateMsg)) =>
      replica ! Replicator.Snapshot(replicateMsg.key, replicateMsg.valueOption, seq)
    }
  }

}
