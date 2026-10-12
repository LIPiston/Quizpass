package top.lipiston.quizpass.notify

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import top.lipiston.quizpass.config.QuizpassConfig
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Fans a [Notification] out to the enabled channels: mail, an HTTP callback and a QQ bot.
 *
 * Two properties matter more than the channels themselves:
 *
 *  - *Optional.* Nothing is sent unless `notify.enabled` is true and the individual channel is
 *    enabled too. A stock install stays completely silent.
 *  - *Off the request path.* Delivery runs on a daemon thread, so a slow or unreachable relay can
 *    never delay or fail the player's HTTP request. Each channel also fails on its own: a mail
 *    server being down must not stop the callback from firing.
 */
object Notifier {

    private val LOGGER: Logger = LogManager.getLogger("Quizpass")

    private val EXECUTOR_LOCK = Any()

    /**
     * Created on demand and re-created after [shutdown], because Kalmia may reload this plugin
     * under the same classloader: a permanently dead executor would silently stop notifying after
     * the first `reload`.
     */
    private var executor: ExecutorService? = null

    private val HTTP: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * Queues [notification] for delivery and returns immediately. Called after the RCON command
     * has been handed to the server; never throws into the request path.
     */
    fun submit(config: QuizpassConfig, notification: Notification) {
        val task = Runnable {
            if (config.emailEnabled) {
                sendEmail(config, notification)
            }
            if (config.callbackEnabled) {
                sendCallback(config, notification)
            }
            if (config.qqbotEnabled) {
                sendQqBot(config, notification)
            }
        }

        try {
            executor().execute(task)
        } catch (e: RejectedExecutionException) {
            LOGGER.warn("Notification for {} dropped, notifier is shut down: {}", notification.player, e.toString())
        }
    }

    private fun executor(): ExecutorService = synchronized(EXECUTOR_LOCK) {
        val current = executor

        if (current != null && !current.isShutdown) {
            current
        } else {
            Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "Quizpass-Notifier").apply { isDaemon = true }
            }.also { executor = it }
        }
    }

    /**
     * Called from Kalmia's reload/stop listeners.
     *
     * A reload builds this object again under a new classloader, so without an explicit shutdown
     * every reload would strand another idle notifier thread.
     */
    fun shutdown() {
        synchronized(EXECUTOR_LOCK) {
            executor?.shutdownNow()
            executor = null
        }
    }

    private fun sendEmail(config: QuizpassConfig, notification: Notification) {
        if (config.emailTo.isEmpty()) {
            LOGGER.warn("Email notification skipped: notify.email.to is empty")
            return
        }

        val from = config.emailFrom.ifBlank { config.emailUsername }
        if (from.isBlank()) {
            LOGGER.warn("Email notification skipped: set notify.email.from or notify.email.username")
            return
        }

        val settings = SmtpSettings(
            host = config.emailHost,
            port = config.emailPort,
            ssl = config.emailSsl,
            startTls = config.emailStartTls,
            trustAll = config.emailTrustAll,
            username = config.emailUsername,
            password = config.emailPassword,
            from = from,
            recipients = config.emailTo,
            timeoutMillis = config.notifyTimeoutMillis,
        )

        val subject = config.emailSubject
            .replace("{player}", notification.player)
            .replace("{event}", notification.event)

        attempt("email", notification) {
            SmtpMailer.send(settings, subject, notification.toText())
        }
    }

    private fun sendCallback(config: QuizpassConfig, notification: Notification) {
        val request = HttpRequest.newBuilder(URI.create(config.callbackUrl))
            .timeout(Duration.ofMillis(config.notifyTimeoutMillis.toLong()))
            .header("Content-Type", "application/json; charset=UTF-8")
            .apply { if (config.callbackToken.isNotBlank()) header("Authorization", "Bearer ${config.callbackToken}") }
            .POST(HttpRequest.BodyPublishers.ofString(notification.toJson(), Charsets.UTF_8))
            .build()

        attempt("callback", notification) {
            val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) {
                "callback returned HTTP ${response.statusCode()}"
            }
        }
    }

    /**
     * Speaks the OneBot v11 HTTP API (`/send_group_msg`, `/send_private_msg`), the interface
     * exposed by the common Chinese QQ bot backends. A group id wins over a user id when both are
     * configured.
     */
    private fun sendQqBot(config: QuizpassConfig, notification: Notification) {
        val base = config.qqbotUrl.trimEnd('/')

        val (endpoint, body) = when {
            config.qqbotGroupId.isNotBlank() -> {
                val group = config.qqbotGroupId.toLongOrNull()
                if (group == null) {
                    LOGGER.warn("QQ bot notification skipped: notify.qqbot.groupId '{}' is not a number", config.qqbotGroupId)
                    return
                }
                "/send_group_msg" to "{\"group_id\":$group,\"message\":\"${jsonEscape(notification.toText())}\"}"
            }

            config.qqbotUserId.isNotBlank() -> {
                val user = config.qqbotUserId.toLongOrNull()
                if (user == null) {
                    LOGGER.warn("QQ bot notification skipped: notify.qqbot.userId '{}' is not a number", config.qqbotUserId)
                    return
                }
                "/send_private_msg" to "{\"user_id\":$user,\"message\":\"${jsonEscape(notification.toText())}\"}"
            }

            else -> {
                LOGGER.warn("QQ bot notification skipped: set notify.qqbot.groupId or notify.qqbot.userId")
                return
            }
        }

        val request = HttpRequest.newBuilder(URI.create(base + endpoint))
            .timeout(Duration.ofMillis(config.notifyTimeoutMillis.toLong()))
            .header("Content-Type", "application/json; charset=UTF-8")
            .apply { if (config.qqbotAccessToken.isNotBlank()) header("Authorization", "Bearer ${config.qqbotAccessToken}") }
            .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            .build()

        attempt("qqbot", notification) {
            val response = HTTP.send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() in 200..299) {
                "QQ bot returned HTTP ${response.statusCode()}: ${response.body().take(200)}"
            }
        }
    }

    /** One channel failing is logged and does not affect the remaining channels. */
    private inline fun attempt(channel: String, notification: Notification, block: () -> Unit) {
        try {
            block()
            LOGGER.info("Sent {} notification for {} via {}", notification.event, notification.player, channel)
        } catch (e: Exception) {
            LOGGER.warn(
                "Failed to send {} notification for {} via {}: {}",
                notification.event,
                notification.player,
                channel,
                e.toString()
            )
        }
    }
}
