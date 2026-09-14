package dev.mobileforge.runtime

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.core.common.DefaultAppDispatchers
import dev.mobileforge.core.common.NoOpLogger
import dev.mobileforge.runtime.exec.RuntimeEnvironmentBuilder
import dev.mobileforge.runtime.pty.NativePtyLauncher
import dev.mobileforge.runtime.pty.TerminalSession
import dev.mobileforge.runtime.toolchain.DeviceProfile
import dev.mobileforge.runtime.toolchain.ToolchainInstaller
import dev.mobileforge.runtime.toolchain.ToolchainManifest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * VERIFIES THE TOOLCHAIN THROUGH THE PRODUCT'S OWN TERMINAL.
 *
 * Every other toolchain test launches processes with `JvmProcessLauncher` — pipes, and an
 * environment the test assembles itself. That proves the binaries work; it does not prove the
 * thing a user actually touches works, and the two differ in ways that matter:
 *
 *  - the terminal runs its shell under a real PTY, so programs take their interactive paths;
 *  - the environment comes from `RuntimeEnvironmentBuilder` via the app's own wiring, not from
 *    a map written in a test;
 *  - output passes through `TerminalEmulator`, so anything the shell emits has to survive
 *    escape-sequence parsing to be seen at all.
 *
 * A toolchain that works in the harness but not at the prompt is not a working toolchain.
 */
@RunWith(AndroidJUnit4::class)
class ToolchainInTerminalVerificationTest {

    private lateinit var environmentBuilder: RuntimeEnvironmentBuilder
    private lateinit var shellCommandFactory: ShellCommandFactory
    private lateinit var context: Context
    private lateinit var homeDir: String
    private lateinit var nativeLibraryDir: String

    private val launcher = NativePtyLauncher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var session: TerminalSession? = null
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val execEnvironment = AndroidRuntimeFactory.execEnvironment(context)

        environmentBuilder = RuntimeEnvironmentBuilder(execEnvironment)
        shellCommandFactory = ShellCommandFactory(
            commandBuilder = dev.mobileforge.runtime.exec.ExecCommandBuilder(
                strategySelector =
                    dev.mobileforge.runtime.exec.ExecStrategySelector(execEnvironment),
                headerReader = AndroidRuntimeFactory.fileHeaderReader(),
            ),
            environmentBuilder = environmentBuilder,
        )
        nativeLibraryDir = execEnvironment.nativeLibraryDir
        homeDir = environmentBuilder.homeDir.also { File(it).mkdirs() }

        assumeTrue("Native PTY not available for this ABI", launcher.isSupported)
        assumeTrue("No PHP bundle for this ABI", install("php-8.5.1", "bin/php"))
    }

    @After
    fun tearDown() {
        session?.close()
        session = null
        scope.cancel()
    }

    /**
     * `php` typed at the prompt, resolved through PATH.
     *
     * Not an absolute path: this is the case that needs the interposer to be reached by the
     * shell's own PATH lookup, which is how a person would actually run it.
     */
    @Test
    fun phpRunsFromThePromptByName() {
        val terminal = openTerminal()
        val output = send(terminal, "php -r 'echo \"PHP_FROM_PROMPT_\" . (6*7);'\n", settleMs = 5_000)
        Log.i(TAG, "prompt output: ${output.takeLast(200)}")

        assertTrue(
            "php did not run from the prompt. Screen was:\n$output",
            output.contains("PHP_FROM_PROMPT_42"),
        )
    }

    /** The environment the app builds must be what the shell actually sees. */
    @Test
    fun thePrefixIsVisibleAtThePrompt() {
        val terminal = openTerminal()
        val output = send(terminal, "echo \"prefix=\$PREFIX\"\n", settleMs = 3_000)
        Log.i(TAG, "prefix output: ${output.takeLast(160)}")

        assertTrue(
            "PREFIX was not set in the shell. Screen was:\n$output",
            output.contains("prefix=${environmentBuilder.prefix}") ||
                output.contains("prefix=/data/data/${context.packageName}/files/usr"),
        )
    }

    // -----------------------------------------------------------------------------

    private fun openTerminal(): TerminalSession {
        val terminal = TerminalSession(
            launcher = launcher,
            dispatchers = DefaultAppDispatchers,
            logger = NoOpLogger,
            scope = scope,
        ).also { session = it }

        // The app's own factory, not a hand-written argv: this is the seam that resolves the
        // shell and assembles the environment in production, so testing anything else would be
        // testing a path no user takes.
        val command = shellCommandFactory.create(homeDir)
        assumeTrue("No shell could be resolved on this device", command is AppResult.Success)
        val shell = (command as AppResult.Success).value

        runBlocking {
            terminal.start(
                argv = shell.argv,
                workingDirectory = shell.workingDirectory,
                environment = shell.environment,
            )
        }
        Thread.sleep(SETTLE_MS)
        return terminal
    }

    /** Types into the terminal and returns what the emulator ended up displaying. */
    private fun send(terminal: TerminalSession, input: String, settleMs: Long): String {
        runBlocking { terminal.send(input) }
        Thread.sleep(settleMs)
        return terminal.state.value.lines.joinToString("\n") { row ->
            row.joinToString("") { it.text }.trimEnd()
        }
    }

    private fun install(bundle: String, probe: String): Boolean {
        val prefixDir = File(environmentBuilder.prefix)
        if (File(prefixDir, probe).isFile) return true

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

        val result = runBlocking {
            ToolchainInstaller(
                prefixDir = prefixDir,
                stagingDir = File(context.cacheDir, "terminal-staging"),
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
            Log.w(TAG, "install failed: ${result.error.detail}")
        }
        return result is AppResult.Success && File(prefixDir, probe).isFile
    }

    private companion object {
        const val TAG = "MF.TerminalToolchain"
        const val SETTLE_MS = 1_500L
    }
}
