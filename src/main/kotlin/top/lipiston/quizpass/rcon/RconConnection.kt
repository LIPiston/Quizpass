package top.lipiston.quizpass.rcon

import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioIoHandler
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.DefaultHttpHeaders
import io.netty.handler.codec.http.HttpClientCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketVersion
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketClientCompressionHandler
import io.netty.util.concurrent.ScheduledFuture
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import top.lipiston.quizpass.data.RconData
import top.lipiston.quizpass.data.type.ClientToServerRequestType
import top.lipiston.quizpass.data.type.ServerToClientRequestType
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

/**
 * A self-healing RCON client.
 *
 * `KalmiaWebSocketClient.connect()` blocks until the socket closes, which makes every statement
 * after it unreachable and leaves no room for a retry. This class drives the Netty pipeline itself
 * instead, returns immediately, and keeps the connection alive on its own:
 *
 * ```
 * DISCONNECTED --connect--> CONNECTING --HANDSHAKE_COMPLETE--> AUTHENTICATING --AUTH_RESPONSE--> READY
 *      ^                                                              |                         |
 *      +---------------- exponential backoff (1s..30s) ---------------+---- peer drop / failure -+
 * ```
 *
 * Threading: the socket is served by a single-threaded event loop owned by this instance, and every
 * state transition happens on that thread, so the state machine needs no locking. [state] is
 * `@Volatile` only because HTTP worker threads read it through [isReady].
 */
