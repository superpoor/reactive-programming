package kvstore

import akka.actor.{Actor, ActorRef, AllForOneStrategy, OneForOneStrategy, PoisonPill, Props, SupervisorStrategy, Terminated}
import kvstore.Arbiter._
import akka.pattern.{ask, pipe}
import scala.concurrent.duration._

import akka.util.Timeout
import kvstore.Replica.WaitingAck

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

  case class CheckOperationAckTimeout(id: Long)
  case object RetryPersist

  case class WaitingAck(
    requester: ActorRef,
    persistedAck: (Persistence.Persist, Boolean),
    replicatedAcks: Set[ActorRef]
  ) {
    lazy val isDone: Boolean = persistedAck._2 && replicatedAcks.isEmpty
  }

  def props(arbiter: ActorRef, persistenceProps: Props): Props = Props(new Replica(arbiter, persistenceProps))
}

class Replica(val arbiter: ActorRef, persistenceProps: Props) extends Actor {
  override val supervisorStrategy: SupervisorStrategy = OneForOneStrategy() {
    case _ => SupervisorStrategy.Restart
  }

  import context.dispatcher
  context.system.scheduler.scheduleAtFixedRate(
    100.milliseconds,
    100.milliseconds,
    self,
    Replica.RetryPersist
  )

  var kv = Map.empty[String, String]
  // a map from secondary replicas to replicators
  var secondaries = Map.empty[ActorRef, ActorRef]
  // the current set of replicators
  var replicators = Set.empty[ActorRef]
  // the expected seq counter of snapshot message
  var expectedSeqCounter = 0L
  // waiting acks from replicator and persistence
  var waitingAcks: Map[Long, Replica.WaitingAck] = Map.empty
  // create and watch persistence actor
  val persistenceActor: ActorRef = context.actorOf(persistenceProps, "Persistence")
  context.watch(persistenceActor)

  // Replicate must first send a Join message to arbiter
  arbiter ! Join

  def receive: Receive = {
    case JoinedPrimary   => context.become(leader)
    case JoinedSecondary => context.become(replica)
  }

  /* TODO Behavior for the leader role. */
  val leader: Receive = {
    case Arbiter.Replicas(replicas) => handleReplicas(replicas)
    case Replica.Insert(key, value, id) => handleInsert(key, value, id)
    case Replica.Remove(key, id) => handleRemove(key, id)
    case Replica.Get(key, id) => handleGet(key, id)
    case Replica.RetryPersist => handleRetryPersist()
    case Replica.CheckOperationAckTimeout(id) => handleOperationAckTimeout(id)
    case Persistence.Persisted(key, id) => handlePersisted(key, id, isPrimary = true)
    case Replicator.Replicated(key, id) => handleReplicated(key, id)
  }

  /* TODO Behavior for the replica role. */
  val replica: Receive = {
    case snapshot: Replicator.Snapshot => handleSnapshot(snapshot)
    case Replica.Get(key, id) => handleGet(key, id)
    case Replica.RetryPersist => handleRetryPersist()
    case Persistence.Persisted(key, id) => handlePersisted(key, id, isPrimary = false)
  }

  private def handleReplicas(replicas: Set[ActorRef]): Unit = {
    replicas.filter(_ != self).foreach { replica =>
      if (!secondaries.contains(replica)) {
        val replicator = context.actorOf(Replicator.props(replica))
        replicators += replicator
        secondaries += replica -> replicator
      }
    }
  }

  private def handleInsert(key: String, value: String, id: Long): Unit = {
    kv += key -> value
    handleSendUpdate(key, Some(value), id)
    context.system.scheduler.scheduleOnce(1.second, self, Replica.CheckOperationAckTimeout(id))
  }

  private def handleRemove(key: String, id: Long): Unit = {
    kv -= key
    handleSendUpdate(key, None, id)
    context.system.scheduler.scheduleOnce(1.second, self, Replica.CheckOperationAckTimeout(id))
  }

  private def handleGet(key: String, id: Long): Unit = {
    sender ! Replica.GetResult(key, kv.get(key), id)
  }

  private def handleSnapshot(snapshot: Replicator.Snapshot): Unit = {
    if (snapshot.seq > expectedSeqCounter) {
      // Totally ignore
      ()
    } else if (snapshot.seq < expectedSeqCounter) {
      // Only ack
      sender ! Replicator.SnapshotAck(snapshot.key, snapshot.seq)
    } else {
      snapshot.valueOption.fold { kv -= snapshot.key } {
        value => kv += snapshot.key -> value
      }
      handleSendUpdate(snapshot.key, snapshot.valueOption, snapshot.seq)
    }
  }

  private def handleOperationAckTimeout(id: Long): Unit = {
    waitingAcks.get(id).foreach { waitingAck =>
      if (!waitingAck.isDone) {
        waitingAck.requester ! Replica.OperationFailed(id)
      }
      waitingAcks -= id
    }
  }

  private def handleRetryPersist(): Unit = {
    waitingAcks.foreach { case (_, waitingAck) =>
      if (!waitingAck.persistedAck._2) {
        persistenceActor ! waitingAck.persistedAck._1
      }
    }
  }

  private def handlePersisted(key: String, id: Long, isPrimary: Boolean): Unit = {
    waitingAcks.get(id).foreach { waitingAck =>
      val newAwaitingAck = waitingAck.copy(persistedAck = waitingAck.persistedAck._1 -> true)
      if (newAwaitingAck.isDone) {
        waitingAcks -= id
        if (isPrimary) {
          waitingAck.requester ! Replica.OperationAck(id)
        } else {
          expectedSeqCounter = id + 1
          waitingAck.requester ! Replicator.SnapshotAck(key, id)
        }
      } else {
        waitingAcks += id -> newAwaitingAck
      }
    }
  }

  private def handleReplicated(key: String, id: Long): Unit = {
    waitingAcks.get(id).foreach { waitingAck =>
      val newWaitingAck = waitingAck.copy(replicatedAcks = waitingAck.replicatedAcks - sender)
      if (newWaitingAck.isDone) {
        waitingAcks -= id
        waitingAck.requester ! Replica.OperationAck(id)
      } else {
        waitingAcks += id -> newWaitingAck
      }
    }
  }

  private def handleSendUpdate(key: String, valueOpt: Option[String], id: Long): Unit = {
    secondaries.foreach {
      case (_, replicator) =>
        replicator ! Replicator.Replicate(key, valueOpt, id)
    }
    persistenceActor ! Persistence.Persist(key, valueOpt, id)
    waitingAcks += id -> WaitingAck(
      requester = sender,
      persistedAck = Persistence.Persist(key, valueOpt, id) -> false,
      replicatedAcks = secondaries.values.toSet
    )
  }

  override def postStop(): Unit = {
    persistenceActor ! PoisonPill
    replicators.foreach(_ ! PoisonPill)
  }

}

