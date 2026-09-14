package dev.mobileforge.runtime.exec

import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.common.AppResult
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.api.ToolStatus
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Reports what this device can actually run — by running it.
 *
 * Replaces `NotImplementedRuntimeProbe` from Phase 1. Every answer here is a measurement, not
 * an inference: a tool is [ToolStatus.Available] only if it was executed and printed a version.
 *
 * Also performs a **self-test of the execution mechanism itself** ([selfTest]). That matters
 * because the whole runtime rests on a platform behaviour we cannot assume: if system-linker
 * exec ever stops working on a future Android, this is what says so plainly rather than
 * leaving every tool mysteriously broken.
 */
class SystemLinkerRuntimeProbe(
    private val environment: ExecEnvironment,
    private val environmentBuilder: RuntimeEnvironmentBuilder,
    private val launcher: ProcessLauncher,
    private val commandBuilder: ExecCommandBuilder,
    private val dispatchers: AppDispatchers,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : RuntimeCapabilityProbe {

    override suspend fun probeAll(): Map<DevTool, ToolStatus> =
        DevTool.entries.associateWith { probe(it) }

    override suspend fun probe(tool: DevTool): ToolStatus = withContext(dispatchers.io) {
        val path = pathFor(tool)
        if (!File(path).exists()) return@withContext ToolStatus.NotInstalled

        when (val version = runForOutput(path, tool.versionArguments)) {
            is ProbeOutcome.Output -> ToolStatus.Available(
                version = firstLine(version.text),
                absolutePath = path,
            )

            is ProbeOutcome.Failed -> ToolStatus.Unsupported(
                reason = version.reason,
                recovery = version.recovery,
            )
        }
    }

    /**
     * Verifies the execution mechanism end to end, using a program guaranteed to exist.
     *
     * `/system/bin/sh` is on the read-only system partition, so it runs directly and proves the
     * launcher works at all. Then, if a bootstrapped shell exists in app storage, that is run
     * too — and *that* is the real test, because it is the case W^X would block.
     */
    suspend fun selfTest(): ExecSelfTest = withContext(dispatchers.io) {
        val systemShell = runForOutput("/system/bin/sh", listOf("-c", "echo $SELF_TEST_TOKEN"))
        val systemOk = systemShell is ProbeOutcome.Output &&
            systemShell.text.contains(SELF_TEST_TOKEN)

        val bundledShell = "${environmentBuilder.binDir}/sh"
        val bundledExists = File(bundledShell).exists()
        val bundled = if (bundledExists) {
            runForOutput(bundledShell, listOf("-c", "echo $SELF_TEST_TOKEN"))
        } else {
            null
        }

        ExecSelfTest(
            strategy = ExecStrategySelector(environment)
                .strategyFor("${environmentBuilder.binDir}/sh"),
            systemExecWorks = systemOk,
            appStorageExecTested = bundledExists,
            appStorageExecWorks = bundled is ProbeOutcome.Output &&
                bundled.text.contains(SELF_TEST_TOKEN),
            failureDetail = (bundled as? ProbeOutcome.Failed)?.reason
                ?: (systemShell as? ProbeOutcome.Failed)?.reason,
            enforcesWriteXorExecute = environment.enforcesWriteXorExecute,
            linkerPath = environment.systemLinkerPath,
            primaryAbi = environment.primaryAbi,
        )
    }

    private fun pathFor(tool: DevTool): String = when (tool) {
        DevTool.Shell -> "${environmentBuilder.binDir}/sh"
        DevTool.Php -> "${environmentBuilder.binDir}/php"
        DevTool.Composer -> "${environmentBuilder.binDir}/composer"
        DevTool.Node -> "${environmentBuilder.binDir}/node"
        DevTool.Npm -> "${environmentBuilder.binDir}/npm"
        DevTool.Git -> "${environmentBuilder.binDir}/git"
    }

    /**
     * Runs a command and collects its output, bounded in both time and size.
     *
     * A probe must never hang the diagnostics screen: a broken binary that blocks forever is
     * exactly the kind of thing we are probing for, so the timeout is part of the contract.
     */
    private fun runForOutput(path: String, args: List<String>): ProbeOutcome {
        val resolved = when (val built = commandBuilder.build(path, args)) {
            is AppResult.Failure -> return ProbeOutcome.Failed(
                reason = built.error.detail ?: built.error.message,
                recovery = built.error.recovery,
            )
            is AppResult.Success -> built.value
        }

        val environmentVars = environmentBuilder.build(resolved, environmentBuilder.homeDir)

        val launched = when (
            val result = launcher.launch(resolved.argv, systemWritableCwd(), environmentVars)
        ) {
            is AppResult.Failure -> return ProbeOutcome.Failed(
                reason = result.error.detail ?: result.error.message,
                recovery = result.error.recovery,
            )
            is AppResult.Success -> result.value
        }

        return try {
            val text = StringBuilder()
            val reader = Thread {
                runCatching {
                    launched.stdout.bufferedReader().use { r ->
                        var read = 0
                        while (read < MAX_PROBE_OUTPUT) {
                            val line = r.readLine() ?: break
                            text.appendLine(line)
                            read += line.length
                        }
                    }
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMs)

            if (launched.isAlive()) {
                launched.destroy(force = true)
                ProbeOutcome.Failed(
                    reason = "'${path.substringAfterLast('/')}' did not respond within " +
                        "${timeoutMs}ms and was stopped.",
                    recovery = "The installed build may be incompatible with this device.",
                )
            } else if (text.isBlank()) {
                ProbeOutcome.Failed(
                    reason = "'${path.substringAfterLast('/')}' exited without producing output.",
                    recovery = "Reinstall the tool from Settings.",
                )
            } else {
                ProbeOutcome.Output(text.toString())
            }
        } finally {
            if (launched.isAlive()) launched.destroy(force = true)
        }
    }

    /** Probes run from HOME, which always exists once the runtime is initialised. */
    private fun systemWritableCwd(): String {
        val home = File(environmentBuilder.homeDir)
        return if (home.isDirectory) home.absolutePath else "/"
    }

    private fun firstLine(text: String) = text.lineSequence().firstOrNull()?.trim().orEmpty()

    private sealed interface ProbeOutcome {
        data class Output(val text: String) : ProbeOutcome
        data class Failed(val reason: String, val recovery: String?) : ProbeOutcome
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        const val MAX_PROBE_OUTPUT = 8_000
        const val SELF_TEST_TOKEN = "mobileforge_exec_ok"
    }
}

/**
 * The result of testing the execution mechanism itself.
 *
 * Rendered verbatim on the diagnostics screen. When someone reports "nothing runs on my
 * phone", this is the single artefact that says whether the platform assumption held.
 */
data class ExecSelfTest(
    val strategy: ExecStrategy,
    /** Did a known-good system binary run at all? */
    val systemExecWorks: Boolean,
    /** Was a binary in app storage available to test? False before the toolchain is installed. */
    val appStorageExecTested: Boolean,
    /** Did it run? This is the answer to "does system-linker exec work on this device". */
    val appStorageExecWorks: Boolean,
    val failureDetail: String?,
    val enforcesWriteXorExecute: Boolean,
    val linkerPath: String,
    val primaryAbi: String,
) {
    val summary: String
        get() = when {
            !systemExecWorks ->
                "Cannot start any process on this device."
            !appStorageExecTested ->
                "Process execution works. The toolchain is not installed yet, so " +
                    "execution from app storage has not been tested."
            appStorageExecWorks ->
                "Verified: programs in app storage run via ${strategyLabel()}."
            else ->
                "Programs in app storage could NOT be started" +
                    (failureDetail?.let { " — $it" } ?: ".")
        }

    private fun strategyLabel(): String = when (strategy) {
        is ExecStrategy.SystemLinker -> "the system linker ($linkerPath)"
        ExecStrategy.Direct -> "direct execution"
        is ExecStrategy.Unsupported -> "no supported strategy"
    }
}
