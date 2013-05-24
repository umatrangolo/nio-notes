NIO
===

The NIO (New-IO) library under java.nio._ provides:

- Zero copy byte buffers
- Non blocking IO
- Multiplexed IO

It has been available since Java 1.4 and its goal is to solve the c10K
problem on the JVM.

c10K
----

"It's time for web servers to handle ten thousand clients
simultaneously, don't you think? After all, the web is a big place
now." (c10K problem) [c10K](http://www.kegel.com/c10k.html)

The problem boils down to write a web server that is able to handle
efficiently thousands of *concurrent* connections wout eating up all
its hosts resources (e.g. a chat server).

This was already a problem when the c10K problem was first stated
(~2003) and the issue grow exponentially with time because now any
decent web browsers has to support the Comet pattern.

### Comet

"Comet is a web application model in which a long-held HTTP request
allows a web server to push data to a browser, without the browser
explicitly requesting it"

This is implemented is different ways but afaik the most succesful one
is XMLHttpRequest (aka Ajax).

C10K Solution
-------------

The standard way to implement a web server was to bind a
process/thread to a request. The architecture (thread-per-request) was
simple:

- Acceptor thread accept()ing incoming connections on a server socket
  and spawing new worker threads with the resulting client socket.
- Worker thread getting the incoming request and processing the
  response.

This is the *most performant* solution if you have very short lived
connections where your clients are interested in some sort of
information and once obtained it they will rarely come back.

Unfortunately it does not scale with the c10k problem.

- Too many threads (try to spwan 10k threads on a JVM)
- Too many context switches (costly operation)

### Non blocking IO
The main problem with the thread-per-request approach is that you have
to *block* on each connection (socket) waiting for some event coming
from the client.

The solution is to use *non blocking* IO and embrace an event-driven
architecture.

In this architecture a thread is bound no more to a request but reacts
to an event. A single acceptor thread listens for incoming connections
and a Dispatcher thread dispatches events coming from the connections
to a pool of worker thread. This way we decoupled the threads handling
the connections from the threads doing the actual work. The throughput
of your server is now only limited by CPU, ram and network.

Non Blocking IO on the JVM
--------------------------

In the JVM world the c10K solution was first introduced in JDK 1.4
with the java.nio._ package.

To understand how this work we need to start from the OS foundations
and make our way up to the application layer:


           ... thousands of concurrent clients ....

        --app------------------------------------------
                   Your c10k compliant web app
        --framework------------------------------------
          (Reactor pattern) --> Netty, Grizzly, Ning
        --JVM------------------------------------------
          java.nio. { Buffer, Channel, Selector, ... }
        -OS--------------------------------------------
                 epoll(), ioctl(), select(), ...
        -HW--------------------------------------------
                      NIC Device Driver
