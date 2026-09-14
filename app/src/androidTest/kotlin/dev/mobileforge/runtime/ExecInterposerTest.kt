package dev.mobileforge.runtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.exec.ExecCommandBuilder
import dev.mobileforge.runtime.exec.ExecStrategySelector
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
 * VERIFIES THE LD_PRELOAD EXEC INTERPOSER.
 *
 * This exists because of a failure seen in the app's own terminal:
 *
 *     /system/bin/sh: .../usr/bin/mf-doctor: Permission denied
 *
 * `ExecCommandBuilder` rewrites program paths for processes *we* launch, but a shell execs its
 * own children through the kernel's normal path, where W^X refuses them. `libmfexec` overrides
 * `execve()` in every descendant so the rewrite happens regardless of who calls it.
 *
 * **This test must run in the app's own process.** An `adb shell run-as` session has a
 * different SELinux domain and can execute app-data files directly, so it cannot reproduce the
 * constraint — a shell test there passes while the real app fails. Only an instrumented test
 * runs in the domain that actually matters.
 *
 * [shellCannotExecuteWithoutTheInterposer] proves the problem is real.
 * [shellCanExecuteWithTheInterposer]      proves libmfexec fixes it.
 */
@RunWith(AndroidJUnit4::class)
class ExecInterposerTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var commandBuilder: ExecCommandBuilder
    private lateinit var toolPath: String
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        commandBuilder = ExecCommandBuilder(
            strategySelector = ExecStrategySelector(execEnvironment),
            headerReader = AndroidRuntimeFactory.fileHeaderReader(),
        )
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        toolPath = "${environmentBuilder.binDir}/mf-doctor"

        assumeTrue("mf-doctor bundle not available for this ABI", installTool(context))
        Log.i(TAG, "tool=$toolPath libs=$nativeLibraryDir")
    }

    /**
     * The problem: a shell cannot exec our binary on its own.
     *
     * The shell itself runs fine — it lives on the system partition. What fails is the child it
     * tries to exec out of app storage.
     */
    @Test
    fun shellCannotExecuteWithoutTheInterposer() {
        val output = runShell(toolPath, withInterposer = false)
        Log.i(TAG, "without interposer: $output")

        assertTrue(
            "Expected the shell to be refused, but it ran the binary. Output was: $output",
            output.contains("Permission denied", ignoreCase = true) ||
                output.contains("can't execute", ignoreCase = true) ||
                output.contains("not executable", ignoreCase = true),
        )
    }

    /** The fix: with libmfexec preloaded, the same command succeeds. */
    @Test
    fun shellCanExecuteWithTheInterposer() {
        val output = runShell(toolPath, withInterposer = true)
        Log.i(TAG, "with interposer: $output")

        assertTrue(
            "The interposer did not enable execution. Output was: $output",
            output.contains("mf-doctor 1.0.0"),
        )
        assertTrue(
            "The environment did not reach the process. Output was: $output",
            output.contains(environmentBuilder.prefix),
        )
        assertTrue("The child executable path shim is missing: $output",
            output.lineSequence().any { it.trim().startsWith("real path") && it.trim().endsWith(toolPath) })
    }

    // -----------------------------------------------------------------------------

    /** Runs `/system/bin/sh -c <command>`, optionally preloading the interposer. */
    private fun runShell(command: String, withInterposer: Boolean): String {
        val environment = buildMap {
            put("HOME", homeDir)
            put("PREFIX", environmentBuilder.prefix)
            put("PATH", "${environmentBuilder.binDir}:/system/bin:/system/xbin")
            put("TERM", "dumb")
            if (withInterposer) {
                put(
                    "LD_PRELOAD",
                    "$nativeLibraryDir/${RuntimeEnvironmentBuilder.INTERPOSER_LIBRARY}",
                )
                put(RuntimeEnvironmentBuilder.PREFIX_VAR, environmentBuilder.prefix)
            }
        }

        val launched = launcher.launch(
            argv = listOf("/system/bin/sh", "-c", command),
            workingDirectory = homeDir,
            environment = environment,
        )

        if (launched is AppResult.Failure) {
            return "launch failed: ${launched.error.detail}"
        }

        val process = (launched as AppResult.Success).value
        val stdout = process.stdout.bufferedReader().readText()
        val stderr = process.stderr.bufferedReader().readText()
        process.waitFor()
        return (stdout + stderr).trim()
    }

    /** Installs mf-doctor from test assets so the test is self-contained. */
    private fun installTool(context: android.content.Context): Boolean {
        if (File(toolPath).isFile) return true

        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val base = "bundles/mf-doctor-1.0.0-$abi"

        val manifestJson = runCatching {
            assets.open("$base.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return false

        val archive = File(context.cacheDir, "mf-doctor-interposer-$abi.zip")
        assets.open("$base.zip").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }

        val prefixDir = File(environmentBuilder.prefix)
        val manifest = json.decodeFromString<ToolchainManifest>(manifestJson)
            .copy(prefix = prefixDir.absolutePath)

        val installer = ToolchainInstaller(
            prefixDir = prefixDir,
            stagingDir = File(context.cacheDir, "interposer-staging"),
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
        return result is AppResult.Success && File(toolPath).isFile
    }

    private companion object {
        const val TAG = "MF.InterposerVerify"
    }
}
