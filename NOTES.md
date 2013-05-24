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
its host resources (e.g. a chat server).

This was already a problem when the c10K problem was first stated
(~2003) and the issue grew exponentially with time because now any
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

- An Acceptor thread accept()ing incoming connections on a server socket
  and spawing new worker threads passing resulting client socket.
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
to a pool of worker threads. This way we decoupled the threads handling
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
             Your c10k compliant web app (e.g. Play)
        --framework------------------------------------
          (Reactor pattern) --> Netty, Grizzly, Ning
        --JVM------------------------------------------
          java.nio. { Buffer, Channel, Selector, ... }
        -OS--------------------------------------------
                     select(), epoll_*()
        -HW--------------------------------------------
                      NIC Device Driver

OS
--

At the OS level the support for non blocking IO is provided by a set of
well crafted sys calls.

The first intersting sys call is:

          int select(int nfds,
                     fd_set *restrict readfds,
                     fd_set *restrict writefds,
                     fd_set *restrict errorfds,
                     struct timeval *restrict timeout);

*select()* examines the I/O descriptor sets whose addresses are passed
in readfds, writefds, and errorfds to see if some of their descriptors
are ready for reading, are ready for writing, or have an exceptional
condition pending, respectively.

An evolution of select() on Linux is *epoll()*:

The epoll API performs a similar task to select(): monitoring multiple
file descriptors to see if I/O is possible on any of them.  The epoll
API can be used either as an edge-triggered or a level-triggered
interface and scales well to large numbers of watched file
descriptors.  The following system calls are provided to create and
manage an epoll instance:

*  epoll_create() creates an epoll instance and returns a file
   descriptor referring to that instance.

              int epoll_create(int size); // size is ignored

*  Interest in particular file descriptors is then registered via
   epoll_ctl(). The set of file descriptors currently registered on an
   epoll instance is sometimes called an epoll set.

              int epoll_ctl(int epfd, // fd got from the previous call
                            int op, // what do you want to do on it ?
                            int fd, // on what do you want to work on ?
                            struct epoll_event *event); // ready event on fd

*  epoll_wait() waits for I/O events, *blocking* the calling thread if
   no events are currently available.

             int epoll_wait(int epfd, // epoll fd
                           struct epoll_event *events, // ready events available
                           int maxevents,
                           int timeout);

As we will see all the machinery in java.nio._ will exactly mimic the
same logic we found at the OS level with the epoll() calls.

JVM
---

NIO on the JVM is implemented in the java.nio._ package and uses the following
classes and types that we will explore in details:

- java.nio.Buffer
- java.nio.ByteBuffer
- java.nio.channels.Channel
- java.nio.channels.Selector
- java.nio.channels.SelectionKey

### java.nio.Buffer

This is the basic type that is used along all the NIO machinery. It
provides a way to efficiently store, manipulate and move around
chuncks of bytes.

Each Buffer is characterized by 4 indexes:

- capacity
  The max amount of items the buffer can hold.
- limit
  The first element of the buffer that can't be read/written.
- position
  The index of the next element to be read or written.
- mark
  A `bookmarked` position inside the buffer.

During all operations on a Buffer the following invariant is always
met:

        0 <= mark <= position <= limit <= capacity

A typical buffer containing the string 'Hello' would be like:

              0   1   2   3   4   5   6   7   8
            +-------------------------------+
            | H | e | l | l | o |   |   |   |( )
            +-------------------------------+

where we will have that pos=5 lim=8 cap=8.

NIO prefers to handle incoming/outcoming data as stream of bytes; the
main implementation of the Buffer type is the ByteBuffer class that
can be created using two static factory methods:

    scala> java.nio.ByteBuffer.allocate(8)
    res1: java.nio.ByteBuffer = java.nio.HeapByteBuffer[pos=0 lim=8 cap=8]

or

    scala> java.nio.ByteBuffer.allocateDirect(8)
    res2: java.nio.ByteBuffer = java.nio.DirectByteBuffer[pos=0 lim=8 cap=8]

