package top.lipiston.quizpass.assets

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

/**
 * Fetches the web panel from a remote URL into the Kalmia asset directory.
 *
 * This exists so the front end can be updated without rebuilding and redeploying the plugin jar.
 * The download is deliberately fail-soft: a missing network, an unreachable host, a non-200
 * response or a truncated body all just log a warning and leave whatever file is already on disk
 * in place. Startup must never depend on the remote being available.
 */
object AssetsDownloader {

    private val LOGGER: Logger = LogManager.getLogger("Quizpass")

    /** Bounded so an unresponsive host cannot stall startup for long. */
    private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
    private val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(15)

    /**
     * Downloads [url] to [targetPath] (relative paths resolve against the process working
     * directory, the same way Kalmia resolves `configs/`). Returns `true` only when a file was
     * actually replaced.
     */
    fun download(url: String, targetPath: String): Boolean {
        val target = File(targetPath)

        if (!isUsableTarget(target)) return false

        LOGGER.info("Downloading web panel assets from {} to {}", url, target.absolutePath)

        val body = try {
            fetch(url)
        } catch (e: Exception) {
            LOGGER.warn("Failed to download web panel assets from {}: {}", url, e.toString())
            return false
        }

        if (body.isEmpty()) {
            LOGGER.warn("Downloaded web panel assets from {} were empty; keeping the existing file", url)
            return false
        }

        return try {
            writeAtomically(target, body)
            LOGGER.info("Web panel assets updated ({} bytes)", body.size)
            true
        } catch (e: Exception) {
            LOGGER.warn("Failed to write web panel assets to {}: {}", target.absolutePath, e.toString())
            false
        }
    }

    private fun fetch(url: String): ByteArray {
        val client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())

        if (response.statusCode() != 200) {
            throw IllegalStateException("unexpected HTTP status ${response.statusCode()}")
        }

        return response.body() ?: ByteArray(0)
    }

    /**
     * Refuses paths that would escape the working directory, and paths that already point at a
     * directory, so a typo in the config cannot clobber something unrelated.
     */
    private fun isUsableTarget(target: File): Boolean {
        val workingDir = File(".").canonicalFile
        val resolved = target.canonicalFile

        if (!resolved.toPath().startsWith(workingDir.toPath())) {
            LOGGER.warn(
                "Refusing to download web panel assets outside the working directory: {}",
                resolved.absolutePath
            )
            return false
        }

        if (resolved.isDirectory) {
            LOGGER.warn("Web panel assets target is a directory, not a file: {}", resolved.absolutePath)
            return false
        }

        return true
    }

    /**
     * Writes to a sibling temp file and moves it into place, so a failure midway through cannot
     * leave a half-written panel behind.
     */
    private fun writeAtomically(target: File, body: ByteArray) {
        target.parentFile?.mkdirs()

        val temp = File(target.parentFile, "${target.name}.tmp")
        try {
            temp.writeBytes(body)
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
