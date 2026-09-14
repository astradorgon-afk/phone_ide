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
import java.nio.file.Files

/**
 * VERIFIES GIT — the first tool the product actually needs.
 *
 * `tree` proved a real package with one shared library could be installed and run. Git is a
 * harder and more representative case, and it is what Phase 4 depends on:
 *
 *  - it links against curl, OpenSSL, PCRE2, zlib and more, all built from source for our prefix;
 *  - it is **mostly symlinks** — 986 of them in this bundle, ~50 pointing at one 3.6 MB binary.
 *    Because `zip` follows links and stores a copy of each target, packaging it naively produced
 *    a 302 MB bundle from a 24 MB package. Links are now declared in the manifest and created by
 *    the installer, which brought it to 20.9 MB;
 *  - it does real work with subprocesses and the filesystem, so "it printed a version" is not
 *    enough — this makes an actual commit and reads it back.
 */
@RunWith(AndroidJUnit4::class)
class GitToolchainVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String
    private lateinit var gitPath: String

    private val json = Json { ignoreUnknownKeys = true }
    private val launcher = JvmProcessLauncher()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }
        gitPath = "${environmentBuilder.binDir}/git"

        assumeTrue("No git bundle for this ABI", install())
    }

    @Test
    fun gitRunsAndReportsItsVersion() {
        val output = runShell("$gitPath --version")
        Log.i(TAG, "git --version: $output")

        assertTrue("git did not run. Output was: $output", output.contains("git version 2.55"))
    }

    /**
     * The declared symlinks must exist as links, not as copies.
     *
     * If these came back as regular files the bundle would still work, but it would be the
     * 302 MB one — so this asserts the mechanism, not just the outcome.
     */
    @Test
    fun symlinkedSubcommandsAreLinksAndAreRunnable() {
        val link = File("${environmentBuilder.binDir}/git-receive-pack")

        assertTrue("git-receive-pack is missing", link.exists())
        assertTrue(
            "git-receive-pack should be a symlink, not a copy",
            Files.isSymbolicLink(link.toPath()),
        )

        // Invoked with no arguments so it prints its own usage. NOT `--help`: that hands the
        // request to `man`, which Android does not have, so the test would fail for a reason
        // that has nothing to do with the link.
        val output = runShell("${link.absolutePath} 2>&1 | head -3")
        Log.i(TAG, "git-receive-pack: $output")
        assertTrue(
            "The symlinked subcommand did not execute. Output was: $output",
            output.contains("receive-pack", ignoreCase = true) ||
                output.contains("usage", ignoreCase = true),
        )
    }

    /**
     * The real test: a complete init → add → commit → log cycle.
     *
     * This exercises git spawning its own subprocesses through the interposer, writing to the
     * filesystem, and reading its own repository back.
     */
    @Test
    fun gitCreatesARepositoryAndCommits() {
        val repo = File(context.cacheDir, "git-verify-${System.nanoTime()}").apply { mkdirs() }
        File(repo, "hello.txt").writeText("MobileForge")

        val script = listOf(
            "cd ${repo.absolutePath}",
            "$gitPath init -q",
            "$gitPath config user.email verify@mobileforge.invalid",
            "$gitPath config user.name Verification",
            "$gitPath add hello.txt",
            "$gitPath commit -q -m 'first commit'",
            "$gitPath log --oneline",
        ).joinToString(" && ")

        val output = runShell(script)
        Log.i(TAG, "git workflow: $output")

        assertTrue(
            "The commit was not created. Output was: $output",
            output.contains("first commit"),
        )
        assertTrue(
            "No .git directory was created",
            File(repo, ".git/HEAD").isFile,
        )
        // The commit can succeed while git still fails to spawn a helper — auto-maintenance
        // runs after the commit is written. An earlier version of this test only checked the
        // commit and so passed while "cannot exec 'maintenance': Permission denied" went by
        // unnoticed in the same output.
        assertTrue(
            "git could not exec a helper process. Output was: $output",
            !output.contains("cannot exec", ignoreCase = true),
        )

        repo.deleteRecursively()
    }

    /**
     * Git must be able to exec its own subcommands by PATH lookup.
     *
     * This is the case that exposed a real gap: `libmfexec` overrode `execve` and assumed the
     * rest of the family came with it, because bionic implements them in terms of it. It does —
     * but that internal call never crosses the PLT, so `LD_PRELOAD` never saw `execvp`, and git
     * reported `fatal: cannot exec 'maintenance': Permission denied` while the kernel refused
     * the binary under W^X.
     *
     * `git maintenance` is used because git resolves it through `execvp` rather than running it
     * in-process.
     */
    @Test
    fun gitCanExecItsOwnSubcommandsThroughPathLookup() {
        val repo = File(context.cacheDir, "git-exec-${System.nanoTime()}").apply { mkdirs() }
        val output = runShell(
            "cd ${repo.absolutePath} && $gitPath init -q && $gitPath maintenance register 2>&1",
        )
        Log.i(TAG, "git maintenance: $output")

        assertTrue(
            "git could not exec a subcommand found on PATH. Output was: $output",
            !output.contains("cannot exec", ignoreCase = true) &&
                !output.contains("Permission denied", ignoreCase = true),
        )

        repo.deleteRecursively()
    }

    /**
     * An unambiguous `execvp` probe, using a system binary we do not control.
     *
     * `env` resolves its program through `execvp`, so this exercises libc's PATH search inside a
     * process that is not ours, reaching one of our installed binaries. Unlike a git subcommand
     * — which git may handle in-process as a builtin and so prove nothing — there is no way for
     * this to succeed without the interposer's `execvp` override doing the work.
     */
    @Test
    fun aSystemBinaryCanExecOurToolchainThroughExecvp() {
        val output = runShell("env git --version")
        Log.i(TAG, "env git --version: $output")

        assertTrue(
            "execvp from a system binary did not reach our toolchain. Output was: $output",
            output.contains("git version 2.55"),
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
        if (File(gitPath).isFile) return true

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
            stagingDir = File(context.cacheDir, "git-staging"),
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
        return result is AppResult.Success && File(gitPath).isFile
    }

    private companion object {
        const val TAG = "MF.GitVerify"
        const val BUNDLE = "git-2.55.0"
    }
}
