package dev.mobileforge.runtime.api

/**
 * The Phase 1 [RuntimeCapabilityProbe].
 *
 * This is NOT a fake backend. It is a truthful implementation of a real contract: the
 * development runtime does not exist in this build, and this probe says exactly that, with the
 * phase that delivers it. The diagnostics screen renders the result verbatim.
 *
 * The alternative — showing a green check for PHP, or a "Run" button that throws — is the
 * dishonesty the brief rules out. Reporting "not implemented" is the correct answer to the
 * question "is PHP available?" in a build where it is not.
 *
 * It is replaced wholesale in Phase 2 by a probe that actually execs the system linker; nothing
 * else in the app changes, because callers only ever saw [RuntimeCapabilityProbe].
 */
class NotImplementedRuntimeProbe(
    private val phase: String = "Phase 2 (Development Runtime)",
) : RuntimeCapabilityProbe {

    override suspend fun probeAll(): Map<DevTool, ToolStatus> =
        DevTool.entries.associateWith { ToolStatus.NotImplementedYet(phase) }

    override suspend fun probe(tool: DevTool): ToolStatus = ToolStatus.NotImplementedYet(phase)
}
