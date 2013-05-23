package com.umatrangolo.server.netty

import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

import org.jboss.netty.bootstrap.ServerBootstrap
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.channel.ChannelHandlerContext
import org.jboss.netty.channel.ChannelPipeline
import org.jboss.netty.channel.ChannelPipelineFactory
import org.jboss.netty.channel.Channels
import org.jboss.netty.channel.ExceptionEvent
import org.jboss.netty.channel.MessageEvent
import org.jboss.netty.channel.SimpleChannelUpstreamHandler
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory
import org.jboss.netty.handler.codec.string.StringDecoder
import org.jboss.netty.handler.codec.string.StringEncoder

import org.slf4j.Logger
import org.slf4j.LoggerFactory

object EchoServerHandler {
  val log: Logger = LoggerFactory.getLogger(classOf[EchoServerHandler])
}

class EchoServerHandler extends SimpleChannelUpstreamHandler {
  import EchoServerHandler._

  private val transferredBytes: AtomicLong = new AtomicLong()

  override def messageReceived(ctx: ChannelHandlerContext , e: MessageEvent) {
    // Send back the received message to the remote peer.
    // this should be a String given that there is a codes/enoder in the pipeline
    val content: String = e.getMessage.asInstanceOf[String]
    transferredBytes.addAndGet(content.size)
    log.info("He says: %s, I say: %s".format(content.replaceAll("\n", ""), content.replaceAll("\n", "").toUpperCase))
    e.getChannel().write(content.toUpperCase);
  }

  override def exceptionCaught(ctx: ChannelHandlerContext, e: ExceptionEvent) {
    // Close the connection when an exception is raised.
    log.error("Unexpected exception from downstream: " + e.getCause(), e)
    e.getChannel.close()
  }
}

object EchoServer {
  val log: Logger = LoggerFactory.getLogger(classOf[EchoServer])
}

class EchoServer(port: Int) {
  import EchoServer._

  require(port > 0)

  def start() {
    val bootstrap: ServerBootstrap = new ServerBootstrap(
      new NioServerSocketChannelFactory(
        Executors.newCachedThreadPool(),
        Executors.newCachedThreadPool()
      )
    )

    bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
      override def getPipeline(): ChannelPipeline = {
        val pipeline: ChannelPipeline = Channels.pipeline()

        pipeline.addLast("decoder", new StringDecoder())
        pipeline.addLast("encoder", new StringEncoder())
        pipeline.addLast("handler", new EchoServerHandler())

        pipeline
      }
    })

    bootstrap.bind(new InetSocketAddress(port))
  }
}

object EchoNettyServerApp extends App {
  val log: Logger = LoggerFactory.getLogger(this.getClass)
  val port = 1090
  log.info("Starting Echo server on " + port)

  val server = new EchoServer(port)
  server.start()
  log.info("Started")
}
