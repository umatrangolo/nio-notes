package com.umatrangolo.server.nio

import java.net._
import java.nio._
import java.nio.channels._
import java.util.concurrent.Executors._

import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._

object Acceptor {
  val log: Logger = LoggerFactory.getLogger(classOf[Acceptor])
}

class Acceptor(val port: Int, dispatcher: Dispatcher) extends Runnable {
  import Acceptor._

  private val serverSocketChannel: ServerSocketChannel = ServerSocketChannel.open()

  serverSocketChannel.configureBlocking(true) // nm: accepting connections is cheap
  serverSocketChannel.socket.bind(new InetSocketAddress(port))
  log.info("Acceptor bound to port: " + port)

  override def run() {
    while (true) {
      try {
        log.info("Accepting connections ...")
        val clientSocketChannel = serverSocketChannel.accept() // block for clients
        clientSocketChannel.configureBlocking(false)
        dispatcher.register(clientSocketChannel)
        log.info("Accepted and registered connection from " + clientSocketChannel)
      } catch {
        case e: Exception => log.error("Error while accepting a connetion", e)
      }
    }
  }
}

object Dispatcher {
  val log: Logger = LoggerFactory.getLogger(classOf[Dispatcher])
  val internalExecutor = newFixedThreadPool(16)
}

class Dispatcher extends Runnable {
  import Dispatcher._

  private val selector = Selector.open()
  @volatile private var isRegistering = false

  override def run() {
    while (true) {
      try {
        if (!isRegistering) {
          val readyChs = selector.select()
          val selectedKeys = selector.selectedKeys().asScala.toList
          selector.selectedKeys().clear()
          if (selectedKeys.size > 0) {
            //log.info("Going to process %s selected keys for %s ready channels".format(selectedKeys.size, readyChs))
            dispatch(selectedKeys)
          }
        }
      } catch {
        case e: Exception => log.error("Error while dispatching a connection", e)
      }
    }
  }

  def register(socketChannel: SocketChannel) {
    isRegistering = true
    selector.wakeup()
    socketChannel.register(selector, SelectionKey.OP_READ)
    isRegistering = false
  }

  @tailrec
  private def dispatch(keys: List[SelectionKey]) {
    keys match {
      case key :: rest if (key.isValid && key.isReadable) => { // read event
        val readableChannel = key.channel.asInstanceOf[SocketChannel]
        val worker = new Worker(readableChannel)

        internalExecutor.submit(worker)
        //log.info("Dispatched read event for channel %s".format(readableChannel))

        dispatch(rest)
      }
      case key :: rest => { // unknown event on the channel or invalid key
        log.warn("Unknown event on channel: " + key.channel)
        dispatch(rest)
      }
      case Nil => //log.info("Finished to process ready channels")
    }
  }
}

object Worker {
  val log: Logger = LoggerFactory.getLogger(classOf[Worker])
}

class Worker(private val channel: SocketChannel) extends Runnable {
  import Worker._

  override def run() {
    val byteBuffer = ByteBuffer.allocateDirect(64)

    channel.read(byteBuffer) match {
      case n if (n > 0) => {
        val content = new String(0 until byteBuffer.limit map { i => byteBuffer.get(i) } toArray)
        val response = process(content)

        log.info("He says: %s, I say: %s".format(content.replaceAll("\n", ""), response.replaceAll("\n", "").toUpperCase))

        byteBuffer.clear()
        byteBuffer.put(response.getBytes)
        byteBuffer.flip()
        channel.write(byteBuffer)

        if (content.replaceAll("\n", "").trim  == "bye") close(channel)
      }
      case _ => {
        //log.warn("Empty content from channel %s".format(channel))
      }
    }
  }

  private def close(channel: SocketChannel) {
    try {
      channel.socket.close()
      log.info("Closed channel " + channel)
    } catch {
      case e: Exception => log.error("Error while closing socket: %s".format(channel.socket), e)
    }
  }

  private def process(s: String): String = s.replaceAll("\n", "").trim match {
    case "hello" => "Hello. My name is " + Thread.currentThread + "\n"
    case "bye" => "Bye. See you soon...\n"
    case _ => s.toUpperCase
  }
}

object EchoNIOServerApp extends App {
  private val log: Logger = LoggerFactory.getLogger(this.getClass)

  val port = 1090
  val dispatcher = new Dispatcher()
  val acceptor = new Thread(new Acceptor(port, dispatcher))
  val runningDispatcher = new Thread(dispatcher)
  runningDispatcher.setDaemon(true)
  acceptor.setDaemon(false)
  runningDispatcher.start()
  acceptor.start()

  log.info("Started server on port " + port)
}
