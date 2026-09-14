package dev.mobileforge.core.common

/**
 * Structured logging contract.
 *
 * SECURITY is a first-class level, not a WARN with a prefix, because security events must be
 * filterable and exportable on their own. Every message passes through [SecretRedactor] before
 * reaching a sink - redaction is enforced here rather than trusted to each call site.
 */
interface Logger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, cause: Throwable? = null)
    fun error(tag: String, message: String, cause: Throwable? = null)

    /** Denied path escape, blocked bridge message, permission refusal, trust change. */
    fun security(tag: String, message: String)
}

enum class LogLevel { Debug, Info, Warn, Error, Security }

/** A logger that discards everything. Default in pure-JVM tests. */
object NoOpLogger : Logger {
    override fun debug(tag: String, message: String) = Unit
    override fun info(tag: String, message: String) = Unit
    override fun warn(tag: String, message: String, cause: Throwable?) = Unit
    override fun error(tag: String, message: String, cause: Throwable?) = Unit
    override fun security(tag: String, message: String) = Unit
}
