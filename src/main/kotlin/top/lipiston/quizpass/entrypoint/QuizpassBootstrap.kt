package top.lipiston.quizpass.entrypoint

import com.github.kusa233.kalmia.server.network.http.body.json.KalmiaHttpJsonBody
import com.github.kusa233.kalmia.server.network.http.builder.http
import com.github.kusa233.kalmia.server.network.http.context.KalmiaHttpContext
import com.github.kusa233.kalmia.server.network.http.entrypoint.service.KalmiaHttpService
import com.github.kusa233.kalmia.status.KalmiaStatus
import io.netty.handler.codec.http.HttpResponseStatus
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import top.lipiston.quizpass.data.type.ServerToClientRequestType
import top.lipiston.quizpass.rcon.RconConnection
import kotlin.Exception

@Suppress("UNUSED")
object QuizpassBootstrap {
    private val LOGGER: Logger = LogManager.getLogger("Quizpass")

    // TODO: move these to a config file instead of hardcoding them in source.
    private const val RCON_HOST = "127.0.0.1"

    /** Change to the RCON port if it is not the default one. */
    private const val RCON_PORT = 25575

    /** Change to the real password. */
    private const val RCON_PASSWORD = "awa"

    private const val RCON_NOT_READY_MESSAGE =
        "RCON 服务器未连接或认证未完成，请稍后重试。"

    private const val INVALID_BODY_MESSAGE = "API only accept JSON post body"

    private val rcon = RconConnection(RCON_HOST, RCON_PORT, RCON_PASSWORD) { data ->
        if (data.requestType == ServerToClientRequestType.COMMAND_RESPONSE) {
            LOGGER.info("Command response: {}", data.payload)
        }
    }

    @JvmStatic
    fun init() {
        // Start connecting without blocking: the HTTP API must come up even while the RCON
        // server is down, unreachable, or rejecting us. RconConnection keeps retrying on its own.
        rcon.start()

        // A reload re-runs this entrypoint under a fresh classloader while Kalmia's static listener
        // lists survive, so the previous instance has to be torn down explicitly. Without this every
        // reload would strand another open socket and another event-loop thread.
        KalmiaStatus.registerReloadListener { rcon.stop() }
        KalmiaStatus.registerStopListener { rcon.stop() }

        KalmiaHttpService.start(http {
            assets("assets/")

            route("/api/add-whitelist") {
                post {
                    val name = try {
                        getPlayerName(this)
                    } catch (e: Exception) {
                        abort(this, HttpResponseStatus.INTERNAL_SERVER_ERROR, INVALID_BODY_MESSAGE)
                    }

                    if (!rcon.sendCommand("/whitelist add $name")) {
                        abort(this, HttpResponseStatus.SERVICE_UNAVAILABLE, RCON_NOT_READY_MESSAGE)
                    }
                }
            }

            route("/api/remove-whitelist") {
                post {
                    val name = try {
                        getPlayerName(this)
                    } catch (e: Exception) {
                        abort(this, HttpResponseStatus.INTERNAL_SERVER_ERROR, INVALID_BODY_MESSAGE)
                    }

                    if (!rcon.sendCommand("/whitelist remove $name")) {
                        abort(this, HttpResponseStatus.SERVICE_UNAVAILABLE, RCON_NOT_READY_MESSAGE)
                    }
                }
            }
        })
    }

    /**
     * Aborts the request with [status] and [message], then throws so the call site never continues.
     *
     * `abortWith` signals failure by throwing `UnexpectedBehaviorException`, and the declared return
     * type is `Unit`, which would make a surrounding `catch (e: Exception)` swallow the abort and
     * rewrite the response. Declaring this helper as `Nothing` both tells the compiler the request
     * cannot continue and keeps the abort out of any caller's catch block. It must therefore be
     * called *outside* the parsing `try`.
     */
    private fun abort(context: KalmiaHttpContext, status: HttpResponseStatus, message: String): Nothing {
        context.abortWith(IllegalStateException(message), status, context)
        throw IllegalStateException(message)
    }

    fun getPlayerName(context: KalmiaHttpContext): String {
        val body = context.body() as KalmiaHttpJsonBody

        return body.json.getString("username")!!
    }
}
