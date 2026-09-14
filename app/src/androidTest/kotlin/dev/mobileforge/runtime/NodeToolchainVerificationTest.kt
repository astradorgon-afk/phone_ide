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
 * VERIFIES NODE — and with it the `/proc/self/exe` shim.
 *
 * Node is the case ADR-011 predicted would need `TERMUX_EXEC__PROC_SELF_EXE`. It locates its own
 * installation by reading `/proc/self/exe`, which under linker-exec points at
 * `/system/bin/linker64` rather than at node — so without the shim it either fails outright or
 * silently resolves its libraries and npm paths to the wrong place.
 *
 * Node 26.4.0 could not be built: its bundled V8 fails to compile against the build container's
 * toolchain (`roots.h:504: error: expected identifier`), which is a host incompatibility and not
 * a resource limit. This bundle is `nodejs-lts` 24.18.0.
 */
@RunWith(AndroidJUnit4::class)
class NodeToolchainVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var nodePath: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        nodePath = "${environmentBuilder.binDir}/node"

        assumeTrue("No Node bundle for this ABI", install())
    }

    @Test
    fun nodeRunsAndReportsItsVersion() {
        val output = runShell("$nodePath --version")
        Log.i(TAG, "node --version: $output")

        assertTrue("Node did not run. Output was: $output", output.contains("v24."))
    }

    /** A JIT that starts but cannot evaluate would still pass a version check. */
    @Test
    fun nodeEvaluatesCode() {
        val output = runShell("$nodePath -e 'console.log(6 * 7)'")
        Log.i(TAG, "node -e: $output")

        assertTrue("Node did not evaluate. Output was: $output", output.contains("42"))
    }

    /**
     * The `/proc/self/exe` shim.
     *
     * `process.execPath` is how Node reports where it thinks it lives. Under linker-exec the
     * kernel's answer is `/system/bin/linker64`; the shim must make it node's real path instead,
     * or every path Node derives from its own location — npm, its module search — is wrong.
     */
    @Test
    fun nodeKnowsItsOwnLocation() {
        val output = runShell("$nodePath -e 'console.log(process.execPath)'")
        Log.i(TAG, "process.execPath: $output")

        assertTrue(
            "Node resolved its own path to the linker instead of itself. Output was: $output",
            !output.contains("linker"),
        )
        assertTrue(
            "process.execPath is not inside our prefix. Output was: $output",
            output.contains("/usr/bin/node") || output.contains("/usr/lib/nodejs"),
        )
    }

    /** Exercises the filesystem and module system, not just the parser. */
    @Test
    fun nodeRunsAScriptAndUsesCoreModules() {
        val script = File(context.cacheDir, "verify-${System.nanoTime()}.js")
        script.writeText(
            """
            const os = require('os');
            const path = require('path');
            console.log('platform:' + os.platform());
            console.log(path.join('mobile', 'forge'));
            console.log(JSON.stringify({ ok: true }));
            """.trimIndent(),
        )

        val output = runShell("$nodePath ${script.absolutePath}")
        Log.i(TAG, "node script: ${output.replace("\n", " | ")}")

        assertTrue("os module failed. Output was: $output", output.contains("platform:android") ||
            output.contains("platform:linux"))
        assertTrue("path module failed. Output was: $output", output.contains("mobile/forge"))
        assertTrue("JSON failed. Output was: $output", output.contains("{\"ok\":true}"))

        script.delete()
    }

    /**
     * npm has to be in the bundle, and has to run.
     *
     * It very nearly was not. termux builds `nodejs-lts` with `--without-npm` and lists npm
     * under `Recommends`, while the bundle's dependency closure follows `Depends` only — so the
     * first Node bundle contained `node` and `corepack` and no npm at all. Nothing failed; the
     * package manager was simply absent, which is the kind of gap that only shows up when
     * someone tries to install a dependency.
     *
     * npm is also a Node *script*, so running it exercises the interpreter finding its own
     * modules — a different path from `node -e`.
     */
    @Test
    fun npmIsPresentAndRuns() {
        val npm = File("${environmentBuilder.binDir}/npm")
        assertTrue("npm is not in the bundle", npm.exists())

        val output = runShell("${npm.absolutePath} --version")
        Log.i(TAG, "npm --version: $output")

        assertTrue(
            "npm did not run. Output was: $output",
            Regex("""\d+\.\d+\.\d+""").containsMatchIn(output),
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

    private fun install(): Boolean {
        if (File(nodePath).isFile) return true

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
            stagingDir = File(context.cacheDir, "node-staging"),
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
        return result is AppResult.Success && File(nodePath).isFile
    }

    private companion object {
        const val TAG = "MF.NodeVerify"
        const val BUNDLE = "nodejs-lts-24.18.0"
    }
}
