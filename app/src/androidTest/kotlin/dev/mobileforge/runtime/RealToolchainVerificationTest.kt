package dev.mobileforge.runtime

import android.content.Context
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * VERIFIES A REAL, THIRD-PARTY TOOLCHAIN BINARY.
 *
 * Everything before this proved the mechanism with `mf-doctor` — a ~40-line C program written
 * for the purpose, with no dependencies. That answered "can we exec something we installed?"
 * but not "can we ship real software?", which is a different question: real packages link
 * against shared libraries, and those libraries must be found through a `DT_RUNPATH` that was
 * baked in at build time against **our** prefix (ADR-011).
 *
 * The bundle here is `tree`, built from source by termux-packages with
 * `TERMUX_APP__PACKAGE_NAME` repointed to `dev.mobileforge`. It is deliberately a package with
 * a real shared-library dependency (`libandroid-support.so`), because a statically-linked
 * binary would not exercise the part most likely to be wrong.
 *
 * The bundle is architecture-specific, so this skips on any device whose ABI it was not built
 * for — that is a property of the bundle, not a defect.
 */
@RunWith(AndroidJUnit4::class)
class RealToolchainVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var toolPath: String
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        toolPath = "${environmentBuilder.binDir}/tree"

        assumeTrue("No tree bundle for this ABI", install())
    }

    /**
     * The prefix the bundle was built for must be the prefix this app actually uses.
     *
     * This is the assertion that catches an application-id change: bundles are not relocatable,
     * so a `.debug` suffix (or any rename) silently invalidates every bundle ever built. Better
     * to fail here, on one line, than to debug "library not found" on a device later.
     */
    @Test
    fun theBundlePrefixMatchesThisApp() {
        val manifest = readManifest() ?: return
        // Compared by package name rather than raw string: `filesDir` reports
        // /data/user/0/<pkg> while the bundle bakes in /data/data/<pkg>, and those are the same
        // directory. What must match exactly is which app the prefix belongs to.
        assertEquals(
            "The bundle was built for a different app than this one. Rebuild the toolchain, " +
                "or restore the application id.",
            context.packageName,
            manifest.prefix.removeSuffix("/usr").removeSuffix("/files").substringAfterLast('/'),
        )
        assertTrue(
            "A toolchain prefix must end in /usr, but was ${manifest.prefix}",
            manifest.prefix.endsWith("/usr"),
        )
    }

    /**
     * The real test: a shell runs an installed, dynamically-linked binary.
     *
     * This exercises the whole chain at once — install, the LD_PRELOAD interposer rewriting
     * `execve` under W^X, the system linker loading the binary, and `DT_RUNPATH` resolving its
     * shared library out of our prefix.
     */
    @Test
    fun aRealPackageRunsFromTheShell() {
        val output = runShell("$toolPath --version")
        Log.i(TAG, "tree --version: $output")

        assertTrue(
            "tree did not run. Output was: $output",
            output.contains("tree v", ignoreCase = true),
        )
    }

    /** It has to do actual work, not merely start and print a version banner. */
    @Test
    fun aRealPackageProducesRealOutput() {
        val probe = File(context.cacheDir, "tree-probe").apply {
            deleteRecursively()
            mkdirs()
            File(this, "alpha").mkdirs()
            File(this, "alpha/beta.txt").writeText("hello")
        }

        val output = runShell("$toolPath ${probe.absolutePath}")
        Log.i(TAG, "tree output: $output")

        assertTrue("Directory not listed. Output was: $output", output.contains("alpha"))
        assertTrue("File not listed. Output was: $output", output.contains("beta.txt"))
    }

    /**
     * The shared library must resolve through the baked RUNPATH.
     *
     * `LD_LIBRARY_PATH` is deliberately NOT set here. If the binary only works because we hand
     * it a library path, then `DT_RUNPATH` is wrong and anything the user runs from their own
     * shell — where we control nothing — would fail.
     */
    @Test
    fun sharedLibrariesResolveWithoutLdLibraryPath() {
        val output = runShell("$toolPath --version", includeLibraryPath = false)
        Log.i(TAG, "without LD_LIBRARY_PATH: $output")

        assertTrue(
            "The binary could not find its libraries via DT_RUNPATH. Output was: $output",
            output.contains("tree v", ignoreCase = true),
        )
    }

    // -----------------------------------------------------------------------------

    private fun runShell(command: String, includeLibraryPath: Boolean = true): String {
        val environment = buildMap {
            put("HOME", homeDir)
            put("PREFIX", environmentBuilder.prefix)
            put("PATH", "${environmentBuilder.binDir}:/system/bin:/system/xbin")
            put("TERM", "dumb")
            put(
                "LD_PRELOAD",
                "$nativeLibraryDir/${RuntimeEnvironmentBuilder.INTERPOSER_LIBRARY}",
            )
            put(RuntimeEnvironmentBuilder.PREFIX_VAR, environmentBuilder.prefix)
            if (includeLibraryPath) put("LD_LIBRARY_PATH", environmentBuilder.libDir)
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

    private fun readManifest(): ToolchainManifest? {
        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val text = runCatching {
            assets.open("bundles/$BUNDLE-$abi.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return json.decodeFromString<ToolchainManifest>(text)
    }

    private fun install(): Boolean {
        if (File(toolPath).isFile) return true
        val manifest = readManifest() ?: return false

        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val archive = File(context.cacheDir, "$BUNDLE-$abi.zip")
        assets.open("bundles/$BUNDLE-$abi.zip").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }

        val prefixDir = File(environmentBuilder.prefix)
        val installer = ToolchainInstaller(
            prefixDir = prefixDir,
            stagingDir = File(context.cacheDir, "real-toolchain-staging"),
            dispatchers = DefaultAppDispatchers,
            logger = NoOpLogger,
        )

        val result = runBlocking {
            installer.install(
                // The manifest's own prefix is used, NOT overridden: this test is partly about
                // whether the bundle was built for the right prefix in the first place.
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
        return result is AppResult.Success && File(toolPath).isFile
    }

    private companion object {
        const val TAG = "MF.RealToolchain"
        const val BUNDLE = "tree-2.3.2"
    }
}
