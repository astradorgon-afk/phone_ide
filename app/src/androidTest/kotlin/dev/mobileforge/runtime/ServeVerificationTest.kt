package dev.mobileforge.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.exec.JvmProcessLauncher
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
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
 * VERIFIES THE PHP SERVE PATH — can this phone actually host a web app?
 *
 * `php artisan serve` is not a special server; it is Laravel shelling out to PHP's built-in
 * development server (`php -S`). So "can the phone serve Laravel?" reduces to three ordinary
 * questions, each of which is checked here independently so a failure names the piece that
 * broke rather than just "serve didn't work":
 *
 *   1. **Can the toolchain arrive the way a user installs it?** The install here goes through
 *      [CatalogInstaller] against the same catalog the app reads — catalog -> manifest -> digest
 *      -> install — not through test-only assets. That is the "Download and install" path.
 *   2. **Does `php -S` bind loopback and answer HTTP?** That is the server Laravel's `serve`
 *      command runs, so proving it directly proves the mechanism.
 *   3. **Can PHP start a child process?** `artisan serve` spawns `php -S` through
 *      Symfony Process (proc_open). If an app cannot exec a child, every version banner still
 *      passes and only `serve` fails, which would be a nightmare to diagnose later.
 *
 * Requires a bundle host on http://127.0.0.1:8080 reachable through `adb reverse tcp:8080
 * tcp:8080` (tools/serve-bundles.ps1). Skips otherwise, like the other bundle tests.
 */
@RunWith(AndroidJUnit4::class)
class ServeVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var phpPath: String
    private lateinit var composerPath: String

    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        phpPath = "${environmentBuilder.binDir}/php"
        composerPath = "${environmentBuilder.binDir}/composer"

        assumeTrue("No PHP toolchain available (bundle host not running?)", install())
    }

    /**
     * The real install path: the published catalog, fetched over HTTP and verified.
     *
     * Only the tools this test needs are requested (base -> php -> composer), because that is
     * the dependency order the app itself enforces and the opencode bundle is irrelevant here
     * and large.
     */
    private fun install(): Boolean {
        if (File(phpPath).isFile) return true

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
        return result is AppResult.Success && File(phpPath).isFile
    }

    /** PHP's built-in server is what `artisan serve` runs. Bind loopback and answer a request. */
    @Test
    fun phpBuiltInServerServesHttp() {
        val docRoot = File(context.cacheDir, "serve-docroot").apply {
            deleteRecursively()
            mkdirs()
            File(this, "index.php").writeText("<?php echo 'mf-serve-ok '.PHP_VERSION;")
        }

        val port = 8231
        val process = startBackground(
            "$phpPath -S 127.0.0.1:$port -t ${docRoot.absolutePath}",
        )
        try {
            assertTrue("php -S never accepted a connection on 127.0.0.1:$port", waitForPort(port))
            val body = httpGet("http://127.0.0.1:$port/")
            Log.i(TAG, "php -S response: $body")

            assertTrue("The PHP server did not run our script. Response was: $body",
                body.contains("mf-serve-ok"))
            assertTrue("The server did not report a PHP version. Response was: $body",
                body.contains("8.5"))
        } finally {
            process.destroy(force = true)
        }
    }

    /**
     * The prerequisite `artisan serve` has but a version banner does not: exec'ing a child.
     *
     * Symfony's Process uses proc_open with an argument vector, so this does the same rather
     * than going through a shell. If this fails, `php artisan serve` can never work no matter
     * how healthy `php -v` looks.
     */
    @Test
    fun phpCanSpawnAChildPhpProcess() {
        val script = File(context.cacheDir, "spawn-child-${System.nanoTime()}.php")
        script.writeText(
            """
            <?php
            ${'$'}cmd = [PHP_BINARY, '-r', 'echo "CHILD-OK";'];
            ${'$'}pipes = [];
            ${'$'}p = proc_open(${'$'}cmd, [1 => ['pipe', 'w'], 2 => ['pipe', 'w']], ${'$'}pipes);
            if (!is_resource(${'$'}p)) { echo 'proc_open-failed'; exit(1); }
            ${'$'}out = stream_get_contents(${'$'}pipes[1]);
            ${'$'}err = stream_get_contents(${'$'}pipes[2]);
            fclose(${'$'}pipes[1]); fclose(${'$'}pipes[2]);
            ${'$'}code = proc_close(${'$'}p);
            echo 'out=' . trim(${'$'}out) . ' err=' . trim(${'$'}err) . ' code=' . ${'$'}code;
            """.trimIndent(),
        )

        val output = runShell("$phpPath ${script.absolutePath}")
        Log.i(TAG, "child spawn: $output")
        script.delete()

        assertTrue("PHP could not exec a child PHP (artisan serve needs this). Output was: $output",
            output.contains("out=CHILD-OK"))
    }

    /**
     * The whole point: create a real Laravel app with real Composer, then serve it.
     *
     * `laravel/laravel` is fetched by Composer on the device, so nothing here is a stand-in.
     * Creation is skipped when the project already exists, which is what makes repeated runs
     * cheap, but on a fresh device this is the same `create-project` a user would run.
     */
    @Test
    fun artisanServeServesARealProject() {
        val project = findLaravelProject() ?: createLaravelProject()

        val port = 8232
        val process = startBackground(
            "cd ${project.absolutePath} && $phpPath artisan serve --host=127.0.0.1 --port=$port",
        )
        try {
            // artisan boots the framework before it starts listening, so allow a wide window.
            assertTrue("php artisan serve never listened on $port", waitForPort(port, 30_000))
            val body = httpGet("http://127.0.0.1:$port/")
            Log.i(TAG, "artisan serve response (${body.length} chars): ${body.take(300)}")

            assertTrue("artisan serve returned nothing", body.isNotBlank())
            assertTrue(
                "The page did not look like a served app. Response started: ${body.take(200)}",
                body.contains("html", ignoreCase = true) || body.contains("Laravel"),
            )
        } finally {
            process.destroy(force = true)
        }
    }

    /**
     * A real Laravel project, or a loud failure.
     *
     * The point of this test is that the phone serves a genuine app, so a missing framework is
     * a real failure here rather than something to skip past.
     */
    private fun createLaravelProject(): File {
        val projectsDir = File(context.filesDir, "projects").apply { mkdirs() }
        val project = File(projectsDir, "mfserve")

        val output = runShell(
            "$composerPath create-project laravel/laravel ${project.absolutePath} " +
                "--no-interaction --prefer-dist --no-progress",
        )
        Log.i(TAG, "create-project output (${output.length} chars): ${output.takeLast(600)}")

        assertTrue(
            "composer create-project did not produce an artisan entrypoint. Output tail: " +
                output.takeLast(400),
            File(project, "artisan").isFile && File(project, "vendor/autoload.php").isFile,
        )
        return project
    }

    // -----------------------------------------------------------------------------

    private fun findLaravelProject(): File? {
        val roots = listOf(File(homeDir), File(context.filesDir, "projects"))
        roots.forEach { root ->
            root.listFiles()?.forEach { candidate ->
                if (File(candidate, "artisan").isFile && File(candidate, "vendor/autoload.php").isFile) {
                    return candidate
                }
            }
        }
        return null
    }

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

    private fun runShell(command: String): String {
        val launched = launcher.launch(
            argv = listOf("/system/bin/sh", "-c", command),
            workingDirectory = homeDir,
            environment = shellEnvironment(),
        )
        if (launched is AppResult.Failure) return "launch failed: ${launched.error.detail}"

        val process = (launched as AppResult.Success).value
        val stdout = process.stdout.bufferedReader().readText()
        val stderr = process.stderr.bufferedReader().readText()
        process.waitFor()
        return (stdout + stderr).trim()
    }

    private fun shellEnvironment(): Map<String, String> = buildMap {
        val tmp = File(environmentBuilder.prefix, "tmp").apply { mkdirs() }
        put("HOME", homeDir)
        put("PREFIX", environmentBuilder.prefix)
        put("PATH", "${environmentBuilder.binDir}:/system/bin:/system/xbin")
        put("TERM", "dumb")
        put("TMPDIR", tmp.absolutePath)
        put("TMP", tmp.absolutePath)
        put("COMPOSER_HOME", File(homeDir, ".composer").apply { mkdirs() }.absolutePath)
        put("LD_LIBRARY_PATH", environmentBuilder.libDir)
        put("LD_PRELOAD", "$nativeLibraryDir/${RuntimeEnvironmentBuilder.INTERPOSER_LIBRARY}")
        put(RuntimeEnvironmentBuilder.PREFIX_VAR, environmentBuilder.prefix)
    }

    private companion object {
        const val TAG = "MF.ServeVerify"
        val NEEDED = setOf("base", "php", "composer")
    }
}
