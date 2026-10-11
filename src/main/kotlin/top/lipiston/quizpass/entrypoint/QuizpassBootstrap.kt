package top.lipiston.quizpass.entrypoint

import com.github.kusa233.kalmia.server.network.http.body.json.KalmiaHttpJsonBody
import com.github.kusa233.kalmia.server.network.http.builder.http
import com.github.kusa233.kalmia.server.network.http.context.KalmiaHttpContext
import com.github.kusa233.kalmia.server.network.http.entrypoint.service.KalmiaHttpService
import com.github.kusa233.kalmia.server.network.websocket.client.KalmiaWebSocketClient
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import top.lipiston.quizpass.data.RconData
import top.lipiston.quizpass.data.type.ClientToServerRequestType
import top.lipiston.quizpass.data.type.ServerToClientRequestType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
import kotlin.Exception
import kotlin.IllegalStateException
import kotlin.Int
import kotlin.String
import kotlin.Suppress


@Suppress("UNUSED")
object QuizpassBootstrap {
    private val LOGGER: Logger = LogManager.getLogger("Quizpass")
    private var authRequestId = -1
    private val counter: AtomicInteger = AtomicInteger()

    @JvmStatic
    fun init() {
        val rcon = KalmiaWebSocketClient()
        rcon.registerHandler(
            BinaryWebSocketFrame::class
        ) {
            val data = deserializeData(it.content().array())

            if (data.requestType == ServerToClientRequestType.AUTH_RESPONSE && data.requestId == authRequestId) {
                LOGGER.info("Auth successful")
            } else if (data.requestType == ServerToClientRequestType.FAILURE) {
                LOGGER.warn("Auth failed, retry in 5s seconds")
                Thread.sleep(5000)
            } else if (data.requestType == ServerToClientRequestType.COMMAND_RESPONSE) {
                LOGGER.info("Command response: ${data.payload}")
            }
        }
        rcon.connect(
            "127.0.0.1",
            // Change to RCON port if not default port.
            25575
        )
        this.authRequestId = this.counter.getAndIncrement()
        auth(
            this.authRequestId,
            rcon,
            // Change to real password.
            "awa"
        )
        KalmiaHttpService.start(http {
            assets("assets/")

            route("/api/add-whitelist") {
                post {
                    try {
                        val name = getPlayerName(this)
                        rcon.sendMessage(
                            createRequest(
                                counter.getAndIncrement(),
                                ClientToServerRequestType.COMMAND,
                                "/whitelist add $name"
                            )
                        )
                    } catch (e: Exception) {
                        abortWith(
                            IllegalStateException("API only accept JSON post body", e),
                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            this
                        )
                    }
                }
            }

            route("/api/remove-whitelist") {
                post {
                    try {
                        val name = getPlayerName(this)
                        rcon.sendMessage(
                            createRequest(
                                counter.getAndIncrement(),
                                ClientToServerRequestType.COMMAND,
                                "/whitelist remove $name"
                            )
                        )
                    } catch (e: Exception) {
                        abortWith(
                            IllegalStateException("API only accept JSON post body", e),
                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            this
                        )
                    }
                }
            }
        })
    }

    private fun auth(requestId: Int, client: KalmiaWebSocketClient, password: String) {
        client.sendMessage(
            createRequest(requestId, ClientToServerRequestType.AUTH, password + "\u0000")
        )
    }

    private fun deserializeData(data: ByteArray): RconData {
        val byteBuffer = ByteBuffer.wrap(data)

        val responseLength = byteBuffer.getInt() - (2 * 4)
        val requestId = byteBuffer.getInt()
        val requestType = byteBuffer.getInt()

        val payloadStart = byteBuffer.arrayOffset()

        val payload = String(
            byteBuffer.array().copyOfRange(
                payloadStart,
                payloadStart + responseLength
            )
        )

        // Skip padding.
        byteBuffer.get()
        byteBuffer.get()

        return RconData(
            requestId,
            ServerToClientRequestType.of(requestType),
            payload
        )
    }

    private fun createRequest(requestId: Int, requestType: ClientToServerRequestType, command: String): ByteArray {
        val byteBuffer: ByteBuffer = ByteBuffer.allocate((3 * 4) + command.toByteArray().size + 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)

        byteBuffer.putInt((2 * 4) + command.toByteArray().size + 2)
        byteBuffer.putInt(requestId)
        byteBuffer.putInt(requestType.type)
        byteBuffer.put(command.toByteArray())
        byteBuffer.put(0.toByte())
        byteBuffer.put(0.toByte())

        byteBuffer.position(0)

        return byteBuffer.array().copyOfRange(
            byteBuffer.arrayOffset(),
            byteBuffer.arrayOffset() + byteBuffer.limit()
        )
    }

    fun getPlayerName(context: KalmiaHttpContext): String {
        val body = context.body() as KalmiaHttpJsonBody

        return body.json.getString("username")!!
    }
}