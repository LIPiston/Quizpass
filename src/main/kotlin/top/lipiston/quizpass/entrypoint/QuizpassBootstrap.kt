package top.lipiston.quizpass.entrypoint

import com.github.kusa233.kalmia.server.network.http.body.json.KalmiaHttpJsonBody
import com.github.kusa233.kalmia.server.network.http.builder.http
import com.github.kusa233.kalmia.server.network.http.context.KalmiaHttpContext
import com.github.kusa233.kalmia.server.network.http.entrypoint.service.KalmiaHttpService
import com.github.kusa233.kalmia.status.KalmiaStatus
import io.netty.handler.codec.http.HttpResponseStatus
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import top.lipiston.quizpass.assets.AssetsDownloader
import top.lipiston.quizpass.config.QuizpassConfig
import top.lipiston.quizpass.data.type.ServerToClientRequestType
import top.lipiston.quizpass.notify.Notification
import top.lipiston.quizpass.notify.Notifier
import top.lipiston.quizpass.rcon.RconConnection
import kotlin.Exception

@Suppress("UNUSED")
object QuizpassBootstrap {
    private val LOGGER: Logger = LogManager.getLogger("Quizpass")

    private const val RCON_NOT_READY_MESSAGE =
        "RCON 服务器未连接或认证未完成，请稍后重试。"

    private const val INVALID_BODY_MESSAGE = "API only accept JSON post body"

    /** Mojang-compatible player names: the same shape the panel checks client-side. */
    private val PLAYER_NAME_PATTERN = Regex("^[A-Za-z0-9_]{3,16}$")

    /** QQ numbers are 5 to 11 digits and never start with a zero. */
    private val QQ_PATTERN = Regex("^[1-9][0-9]{4,10}$")

    private const val NAME_INVALID_MESSAGE =
        "游戏内名称格式无效：请使用 3-16 个字母、数字或下划线。"

    private const val ACCOUNT_INVALID_MESSAGE =
        "游戏方式无效：请选择正版或皮肤站。"

    private const val QQ_INVALID_MESSAGE = "QQ 号格式无效：请输入 5-11 位数字。"

    /** The three fields the player-facing form submits. */
    private data class Signup(val player: String, val account: String, val qq: String)

    @JvmStatic
    fun init() {
        val config = QuizpassConfig.load()

        // Refresh the web panel before the HTTP server starts serving it, so a request can never
        // observe a half-updated file. The download is bounded and fail-soft: the file already on
        // disk is kept whenever the remote cannot be reached.
        if (config.assetsDownload) {
            AssetsDownloader.download(config.assetsUrl, config.assetsPath)
        }

        val rcon = RconConnection(config.rconHost, config.rconPort, config.rconPassword) { data ->
            if (data.requestType == ServerToClientRequestType.COMMAND_RESPONSE) {
                LOGGER.info("Command response: {}", data.payload)
            }
        }

        // Start connecting without blocking: the HTTP API must come up even while the RCON
        // server is down, unreachable, or rejecting us. RconConnection keeps retrying on its own.
        rcon.start()

        // A reload re-runs this entrypoint under a fresh classloader while Kalmia's static listener
        // lists survive, so the previous instance has to be torn down explicitly. Without this every
        // reload would strand another open socket, another event-loop thread and another notifier
        // thread.
        KalmiaStatus.registerReloadListener {
            rcon.stop()
            Notifier.shutdown()
        }
        KalmiaStatus.registerStopListener {
            rcon.stop()
            Notifier.shutdown()
        }

        KalmiaHttpService.start(http {
            assets("assets/")

            route("/api/add-whitelist") {
                post {
                    handleSignup(this, rcon, config)
                }
            }

            route("/api/remove-whitelist") {
                post {
                    handleWhitelistChange(this, rcon, config, Notification.EVENT_REMOVE) { "/whitelist remove $it" }
                }
            }
        })
    }

    /**
     * Handles the player-facing signup form: validates the three fields, adds the player to the
     * server whitelist over RCON and — when configured — tells the administrators about it.
     *
     * Validation failures are reported as `400` with a specific Chinese reason rather than a
     * generic failure, because the form is filled in by players who cannot read the server log.
     */
    private fun handleSignup(
        context: KalmiaHttpContext,
        rcon: RconConnection,
        config: QuizpassConfig,
    ) {
        val signup = try {
            parseSignup(context)
        } catch (e: Exception) {
            abort(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, INVALID_BODY_MESSAGE)
        }

        if (!PLAYER_NAME_PATTERN.matches(signup.player)) {
            abort(context, HttpResponseStatus.BAD_REQUEST, NAME_INVALID_MESSAGE)
        }
        if (signup.account !in Notification.ACCOUNT_VALUES) {
            abort(context, HttpResponseStatus.BAD_REQUEST, ACCOUNT_INVALID_MESSAGE)
        }
        if (!QQ_PATTERN.matches(signup.qq)) {
            abort(context, HttpResponseStatus.BAD_REQUEST, QQ_INVALID_MESSAGE)
        }

        val rconCommand = "/whitelist add ${signup.player}"

        if (!rcon.sendCommand(rconCommand)) {
            abort(context, HttpResponseStatus.SERVICE_UNAVAILABLE, RCON_NOT_READY_MESSAGE)
        }

        broadcastWhitelistChange(config, rcon, Notification.EVENT_ADD, signup.player)

        notifyAdministrators(
            context = context,
            config = config,
            event = Notification.EVENT_ADD,
            name = signup.player,
            rconCommand = rconCommand,
            account = signup.account,
            qq = signup.qq,
        )
    }

