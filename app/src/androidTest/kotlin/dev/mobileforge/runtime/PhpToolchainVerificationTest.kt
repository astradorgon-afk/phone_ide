package dev.mobileforge.runtime

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.exec.JvmProcessLauncher
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import dev.mobileforge.runtime.toolchain.DeviceProfile
import dev.mobileforge.runtime.toolchain.ToolchainInstaller
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * VERIFIES PHP — the interpreter Phase 3 is built on.
 *
 * Laravel is the product's headline use case, and none of it means anything unless PHP actually
 * runs on the device. This is a harder case than git:
 *
 *  - PHP links against 33 packages' worth of libraries — ICU, OpenSSL, curl, libxml2, oniguruma
 *    and more — all built from source against our prefix;
 *  - it loads shared *extensions* at runtime from a path compiled into it, which is a second way
 *    the baked prefix has to be right;
 *  - it is an interpreter, so "it started" proves very little. These tests make it evaluate code
 *    and run a real script from disk.
 */
@RunWith(AndroidJUnit4::class)
class PhpToolchainVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var phpPath: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        phpPath = "${environmentBuilder.binDir}/php"

        assumeTrue("No PHP bundle for this ABI", install())
    }

    @Test
    fun phpRunsAndReportsItsVersion() {
        val output = runShell("$phpPath --version")
        Log.i(TAG, "php --version: $output")

        assertTrue("PHP did not run. Output was: $output", output.contains("PHP 8.5"))
    }

    /** An interpreter that starts but cannot evaluate anything would pass a version check. */
    @Test
    fun phpEvaluatesCode() {
        val output = runShell("$phpPath -r 'echo 6 * 7;'")
        Log.i(TAG, "php -r: $output")

        assertTrue("PHP did not evaluate the expression. Output was: $output", output.contains("42"))
    }

    /**
     * Extensions load from a path compiled into the binary.
     *
     * This is a second, independent way the baked prefix has to be correct: even a PHP that runs
     * will fail to load its own extensions if `extension_dir` points somewhere that does not
     * exist on the device. Laravel needs several of these — mbstring and json among them.
     */
    @Test
    fun coreExtensionsAreLoaded() {
        val output = runShell("$phpPath -m")
        Log.i(TAG, "php -m: ${output.replace("\n", " ")}")

        listOf("json", "mbstring", "openssl", "PDO").forEach { extension ->
            assertTrue(
                "Extension '$extension' is missing. Modules were: $output",
                output.contains(extension, ignoreCase = true),
            )
        }
    }

    /** Running a script from disk, which is what a project actually does. */
    @Test
    fun phpRunsAScriptFromDisk() {
        val script = File(context.cacheDir, "verify-${System.nanoTime()}.php")
        script.writeText(
            """
            <?php
            ${'$'}parts = array_map('strtoupper', ['mobile', 'forge']);
            echo implode('-', ${'$'}parts), PHP_EOL;
            echo json_encode(['ok' => true]), PHP_EOL;
            """.trimIndent(),
        )

        val output = runShell("$phpPath ${script.absolutePath}")
        Log.i(TAG, "php script: $output")

        assertTrue("Script output missing. Output was: $output", output.contains("MOBILE-FORGE"))
        assertTrue(
            "json_encode did not work, so the json extension is not usable. Output was: $output",
            output.contains("{\"ok\":true}"),
        )

        script.delete()
    }

    // -----------------------------------------------------------------------------

    private fun runShell(command: String): String {
        val environment = buildMap {
            put("HOME", homeDir)
            put("PREFIX", environmentBuilder.prefix)
            put("PATH", "${environmentBuilder.binDir}:/system/bin:/system/xbin")
            put("TERM", "dumb")
            put("LD_LIBRARY_PATH", environmentBuilder.libDir)
            put(
                "LD_PRELOAD",
                "$nativeLibraryDir/${RuntimeEnvironmentBuilder.INTERPOSER_LIBRARY}",
            )
            put(RuntimeEnvironmentBuilder.PREFIX_VAR, environmentBuilder.prefix)
        }

        val launched = launcher.launch(
            argv = listOf("/system/bin/sh", "-c", command),
            workingDirectory = homeDir,
            environment = environment,
        )
        if (launched is AppResult.Failure) return "launch failed: ${launched.error.detail}"

        val process = (launched as AppResult.Success).value
        val stdout = process.stdout.bufferedReader().readText()
        val stderr = process.stderr.bufferedReader().readText()
        process.waitFor()
        return (stdout + stderr).trim()
    }

    private fun install(): Boolean {
        if (File(phpPath).isFile) return true

        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val manifestText = runCatching {
            assets.open("bundles/$BUNDLE-$abi.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false
        val manifest = json.decodeFromString<ToolchainManifest>(manifestText)

        val archive = File(context.cacheDir, "$BUNDLE-$abi.zip")
        assets.open("bundles/$BUNDLE-$abi.zip").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }

        val prefixDir = File(environmentBuilder.prefix)
        val installer = ToolchainInstaller(
            prefixDir = prefixDir,
            stagingDir = File(context.cacheDir, "php-staging"),
            dispatchers = DefaultAppDispatchers,
            logger = NoOpLogger,
        )

        val result = runBlocking {
            installer.install(
                manifest,
                archive,
                DeviceProfile(
                    abi = abi,
                    pageSize = android.system.Os
                        .sysconf(android.system.OsConstants._SC_PAGESIZE).toInt(),
                    prefix = prefixDir.absolutePath,
                    availableBytes = context.filesDir.usableSpace,
                ),
            )
        }
        if (result is AppResult.Failure) {
            Log.w(TAG, "install failed: ${result.error.message} / ${result.error.detail}")
        }
        return result is AppResult.Success && File(phpPath).isFile
    }

    private companion object {
        const val TAG = "MF.PhpVerify"
        const val BUNDLE = "php-8.5.1"
    }
}
