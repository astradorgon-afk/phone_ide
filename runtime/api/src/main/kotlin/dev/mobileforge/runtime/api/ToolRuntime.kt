package dev.mobileforge.runtime.api

import dev.mobileforge.core.common.AppResult

/**
 * A development tool the IDE can drive (PHP, Composer, Node, npm, Git, Artisan, Vite).
 *
 * Phase 1 has no implementations. The point of shipping this contract now is that the
 * diagnostics screen can honestly report every tool as "not installed", with the reason,
 * rather than showing a green check for something that does not exist.
 */
interface ToolRuntime {
    val tool: DevTool

    /** Probes the device. Must never throw; an absent tool is a normal result, not an error. */
    suspend fun probe(): ToolStatus

    /** Prepared invocation. Callers never construct a command string themselves. */
    suspend fun spec(arguments: List<String>, workingDirectory: String): AppResult<ProcessSpec>
}

enum class DevTool(val displayName: String, val versionArguments: List<String>) {
    Php("PHP", listOf("--version")),
    Composer("Composer", listOf("--version")),
    Node("Node.js", listOf("--version")),
    Npm("npm", listOf("--version")),
    Git("Git", listOf("--version")),
    Shell("Shell", listOf("--version")),
}

/**
 * The result of probing for a tool.
 *
 * [Unsupported] carries a reason because "not available" without a cause is exactly the
 * unhelpful error the brief rules out. On this platform the reason is usually a concrete
 * runtime constraint (ABI mismatch, page size, missing linker support), and the user deserves
 * to see it.
 */
sealed interface ToolStatus {

    data class Available(
        val version: String,
        val absolutePath: String,
    ) : ToolStatus

    /** Present on device but unusable, with a specific cause. */
    data class Unsupported(
        val reason: String,
        val recovery: String?,
    ) : ToolStatus

    data object NotInstalled : ToolStatus

    /**
     * The subsystem that would provide this tool is not built yet.
     *
     * This is the honest Phase 1 answer for every tool, and it is a distinct state from
     * [NotInstalled] so the UI can say "coming in Phase 2" rather than implying the user
     * could fix it by installing something today.
     */
    data class NotImplementedYet(val phase: String) : ToolStatus
}

/**
 * Reports which runtime capabilities this device and build actually have.
 *
 * Phase 1 provides a real implementation of this that answers [ToolStatus.NotImplementedYet]
 * for everything. That is a truthful implementation of a real contract, not a fake backend:
 * the answer it gives is correct.
 */
interface RuntimeCapabilityProbe {
    suspend fun probeAll(): Map<DevTool, ToolStatus>
    suspend fun probe(tool: DevTool): ToolStatus
}
