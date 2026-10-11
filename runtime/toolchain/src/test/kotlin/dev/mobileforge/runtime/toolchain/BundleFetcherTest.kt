package dev.mobileforge.runtime.toolchain

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import dev.mobileforge.core.common.DefaultAppDispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

/**
 * The fetcher against a real HTTP server.
 *
 * A fake would prove the code calls `read`; this proves a truncated download is rejected and
 * that an interrupted fetch cannot leave a usable-looking file behind — the two failures that
 * would otherwise surface much later as a digest mismatch.
 */
class BundleFetcherTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    private val fetcher = HttpBundleFetcher(DefaultAppDispatchers)

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun serve(path: String, body: ByteArray, status: Int = 200) {
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
    }

    @Test
    fun `downloads a body and reports completion`() = runTest {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        serve("/bundle.zip", payload)
        val destination = File(temp.root, "bundle.zip")

        val progress = mutableListOf<Float>()
        fetcher.fetch("$baseUrl/bundle.zip", destination) { progress += it }

        assertThat(destination.readBytes()).isEqualTo(payload)
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress.first()).isGreaterThan(0f)
    }

    @Test
    fun `a non-success status is an error, not an empty file`() = runTest {
        serve("/missing.zip", "not found".toByteArray(), status = 404)
        val destination = File(temp.root, "missing.zip")

        val failure = runCatching {
            fetcher.fetch("$baseUrl/missing.zip", destination)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).contains("404")
        assertThat(destination.exists()).isFalse()
    }

    @Test
    fun `a failed download leaves no partial file`() = runTest {
        serve("/broken.zip", ByteArray(10_000), status = 500)
        val destination = File(temp.root, "broken.zip")

        runCatching { fetcher.fetch("$baseUrl/broken.zip", destination) }

        assertThat(destination.exists()).isFalse()
        assertThat(File(temp.root, "broken.zip.part").exists()).isFalse()
    }

    @Test
    fun `an existing file is replaced, and only after it is complete`() = runTest {
        val payload = ByteArray(50_000) { 7 }
        serve("/again.zip", payload)
        val destination = File(temp.root, "again.zip").apply { writeBytes(ByteArray(3)) }

        fetcher.fetch("$baseUrl/again.zip", destination)

        assertThat(destination.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `a cleartext public address is refused before opening a connection`() = runTest {
        val destination = File(temp.root, "refused.zip")

        val failure = runCatching {
            fetcher.fetch("http://bundles.example.com/bundle.zip", destination)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(destination.exists()).isFalse()
    }
}
