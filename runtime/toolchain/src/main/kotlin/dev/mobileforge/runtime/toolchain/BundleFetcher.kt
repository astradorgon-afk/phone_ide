package dev.mobileforge.runtime.toolchain

import dev.mobileforge.core.common.AppDispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.coroutineContext

/**
 * Fetches bytes for the toolchain installer.
 *
 * An interface because the install path must be exercisable without a network, and because a
 * future mirror or package-manager back end is a different implementation of the same act.
 */
interface BundleFetcher {
    /**
     * Downloads [url] to [destination], replacing whatever is there.
     *
     * [onProgress] is 0.0..1.0 and only called when the server declares a length; a chunked
     * response reports nothing rather than lying about a fraction of an unknown total.
     * Throws [IOException] on failure — the caller turns that into a structured error.
     */
    suspend fun fetch(url: String, destination: File, onProgress: (Float) -> Unit = {})
}

/**
 * The real fetch, over HTTP.
 *
 * There is deliberately no third-party HTTP client: this makes one GET, streams it to disk and
 * stops. The connection is never reused and nothing is buffered whole, because a PHP bundle is
 * tens of megabytes on a phone.
 *
 * Downloads land in a sibling `*.part` file and are renamed only once complete, so an
 * interrupted fetch can never leave a truncated file that a later run would mistake for a
 * finished one.
 */
class HttpBundleFetcher(
    private val dispatchers: AppDispatchers,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : BundleFetcher {

    override suspend fun fetch(
        url: String,
        destination: File,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(dispatchers.io) {
        if (!BundleUrlPolicy.isPermitted(url)) {
            throw IOException("Refusing to download from an address this app does not trust: $url")
        }

        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        var connection: HttpURLConnection? = null
        try {
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = true
                // Identity encoding: progress needs the real byte count, and a toolchain
                // archive is already compressed.
                setRequestProperty("Accept-Encoding", "identity")
            }

            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("The bundle source answered HTTP $code for $url.")
            }

            val declared = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER)
                    var copied = 0L
                    while (true) {
                        // Checks the job is still wanted between chunks: Stop on a 200 MB
                        // download must actually stop, not finish quietly.
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        output.write(buffer, 0, read)
                        if (declared > 0) {
                            onProgress((copied.toFloat() / declared).coerceIn(0f, 1f))
                        }
                    }
                }
            }

            if (declared > 0 && partial.length() != declared) {
                throw IOException(
                    "The download stopped early: got ${partial.length()} of $declared bytes.",
                )
            }

            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            onProgress(1f)
        } catch (t: Throwable) {
            partial.delete()
            throw t
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val COPY_BUFFER = 64 * 1024
    }
}
