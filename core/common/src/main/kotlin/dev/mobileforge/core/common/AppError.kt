package dev.mobileforge.core.common

/**
 * A structured, user-presentable error.
 *
 * [message] is written for a developer using the IDE, not for a stack trace reader.
 * [recovery] is mandatory-by-convention wherever the user can actually do something.
 *
 * Contrast the two error shapes the brief calls out:
 *   bad:  "Command failed"
 *   good: message  = "PHP could not be started."
 *         detail   = "PHP was not found in the active development runtime."
 *         recovery = "Install or configure PHP before starting Laravel."
 */
data class AppError(
    val category: ErrorCategory,
    val message: String,
    val detail: String? = null,
    val recovery: String? = null,
    val cause: Throwable? = null,
    val retryable: Boolean = false,
) {
    init {
        require(message.isNotBlank()) { "AppError.message must not be blank" }
    }
}

enum class ErrorCategory {
    /** The request was structurally invalid before anything was attempted. */
    Validation,

    /** A path escaped its workspace, or a permission was denied. Always logged as SECURITY. */
    Security,

    /** Disk, SAF, encoding, or file-not-found. */
    FileSystem,

    /** A subsystem exists but is not implemented in this phase. Never shown as a crash. */
    NotImplemented,

    /** A required tool or capability is absent on this device. */
    Unavailable,

    /** Process spawn/lifecycle failures (Phase 2+). */
    Process,

    /** Network and provider transport failures (Phase 5+). */
    Network,

    /** Anything genuinely unexpected. */
    Internal,
}

/** Convenience for the very common "this lands in a later phase" case. */
fun notImplemented(feature: String, phase: String): AppError = AppError(
    category = ErrorCategory.NotImplemented,
    message = "$feature is not available yet.",
    detail = "This subsystem is scheduled for $phase and is not implemented in this build.",
    recovery = "See ROADMAP.md for what this phase delivers.",
)
