package dev.mobileforge.core.model

/**
 * A workspace is the unit the IDE opens. It owns a root directory and a trust decision.
 *
 * [rootPath] is always an absolute, canonical path inside app-managed storage. Files reached
 * through the Storage Access Framework are represented by [ExternalWorkspaceRef] instead,
 * because a SAF tree URI is not a filesystem path and pretending otherwise is how path
 * handling rots.
 */
data class Workspace(
    val id: WorkspaceId,
    val name: String,
    val rootPath: String,
    val trust: WorkspaceTrust,
    val framework: DetectedFramework,
    val createdAtEpochMs: Long,
    val lastOpenedAtEpochMs: Long,
    val lastActiveFile: String? = null,
) {
    init {
        require(name.isNotBlank()) { "Workspace name must not be blank" }
        require(rootPath.isNotBlank()) { "Workspace rootPath must not be blank" }
    }
}

@JvmInline
value class WorkspaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "WorkspaceId must not be blank" }
    }
}

/** A user-granted Storage Access Framework tree. Not a path; deliberately a separate type. */
data class ExternalWorkspaceRef(
    val treeUri: String,
    val displayName: String,
)

/**
 * Trust gates capability. A freshly cloned repository is [Untrusted] until the user says
 * otherwise, and the decision is asked before the project opens, not before its first command.
 *
 * See docs/adr/ADR-005-agent-permissions.md.
 */
enum class WorkspaceTrust {
    /** Read-only browsing. No command execution, no network, no push, no package installs. */
    Untrusted,

    /** Full capability, still subject to per-operation permission grants. */
    Trusted,
    ;

    val allowsExecution: Boolean get() = this == Trusted
    val allowsNetwork: Boolean get() = this == Trusted
    val allowsGitPush: Boolean get() = this == Trusted
}

/**
 * What kind of project this appears to be, from on-disk evidence only.
 *
 * [Unknown] is a real, expected answer. Detection never guesses: a project is only Laravel if
 * the marker files are actually present.
 */
enum class DetectedFramework {
    Unknown,
    Laravel,
    Php,
    Node,
    Static,
    ;

    val displayName: String
        get() = when (this) {
            Unknown -> "Project"
            Laravel -> "Laravel"
            Php -> "PHP"
            Node -> "Node.js"
            Static -> "Static site"
        }
}
