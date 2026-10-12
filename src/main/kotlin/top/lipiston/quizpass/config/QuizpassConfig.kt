package top.lipiston.quizpass.config

import com.github.cao.awa.cason.array.JSONArray
import com.github.cao.awa.cason.obj.JSONObject
import com.github.kusa233.kalmia.config.KalmiaConfig
import java.io.File

/**
 * Plugin settings, read from `configs/quizpass.json` relative to the Kalmia working directory.
 *
 * Loading goes through [KalmiaConfig.createConfig], the same helper Kalmia uses for its own
 * `launch.json` / `kalmia_http.json`: the file is parsed when it exists, and otherwise the defaults
 * below are written out so the file is discoverable and editable. Keys are read one at a time, so a
 * partially written or hand-trimmed file keeps working and only the omitted keys fall back.
 */
class QuizpassConfig : KalmiaConfig() {

    var rconHost: String = DEFAULT_RCON_HOST
        private set

    var rconPort: Int = DEFAULT_RCON_PORT
        private set

    var rconPassword: String = DEFAULT_RCON_PASSWORD
        private set

    /** Whether the web panel should be fetched from [assetsUrl] on every startup. */
    var assetsDownload: Boolean = DEFAULT_ASSETS_DOWNLOAD
        private set

    var assetsUrl: String = DEFAULT_ASSETS_URL
        private set

    /** Where the downloaded panel is written; relative to the Kalmia working directory. */
    var assetsPath: String = DEFAULT_ASSETS_PATH
        private set

    // ---------------------------------------------------------------------------------------
    // Administrator notifications. Entirely optional: nothing is sent while [notifyEnabled]
    // is false, which is the default, so an untouched install behaves exactly as before.
    // ---------------------------------------------------------------------------------------

    /** Master switch for every notification channel. */
    var notifyEnabled: Boolean = DEFAULT_NOTIFY_ENABLED
        private set

    /** Notify when a player adds themselves to the whitelist. */
    var notifyOnAdd: Boolean = DEFAULT_NOTIFY_ON_ADD
        private set

    /** Notify when a player removes themselves from the whitelist. */
    var notifyOnRemove: Boolean = DEFAULT_NOTIFY_ON_REMOVE
        private set

    /** Per-channel network timeout, shared by mail, callback and QQ bot. */
    var notifyTimeoutMillis: Int = DEFAULT_NOTIFY_TIMEOUT_MILLIS
        private set

    var emailEnabled: Boolean = DEFAULT_EMAIL_ENABLED
        private set

    var emailHost: String = DEFAULT_EMAIL_HOST
        private set

    var emailPort: Int = DEFAULT_EMAIL_PORT
        private set

    /** Connect with implicit TLS (SMTPS, usually port 465) instead of plain/STARTTLS. */
    var emailSsl: Boolean = DEFAULT_EMAIL_SSL
        private set

    /** Upgrade a plaintext connection with STARTTLS (usually port 587). */
    var emailStartTls: Boolean = DEFAULT_EMAIL_START_TLS
        private set

    var emailUsername: String = DEFAULT_EMAIL_USERNAME
        private set

    var emailPassword: String = DEFAULT_EMAIL_PASSWORD
        private set

    /** Envelope sender; falls back to [emailUsername] when blank. */
    var emailFrom: String = DEFAULT_EMAIL_FROM
        private set

    /** Recipients, in the order the config lists them. */
    var emailTo: List<String> = emptyList()
        private set

    /** Supports the `{player}` and `{event}` placeholders. */
    var emailSubject: String = DEFAULT_EMAIL_SUBJECT
        private set

    /** Trust any TLS certificate; only for internal relays using a self-signed one. */
    var emailTrustAll: Boolean = DEFAULT_EMAIL_TRUST_ALL
        private set

    var callbackEnabled: Boolean = DEFAULT_CALLBACK_ENABLED
        private set

    var callbackUrl: String = DEFAULT_CALLBACK_URL
        private set

    /** Sent as `Authorization: Bearer <token>` when not blank. */
    var callbackToken: String = DEFAULT_CALLBACK_TOKEN
        private set

    var qqbotEnabled: Boolean = DEFAULT_QQBOT_ENABLED
        private set

    /** Base URL of a OneBot v11 HTTP API, e.g. `http://127.0.0.1:3000`. */
    var qqbotUrl: String = DEFAULT_QQBOT_URL
        private set

    /** Group messages win over private ones when both are set. */
    var qqbotGroupId: String = DEFAULT_QQBOT_GROUP_ID
        private set

    var qqbotUserId: String = DEFAULT_QQBOT_USER_ID
        private set

    var qqbotAccessToken: String = DEFAULT_QQBOT_ACCESS_TOKEN
        private set

    /** True when at least one channel is switched on, used to skip building notifications. */
    val anyChannelEnabled: Boolean
        get() = (emailEnabled && emailTo.isNotEmpty()) || callbackEnabled || qqbotEnabled

