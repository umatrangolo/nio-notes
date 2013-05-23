package com.umatrangolo.server.socket

import java.io._
import java.net.{ ServerSocket, Socket }
import java.util.concurrent.Executors._

import org.slf4j.Logger
import org.slf4j.LoggerFactory

object Server {
  private[Server] val log: Logger = LoggerFactory.getLogger(classOf[Server])
  private[Server] val internalExecutor = newCachedThreadPool()
}

class Server(private val port: Int) {
  import Server._

  object Acceptor {
    val log: Logger = LoggerFactory.getLogger(classOf[Acceptor])
  }

  class Acceptor extends Runnable {
    import Acceptor._

    override def run() {
      val serverSocket = new ServerSocket(port)

      while (true) {
        try {
          log.info("Accepting connection...")
          val clientSocket = serverSocket.accept() // blocks waiting for a client
          log.info("Accepted connection from " + clientSocket)
          internalExecutor.submit(new Worker(clientSocket))
        } catch {
          case e: Exception => log.error("Error while accepting a connection", e)
        }
      }
    }
  }

  object Worker {
    val log: Logger = LoggerFactory.getLogger(classOf[Worker])
  }

  class Worker(private val clientSocket: Socket) extends Runnable {
    import Worker._

    override def run() {
      val in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))
      val out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream())))

//      log.info("Talking with " + clientSocket)
      talk(in, out)
//      log.info("Finished to talk with " + clientSocket)

      def talk(in: BufferedReader, out: PrintWriter) {
         in.readLine() match { // blocks for input from the client
          case "hello" => {
            out.println("Hello. My name is " + Thread.currentThread)
            out.flush()
            talk(in, out)
          }
          case "bye" => {
            out.println("Bye. see you soon...")
            out.flush()
          }
          case youSay => {
            log.info("He says: %s, I say: %s".format(youSay, youSay.toUpperCase))
            out.println(youSay.toUpperCase)
            out.flush()
            talk(in, out)
          }
        }
      }
    }
  }

  require(port > 1024)

  def start() {
    log.info("Starting on port %s".format(port))
    val server = new Thread(new Acceptor())
    server.setDaemon(false)
    server.start()
  }
}

object EchoSocketServerApp extends App {
  private val log: Logger = LoggerFactory.getLogger(this.getClass)

  val port = 1090
  val server = new Server(port)
  server.start()
}
