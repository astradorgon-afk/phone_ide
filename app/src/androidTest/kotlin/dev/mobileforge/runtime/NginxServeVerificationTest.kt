package dev.mobileforge.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.api.ProcessSpec
import dev.mobileforge.runtime.exec.JvmProcessLauncher
import dev.mobileforge.runtime.exec.LaunchedProcess
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import dev.mobileforge.runtime.laravel.ServeLaunch
import dev.mobileforge.runtime.toolchain.BundleCatalog
import dev.mobileforge.runtime.toolchain.CatalogInstaller
import dev.mobileforge.runtime.toolchain.HttpBundleFetcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * VERIFIES THE NGINX SERVE PATH — the second of the two servers the Serve pane offers.
 *
 * `php artisan serve` is proven by [ServeVerificationTest]; this is the other choice: a real
 * nginx in front of `php-cgi`. nginx is a native binary shipped in its own bundle, and php-cgi
 * is the FastCGI SAPI that already rides along with the PHP bundle, so the questions are:
 *
 *   1. **Can the nginx bundle arrive the way a user installs it?** Through [CatalogInstaller]
 *      against the app's own catalog — catalog -> manifest -> digest -> install.
 *   2. **Does the generated config start nginx in the foreground?** `daemon off` is what keeps
 *      the process we launched the process that serves; without it the process exits at once and
 *      the app would read that as a crash.
 *   3. **Does a request reach PHP through FastCGI?** The whole reason to choose nginx: static
 *      files without booting PHP, and `.php` forwarded to php-cgi.
 *
 * Requires a bundle host on http://127.0.0.1:8080 reachable through `adb reverse tcp:8080
 * tcp:8080` (tools/serve-bundles.ps1). Skips otherwise, like the other bundle tests.
 */
@RunWith(AndroidJUnit4::class)
class NginxServeVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var phpPath: String
    private lateinit var phpCgiPath: String
    private lateinit var nginxPath: String

    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        phpPath = "${environmentBuilder.binDir}/php"
        phpCgiPath = "${environmentBuilder.binDir}/php-cgi"
        nginxPath = "${environmentBuilder.binDir}/nginx"

        assumeTrue("No nginx bundle available (bundle host not running?)", install())
    }

    /**
     * The real install path: the published catalog, fetched over HTTP and verified.
     *
     * Requests only what this test needs (base -> php -> nginx). The early return checks for
     * BOTH binaries: an emulator with the toolchain already installed would otherwise report
     * "installed" while nginx is still missing, and the test would fail for the wrong reason.
     */
    private fun install(): Boolean {
        if (File(phpPath).isFile && File(phpCgiPath).isFile && File(nginxPath).isFile) return true

        val catalogText = runCatching {
            context.assets.open("bundles/catalog.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: run {
            Log.w(TAG, "no catalog.json in the app's assets")
            return false
        }

        val catalog = runCatching {
            BundleCatalog.parse(catalogText).let { parsed ->
                parsed.copy(bundles = parsed.bundles.filter { it.id in NEEDED })
            }
        }.getOrElse {
            Log.w(TAG, "catalog could not be parsed: ${it.message}")
            return false
        }

        val manager = AndroidToolchainManager(
            context,
            File(environmentBuilder.prefix),
            DefaultAppDispatchers,
            NoOpLogger,
        )
        val installer = CatalogInstaller(
            fetcher = HttpBundleFetcher(DefaultAppDispatchers),
            manager = manager,
            downloadDir = File(context.cacheDir, "catalog-downloads"),
            deviceAbi = Build.SUPPORTED_ABIS.first(),
        )

        val result = runBlocking {
            installer.installAll(catalog) { progress ->
                Log.i(TAG, "install ${progress.displayName} ${(progress.overall * 100).toInt()}%")
            }
        }
        if (result is AppResult.Failure) {
            Log.w(TAG, "install failed: ${result.error.message} / ${result.error.detail}")
        }
        return File(phpPath).isFile && File(phpCgiPath).isFile && File(nginxPath).isFile
    }

    /**
     * The whole point of choosing nginx: it serves a static file on its own, and hands `.php`
     * to php-cgi, using exactly the config the app generates.
     */
    @Test
    fun nginxServesStaticFilesAndPhpThroughFastCgi() {
        val docRoot = File(context.cacheDir, "nginx-docroot").apply {
            deleteRecursively()
            mkdirs()
            File(this, "index.html").writeText("mf-static-ok")
            File(this, "index.php").writeText("<?php echo 'mf-nginx-ok '.PHP_VERSION;")
        }
        val workDir = File(context.cacheDir, "nginx-work").apply {
            deleteRecursively()
            mkdirs()
        }

        val port = 8331
        val fastCgiPort = ServeLaunch.fastCgiPortFor(port)
        val config = File(workDir, ServeLaunch.configFileName()).apply {
            writeText(
                ServeLaunch.renderNginxConfig(
                    port = port,
                    fastCgiPort = fastCgiPort,
                    documentRoot = docRoot.absolutePath,
                    workDir = workDir.absolutePath,
                ),
            )
        }

        // php-cgi first: nginx resolves the FastCGI backend per request, but the backend must
        // be listening before the first `.php` request arrives.
        val cgi = startBackground(
            shellify(ServeLaunch.phpCgiSpec(phpCgiPath, docRoot.absolutePath, fastCgiPort)),
        )
        val nginx = startBackground(
            shellify(ServeLaunch.nginxSpec(nginxPath, workDir.absolutePath, config.absolutePath)),
        )
        try {
            assertTrue(
                "nginx never accepted a connection on 127.0.0.1:$port. stderr:\n${drainAvailable(nginx)}",
                waitForPort(port),
            )

            val static = httpGet("http://127.0.0.1:$port/index.html")
            Log.i(TAG, "static response: $static")
            assertTrue("Static file was not served. Response was: $static", static.contains("HTTP 200"))
            assertTrue("Static body was wrong. Response was: $static", static.contains("mf-static-ok"))

            val php = httpGet("http://127.0.0.1:$port/index.php")
            Log.i(TAG, "php response: $php")
            assertTrue("PHP was not served. Response was: $php", php.contains("HTTP 200"))
            assertTrue(
                "The FastCGI request did not reach PHP. Response was: $php",
                php.contains("mf-nginx-ok"),
            )
            assertTrue("The PHP version was not reported. Response was: $php", php.contains("8.5"))
        } finally {
            nginx.destroy(force = true)
            cgi.destroy(force = true)
        }
    }

    // -----------------------------------------------------------------------------

    /** Renders a [ProcessSpec] the way the exec core would, for a shell-based test harness. */
    private fun shellify(spec: ProcessSpec): String {
        val env = spec.environment.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val invocation = (listOf(env, spec.executable) + spec.arguments)
            .filter { it.isNotBlank() }
            .joinToString(" ")
        return "cd ${spec.workingDirectory} && $invocation"
    }

    /**
     * Whatever nginx has written to stderr so far — its `error_log stderr` reason for refusing
     * to start, usually. Best-effort: the pipe may legitimately be empty.
     */
    private fun drainAvailable(process: LaunchedProcess): String =
        runCatching {
            val available = process.stderr.available()
            if (available <= 0) return ""
            val buffer = ByteArray(available)
            process.stderr.read(buffer)
            String(buffer).trim()
        }.getOrDefault("")

    /** Launches a command and returns immediately, for long-running servers. */
    private fun startBackground(command: String) =
        when (val launched = launcher.launch(
            argv = listOf("/system/bin/sh", "-c", command),
            workingDirectory = homeDir,
            environment = shellEnvironment(),
        )) {
            is AppResult.Failure -> error("launch failed: ${launched.error.detail}")
            is AppResult.Success -> launched.value
        }

    private fun waitForPort(port: Int, timeoutMs: Long = 15_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                return true
            } catch (_: Exception) {
                Thread.sleep(200)
            }
        }
        return false
    }

    private fun httpGet(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            "HTTP $code\n$body"
        } finally {
            connection.disconnect()
        }
    }

    private fun shellEnvironment(): Map<String, String> = buildMap {
        val tmp = File(environmentBuilder.prefix, "tmp").apply { mkdirs() }
        put("HOME", homeDir)
        put("PREFIX", environmentBuilder.prefix)
        put("PATH", "${environmentBuilder.binDir}:/system/bin:/system/xbin")
        put("TERM", "dumb")
        put("TMPDIR", tmp.absolutePath)
        put("TMP", tmp.absolutePath)
        put("LD_LIBRARY_PATH", environmentBuilder.libDir)
        put("LD_PRELOAD", "$nativeLibraryDir/${RuntimeEnvironmentBuilder.INTERPOSER_LIBRARY}")
        put(RuntimeEnvironmentBuilder.PREFIX_VAR, environmentBuilder.prefix)
    }

    private companion object {
        const val TAG = "MF.NginxServeVerify"
        val NEEDED = setOf("base", "php", "nginx")
    }
}