The difference is important: the first one creates a plain buffer
located in the heap while the second one creates a `direct` buffer:
one that is placed *outside* the heap as far as possible from the
GC. From a performance point of view this is needed if we think on how
IO happens between the JVM and the OS: when some piece of data is
available from the NIC device driver it gets copied into an internal
kernel buffer. Later on, the JVM requests this data with a sys call
and it gets moved in an userland buffer. This means that the same
piece of data has been moved twice. The JVM can't access a kernel
buffers and the kernel can't write directly in a JVM buffer because
the GC can move it around halfway.

To overcome these limitations a direct ByteBuffer is guaranteed to be
placed in a location outside of the JVM's heap where the data can be
safely shared (memory mapped) between the kernel and the JVM. Thus,
the bytes from the NIC are stored and are directly available to the
JVM wout wasting time moving them around. Also, given that the buffer
is not affected by the JVM, the OS can use DMA to move bytes in the
buffer in a more efficient way.

This is what is called Zero-Copy-Buffer in NIO jargon.

Using a Buffer is a bit tricky and requires to keep an eye on the
*position* value otherwise you will end up giving a misconfigured
buffer to a consumer.

To fill a buffer there is the usual put() call:

    scala> bb.put('F': Byte)
    res10: java.nio.ByteBuffer = java.nio.HeapByteBuffer[pos=1 lim=8 cap=8]

    scala> bb.put('o': Byte)
    res11: java.nio.ByteBuffer = java.nio.HeapByteBuffer[pos=2 lim=8 cap=8]

    scala> bb.put('o': Byte)
    res12: java.nio.ByteBuffer = java.nio.HeapByteBuffer[pos=3 lim=8 cap=8]

Note how the `position` value gets incremented each time.

To read from the buffer:

    scala> bb.get()
    res18: Byte = 0

as expected: we got a 0 because the `position` was not pointing to the
first byte of 'Foo'. To actually retrieve the content of the buffer we
need to switch in 'drain' mode using the `flip()` call:

    scala> bb.flip()
    res19: java.nio.Buffer = java.nio.HeapByteBuffer[pos=0 lim=4 cap=8]

The `flip()` call moved the indexes so the buffer can now be safely
drained:

    scala> bb.get()
    res20: Byte = 70

    scala> 'F'.toByte
    res21: Byte = 70

These are the most basic things about ByteBuffers. Actually,
java.nio._ contains a lot more. Just to scratch:

- Decorations of the ByteBuffer to have CharBuffer, FloatBuffer, DoubleBuffer ...
- Compacting, bulk moves, views, etc ...

### java.nio.Channel

A *Channel* models a duplex communication media. Buffers are used to
write something in a Channel and to read stuff from it. This is what
the JVM uses to represent a file descriptor from the OS.

We usually bind a Channel to a File or to a Socket. We are interested
in the networking part so we will focus only on the the former usage.

There are two related Channel impls for networking:

- java.nio.channels.ServerSocketChannel
- java.nio.channels.SocketChannel

The first one is used to accept incoming connection requests while the
second is the final socket that comes out from the connection
handshaking.

Let's fire up a little server on port 1090:

    scala> val ssc = java.nio.channels.ServerSocketChannel.open()
    ssc: java.nio.channels.ServerSocketChannel = sun.nio.ch.ServerSocketChannelImpl[unbound]

The server Channel is ready but still unbound. Every Channel has an
internal socket that we can use: this allowed to not copy over all the
java.net._ API into the NIO package.

    scala> ssc.socket.bind(new java.net.InetSocketAddress(1090))

    scala> ssc
    res24: java.nio.channels.ServerSocketChannel = sun.nio.ch.ServerSocketChannelImpl[/0.0.0.0:1090]

Now our socket is bound and we can start to accept connections:

    scala> ssc.accept() // this will block!

Ouch! This call is blocking even if we are using the NIO
package. Actually, this is desired behavior: until not configured
accordingly every Channel will be in blocking mode:

    scala> ssc.isBlocking
    res26: Boolean = true

    scala> ssc.configureBlocking(false)
    res27: java.nio.channels.SelectableChannel = sun.nio.ch.ServerSocketChannelImpl[/0.0.0.0:1090]

    scala> ssc.isBlocking
    res28: Boolean = false

