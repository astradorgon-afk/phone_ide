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
 * VERIFIES COMPOSER — the tool Phase 3 Laravel creation is built on.
 *
 * This exercises the longest execution chain in the product: a shell looks up
 * `composer` on PATH, the kernel follows its `#!$PREFIX/bin/php` shebang, the
 * `libmfexec` interposer reroutes both through the system linker past W^X, PHP
 * boots, and the phar runs. Any broken link reads as "can't execute" rather than a
 * Composer error, which is why the assertions check real Composer output and not
 * merely a zero exit.
 */
@RunWith(AndroidJUnit4::class)
class ComposerToolchainVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var composerPath: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        composerPath = "${environmentBuilder.binDir}/composer"

        assumeTrue("No PHP bundle for this ABI", install(PHP_BUNDLE, "php"))
        assumeTrue("No Composer bundle for this ABI", install(COMPOSER_BUNDLE, "composer"))
    }

    @Test
    fun composerRunsAndReportsItsVersion() {
        val output = runShell("$composerPath --version")
        Log.i(TAG, "composer --version: $output")

        assertTrue(
            "Composer did not run. Output was: $output",
            output.contains("Composer version 2."),
        )
    }

    /**
     * The command the Laravel wizard depends on must exist. Listing commands boots the
     * whole phar — argument parsing, console wiring — rather than just the version stub.
     */
    @Test
    fun composerListsCreateProject() {
        val output = runShell("$composerPath list --no-ansi")
        Log.i(TAG, "composer list: ${output.take(300)}")

        assertTrue(
            "create-project is not offered. Output was: $output",
            output.contains("create-project"),
        )
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

    private fun install(bundle: String, binary: String): Boolean {
        if (File(environmentBuilder.binDir, binary).isFile) return true

        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val manifestText = runCatching {
            assets.open("bundles/$bundle-$abi.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false
        val manifest = json.decodeFromString<ToolchainManifest>(manifestText)

        val archive = File(context.cacheDir, "$bundle-$abi.zip")
        assets.open("bundles/$bundle-$abi.zip").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }

        val prefixDir = File(environmentBuilder.prefix)
        val installer = ToolchainInstaller(
            prefixDir = prefixDir,
            stagingDir = File(context.cacheDir, "$bundle-staging"),
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
        return result is AppResult.Success && File(environmentBuilder.binDir, binary).isFile
    }

    private companion object {
        const val TAG = "MF.ComposerVerify"
        const val PHP_BUNDLE = "php-8.5.1"
        const val COMPOSER_BUNDLE = "composer-2.10.3"
    }
}