class RconConnection(
    private val host: String,
    private val port: Int,
    private val password: String,
    private val onData: (RconData) -> Unit = {},
) {

    enum class State {
        /** No socket: stopped, never started, or waiting out the backoff delay. */
        DISCONNECTED,

        /** TCP connect / WebSocket handshake in flight. */
        CONNECTING,

        /** Handshake finished, AUTH sent, waiting for the AUTH_RESPONSE. */
        AUTHENTICATING,

        /** Authenticated; commands may be sent. */
        READY,
    }

    private companion object {
        val LOGGER: Logger = LogManager.getLogger("Quizpass")

        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val BACKOFF_JITTER_MS = 500L

        /** A peer that accepts TCP but never finishes the handshake must not hang us forever. */
        const val HANDSHAKE_TIMEOUT_MS = 5_000L

        /** Nor may a peer that finishes the handshake but never answers AUTH. */
        const val AUTH_TIMEOUT_MS = 5_000L

        const val MAX_FRAME_LENGTH = 8 * 1024

        /** Caps the exponent so the backoff delay saturates at [MAX_BACKOFF_MS]. */
        const val MAX_BACKOFF_EXPONENT = 5
    }

    private val eventLoop: EventLoopGroup = MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory())
    private val requestIdSequence = AtomicInteger()
    private val uri = URI("ws://$host:$port")

    /** Bumped per connect attempt, so late events from an abandoned attempt can be ignored. */
    private val generation = AtomicInteger()

    /** Event-loop-confined: highest attempt generation already reported as failed. */
    private var handledGeneration = 0

    @Volatile
    private var channel: Channel? = null

    private var authRequestId = -1

    /** Consecutive failures; reset only after a successful authentication. */
    private var reconnectAttempts = 0

    @Volatile
    private var stopped = false

    /** Guards [start] so a repeated entrypoint call cannot open a second connect loop. */
    private val started = AtomicBoolean(false)

    private var reconnectTask: ScheduledFuture<*>? = null
    private var authTimeoutTask: ScheduledFuture<*>? = null

    @Volatile
    var state: State = State.DISCONNECTED
        private set

    /** True only between a successful AUTH and the next disconnect. */
    val isReady: Boolean
        get() = state == State.READY

    /**
     * Starts connecting. Never blocks, so the HTTP server never waits on RCON.
     *
     * Idempotent: Kalmia can invoke an entrypoint more than once across reloads, and a second call
     * must not start a competing connect loop that would double every AUTH and every command.
     * [stop] is terminal — a stopped instance cannot be restarted.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        stopped = false
        eventLoop.execute { connect() }
    }

    /**
     * Sends a command frame. Returns `false` when the connection is not authenticated yet, letting
     * the caller answer with a retryable status instead of dropping the command silently.
     */
    fun sendCommand(command: String): Boolean {
        val current = channel ?: return false
        if (!isReady || !current.isActive) return false

        val requestId = requestIdSequence.getAndIncrement()
        LOGGER.info("Sending RCON command (requestId={}): {}", requestId, command)
        current.writeAndFlush(binaryFrame(RconCodec.encode(requestId, ClientToServerRequestType.COMMAND, command)))
        return true
    }

    /** Stops reconnecting, closes the socket and releases the event loop. Idempotent. */
    fun stop() {
        if (stopped) return
        stopped = true
        state = State.DISCONNECTED
        LOGGER.info("Stopping RCON connection")
        cancelReconnect()
        cancelAuthTimeout()

        val current = channel
        channel = null
        current?.close()

        eventLoop.shutdownGracefully(0, 1, TimeUnit.SECONDS)
    }

    private fun connect() {
        if (stopped) return

        val gen = generation.incrementAndGet()
        reconnectAttempts++
        state = State.CONNECTING
        LOGGER.info("Connecting to RCON at ws://{}:{} (attempt {})", host, port, reconnectAttempts)

        val handshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri,
            WebSocketVersion.V13,
            null,
            true,
            DefaultHttpHeaders(),
        )

        Bootstrap()
            .group(eventLoop)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Same pipeline Kalmia's own client builds.
                    ch.pipeline().addLast(
                        HttpClientCodec(),
                        HttpObjectAggregator(MAX_FRAME_LENGTH),
                        WebSocketClientCompressionHandler(MAX_FRAME_LENGTH),
                        WebSocketClientProtocolHandler(handshaker, HANDSHAKE_TIMEOUT_MS),
                        RconHandler(gen),
                    )
                }
            })
            .connect(host, port)
            .addListener(ChannelFutureListener { future ->
                // A refused or otherwise failed TCP connect fires neither channelActive nor
                // channelInactive, so it has to be handled here.
                if (!future.isSuccess) {
                    val cause = future.cause()
                    onFailure(
                        gen,
                        "connect failed: ${cause?.message ?: cause?.javaClass?.simpleName ?: "unknown"}",
                    )
                }
            })
    }

    /**
     * The single exit point for every way an attempt can end. Guarded so that the several events one
     * attempt may fire (a handshake timeout *and* channelInactive, say) schedule exactly one retry,
     * and so that a late event from an abandoned attempt cannot touch the current connection.
     */
    private fun onFailure(gen: Int, reason: String) {
        if (stopped) return
        if (gen != generation.get()) return
        if (handledGeneration >= gen) return
        handledGeneration = gen

        LOGGER.warn("RCON connection lost: {}", reason)
        state = State.DISCONNECTED
        authRequestId = -1
        cancelAuthTimeout()

        val current = channel
        channel = null
        current?.close()

        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (stopped) return
        cancelReconnect()

        val exponent = (reconnectAttempts - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        val delay = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl exponent) + Random.nextLong(BACKOFF_JITTER_MS)

        LOGGER.info("Retrying RCON connection in {} ms", delay)
        reconnectTask = eventLoop.schedule(
            {
                reconnectTask = null
                connect()
            },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelReconnect() {
        reconnectTask?.cancel(false)
        reconnectTask = null
    }

    private fun cancelAuthTimeout() {
        authTimeoutTask?.cancel(false)
        authTimeoutTask = null
    }

    private fun binaryFrame(payload: ByteArray) = BinaryWebSocketFrame(Unpooled.wrappedBuffer(payload))

    private fun markReady() {
        authRequestId = -1
        cancelAuthTimeout()
        reconnectAttempts = 0
        state = State.READY
        LOGGER.info("RCON ready: authenticated successfully")
    }

    private inner class RconHandler(private val gen: Int) : SimpleChannelInboundHandler<WebSocketFrame>() {

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            when (evt) {
                WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE ->
                    onHandshakeComplete(ctx)

                // A peer that accepts TCP and then stays silent lands here.
                WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_TIMEOUT -> {
                    onFailure(gen, "WebSocket handshake timed out after ${HANDSHAKE_TIMEOUT_MS}ms")
                    ctx.close()
                }

                else -> super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelActive(ctx: ChannelHandlerContext) {
            channel = ctx.channel()
            super.channelActive(ctx)
        }

        override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
            if (frame !is BinaryWebSocketFrame) {
                LOGGER.debug("Ignoring non-binary RCON frame: {}", frame.javaClass.simpleName)
                return
            }

            // Never assume a pooled or direct backing array: copy just the readable region.
            val content = frame.content()
            val data = ByteArray(content.readableBytes())
            content.getBytes(content.readerIndex(), data)

            val parsed = RconCodec.decode(data)
            if (parsed == null) {
                LOGGER.warn("Ignoring malformed RCON frame ({} bytes)", data.size)
                return
            }

            when (parsed.requestType) {
                ServerToClientRequestType.AUTH_RESPONSE -> {
                    if (parsed.requestId == authRequestId) {
                        markReady()
                    } else {
                        LOGGER.debug(
                            "Ignoring AUTH_RESPONSE with unexpected requestId {} (expected {})",
                            parsed.requestId,
                            authRequestId,
                        )
                    }
                }

                ServerToClientRequestType.FAILURE -> {
                    LOGGER.warn("RCON authentication rejected by the server (wrong password?)")
                    onFailure(gen, "authentication rejected")
                }

                ServerToClientRequestType.COMMAND_RESPONSE -> Unit
            }

            onData(parsed)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            onFailure(gen, "socket closed by peer")
            super.channelInactive(ctx)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            // Also covers a peer that answers the handshake with something that is not WebSocket.
            LOGGER.warn("RCON channel error: {}", cause.toString())
            ctx.close()
        }

        private fun onHandshakeComplete(ctx: ChannelHandlerContext) {
            if (stopped) return

            state = State.AUTHENTICATING
            val requestId = requestIdSequence.getAndIncrement()
            authRequestId = requestId

            LOGGER.info("RCON handshake complete, authenticating (requestId={})", requestId)
            ctx.writeAndFlush(
                binaryFrame(RconCodec.encode(requestId, ClientToServerRequestType.AUTH, password + "\u0000"))
            )

            cancelAuthTimeout()
            // Bound to this attempt's generation, so a stale timer cannot fail a newer connection.
            authTimeoutTask = eventLoop.schedule(
                { onFailure(gen, "no AUTH_RESPONSE within ${AUTH_TIMEOUT_MS}ms") },
                AUTH_TIMEOUT_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }
}