    override fun toJSON(): JSONObject = JSONObject()
        .put(
            "rcon", JSONObject()
                .put("host", rconHost)
                .put("port", rconPort)
                .put("password", rconPassword)
        )
        .put(
            "assets", JSONObject()
                .put("download", assetsDownload)
                .put("url", assetsUrl)
                .put("path", assetsPath)
        )
        .put(
            "notify", JSONObject()
                .put("enabled", notifyEnabled)
                .put("onAdd", notifyOnAdd)
                .put("onRemove", notifyOnRemove)
                .put("timeoutMillis", notifyTimeoutMillis)
                .put(
                    "email", JSONObject()
                        .put("enabled", emailEnabled)
                        .put("host", emailHost)
                        .put("port", emailPort)
                        .put("ssl", emailSsl)
                        .put("startTls", emailStartTls)
                        .put("username", emailUsername)
                        .put("password", emailPassword)
                        .put("from", emailFrom)
                        .put("subject", emailSubject)
                        .put("trustAll", emailTrustAll)
                        .put("to", JSONArray().apply { emailTo.forEach { add(it) } })
                )
                .put(
                    "callback", JSONObject()
                        .put("enabled", callbackEnabled)
                        .put("url", callbackUrl)
                        .put("token", callbackToken)
                )
                .put(
                    "qqbot", JSONObject()
                        .put("enabled", qqbotEnabled)
                        .put("url", qqbotUrl)
                        .put("groupId", qqbotGroupId)
                        .put("userId", qqbotUserId)
                        .put("accessToken", qqbotAccessToken)
                )
        )

    companion object {
        /** Where the config is looked up, relative to the Kalmia working directory. */
        const val CONFIG_PATH = "configs/quizpass.json"

        const val DEFAULT_RCON_HOST = "127.0.0.1"
        const val DEFAULT_RCON_PORT = 25575

        /** Default from the original hardcoded source; change it in the config file. */
        const val DEFAULT_RCON_PASSWORD = "awa"

        const val DEFAULT_ASSETS_DOWNLOAD = true
        const val DEFAULT_ASSETS_URL =
            "https://raw.githubusercontent.com/LIPiston/Quizpass/master/assets/index.html"
        const val DEFAULT_ASSETS_PATH = "assets/index.html"

        const val DEFAULT_NOTIFY_ENABLED = false
        const val DEFAULT_NOTIFY_ON_ADD = true
        const val DEFAULT_NOTIFY_ON_REMOVE = true
        const val DEFAULT_NOTIFY_TIMEOUT_MILLIS = 10_000

        const val DEFAULT_EMAIL_ENABLED = false
        const val DEFAULT_EMAIL_HOST = "smtp.example.com"
        const val DEFAULT_EMAIL_PORT = 465
        const val DEFAULT_EMAIL_SSL = true
        const val DEFAULT_EMAIL_START_TLS = false
        const val DEFAULT_EMAIL_USERNAME = ""
        const val DEFAULT_EMAIL_PASSWORD = ""
        const val DEFAULT_EMAIL_FROM = ""
        const val DEFAULT_EMAIL_SUBJECT = "Quizpass 白名单变更: {player} ({event})"
        const val DEFAULT_EMAIL_TRUST_ALL = false

        const val DEFAULT_CALLBACK_ENABLED = false
        const val DEFAULT_CALLBACK_URL = "http://127.0.0.1:8080/quizpass/notify"
        const val DEFAULT_CALLBACK_TOKEN = ""

        const val DEFAULT_QQBOT_ENABLED = false
        const val DEFAULT_QQBOT_URL = "http://127.0.0.1:3000"
        const val DEFAULT_QQBOT_GROUP_ID = ""
        const val DEFAULT_QQBOT_USER_ID = ""
        const val DEFAULT_QQBOT_ACCESS_TOKEN = ""

        /** Reads [file], creating it from the defaults when it does not exist yet. */
        fun load(file: File = File(CONFIG_PATH)): QuizpassConfig =
            createConfig(file) { fromJSON() }

        private fun JSONObject.fromJSON(): QuizpassConfig = QuizpassConfig().apply {
            ifJSON("rcon") {
                ifString("host") { rconHost = this }
                ifInt("port") { rconPort = this }
                ifString("password") { rconPassword = this }
            }
            ifJSON("assets") {
                ifBoolean("download") { assetsDownload = this }
                ifString("url") { assetsUrl = this }
                ifString("path") { assetsPath = this }
            }
            ifJSON("notify") {
                ifBoolean("enabled") { notifyEnabled = this }
                ifBoolean("onAdd") { notifyOnAdd = this }
                ifBoolean("onRemove") { notifyOnRemove = this }
                ifInt("timeoutMillis") { notifyTimeoutMillis = this }

                ifJSON("email") {
                    ifBoolean("enabled") { emailEnabled = this }
                    ifString("host") { emailHost = this }
                    ifInt("port") { emailPort = this }
                    ifBoolean("ssl") { emailSsl = this }
                    ifBoolean("startTls") { emailStartTls = this }
                    ifString("username") { emailUsername = this }
                    ifString("password") { emailPassword = this }
                    ifString("from") { emailFrom = this }
                    ifString("subject") { emailSubject = this }
                    ifBoolean("trustAll") { emailTrustAll = this }
                    ifArray("to") {
                        // Deliberately indexed rather than using `list`: Kalmia's bundled Cason
                        // changed `getList()` from `ArrayList` to `List` between 1.0.28 and 1.0.32,
                        // and `size()`/`getString(int)` are binary-stable across both.
                        val recipients = ArrayList<String>(size())
                        for (index in 0 until size()) {
                            getString(index)?.let { recipients += it }
                        }
                        emailTo = recipients
                    }
                }

                ifJSON("callback") {
                    ifBoolean("enabled") { callbackEnabled = this }
                    ifString("url") { callbackUrl = this }
                    ifString("token") { callbackToken = this }
                }

                ifJSON("qqbot") {
                    ifBoolean("enabled") { qqbotEnabled = this }
                    ifString("url") { qqbotUrl = this }
                    ifString("groupId") { qqbotGroupId = this }
                    ifString("userId") { qqbotUserId = this }
                    ifString("accessToken") { qqbotAccessToken = this }
                }
            }
        }
    }
}
