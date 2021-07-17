package kvstore

import akka.actor.{ OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated, ActorRef, Actor }
import kvstore.Arbiter._
import akka.pattern.{ ask, pipe }
import scala.concurrent.duration._
import akka.util.Timeout

object Replica {
  sealed trait Operation {
    def key: String
    def id: Long
  }
  case class Insert(key: String, value: String, id: Long) extends Operation
  case class Remove(key: String, id: Long) extends Operation
  case class Get(key: String, id: Long) extends Operation

  sealed trait OperationReply
  case class OperationAck(id: Long) extends OperationReply
  case class OperationFailed(id: Long) extends OperationReply
  case class GetResult(key: String, valueOption: Option[String], id: Long) extends OperationReply

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
//  import context.dispatcher

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  // the expected seq counter of snapshot message
  var expectedSeqCounter = 0L

  // Replicate must first send a Join message to arbiter
  arbiter ! Join

  def receive: Receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for the leader role. */
  val leader: Receive = {
    case Replica.Insert(key, value, id) => handleInsert(key, value, id)
    case Replica.Remove(key, id) => handleRemove(key, id)
    case Replica.Get(key, id) => handleGet(key, id)
  }

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case Replicator.Snapshot(key, valueOpt, seq) => handleSnapshot(key, valueOpt, seq)
    case Replica.Get(key, id) => handleGet(key, id)
  }

  private def handleInsert(key: String, value: String, id: Long): Unit = {
    kv += key -> value
    sender ! Replica.OperationAck(id)
  }

  private def handleRemove(key: String, id: Long): Unit = {
    kv -= key
    sender ! Replica.OperationAck(id)
  }

  private def handleGet(key: String, id: Long): Unit = {
    sender ! Replica.GetResult(key, kv.get(key), id)
  }

  private def handleSnapshot(key: String, valueOpt: Option[String], seq: Long): Unit = {
    if (seq > expectedSeqCounter) {
      // Totally ignore
      ()
    } else if (seq < expectedSeqCounter) {
      // Only ack
      sender ! Replicator.SnapshotAck(key, seq)
    } else {
      // Update and ack
      valueOpt.fold { kv -= key } {
        value => kv += key -> value
      }
      expectedSeqCounter += 1
      sender ! Replicator.SnapshotAck(key, seq)
    }
  }

}

