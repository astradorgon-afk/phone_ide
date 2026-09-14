package dev.mobileforge.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mobileforge.runtime.exec.ExecEnvironment
import dev.mobileforge.runtime.exec.ExecStrategy
import dev.mobileforge.runtime.exec.ExecStrategySelector
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ON-DEVICE VERIFICATION OF THE PHASE 2 PLATFORM ASSUMPTION.
 *
 * The entire development runtime rests on one claim: an app targeting a current SDK cannot
 * execute a binary in its own data directory, but *can* execute it by handing it to the system
 * linker. That claim came from research (docs/adr/ADR-002-runtime-strategy.md). This test is
 * what turns it from researched into measured.
 *
 * It deliberately uses only files guaranteed to exist on every Android device — no bootstrapped
 * toolchain required — by copying `/system/bin/sh`, a dynamically linked ELF, into app storage
 * and attempting to run the copy.
 *
 *   [directExecIsBlocked]         proves the PROBLEM is real on this device
 *   [systemLinkerExecSucceeds]    proves the SOLUTION works on this device
 *
 * If the first test ever starts passing-by-not-failing on a future Android, the workaround has
 * become unnecessary. If the second fails, Phase 2 does not work on that device and we need
 * the fallback chain in ADR-002. Either way, this is the test that tells us.
 *
 * Run with:  ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExecMechanismVerificationTest {

    private lateinit var context: Context
    private lateinit var environment: ExecEnvironment
    private lateinit var copiedShell: File

    private val systemShell = File("/system/bin/sh")

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        environment = ExecEnvironment(
            deviceSdkInt = Build.VERSION.SDK_INT,
            appTargetSdk = context.applicationInfo.targetSdkVersion,
            primaryAbi = Build.SUPPORTED_ABIS.first(),
            filesDir = context.filesDir.absolutePath,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        )

        // A real dynamically linked ELF, in app storage, with the execute bit set.
        // The execute bit is the point: SELinux, not file permissions, is what blocks this.
        copiedShell = File(context.filesDir, "verify-sh").apply {
            delete()
            systemShell.copyTo(this, overwrite = true)
            setReadable(true)
            setExecutable(true)
        }

        report(
            "device=API ${environment.deviceSdkInt} target=API ${environment.appTargetSdk} " +
                "abi=${environment.primaryAbi} wx=${environment.enforcesWriteXorExecute} " +
                "linker=${environment.systemLinkerPath}",
        )
    }

    /**
     * The problem, demonstrated.
     *
     * Executing the copy directly should be refused by SELinux with EACCES on any device where
     * W^X applies. On a device where it does not apply, this is reported rather than failed —
     * the point is to record what the platform actually does, not to force an outcome.
     */
    @Test
    fun directExecIsBlocked() {
        val outcome = run(listOf(copiedShell.absolutePath, "-c", "echo $TOKEN"))
        report("direct exec -> $outcome")

        if (!environment.enforcesWriteXorExecute) {
            report("W^X does not apply here; direct execution is legitimately allowed.")
            return
        }

        when (outcome) {
            is Outcome.Failed -> assertTrue(
                "Expected a permission denial, got: ${outcome.message}",
                outcome.message.contains("error=13", ignoreCase = true) ||
                    outcome.message.contains("permission denied", ignoreCase = true) ||
                    outcome.message.contains("EACCES", ignoreCase = true),
            )

            is Outcome.Succeeded -> fail(
                "Direct execution from app storage SUCCEEDED on API " +
                    "${environment.deviceSdkInt} targeting ${environment.appTargetSdk}. " +
                    "The W^X assumption in ADR-002 does not hold on this device — the " +
                    "system-linker workaround may no longer be necessary.",
            )
        }
    }

    /**
     * The solution, demonstrated.
     *
     * The same file, invoked through the system linker, must run. This is the single assertion
     * the whole development runtime depends on.
     */
    @Test
    fun systemLinkerExecSucceeds() {
        val strategy = ExecStrategySelector(environment).strategyFor(copiedShell.absolutePath)
        report("selected strategy: $strategy")

        val argv = when (strategy) {
            is ExecStrategy.SystemLinker ->
                listOf(strategy.linkerPath, copiedShell.absolutePath, "-c", "echo $TOKEN")

            ExecStrategy.Direct ->
                listOf(copiedShell.absolutePath, "-c", "echo $TOKEN")

            is ExecStrategy.Unsupported ->
                fail("No supported strategy on this device: ${strategy.reason}").let { return }
        }

        val outcome = run(argv)
        report("linker exec -> $outcome")

        when (outcome) {
            is Outcome.Succeeded -> assertTrue(
                "Process ran but did not produce the expected output: '${outcome.output}'",
                outcome.output.contains(TOKEN),
            )

            is Outcome.Failed -> fail(
                "Execution from app storage FAILED on API ${environment.deviceSdkInt} " +
                    "(abi ${environment.primaryAbi}) using $strategy: ${outcome.message}. " +
                    "The Phase 2 runtime cannot work on this device as designed; see the " +
                    "fallback chain in ADR-002.",
            )
        }
    }

    /** Sanity check: the launcher itself works, so a failure above is about app storage. */
    @Test
    fun systemBinaryRunsDirectly() {
        val outcome = run(listOf(systemShell.absolutePath, "-c", "echo $TOKEN"))
        report("system exec -> $outcome")

        assertTrue(
            "Could not run /system/bin/sh at all: $outcome",
            outcome is Outcome.Succeeded && outcome.output.contains(TOKEN),
        )
    }

    // -----------------------------------------------------------------------------

    private sealed interface Outcome {
        data class Succeeded(val output: String, val exitCode: Int) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun run(argv: List<String>): Outcome = try {
        val process = ProcessBuilder(argv)
            .directory(context.filesDir)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            Outcome.Failed("timed out after ${TIMEOUT_SECONDS}s")
        } else {
            Outcome.Succeeded(output.trim(), process.exitValue())
        }
    } catch (e: Exception) {
        Outcome.Failed(e.message ?: e.javaClass.simpleName)
    }

    /** Logged so the result is recoverable from logcat even when the test passes. */
    private fun report(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "MF.ExecVerify"
        const val TOKEN = "mobileforge_exec_ok"
        const val TIMEOUT_SECONDS = 10L
    }
}
