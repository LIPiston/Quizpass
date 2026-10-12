package top.lipiston.quizpass.notify

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Everything [SmtpMailer] needs for one delivery. */
internal data class SmtpSettings(
    val host: String,
    val port: Int,
    val ssl: Boolean,
    val startTls: Boolean,
    val trustAll: Boolean,
    val username: String,
    val password: String,
    val from: String,
    val recipients: List<String>,
    val timeoutMillis: Int,
)

/**
 * A minimal SMTP client built on plain sockets.
 *
 * Kalmia does not ship JavaMail (or any mail library) and its classloader only treats jars in
 * `libs/` as plugins, so pulling one in would mean shading a dependency into the plugin jar. The
 * subset of SMTP an administrator notification actually needs — greeting, EHLO/HELO, optional
 * STARTTLS, AUTH LOGIN/PLAIN, MAIL FROM, RCPT TO, DATA, QUIT — is small enough to speak directly,
 * which keeps the plugin dependency-free.
 *
 * Every failure is raised as an exception; the caller logs it and moves on.
 */
internal object SmtpMailer {

    private const val CRLF = "\r\n"

    private val DATE_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.US)

    /**
     * Delivers one UTF-8 text mail. Throws [IOException] (or a TLS exception) when the server
     * rejects any step, so the caller can report exactly which channel failed.
     */
    fun send(settings: SmtpSettings, subject: String, text: String) {
        val session = Session(settings)

        try {
            session.open()
            session.deliver(settings.from, subject, text)
            session.quit()
        } finally {
            session.close()
        }
    }

    private class Session(private val settings: SmtpSettings) {

        // Bound only once a socket is actually connected: `Socket()` is unconnected, and asking it
        // for its streams throws "Socket is not connected", so there is no usable placeholder.
        private lateinit var socket: Socket
        private lateinit var reader: BufferedReader
        private lateinit var writer: BufferedWriter

        private fun bind(target: Socket) {
            socket = target
            reader = BufferedReader(InputStreamReader(target.getInputStream(), Charsets.US_ASCII))
            writer = BufferedWriter(OutputStreamWriter(target.getOutputStream(), Charsets.US_ASCII))
        }

        fun open() {
            val plain = Socket()
            plain.connect(InetSocketAddress(settings.host, settings.port), settings.timeoutMillis)
            plain.soTimeout = settings.timeoutMillis

            bind(if (settings.ssl) upgrade(plain) else plain)

            expect("greeting", command("", send = false), 220)
        }

        fun deliver(from: String, subject: String, text: String) {
            var capabilities: List<String>

            val greeting = command("EHLO quizpass")
            if (greeting.first == 250) {
                capabilities = greeting.second
            } else {
                // Older servers reject EHLO outright; HELO is enough when no AUTH is needed.
                val helo = command("HELO quizpass")
                expect("HELO", helo, 250)
                capabilities = helo.second
            }

            if (settings.startTls && !settings.ssl) {
                expect("STARTTLS", command("STARTTLS"), 220)
                bind(upgrade(socket))
                val rehandshake = command("EHLO quizpass")
                expect("EHLO after STARTTLS", rehandshake, 250)
                capabilities = rehandshake.second
            }

            if (settings.username.isNotBlank()) {
                authenticate(capabilities)
            }

            expect("MAIL FROM", command("MAIL FROM:<$from>"), 250)

            for (recipient in settings.recipients) {
                val reply = command("RCPT TO:<$recipient>")
                if (reply.first != 250 && reply.first != 251) {
                    throw IOException("SMTP server rejected recipient <$recipient>: ${last(reply)}")
                }
            }

            expect("DATA", command("DATA"), 354)

            writer.write(dotStuff(compose(from, subject, text)))
            writer.write(CRLF)
            writer.write(".")
            writer.write(CRLF)
            writer.flush()

            expect("end of DATA", readReply(), 250)
        }

        fun quit() {
            runCatching { command("QUIT") }
        }

        fun close() {
            runCatching { socket.close() }
        }

        private fun authenticate(capabilities: List<String>) {
            val advertised = capabilities.joinToString(" ").uppercase(Locale.ROOT)

            when {
                "PLAIN" in advertised -> authPlain()
                "LOGIN" in advertised -> authLogin()
                // A server that advertises no mechanism may still accept LOGIN; trying it is
                // cheaper than failing outright.
                else -> authLogin()
            }
        }

        private fun authLogin() {
            expect("AUTH LOGIN", command("AUTH LOGIN"), 334)
            expect("AUTH LOGIN username", command(base64(settings.username)), 334)
            expect("AUTH LOGIN password", command(base64(settings.password)), 235)
        }

        private fun authPlain() {
            val payload = base64("\u0000${settings.username}\u0000${settings.password}")
            val reply = command("AUTH PLAIN $payload")

            if (reply.first == 334) {
                expect("AUTH PLAIN payload", command(payload), 235)
                return
            }

            expect("AUTH PLAIN", reply, 235)
        }

        /** Wraps [plain] in TLS, trusting only the JVM default store unless configured otherwise. */
        private fun upgrade(plain: Socket): Socket {
            val ssl = sslContext().socketFactory
                .createSocket(plain, settings.host, settings.port, true) as SSLSocket

            ssl.soTimeout = settings.timeoutMillis
            ssl.startHandshake()

            return ssl
        }

        private fun sslContext(): SSLContext {
            val context = SSLContext.getInstance("TLS")

            if (settings.trustAll) {
                context.init(null, TRUST_ALL, SecureRandom())
            } else {
                context.init(null, null, null)
            }

            return context
        }

        /**
         * The final `Message-ID`/`Date`/`MIME` headers are built here because a raw socket has no
         * library filling them in. The body is base64 so non-ASCII (Chinese) content survives
         * servers that would otherwise mangle 8-bit lines.
         */
        private fun compose(from: String, subject: String, text: String): String = buildString {
            append("From: ").append(from).append(CRLF)
            append("To: ").append(settings.recipients.joinToString(", ")).append(CRLF)
            append("Subject: ").append(encodeHeader(subject)).append(CRLF)
            append("Date: ").append(ZonedDateTime.now().format(DATE_FORMAT)).append(CRLF)
            append("Message-ID: <").append(UUID.randomUUID()).append("@quizpass>").append(CRLF)
            append("MIME-Version: 1.0").append(CRLF)
            append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
            append("Content-Transfer-Encoding: base64").append(CRLF)
            append(CRLF)
            append(wrapBase64(text.toByteArray(Charsets.UTF_8)))
        }

        /** A line consisting of a single `.` ends the DATA section, so leading dots are doubled. */
        private fun dotStuff(message: String): String =
            message.split(CRLF).joinToString(CRLF) { line ->
                if (line.startsWith(".")) ".$line" else line
            }

        private fun command(line: String, send: Boolean = true): Pair<Int, List<String>> {
            if (send) {
                writer.write(line)
                writer.write(CRLF)
                writer.flush()
            }

            return readReply()
        }

        /** Reads a possibly multi-line reply (`250-…` continuations until a `250 …` line). */
        private fun readReply(): Pair<Int, List<String>> {
            val lines = ArrayList<String>(4)

            while (true) {
                val line = reader.readLine()
                    ?: throw IOException("SMTP server closed the connection")

                lines.add(line)

                val code = line.take(3).toIntOrNull()
                    ?: throw IOException("Malformed SMTP reply: $line")

                if (line.length < 4 || line[3] != '-') {
                    return code to lines
                }
            }
        }

        private fun expect(step: String, reply: Pair<Int, List<String>>, code: Int) {
            if (reply.first != code) {
                throw IOException("SMTP $step failed: expected $code but got ${last(reply)}")
            }
        }

        private fun last(reply: Pair<Int, List<String>>): String =
            reply.second.lastOrNull() ?: reply.first.toString()
    }

    private fun base64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.US_ASCII))

    /** RFC 2047 encoding, needed because the subject line carries Chinese text. */
    private fun encodeHeader(value: String): String =
        if (value.all { it.code in 32..126 }) {
            value
        } else {
            "=?UTF-8?B?" + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8)) + "?="
        }

    private fun wrapBase64(bytes: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(bytes)

        return encoded.chunked(76).joinToString(CRLF)
    }

    /**
     * Only used when the config explicitly opts in, for internal relays using a self-signed
     * certificate.
     */
    private val TRUST_ALL: Array<TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )
}
