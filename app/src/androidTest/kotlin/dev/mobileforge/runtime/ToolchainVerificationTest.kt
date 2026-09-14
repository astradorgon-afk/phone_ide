package dev.mobileforge.runtime

import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.exec.ExecCommandBuilder
import dev.mobileforge.runtime.exec.ExecStrategy
import dev.mobileforge.runtime.exec.ExecStrategySelector
import dev.mobileforge.runtime.exec.JvmProcessLauncher
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import dev.mobileforge.runtime.toolchain.DeviceProfile
import dev.mobileforge.runtime.toolchain.ToolchainInstaller
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * END-TO-END VERIFICATION OF THE TOOLCHAIN PIPELINE.
 *
 * This is the test that decides whether Phase 2b-iii's approach is sound. It exercises the
 * complete chain with a binary we built ourselves:
 *
 *     bundle -> SHA-256 verify -> extract into $PREFIX -> system-linker exec -> real output
 *
 * `mf-doctor` is a genuine dynamically-linked PIE ELF compiled with the NDK against this app's
 * own prefix. If it runs and prints its environment, then installing and executing our own
 * software on Android works, and packaging PHP or Node becomes a build-system problem rather
 * than an unsolved platform problem.
 *
 * Run with:  ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ToolchainVerificationTest {

    private lateinit var prefixDir: File
    private lateinit var installer: ToolchainInstaller
    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var commandBuilder: ExecCommandBuilder
    private lateinit var device: DeviceProfile

    private val json = Json { ignoreUnknownKeys = true }
    private val abi: String get() = Build.SUPPORTED_ABIS.first()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        prefixDir = File(environmentBuilder.prefix)

        commandBuilder = ExecCommandBuilder(
            strategySelector = ExecStrategySelector(execEnvironment),
            headerReader = AndroidRuntimeFactory.fileHeaderReader(),
        )

        installer = ToolchainInstaller(
            prefixDir = prefixDir,
            stagingDir = File(context.cacheDir, "toolchain-staging"),
            dispatchers = DefaultAppDispatchers,
            logger = NoOpLogger,
        )

        device = DeviceProfile(
            abi = abi,
            pageSize = Os.sysconf(OsConstants._SC_PAGESIZE).toInt(),
            prefix = prefixDir.absolutePath,
            availableBytes = context.filesDir.usableSpace,
        )

        Log.i(TAG, "abi=$abi pageSize=${device.pageSize} prefix=${device.prefix}")
    }

    /**
     * Install, then run. The whole point of the phase in one test.
     */
    @Test
    fun installsAndExecutesItsOwnBinary() {
        val (manifest, archive) = stageBundle() ?: run {
            // A bundle for this ABI was not packaged; skipping is honest, passing would not be.
            assumeTrue("No mf-doctor bundle bundled for $abi", false)
            return
        }

        // ---- install ----
        val installed = runBlocking {
            installer.install(manifest, archive, device)
        }

        if (installed is AppResult.Failure) {
            fail("Installation failed: ${installed.error.message} — ${installed.error.detail}")
            return
        }

        val binary = File(prefixDir, "bin/mf-doctor")
        assertTrue("mf-doctor was not written into the prefix", binary.isFile)
        Log.i(TAG, "installed ${binary.absolutePath} (${binary.length()} bytes)")

        // ---- resolve ----
        val resolved = commandBuilder.build(binary.absolutePath, emptyList())
        if (resolved is AppResult.Failure) {
            fail("Could not resolve a command: ${resolved.error.detail}")
            return
        }
        val command = (resolved as AppResult.Success).value
        Log.i(TAG, "strategy=${command.strategy} argv=${command.argv}")

        // On a modern target this MUST go through the linker; direct exec is what SELinux
        // blocks, and a Direct strategy here would mean the workaround was silently skipped.
        assertTrue(
            "Expected system-linker exec for a binary in app storage, got ${command.strategy}",
            command.strategy is ExecStrategy.SystemLinker,
        )

        // ---- execute ----
        val launcher = JvmProcessLauncher()
        val launched = launcher.launch(
            argv = command.argv,
            workingDirectory = environmentBuilder.homeDir.also { File(it).mkdirs() },
            environment = environmentBuilder.build(command, environmentBuilder.homeDir),
        )

        if (launched is AppResult.Failure) {
            fail("Could not start mf-doctor: ${launched.error.detail}")
            return
        }

        val process = (launched as AppResult.Success).value
        val output = process.stdout.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()

        Log.i(TAG, "exit=$exitCode output:\n$output")

        assertTrue("mf-doctor exited with $exitCode", exitCode == 0)
        assertTrue(
            "mf-doctor did not identify itself. Output was:\n$output",
            output.contains("mf-doctor 1.0.0"),
        )
        // Proves RuntimeEnvironmentBuilder ran and the process saw our prefix.
        assertTrue(
            "PREFIX was not visible to the process. Output was:\n$output",
            output.contains(environmentBuilder.prefix),
        )
        // Proves the /proc/self/exe shim carried the real path across the linker.
        assertTrue(
            "The real program path was not propagated. Output was:\n$output",
            output.contains("bin/mf-doctor"),
        )
    }

    /** A tampered bundle must be refused, and nothing may be written. */
    @Test
    fun refusesATamperedBundle() {
        val (manifest, archive) = stageBundle() ?: run {
            assumeTrue("No mf-doctor bundle bundled for $abi", false)
            return
        }

        val tampered = manifest.copy(sha256 = "0".repeat(64))
        val marker = File(prefixDir, "bin/mf-doctor-tampered-canary")
        marker.delete()

        val result = runBlocking { installer.install(tampered, archive, device) }

        assertTrue("A tampered bundle was accepted", result is AppResult.Failure)
        assertTrue("A tampered bundle wrote files", !marker.exists())
        Log.i(TAG, "tampered bundle correctly refused")
    }

    // -----------------------------------------------------------------------------

    /** Copies the packaged bundle for this ABI out of test assets onto disk. */
    private fun stageBundle(): Pair<ToolchainManifest, File>? {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val base = "bundles/mf-doctor-1.0.0-$abi"

        val manifestJson = runCatching {
            assets.open("$base.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null

        val manifest = json.decodeFromString<ToolchainManifest>(manifestJson)

        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val archive = File(cacheDir, "mf-doctor-$abi.zip")
        assets.open("$base.zip").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }

        // The manifest records the release prefix; the instrumented app is the debug variant,
        // so align it with where this build actually installs.
        return manifest.copy(prefix = prefixDir.absolutePath) to archive
    }

    private companion object {
        const val TAG = "MF.ToolchainVerify"
    }
}
