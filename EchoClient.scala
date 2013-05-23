package com.umatrangolo.client

import java.io._
import java.net.{ ServerSocket, Socket }
import java.util.concurrent.Executors._

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.mutable.{ Map => MMap }

object RandomUtils {
  // Random generator
  val random = new scala.util.Random

  // Generate a random string of length n from the given alphabet
  def randomString(alphabet: String)(n: Int): String =
    Stream.continually(random.nextInt(alphabet.size)).map(alphabet).take(n).mkString

  // Generate a random alphabnumeric string of length n
  def randomAlphanumericString(n: Int) =
    randomString("abcdefghijklmnopqrstuvwxyz0123456789")(n)
}

object EchoServerClient {
  private[EchoServerClient] val log: Logger = LoggerFactory.getLogger(classOf[EchoServerClient])
  private[EchoServerClient] val rndGen = new java.util.Random()

  private[EchoServerClient] val words = scala.util.Random.alphanumeric
}

class EchoServerClient(n: Int) extends Runnable {
  import EchoServerClient._
  import RandomUtils._

  type Chat = (Socket, BufferedReader, PrintWriter)

  private val chats: MMap[Int, Chat] = MMap.empty[Int, Chat]

  1 to n foreach { tryEstablishChat(_, chats) }

  def tryEstablishChat(n: Int, chats: MMap[Int, Chat]) {
    try {
      val clientSocket = new Socket("localhost", 1090)
      val out = new PrintWriter(clientSocket.getOutputStream(), true)
      val in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
      chats += (n -> (clientSocket, in, out))
      log.info("Client %s started to chat with the EchoServer ... (%s clients so far)".format(n, chats.size))
    } catch {
      case e: Exception => {
        log.error("Unable to establish chat with server", e)
      }
    }
  }

  override def run() {
    var events = 0
    val todo = 10000

    while (events < todo) {
      val next = rndGen.nextInt(n)

      chats.get(next) match {
        case None => tryEstablishChat(next, chats)
        case Some((clientSocket, in, out)) => {
          try {
            val iSay = randomAlphanumericString(32)
            val now = System.currentTimeMillis
            out.println(iSay)
            out.flush()
            val answer = in.readLine()
            log.info("(%s ms.) (%s left) (%s -> %s)".format((System.currentTimeMillis - now), (todo - events), iSay, answer))
            Thread.sleep(rndGen.nextInt(100))
          } catch {
            case e: Exception => log.error("Error while talking", e)
          }

          events += 1
        }
      }
    }

    log.info("Shutting down ...")
    1 to n foreach { i =>
      chats.get(i).map { case (clientSocket, in, out) =>
        out.println("bye")
        out.flush()
        clientSocket.close()
      }
    }
  }
}

object EchoClientApp extends App {
  //val n = 10000 // c10K
  val n = 2500

  val client = new Thread(new EchoServerClient(n))
  client.setDaemon(false)
  client.start()
}
