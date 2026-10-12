package top.lipiston.quizpass.notify

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * One whitelist change that an administrator may want to hear about.
 *
 * [account] and [qq] come from the player-facing signup form and are empty for events that were
 * not submitted through it (for example the API-only removal endpoint). They are rendered only
 * when present, so an empty field never shows up as a blank line in a mail or a QQ message.
 *
 * Kalmia's HTTP context exposes request headers but not the remote socket address, so
 * [clientAddress] is best-effort: it carries `X-Forwarded-For` / `X-Real-IP` when the panel sits
 * behind a reverse proxy, and [UNKNOWN_ADDRESS] otherwise. That limitation is documented in the
 * README rather than papered over with a guessed value.
 */
data class Notification(
    val event: String,
    val player: String,
    val account: String,
    val qq: String,
    val command: String,
    val host: String,
    val clientAddress: String,
    val timestamp: String,
) {

    val title: String
        get() = if (event == EVENT_REMOVE) "Quizpass 白名单移除" else "Quizpass 白名单申请"

    /** Chinese rendering of the wire value in [account], for humans. */
    val accountLabel: String
        get() = when (account) {
            ACCOUNT_PREMIUM -> "正版"
            ACCOUNT_SKIN -> "皮肤站"
            else -> account
        }

    /** Human-readable body, shared by the mail and QQ bot channels. */
    fun toText(): String = buildString {
        append(title).append('\n')
        append('\n')
        append("玩家: ").append(player).append('\n')
        if (account.isNotBlank()) {
            append("游戏方式: ").append(accountLabel).append('\n')
        }
        if (qq.isNotBlank()) {
            append("QQ: ").append(qq).append('\n')
        }
        append("操作: ").append(event).append('\n')
        append("命令: ").append(command).append('\n')
        append("时间: ").append(timestamp).append('\n')
        append("来源地址: ").append(clientAddress).append('\n')
        append("站点: ").append(host.ifBlank { UNKNOWN_HOST })
    }

    /**
     * Structured payload for the HTTP callback channel.
     *
     * [account] is the wire value (`premium` / `skin`), not the Chinese label: consumers should
     * branch on a stable identifier. The label is still recoverable from `text`.
     */
    fun toJson(): String = buildString {
        append('{')
        append("\"event\":\"").append(jsonEscape(event)).append("\",")
        append("\"player\":\"").append(jsonEscape(player)).append("\",")
        append("\"account\":\"").append(jsonEscape(account)).append("\",")
        append("\"qq\":\"").append(jsonEscape(qq)).append("\",")
        append("\"command\":\"").append(jsonEscape(command)).append("\",")
        append("\"host\":\"").append(jsonEscape(host)).append("\",")
        append("\"address\":\"").append(jsonEscape(clientAddress)).append("\",")
        append("\"time\":\"").append(jsonEscape(timestamp)).append("\",")
        append("\"text\":\"").append(jsonEscape(toText())).append('"')
        append('}')
    }

    companion object {
        const val EVENT_ADD = "add"
        const val EVENT_REMOVE = "remove"

        /** A Mojang (paid) account. */
        const val ACCOUNT_PREMIUM = "premium"

        /** An account from a third-party skin site (authlib-injector style login). */
        const val ACCOUNT_SKIN = "skin"

        /** Every accepted wire value for [account]. */
        val ACCOUNT_VALUES: Set<String> = setOf(ACCOUNT_PREMIUM, ACCOUNT_SKIN)

        const val UNKNOWN_ADDRESS = "unknown"
        const val UNKNOWN_HOST = "unknown"

        private val TIMESTAMP_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        fun of(
            event: String,
            player: String,
            command: String,
            host: String,
            clientAddress: String,
            account: String = "",
            qq: String = "",
        ): Notification = Notification(
            event = event,
            player = player,
            account = account,
            qq = qq,
            command = command,
            host = host,
            clientAddress = clientAddress.ifBlank { UNKNOWN_ADDRESS },
            timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT),
        )
    }
}

/**
 * Escapes [value] into a JSON string body.
 *
 * The callback and QQ bot channels are plain HTTP, so the request body is assembled by hand
 * instead of routing a handful of fields through the config library's object model.
 */
internal fun jsonEscape(value: String): String {
    val escaped = StringBuilder(value.length + 16)

    for (character in value) {
        when (character) {
            '"' -> escaped.append("\\\"")
            '\\' -> escaped.append("\\\\")
            '\n' -> escaped.append("\\n")
            '\r' -> escaped.append("\\r")
            '\t' -> escaped.append("\\t")
            '\b' -> escaped.append("\\b")
            '\u000C' -> escaped.append("\\f")
            else -> if (character < ' ') {
                escaped.append("\\u%04x".format(character.code))
            } else {
                escaped.append(character)
            }
        }
    }

    return escaped.toString()
}
