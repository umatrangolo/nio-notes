nio-notes
=========

My study notes about NIO.

To play with them:

            sbt run

will show up the list of available apps to run:

           [1] com.umatrangolo.client.EchoClientApp
           [2] com.umatrangolo.server.nio.EchoNIOServerApp
           [3] com.umatrangolo.server.socket.EchoSocketServerApp
           [4] com.umatrangolo.server.netty.EchoNettyServerApp

The first one is a simple client that will open 2500 connections to
the running server.

The server is a simple echo server implemented in three different ways:

* Plain socket server (thread-per-server)
* NIO Channels (thread-per-event)
* Netty (undercover NIO)

Lots of missing parts and poor concurrency handling: don't do this at home!

ugo.matrangolo@gmail.com
23-May-2013
---