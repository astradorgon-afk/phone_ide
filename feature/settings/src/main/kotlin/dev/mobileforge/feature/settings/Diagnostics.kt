package dev.mobileforge.feature.settings

import android.content.Context
import android.os.Build
import dev.mobileforge.core.common.AppDispatchers
import dev.mobileforge.core.security.SecretRedactor
import dev.mobileforge.runtime.api.DevTool
import dev.mobileforge.runtime.api.RuntimeCapabilityProbe
import dev.mobileforge.runtime.api.ToolStatus
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gathers what the app can truthfully report about the device and its own state.
 *
 * The rule this file exists to hold: **report only what was measured.** A tool is reported with
 * a version only after it was executed and printed one; printing a plausible PHP version we
 * never asked for would be exactly the fabrication the brief rules out.
 *
 * Since Phase 2 the report also carries the execution self-test, which states whether running
 * programs from app storage actually works on THIS device rather than assuming ADR-002
 * generalises to it.
 */
interface DiagnosticsProvider {
    suspend fun collect(): DiagnosticsReport
}

class DefaultDiagnosticsProvider(
    private val context: Context,
    private val runtimeProbe: RuntimeCapabilityProbe,
    private val workspacesRoot: File,
    private val dispatchers: AppDispatchers,
    /**
     * Runs the execution-mechanism self-test and returns a one-line summary.
     *
     * Injected as a lambda so this module keeps depending only on :runtime:api and never on
     * the exec implementation. When someone reports "nothing runs on my phone", this line is
     * the single most useful thing in the export.
     */
    private val execSelfTest: suspend () -> String = { "Not tested." },
    /**
     * One-line WebView engine summary.
     *
     * Injected as a lambda so this module does not depend on :feature:editor. It is here
     * because device testing showed the editor silently failing on an old WebView, and the
     * engine version is not something a user can otherwise discover.
     */
    private val webViewStatus: () -> String = { "Not checked." },
    /**
     * Whether the native PTY library loaded: true, false, or null if nobody checked.
     *
     * Injected for the same reason as [webViewStatus] — this module does not depend on
     * `:runtime:pty`. Null is a real answer and is reported as "not checked", never as working.
     */
    private val terminalSupported: () -> Boolean? = { null },
) : DiagnosticsProvider {

    override suspend fun collect(): DiagnosticsReport = withContext(dispatchers.io) {
        val probed = runtimeProbe.probeAll()
        val tools = probed.map { (tool, status) ->
            ToolReport(name = tool.displayName, status = status.describe())
        }

        DiagnosticsReport(
            platform = listOf(
                DiagnosticEntry("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
                DiagnosticEntry("Device", "${Build.MANUFACTURER} ${Build.MODEL}"),
                DiagnosticEntry("Primary ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"),
                DiagnosticEntry("All ABIs", Build.SUPPORTED_ABIS.joinToString(", ")),
                DiagnosticEntry("WebView", webViewStatus()),
            ),
            storage = buildStorageEntries(),
            tools = tools,
            execution = execSelfTest(),
            subsystems = listOf(
                // Stated as facts about THIS build, so nobody has to guess what works.
                SubsystemReport(
                    "Editor",
                    if (webViewSupported()) SubsystemState.Available else SubsystemState.Degraded,
                    if (webViewSupported()) {
                        "Monaco, bundled offline"
                    } else {
                        "WebView engine too old — update Android System WebView"
                    },
                ),
                SubsystemReport("File system", SubsystemState.Available, "App-managed storage"),
                SubsystemReport("Workspace trust", SubsystemState.Available, "Enforced"),
                SubsystemReport(
                    "Process execution",
                    SubsystemState.Available,
                    "System-linker exec — see the Execution section above",
                ),
                // Both of these read "not implemented" for a long time after they were built,
                // which is the one thing this screen must never do. They are now derived from
                // what was measured, so they cannot go stale the same way.
                toolchainSubsystem(probed),
                terminalSubsystem(terminalSupported()),
                SubsystemReport(
                    "Git",
                    SubsystemState.NotImplemented,
                    "In-app Git is Phase 4 — the git command itself runs in the terminal once " +
                        "it is installed",
                ),
                SubsystemReport("AI providers", SubsystemState.NotImplemented, "Phase 5"),
                SubsystemReport("Extensions", SubsystemState.NotImplemented, "Phase 8"),
            ),
        )
    }

    /** Cheap textual check so the subsystem row agrees with the platform row. */
    private fun webViewSupported(): Boolean = !webViewStatus().contains("too old", ignoreCase = true)

    private fun buildStorageEntries(): List<DiagnosticEntry> {
        val filesDir = context.filesDir
        // `usableSpace` on purpose, not `getAllocatableBytes`. Diagnostics reports what is
        // free right now; allocatable space includes caches Android would have to evict, which
        // is the right number for gating an install (see AndroidToolchainManager) and the wrong
        // one for telling a user how full their device is.
        @Suppress("UsableSpace")
        val usableBytes = runCatching { filesDir.usableSpace }.getOrDefault(-1L)
        val projectCount = runCatching {
            workspacesRoot.listFiles()?.count { it.isDirectory } ?: 0
        }.getOrDefault(0)

        return listOf(
            DiagnosticEntry("Workspace root", workspacesRoot.absolutePath),
            DiagnosticEntry("Projects on disk", projectCount.toString()),
            DiagnosticEntry(
                "Free space",
                if (usableBytes >= 0) "${usableBytes / 1024 / 1024} MB" else "unavailable",
            ),
        )
    }
}

/** The tools a developer reaches for — as opposed to the shell, which every device has. */
private val TOOLCHAIN_TOOLS =
    listOf(DevTool.Php, DevTool.Composer, DevTool.Node, DevTool.Npm, DevTool.Git)

private const val TOOLCHAIN_ROW = "Toolchain (PHP, Node, Git)"
private const val TERMINAL_ROW = "Interactive terminal"

/**
 * The toolchain row, from what the probe actually found.
 *
 * "Available" means every tool answered a version query. Anything less is [SubsystemState.Degraded]
 * and names what is missing, because "something is missing" is not an actionable sentence. A tool
 * the probe reports as unsupported counts as missing here; the reason is in the tool list above.
 */
internal fun toolchainSubsystem(probed: Map<DevTool, ToolStatus>): SubsystemReport {
    val ready = TOOLCHAIN_TOOLS.filter { probed[it] is ToolStatus.Available }
    val missing = TOOLCHAIN_TOOLS - ready.toSet()
    fun names(tools: List<DevTool>) = tools.joinToString(", ") { it.displayName }
    return when {
        missing.isEmpty() ->
            SubsystemReport(TOOLCHAIN_ROW, SubsystemState.Available, "${names(ready)} all run")

        ready.isEmpty() -> SubsystemReport(
            TOOLCHAIN_ROW,
            SubsystemState.Degraded,
            "Nothing installed yet — install it from Settings → Toolchains",
        )

        else -> SubsystemReport(
            TOOLCHAIN_ROW,
            SubsystemState.Degraded,
            "Installed: ${names(ready)} · Missing: ${names(missing)} — add them from " +
                "Settings → Toolchains",
        )
    }
}

/** The terminal row. Unknown is reported as unknown, never as working. */
internal fun terminalSubsystem(supported: Boolean?): SubsystemReport = when (supported) {
    true -> SubsystemReport(
        TERMINAL_ROW,
        SubsystemState.Available,
        "Real pseudo-terminal (forkpty)",
    )

    false -> SubsystemReport(
        TERMINAL_ROW,
        SubsystemState.Degraded,
        "The native terminal library could not be loaded for this CPU architecture",
    )

    null -> SubsystemReport(TERMINAL_ROW, SubsystemState.Degraded, "Not checked")
}

private fun ToolStatus.describe(): String = when (this) {
    is ToolStatus.Available -> version
    is ToolStatus.Unsupported -> "Unsupported — $reason"
    ToolStatus.NotInstalled -> "Not installed"
    is ToolStatus.NotImplementedYet -> "Not implemented — $phase"
}

data class DiagnosticsReport(
    val platform: List<DiagnosticEntry>,
    val storage: List<DiagnosticEntry>,
    /** Result of the execution-mechanism self-test. The load-bearing line (ADR-009). */
    val execution: String,
    val tools: List<ToolReport>,
    val subsystems: List<SubsystemReport>,
) {
    /**
     * Plain-text export for bug reports.
     *
     * Passed through [SecretRedactor] before it leaves the app. Nothing here is *supposed* to
     * contain a credential, but a diagnostics export is precisely the artefact users paste into
     * public issue trackers, so it gets the backstop anyway (RISK-013).
     */
    fun toShareableText(): String = SecretRedactor.redact(
        buildString {
            appendLine("MobileForge Diagnostics")
            appendLine("=======================")
            appendLine()
            appendLine("Platform")
            platform.forEach { appendLine("  ${it.label}: ${it.value}") }
            appendLine()
            appendLine("Storage")
            storage.forEach { appendLine("  ${it.label}: ${it.value}") }
            appendLine()
            appendLine("Execution")
            appendLine("  " + execution)
            appendLine()
            appendLine("Development tools")
            tools.forEach { appendLine("  ${it.name}: ${it.status}") }
            appendLine()
            appendLine("Subsystems")
            subsystems.forEach { appendLine("  ${it.name}: ${it.state.label} — ${it.detail}") }
        },
    )
}

data class DiagnosticEntry(val label: String, val value: String)

data class ToolReport(val name: String, val status: String)

data class SubsystemReport(
    val name: String,
    val state: SubsystemState,
    val detail: String,
)

enum class SubsystemState(val label: String) {
    Available("Available"),
    Degraded("Degraded"),
    NotImplemented("Not implemented"),
}

/** Exposed so the UI can iterate the tool list without importing :runtime:api directly. */
val allDevTools: List<DevTool> = DevTool.entries