Now let's try again:

    scala> ssc.accept()
    res29: java.nio.channels.SocketChannel = null

As expected we got a null. The accept() call had nothing to connect to
and given that now ssc is now in non blocking mode it returned null.

If we fire up a telnet in another window:

   IRL-ML-DUBLIN:~ umatrangolo$ telnet localhost 1090
   Trying ::1...
   Connected to localhost.
   Escape character is '^]'.

and we try again to accept:

     scala> val sc = ssc.accept()
     sc: java.nio.channels.SocketChannel = java.nio.channels.SocketChannel[connected local=/0:0:0:0:0:0:0:1:1090 remote=/0:0:0:0:0:0:0:1:65519]

This time there was something to accept and we got a nice socket
channel to work with the client:

Let's say 'Hello' from the telnet client and try to read it from the Channel. We need a Buffer
to store the incoming bytes:

    scala> val bb = java.nio.ByteBuffer.allocateDirect(16)
    bb: java.nio.ByteBuffer = java.nio.DirectByteBuffer[pos=0 lim=16 cap=16]

    scala> sc.read(bb) // blocks!

Again! We need to configure the client socket to be non blocking:

    scala> sc.configureBlocking(false)
    res35: java.nio.channels.SelectableChannel = java.nio.channels.SocketChannel[connected local=/0:0:0:0:0:0:0:1:1090 remote=/0:0:0:0:0:0:0:1:65519]

Try again:

    scala> sc.read(bb)
    res42: Int = 0

This time the read() call returned instantly and given that there was
nothing to read our buffer is empty:

    scala> bb
    res43: java.nio.ByteBuffer = java.nio.DirectByteBuffer[pos=0 lim=16 cap=16]

If we write something in the Telnet shell and we read() again:

    scala> sc.read(bb)
    res44: Int = 8

    scala> bb
    res45: java.nio.ByteBuffer = java.nio.DirectByteBuffer[pos=8 lim=16 cap=16]

    scala> bb.flip()
    res46: java.nio.Buffer = java.nio.DirectByteBuffer[pos=0 lim=8 cap=16]

    scala> bb.get(0) == 'H'.toChar
    res47: Boolean = true

Excellent! This time we had something from the Channel and we now have
all the bytes in the buffer ready to be processed.

This is the most basic non-blocking IO operation that could be done
with NIO. Channel and Buffer are potentially all we need to perform
non blocking IO but it will be tedious and requires a lot of crafting
around the polling of all our channels. Luckily there is the final
piece: SelectableChannel and multiplexed IO.

### Selector

Here it comes the Java select() call that mimics the epoll_*() calls
we found in the OS layer. The selection process comprises:

- A `Selector` object that holds all the channels you are intersted on.
- A `SelectionKey` that keeps track of a registration op and on what
  kind of operation on the channel are we intersted.
- The select() call on a Selector that blocks and returns all `ready
  to be serviced` channels.

Let's have the open connections:

    scala> val sc1 = ssc.accept()
    sc1: java.nio.channels.SocketChannel = java.nio.channels.SocketChannel[connected local=/0:0:0:0:0:0:0:1:1090 remote=/0:0:0:0:0:0:0:1:49686]

    scala> val sc2 = ssc.accept()
    sc2: java.nio.channels.SocketChannel = java.nio.channels.SocketChannel[connected local=/0:0:0:0:0:0:0:1:1090 remote=/0:0:0:0:0:0:0:1:49699]

    scala> val sc3 = ssc.accept()
    sc3: java.nio.channels.SocketChannel = java.nio.channels.SocketChannel[connected local=/0:0:0:0:0:0:0:1:1090 remote=/0:0:0:0:0:0:0:1:49704]

Creating a Selector:

    scala> val selector = java.nio.channels.Selector.open()
    selector: java.nio.channels.Selector = sun.nio.ch.KQueueSelectorImpl@73514aec

