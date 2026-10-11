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
 * VERIFIES OPENCODE — the one tool that cannot use the normal exec path at all.
 *
 * Every other tool in the toolchain is an Android/bionic binary, so `libmfexec` can hand it to
 * the system linker. opencode is different in two ways at once: it is a musl-linked, non-PIE
 * (ET_EXEC) binary, so bionic's linker cannot load it, and Android refuses to exec anything under
 * `$PREFIX` (W^X from targetSdk 29), so the kernel cannot either. The only way through is to run
 * the *musl dynamic loader* out of the app's native library directory — the one readable location
 * where exec is still permitted — and let it map opencode out of `$PREFIX`.
 *
 * This test exercises exactly that: `bin/opencode` is a shell wrapper, the wrapper execs
 * `libldmusl.so` from the native library directory, the loader maps `lib/opencode/opencode` and
 * its libraries, and Bun boots. Anything broken along that chain reads as "can't execute" rather
 * than a version, which is why the assertion checks the reported version.
 *
 * The wrapper also preloads `lib/opencode/libsigsys-shim.so`. Android's app seccomp policy is an
 * allowlist, and Bun calls syscalls it predates (`close_range`) plus legacy path syscalls
 * (`open`/`stat`/`access`/`mkdir`, which native code is expected to call as `*at`). Both trap as
 * SIGSYS and would kill Bun with "Bad system call"; the shim's SIGSYS handler forwards those to
 * their allowed equivalents so the process can continue.
 */
@RunWith(AndroidJUnit4::class)
class OpenCodeToolchainVerificationTest {

    private lateinit var context: Context
    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var openCodePath: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        openCodePath = "${environmentBuilder.binDir}/opencode"

        assumeTrue("No OpenCode bundle for this ABI", install(OPENCODE_BUNDLE, "opencode"))
    }

    /**
     * The launcher must be present in the APK. Without it the wrapper exits 127 and reports a
     * missing launcher — a broken build rather than a broken bundle.
     */
    @Test
    fun muslLoaderIsPackagedInTheNativeLibraryDirectory() {
        val loader = File(nativeLibraryDir, LOADER_NAME)
        assertTrue(
            "The musl loader is not in the native library directory ($nativeLibraryDir). " +
                "It must be packaged as jniLibs/<abi>/$LOADER_NAME.",
            loader.isFile,
        )
        assertTrue("The packaged loader is not executable", loader.canExecute())
    }

    /** Diagnostic: isolate the loader from the wrapper. Not an assertion. */
    @Test
    fun diagnose() {
        val loader = "$nativeLibraryDir/$LOADER_NAME"
        val libDir = "${environmentBuilder.libDir}/opencode"
        val command = buildString {
            appendLine("echo PRELOAD=[\$LD_PRELOAD]")
            appendLine("unset LD_PRELOAD LD_LIBRARY_PATH")
            appendLine("echo NOW=[\$LD_PRELOAD]")
            appendLine("echo ---LOADER-ONLY---")
            appendLine("\"$loader\"; echo LOADER_ONLY_EXIT=\$?")
            appendLine("echo ---DIRECT---")
            appendLine("\"$loader\" --library-path \"$libDir\" \"$libDir/opencode\" --version; echo DIRECT_EXIT=\$?")
            appendLine("echo ---VIA-WRAPPER---")
            appendLine("\"$openCodePath\" --version; echo WRAPPER_EXIT=\$?")
        }
        Log.i(TAG, "DIAGNOSE:\n" + runShell(command))
    }

    @Test
    fun opencodeRunsAndReportsItsVersion() {
        val output = runShell("$openCodePath --version")
        Log.i(TAG, "opencode --version: $output")

        assertTrue(
            "OpenCode did not run. Output was: $output",
            output.contains("1.18.35"),
        )
    }

    /**
     * Booting the CLI's command surface proves the whole binary loaded, not merely a version
     * string embedded near the entry point.
     */
    @Test
    fun opencodeListsItsCommands() {
        val output = runShell("$openCodePath --help")
        Log.i(TAG, "opencode --help: ${output.take(300)}")

        assertTrue(
            "The opencode CLI did not start. Output was: $output",
            output.contains("opencode") && output.contains("run"),
        )
    }

    // -----------------------------------------------------------------------------

    private fun runShell(command: String): String {
        // The production environment, minus the terminal. In particular this includes
        // MOBILEFORGE_NATIVE_LIB_DIR, which the wrapper uses to find the musl loader.
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
            put(RuntimeEnvironmentBuilder.NATIVE_LIB_DIR_VAR, nativeLibraryDir)
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
        const val TAG = "MF.OpenCodeVerify"
        const val OPENCODE_BUNDLE = "opencode-1.18.35"
        const val LOADER_NAME = "libldmusl.so"
    }
}
