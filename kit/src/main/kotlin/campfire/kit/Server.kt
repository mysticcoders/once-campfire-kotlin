package campfire.kit

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBufUtil
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.IoHandlerFactory
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.epoll.Epoll
import io.netty.channel.epoll.EpollIoHandler
import io.netty.channel.epoll.EpollServerSocketChannel
import io.netty.channel.kqueue.KQueue
import io.netty.channel.kqueue.KQueueIoHandler
import io.netty.channel.kqueue.KQueueServerSocketChannel
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.ServerSocketChannel
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpHeadersFactory
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpHeaders
import io.netty.handler.codec.http.HttpMessage
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpUtil
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.util.concurrent.ScheduledFuture
import java.net.InetSocketAddress
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * How one listener serves: where, with how many worker threads, whether it adds `Date`, and the
 * largest request body it reads. The timeouts (0 for none) are Go's `http.Server` deadlines as
 * `crates/kit/src/front/conn.rs` applies them:
 *
 * - [readTimeoutMillis] bounds reading a request, and also the wait for the next one on a
 *   keep-alive connection (hyper's header timer, which the Rust port lets double as an idle timer);
 * - [writeTimeoutMillis] bounds the rest of the exchange, from the request's head to the response's
 *   end; past it the connection closes without a response;
 * - [idleTimeoutMillis] closes a keep-alive connection with no request in flight.
 *
 * With [oversizedToHandler], a request whose body is over [maxRequestBody] reaches the handler with
 * an empty body and the [RequestBodyTooLarge] marker (so the front server answers it as Thruster
 * does) instead of Netty's own 413, and the connection closes after the response.
 */
class ServerOptions(
    val port: Int,
    val bind: String? = null,
    val workers: Int = Runtime.getRuntime().availableProcessors(),
    val date: Boolean = true,
    val maxRequestBody: Int = 1 shl 20,
    val readTimeoutMillis: Long = 0,
    val writeTimeoutMillis: Long = 0,
    val idleTimeoutMillis: Long = 0,
    val oversizedToHandler: Boolean = false,
)

/**
 * A response extension: send the body with chunked transfer coding and no `Content-Length`, as
 * hyper sends a streamed body (a gzipped one from `Rack::Deflater`, or the front server's
 * compression past Go's 2 KB buffer). The body goes out as one chunk. Ignored for statuses without
 * a body; a HEAD response gets neither header, and an HTTP/1.0 one is delimited by closing the
 * connection, as hyper does.
 */
data object ChunkedBody

/** A request extension: the body was over [ServerOptions.maxRequestBody] and wasn't read (see [ServerOptions.oversizedToHandler]). */
data object RequestBodyTooLarge

/**
 * HTTP/1.1 over Netty: requests are read whole and handed to [handler] on a worker pool, keeping
 * the event loops for I/O, and bodies go out without copying (a page's parts as one composite
 * buffer).
 */
class HttpServer(private val options: ServerOptions, private val handler: Handler) {
    private val workers: ExecutorService = Executors.newFixedThreadPool(options.workers)
    private var group: MultiThreadIoEventLoopGroup? = null
    private var channel: Channel? = null

    /** Binds the listener and returns once it accepts connections. */
    fun start(): HttpServer = apply {
        val (io, channelClass) = transport()
        val group = MultiThreadIoEventLoopGroup(io).also { group = it }
        val bootstrap = ServerBootstrap()
            .group(group)
            .channel(channelClass)
            .option(ChannelOption.SO_BACKLOG, 1024)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    val deadlines = Deadlines()
                    ch.pipeline().addLast(HttpServerCodec(), deadlines, Aggregator(deadlines), Adapter(deadlines))
                }
            })
        val address = if (options.bind == null) InetSocketAddress(options.port) else InetSocketAddress(options.bind, options.port)
        channel = bootstrap.bind(address).sync().channel()
    }

    /** The port the listener is bound to (the chosen one when [ServerOptions.port] is 0). */
    val port: Int get() = (channel?.localAddress() as? InetSocketAddress)?.port ?: options.port

    /** The transport's name, for the startup line. */
    val transportName: String get() = transport().second.simpleName

    /** Blocks until the listener closes. */
    fun await() {
        channel?.closeFuture()?.sync()
    }

    /** Closes the listener and its connections, and stops the worker pool. */
    fun stop() {
        channel?.close()?.syncUninterruptibly()
        group?.shutdownGracefully(0, 1, TimeUnit.SECONDS)?.syncUninterruptibly()
        workers.shutdownNow()
    }

    /**
     * The connection's read, write and idle deadlines (see [ServerOptions]), and its queue of
     * exchanges. Pipelined requests are answered one at a time in the order they arrived, as hyper
     * and Go's `net/http` serve a connection. Its state is only touched on the channel's event
     * loop: requests arriving here, and responses finishing in [write]'s listener.
     */
    private inner class Deadlines : ChannelInboundHandlerAdapter() {
        private var ctx: ChannelHandlerContext? = null
        private var read: ScheduledFuture<*>? = null
        private var idle: ScheduledFuture<*>? = null
        private val writes = ArrayDeque<ScheduledFuture<*>?>()
        private var inFlight = 0
        private val exchanges = ArrayDeque<Runnable>()
        private var serving = false

        override fun handlerAdded(ctx: ChannelHandlerContext) {
            this.ctx = ctx
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            waiting()
            ctx.fireChannelActive()
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            read?.cancel(false)
            idle?.cancel(false)
            for (write in writes) write?.cancel(false)
            writes.clear()
            exchanges.clear()
            ctx.fireChannelInactive()
        }

        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                idle?.cancel(false)
                read?.cancel(false)
                read = schedule(options.readTimeoutMillis)
                writes.addLast(schedule(options.writeTimeoutMillis))
                inFlight++
            }
            if (msg is LastHttpContent) {
                read?.cancel(false)
                read = null
            }
            ctx.fireChannelRead(msg)
        }

        /** The write deadline of the oldest request not yet answered, now being answered. */
        fun dispatched(): ScheduledFuture<*>? = writes.removeFirstOrNull()

        /** Runs [exchange] now if the connection is idle, or after the exchanges queued before it. */
        fun serve(exchange: Runnable) {
            exchanges.addLast(exchange)
            if (!serving) next()
        }

        /**
         * A response finished (or failed): cancels its deadline, then serves the next queued
         * request, or with nothing in flight starts waiting. Once the connection is closing (not
         * [open]: a `Connection: close` response, or a failed write) the queued requests are
         * dropped unanswered, as hyper does.
         */
        fun answered(write: ScheduledFuture<*>?, open: Boolean) {
            write?.cancel(false)
            inFlight--
            if (!open) exchanges.clear()
            next()
            if (inFlight <= 0) {
                inFlight = 0
                waiting()
            }
        }

        private fun next() {
            val exchange = exchanges.removeFirstOrNull()
            serving = exchange != null
            exchange?.run()
        }

        /** Waiting for the next request: the read timer (hyper's header timer) and the idle timer run. */
        private fun waiting() {
            read?.cancel(false)
            read = schedule(options.readTimeoutMillis)
            idle = schedule(options.idleTimeoutMillis)
        }

        private fun schedule(millis: Long): ScheduledFuture<*>? {
            val ctx = ctx ?: return null
            if (millis <= 0) return null
            return ctx.executor().schedule({ ctx.close() }, millis, TimeUnit.MILLISECONDS)
        }
    }

    /** Netty's aggregator, handing oversized requests to the handler when [ServerOptions.oversizedToHandler] says so. */
    private inner class Aggregator(private val deadlines: Deadlines) : HttpObjectAggregator(options.maxRequestBody) {
        override fun handleOversizedMessage(ctx: ChannelHandlerContext, oversized: HttpMessage) {
            if (!options.oversizedToHandler || oversized !is HttpRequest) return super.handleOversizedMessage(ctx, oversized)
            val request = toRequest(ctx, oversized, Request.EMPTY)
            request.putExtension(RequestBodyTooLarge)
            dispatch(ctx, deadlines, request, keepAlive = false, head = oversized.method() == HttpMethod.HEAD)
        }
    }

    private inner class Adapter(private val deadlines: Deadlines) : SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val content = msg.content()
            val body = if (content.isReadable) ByteBufUtil.getBytes(content) else Request.EMPTY
            dispatch(ctx, deadlines, toRequest(ctx, msg, body), HttpUtil.isKeepAlive(msg), msg.method() == HttpMethod.HEAD)
        }
    }

    /**
     * Queues [request] on its connection, then runs it on a worker and writes its response once the
     * requests before it are answered, keeping the connection's deadlines.
     */
    private fun dispatch(ctx: ChannelHandlerContext, deadlines: Deadlines, request: Request, keepAlive: Boolean, head: Boolean) {
        val write = deadlines.dispatched()
        deadlines.serve {
            workers.execute {
                val response = try {
                    handler.handle(request)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Response.text(500, "Internal Server Error")
                }
                write(ctx, response, keepAlive, head, request.version == "HTTP/1.0")
                    .addListener { deadlines.answered(write, open = it.isSuccess && ctx.channel().isOpen) }
            }
        }
    }

    private fun toRequest(ctx: ChannelHandlerContext, msg: HttpRequest, body: ByteArray): Request {
        val headers = Headers()
        for (entry in msg.headers()) headers.add(entry.key, entry.value)
        val version = if (msg.protocolVersion() == HttpVersion.HTTP_1_0) "HTTP/1.0" else "HTTP/1.1"
        return Request(msg.method().name(), msg.uri(), headers, body, ctx.channel().remoteAddress() as? InetSocketAddress, version)
    }

    /**
     * Writes [response]: with a `Content-Length` from the body unless it has one (none for 204,
     * 304, or an empty HEAD response, as hyper does), or chunked when it's marked [ChunkedBody];
     * `Date` when the options say so and the response has none.
     */
    private fun write(ctx: ChannelHandlerContext, response: Response, keepAlive0: Boolean, head: Boolean, http10: Boolean): ChannelFuture {
        var keepAlive = keepAlive0
        val status = HttpResponseStatus.valueOf(response.status)
        val bodiless = response.status in 100..199 || response.status == 204 || response.status == 304
        val chunks = if (head) emptyList() else response.body.chunks()
        val content = if (chunks.isEmpty()) Unpooled.EMPTY_BUFFER else Unpooled.wrappedBuffer(*chunks.toTypedArray())
        if (!bodiless && response.extension<ChunkedBody>() != null) {
            val out = DefaultHttpResponse(HttpVersion.HTTP_1_1, status, HEADERS)
            val headers = out.headers()
            copyHeaders(response, headers)
            headers.remove(HttpHeaderNames.CONTENT_LENGTH)
            if (http10) keepAlive = false
            if (!head && !http10) headers.set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
            if (!keepAlive) headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ctx.write(out)
            if (content.isReadable) ctx.write(DefaultHttpContent(content))
            return finish(ctx, ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT), keepAlive)
        }
        val out = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content, HEADERS, TRAILERS)
        val headers = out.headers()
        copyHeaders(response, headers)
        val length = response.body.length
        if (!bodiless && !(head && length == 0L) && !headers.contains(HttpHeaderNames.CONTENT_LENGTH) && !headers.contains(HttpHeaderNames.TRANSFER_ENCODING)) {
            headers.set(HttpHeaderNames.CONTENT_LENGTH, length)
        }
        if (!keepAlive) headers.set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        return finish(ctx, ctx.writeAndFlush(out), keepAlive)
    }

    /**
     * The response's headers in order, then `Date`. A zero `Content-Length` goes last, after
     * `Date`, as the Rust port's responses carry it (hyper writes that one itself, after the rest);
     * a non-zero one stays where it was inserted.
     */
    private fun copyHeaders(response: Response, headers: HttpHeaders) {
        var zeroLength = false
        for ((name, value) in response.headers) {
            if (name == "content-length" && value == "0") zeroLength = true else headers.add(name, value)
        }
        if (options.date && !headers.contains(HttpHeaderNames.DATE)) headers.add(HttpHeaderNames.DATE, HttpDate.now())
        if (zeroLength) headers.add(HttpHeaderNames.CONTENT_LENGTH, "0")
    }

    private fun finish(ctx: ChannelHandlerContext, future: ChannelFuture, keepAlive: Boolean): ChannelFuture {
        if (!keepAlive) future.addListener { ctx.close() }
        return future
    }

    companion object {
        private val HEADERS = DefaultHttpHeadersFactory.headersFactory().withValidation(false)
        private val TRAILERS = DefaultHttpHeadersFactory.trailersFactory().withValidation(false)

        private fun transport(): Pair<IoHandlerFactory, Class<out ServerSocketChannel>> = when {
            Epoll.isAvailable() -> EpollIoHandler.newFactory() to EpollServerSocketChannel::class.java
            KQueue.isAvailable() -> KQueueIoHandler.newFactory() to KQueueServerSocketChannel::class.java
            else -> NioIoHandler.newFactory() to NioServerSocketChannel::class.java
        }
    }
}

/** HTTP dates (`Wed, 07 Oct 2026 18:02:29 GMT`), with the current second's formatted once. */
object HttpDate {
    private val FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)

    @Volatile private var cached: Pair<Long, String> = 0L to ""

    fun format(instant: Instant): String = FORMAT.format(ZonedDateTime.ofInstant(instant, ZoneOffset.UTC))

    fun now(): String {
        val second = System.currentTimeMillis() / 1000
        val current = cached
        if (current.first == second) return current.second
        val value = format(Instant.ofEpochSecond(second))
        cached = second to value
        return value
    }
}