With this selector we can declare interest in reading from each one of
the channels:

    scala> sc1.register(selector, java.nio.channels.SelectionKey.OP_READ)
    res57: java.nio.channels.SelectionKey = sun.nio.ch.SelectionKeyImpl@1ee902cd

    scala> sc2.register(selector, java.nio.channels.SelectionKey.OP_READ)
    res58: java.nio.channels.SelectionKey = sun.nio.ch.SelectionKeyImpl@2c5506e9

    scala> sc3.register(selector, java.nio.channels.SelectionKey.OP_READ)
    res59: java.nio.channels.SelectionKey = sun.nio.ch.SelectionKeyImpl@10b0b504

Here we just registered our interest in reading from each
SocketChannel. The list of possible events that we can use during
registration is:

    scala> java.nio.channels.SelectionKey.OP_
    OP_ACCEPT    OP_CONNECT   OP_READ      OP_WRITE

If we try to select():

    scala> selector.select(1000)
    res60: Int = 0

We just tried to select() with a timeout of 1 sec. Given that there is
no read operation ready to be performed the call returned a 0 as the
number of ready channels.

If we write something from one of our Telnet shells and we try again
to select():

    scala> selector.select(1000)
    res61: Int = 1

the call returns immediately telling us that there is a channel ready
to serviced.

    scala> selector.selectedKeys
    res62: java.util.Set[java.nio.channels.SelectionKey] = [sun.nio.ch.SelectionKeyImpl@1ee902cd]

We could now pass this set to a dispatcher that will loop on the
selected keys and process the incoming requests sharing the work on
a *bounded* pool of threads.

The point of all of this is that we are now handling 3 live
connections with a single thread (the REPL one) and we are not limited
anymore by the thread-per-request model.

Framework
---------

There is a lot of code that we need to write to handle a couple of
connections. The thread-per-server model was less scalable but it was
way more easy to implement. This is where things like Netty, Grizzly
and Ning are useful.

They are nice libraries that will encapsulate all the NIO machinery
and will let us write only the final piece of code that process the
request and emits the outgoing data.

All of them implements in a way or in another a basic pattern used in
writing efficient and scalable network services: the Reactor pattern.

In a few words the Reactor pattern is a way to decouple the threads
handling connections and the threads processing the requests. An
Acceptor waits for incoming connections and passes the resulting
client socket to a Dispatcher that will register it on a Selector. As
soon something comes out from this socket the incoming data is
extracted and passed to a pool of Handlers (provided by the user) that
will perform the needed business logic to emit a correct response.

The most famous NIO framework is Netty and this is its workflow:

                                         I/O Request
                                             |
    +----------------------------------------+---------------+
    |                  ChannelPipeline       |               |
    |                                       \|/              |
    |  +----------------------+  +-----------+------------+  |
    |  | Upstream Handler  N  |  | Downstream Handler  1  |  |
    |  +----------+-----------+  +-----------+------------+  |
    |            /|\                         |               |
    |             |                         \|/              |
    |  +----------+-----------+  +-----------+------------+  |
    |  | Upstream Handler N-1 |  | Downstream Handler  2  |  |
    |  +----------+-----------+  +-----------+------------+  |
    |            /|\                         .               |
    |             .                          .               |
    |     [ sendUpstream() ]        [ sendDownstream() ]     |
    |     [ + INBOUND data ]        [ + OUTBOUND data  ]     |
    |             .                          .               |
    |             .                         \|/              |
    |  +----------+-----------+  +-----------+------------+  |
    |  | Upstream Handler  2  |  | Downstream Handler M-1 |  |
    |  +----------+-----------+  +-----------+------------+  |
    |            /|\                         |               |
    |             |                         \|/              |
    |  +----------+-----------+  +-----------+------------+  |
    |  | Upstream Handler  1  |  | Downstream Handler  M  |  |
    |  +----------+-----------+  +-----------+------------+  |
    |            /|\                         |               |
    +-------------+--------------------------+---------------+
                  |                         \|/
    +-------------+--------------------------+---------------+
    |             |                          |               |
    |     [ Socket.read() ]          [ Socket.write() ]      |
    |                                                        |
    |  Netty Internal I/O Threads (Transport Implementation) |
    +--------------------------------------------------------+

Netty works around the concept of two pipelines of Handlers: one
upstream and one downstream. The first one is used to decode and
process the incoming data while the second one is used to encode and
send the response.
