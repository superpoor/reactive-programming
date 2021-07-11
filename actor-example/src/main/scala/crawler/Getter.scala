package crawler

import org.jsoup.Jsoup
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

import akka.actor.{Actor, ActorLogging, Status}

class Getter(url: String, depth: Int) extends Actor with ActorLogging {

  implicit val exec = context.dispatcher

  def client: WebClient = AsyncWebClient

//  WebClient get url pipeTo self
  client.get(url).onComplete {
    case Success(body) => self ! body
    case Failure(err) => self ! Status.Failure(err)
  }

  private def findLinks(body: String): Iterator[String] = {
    val document = Jsoup.parse(body, url)
    val links = document.select("a[href]")
    for {
      link <- links.iterator().asScala
    } yield link.absUrl("href")
  }

  def receive = {
    case body: String =>
      for (link <- findLinks(body)) {
        context.parent ! Controller.Check(link, depth)
      }
      stop()
    case Getter.Abort => stop()
    case _: Status.Failure => stop()
  }

  def stop(): Unit = {
    context.parent ! Getter.Done
    context.stop(self)
  }

}

object Getter {
  object Done
  object Abort
}