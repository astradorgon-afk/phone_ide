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
import dev.mobileforge.runtime.toolchain.ToolchainCompatibility
import dev.mobileforge.runtime.toolchain.ToolchainInstaller
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/**
 * VERIFIES THE BASE BUNDLE, ON DEVICE.
 *
 * Bundles all extract into one shared `$PREFIX`, which is what lets a tool bundle rely on a
 * `base` bundle having put a shell and coreutils there. That split took `tree` from 19 MB — its
 * own private copy of bash and coreutils — to 56 KB.
 *
 * The saving is only real if the sharing actually works on a device, so this installs both and
 * checks the things that would silently be wrong otherwise: that `base` provides
 * `$PREFIX/bin/sh`, that a tool installed afterwards can use it, and that the dependency is
 * declared rather than assumed.
 */
@RunWith(AndroidJUnit4::class)
class BaseBundleVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
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

        assumeTrue("No base bundle for this ABI", install(BASE))
    }

    /**
     * The dependency is declared, not implied.
     *
     * If `tree` stopped declaring `requires: [base]`, everything below would still pass while
     * the install order became luck — so the declaration is asserted first.
     */
    @Test
    fun theToolBundleDeclaresItsDependencyOnBase() {
        val tree = readManifest(TREE) ?: return
        assertTrue("tree does not declare a dependency on base", tree.requires.contains("base"))

        // And the gate agrees: without base installed, this bundle is refused.
        assertTrue(
            "missingRequirements did not report base",
            ToolchainCompatibility.missingRequirements(tree, emptySet()).contains("base"),
        )
        assertTrue(
            "missingRequirements reported base even though it is installed",
            ToolchainCompatibility.missingRequirements(tree, setOf("base")).isEmpty(),
        )
    }

    /**
     * `$PREFIX/bin/sh` exists because base put it there.
     *
     * No termux `.deb` provides it — Termux's bootstrap archive does, and we ship no bootstrap.
     * This is the file whose absence made npm unrunnable, so it is checked as a link to a real
     * shell rather than merely present.
     */
    @Test
    fun baseProvidesAWorkingShell() {
        val sh = File("${environmentBuilder.binDir}/sh")

        assertTrue("base did not install bin/sh", sh.exists())
        assertTrue("bin/sh should be a link to the real shell", Files.isSymbolicLink(sh.toPath()))

        val output = runShell("${sh.absolutePath} -c 'echo SHELL_FROM_BASE'")
        Log.i(TAG, "base sh: $output")
        assertTrue("The bundled shell did not run. Output was: $output",
            output.contains("SHELL_FROM_BASE"))
    }

    /** coreutils is the other half of what base exists to provide. */
    @Test
    fun baseProvidesCoreutils() {
        val env = File("${environmentBuilder.binDir}/env")
        assertTrue("base did not install bin/env", env.exists())

        val output = runShell("${env.absolutePath} echo ENV_WORKS")
        Log.i(TAG, "base env: $output")
        assertTrue("env did not run. Output was: $output", output.contains("ENV_WORKS"))
    }

    /**
     * The point of the whole exercise: a tiny tool bundle that works because base is there.
     *
     * `tree` ships 56 KB and no shell of its own. If it runs, the sharing works.
     */
    @Test
    fun aMinimalToolBundleWorksOnTopOfBase() {
        assumeTrue("No tree bundle for this ABI", install(TREE))

        val treePath = "${environmentBuilder.binDir}/tree"
        assertTrue("tree was not installed", File(treePath).isFile)

        val output = runShell("$treePath --version")
        Log.i(TAG, "tree --version: $output")
        assertTrue("tree did not run. Output was: $output", output.contains("tree v2.3.2"))
    }

    /** A 56 KB bundle must not have smuggled a shell in after all. */
    @Test
    fun theToolBundleDoesNotCarryItsOwnShell() {
        val tree = readManifest(TREE) ?: return

        assertFalse(
            "tree ships its own bash, which is what the base split was meant to stop",
            tree.components.any { it.name == "bash" || it.name == "coreutils" },
        )
        assertTrue(
            "tree is larger than a bundle with no shell should be: ${tree.sizeBytes} bytes",
            tree.sizeBytes < 1_000_000,
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

    private fun readManifest(bundle: String): ToolchainManifest? {
        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val text = runCatching {
            assets.open("bundles/$bundle-$abi.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return null
        return json.decodeFromString<ToolchainManifest>(text)
    }

    private fun install(bundle: String): Boolean {
        val manifest = readManifest(bundle) ?: return false
        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets

        val archive = File(context.cacheDir, "$bundle-$abi.zip")
        assets.open("bundles/$bundle-$abi.zip").use { input ->
            archive.outputStream().use { output -> input.copyTo(output) }
        }

        val prefixDir = File(environmentBuilder.prefix)
        val result = runBlocking {
            ToolchainInstaller(
                prefixDir = prefixDir,
                stagingDir = File(context.cacheDir, "base-staging"),
                dispatchers = DefaultAppDispatchers,
                logger = NoOpLogger,
            ).install(
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
            Log.w(TAG, "install of $bundle failed: ${result.error.detail}")
        }
        return result is AppResult.Success
    }

    private companion object {
        const val TAG = "MF.BaseBundle"
        const val BASE = "base-1.0.0"
        const val TREE = "tree-2.3.2"
    }
}