    /**
     * Reads the signup form fields.
     *
     * Every field is optional at this layer and validated by the caller, so a missing or wrongly
     * typed field is reported as a `400` naming the field instead of an NPE turning into a generic
     * `500`. Only a body that is not JSON at all throws out of here — that is still the documented
     * `500`.
     */
    private fun parseSignup(context: KalmiaHttpContext): Signup {
        val json = (context.body() as KalmiaHttpJsonBody).json

        var player = ""
        var account = ""
        var qq = ""

        json.ifString("username") { player = this.trim() }
        json.ifString("account") { account = this.trim() }
        json.ifString("qq") { qq = this.trim() }

        return Signup(player, account, qq)
    }

    /**
     * Parses the player name, forwards the RCON command and — when configured — tells the
     * administrators about it.
     *
     * The notification is handed to [Notifier], which delivers on a background thread: a slow or
     * unreachable relay must never delay the player's response or turn a successful whitelist
     * change into an error.
     */
    private fun handleWhitelistChange(
        context: KalmiaHttpContext,
        rcon: RconConnection,
        config: QuizpassConfig,
        event: String,
        command: (String) -> String,
    ) {
        val name = try {
            getPlayerName(context)
        } catch (e: Exception) {
            abort(context, HttpResponseStatus.INTERNAL_SERVER_ERROR, INVALID_BODY_MESSAGE)
        }

        val rconCommand = command(name)

        if (!rcon.sendCommand(rconCommand)) {
            abort(context, HttpResponseStatus.SERVICE_UNAVAILABLE, RCON_NOT_READY_MESSAGE)
        }

        broadcastWhitelistChange(config, rcon, event, name)

        notifyAdministrators(context, config, event, name, rconCommand)
    }

    /**
     * Prints a `/say` line for the whitelist change, so the event shows up in the Minecraft
     * server console and to every online player.
     *
     * Deliberately best-effort and after the whitelist command: the whitelist change has already
     * been handed to the server, so a broadcast that cannot be sent — the connection dropped in
     * between, or the template is blank — must not turn the player's successful request into an
     * error. `/say` is used rather than `/tellraw` because only `/say` writes to the server
     * console log; the trade-off is that `/say` carries no colour.
     */
    private fun broadcastWhitelistChange(
        config: QuizpassConfig,
        rcon: RconConnection,
        event: String,
        name: String,
    ) {
        val message = config.broadcastMessage(event, name) ?: return

        val command = "/say $message"
        if (!rcon.sendCommand(command)) {
            LOGGER.warn("Skipped in-game broadcast for {}: RCON is not ready", name)
        }
    }

    private fun notifyAdministrators(
        context: KalmiaHttpContext,
        config: QuizpassConfig,
        event: String,
        name: String,
        rconCommand: String,
        account: String = "",
        qq: String = "",
    ) {
        if (!config.notifyEnabled) {
            return
        }
        if (event == Notification.EVENT_ADD && !config.notifyOnAdd) {
            return
        }
        if (event == Notification.EVENT_REMOVE && !config.notifyOnRemove) {
            return
        }
        if (!config.anyChannelEnabled) {
            return
        }

        Notifier.submit(
            config,
            Notification.of(
                event = event,
                player = name,
                command = rconCommand,
                host = resolveHost(context),
                clientAddress = resolveClientAddress(context),
                account = account,
                qq = qq,
            )
        )
    }

    /**
     * The host the player addressed, for the notification text.
     *
     * `context.host()` is the address the server is *bound* to, which is usually the wildcard
     * `0.0.0.0` and therefore useless in a notification; the request's `Host` header is the real
     * name the player used. Falls back to the bound host if the header is absent.
     */
    private fun resolveHost(context: KalmiaHttpContext): String {
        val hostHeader = runCatching { context.getHeader("Host") }.getOrNull()
            ?.takeIf { it.isNotBlank() }

        return hostHeader?.trim() ?: runCatching { context.host() }.getOrDefault("")
    }

    /**
     * Best-effort client address.
     *
     * Kalmia exposes the request headers but not the remote socket address, so the real peer address
     * is only knowable when the panel runs behind a reverse proxy that sets `X-Forwarded-For` or
     * `X-Real-IP`. Otherwise the notification says `unknown` rather than reporting a wrong value.
     */
    private fun resolveClientAddress(context: KalmiaHttpContext): String {
        val forwarded = runCatching { context.getHeader("X-Forwarded-For") }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.substringBefore(',')

        if (forwarded != null) {
            return forwarded.trim()
        }

        val realIp = runCatching { context.getHeader("X-Real-IP") }.getOrNull()
            ?.takeIf { it.isNotBlank() }

        return realIp?.trim() ?: Notification.UNKNOWN_ADDRESS
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
