package crawler

import java.util.concurrent.Executor
import scala.concurrent.{Future, Promise}

import org.asynchttpclient.Dsl

trait WebClient {
  def get(url: String)(implicit exec: Executor): Future[String]
}

case class BadStatus(errorCode: Int) extends Exception

object AsyncWebClient extends WebClient {

  private val client = Dsl.asyncHttpClient

  def get(url: String)(implicit exec: Executor): Future[String] = {
    val f = client.prepareGet(url).execute()
    val p = Promise[String]()
    f.addListener(new Runnable {
      override def run(): Unit = {
        val response = f.get
        if (response.getStatusCode < 400) {
          p.success(response.getResponseBody)
        } else {
          p.failure(BadStatus(response.getStatusCode))
        }
      }
    }, exec)
    p.future
  }

  def shutdown(): Unit = {
    client.close()
  }

}
