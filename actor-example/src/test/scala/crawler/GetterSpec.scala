package crawler

import java.util.concurrent.Executor
import scala.concurrent.Future

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.junit.Test

class GetterSpec extends TestKit(ActorSystem("GetterSpec")) with ImplicitSender  {

  import GetterSpec._

  @Test def `A Getter should return the right body`: Unit = {
    val getter = system.actorOf(Props(new FosterParent(fakeGetter(firstLink, 2), testActor)), "rightBody")
    for (link <- links(firstLink))
      expectMsg(Controller.Check(link, 2))
    expectMsg(Getter.Done)
  }

  @Test def `A Getter should properly finish in case of errors`: Unit = {
    val getter = system.actorOf(Props(new FosterParent(fakeGetter("Unknown", 2), testActor)), "wrongLink")
    expectMsg(Getter.Done)
  }

}

object GetterSpec {

  val firstLink = "http://www.rkuhn.info/1"

  val bodies = Map(
    firstLink ->
      """
        |<html>
        | <head><title>Page</title></head>
        |<body>
        | <h1>A Link</h1>
        | <a href="http://rkuhn.info/2">Click here</a>
        |</body>
        |</html>""".stripMargin
  )

  val links = Map(
    firstLink -> Seq("http://rkuhn.info/2")
  )

  object FakeWebClient extends WebClient {
    override def get(url: String)(implicit exec: Executor): Future[String] =
      bodies get url match {
        case None => Future.failed(BadStatus(404))
        case Some(body) => Future.successful(body)
      }
  }

  def fakeGetter(url: String, depth: Int): Props =
    Props(new Getter(url, depth) {
      override def client: WebClient = FakeWebClient
    })

}