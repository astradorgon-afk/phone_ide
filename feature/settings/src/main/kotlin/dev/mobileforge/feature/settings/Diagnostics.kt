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
) : DiagnosticsProvider {

    override suspend fun collect(): DiagnosticsReport = withContext(dispatchers.io) {
        val tools = runtimeProbe.probeAll().map { (tool, status) ->
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
                SubsystemReport(
                    "Toolchain (PHP, Node, Git)",
                    SubsystemState.NotImplemented,
                    "Phase 2b — binaries are not bundled yet",
                ),
                SubsystemReport(
                    "Interactive terminal",
                    SubsystemState.NotImplemented,
                    "Phase 2b — needs a PTY, which pipes cannot provide",
                ),
                SubsystemReport("Git", SubsystemState.NotImplemented, "Phase 4"),
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
