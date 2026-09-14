package dev.mobileforge.ai.api

import dev.mobileforge.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * An agent supplies behaviour: planning, tool selection and an execution loop.
 * It is composed with an [AiProvider], which supplies model access.
 *
 * This split is what makes "OpenCode + Claude", "built-in agent + Gemini" and
 * "Claude Code CLI with its own auth" all expressible without special cases.
 *
 * PHASE 1: contract only. No agent runs.
 */
interface AiAgent {
    val id: AgentId
    val displayName: String
    val backend: AgentBackend

    /** Reports whether this agent can actually run here. Never throws. */
    suspend fun probe(): AgentAvailability

    suspend fun startSession(request: SessionRequest): AppResult<AgentSession>
}

@JvmInline
value class AgentId(val value: String)

sealed interface AgentBackend {
    /** Implemented in-process against an [AiProvider]. Works with no external CLI. */
    data object BuiltIn : AgentBackend

    /**
     * An external command-line agent (opencode, claude, codex, aider).
     * These are Node/Python programs never tested on Android; availability is DETECTED,
     * never assumed. See RISK-007.
     */
    data class ExternalCli(val executable: String) : AgentBackend
}

sealed interface AgentAvailability {
    data class Available(val version: String?) : AgentAvailability
    data object NotInstalled : AgentAvailability

    /** Present but unusable here, with a specific, actionable reason. Never a silent failure. */
    data class Incompatible(val reason: String, val recovery: String?) : AgentAvailability

    /** Honest Phase 1 answer for every agent. */
    data class NotImplementedYet(val phase: String) : AgentAvailability
}

interface AgentSession {
    val id: String
    val events: Flow<AgentEvent>
    val usage: Flow<TokenUsage>

    suspend fun send(instruction: String): AppResult<Unit>

    /** Answers a pending permission request. Only the user may call this path. */
    suspend fun respondToPermission(requestId: String, decision: PermissionDecision): AppResult<Unit>

    suspend fun cancel(): AppResult<Unit>
}

data class SessionRequest(
    val workspaceId: String,
    val modelId: String,
    val autonomy: AutonomyMode,
    val limits: SessionLimits,
)

/**
 * Autonomy raises a ceiling; it never overrides a denied grant, and it never removes
 * confirmation for destructive or network operations. There is no mode granting system-wide
 * access. See docs/adr/ADR-005-agent-permissions.md.
 */
enum class AutonomyMode {
    /** Answers questions. No tools at all. */
    Manual,

    /** Reads context and proposes diffs. No writes. */
    Assisted,

    /** Applies changes after per-operation permission. */
    Agent,

    /** Acts within a pre-approved set, inside project boundaries, under [SessionLimits]. */
    Autonomous,
}

/** Hard stops. On breach the session pauses with a stated reason; the user resumes deliberately. */
data class SessionLimits(
    val maxRuntimeMs: Long = 10 * 60 * 1000,
    val maxToolCalls: Int = 50,
    val maxFilesChanged: Int = 25,
    val maxCommands: Int = 10,
)

sealed interface AgentEvent {
    data class Thinking(val summary: String) : AgentEvent
    data class TextDelta(val text: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val callId: String, val result: ToolResult) : AgentEvent

    /** The agent is blocked until the user answers. It cannot self-approve. */
    data class PermissionRequested(val request: PermissionRequest) : AgentEvent

    /** Taken before a batch of changes so a run can be inspected, accepted or reverted whole. */
    data class CheckpointCreated(val checkpointId: String, val label: String) : AgentEvent

    data class Paused(val reason: String) : AgentEvent
    data class Completed(val filesChanged: Int) : AgentEvent
    data class Failed(val error: dev.mobileforge.core.common.AppError) : AgentEvent
}

// ---------------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------------

data class ToolSchema(
    val name: String,
    val description: String,
    /** JSON Schema for the arguments. */
    val parametersJson: String,
    /** Permissions required BEFORE this tool may run. Empty means the tool is inert. */
    val requiredPermissions: Set<AgentPermission>,
    val timeoutMs: Long = 30_000,
)

data class ToolCall(
    val id: String,
    val toolName: String,
    val argumentsJson: String,
)

data class ToolResult(
    val callId: String,
    val success: Boolean,
    val output: String,
    /**
     * Tool output is untrusted input and always re-enters the model at
     * [TrustTier.ToolOutput]. It never gains the authority of a user instruction.
     */
    val trustTier: TrustTier = TrustTier.ToolOutput,
)

/**
 * Capabilities an agent can be granted. Deny by default.
 *
 * [InstallPackages] is deliberately NOT a subset of [ExecuteCommand]: a user who allowed
 * "run commands" has not consented to executing arbitrary package-maintainer lifecycle
 * scripts (RISK-015).
 *
 * [NetworkRequest] is deliberately separate from model access: letting an agent talk to a
 * model is not letting it talk to the internet. That distinction is the core defence against
 * exfiltration instructions planted in a repository (RISK-012).
 */
enum class AgentPermission {
    ReadFile,
    WriteFile,
    DeleteFile,
    ExecuteCommand,
    InstallPackages,
    NetworkRequest,
    GitCommit,
    GitPush,

    /** Never grantable as "always allow" - each access is asked for individually. */
    AccessSecrets,
}

data class PermissionRequest(
    val id: String,
    val permission: AgentPermission,
    /** Exactly what will happen, shown verbatim to the user. */
    val description: String,
    /** The literal command or path, so the user reviews the real thing, not a paraphrase. */
    val subject: String,
    val risk: RiskLevel,
)

enum class RiskLevel { Low, Medium, High }

sealed interface PermissionDecision {
    data object AllowOnce : PermissionDecision
    data object AllowForSession : PermissionDecision

    /** Persisted per workspace. Unavailable for [AgentPermission.AccessSecrets]. */
    data object AllowAlwaysInProject : PermissionDecision
    data object Reject : PermissionDecision
}
