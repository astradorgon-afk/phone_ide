package dev.mobileforge.core.security

/**
 * Classifies a shell command by how much damage it can do.
 *
 * Deliberately advisory-POSITIVE only: a match escalates the required confirmation, and a
 * non-match NEVER downgrades an operation below its declared permission class. An unrecognised
 * command in an untrusted workspace is still blocked by the permission engine.
 *
 * Treating a pattern matcher as a security boundary would be a mistake; treating it as an
 * extra warning layered on top of one is not. See docs/adr/ADR-005-agent-permissions.md.
 */
object CommandRiskClassifier {

    fun classify(command: String): CommandRisk {
        val normalised = command.trim().lowercase()
        if (normalised.isEmpty()) return CommandRisk.Unknown

        destructivePatterns.firstOrNull { it.pattern.containsMatchIn(normalised) }?.let {
            return CommandRisk.Destructive(it.reason)
        }
        if (packageInstallPatterns.any { it.containsMatchIn(normalised) }) {
            return CommandRisk.PackageInstall
        }
        if (networkPatterns.any { it.containsMatchIn(normalised) }) {
            return CommandRisk.Network
        }
        return CommandRisk.Ordinary
    }

    private data class Destructive(val pattern: Regex, val reason: String)

    private val destructivePatterns = listOf(
        Destructive(
            Regex("""\brm\s+(-[a-z]*\s+)*-[a-z]*[rf]"""),
            "Recursive or forced delete permanently removes files.",
        ),
        Destructive(
            Regex("""\bgit\s+reset\s+--hard"""),
            "This discards uncommitted changes permanently.",
        ),
        Destructive(
            Regex("""\bgit\s+clean\s+-[a-z]*[fd]"""),
            "This deletes untracked files permanently.",
        ),
        Destructive(
            Regex("""\bgit\s+push\b.*(--force\b|\s-f\b)"""),
            "Force push can overwrite commits on the remote for everyone.",
        ),
        Destructive(
            Regex("""\bmigrate:(fresh|reset|rollback)\b"""),
            "This drops or reverts database tables and the data in them.",
        ),
        Destructive(
            Regex("""\bdb:wipe\b"""),
            "This drops all database tables.",
        ),
        Destructive(
            Regex("""\b(drop\s+(table|database)|truncate\s+table)\b"""),
            "This permanently removes database structures or rows.",
        ),
        Destructive(
            Regex("""\bmkfs\b|\bdd\s+if=.*of=/dev/"""),
            "This writes directly to a block device.",
        ),
        Destructive(
            Regex("""\bchmod\s+(-[a-z]+\s+)*777\b"""),
            "This makes files world-writable.",
        ),
    )

    private val packageInstallPatterns = listOf(
        Regex("""\bcomposer\s+(install|update|require|create-project)\b"""),
        Regex("""\bnpm\s+(install|i|ci|add)\b"""),
        Regex("""\b(yarn|pnpm|bun)\s+(install|add)\b"""),
        Regex("""\b(pkg|apt|apt-get|pip|pip3|gem|cargo)\s+(install|add)\b"""),
    )

    private val networkPatterns = listOf(
        Regex("""\b(curl|wget)\b"""),
        Regex("""\bgit\s+(clone|fetch|pull|push)\b"""),
        Regex("""\bssh\b|\bscp\b|\brsync\b"""),
    )
}

sealed interface CommandRisk {

    /** Nothing matched a known-dangerous shape. Still subject to the permission engine. */
    data object Ordinary : CommandRisk

    /** Empty or unparseable. Treated as at least [Ordinary] — never as a safety signal. */
    data object Unknown : CommandRisk

    /**
     * Executes third-party lifecycle scripts. A separate class from [Ordinary] because a user
     * who granted "run commands" has not thereby consented to running arbitrary package
     * maintainer code (RISK-015).
     */
    data object PackageInstall : CommandRisk

    /** Reaches the network. A separate permission from model access. */
    data object Network : CommandRisk

    /** Can destroy work. Always requires an explicit high-risk confirmation. */
    data class Destructive(val reason: String) : CommandRisk

    val requiresExplicitConfirmation: Boolean
        get() = this is Destructive || this is PackageInstall
}
