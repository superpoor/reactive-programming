package actorbintree

import akka.actor.{Actor, ActorSystem, Props}

class Main extends Actor {

  val treeSet = context.actorOf(Props[BinaryTreeSet], "binarytreeset")

  treeSet ! BinaryTreeSet.Insert(self, 10, 1)
  treeSet ! BinaryTreeSet.Contains(self, 12, 1)
  treeSet ! BinaryTreeSet.Remove(self, 20, 1)
  treeSet ! BinaryTreeSet.GC
  treeSet ! BinaryTreeSet.Contains(self, 22, 1)


  def  receive: Receive = {
    case BinaryTreeSet.ContainsResult(id, result) =>
      println(s"Contains result for $id is $result")

    case BinaryTreeSet.OperationFinished(id) =>
      println(s"Operation finish for $id")
  }


}

object Main extends App {

  val system = ActorSystem("Main")
  val mainActor = system.actorOf(Props[Main])
}
